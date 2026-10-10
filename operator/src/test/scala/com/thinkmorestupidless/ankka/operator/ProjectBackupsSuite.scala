package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaProjectSpec, ProjectDatabaseSpec}
import com.thinkmorestupidless.ankka.operator.cnpg.{ClusterCondition, ClusterStatus, RecoveryWindow}

import java.time.Instant
import scala.collection.mutable

/**
 * What a project's pass says of its backups and its database (feature 041, research R9), against an
 * executor that answers from maps: what it reads, what it asks the database, and nothing more.
 */
class ProjectBackupsSuite extends munit.FunSuite:

  private val now = Instant.parse("2026-10-08T10:12:00Z")

  private class Reads(
      cluster: Option[ClusterStatus],
      window: Option[RecoveryWindow] = None,
      answers: Map[String, String] = Map.empty
  ) extends Executor:
    val asked                         = mutable.ArrayBuffer.empty[(String, String)]
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
    override def observeBackups(namespace: String, cluster: String, objectStore: String) =
      BackupObservation(this.cluster, window, None)
    override def query(namespace: String, pod: String, database: String, sql: String) =
      asked += pod -> sql
      answers.collectFirst { case (fragment, answer) if sql.contains(fragment) => answer }

  private val store = ObjectStoreSettings(
    "http://garage:3903",
    "token",
    "http://garage:3900",
    "garage",
    ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
  )
  private val backedUp = Settings.default.copy(
    objectStore = Some(store),
    backups = BackupSettings(target = Some(BackupTarget.ObjectStore))
  )

  private val healthy = ClusterStatus(
    instances = 1,
    readyInstances = 1,
    currentPrimary = Some("ankka-db-1"),
    conditions = Vector(ClusterCondition("ContinuousArchiving", "True"))
  )

  test("with no backup target the status says so, and only the instances are read") {
    val reads = Reads(Some(healthy))
    val result = ProjectReconciler.backups(
      "ankka-shop",
      AnkkaProjectSpec("shop"),
      Settings.default,
      reads,
      now
    )
    assertEquals(result.status.map(_.target), Some("none"))
    assertEquals(result.status.flatMap(_.detail), Some("the installation has no backup target"))
    assertEquals(result.database.map(_.readyInstances), Some(1))
    assert(reads.asked.isEmpty, "nothing is asked of a database with nothing to report")
  }

  test("a project with no database yet says so") {
    val result =
      ProjectReconciler.backups("ankka-shop", AnkkaProjectSpec("shop"), backedUp, Reads(None), now)
    assertEquals(result.status.flatMap(_.detail), Some("the project has no database yet"))
    assertEquals(result.database, None)
  }

  test("an archiving project database reports its line, with the lag read on its primary") {
    val reads = Reads(
      Some(healthy),
      Some(RecoveryWindow(Some("2026-09-08T00:00:12Z"), Some("2026-10-08T00:00:12Z"))),
      Map("pg_stat_archiver" -> "12.5|0|")
    )
    val result =
      ProjectReconciler.backups("ankka-shop", AnkkaProjectSpec("shop"), backedUp, reads, now)
    val line = result.status.flatMap(_.lines.headOption).getOrElse(fail("no line"))
    assertEquals(line.phase, BackupStatus.BackingUp)
    assertEquals(line.archiveLagSeconds, Some(12.5))
    assertEquals(reads.asked.map(_._1).toSet, Set("ankka-db-1"))
  }

  test("a synchronous project database with no replica in sync says its writes wait") {
    val spec = AnkkaProjectSpec("shop", database = Some(ProjectDatabaseSpec(2, synchronous = true)))
    val reads = Reads(
      Some(healthy.copy(instances = 3, readyInstances = 2)),
      answers = Map("pg_stat_replication" -> "ankka-db-2|potential")
    )
    val db = ProjectReconciler.backups("ankka-shop", spec, backedUp, reads, now).database.get
    assert(db.synchronous)
    assertEquals(db.instances, 3)
    assert(
      db.writesWaitingOn.exists(_.contains("ankka-db-2 potential")),
      db.writesWaitingOn.toString
    )
    val inSync = Reads(Some(healthy), answers = Map("pg_stat_replication" -> "ankka-db-2|sync"))
    assertEquals(
      ProjectReconciler
        .backups("ankka-shop", spec, backedUp, inSync, now)
        .database
        .get
        .writesWaitingOn,
      None
    )
  }

  test("the status is written only when it says something new") {
    val reads = Reads(Some(healthy), answers = Map("pg_stat_archiver" -> "1|0|"))
    val spec  = AnkkaProjectSpec("shop")
    val once  = ProjectReconciler.backups("ankka-shop", spec, backedUp, reads, now)
    val ref   = ServiceRef("ankka-shop", "shop")
    val first = ProjectReconciler.actions(ref, spec, None, Map.empty, None, once)
    val written = first
      .collectFirst { case Action.SetProjectStatus(_, _, s) => s }
      .getOrElse(fail("not written"))
    val again = ProjectReconciler.actions(ref, spec, None, Map.empty, Some(written), once)
    assert(!again.exists(_.isInstanceOf[Action.SetProjectStatus]), again.toString)
  }

  test(
    "the control plane's database has a bucket and a credential of its own, only with a target"
  ) {
    assertEquals(PlatformBackups.actions(Settings.default), Vector.empty)
    val actions = PlatformBackups.actions(backedUp)
    assertEquals(actions.head, Action.EnsureBucket("platform.backups-controlplane"))
    actions.collectFirst { case a: Action.EnsureBackupCredential => a } match
      case Some(credential) =>
        assertEquals(credential.namespace, "ankka-controlplane")
        assertEquals(credential.permission, BucketPermission.ReadWrite)
      case None => fail("no credential")
  }
