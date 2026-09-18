package nakka.operator.cnpg

import nakka.crd.NakkaSerialization

/**
 * The three CNPG partial models survive a round trip through the same serialization the operator
 * actually uses.
 *
 * No separate serializer to set up here — `nakka.crd.NakkaSerialization()` is Scala-aware Jackson,
 * and the operator's single `KubernetesClient` is built with it for every resource kind it touches,
 * not just `NakkaService`. This suite exists to prove that sharing actually works for a type it was
 * not originally written for.
 */
class CnpgModelsSuite extends munit.FunSuite:

  private val serialization = NakkaSerialization()

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
        Some(BootstrapSpec(initdb = Some(InitdbSpec(database = "nakka", owner = "nakka"))))
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
    val resource = PostgresCluster("nakka-checkout", "nakka-db", ClusterSpec(instances = 1))
    assertEquals(resource.getStatus, null)

    val decoded = serialization.unmarshal(serialization.asJson(resource), classOf[PostgresCluster])
    assertEquals(decoded.getMetadata.getNamespace, "nakka-checkout")
    assertEquals(decoded.getMetadata.getName, "nakka-db")
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
    val spec = DatabaseSpec(name = "my-cart", owner = "my-cart", cluster = ClusterRef("nakka-db"))
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

  test("a DatabaseRole spec round-trips") {
    val spec = DatabaseRoleSpec(
      name = "cart",
      cluster = ClusterRef("nakka-db"),
      login = true,
      passwordSecret = PasswordSecretRef("cart-db")
    )
    assertEquals(
      serialization.unmarshal(serialization.asJson(spec), classOf[DatabaseRoleSpec]),
      spec
    )
  }

  test("DatabaseRole defaults to retain, never delete") {
    assertEquals(DatabaseRoleSpec().databaseRoleReclaimPolicy, "retain")
  }

  test("a DatabaseRole status carrying the transient 'forbidden' message round-trips") {
    // The exact message CNPG produced during planning verification (research R5), preserved
    // here so the classification logic in Provisioning has a real string to match against.
    val message =
      "secrets \"cart-db\" is forbidden: User \"system:serviceaccount:nakka-checkout:nakka-db\" " +
        "cannot get resource \"secrets\" in API group \"\" in the namespace \"nakka-checkout\""
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
