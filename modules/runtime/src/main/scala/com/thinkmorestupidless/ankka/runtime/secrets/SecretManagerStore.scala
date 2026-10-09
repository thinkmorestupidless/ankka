package com.thinkmorestupidless.ankka.runtime.secrets

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.{SecretRules, SecretStore}
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import scala.util.control.NonFatal

/**
 * The secret store on the Secret Manager backend: each service secret is a secret in Google Secret
 * Manager under the id `DerivedIds` derives from the project, the service and the name, reached as
 * the service's own identity.
 *
 * The semantics are the database store's. `get` reads the newest enabled version on every call,
 * with no cache, so a value kept on one instance is read by the next `get` on every instance. `put`
 * adds a version — making the secret first when there is none — and then destroys the versions
 * beyond `versionsKept`, oldest first; a version is never disabled, so the newest can always be
 * read. `delete` removes the secret and every version. The rules for names and values are checked
 * before Secret Manager is called, so both backends refuse the same things.
 *
 * Nothing of a value is logged; a name is, at debug.
 */
private[ankka] final class SecretManagerStore(
    client: SecretManager,
    project: String,
    service: String,
    versionsKept: Int,
    location: Option[String] = None,
    kmsKey: Option[String] = None
) extends SecretStore
    with ReadDetail:
  import SecretManagerException.Kind

  private val log = LoggerFactory.getLogger(classOf[SecretManagerStore])

  /**
   * The newest version this instance has seen of each id: how a skipped newer version is noticed.
   */
  private val newestSeen = ConcurrentHashMap[String, java.lang.Long]()

  /** What every id of this service's secrets begins with: what its access is conditioned on. */
  val prefix: String = DerivedIds.servicePrefix(project, service)

  def put(name: String, value: String): Unit =
    SecretRules.check(name, value)
    val id   = DerivedIds.service(project, service, name)
    val data = value.getBytes(StandardCharsets.UTF_8)
    mapped(name, id) {
      val number =
        try client.addVersion(id, data)
        catch
          case e: SecretManagerException if e.kind == Kind.NotFound =>
            client.createSecret(
              id,
              DerivedIds.serviceAnnotations(project, service, name),
              location,
              kmsKey
            ): Unit
            client.addVersion(id, data)
      seen(id, number)
      prune(name, id)
    }
    log.debug("kept the secret '{}' in Secret Manager", name)

  def get(name: String): Option[String] = read(name).value

  def read(name: String): ReadDetail.Read =
    SecretRules.check(name)
    val id = DerivedIds.service(project, service, name)
    mapped(name, id)(client.accessLatest(id)) match
      case None => ReadDetail.Read(None, latestSkipped = false)
      case Some(accessed) =>
        val skipped = Option(newestSeen.get(id)).exists(_.longValue > accessed.version)
        seen(id, accessed.version)
        ReadDetail.Read(Some(String(accessed.data, StandardCharsets.UTF_8)), skipped)

  def delete(name: String): Unit =
    SecretRules.check(name)
    val id = DerivedIds.service(project, service, name)
    mapped(name, id)(client.deleteSecret(id)): Unit
    newestSeen.remove(id): Unit
    log.debug("removed the secret '{}' from Secret Manager", name)

  private def seen(id: String, number: Long): Unit =
    newestSeen.merge(id, number, (a, b) => math.max(a.longValue, b.longValue)): Unit

  /** Destroys what is beyond the kept count. A failure here never fails the keep. */
  private def prune(name: String, id: String): Unit =
    try
      client
        .listEnabledVersions(id)
        .drop(versionsKept)
        .foreach(number => client.destroyVersion(id, number))
    catch
      case NonFatal(e) =>
        log.warn(
          "kept the secret '{}', and could not destroy its older versions: {}",
          name,
          e.getMessage
        )

  private def mapped[A](name: String, id: String)(work: => A): A =
    try work
    catch
      case e: SecretManagerException =>
        e.kind match
          case Kind.Unavailable =>
            log.warn(
              "the secret store could not reach Secret Manager for '{}': {}",
              name,
              e.getMessage
            )
            throw CommandError(
              s"the secret store is unavailable: ${e.getMessage}",
              ErrorCode.Unavailable
            )
          case Kind.Denied =>
            throw CommandError(
              s"this service has not been given access to its secrets in Secret Manager: " +
                s"${e.getMessage}. Its secret access must admit ids beginning '$prefix' " +
                s"(the secret's id is '$id')",
              ErrorCode.Internal
            )
          case _ =>
            throw CommandError(s"the secret store failed: ${e.getMessage}", ErrorCode.Internal)
      case e: CommandError => throw e

/**
 * A store that can say more about a read than its value: whether the newest version it knew of was
 * not the one read, because it had been disabled by hand. The read record carries it.
 */
private[ankka] trait ReadDetail:
  def read(name: String): ReadDetail.Read

private[ankka] object ReadDetail:
  final case class Read(value: Option[String], latestSkipped: Boolean)
