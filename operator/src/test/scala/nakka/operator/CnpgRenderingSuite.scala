package nakka.operator

import nakka.crd.NakkaServiceSpec
import nakka.operator.cnpg.{BootstrapSpec, InitdbSpec}

/**
 * The four objects a provisioned service needs, rendered as pure data — no cluster, no Docker.
 *
 * See
 * [contracts/cnpg-resources.md](../../../../../specs/002-cnpg-database-provisioning/contracts/cnpg-resources.md).
 */
class CnpgRenderingSuite extends munit.FunSuite:

  private val settings = Settings.default

  private val spec = NakkaServiceSpec(
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
    assertEquals(cluster.getMetadata.getNamespace, "nakka-checkout")
    assertEquals(cluster.getMetadata.getName, "nakka-db")
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
      CnpgRendering.controlPlaneCluster("nakka-controlplane", "nakka-controlplane-db", settings)
    assertEquals(cluster.getMetadata.getNamespace, "nakka-controlplane")
    assertEquals(cluster.getMetadata.getName, "nakka-controlplane-db")
    assertEquals(cluster.getSpec.instances, 1)
    assertEquals(
      cluster.getSpec.bootstrap,
      Some(BootstrapSpec(initdb = Some(InitdbSpec(database = "nakka", owner = "nakka"))))
    )
  }

  test("rendering the same spec twice produces identical objects") {
    assertEquals(
      CnpgRendering.databaseRole(spec, "nakka-checkout"),
      CnpgRendering.databaseRole(spec, "nakka-checkout")
    )
  }

  test("a hyphenated service name renders unchanged in every name field — no normalisation") {
    val hyphenated = spec.copy(serviceName = "my-cart")
    val role       = CnpgRendering.databaseRole(hyphenated, "nakka-checkout")
    val database   = CnpgRendering.database(hyphenated, "nakka-checkout")

    assertEquals(role.getMetadata.getName, "my-cart")
    assertEquals(role.getSpec.name, "my-cart")
    assertEquals(database.getMetadata.getName, "my-cart")
    assertEquals(database.getSpec.name, "my-cart")
    assertEquals(database.getSpec.owner, "my-cart")
  }

  test("the database's owner is the role of the same name") {
    val database = CnpgRendering.database(spec, "nakka-checkout")
    assertEquals(database.getSpec.owner, spec.serviceName)
  }

  test("the database references the project's cluster by name") {
    val database = CnpgRendering.database(spec, "nakka-checkout")
    assertEquals(database.getSpec.cluster.name, CnpgRendering.projectClusterName)
  }

  test("database and role reclaim policy is retain, never delete") {
    val database = CnpgRendering.database(spec, "nakka-checkout")
    val role     = CnpgRendering.databaseRole(spec, "nakka-checkout")
    assertEquals(database.getSpec.databaseReclaimPolicy, "retain")
    assertEquals(role.getSpec.databaseRoleReclaimPolicy, "retain")
  }

  test("the role's password secret is named after the service") {
    val role = CnpgRendering.databaseRole(spec, "nakka-checkout")
    assertEquals(role.getSpec.passwordSecret.name, s"${spec.serviceName}-db")
  }

  test("the credential secret carries both basic-auth keys and the NAKKA_DB_* keys") {
    val secret =
      CnpgRendering.credentialSecret(
        spec,
        "nakka-checkout",
        CnpgRendering.projectClusterName,
        "generated-pw"
      )
    val data = secret.getStringData
    assertEquals(data.get("username"), spec.serviceName)
    assertEquals(data.get("password"), "generated-pw")
    assertEquals(data.get("NAKKA_DB_HOST"), s"${CnpgRendering.projectClusterName}-rw")
    assertEquals(data.get("NAKKA_DB_PORT"), "5432")
    assertEquals(data.get("NAKKA_DB_NAME"), spec.serviceName)
    assertEquals(data.get("NAKKA_DB_USER"), spec.serviceName)
    assertEquals(data.get("NAKKA_DB_PASSWORD"), "generated-pw")
  }

  test("the credential secret is basic-auth typed, which DatabaseRole requires") {
    val secret =
      CnpgRendering.credentialSecret(spec, "nakka-checkout", CnpgRendering.projectClusterName, "pw")
    assertEquals(secret.getType, "kubernetes.io/basic-auth")
  }

  test("CNPG objects carry no owner reference to the NakkaService — they must outlive it") {
    // This is the crux of "nothing is ever destroyed": an owner reference would make Kubernetes'
    // own garbage collector delete these the moment the NakkaService goes, regardless of the
    // operator's own withheld delete verb (the GC runs with cluster-level privilege, not the
    // operator's RBAC). No owner reference is what makes retain mean anything.
    val role     = CnpgRendering.databaseRole(spec, "nakka-checkout")
    val database = CnpgRendering.database(spec, "nakka-checkout")
    val secret   = CnpgRendering.credentialSecret(spec, "nakka-checkout", "nakka-db", "pw")

    assert(
      role.getMetadata.getOwnerReferences.isEmpty,
      "DatabaseRole must not be owned by the NakkaService"
    )
    assert(
      database.getMetadata.getOwnerReferences.isEmpty,
      "Database must not be owned by the NakkaService"
    )
    assert(
      secret.getMetadata.getOwnerReferences.isEmpty,
      "the credential secret must not be owned by the NakkaService"
    )
  }

  test("the schema ConfigMap carries all three DDL files, keyed by filename") {
    val configMap = CnpgRendering.schemaConfigMap("nakka-checkout")
    val keys      = configMap.getData.keySet()
    assert(keys.contains("10-journal-postgres.sql"), keys.toString)
    assert(keys.contains("20-projection-postgres.sql"), keys.toString)
    assert(keys.contains("30-timers-postgres.sql"), keys.toString)
  }

  test("the schema ConfigMap is one per project namespace, not per service") {
    assertEquals(
      CnpgRendering.schemaConfigMap("nakka-checkout").getMetadata.getName,
      "nakka-schema"
    )
  }
