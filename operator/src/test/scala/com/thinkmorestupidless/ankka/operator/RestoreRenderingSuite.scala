package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaService,
  AnkkaServiceSpec,
  AnkkaSerialization,
  ProjectDatabaseSpec,
  RestoreEntry
}
import com.thinkmorestupidless.ankka.operator.cnpg.{ClusterCondition, ClusterStatus}
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder

import scala.jdk.CollectionConverters.*

/**
 * A restore rendered (feature 041, research R12, R14): a second cluster made from a line of history
 * at a moment, archiving nothing until a service uses it; and a service switched to it, which
 * reaches it by an address on its pod and nothing else.
 */
class RestoreRenderingSuite extends munit.FunSuite:

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
  private val entry = RestoreEntry(
    "ankka-db-r202610081012",
    "ankka-db",
    "2026-10-08T09:20:00Z",
    "2026-10-08T10:12:00Z"
  )

  test(
    "a restore is a cluster beside the project database, recovered from the line at the moment"
  ) {
    val spec = RestoreRendering.cluster("ankka-shop", entry, settings, None, inUse = false).getSpec
    val recovery = spec.bootstrap.flatMap(_.recovery).getOrElse(fail("no recovery"))
    assertEquals(recovery.source, "line")
    assertEquals(recovery.recoveryTarget.flatMap(_.targetTime), Some("2026-10-08T09:20:00Z"))
    // Nothing made after recovery: CNPG's default would add an `app` database to every restore.
    assertEquals(recovery.database, Some("postgres"))
    assertEquals(recovery.owner, Some("postgres"))
    val source = spec.externalClusters.flatMap(_.headOption).getOrElse(fail("no source"))
    assertEquals(source.name, "line")
    assertEquals(source.plugin.map(_.parameters("serverName")), Some("ankka-db"))
    assertEquals(source.plugin.map(_.parameters("barmanObjectName")), Some("ankka-backups"))
    // Every service's certificate is accepted, as by the project database.
    assertEquals(spec.certificates.map(_.clientCASecret), Some(CnpgRendering.clientCaName))
    assertEquals(spec.postgresql.map(_.pgHba), Some(Vector(CnpgRendering.CertificateRule)))
  }

  test("a restore no service uses archives nothing, and one in use archives under its own name") {
    val idle = RestoreRendering.cluster("ankka-shop", entry, settings, None, inUse = false).getSpec
    assertEquals(idle.plugins, None)
    assert(
      !RestoreRendering
        .actions("ankka-shop", entry, settings, None, inUse = false)
        .exists(_.isInstanceOf[Action.EnsureScheduledBackup])
    )
    val used = RestoreRendering.cluster("ankka-shop", entry, settings, None, inUse = true).getSpec
    assertEquals(
      used.plugins.flatMap(_.headOption).map(_.parameters("serverName")),
      Some("ankka-db-r202610081012")
    )
    val schedule = RestoreRendering
      .actions("ankka-shop", entry, settings, None, inUse = true)
      .collectFirst { case Action.EnsureScheduledBackup(s) => s }
      .getOrElse(fail("no schedule"))
    assertEquals(schedule.getSpec.cluster.name, "ankka-db-r202610081012")
  }

  test("a restore starts alone, and takes the project's replicas once in use") {
    val database = Some(ProjectDatabaseSpec(replicas = 2))
    assertEquals(
      RestoreRendering.cluster("ns", entry, settings, database, inUse = false).getSpec.instances,
      1
    )
    assertEquals(
      RestoreRendering.cluster("ns", entry, settings, database, inUse = true).getSpec.instances,
      3
    )
  }

  test("a restore has a network policy of its own, selecting its own instances") {
    val policy = CnpgRendering.databasePolicy("ankka-shop", entry.name)
    assertEquals(policy.getMetadata.getName, "ankka-db-r202610081012-database")
    assertEquals(
      policy.getSpec.getPodSelector.getMatchLabels.asScala.toMap,
      Map("cnpg.io/cluster" -> "ankka-db-r202610081012")
    )
    // The project database's is named as it always was.
    assertEquals(
      CnpgRendering.databasePolicy("ankka-shop").getMetadata.getName,
      "ankka-db-database"
    )
  }

  // ── A switched service ────────────────────────────────────────────────────

  private def resource(spec: AnkkaServiceSpec): AnkkaService =
    val r = new AnkkaService
    r.setMetadata(
      new ObjectMetaBuilder()
        .withName(spec.serviceName)
        .withNamespace("ankka-shop")
        .withUid("u-1")
        .build()
    )
    r.setSpec(spec)
    r

  private val wallet =
    AnkkaServiceSpec(
      projectId = "shop",
      serviceName = "wallet",
      generation = 3L,
      image = "wallet:1"
    )

  private def rendered(spec: AnkkaServiceSpec) =
    Rendering
      .render(resource(spec), Settings.default, ProvisioningPlan.Ready(recovered = true))
      .getOrElse(fail("not rendered"))

  private def deploymentOf(actions: Vector[Action]) =
    actions.collectFirst { case Action.ApplyDeployment(d) => d }.getOrElse(fail("no deployment"))

  test("a switched service is told the restore's address and line on its pod, over the Secret's") {
    val pod = deploymentOf(
      rendered(wallet.copy(databaseCluster = Some(entry.name)))
    ).getSpec.getTemplate.getSpec
    val platform =
      pod.getContainers.asScala.find(_.getName == "wallet").getOrElse(fail("no container"))
    val env = platform.getEnv.asScala.map(e => e.getName -> e.getValue).toMap
    assertEquals(env.get("ANKKA_DB_HOST"), Some("ankka-db-r202610081012-rw"))
    assertEquals(env.get("ANKKA_DB_LINE"), Some("ankka-db-r202610081012"))
    // The credential Secret still comes whole: the literal beats its ANKKA_DB_HOST (spike S2).
    assert(platform.getEnvFrom.asScala.exists(_.getSecretRef.getName == "wallet-db"))
    val schema =
      pod.getInitContainers.asScala.find(_.getName == "ankka-schema").getOrElse(fail("no schema"))
    assert(
      schema.getEnv.asScala.exists(e =>
        e.getName == "ANKKA_DB_HOST" && e.getValue == "ankka-db-r202610081012-rw"
      )
    )
    val ca = pod.getVolumes.asScala.find(_.getName == "ankka-database-ca").getOrElse(fail("no CA"))
    assertEquals(ca.getSecret.getSecretName, "ankka-db-r202610081012-ca")
  }

  test("a service on the project database renders exactly what it did before the feature") {
    val serialization = AnkkaSerialization()
    val pod           = deploymentOf(rendered(wallet)).getSpec.getTemplate.getSpec
    val names = (pod.getContainers.asScala ++ pod.getInitContainers.asScala)
      .flatMap(_.getEnv.asScala.map(_.getName))
    assert(!names.contains("ANKKA_DB_LINE"), names.toString)
    assert(!names.contains("ANKKA_DB_HOST"), names.toString)
    assert(serialization.asJson(pod).contains("\"secretName\":\"ankka-db-ca\""))
  }

  test("the credential Secret is the same object whichever cluster the service is on") {
    def secret(spec: AnkkaServiceSpec) =
      rendered(spec)
        .collectFirst { case Action.EnsureCredentials(s) => s }
        .getOrElse(fail("no Secret"))
    assertEquals(secret(wallet.copy(databaseCluster = Some(entry.name))), secret(wallet))
  }

  test("a restore is verified once healthy, with what each service's database holds") {
    val reads = new ProjectBackupsSuiteReads(
      Some(
        ClusterStatus(
          readyInstances = 1,
          phase = Some("Cluster in healthy state"),
          currentPrimary = Some("ankka-db-r202610081012-1")
        )
      ),
      Map(
        "pg_database"       -> "postgres\nwallet",
        "count(*)"          -> "12|1|4|0|0|12|",
        "max(db_timestamp)" -> "2026-10-08T09:19:58Z"
      )
    )
    val spec = com.thinkmorestupidless.ankka.crd.AnkkaProjectSpec("shop", restores = List(entry))
    val result = ProjectReconciler.restores(
      "ankka-shop",
      spec,
      settings,
      reads,
      Vector("wallet" -> None, "rewards" -> None),
      None
    )
    val status = result.statuses.head
    assertEquals(status.phase, "Verified")
    assertEquals(status.reachedAt, Some("2026-10-08T09:19:58Z"))
    val byName = status.services.map(v => v.name -> v).toMap
    assert(byName("wallet").present)
    assertEquals(byName("wallet").journalRows, 12L)
    assert(!byName("rewards").present, "a service with no database at the moment")
    assert(result.actions.exists(_.isInstanceOf[Action.EnsureCluster]))
  }

  test("a verified restore is not read again, and is in use once a service is switched to it") {
    val reads = new ProjectBackupsSuiteReads(None, Map.empty)
    val spec  = com.thinkmorestupidless.ankka.crd.AnkkaProjectSpec("shop", restores = List(entry))
    val before = com.thinkmorestupidless.ankka.crd.AnkkaProjectStatus(restores =
      List(
        com.thinkmorestupidless.ankka.crd
          .RestoreStatus(entry.name, entry.line, entry.targetTime, "Verified")
      )
    )
    val result = ProjectReconciler.restores(
      "ankka-shop",
      spec,
      settings,
      reads,
      Vector("wallet" -> Some(entry.name), "rewards" -> None),
      Some(before)
    )
    assertEquals(result.statuses.head.phase, "InUse")
    assert(reads.asked.isEmpty, "a verified restore is read once")
    val clusters = result.clusters.map(c => c.name -> c).toMap
    assertEquals(clusters("ankka-db").services, List("rewards"))
    assertEquals(clusters("ankka-db").phase, "live")
    assertEquals(clusters(entry.name).services, List("wallet"))
  }

  test("a project database every service has left is listed as left") {
    val reads = new ProjectBackupsSuiteReads(None, Map.empty)
    val spec  = com.thinkmorestupidless.ankka.crd.AnkkaProjectSpec("shop", restores = List(entry))
    val before = com.thinkmorestupidless.ankka.crd.AnkkaProjectStatus(restores =
      List(
        com.thinkmorestupidless.ankka.crd
          .RestoreStatus(entry.name, entry.line, entry.targetTime, "InUse")
      )
    )
    val result = ProjectReconciler.restores(
      "ankka-shop",
      spec,
      settings,
      reads,
      Vector("wallet" -> Some(entry.name)),
      Some(before)
    )
    assertEquals(result.clusters.find(_.name == "ankka-db").map(_.phase), Some("left"))
  }

