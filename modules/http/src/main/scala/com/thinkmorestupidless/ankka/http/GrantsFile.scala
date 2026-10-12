package com.thinkmorestupidless.ankka.http

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray}
import com.thinkmorestupidless.ankka.core.Codecs
import org.slf4j.LoggerFactory

import java.nio.file.{Files, Path}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * The grants that name this service, read from the file the operator renders into the project's
 * ConfigMap (`ANKKA_PROJECT_GRANTS`, feature 040).
 *
 * Checked on access, at most once per `interval`, by the real file's modification time — the
 * kubelet swaps a symlinked directory to update a ConfigMap volume, so the link's own time never
 * changes, exactly as for `RotatingTls`. No thread and no watch: an access that finds the interval
 * passed compares one time, and reads the file only when it moved. A file that cannot be read or
 * parsed keeps the grants last read, and is tried again at the next interval; a missing file is no
 * grants. Every change is announced to the server's listeners, which end what it no longer admits.
 *
 * Of the file's grants it keeps the ones on this service's own routes and methods: a topic grant is
 * the broker's to enforce, and an erasure grant the keyring's.
 */
final class GrantsFile(path: Path, service: String, interval: FiniteDuration) extends Grants:

  private val log  = LoggerFactory.getLogger(getClass)
  private val held = Grants.Mutable(Vector.empty)

  @volatile private var modified: Long  = Long.MinValue
  @volatile private var checkedAt: Long = Long.MinValue

  refresh(force = true)

  def admits(caller: Caller, target: GrantTarget): Boolean =
    refresh(force = false)
    held.admits(caller, target)

  override def onChange(listener: () => Unit): Unit = held.onChange(listener)

  /** What is held now, after a check if one is due. */
  def entries: Vector[GrantEntry] =
    refresh(force = false)
    held.entries

  /**
   * Checks now, whatever the interval says. A server calls this on a timer so a revocation reaches
   * an open socket that nothing else is asking about.
   */
  def check(): Unit = refresh(force = true)

  private def refresh(force: Boolean): Unit =
    val now = System.nanoTime()
    if force || now - checkedAt >= interval.toNanos then
      synchronized {
        if force || now - checkedAt >= interval.toNanos then
          checkedAt = now
          try
            if !Files.exists(path) then
              if modified != Long.MinValue then
                modified = Long.MinValue
                held.set(Vector.empty)
            else
              val time = Files.getLastModifiedTime(path.toRealPath()).toMillis
              if time != modified then
                held.set(GrantsFile.parse(Files.readAllBytes(path), service))
                modified = time
          catch
            case NonFatal(failure) =>
              log.warn(
                "grants file {} could not be read; keeping the grants last read: {}",
                path,
                failure.getMessage
              )
      }

object GrantsFile:

  /** The file's name in the project's ConfigMap, beside `topics.json`. */
  val FileName: String = "grants.json"

  private final case class Entry(
      id: String = "",
      grantee: String = "",
      kind: String = "",
      service: Option[String] = None,
      httpMethod: Option[String] = None,
      path: Option[String] = None,
      method: Option[String] = None
  )
  private final case class File(project: String = "", grants: Vector[Entry] = Vector.empty)

  private given JsonValueCodec[File] = Codecs.make[File]

  /** The file's grants on `service`'s own routes and methods; anything malformed throws. */
  def parse(bytes: Array[Byte], service: String): Vector[GrantEntry] =
    readFromArray[File](bytes).grants
      .filter(_.service.contains(service))
      .flatMap { e =>
        val target = e.kind match
          case "route"  => for m <- e.httpMethod; p <- e.path yield GrantTarget.Route(m, p)
          case "method" => e.method.map(GrantTarget.Method.apply)
          case _        => None
        for
          caller <- Caller.decode(e.grantee).filter {
            case _: Caller.Service | _: Caller.Machine => true
            case _                                     => false
          }
          t <- target
        yield GrantEntry(caller, t)
      }

  /**
   * From `ankka.grants`, when a file is configured, or else beside the project's declarations: the
   * operator renders both into one ConfigMap mounted in one directory, and a deployed service is
   * told only where the declarations are. Naming no new variable is what lets a running service
   * read grants with no change to its pod, so nothing rolls when the platform is upgraded.
   */
  def fromConfig(config: com.typesafe.config.Config, service: String): Option[GrantsFile] =
    def setting(path: String) = if config.hasPath(path) then config.getString(path) else ""
    val file = Option(setting("ankka.grants.file"))
      .filter(_.nonEmpty)
      .orElse(
        Option(setting("ankka.grants.declarations"))
          .filter(_.nonEmpty)
          .map(d => Path.of(d).resolveSibling(FileName).toString)
      )
      .getOrElse("")
    Option.when(file.nonEmpty) {
      val interval = FiniteDuration(
        config.getDuration("ankka.grants.reload-interval").toMillis,
        java.util.concurrent.TimeUnit.MILLISECONDS
      )
      GrantsFile(Path.of(file), service, interval)
    }
