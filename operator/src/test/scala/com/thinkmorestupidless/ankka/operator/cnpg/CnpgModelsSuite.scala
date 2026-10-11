package com.thinkmorestupidless.ankka.operator.cnpg

import com.thinkmorestupidless.ankka.crd.AnkkaSerialization

/**
 * The three CNPG partial models survive a round trip through the same serialization the operator
 * actually uses.
 *
 * No separate serializer to set up here — `com.thinkmorestupidless.ankka.crd.AnkkaSerialization()`
 * is Scala-aware Jackson, and the operator's single `KubernetesClient` is built with it for every
 * resource kind it touches, not just `AnkkaService`. This suite exists to prove that sharing
 * actually works for a type it was not originally written for.
 */
class CnpgModelsSuite extends munit.FunSuite:

  private val serialization = AnkkaSerialization()

  test("a Cluster spec for a project (no bootstrap) round-trips, and bootstrap is omitted") {
    val spec = ClusterSpec(instances = 1, storage = Some(StorageSpec("2Gi")), bootstrap = None)
    val json = serialization.asJson(spec)

    assert(!json.contains("bootstrap"), s"an absent bootstrap must not appear at all: $json")
    assertEquals(serialization.unmarshal(json, classOf[ClusterSpec]), spec)
  }

  test("a Cluster spec for the control plane (with bootstrap.initdb) round-trips") {
    val spec = ClusterSpec(
      instances = 1,
      storage = Some(StorageSpec("1Gi")),
      bootstrap =
        Some(BootstrapSpec(initdb = Some(InitdbSpec(database = "ankka", owner = "ankka"))))
    )
    assertEquals(serialization.unmarshal(serialization.asJson(spec), classOf[ClusterSpec]), spec)
  }

  test("a Cluster missing fields decodes to defaults, since CNPG owns fields we do not send") {
    val sparse  = """{"instances": 3}"""
    val decoded = serialization.unmarshal(sparse, classOf[ClusterSpec])
    assertEquals(decoded.instances, 3)
    assertEquals(decoded.storage, None)
  }

  test("a whole Cluster resource round-trips with a null status, not a default one") {
    val resource = PostgresCluster("ankka-checkout", "ankka-db", ClusterSpec(instances = 1))
    assertEquals(resource.getStatus, null)

    val decoded = serialization.unmarshal(serialization.asJson(resource), classOf[PostgresCluster])
    assertEquals(decoded.getMetadata.getNamespace, "ankka-checkout")
    assertEquals(decoded.getMetadata.getName, "ankka-db")
    assertEquals(decoded.getSpec, ClusterSpec(instances = 1))
    assertEquals(decoded.getStatus, null)
  }

  test("Cluster identity is derived from its own annotations") {
    assertEquals(PostgresCluster.identity.kind, "Cluster")
    assertEquals(PostgresCluster.identity.plural, "clusters")
    assertEquals(PostgresCluster.identity.apiVersion, "postgresql.cnpg.io/v1")
    assertEquals(PostgresCluster.identity.crdName, "clusters.postgresql.cnpg.io")
  }

  test("a Database spec round-trips, including a hyphenated name") {
    val spec = DatabaseSpec(name = "my-cart", owner = "my-cart", cluster = ClusterRef("ankka-db"))
    assertEquals(serialization.unmarshal(serialization.asJson(spec), classOf[DatabaseSpec]), spec)
  }

  test("Database defaults to retain, never delete") {
    assertEquals(DatabaseSpec().databaseReclaimPolicy, "retain")
  }

  test("a Database status with a transient message round-trips") {
    val status = DatabaseStatus(applied = false, message = Some("role \"cart\" does not exist"))
    assertEquals(
      serialization.unmarshal(serialization.asJson(status), classOf[DatabaseStatus]),
      status
    )
  }

  test("Database identity is derived from its own annotations") {
    assertEquals(PostgresDatabase.identity.kind, "Database")
    assertEquals(PostgresDatabase.identity.crdName, "databases.postgresql.cnpg.io")
  }

  test("a DatabaseRole spec round-trips, the pre-certificate shape and today's") {
    val old = DatabaseRoleSpec(
      name = "cart",
      cluster = ClusterRef("ankka-db"),
      login = true,
      passwordSecret = Some(PasswordSecretRef("cart-db"))
    )
    val current = DatabaseRoleSpec(
      name = "cart",
      cluster = ClusterRef("ankka-db"),
      login = true,
      disablePassword = Some(true),
      inRoles = Vector("ankka_tls")
    )
    for spec <- Vector(old, current) do
      assertEquals(
        serialization.unmarshal(serialization.asJson(spec), classOf[DatabaseRoleSpec]),
        spec
      )
    // An absent password secret is absent on the wire, not an empty name CNPG would go looking for.
    assert(!serialization.asJson(current).contains("passwordSecret"), serialization.asJson(current))
  }

  test("a Cluster spec with the TLS fields round-trips, and pg_hba keeps CNPG's field name") {
    val spec = ClusterSpec(
      certificates = Some(CertificatesSpec("ankka-db-client-ca", "ankka-db-replication")),
      postgresql = Some(PostgresqlSpec(Vector("hostssl all +ankka_tls all cert"))),
      managed = Some(ManagedSpec(Vector(ManagedRole("ankka_tls"))))
    )
    val json = serialization.asJson(spec)
    assert(json.contains("\"pg_hba\""), json)
    assertEquals(serialization.unmarshal(json, classOf[ClusterSpec]), spec)
  }

  test("DatabaseRole defaults to retain, never delete") {
    assertEquals(DatabaseRoleSpec().databaseRoleReclaimPolicy, "retain")
  }

  test("a DatabaseRole status carrying the transient 'forbidden' message round-trips") {
    // The exact message CNPG produced during planning verification (research R5), preserved
    // here so the classification logic in Provisioning has a real string to match against.
    val message =
      "secrets \"cart-db\" is forbidden: User \"system:serviceaccount:ankka-checkout:ankka-db\" " +
        "cannot get resource \"secrets\" in API group \"\" in the namespace \"ankka-checkout\""
    val status = DatabaseRoleStatus(applied = false, message = Some(message))
    assertEquals(
      serialization.unmarshal(serialization.asJson(status), classOf[DatabaseRoleStatus]),
      status
    )
  }

  test("DatabaseRole identity is derived from its own annotations") {
    assertEquals(PostgresDatabaseRole.identity.kind, "DatabaseRole")
    assertEquals(PostgresDatabaseRole.identity.crdName, "databaseroles.postgresql.cnpg.io")
  }

  // ── Feature 041: the archiver, recovery, replicas, and what is read back ──

  test("a Cluster with the archiver, a restore's source and synchronous replication round-trips") {
    val plugin = PluginConfiguration(
      name = BarmanObjectStore.Plugin,
      parameters = Map("barmanObjectName" -> "ankka-backups", "serverName" -> "ankka-db")
    )
    val spec = ClusterSpec(
      instances = 3,
      postgresql = Some(
        PostgresqlSpec(
          pgHba = Vector("hostssl all +ankka_tls all cert clientcert=verify-full"),
          parameters = Some(Map("archive_timeout" -> "60s")),
          synchronous = Some(SynchronousSpec())
        )
      ),
      plugins = Some(Vector(plugin.copy(isWALArchiver = Some(true)))),
      bootstrap = Some(
        BootstrapSpec(recovery =
          Some(
            RecoverySpec(
              source = "line",
              database = Some("postgres"),
              owner = Some("postgres"),
              recoveryTarget = Some(RecoveryTarget(targetTime = Some("2026-10-08T09:20:00Z")))
            )
          )
        )
      ),
      externalClusters = Some(Vector(ExternalCluster("line", Some(plugin))))
    )
    val json = serialization.asJson(spec)
    assertEquals(serialization.unmarshal(json, classOf[ClusterSpec]), spec)
    for expected <- Vector(
        "\"isWALArchiver\":true",
        "\"dataDurability\":\"required\"",
        "\"method\":\"any\"",
        "\"archive_timeout\":\"60s\"",
        "\"targetTime\":\"2026-10-08T09:20:00Z\""
      )
    do assert(json.contains(expected), s"$expected not in $json")
    // An external cluster's plugin is not the archiver, and says nothing about it.
    assertEquals("isWALArchiver".r.findAllIn(json).size, 1, json)
  }

  test("a project's Cluster with none of feature 041's fields renders none of them") {
    val json =
      serialization.asJson(ClusterSpec(postgresql = Some(PostgresqlSpec(pgHba = Vector("rule")))))
    for absent <- Vector("plugins", "externalClusters", "parameters", "synchronous", "recovery") do
      assert(!json.contains(absent), s"$absent should not appear in: $json")
  }

  test("a Cluster's status as CNPG writes it decodes the phase, the primary and the conditions") {
    val status = serialization.unmarshal(
      """{"instances":3,"readyInstances":2,"phase":"Cluster in healthy state","phaseReason":"",
        |"currentPrimary":"ankka-db-1","targetPrimary":"ankka-db-1","timelineID":2,
        |"instanceNames":["ankka-db-1","ankka-db-2","ankka-db-3"],
        |"conditions":[{"type":"ContinuousArchiving","status":"False",
        |"reason":"ContinuousArchivingFailing",
        |"message":"unexpected failure invoking barman-cloud-wal-archive: exit status 4",
        |"lastTransitionTime":"2026-10-08T10:00:00Z"},
        |{"type":"Ready","status":"True","reason":"ClusterIsReady","message":"Cluster is Ready"}],
        |"certificates":{"expirations":{}},"latestGeneratedNode":3}""".stripMargin,
      classOf[ClusterStatus]
    )
    assertEquals(status.instances, 3)
    assertEquals(status.readyInstances, 2)
    assertEquals(status.phase, Some("Cluster in healthy state"))
    assertEquals(status.currentPrimary, Some("ankka-db-1"))
    val timeline: Int = status.timelineID.get
    assertEquals(timeline, 2)
    assertEquals(status.instanceNames.size, 3)
    val archiving = status.conditions.find(_.`type` == "ContinuousArchiving").get
    assertEquals(archiving.status, "False")
    assert(archiving.message.exists(_.contains("barman-cloud-wal-archive")))
  }

  test("an ObjectStore renders as the plugin reads it, and its recovery window decodes") {
    val spec = BarmanObjectStoreSpec(
      retentionPolicy = Some("30d"),
      configuration = BarmanConfiguration(
        destinationPath = "s3://platform.backups.shop/",
        endpointURL = Some("http://garage.garage-system.svc.cluster.local:3900"),
        s3Credentials = Some(
          S3Credentials(
            SecretKeyRef("ankka-db-backups", "ACCESS_KEY_ID"),
            SecretKeyRef("ankka-db-backups", "ACCESS_SECRET_KEY"),
            Some(SecretKeyRef("ankka-db-backups", "REGION"))
          )
        ),
        wal = Some(WalConfiguration(Some("lz4"), Some(2))),
        data = Some(DataConfiguration(Some("lz4"), Some(2)))
      )
    )
    val json = serialization.asJson(spec)
    assertEquals(serialization.unmarshal(json, classOf[BarmanObjectStoreSpec]), spec)
    assert(
      !json.contains("serverName"),
      s"the plugin requires the resource's serverName empty: $json"
    )
    val status = serialization.unmarshal(
      """{"serverRecoveryWindow":{"ankka-db":{"firstRecoverabilityPoint":"2026-09-08T00:00:12Z",
        |"lastSuccessfulBackupTime":"2026-10-08T00:00:12Z",
        |"lastFailedBackupTime":"2026-10-01T00:00:09Z"}}}""".stripMargin,
      classOf[BarmanObjectStoreStatus]
    )
    assertEquals(
      status.serverRecoveryWindow("ankka-db").lastSuccessfulBackupTime,
      Some("2026-10-08T00:00:12Z")
    )
    assertEquals(BarmanObjectStore.identity.apiVersion, "barmancloud.cnpg.io/v1")
    assertEquals(BarmanObjectStore.identity.plural, "objectstores")
  }

  test(
    "a ScheduledBackup is the plugin's, immediate and the cluster's; a Backup's status decodes"
  ) {
    val json = serialization.asJson(ScheduledBackupSpec(cluster = ClusterRef("ankka-db")))
    for expected <- Vector(
        "\"schedule\":\"0 0 0 * * *\"",
        "\"immediate\":true",
        "\"backupOwnerReference\":\"cluster\"",
        "\"method\":\"plugin\"",
        "\"name\":\"barman-cloud.cloudnative-pg.io\""
      )
    do assert(json.contains(expected), s"$expected not in $json")
    val backup = serialization.unmarshal(
      """{"phase":"failed","startedAt":"2026-10-08T00:00:00Z","error":"can't upload: AccessDenied",
        |"method":"plugin"}""".stripMargin,
      classOf[BackupStatus]
    )
    assertEquals(backup.phase, Some("failed"))
    assert(backup.error.exists(_.contains("AccessDenied")))
    assertEquals(PostgresScheduledBackup.identity.plural, "scheduledbackups")
    assertEquals(PostgresBackup.identity.plural, "backups")
  }
