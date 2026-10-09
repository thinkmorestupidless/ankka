package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.{ConfigMapBuilder, ObjectMetaBuilder, SecretBuilder}
import com.thinkmorestupidless.ankka.crd.AnkkaServiceSpec
import com.thinkmorestupidless.ankka.operator.cnpg.*

import scala.jdk.CollectionConverters.*

/**
 * The four objects a provisioned service needs, and the one object a project needs before any of
 * its services can have them. Total, pure and deterministic — no clock, no client, no randomness —
 * for the same reason `Rendering.deployment` is: comparing desired against observed only means
 * anything if rendering is reproducible.
 *
 * Deliberately **no owner reference** to the `AnkkaService` on any of these, unlike the Deployment.
 * An owner reference would make Kubernetes' own garbage collector delete them the moment the
 * `AnkkaService` goes — regardless of the operator's own withheld `delete` verb, since the garbage
 * collector runs with cluster-level privilege, not the operator's RBAC. No owner reference is what
 * makes "nothing is ever destroyed" (FR-023, FR-024) actually true rather than almost true.
 */
object CnpgRendering:

  /**
   * One shared cluster per project. See research R2 for why per-project, not per-service or global.
   */
  val projectClusterName: String = "ankka-db"

  /**
   * Where the schema lands in every project namespace, mounted by every service's schema-init
   * container. One per project, not per service — the schema does not vary by service.
   */
  val schemaConfigMapName: String = "ankka-schema"

  /**
   * The DDL files, named explicitly rather than enumerated off the classpath: listing a "directory"
   * of classpath resources is unreliable across an exploded classes dir and a packaged jar, and the
   * file set is fixed and small.
   */
  private val SchemaFiles =
    Vector(
      "10-journal-postgres.sql",
      "20-projection-postgres.sql",
      "30-timers-postgres.sql",
      "40-secrets-postgres.sql",
      "50-erasure-postgres.sql"
    )

  /**
   * A project's shared Postgres capacity. No `bootstrap.initdb` — its databases arrive as
   * `Database` objects, one per service, never a single named database nobody owns.
   */
  def projectCluster(projectId: String, settings: Settings): PostgresCluster =
    val namespace = Names.namespace(settings.namespacePrefix, projectId)
    PostgresCluster(
      namespace,
      projectClusterName,
      ClusterSpec(
        instances = 1,
        storage = Some(StorageSpec(settings.databaseStorageSize)),
        certificates = Some(
          CertificatesSpec(
            clientCASecret = clientCaName,
            replicationTLSSecret = replicationName
          )
        ),
        postgresql = Some(PostgresqlSpec(pgHba = Vector(CertificateRule))),
        managed = Some(ManagedSpec(roles = Vector(ManagedRole(name = TlsGroup, login = false))))
      )
    )

  /**
   * The group every provisioned role joins (feature 014). Matching the group rather than `all` is
   * what lets a project move one service at a time: a role provisioned before certificates keeps
   * logging in by password, through CNPG's own default rule, until its own next deploy moves it in.
   */
  val TlsGroup: String = "ankka_tls"

  /**
   * CNPG places user rules after its fixed ones and before its password default, so this is the
   * rule that decides for any member of the group: TLS, and a client certificate from the project's
   * database authority whose common name is the role.
   */
  val CertificateRule: String = s"hostssl all +$TlsGroup all cert clientcert=verify-full"

  /**
   * The project's database authority: a root, and the Issuer that signs each service's client
   * certificate.
   */
  val clientCaName: String    = s"$projectClusterName-client-ca"
  val replicationName: String = s"$projectClusterName-replication"

  /**
   * The project-level objects, in the order they depend on each other: the root, the Issuer over
   * it, and the replication certificate CNPG needs once it is told about a client CA. Applied on
   * every pass like the schema, because a project created before feature 014 must gain them and an
   * unchanged server-side apply changes nothing. Not owned by any service, like the cluster.
   */
  def projectAuthority(
      namespace: String
  ): Vector[io.fabric8.kubernetes.api.model.GenericKubernetesResource] =
    def meta(name: String) =
      new ObjectMetaBuilder()
        .withName(name)
        .withNamespace(namespace)
        .withLabels(Map(Labels.ManagedByKey -> Labels.ManagedByAnkka).asJava)
        .build()
    def resource(kind: String, name: String, spec: Map[String, AnyRef]) =
      new io.fabric8.kubernetes.api.model.GenericKubernetesResourceBuilder()
        .withApiVersion("cert-manager.io/v1")
        .withKind(kind)
        .withMetadata(meta(name))
        .withAdditionalProperties(Map[String, AnyRef]("spec" -> spec.asJava).asJava)
        .build()
    def issuerRef(name: String, kind: String) =
      Map("name" -> name, "kind" -> kind, "group" -> "cert-manager.io").asJava
    val rsa = Map[String, AnyRef](
      "algorithm" -> "RSA",
      "size"      -> Integer.valueOf(2048),
      "encoding"  -> "PKCS8"
    ).asJava
    Vector(
      resource(
        "Certificate",
        clientCaName,
        Map(
          "isCA"       -> java.lang.Boolean.TRUE,
          "commonName" -> s"ankka database authority ($namespace)",
          "secretName" -> clientCaName,
          "duration"   -> "87600h",
          "privateKey" -> rsa,
          "issuerRef"  -> issuerRef("ankka-selfsigned", "ClusterIssuer")
        )
      ),
      resource(
        "Issuer",
        ZeroTrust.Database.Issuer,
        Map("ca" -> Map("secretName" -> clientCaName).asJava)
      ),
      resource(
        "Certificate",
        replicationName,
        Map(
          // CNPG's own streaming replica, which it authenticates by certificate from the client CA.
          "commonName"  -> "streaming_replica",
          "secretName"  -> replicationName,
          "usages"      -> List("client auth").asJava,
          "duration"    -> ZeroTrust.Duration,
          "renewBefore" -> ZeroTrust.RenewBefore,
          "privateKey"  -> rsa,
          "issuerRef"   -> issuerRef(ZeroTrust.Database.Issuer, "Issuer")
        )
      )
    )

  val databasePolicyName: String = s"$projectClusterName-database"

  /**
   * Who may connect to a project's database pods (feature 014): the project's own ankka workloads,
   * on 5432; the database's own instances, and the database operator's namespace, on 5432 and on
   * the instance manager's 8000. Another project's workload is refused before any TLS begins, so a
   * stolen client certificate is worth nothing from outside the project. Unowned, like the cluster.
   */
  def databasePolicy(
      namespace: String
  ): io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy =
    import io.fabric8.kubernetes.api.model.{IntOrString, LabelSelectorBuilder}
    import io.fabric8.kubernetes.api.model.networking.v1.*
    def tcp(port: Int) =
      new NetworkPolicyPortBuilder().withProtocol("TCP").withPort(new IntOrString(port)).build()
    def selector(labels: (String, String)*) =
      new LabelSelectorBuilder().withMatchLabels(labels.toMap.asJava).build()
    val instances = "cnpg.io/cluster" -> projectClusterName
    new NetworkPolicyBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withName(databasePolicyName)
          .withNamespace(namespace)
          .withLabels(Map(Labels.ManagedByKey -> Labels.ManagedByAnkka).asJava)
          .build()
      )
      .withSpec(
        new NetworkPolicySpecBuilder()
          .withPodSelector(selector(instances))
          .withPolicyTypes("Ingress")
          .withIngress(
            new NetworkPolicyIngressRuleBuilder()
              .withPorts(tcp(5432))
              .withFrom(
                new NetworkPolicyPeerBuilder()
                  .withPodSelector(selector(Labels.ManagedByKey -> Labels.ManagedByAnkka))
                  .build()
              )
              .build(),
            new NetworkPolicyIngressRuleBuilder()
              .withPorts(tcp(5432), tcp(8000))
              .withFrom(
                new NetworkPolicyPeerBuilder().withPodSelector(selector(instances)).build(),
                new NetworkPolicyPeerBuilder()
                  .withNamespaceSelector(
                    selector("kubernetes.io/metadata.name" -> CnpgNamespace)
                  )
                  .build()
              )
              .build()
          )
          .build()
      )
      .build()

  /** Where the database operator runs; the platform's `cnpg` component installs it there. */
  val CnpgNamespace: String = "cnpg-system"

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
          Some(BootstrapSpec(initdb = Some(InitdbSpec(database = "ankka", owner = "ankka"))))
      )
    )

  def databaseRole(spec: AnkkaServiceSpec, namespace: String): PostgresDatabaseRole =
    PostgresDatabaseRole(
      namespace,
      spec.serviceName,
      DatabaseRoleSpec(
        name = spec.serviceName,
        cluster = ClusterRef(projectClusterName),
        login = true,
        // No password at all (feature 014): the role logs in with a certificate whose common name
        // is its name, through the `ankka_tls` rule, and in no other way.
        databaseRoleReclaimPolicy = "retain",
        disablePassword = Some(true),
        inRoles = Vector(TlsGroup)
      )
    )

  def database(spec: AnkkaServiceSpec, namespace: String): PostgresDatabase =
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
   * Where the service's database is, as the `ANKKA_DB_*` keys its container and its schema-init
   * container read — and, since feature 014, nothing secret at all: the service authenticates with
   * a certificate, so there is no password to generate, store or mount. Still a Secret, and still
   * created only when absent, so a service provisioned before keeps the one it has; its password
   * there simply stops working when its role moves to certificates.
   */
  def credentialSecret(
      spec: AnkkaServiceSpec,
      namespace: String,
      clusterName: String
  ): io.fabric8.kubernetes.api.model.Secret =
    val data = Map(
      "ANKKA_DB_HOST" -> s"$clusterName-rw",
      "ANKKA_DB_PORT" -> "5432",
      "ANKKA_DB_NAME" -> spec.serviceName,
      "ANKKA_DB_USER" -> spec.serviceName
    )
    new SecretBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withNamespace(namespace)
          .withName(credentialSecretName(spec.serviceName))
          .withLabels(Labels.identity(spec.projectId, spec.serviceName).asJava)
          .build()
      )
      .withType("Opaque")
      .withStringData(data.asJava)
      .build()

  /**
   * The schema, published once per project namespace, sourced from the classpath resources under
   * `ankka/ddl` (a directory symlink to `modules/runtime/src/main/resources/ankka/ddl`, the single
   * canonical copy — research R6, verified in feature 001 and again here).
   */
  def schemaConfigMap(namespace: String): io.fabric8.kubernetes.api.model.ConfigMap =
    val data = SchemaFiles.map { name =>
      val stream = getClass.getResourceAsStream(s"/ankka/ddl/$name")
      require(stream != null, s"schema resource /ankka/ddl/$name not found on the classpath")
      try name -> scala.io.Source.fromInputStream(stream).mkString
      finally stream.close()
    }.toMap

    new ConfigMapBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withNamespace(namespace)
          .withName(schemaConfigMapName)
          .withLabels(Map(Labels.ManagedByKey -> Labels.ManagedByAnkka).asJava)
          .build()
      )
      .withData(data.asJava)
      .build()
