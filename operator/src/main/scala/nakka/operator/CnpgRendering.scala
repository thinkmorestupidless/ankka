package nakka.operator

import io.fabric8.kubernetes.api.model.{ConfigMapBuilder, ObjectMetaBuilder, SecretBuilder}
import nakka.crd.NakkaServiceSpec
import nakka.operator.cnpg.*

import scala.jdk.CollectionConverters.*

/**
 * The four objects a provisioned service needs, and the one object a project needs before any of
 * its services can have them. Total, pure and deterministic — no clock, no client, no randomness —
 * for the same reason `Rendering.deployment` is: comparing desired against observed only means
 * anything if rendering is reproducible.
 *
 * Deliberately **no owner reference** to the `NakkaService` on any of these, unlike the Deployment.
 * An owner reference would make Kubernetes' own garbage collector delete them the moment the
 * `NakkaService` goes — regardless of the operator's own withheld `delete` verb, since the garbage
 * collector runs with cluster-level privilege, not the operator's RBAC. No owner reference is what
 * makes "nothing is ever destroyed" (FR-023, FR-024) actually true rather than almost true.
 */
object CnpgRendering:

  /**
   * One shared cluster per project. See research R2 for why per-project, not per-service or global.
   */
  val projectClusterName: String = "nakka-db"

  /**
   * Where the schema lands in every project namespace, mounted by every service's schema-init
   * container. One per project, not per service — the schema does not vary by service.
   */
  val schemaConfigMapName: String = "nakka-schema"

  /**
   * The three DDL files, named explicitly rather than enumerated off the classpath: listing a
   * "directory" of classpath resources is unreliable across an exploded classes dir and a packaged
   * jar, and the file set is fixed and small.
   */
  private val SchemaFiles =
    Vector("10-journal-postgres.sql", "20-projection-postgres.sql", "30-timers-postgres.sql")

  /**
   * A project's shared Postgres capacity. No `bootstrap.initdb` — its databases arrive as
   * `Database` objects, one per service, never a single named database nobody owns.
   */
  def projectCluster(projectId: String, settings: Settings): PostgresCluster =
    val namespace = Names.namespace(settings.namespacePrefix, projectId)
    PostgresCluster(
      namespace,
      projectClusterName,
      ClusterSpec(instances = 1, storage = Some(StorageSpec(settings.databaseStorageSize)))
    )

  /**
   * The control plane's own cluster — the one case with `bootstrap.initdb`, since it hosts exactly
   * one well-known database rather than one per service.
   */
  def controlPlaneCluster(
      namespace: String,
      clusterName: String,
      settings: Settings
  ): PostgresCluster =
    PostgresCluster(
      namespace,
      clusterName,
      ClusterSpec(
        instances = 1,
        storage = Some(StorageSpec(settings.databaseStorageSize)),
        bootstrap =
          Some(BootstrapSpec(initdb = Some(InitdbSpec(database = "nakka", owner = "nakka"))))
      )
    )

  def databaseRole(spec: NakkaServiceSpec, namespace: String): PostgresDatabaseRole =
    PostgresDatabaseRole(
      namespace,
      spec.serviceName,
      DatabaseRoleSpec(
        name = spec.serviceName,
        cluster = ClusterRef(projectClusterName),
        login = true,
        passwordSecret = PasswordSecretRef(credentialSecretName(spec.serviceName)),
        databaseRoleReclaimPolicy = "retain"
      )
    )

  def database(spec: NakkaServiceSpec, namespace: String): PostgresDatabase =
    PostgresDatabase(
      namespace,
      spec.serviceName,
      DatabaseSpec(
        name = spec.serviceName,
        owner = spec.serviceName,
        cluster = ClusterRef(projectClusterName),
        databaseReclaimPolicy = "retain"
      )
    )

  def credentialSecretName(serviceName: String): String = s"$serviceName-db"

  /**
   * Both the `kubernetes.io/basic-auth` keys `DatabaseRole` requires (`username`/`password`) and
   * the `NAKKA_DB_*` keys the service's own container and its schema-init container read — one
   * secret, one `envFrom`, rather than a join between two.
   */
  def credentialSecret(
      spec: NakkaServiceSpec,
      namespace: String,
      clusterName: String,
      password: String
  ): io.fabric8.kubernetes.api.model.Secret =
    val data = Map(
      "username"          -> spec.serviceName,
      "password"          -> password,
      "NAKKA_DB_HOST"     -> s"$clusterName-rw",
      "NAKKA_DB_PORT"     -> "5432",
      "NAKKA_DB_NAME"     -> spec.serviceName,
      "NAKKA_DB_USER"     -> spec.serviceName,
      "NAKKA_DB_PASSWORD" -> password
    )
    new SecretBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withNamespace(namespace)
          .withName(credentialSecretName(spec.serviceName))
          .withLabels(Labels.identity(spec.projectId, spec.serviceName).asJava)
          .build()
      )
      .withType("kubernetes.io/basic-auth")
      .withStringData(data.asJava)
      .build()

  /**
   * The schema, published once per project namespace, sourced from the classpath resources under
   * `nakka/ddl` (a directory symlink to `modules/runtime/src/main/resources/nakka/ddl`, the single
   * canonical copy — research R6, verified in feature 001 and again here).
   */
  def schemaConfigMap(namespace: String): io.fabric8.kubernetes.api.model.ConfigMap =
    val data = SchemaFiles.map { name =>
      val stream = getClass.getResourceAsStream(s"/nakka/ddl/$name")
      require(stream != null, s"schema resource /nakka/ddl/$name not found on the classpath")
      try name -> scala.io.Source.fromInputStream(stream).mkString
      finally stream.close()
    }.toMap

    new ConfigMapBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withNamespace(namespace)
          .withName(schemaConfigMapName)
          .withLabels(Map(Labels.ManagedByKey -> Labels.ManagedByNakka).asJava)
          .build()
      )
      .withData(data.asJava)
      .build()
