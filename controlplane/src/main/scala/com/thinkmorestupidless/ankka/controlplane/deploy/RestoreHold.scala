package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.api.{RestoreHoldStatus, ServiceDifference}
import com.thinkmorestupidless.ankka.runtime.{AnkkaService, Database, RuntimeExtension, SqlFragment}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import org.slf4j.{Logger, LoggerFactory}

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/**
 * The control plane after its own database was restored (feature 041, research R19).
 *
 * The restore's last step writes a row to `ankka_restore_marker`. A control plane that starts with
 * an unreleased row there is held: its projector writes nothing to the cluster, since what the
 * restored database records is older than what the cluster runs, and projecting it would roll every
 * service back to the restore point. Held, it lists what differs instead, until a platform
 * administrator releases it, which is recorded on the row. Registered first, so the hold is known
 * before the projector starts.
 *
 * `reconcilers` is where a later feature brings what the restore lost back from elsewhere before a
 * release (an erasure log from its bucket); each answers a line for the listing. None exist yet.
 */
final class RestoreHold(
    database: AnkkaService => Database = service => Database()(using service.system),
    val reconcilers: Vector[() => String] = Vector.empty
) extends RuntimeExtension:

  private val log: Logger = LoggerFactory.getLogger("ankka.controlplane.restore")

  def name: String = "restore-hold"

  @volatile private var db: Option[Database]                = None
  @volatile private var marker: Option[RestoreHold.Marker]  = None
  @volatile private var services: Vector[ServiceDifference] = Vector.empty
  @volatile private var topics: Vector[String]              = Vector.empty
  @volatile private var unknown: Vector[String]             = Vector.empty
  @volatile private var reconciled: Vector[String]          = Vector.empty

  def start(service: AnkkaService): Unit =
    val opened = database(service)
    db = Some(opened)
    marker =
      try
        Await.result(
          opened.queryOne(
            SqlFragment.raw(
              "SELECT restored_at, target_time, released_at, released_by FROM ankka_restore_marker " +
                "ORDER BY restored_at DESC LIMIT 1"
            )
          ) { row =>
            def at(column: String) =
              Option(row.get(column, classOf[java.time.OffsetDateTime])).map(_.toInstant)
            RestoreHold.Marker(
              at("restored_at").getOrElse(Instant.EPOCH),
              at("target_time").getOrElse(Instant.EPOCH),
              at("released_at"),
              Option(row.get("released_by", classOf[String]))
            )
          },
          30.seconds
        )
      catch
        case scala.util.control.NonFatal(failure) =>
          log.warn(s"could not read the restore marker; not held: ${failure.getMessage}")
          None
    if held then
      reconciled = reconcilers.map(r => r())
      log.warn(
        s"the control plane's database was restored to ${marker.map(_.targetTime).orNull}: " +
          "projection is held until a platform administrator releases it"
      )

  /** For a test: as if the marker read at start were `m`. */
  private[controlplane] def marked(m: RestoreHold.Marker): Unit = marker = Some(m)

  /** Whether the projector may write nothing to the cluster. */
  def held: Boolean = marker.exists(_.releasedAt.isEmpty)

  private val byService =
    java.util.concurrent.ConcurrentHashMap[(String, String), ServiceDifference]()
  private val byProject = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /** A service the projector would have written: how it differs, or none when it does not. */
  def service(project: String, name: String, difference: Option[ServiceDifference]): Unit =
    difference match
      case Some(d) => byService.put((project, name), d): Unit
      case None    => byService.remove((project, name)): Unit
    import scala.jdk.CollectionConverters.*
    services = byService.values.asScala.toVector.sortBy(d => (d.project, d.service))

  /** A project whose declared topics differ from what the cluster holds, or no longer do. */
  def topics(project: String, differ: Boolean): Unit =
    if differ then byProject.add(project): Unit else byProject.remove(project): Unit
    import scala.jdk.CollectionConverters.*
    topics = byProject.asScala.toVector.sorted

  /** The projects the cluster holds that the database does not know, from the latest sweep. */
  def unknownProjects(projects: Vector[String]): Unit = unknown = projects.sorted

  def status: RestoreHoldStatus =
    RestoreHoldStatus(
      held = held,
      restoredAt = marker.map(_.restoredAt),
      targetTime = marker.map(_.targetTime),
      releasedAt = marker.flatMap(_.releasedAt),
      releasedBy = marker.flatMap(_.releasedBy),
      services = if held then services else Vector.empty,
      topics = if held then topics else Vector.empty,
      unknownProjects = if held then unknown else Vector.empty,
      reconciled = reconciled
    )

  /** Releases the hold, recorded on the marker with who released it; the next sweep projects. */
  def release(by: String): RestoreHoldStatus =
    marker match
      case Some(m) if m.releasedAt.isEmpty =>
        val now = Instant.now()
        db.foreach(opened =>
          Await.result(
            opened.execute(
              SqlFragment.raw(
                "UPDATE ankka_restore_marker SET released_at = now(), released_by = "
              ) ++
                sql"$by" ++ SqlFragment.raw(" WHERE released_at IS NULL")
            ),
            30.seconds
          ): Unit
        )
        marker = Some(m.copy(releasedAt = Some(now), releasedBy = Some(by)))
        log.info(s"projection released by $by")
        status
      case _ => status

object RestoreHold:
  final case class Marker(
      restoredAt: Instant,
      targetTime: Instant,
      releasedAt: Option[Instant],
      releasedBy: Option[String]
  )

  /** A hold nothing holds, for a control plane built without one. */
  val none: RestoreHold = new RestoreHold(_ => throw IllegalStateException("never started"))
