package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProjectSpec,
  AnkkaProjectStatus,
  BackupsStatus,
  LineStatus,
  ProjectDatabaseSpec,
  RehearsalEntry,
  RehearsalStatus
}
import com.thinkmorestupidless.ankka.operator.cnpg.ClusterStatus

import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 * A rehearsal of a restore (feature 041, research R15): the restore's cluster in the project's
 * rehearsal namespace, read with a credential that cannot write, timed, removed, and removed anyway
 * once its time to live passes.
 */
class RehearsalRenderingSuite extends munit.FunSuite:

  private val store = ObjectStoreSettings(
    "http://garage:3903",
    "token",
    "http://garage:3900",
    "garage",
    ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
  )
  private val settings = Settings.default.copy(
    objectStore = Some(store),
    backups = BackupSettings(target = Some(BackupTarget.ObjectStore))
  )
  private val entry =
    RehearsalEntry(
      "ankka-db-x202610090000",
      "ankka-db",
      "2026-10-08T09:20:00Z",
      "2026-10-09T00:00:00Z"
    )
  private val now = Instant.parse("2026-10-09T00:02:00Z")

  /** An executor that answers as a rehearsal's cluster would, and records what it removed. */
  private final class Rehearsing(
      cluster: Option[ClusterStatus],
      answers: Map[String, String] = Map.empty,
      found: Vector[(String, Option[Instant])] = Vector.empty,
      removable: Boolean = true
  ) extends Executor:
    private val reads                 = new ProjectBackupsSuiteReads(cluster, answers)
    val removed                       = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
    def execute(action: Action): Unit = ()
    def snapshot(namespace: String, name: String): Option[ClusterSnapshot]     = None
    def foreignObjectAt(namespace: String, name: String): Boolean              = false
    def podProblems(namespace: String, serviceName: String, projectId: String) = Vector.empty
    def observeRoute(namespace: String, name: String)                          = None
    def observeDatabase(namespace: String, clusterName: String, serviceName: String) =
      cnpg.DatabaseObservation.empty
    def resourceCreatedAt(namespace: String, name: String) = None
    def observeBroker(namespace: String, user: String) = BrokerObservation(
      StrimziObjectState.absent
    )
    def observeTopics(namespace: String, topics: Vector[String]) = Map.empty
    def podTemplateLabels(namespace: String, name: String)       = None
    def awaitNoPods(
        namespace: String,
        selector: Map[String, String],
        timeout: scala.concurrent.duration.FiniteDuration
    ) = true
    // The project's own database, the rehearsal's source, has a primary that answers a recovery
    // point at once: the archive holds the segment the commit was written in.
    override def observeBackups(namespace: String, c: String, objectStore: String) =
      if namespace == "ankka-shop" && c == CnpgRendering.projectClusterName then
        BackupObservation(
          Some(ClusterStatus(1, 1, currentPrimary = Some("ankka-db-1"))),
          None,
          None
        )
      else reads.observeBackups(namespace, c, objectStore)
    override def query(namespace: String, pod: String, database: String, sql: String) =
      sql match
        case RecoveryPoint.Commit                          => Some("812")
        case RecoveryPoint.Switch | RecoveryPoint.Archived => Some("000000010000000000000008")
        case _ => reads.query(namespace, pod, database, sql)
    override def rehearsalClusters(namespace: String) = found
    override def deleteRehearsalCluster(namespace: String, name: String) =
      if removable then
        removed += namespace -> name
        Right(())
      else Left("forbidden")

  private val healthy = Some(
    ClusterStatus(1, 1, Some("Cluster in healthy state"), currentPrimary = Some("ankka-db-x-1"))
  )
  private val holds = Map("pg_database" -> "postgres\nwallet", "count(*)" -> "12|1|4|0|0|12|")

  private def pass(
      executor: Executor,
      spec: AnkkaProjectSpec = AnkkaProjectSpec("shop", rehearsals = List(entry)),
      current: Option[AnkkaProjectStatus] = None,
      backups: Option[BackupsStatus] = None,
      at: Instant = now
  ) =
    ProjectReconciler.rehearsals(
      spec,
      settings,
      executor,
      Vector("wallet" -> None),
      current,
      backups,
      at
    )

  test(
    "a rehearsal's cluster is the restore's, in the rehearsal namespace, expiring after its ttl"
  ) {
    val cluster = RehearsalRendering.cluster("shop", entry, settings)
    assertEquals(cluster.getMetadata.getNamespace, "ankka-shop-rehearsal")
    assertEquals(cluster.getMetadata.getName, "ankka-db-x202610090000")
    assertEquals(
      cluster.getMetadata.getAnnotations.asScala.get(RehearsalRendering.ExpiresAt),
      Some("2026-10-10T00:00:00Z")
    )
    assertEquals(cluster.getSpec.instances, 1)
    assertEquals(cluster.getSpec.plugins, None, "a rehearsal archives nothing")
    assert(cluster.getSpec.bootstrap.flatMap(_.recovery).isDefined)
  }

  test("a rehearsal reads the project's backups with a credential that cannot write") {
    val actions    = RehearsalRendering.actions("shop", entry, settings, 0)
    val credential = actions.collectFirst { case c: Action.EnsureBackupCredential => c }
    assertEquals(credential.map(_.namespace), Some("ankka-shop-rehearsal"))
    assertEquals(credential.map(_.permission), Some(BucketPermission.ReadOnly))
    assertEquals(credential.map(_.bucket), Some("platform.backups.shop"))
    assert(actions.exists(_.isInstanceOf[Action.EnsureObjectStore]))
    assert(actions.exists(_.isInstanceOf[Action.EnsureIssuer]), "the cluster's certificates")
    assertEquals(RehearsalRendering.actions("shop", entry, Settings.default, 0), Vector.empty)
  }

  test("a running rehearsal is rendered and reported running, from when it began") {
    val result = pass(Rehearsing(None))
    assert(result.actions.exists(_.isInstanceOf[Action.EnsureCluster]))
    assertEquals(result.statuses.map(_.outcome), List("Running"))
    assertEquals(result.statuses.head.startedAt, Some(now.toString))
  }

  test("a healthy rehearsal is checked, timed and removed, and its report kept") {
    val executor = Rehearsing(healthy, holds)
    val begun =
      RehearsalStatus(entry.name, entry.targetTime, "Running", Some("2026-10-09T00:00:00Z"))
    val result =
      pass(executor, current = Some(AnkkaProjectStatus(rehearsals = List(begun))))
    val report = result.statuses.head
    assertEquals(report.outcome, "Completed")
    assertEquals(report.elapsedSeconds, Some(120L))
    assert(report.services.exists(v => v.name == "wallet" && v.present))
    assertEquals(executor.removed.toVector, Vector("ankka-shop-rehearsal" -> entry.name))
    assertEquals(result.actions, Vector.empty, "nothing rendered for a rehearsal that ended")
    // The next pass renders nothing and reads nothing: the report is kept as it was.
    val again =
      pass(Rehearsing(None), current = Some(AnkkaProjectStatus(rehearsals = List(report))))
    assertEquals(again.statuses, List(report))
    assertEquals(again.actions, Vector.empty)
  }

  test("a rehearsal whose database could not be removed says so, and goes when its ttl passes") {
    val result = pass(Rehearsing(healthy, holds, removable = false))
    assertEquals(result.statuses.head.outcome, "NotRemoved")
    assert(result.statuses.head.detail.exists(_.contains("time to live")))
    val expired = Rehearsing(
      None,
      found =
        Vector(entry.name -> Some(now.minusSeconds(1)), "ankka-db-x2" -> Some(now.plusSeconds(60)))
    )
    pass(expired, AnkkaProjectSpec("shop"), Some(AnkkaProjectStatus(rehearsals = result.statuses)))
    assertEquals(expired.removed.toVector, Vector("ankka-shop-rehearsal" -> entry.name))
  }

  test("a project set to rehearse every day starts one when a day has passed since the last") {
    val line =
      LineStatus("ankka-db", "ankka-db", "BackingUp", lastRestorable = Some("2026-10-08T23:59:00Z"))
    val backups = Some(BackupsStatus("object-store", List(line)))
    val daily = AnkkaProjectSpec(
      "shop",
      database = Some(ProjectDatabaseSpec(rehearsalSchedule = Some("daily")))
    )
    val started = pass(Rehearsing(None), daily, None, backups)
    assertEquals(started.statuses.map(_.targetTime), List("2026-10-08T23:59:00Z"))
    val recent =
      RehearsalStatus(
        "ankka-db-x1",
        "2026-10-08T00:00:00Z",
        "Completed",
        Some("2026-10-08T12:00:00Z")
      )
    val notYet =
      pass(Rehearsing(None), daily, Some(AnkkaProjectStatus(rehearsals = List(recent))), backups)
    assertEquals(notYet.statuses, List(recent), "less than a day since the last")
  }