/** An executor answering from maps, for the restore and backup passes. */
final class ProjectBackupsSuiteReads(cluster: Option[ClusterStatus], answers: Map[String, String])
    extends Executor:
  val asked                         = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
  def execute(action: Action): Unit = ()
  def snapshot(namespace: String, name: String): Option[ClusterSnapshot]     = None
  def foreignObjectAt(namespace: String, name: String): Boolean              = false
  def podProblems(namespace: String, serviceName: String, projectId: String) = Vector.empty
  def observeRoute(namespace: String, name: String)                          = None
  def observeDatabase(namespace: String, clusterName: String, serviceName: String) =
    cnpg.DatabaseObservation.empty
  def resourceCreatedAt(namespace: String, name: String) = None
  def observeBroker(namespace: String, user: String) = BrokerObservation(StrimziObjectState.absent)
  def observeTopics(namespace: String, topics: Vector[String]) = Map.empty
  def podTemplateLabels(namespace: String, name: String)       = None
  def awaitNoPods(
      namespace: String,
      selector: Map[String, String],
      timeout: scala.concurrent.duration.FiniteDuration
  ) = true
  override def observeBackups(namespace: String, cluster: String, objectStore: String) =
    BackupObservation(this.cluster, None, None)
  override def query(namespace: String, pod: String, database: String, sql: String) =
    asked += pod -> sql
    answers.collectFirst { case (fragment, answer) if sql.contains(fragment) => answer }
  private val _ = ClusterCondition
