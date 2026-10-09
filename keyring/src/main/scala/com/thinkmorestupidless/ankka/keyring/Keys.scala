package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.core.personal.KeyResult
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, SecretStore}

import scala.collection.concurrent.TrieMap

/**
 * Unwrapped keys, where the keyring uses them: the root key from the keyring's own secret store,
 * made on first start (R9; on Google Cloud, feature 044's wrapping key once it is built), each
 * project's keys unwrapped once and held, a subject's made on its first write and wrapped by its
 * project's key-encryption key before any entity sees it.
 */
final class Keys(secrets: () => SecretStore, client: () => ComponentClient):

  @volatile private var root: Option[Array[Byte]] = None
  private val projects = TrieMap.empty[String, (Array[Byte], Array[Byte])]

  /**
   * The root key: read from the secret store, or made and kept there on the keyring's first start.
   */
  def rootKey: Array[Byte] = root.getOrElse {
    synchronized {
      root.getOrElse {
        val store = secrets()
        val key = store.get(Keys.RootSecret) match
          case Some(text) => java.util.Base64.getDecoder.decode(text)
          case None =>
            val made = Wrapping.newKey()
            store.put(Keys.RootSecret, java.util.Base64.getEncoder.encodeToString(made))
            // Read back, so two instances starting at once agree on whichever was kept.
            java.util.Base64.getDecoder.decode(store.get(Keys.RootSecret).get)
        root = Some(key)
        key
      }
    }
  }

  /** A project's key-encryption key and lookup key. */
  def project(project: String): (Array[Byte], Array[Byte]) =
    projects.getOrElseUpdate(
      project, {
        val r = rootKey
        val offer = ProjectKeys(
          Some(Wrapping.wrap(r, s"kek:$project", Wrapping.newKey())),
          Some(Wrapping.wrap(r, s"lookup:$project", Wrapping.newKey()))
        )
        val kept =
          client().forKeyValueEntity(EntityId(project)).call(ProjectKeyEntity.ensure).invoke(offer)
        (
          Wrapping.unwrap(r, s"kek:$project", kept.kek.get),
          Wrapping.unwrap(r, s"lookup:$project", kept.lookup.get)
        )
      }
    )

  /**
   * A subject's key: made when `create` and none exists, `Destroyed` once erased, `Unknown`
   * otherwise.
   */
  def subject(project: String, subject: String, create: Boolean): KeyResult =
    val (kek, _) = this.project(project)
    val owner    = s"$project/$subject"
    val offer    = Option.when(create)(Wrapping.wrap(kek, owner, Wrapping.newKey()))
    val answer =
      client().forKeyValueEntity(EntityId(owner)).call(SubjectKeyEntity.ask).invoke(AskKey(offer))
    answer.kind match
      case "key"    => KeyResult.Available(Wrapping.unwrap(kek, owner, answer.wrapped.get))
      case "erased" => KeyResult.Destroyed(answer.erasureId.getOrElse(""))
      case _        => KeyResult.Unknown

  def lookupKey(project: String): Array[Byte] = this.project(project)._2

object Keys:
  val RootSecret: String = "root-key"
