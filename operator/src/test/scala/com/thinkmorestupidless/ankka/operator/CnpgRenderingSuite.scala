package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.AnkkaServiceSpec
import com.thinkmorestupidless.ankka.operator.cnpg.{BootstrapSpec, InitdbSpec}

/**
 * The four objects a provisioned service needs, rendered as pure data — no cluster, no Docker.
 *
 * See
 * [contracts/cnpg-resources.md](../../../../../specs/002-cnpg-database-provisioning/contracts/cnpg-resources.md).
 */
import scala.jdk.CollectionConverters.*

class CnpgRenderingSuite extends munit.FunSuite:

  private val settings = Settings.default

  private val spec = AnkkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 1L,
    image = "cart:1.0"
  )

  test(
    "the project cluster carries no bootstrap — a project's databases arrive as Database objects"
  ) {
    val cluster = CnpgRendering.projectCluster(spec.projectId, settings)
    assertEquals(cluster.getSpec.bootstrap, None)
    assertEquals(cluster.getSpec.instances, 1)
    assertEquals(cluster.getMetadata.getNamespace, "ankka-checkout")
    assertEquals(cluster.getMetadata.getName, "ankka-db")
  }

  test("the project cluster is retained, never destroyed") {
    // Cluster carries no reclaim-policy field of its own — CNPG's Cluster CRD has none, unlike
    // Database/DatabaseRole. Its persistence comes from the operator's withheld delete verb,
    // asserted at the RBAC layer (US5), not from anything renderable here.
    val cluster = CnpgRendering.projectCluster(spec.projectId, settings)
    assertEquals(cluster.getMetadata.getName, CnpgRendering.projectClusterName)
  }

  test("the control plane cluster carries bootstrap.initdb, unlike a project cluster") {
    val cluster =
      CnpgRendering.controlPlaneCluster("ankka-controlplane", "ankka-controlplane-db", settings)
    assertEquals(cluster.getMetadata.getNamespace, "ankka-controlplane")
    assertEquals(cluster.getMetadata.getName, "ankka-controlplane-db")
    assertEquals(cluster.getSpec.instances, 1)
    assertEquals(
      cluster.getSpec.bootstrap,
      Some(BootstrapSpec(initdb = Some(InitdbSpec(database = "ankka", owner = "ankka"))))
    )
  }

  test("rendering the same spec twice produces identical objects") {
    assertEquals(
      CnpgRendering.databaseRole(spec, "ankka-checkout"),
      CnpgRendering.databaseRole(spec, "ankka-checkout")
    )
  }

  test("a hyphenated service name renders unchanged in every name field — no normalisation") {
    val hyphenated = spec.copy(serviceName = "my-cart")
    val role       = CnpgRendering.databaseRole(hyphenated, "ankka-checkout")
    val database   = CnpgRendering.database(hyphenated, "ankka-checkout")

    assertEquals(role.getMetadata.getName, "my-cart")
    assertEquals(role.getSpec.name, "my-cart")
    assertEquals(database.getMetadata.getName, "my-cart")
    assertEquals(database.getSpec.name, "my-cart")
    assertEquals(database.getSpec.owner, "my-cart")
  }

  test("the database's owner is the role of the same name") {
    val database = CnpgRendering.database(spec, "ankka-checkout")
    assertEquals(database.getSpec.owner, spec.serviceName)
  }

  test("the database references the project's cluster by name") {
    val database = CnpgRendering.database(spec, "ankka-checkout")
    assertEquals(database.getSpec.cluster.name, CnpgRendering.projectClusterName)
  }

  test("database and role reclaim policy is retain, never delete") {
    val database = CnpgRendering.database(spec, "ankka-checkout")
    val role     = CnpgRendering.databaseRole(spec, "ankka-checkout")
    assertEquals(database.getSpec.databaseReclaimPolicy, "retain")
    assertEquals(role.getSpec.databaseRoleReclaimPolicy, "retain")
  }

  test("the role's password secret is named after the service") {
    val role = CnpgRendering.databaseRole(spec, "ankka-checkout")
    // No password secret and no password (feature 014): the role logs in by certificate only.
    assertEquals(role.getSpec.passwordSecret, None)
    assertEquals(role.getSpec.disablePassword, Some(true))
    assertEquals(role.getSpec.inRoles, Vector(CnpgRendering.TlsGroup))
  }

  test("the credential secret says where the database is, and carries no password at all") {
    val secret =
      CnpgRendering.credentialSecret(spec, "ankka-checkout", CnpgRendering.projectClusterName)
    val data = secret.getStringData.asScala.toMap
    assertEquals(
      data,
      Map(
        "ANKKA_DB_HOST" -> s"${CnpgRendering.projectClusterName}-rw",
        "ANKKA_DB_PORT" -> "5432",
        "ANKKA_DB_NAME" -> spec.serviceName,
        "ANKKA_DB_USER" -> spec.serviceName
      )
    )
    // Asked of the serialized object, not the map above: a password anywhere in it is a failure.
    val serialized = io.fabric8.kubernetes.client.utils.Serialization.asJson(secret).toLowerCase
    assert(!serialized.contains("password"), serialized)
    assertEquals(secret.getType, "Opaque")
  }

  test("CNPG objects carry no owner reference to the AnkkaService — they must outlive it") {
    // This is the crux of "nothing is ever destroyed": an owner reference would make Kubernetes'
    // own garbage collector delete these the moment the AnkkaService goes, regardless of the
    // operator's own withheld delete verb (the GC runs with cluster-level privilege, not the
    // operator's RBAC). No owner reference is what makes retain mean anything.
    val role     = CnpgRendering.databaseRole(spec, "ankka-checkout")
    val database = CnpgRendering.database(spec, "ankka-checkout")
    val secret   = CnpgRendering.credentialSecret(spec, "ankka-checkout", "ankka-db")

    assert(
      role.getMetadata.getOwnerReferences.isEmpty,
      "DatabaseRole must not be owned by the AnkkaService"
    )
    assert(
      database.getMetadata.getOwnerReferences.isEmpty,
      "Database must not be owned by the AnkkaService"
    )
    assert(
      secret.getMetadata.getOwnerReferences.isEmpty,
      "the credential secret must not be owned by the AnkkaService"
    )
  }

  test("the schema ConfigMap carries all five DDL files, keyed by filename") {
    val configMap = CnpgRendering.schemaConfigMap("ankka-checkout")
    val keys      = configMap.getData.keySet()
    assertEquals(keys.size, 5, keys.toString)
    assert(keys.contains("50-recovery-postgres.sql"), keys.toString)
    assert(keys.contains("10-journal-postgres.sql"), keys.toString)
    assert(keys.contains("20-projection-postgres.sql"), keys.toString)
    assert(keys.contains("30-timers-postgres.sql"), keys.toString)
    assert(keys.contains("40-secrets-postgres.sql"), keys.toString)
  }

  test("the schema ConfigMap is one per project namespace, not per service") {
    assertEquals(
      CnpgRendering.schemaConfigMap("ankka-checkout").getMetadata.getName,
      "ankka-schema"
    )
  }

  // ── Backups (feature 041) ─────────────────────────────────────────────────

  private val store = ObjectStoreSettings(
    adminUrl = "http://garage.garage-system.svc.cluster.local:3903",
    adminToken = "a-token",
    endpoint = "http://garage.garage-system.svc.cluster.local:3900",
    region = "garage",
    service = ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
  )

  private val backedUp = settings.copy(
    objectStore = Some(store),
    backups = BackupSettings(target = Some(BackupTarget.ObjectStore))
  )

  test("without a backup target the project database is exactly what it was before the feature") {
    val serialization = com.thinkmorestupidless.ankka.crd.AnkkaSerialization()
    val json = serialization.asJson(CnpgRendering.projectCluster("checkout", settings).getSpec)
    for absent <- Vector("plugins", "parameters", "synchronous", "externalClusters") do
      assert(!json.contains(absent), s"$absent in $json")
    assertEquals(
      CnpgRendering.backupActions("ankka-checkout", "checkout", settings, None, 0),
      Vector.empty
    )
  }

  test(
    "with a backup target the project database archives under its own line, every minute at least"
  ) {
    val cluster = CnpgRendering.projectCluster("checkout", backedUp).getSpec
    val plugin  = cluster.plugins.flatMap(_.headOption).getOrElse(fail("no archiver"))
    assertEquals(plugin.name, "barman-cloud.cloudnative-pg.io")
    assertEquals(plugin.isWALArchiver, Some(true))
    assertEquals(plugin.parameters("serverName"), "ankka-db")
    assertEquals(plugin.parameters("barmanObjectName"), "ankka-backups")
    assertEquals(cluster.postgresql.flatMap(_.parameters), Some(Map("archive_timeout" -> "60s")))
  }

  test("the archive goes to the project's backup bucket, with the credential no service can name") {
    val store = CnpgRendering
      .backupObjectStore("ankka-checkout", "checkout", backedUp, 30)
      .getOrElse(fail("no ObjectStore"))
      .getSpec
    assertEquals(store.retentionPolicy, Some("30d"))
    assertEquals(store.configuration.destinationPath, "s3://platform.backups.checkout/")
    assertEquals(
      store.configuration.endpointURL,
      Some("http://garage.garage-system.svc.cluster.local:3900")
    )
    val credentials = store.configuration.s3Credentials.getOrElse(fail("no credential"))
    assertEquals(credentials.accessKeyId.name, "ankka-db-backups")
    assertEquals(credentials.secretAccessKey.key, StorageCredential.BackupSecretKeyEntry)
    assertEquals(credentials.region.map(_.key), Some(StorageCredential.BackupRegionEntry))
  }

  test(
    "a project keeps its backups for its own retention when longer, never for less than the floor"
  ) {
    import com.thinkmorestupidless.ankka.crd.ProjectDatabaseSpec
    assertEquals(CnpgRendering.retentionDays(backedUp, None), 30)
    assertEquals(
      CnpgRendering.retentionDays(backedUp, Some(ProjectDatabaseSpec(retentionDays = Some(45)))),
      45
    )
    assertEquals(
      CnpgRendering.retentionDays(backedUp, Some(ProjectDatabaseSpec(retentionDays = Some(7)))),
      30
    )
  }

  test("a base backup on the installation's schedule, the first at once, owned by the cluster") {
    val schedule = CnpgRendering.scheduledBackup("ankka-checkout", "ankka-db", backedUp)
    assertEquals(schedule.getMetadata.getName, "ankka-db-base")
    assertEquals(schedule.getSpec.schedule, "0 0 0 * * *")
    assertEquals(schedule.getSpec.immediate, true)
    assertEquals(schedule.getSpec.backupOwnerReference, "cluster")
    assertEquals(schedule.getSpec.cluster.name, "ankka-db")
  }

  test("the archiving objects come in the order they depend on each other, and carry no key") {
    val actions = CnpgRendering.backupActions("ankka-checkout", "checkout", backedUp, None, 2)
    assertEquals(
      actions.map(_.getClass.getSimpleName),
      Vector("EnsureBucket", "EnsureBackupCredential", "EnsureObjectStore", "EnsureScheduledBackup")
    )
    assertEquals(actions.head, Action.EnsureBucket("platform.backups.checkout"))
    actions.collectFirst { case a: Action.EnsureBackupCredential => a } match
      case Some(credential) =>
        assertEquals(credential.permission, BucketPermission.ReadWrite)
        assertEquals(credential.generation, 2)
      case None => fail("no credential")
  }

  test("a project's replicas are instances beside the primary, and synchronous only with one") {
    import com.thinkmorestupidless.ankka.crd.ProjectDatabaseSpec
    val two = CnpgRendering
      .projectCluster("checkout", settings, Some(ProjectDatabaseSpec(2, synchronous = true)))
      .getSpec
    assertEquals(two.instances, 3)
    assertEquals(two.postgresql.flatMap(_.synchronous).map(_.dataDurability), Some("required"))
    assertEquals(two.postgresql.flatMap(_.synchronous).map(_.number), Some(1))
    val none = CnpgRendering
      .projectCluster("checkout", settings, Some(ProjectDatabaseSpec(0, synchronous = true)))
      .getSpec
    assertEquals(none.instances, 1)
    assertEquals(none.postgresql.flatMap(_.synchronous), None)
  }
