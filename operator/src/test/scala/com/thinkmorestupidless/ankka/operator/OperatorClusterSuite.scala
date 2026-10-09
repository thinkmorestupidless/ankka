package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import com.thinkmorestupidless.ankka.crd.{
  AnkkaSerialization,
  AnkkaService,
  AnkkaServiceSpec,
  CloudResource,
  CloudResourceStatus
}
import com.thinkmorestupidless.ankka.operator.cnpg.{
  PostgresCluster,
  PostgresDatabase,
  PostgresDatabaseRole
}
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import org.slf4j.LoggerFactory

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * The operator against a real API server.
 *
 * Everything a fake cannot prove lives here, and each case is a mistake that would otherwise ship:
 * a spec Kubernetes rejects, a selector that makes the second apply permanently fail, an owner
 * reference that does not actually cascade, and RBAC that lets the operator rewrite desired state.
 *
 * Disable with `-Dankka.cluster.tests=off`; everything else in the module runs offline.
 */
class OperatorClusterSuite extends munit.FunSuite:

  // 8, not 6: CNPG's own install and startup, plus the realistic first-service provisioning
  // window in tests 12-14 (up to 170s, per research R5), pushed this past the original budget.
  override val munitTimeout: FiniteDuration = 8.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  private val Image = "rancher/k3s:v1.35.1-k3s1"
  // This build's own sample, by the tag the build gives it: the operator's tests cannot see `core`'s
  // BuildInfo, so the build passes the name. `:latest` is shared with every session on the machine.
  private val SampleImage =
    sys.props.getOrElse("ankka.sample.image", fail("the build sets ankka.sample.image"))
  private val Prefix    = "ankka"
  private val Project   = "checkout"
  private val Namespace = s"$Prefix-$Project"
  private val Service   = "cart"

  private var k3s: K3sContainer        = null
  private var client: KubernetesClient = null
  private var operator: Operator       = null

  private val settings =
    Settings.default.copy(resyncInterval = 2.seconds, baseDomain = Some("test.local"))

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(Image))
      k3s.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("k3s")))
      k3s.start()

      client = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()

      // From the shipped manifest, not an inline copy: a CRD that does not install cannot
      // pass CI, which is the same rule the runtime's DDL follows.
      val crd = getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")
      client.load(crd).serverSideApply(): Unit

      // The installation's authorities (feature 014): the operator asks cert-manager for every
      // workload's certificates, and a pod starts only once they are issued.
      PkiStack.install(k3s, client)

      // CloudNativePG too, from its pinned release manifest — the same one
      // kustomization/components/cnpg references — so a manifest mismatch between "what CI
      // tests against" and "what deploy-local.sh installs" cannot happen silently.
      client
        .load(
          java.net.URI
            .create(
              "https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.30/releases/cnpg-1.30.0.yaml"
            )
            .toURL
            .openStream()
        )
        .serverSideApply(): Unit

      // The Gateway API's CRDs (feature 005) — the standard channel of the version Envoy Gateway
      // v1.9.1 bundles — so the operator's routes can be written and read. No controller: these
      // cases are about the objects the operator renders, not about traffic, which is
      // ExposureClusterSuite's job.
      client
        .load(
          java.net.URI
            .create(
              "https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.6.1/standard-install.yaml"
            )
            .toURL
            .openStream()
        )
        .serverSideApply(): Unit

      waitFor(60.seconds)(
        client
          .apiextensions()
          .v1()
          .customResourceDefinitions()
          .list()
          .getItems
          .asScala
          .map(_.getMetadata.getName)
          .toSet
          .intersect(
            Set(
              "ankkaservices.ankka.thinkmorestupidless.com",
              "httproutes.gateway.networking.k8s.io"
            )
          )
          .size == 2
      )
      waitFor(90.seconds) {
        val d = client
          .apps()
          .deployments()
          .inNamespace("cnpg-system")
          .withName("cnpg-controller-manager")
          .get()
        d != null && Option(d.getStatus).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
      }

      // A real ankka image, because since feature 004 readiness means cluster membership: a
      // placeholder like pause can never be Ready again, by design (FR-022). Every case below that
      // waits for Ready deploys this.
      ClusterImages.importInto(k3s, SampleImage)

      operator = new Operator(client, settings, ServiceReconciler(client, settings))
      operator.start()

  override def afterAll(): Unit =
    if operator != null then operator.close()
    if client != null then client.close()
    if k3s != null then k3s.stop()

  private def waitFor(timeout: FiniteDuration)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(250)
    if !passed then fail(s"condition did not hold within $timeout\n${podLogs()}")

  /** The last lines of every pod in the namespace, for a failure that only a pod can explain. */
  private def podLogs(): String =
    try
      client
        .pods()
        .inNamespace(Namespace)
        .list()
        .getItems
        .asScala
        .map { p =>
          val name = p.getMetadata.getName
          // A pod that never started has no log, so its phase and the reason it is not scheduled
          // or not ready ("0/1 nodes are available: 1 Insufficient cpu") are the explanation.
          val conditions = p.getStatus.getConditions.asScala
            .filter(c => c.getStatus != "True")
            .map(c => s"${c.getType}: ${c.getReason} ${Option(c.getMessage).getOrElse("")}".trim)
          val state = (p.getStatus.getPhase +: conditions).mkString("; ")
          val log = Option(p.getSpec.getContainers.get(0).getName)
            .map(c =>
              scala.util
                .Try(
                  client
                    .pods()
                    .inNamespace(Namespace)
                    .withName(name)
                    .inContainer(c)
                    .tailingLines(40)
                    .getLog
                )
                .getOrElse("(no log)")
            )
            .getOrElse("")
          s"--- $name ($state)\n$log"
        }
        .mkString("\n")
    catch case e: Exception => s"(could not read pod logs: $e)"

  /**
   * The CPU a workload that runs no JVM requests (busybox, pause). A request is reserved whether or
   * not it is used, and requests equal limits, so at the default 500m every idle service held half
   * a CPU: on a four-CPU CI runner the services earlier cases leave behind took the node, and the
   * three- and five-instance cases' pods stayed Pending.
   */
  private val IdleCpu = 50

  private def spec(generation: Long = 1L, image: String = "busybox:1.36", paused: Boolean = false) =
    AnkkaServiceSpec(
      projectId = Project,
      serviceName = Service,
      generation = generation,
      paused = paused,
      image = image,
      cpuMillis = IdleCpu,
      progressDeadlineSeconds = 30,
      // These are feature 001's own tests, about Deployment rendering and lifecycle — not
      // database provisioning. The escape hatch keeps them independent of CNPG being ready and
      // of a schema-init container that has nothing to do with what they assert on. The
      // provisioned path gets its own tests and its own service name, below.
      provisionDatabase = false
    )

  private def resources = client.resources(classOf[AnkkaService])

  private def write(s: AnkkaServiceSpec): Unit =
    // The namespace has to exist before a namespaced resource can go in it; the operator
    // creates project namespaces, but the resource lands first.
    if client.namespaces().withName(Namespace).get() == null then
      client
        .namespaces()
        .resource(
          new io.fabric8.kubernetes.api.model.NamespaceBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(Namespace).build())
            .build()
        )
        .serverSideApply(): Unit

    // Always a fresh object, never one fetched from the server. A resource read back
    // carries metadata.managedFields, and server-side apply rejects a payload that has
    // them — "metadata.managedFields must be nil". The production client builds fresh for
    // the same reason; this helper has to as well.
    resources
      .inNamespace(Namespace)
      .resource(AnkkaService(Namespace, Service, s))
      .fieldManager("ankka-test")
      .forceConflicts()
      .serverSideApply(): Unit

  private def deployment = Option(
    client.apps().deployments().inNamespace(Namespace).withName(Service).get()
  )

  private def statusOf = Option(resources.inNamespace(Namespace).withName(Service).get())
    .flatMap(r => Option(r.getStatus))

  test("1. a rendered deployment is accepted by a real API server") {
    write(spec())
    waitFor(90.seconds)(deployment.isDefined)

    val d = deployment.get
    assertEquals(d.getSpec.getReplicas.intValue, 1)
    assertEquals(d.getSpec.getTemplate.getSpec.getContainers.get(0).getImage, "busybox:1.36")
    assertEquals(d.getMetadata.getLabels.get(Labels.ManagedByKey), "ankka")
  }

  test("2. a second apply at a new generation is accepted, so the selector is immutable-safe") {
    // The regression test for putting a changing value in spec.selector: the API server
    // rejects a selector change permanently, so this would brick the service at generation 2.
    write(spec(generation = 2L, image = "busybox:1.37"))
    waitFor(90.seconds)(
      deployment.exists(
        _.getSpec.getTemplate.getSpec.getContainers.get(0).getImage == "busybox:1.37"
      )
    )
  }

  test("3. the generation reaches the Deployment's own metadata, not the pod template") {
    waitFor(60.seconds)(
      deployment.exists(_.getMetadata.getAnnotations.get(Labels.GenerationKey) == "2")
    )
    assert(
      !deployment.get.getSpec.getTemplate.getMetadata.getAnnotations
        .containsKey(Labels.GenerationKey)
    )
  }

  test("4. no autoscaler is created") {
    val hpas = client
      .autoscaling()
      .v2()
      .horizontalPodAutoscalers()
      .inNamespace(Namespace)
      .list()
      .getItems
      .asScala
    assertEquals(hpas.size, 0, "an HPA would scale past one replica and corrupt the journal")
  }

  test("5. an out-of-band edit to the deployment is reverted") {
    val edited = deployment.get
    edited.getSpec.getTemplate.getSpec.getContainers.get(0).setImage("nginx:evil")
    client.resource(edited).update(): Unit

    waitFor(90.seconds)(
      deployment.exists(
        _.getSpec.getTemplate.getSpec.getContainers.get(0).getImage == "busybox:1.37"
      )
    )
  }

  test("6. an out-of-band deletion of the deployment is repaired") {
    client.apps().deployments().inNamespace(Namespace).withName(Service).delete(): Unit
    waitFor(90.seconds)(deployment.isDefined)
  }

  test("7. the operator reports a status") {
    waitFor(90.seconds)(statusOf.exists(_.generation == 2L))
    assert(statusOf.exists(s => s.lifecycle.nonEmpty), s"expected a lifecycle, got ${statusOf}")
  }

  test("8. pausing scales to zero and keeps the configuration") {
    write(spec(generation = 3L, image = "busybox:1.37", paused = true))
    waitFor(90.seconds)(deployment.exists(_.getSpec.getReplicas.intValue == 0))
    assertEquals(
      deployment.get.getSpec.getTemplate.getSpec.getContainers.get(0).getImage,
      "busybox:1.37"
    )
    waitFor(60.seconds)(statusOf.exists(_.lifecycle == "Paused"))
  }

  test("9. resuming restores the instance") {
    write(spec(generation = 4L, image = "busybox:1.37"))
    waitFor(90.seconds)(deployment.exists(_.getSpec.getReplicas.intValue == 1))
  }

  test("10. an unpullable image is reported Failed with a reason an operator can act on") {
    write(spec(generation = 5L, image = "registry.invalid/nope:doesnotexist"))
    waitFor(120.seconds)(
      statusOf.exists(s => s.lifecycle == "Failed" || s.detail.exists(_.contains("Pull")))
    )
    val detail = statusOf.flatMap(_.detail).getOrElse("")
    assert(detail.nonEmpty, "a failure with no reason is the silence this feature exists to remove")
  }

  private val DbService = "with-db"

  private def dbSpec(generation: Long = 1L) = AnkkaServiceSpec(
    projectId = Project,
    serviceName = DbService,
    generation = generation,
    image =
      SampleImage, // a real runtime on its provisioned database — pause can no longer be Ready
    // Generous, matching SC-002's own 3-minute budget for a project's *first* service: CNPG's
    // secret-RBAC-allowlist propagation alone can take 20-40s (research R5), on top of the
    // cluster's own startup time. A tight deadline here does not test provisioning — it tests
    // whether Kubernetes' rollout timeout loses a race against CNPG's, and a first cut of this
    // test did exactly that (progressDeadlineSeconds=60 failed with ProgressDeadlineExceeded
    // before provisioning had a chance to finish).
    progressDeadlineSeconds = 170
    // provisionDatabase defaults to true — this is the whole point of these two tests.
  )

  private def writeDb(s: AnkkaServiceSpec): Unit =
    resources
      .inNamespace(Namespace)
      .resource(AnkkaService(Namespace, s.serviceName, s))
      .fieldManager("ankka-test")
      .forceConflicts()
      .serverSideApply(): Unit

  private def dbDeployment = Option(
    client.apps().deployments().inNamespace(Namespace).withName(DbService).get()
  )

  private def dbStatusOf = Option(resources.inNamespace(Namespace).withName(DbService).get())
    .flatMap(r => Option(r.getStatus))

  test("12. a project's Cluster is created lazily on first service and reaches ready") {
    writeDb(dbSpec())
    waitFor(120.seconds)(
      Option(
        client
          .resources(classOf[PostgresCluster])
          .inNamespace(Namespace)
          .withName(CnpgRendering.projectClusterName)
          .get()
      ).flatMap(c => Option(c.getStatus)).exists(_.readyInstances >= 1)
    )
  }

  test("13. a service with no database configuration reaches Ready on its own database") {
    // The transient window (research R4, R5) must be survived, never reported as Failed — this
    // is asserted throughout the wait, not just at the end, since the bug it guards produces a
    // permanent-looking failure that then disappears.
    val deadline     = System.nanoTime() + 180.seconds.toNanos
    var reachedReady = false
    while !reachedReady && System.nanoTime() < deadline do
      dbStatusOf.foreach { s =>
        assert(
          s.lifecycle != "Failed",
          s"must never report Failed during provisioning; got detail: ${s.detail}"
        )
        reachedReady = s.lifecycle == "Ready"
      }
      if !reachedReady then Thread.sleep(500)
    assert(reachedReady, s"did not reach Ready within 180 seconds; last status: $dbStatusOf")

    assertEquals(dbStatusOf.flatMap(_.database).map(_.phase), Some("Provisioned"))
    assert(
      Option(
        client.resources(classOf[PostgresDatabase]).inNamespace(Namespace).withName(DbService).get()
      ).isDefined,
      "expected a Database object for the service"
    )
    assert(
      Option(
        client
          .resources(classOf[PostgresDatabaseRole])
          .inNamespace(Namespace)
          .withName(DbService)
          .get()
      ).isDefined,
      "expected a DatabaseRole object for the service"
    )

    val d = dbDeployment.getOrElse(fail("expected a Deployment for the provisioned service"))
    val container = d.getSpec.getTemplate.getSpec.getContainers.get(0)
    assert(
      container.getEnvFrom.asScala.exists(_.getSecretRef.getName == s"$DbService-db"),
      "the service's own container must get its credentials from the generated secret"
    )
    assertEquals(
      d.getSpec.getTemplate.getSpec.getInitContainers.size,
      1,
      "expected the schema-init container"
    )
  }

  test("14. ten reconciles of an unchanged, already-provisioned service write nothing new") {
    // FR-008 / SC-010: idempotence. A steady-state service must not touch the credential, the
    // role or the database again — regenerating a password under a running service on a timer
    // is the one mistake this feature cannot afford to make.
    val secretBefore = client.secrets().inNamespace(Namespace).withName(s"$DbService-db").get()
    val resourceVersionBefore = secretBefore.getMetadata.getResourceVersion
    // The secret key likewise: made once, and a second key under a service that has kept secrets
    // with the first would make all of them unreadable.
    val keyBefore =
      client.secrets().inNamespace(Namespace).withName(s"$DbService-secret-key").get()
    assert(keyBefore != null, "the service's secret key was made")

    (1 to 10).foreach { _ =>
      writeDb(dbSpec()) // identical spec, same generation — a genuine no-op apply
      Thread.sleep(300)
    }
    Thread.sleep(3000) // let a few resync/reconcile passes actually happen

    val secretAfter = client.secrets().inNamespace(Namespace).withName(s"$DbService-db").get()
    assertEquals(
      secretAfter.getMetadata.getResourceVersion,
      resourceVersionBefore,
      "the credential secret must not be rewritten at all in steady state"
    )
    val keyAfter = client.secrets().inNamespace(Namespace).withName(s"$DbService-secret-key").get()
    assertEquals(
      keyAfter.getMetadata.getResourceVersion,
      keyBefore.getMetadata.getResourceVersion,
      "the secret key must not be rewritten at all in steady state"
    )
  }

  private val DbService2 = "with-db-2"

  private def dbSpec2(generation: Long = 1L) = AnkkaServiceSpec(
    projectId = Project,
    serviceName = DbService2,
    generation = generation,
    image = SampleImage,
    progressDeadlineSeconds = 170
  )

  private def dbStatusOf2 = Option(resources.inNamespace(Namespace).withName(DbService2).get())
    .flatMap(r => Option(r.getStatus))

  /**
   * Runs `psql` inside the shared Postgres pod as `postgres`, the way a human operator would with
   * `kubectl exec`. This is what makes isolation a measured fact rather than an assumed one — the
   * same discipline research R9 required during planning.
   */
  private def psqlAsPostgres(sql: String): (Int, String) = execInPostgres(
    Seq("psql", "-U", "postgres", "-tA", "-c", sql)
  )

  /**
   * Logs in as `role` the only way a provisioned role can (feature 014): TLS verified against the
   * cluster's server authority, presenting the role's own client certificate. Run inside the
   * Postgres pod with the certificate copied in once — so it still works after the service, and the
   * Certificate object it owns, have been deleted, which is what a data-survives check needs.
   */
  private def psqlAs(role: String, database: String, sql: String): (Int, String) =
    installCertificate(role)
    val dir = s"/controller/ankka-test-certs/$role"
    execInPostgres(
      Seq(
        "psql",
        s"host=ankka-db-rw port=5432 user=$role dbname=$database sslmode=verify-full " +
          s"sslrootcert=$dir/ca.crt sslcert=$dir/tls.crt sslkey=$dir/tls.key",
        "-tA",
        "-c",
        sql
      )
    )

  private val installedCertificates = scala.collection.mutable.Set.empty[String]

  private def installCertificate(role: String): Unit =
    if !installedCertificates.contains(role) then
      waitFor(120.seconds)(
        Option(
          client.secrets().inNamespace(Namespace).withName(s"$role-database-tls").get()
        ).isDefined
      )
      val client_ = client.secrets().inNamespace(Namespace).withName(s"$role-database-tls").get()
      val server  = client.secrets().inNamespace(Namespace).withName("ankka-db-ca").get()
      def put(name: String, b64: String) =
        val (code, out) = execInPostgres(
          Seq(
            "sh",
            "-c",
            s"mkdir -p /controller/ankka-test-certs/$role && echo '$b64' | base64 -d > /controller/ankka-test-certs/$role/$name && chmod 600 /controller/ankka-test-certs/$role/$name"
          )
        )
        assertEquals(code, 0, out)
      put("tls.crt", client_.getData.get("tls.crt"))
      put("tls.key", client_.getData.get("tls.key"))
      put("ca.crt", server.getData.get("ca.crt"))
      installedCertificates += role

  private def execInPostgres(command: Seq[String]): (Int, String) =
    val out = new java.io.ByteArrayOutputStream()
    val watch = client
      .pods()
      .inNamespace(Namespace)
      .withName("ankka-db-1")
      .inContainer("postgres")
      .writingOutput(out)
      .writingError(out)
      .exec(command*)
    val code =
      try watch.exitCode().get(30, java.util.concurrent.TimeUnit.SECONDS)
      finally watch.close()
    (
      Option(code).map(_.intValue).getOrElse(-1),
      out.toString(java.nio.charset.StandardCharsets.UTF_8)
    )

  test("15. a second service in the same project reuses the existing Cluster") {
    writeDb(dbSpec2())
    waitFor(120.seconds)(dbStatusOf2.exists(_.lifecycle == "Ready"))

    val clusters = client
      .resources(classOf[PostgresCluster])
      .inNamespace(Namespace)
      .list()
      .getItems
      .asScala
    assertEquals(clusters.size, 1, "one project must share one Cluster across its services")
  }

  test("15a. a deployed service's secret key is 32 random bytes, its own, and owned by nothing") {
    def key(service: String) =
      def read = client.secrets().inNamespace(Namespace).withName(s"$service-secret-key").get()
      waitFor(60.seconds)(read != null)
      read
    val first  = key(DbService)
    val second = key(DbService2)
    // `data` is the API server's base64 of what was written: the key as the pod receives it, which is
    // itself base64 of the 32 bytes.
    val asGiven = String(java.util.Base64.getDecoder.decode(first.getData.get("key")))
    assertEquals(java.util.Base64.getDecoder.decode(asGiven).length, 32, asGiven)
    assertNotEquals(first.getData.get("key"), second.getData.get("key"), "each service has its own")
    assert(first.getMetadata.getOwnerReferences.isEmpty, "it must outlive the resource")
    val container = client
      .apps()
      .deployments()
      .inNamespace(Namespace)
      .withName(DbService)
      .get()
      .getSpec
      .getTemplate
      .getSpec
      .getContainers
      .get(0)
    val ref = container.getEnv.asScala.find(_.getName == "ANKKA_SECRET_KEY").get
    assertEquals(ref.getValueFrom.getSecretKeyRef.getName, s"$DbService-secret-key")
  }

  test("16. each service's own database is reachable with its own certificate") {
    val (code, out) = psqlAs(DbService, DbService, "select current_database();")
    assertEquals(code, 0, out)
    assertEquals(out.trim, DbService)
  }

  test("16a. the session is TLS, authenticated by certificate, and no password exists anywhere") {
    // Feature 014: what the database itself says about the service's session.
    val (code, out) = psqlAs(
      DbService,
      DbService,
      "select ssl, client_dn from pg_stat_ssl where pid = pg_backend_pid();"
    )
    assertEquals(code, 0, out)
    assertEquals(out.trim, s"t|/CN=$DbService")
    // The credential Secret carries where the database is and nothing secret.
    val data =
      client.secrets().inNamespace(Namespace).withName(s"$DbService-db").get().getData.asScala
    assert(!data.keySet.exists(_.toLowerCase.contains("password")), data.keySet.toString)
    // The role has no password to log in with.
    val (roleCode, rolePassword) =
      psqlAsPostgres(s"select rolpassword is null from pg_authid where rolname = '$DbService';")
    assertEquals(roleCode, 0, rolePassword)
    assertEquals(rolePassword.trim, "t")
  }

  test("16b. one service's certificate does not log in as another service's role") {
    installCertificate(DbService)
    val dir = s"/controller/ankka-test-certs/$DbService"
    val (code, out) = execInPostgres(
      Seq(
        "psql",
        s"host=ankka-db-rw port=5432 user=$DbService2 dbname=$DbService2 sslmode=verify-full " +
          s"sslrootcert=$dir/ca.crt sslcert=$dir/tls.crt sslkey=$dir/tls.key",
        "-tA",
        "-c",
        "select 1;"
      )
    )
    assertNotEquals(code, 0, out)
    assert(out.contains("certificate authentication failed"), out)
  }

  test(
    "17. cross-service CONNECT is refused in both directions — the property research R9 exists for"
  ) {

    val (code12, out12) = psqlAs(DbService, DbService2, "select 1;")
    assertNotEquals(
      code12,
      0,
      s"expected $DbService to be refused CONNECT to $DbService2's database: $out12"
    )
    assert(out12.contains("CONNECT"), out12)

    val (code21, out21) = psqlAs(DbService2, DbService, "select 1;")
    assertNotEquals(
      code21,
      0,
      s"expected $DbService2 to be refused CONNECT to $DbService's database: $out21"
    )
    assert(out21.contains("CONNECT"), out21)
  }

  test("18. each service's timers table exists independently, not shared") {
    // The concrete case that motivated this feature (SC-005): ankka_timers has no service
    // column, so two services sharing a database would delete each other's timers. Separate
    // databases make that structurally impossible — confirmed here, not assumed.
    val (code, out) = psqlAsPostgres(
      "select datname from pg_database where datname in ('with-db', 'with-db-2') order by 1;"
    )
    assertEquals(code, 0, out)
    assertEquals(out.trim.linesIterator.toVector, Vector("with-db", "with-db-2"))
    val (c1, o1) = psqlAs(DbService, DbService, "select count(*) from ankka_timers;")
    val (c2, o2) = psqlAs(DbService2, DbService2, "select count(*) from ankka_timers;")
    assertEquals(c1, 0, o1)
    assertEquals(c2, 0, o2)
  }

  test("19. deleting a service preserves its database and data; re-applying it recovers both") {
    val (createCode, createOut) =
      psqlAs(
        DbService2,
        DbService2,
        "create table scratch_marker(id int); insert into scratch_marker values (42);"
      )
    assertEquals(createCode, 0, createOut)
    val keyUid = client
      .secrets()
      .inNamespace(Namespace)
      .withName(s"$DbService2-secret-key")
      .get()
      .getMetadata
      .getUid

    resources.inNamespace(Namespace).withName(DbService2).delete(): Unit
    waitFor(120.seconds)(
      Option(client.apps().deployments().inNamespace(Namespace).withName(DbService2).get()).isEmpty
    )

    // No owner reference ties a Database, a DatabaseRole or a credential secret to the
    // AnkkaService that requested them (CnpgRendering's deliberate design) — deleting the
    // resource must not cascade to any of them, unlike the Deployment above.
    assert(
      Option(
        client
          .resources(classOf[PostgresDatabase])
          .inNamespace(Namespace)
          .withName(DbService2)
          .get()
      ).isDefined,
      "the Database must survive deleting the AnkkaService that requested it"
    )
    val (survivedCode, survivedOut) =
      psqlAs(DbService2, DbService2, "select id from scratch_marker;")
    assertEquals(survivedCode, 0, survivedOut)
    assertEquals(
      survivedOut.trim,
      "42",
      "the data itself, not just the Database object, must survive"
    )

    writeDb(dbSpec2())
    waitFor(180.seconds)(dbStatusOf2.exists(_.lifecycle == "Ready"))
    assertEquals(dbStatusOf2.flatMap(_.database).map(_.recovered), Some(true))
    assertEquals(dbStatusOf2.flatMap(_.database).map(_.phase), Some("Recovered"))

    val (recoveredCode, recoveredOut) =
      psqlAs(DbService2, DbService2, "select id from scratch_marker;")
    assertEquals(recoveredCode, 0, recoveredOut)
    assertEquals(
      recoveredOut.trim,
      "42",
      "the same row, from before the delete, not a fresh database"
    )
    // And the same secret key, so what the service kept before the delete is readable after it.
    assertEquals(
      client
        .secrets()
        .inNamespace(Namespace)
        .withName(s"$DbService2-secret-key")
        .get()
        .getMetadata
        .getUid,
      keyUid,
      "the secret key must survive the delete and be the one the re-applied service names"
    )
  }

  test("20. the shipped RBAC, both ways: a withheld verb is refused, a granted one really works") {
    // Applied from the shipped manifest, same discipline as the CRD and CNPG above: a ClusterRole
    // that does not actually grant what the operator needs (or grants more than it should) cannot
    // pass this suite. Only the RBAC objects are extracted — the manifest's own Deployment is not
    // applied, since this suite already runs the operator in-process against the admin kubeconfig.
    val rbacObjects = client
      .load(getClass.getResourceAsStream("/ankka/install/operator.yaml"))
      .items()
      .asScala
      .filter(o =>
        Set("Namespace", "ServiceAccount", "ClusterRole", "ClusterRoleBinding").contains(o.getKind)
      )
    rbacObjects.foreach(o => client.resource(o).serverSideApply(): Unit)

    val tokenResult =
      k3s.execInContainer(
        "kubectl",
        "create",
        "token",
        "ankka-operator",
        "-n",
        "ankka-operator",
        "--duration=10m"
      )
    assertEquals(tokenResult.getExitCode, 0, tokenResult.getStderr)
    val token = tokenResult.getStdout.trim

    val restricted = new KubernetesClientBuilder()
      .withConfig(
        new io.fabric8.kubernetes.client.ConfigBuilder()
          .withMasterUrl(client.getConfiguration.getMasterUrl)
          .withTrustCerts(true)
          .withOauthToken(token)
          .build()
      )
      .withKubernetesSerialization(AnkkaSerialization())
      .build()
    try
      val ex = intercept[io.fabric8.kubernetes.client.KubernetesClientException] {
        restricted
          .resources(classOf[PostgresDatabase])
          .inNamespace(Namespace)
          .withName(DbService2)
          .delete(): Unit
      }
      assertEquals(
        ex.getCode,
        403,
        s"expected the API server itself to refuse the delete: ${ex.getMessage}"
      )

      // The operator cannot read a Secret back — a credential it wrote, or a certificate's key —
      // because its grant on Secrets has no `get`. Refused by the API server, not merely unused.
      val read = intercept[io.fabric8.kubernetes.client.KubernetesClientException] {
        restricted.secrets().inNamespace(Namespace).withName(s"$DbService2-db").get(): Unit
      }
      assertEquals(
        read.getCode,
        403,
        s"expected the API server to refuse the read: ${read.getMessage}"
      )

      // The other direction, and the only test that can catch a *missing* grant: everything else
      // here runs the operator on the admin kubeconfig, so a ClusterRole with no `services` rule
      // — which is what shipped until feature 003 looked — passes every other case and fails on
      // the first real deploy. Driven through the real executor rather than a bare create,
      // because the executor writes by server-side apply: that is a PATCH, and "create without
      // patch" is precisely the mistake this ClusterRole has made before.
      //
      // In a namespace the in-process operator does not watch, so the two cannot race.
      val probeNamespace = "rbac-probe"
      client
        .namespaces()
        .resource(
          new io.fabric8.kubernetes.api.model.NamespaceBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(probeNamespace).build())
            .build()
        )
        .serverSideApply(): Unit
      val probeSpec = AnkkaServiceSpec(
        projectId = "probe",
        serviceName = "probe",
        generation = 1L,
        image = "registry.k8s.io/pause:3.9",
        cpuMillis = IdleCpu,
        provisionDatabase = false,
        port = Some(80)
      )
      val owner = client
        .resources(classOf[AnkkaService])
        .inNamespace(probeNamespace)
        .resource(AnkkaService(probeNamespace, "probe", probeSpec))
        .create()
      val asOperator = new Fabric8Executor(restricted)
      def probeService =
        Option(client.services().inNamespace(probeNamespace).withName("probe").get())

      asOperator.execute(
        Action.EnsureService(Rendering.service(owner, probeSpec, probeNamespace, 80))
      )
      assert(probeService.isDefined, "the operator's own ServiceAccount could not create a Service")

      // Applying again is an update by PATCH of an object that now exists.
      asOperator.execute(
        Action.EnsureService(Rendering.service(owner, probeSpec, probeNamespace, 80))
      )

      asOperator.execute(Action.RemoveService(probeNamespace, "probe", "not-the-owner"))
      assert(
        probeService.isDefined,
        "RemoveService removed a Service for an owner it does not have"
      )

      asOperator.execute(Action.RemoveService(probeNamespace, "probe", owner.getMetadata.getUid))
      waitFor(30.seconds)(probeService.isEmpty)

      // A database credential under the same identity, with no `get`: the first `create` writes it,
      // and another process — an operator restarted — meets it as a conflict and changes nothing.
      val credential = CnpgRendering.credentialSecret(probeSpec, probeNamespace, "ankka-db")
      asOperator.execute(Action.EnsureCredentials(credential))
      def credentialVersion =
        client
          .secrets()
          .inNamespace(probeNamespace)
          .withName("probe-db")
          .get()
          .getMetadata
          .getResourceVersion
      val written = credentialVersion
      new Fabric8Executor(restricted).execute(Action.EnsureCredentials(credential))
      assertEquals(credentialVersion, written, "an existing credential was rewritten")

      // Feature 004: the identity objects, under the same real identity. Kubernetes refuses a
      // Role granting what its creator does not hold — the operator holds pods:get/list/watch,
      // so this must succeed with no escalate/bind. Twice, because the second is a PATCH.
      for _ <- 1 to 2 do
        asOperator.execute(
          Action.EnsureServiceAccount(Rendering.serviceAccount(owner, probeSpec, probeNamespace))
        )
        asOperator.execute(Action.EnsureRole(Rendering.peersRole(owner, probeSpec, probeNamespace)))
        asOperator.execute(
          Action.EnsureRoleBinding(Rendering.peersRoleBinding(owner, probeSpec, probeNamespace))
        )
      assert(client.serviceAccounts().inNamespace(probeNamespace).withName("probe").get() != null)
      assert(
        client.rbac().roles().inNamespace(probeNamespace).withName("probe-peers").get() != null
      )
      assert(
        client
          .rbac()
          .roleBindings()
          .inNamespace(probeNamespace)
          .withName("probe-peers")
          .get() != null
      )

      // Feature 034: a storage credential, under the same real identity. Its Secret is written with
      // `create` alone, a conflict is how an existing one is learned of, and no `get` is ever sent —
      // so this holds with the grant as it is and after the grant loses `get`.
      val root =
        Iterator
          .iterate(java.nio.file.Paths.get("").toAbsolutePath)(_.getParent)
          .find(p => java.nio.file.Files.exists(p.resolve("build.sbt")))
          .get
      val store = ObjectStoreStack.install(k3s, client, root)
      try
        val garage = GarageStore(store.settings.adminUrl, store.settings.adminToken)
        val labels = Labels.identity("probe", "probe")
        val credential =
          Action.EnsureStorageCredential(probeNamespace, "probe-storage", labels, "probe.probe")
        def secret = client.secrets().inNamespace(probeNamespace).withName("probe-storage").get()
        val first  = new Fabric8Executor(restricted, store = Some(garage))
        for _ <- 1 to 2 do
          first.execute(Action.EnsureBucket("probe.probe"))
          first.execute(credential)
        assert(secret != null, "the operator's own ServiceAccount could not write a credential")
        assertEquals(
          secret.getData.keySet.asScala.toSet,
          Set(StorageCredential.AccessKeyEntry, StorageCredential.SecretKeyEntry)
        )
        val written = (secret.getMetadata.getUid, secret.getMetadata.getResourceVersion)
        // Another process — an operator restarted — meets the Secret by its conflict, and changes
        // nothing: the credential a running service holds is made once.
        val restarted = new Fabric8Executor(restricted, store = Some(garage))
        restarted.execute(Action.EnsureBucket("probe.probe"))
        restarted.execute(credential)
        assertEquals((secret.getMetadata.getUid, secret.getMetadata.getResourceVersion), written)
        assertEquals(
          garage.keysNamed("probe.probe").size,
          1,
          "a key was left whose secret no Secret holds"
        )
        assert(
          secret.getMetadata.getOwnerReferences == null || secret.getMetadata.getOwnerReferences.isEmpty
        )
      finally store.close()
    finally restricted.close()
  }

  // --- Feature 003: a deployed service can be reached -------------------------------------

  private val WebService   = "web"
  private val DeafService  = "deaf"
  private val QuietService = "quiet"

  /** The sample on its own provisioned database: the only kind of image that can be Ready now. */
  private def webSpec(
      generation: Long = 1L,
      port: Option[Int] = Some(9000),
      instances: Int = 1
  ) = AnkkaServiceSpec(
    projectId = Project,
    serviceName = WebService,
    generation = generation,
    image = SampleImage,
    progressDeadlineSeconds = 170,
    port = port,
    autoscaling = com.thinkmorestupidless.ankka.crd.AutoscalingSpec(minInstances = instances)
  )

  private def statusNamed(name: String) =
    Option(resources.inNamespace(Namespace).withName(name).get()).flatMap(r => Option(r.getStatus))

  private def serviceNamed(name: String) =
    Option(client.services().inNamespace(Namespace).withName(name).get())

  test("21. a service with a port gets an address that really routes to it") {
    writeDb(webSpec())
    waitFor(150.seconds)(statusNamed(WebService).exists(_.lifecycle == "Ready"))

    val service = serviceNamed(WebService).getOrElse(fail("expected a Service named after it"))
    assertEquals(service.getSpec.getType, "ClusterIP")
    assertEquals(service.getSpec.getPorts.get(0).getPort.intValue, 9000)

    // The selector really matches the pod: an Endpoints object with a ready address is the
    // cluster's own statement of that, not ours.
    waitFor(30.seconds) {
      val endpoints = client.endpoints().inNamespace(Namespace).withName(WebService).get()
      endpoints != null && endpoints.getSubsets.asScala.exists(!_.getAddresses.isEmpty)
    }

    // Through the Service's name, from inside a pod with a platform identity: Service ->
    // endpoints -> pod, the real path. A port-forward would go API server -> pod and bypass the
    // Service, passing with a broken selector. Since feature 014 the port is mutual TLS and
    // admits only workloads carrying a platform identity, so the request is made from the
    // service's own pod, presenting its certificate, and verifies the name it reached.
    val pod = client
      .pods()
      .inNamespace(Namespace)
      .withLabel(Labels.NameKey, WebService)
      .list()
      .getItems
      .asScala
      .head
      .getMetadata
      .getName
    val (code, body) = InPod.curl(
      k3s,
      Namespace,
      pod,
      s"https://$WebService.$Namespace.svc.cluster.local:9000/carts/reach"
    )
    assertEquals(code, 200, body)
    assert(body.contains("\"cartId\":\"reach\""), body)

    // And plain HTTP from the node reaches nothing: no certificate, and no platform identity.
    val ip    = service.getSpec.getClusterIP
    val plain = k3s.execInContainer("wget", "-qO-", "-T", "5", s"http://$ip:9000/carts/reach")
    assertNotEquals(plain.getExitCode, 0, plain.getStdout)
  }

  test("22. steady state leaves the Service untouched") {
    val before = serviceNamed(WebService).get.getMetadata.getResourceVersion
    (1 to 10).foreach { _ =>
      writeDb(webSpec())
      Thread.sleep(300)
    }
    Thread.sleep(3000)
    assertEquals(serviceNamed(WebService).get.getMetadata.getResourceVersion, before)
  }

  test("23. an image that cannot join a cluster is never Ready, and ends Failed by the deadline") {
    // `pause` runs happily and has no management endpoint: exactly what an image whose runtime
    // predates feature 004 looks like. It must never be Ready — it would join itself and split —
    // and the Deployment's own deadline, not a clock of ours, must end it (FR-022).
    writeDb(
      AnkkaServiceSpec(
        projectId = Project,
        serviceName = DeafService,
        generation = 1L,
        image = "registry.k8s.io/pause:3.9",
        cpuMillis = IdleCpu,
        progressDeadlineSeconds = 30,
        provisionDatabase = false,
        port = Some(9000)
      )
    )
    val deadline = System.nanoTime() + 150.seconds.toNanos
    var failed   = false
    while !failed && System.nanoTime() < deadline do
      statusNamed(DeafService).foreach { s =>
        assert(s.lifecycle != "Ready", s"reported Ready with its port closed: $s")
        failed = s.lifecycle == "Failed"
      }
      if !failed then Thread.sleep(500)
    assert(failed, s"expected Failed once the deadline passed; last: ${statusNamed(DeafService)}")
    assert(
      statusNamed(DeafService).flatMap(_.detail).exists(_.nonEmpty),
      "a failure needs a reason"
    )
  }

  test(
    "24. a service that declares no HTTP still reaches Ready — on membership — and gets no address"
  ) {
    writeDb(
      AnkkaServiceSpec(
        projectId = Project,
        serviceName = QuietService,
        generation = 1L,
        image = SampleImage,
        progressDeadlineSeconds = 170,
        port = None
      )
    )
    waitFor(120.seconds)(statusNamed(QuietService).exists(_.lifecycle == "Ready"))

    val container = client
      .apps()
      .deployments()
      .inNamespace(Namespace)
      .withName(QuietService)
      .get()
      .getSpec
      .getTemplate
      .getSpec
      .getContainers
      .get(0)
    assert(!container.getPorts.asScala.exists(_.getName == "http"), container.getPorts.toString)
    assertEquals(
      container.getReadinessProbe.getHttpGet.getPath,
      "/ready",
      "readiness is membership, HTTP or not"
    )
    assertEquals(serviceNamed(QuietService), None)
  }

  test("25. turning HTTP off removes the address — but never one the resource does not own") {
    writeDb(webSpec(generation = 2L, port = None))
    waitFor(60.seconds)(serviceNamed(WebService).isEmpty)

    // Somebody's own Service, named after a workload that serves no HTTP. Not ours to remove.
    client
      .services()
      .inNamespace(Namespace)
      .resource(
        new io.fabric8.kubernetes.api.model.ServiceBuilder()
          .withMetadata(
            new ObjectMetaBuilder().withName(QuietService).withNamespace(Namespace).build()
          )
          .withSpec(
            new io.fabric8.kubernetes.api.model.ServiceSpecBuilder()
              .withPorts(
                new io.fabric8.kubernetes.api.model.ServicePortBuilder().withPort(5000).build()
              )
              .build()
          )
          .build()
      )
      .create(): Unit

    // Force real reconciles of `quiet`, each of which renders RemoveService for that very name.
    (2 to 4).foreach { generation =>
      writeDb(
        AnkkaServiceSpec(
          projectId = Project,
          serviceName = QuietService,
          generation = generation.toLong,
          image = SampleImage,
          progressDeadlineSeconds = 170,
          port = None
        )
      )
      Thread.sleep(2500)
    }
    waitFor(60.seconds)(statusNamed(QuietService).exists(_.generation == 4L))
    assert(serviceNamed(QuietService).isDefined, "the operator deleted a Service it did not create")
  }

  test("26. a Deployment from feature 003 — strategy Recreate — moves to RollingUpdate in place") {
    // The reverse of feature 003's migration. That one existed because the API server rejected
    // Recreate while a defaulted rollingUpdate block remained, and server-side apply cannot
    // remove a field nobody owns — a bug only an EXISTING object shows. Feature 004 goes the
    // other way; whether the API server accepts that over a live Recreate object is, likewise,
    // only knowable with a live Recreate object. In a namespace the operator does not watch.
    val ns = "migration-probe"
    client
      .namespaces()
      .resource(
        new io.fabric8.kubernetes.api.model.NamespaceBuilder()
          .withMetadata(new ObjectMetaBuilder().withName(ns).build())
          .build()
      )
      .serverSideApply(): Unit

    val legacySpec = AnkkaServiceSpec(
      projectId = "probe",
      serviceName = "legacy",
      generation = 1L,
      image = "registry.k8s.io/pause:3.9",
      cpuMillis = IdleCpu,
      provisionDatabase = false
    )
    val owner = client
      .resources(classOf[AnkkaService])
      .inNamespace(ns)
      .resource(AnkkaService(ns, "legacy", legacySpec))
      .create()

    // As feature 003 rendered it.
    val asItWas = Rendering.deployment(owner, legacySpec, ns)
    asItWas.getSpec.setStrategy(
      new io.fabric8.kubernetes.api.model.apps.DeploymentStrategyBuilder()
        .withType("Recreate")
        .build()
    )
    client.apps().deployments().inNamespace(ns).resource(asItWas).create(): Unit
    def live = client.apps().deployments().inNamespace(ns).withName("legacy").get()
    assertEquals(live.getSpec.getStrategy.getType, "Recreate", "the premise of this test")

    new Fabric8Executor(client).execute(
      Action.ApplyDeployment(Rendering.deployment(owner, legacySpec, ns))
    )

    assertEquals(live.getSpec.getStrategy.getType, "RollingUpdate")
    assertEquals(live.getSpec.getStrategy.getRollingUpdate.getMaxSurge.getIntVal.intValue, 1)
    assertEquals(live.getSpec.getStrategy.getRollingUpdate.getMaxUnavailable.getIntVal.intValue, 0)
  }

  // --- Feature 004: one cluster of several nodes ----------------------------------------------

  private val ClusteredService = "trio"

  private def trioSpec(generation: Long = 1L, instances: Int = 3) = AnkkaServiceSpec(
    projectId = Project,
    serviceName = ClusteredService,
    generation = generation,
    image = SampleImage,
    progressDeadlineSeconds = 170,
    autoscaling = com.thinkmorestupidless.ankka.crd.AutoscalingSpec(minInstances = instances)
  )

  private def podsOf(name: String) =
    client
      .pods()
      .inNamespace(Namespace)
      .withLabel(Labels.NameKey, name)
      .list()
      .getItems
      .asScala
      .toVector

  /**
   * What one pod says its cluster's Up members are — empty if it has not joined, or cannot answer.
   */
  private def membership(pod: io.fabric8.kubernetes.api.model.Pod): Set[String] =
    // Management requires this service's own cluster certificate (feature 014), which the pod
    // holds; asked from inside it, as a peer would.
    val (_, body) = InPod.curl(
      k3s,
      Namespace,
      pod.getMetadata.getName,
      s"https://${pod.getStatus.getPodIP}:7626/cluster/members",
      identity = "cluster",
      verifyHost = false
    )
    // Deliberately naive parsing: the members array's "node" fields, keeping only Up ones.
    """\{"node":"([^"]+)"[^}]*?"status":"([A-Za-z]+)"""".r
      .findAllMatchIn(body)
      .collect { case m if m.group(2) == "Up" => m.group(1) }
      .toSet

  private def disjointClusters(name: String): Int =
    Membership.disjointClusters(podsOf(name).map(membership))

  test("27. three instances form one cluster, and every pod agrees on its membership") {
    // Services no later case uses are deleted first: they hold CPU the three instances here, and
    // five in case 30, need. Here rather than at the end of their own cases, so a case that failed
    // halfway still leaves the node clear for these.
    val finished = Vector(DbService, DbService2, DeafService)
    finished.foreach(name => resources.inNamespace(Namespace).withName(name).delete(): Unit)
    waitFor(120.seconds)(finished.forall(podsOf(_).isEmpty))
    writeDb(trioSpec())
    waitFor(240.seconds)(
      statusNamed(ClusteredService).exists(s => s.lifecycle == "Ready" && s.readyInstances == 3)
    )

    val views = podsOf(ClusteredService).map(membership)
    assertEquals(views.size, 3)
    assertEquals(Membership.disjointClusters(views), 1, views.toString)
    views.foreach(v => assertEquals(v.size, 3, v.toString))
  }

  test("28. the identity objects exist, owned — and go with the service") {
    val sa = client.serviceAccounts().inNamespace(Namespace).withName(ClusteredService).get()
    val role =
      client.rbac().roles().inNamespace(Namespace).withName(s"$ClusteredService-peers").get()
    val binding =
      client.rbac().roleBindings().inNamespace(Namespace).withName(s"$ClusteredService-peers").get()
    for (what, meta) <- Vector("ServiceAccount" -> sa, "Role" -> role, "RoleBinding" -> binding)
        .map((w, o) => w -> o.getMetadata)
    do
      assert(
        meta.getOwnerReferences.asScala.exists(_.getKind == "AnkkaService"),
        s"$what is not owned"
      )
    assertEquals(
      client
        .apps()
        .deployments()
        .inNamespace(Namespace)
        .withName(ClusteredService)
        .get()
        .getSpec
        .getTemplate
        .getSpec
        .getServiceAccountName,
      ClusteredService
    )
  }

  test("29. a service's own identity can read pods in its namespace, and nothing else, anywhere") {
    // SC-007. Its real token, the way feature 003 minted the operator's.
    val tokenResult = k3s.execInContainer(
      "kubectl",
      "create",
      "token",
      ClusteredService,
      "-n",
      Namespace,
      "--duration=10m"
    )
    assertEquals(tokenResult.getExitCode, 0, tokenResult.getStderr)
    val asService = new KubernetesClientBuilder()
      .withConfig(
        new io.fabric8.kubernetes.client.ConfigBuilder()
          .withMasterUrl(client.getConfiguration.getMasterUrl)
          .withTrustCerts(true)
          .withOauthToken(tokenResult.getStdout.trim)
          .build()
      )
      .build()
    def forbidden(what: String)(attempt: => Any): Unit =
      val e = intercept[io.fabric8.kubernetes.client.KubernetesClientException](attempt)
      assertEquals(e.getCode, 403, s"$what should be forbidden: ${e.getMessage}")
    try
      assert(
        !asService.pods().inNamespace(Namespace).list().getItems.isEmpty,
        "can list its own project's pods"
      )
      forbidden("list pods elsewhere")(asService.pods().inNamespace("ankka-operator").list())
      forbidden("list services")(asService.services().inNamespace(Namespace).list())
      forbidden("list secrets")(asService.secrets().inNamespace(Namespace).list())
      forbidden("delete a pod") {
        asService
          .pods()
          .inNamespace(Namespace)
          .withName(podsOf(ClusteredService).head.getMetadata.getName)
          .delete()
      }
      forbidden("create a pod") {
        asService
          .pods()
          .inNamespace(Namespace)
          .resource(
            new io.fabric8.kubernetes.api.model.PodBuilder()
              .withMetadata(
                new ObjectMetaBuilder().withName("intruder").withNamespace(Namespace).build()
              )
              .withSpec(
                new io.fabric8.kubernetes.api.model.PodSpecBuilder()
                  .withContainers(
                    new io.fabric8.kubernetes.api.model.ContainerBuilder()
                      .withName("x")
                      .withImage("registry.k8s.io/pause:3.9")
                      .build()
                  )
                  .build()
              )
              .build()
          )
          .create()
      }
    finally asService.close()
  }

  test("30. scaling keeps the pods that stay") {
    val before = podsOf(ClusteredService).map(_.getMetadata.getName).toSet
    writeDb(trioSpec(generation = 2L, instances = 5))
    waitFor(240.seconds)(
      statusNamed(ClusteredService).exists(s => s.lifecycle == "Ready" && s.readyInstances == 5)
    )
    val after = podsOf(ClusteredService)
    assert(before.subsetOf(after.map(_.getMetadata.getName).toSet), "an original pod was replaced")
    after.filter(p => before.contains(p.getMetadata.getName)).foreach { p =>
      assertEquals(
        p.getStatus.getContainerStatuses.get(0).getRestartCount.intValue,
        0,
        p.getMetadata.getName
      )
    }
    assertEquals(disjointClusters(ClusteredService), 1)

    writeDb(trioSpec(generation = 3L, instances = 2))
    waitFor(240.seconds)(
      podsOf(ClusteredService).size == 2 && statusNamed(ClusteredService).exists(
        _.lifecycle == "Ready"
      )
    )
    assertEquals(disjointClusters(ClusteredService), 1)
  }

  // ---- Exposure (feature 005): the objects the operator renders, and the verbs it holds -------

  private def routeNamed(name: String) =
    Option(
      client
        .resources(classOf[io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute])
        .inNamespace(Namespace)
        .withName(name)
        .get()
    )

  test("31. an exposed service gets an HTTPRoute, owned, shaped as the contract says") {
    writeDb(webSpec(generation = 10L).copy(exposed = true))
    waitFor(60.seconds)(routeNamed(WebService).isDefined)
    val route = routeNamed(WebService).get
    val owner = route.getMetadata.getOwnerReferences.get(0)
    assertEquals(owner.getKind, "AnkkaService")
    assertEquals(owner.getName, WebService)
    assertEquals(route.getSpec.getHostnames.asScala.toVector, Vector(s"web-$Project.test.local"))
    val parent = route.getSpec.getParentRefs.get(0)
    assertEquals(
      (parent.getName, parent.getNamespace, parent.getSectionName),
      ("ankka", "ankka-gateway", "https")
    )
    val backend = route.getSpec.getRules.get(0).getBackendRefs.get(0)
    assertEquals(
      (backend.getName, backend.getPort.intValue, backend.getNamespace),
      (WebService, 9000, null)
    )
    // No controller here, so the gateway has not spoken: the status says so rather than nothing.
    waitFor(30.seconds)(statusNamed(WebService).exists(_.route.contains("pending")))
  }

  test("32. unexposing removes the route and leaves the pod template untouched") {
    val template = client
      .apps()
      .deployments()
      .inNamespace(Namespace)
      .withName(WebService)
      .get()
      .getSpec
      .getTemplate
    writeDb(webSpec(generation = 11L).copy(exposed = false))
    waitFor(60.seconds)(routeNamed(WebService).isEmpty)
    waitFor(30.seconds)(statusNamed(WebService).exists(s => s.generation == 11L && s.route.isEmpty))
    val after = client
      .apps()
      .deployments()
      .inNamespace(Namespace)
      .withName(WebService)
      .get()
      .getSpec
      .getTemplate
    assertEquals(after, template, "unexposing rolled the pods")
  }

  test("33. exposed but serving no HTTP: no route, and the status says why on the resource") {
    writeDb(webSpec(generation = 12L, port = None).copy(exposed = true))
    Thread.sleep(5000)
    assertEquals(routeNamed(WebService), None)
    // The control plane refuses this before it ever reaches a resource; the operator's own answer
    // is "pending" — there is nothing to route — which the route field carries.
    waitFor(30.seconds)(statusNamed(WebService).exists(_.route.isDefined))
    writeDb(webSpec(generation = 13L).copy(exposed = true))
    waitFor(60.seconds)(routeNamed(WebService).isDefined)
  }

  test("34. a route the resource does not own is never removed") {
    val stranger = new io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteBuilder()
      .withMetadata(
        new ObjectMetaBuilder().withName(QuietService).withNamespace(Namespace).build()
      )
      .withSpec(
        new io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRouteSpecBuilder()
          .withParentRefs(
            new io.fabric8.kubernetes.api.model.gatewayapi.v1.ParentReferenceBuilder()
              .withName("ankka")
              .withNamespace("ankka-gateway")
              .build()
          )
          .withHostnames(s"quiet-$Project.test.local")
          .build()
      )
      .build()
    client.resource(stranger).create(): Unit
    // quiet is not exposed, so every reconcile of it renders RemoveHttpRoute for this very name.
    writeDb(
      AnkkaServiceSpec(
        projectId = Project,
        serviceName = QuietService,
        generation = 5L,
        image = SampleImage,
        progressDeadlineSeconds = 170,
        port = None
      )
    )
    waitFor(60.seconds)(statusNamed(QuietService).exists(_.generation == 5L))
    Thread.sleep(5000)
    assert(routeNamed(QuietService).isDefined, "the operator deleted a route it did not create")
    client.resource(stranger).delete(): Unit
  }

  test("35. deleting the resource cascades to its route") {
    writeDb(webSpec(generation = 14L).copy(exposed = true))
    waitFor(60.seconds)(routeNamed(WebService).isDefined)
    resources.inNamespace(Namespace).withName(WebService).delete(): Unit
    waitFor(60.seconds)(routeNamed(WebService).isEmpty)
  }

  test(
    "36. the operator can write routes and nothing else about routing; a service cannot read them"
  ) {
    // The operator's real token (test 20 applied the shipped RBAC).
    val operatorToken = k3s.execInContainer(
      "kubectl",
      "create",
      "token",
      "ankka-operator",
      "-n",
      "ankka-operator",
      "--duration=10m"
    )
    assertEquals(operatorToken.getExitCode, 0, operatorToken.getStderr)
    def clientWith(token: String) = new KubernetesClientBuilder()
      .withConfig(
        new io.fabric8.kubernetes.client.ConfigBuilder()
          .withMasterUrl(client.getConfiguration.getMasterUrl)
          .withTrustCerts(true)
          .withOauthToken(token)
          .build()
      )
      .build()
    def forbidden(what: String)(
        attempt: => Any
    ): Unit =
      val e = intercept[io.fabric8.kubernetes.client.KubernetesClientException](attempt)
      assertEquals(e.getCode, 403, s"$what should be forbidden: ${e.getMessage}")

    val asOperator = clientWith(operatorToken.getStdout.trim)
    try
      // Granted: routes in a project namespace.
      asOperator
        .resources(classOf[io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute])
        .inNamespace(Namespace)
        .list(): Unit
      // Withheld: everything about where traffic enters or what certificate it is served under.
      forbidden("list gateways")(
        asOperator
          .resources(classOf[io.fabric8.kubernetes.api.model.gatewayapi.v1.Gateway])
          .inNamespace("ankka-gateway")
          .list()
      )
      forbidden("create a gateway") {
        asOperator
          .resource(
            new io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewayBuilder()
              .withMetadata(
                new ObjectMetaBuilder().withName("rogue").withNamespace(Namespace).build()
              )
              .withSpec(
                new io.fabric8.kubernetes.api.model.gatewayapi.v1.GatewaySpecBuilder()
                  .withGatewayClassName("ankka")
                  .withListeners(
                    new io.fabric8.kubernetes.api.model.gatewayapi.v1.ListenerBuilder()
                      .withName("http")
                      .withPort(80)
                      .withProtocol("HTTP")
                      .build()
                  )
                  .build()
              )
              .build()
          )
          .create()
      }
      forbidden("list secrets")(
        asOperator.secrets().inNamespace("ankka-gateway").list()
      )
      // Feature 014: it may request certificates and write policies, and it may not replace an
      // installation authority.
      asOperator
        .genericKubernetesResources("cert-manager.io/v1", "Certificate")
        .inNamespace(Namespace)
        .list(): Unit
      asOperator
        .genericKubernetesResources("cert-manager.io/v1", "Issuer")
        .inNamespace(Namespace)
        .list(): Unit
      asOperator.network().v1().networkPolicies().inNamespace(Namespace).list(): Unit
      asOperator
        .resources(classOf[io.fabric8.kubernetes.api.model.gatewayapi.v1.BackendTLSPolicy])
        .inNamespace(Namespace)
        .list(): Unit
      forbidden("list clusterissuers")(
        asOperator.genericKubernetesResources("cert-manager.io/v1", "ClusterIssuer").list()
      )

    finally asOperator.close()

    // A deployed service's own identity (test 29's) cannot see routes at all.
    val serviceToken = k3s.execInContainer(
      "kubectl",
      "create",
      "token",
      ClusteredService,
      "-n",
      Namespace,
      "--duration=10m"
    )
    assertEquals(serviceToken.getExitCode, 0, serviceToken.getStderr)
    val asService = clientWith(serviceToken.getStdout.trim)
    try
      forbidden("list httproutes")(
        asService
          .resources(classOf[io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute])
          .inNamespace(Namespace)
          .list()
      )
    finally asService.close()
  }

  // ── Cloud requests (feature 044): both identities' grants, on a real API server ──────────

  private val CloudOwner = "cloud-probe"

  /** A token for a ServiceAccount, minted by the node's kubectl. */
  private def mintToken(account: String, namespace: String): String =
    val result = k3s.execInContainer(
      "kubectl",
      "create",
      "token",
      account,
      "-n",
      namespace,
      "--duration=10m"
    )
    assertEquals(result.getExitCode, 0, result.getStderr)
    result.getStdout.trim

  /** The cloud request type, the operator's RBAC and the provider's, and a resource to own. */
  private lazy val cloudReady: AnkkaService =
    cloud.CloudProviderStack.install(k3s, client)
    client
      .load(getClass.getResourceAsStream("/ankka/install/operator.yaml"))
      .items()
      .asScala
      .filter(o =>
        Set("Namespace", "ServiceAccount", "ClusterRole", "ClusterRoleBinding").contains(o.getKind)
      )
      .foreach(o => client.resource(o).serverSideApply(): Unit)
    if client.namespaces().withName(Namespace).get() == null then
      client
        .namespaces()
        .resource(
          new io.fabric8.kubernetes.api.model.NamespaceBuilder()
            .withMetadata(new ObjectMetaBuilder().withName(Namespace).build())
            .build()
        )
        .serverSideApply(): Unit
    resources
      .inNamespace(Namespace)
      .resource(
        AnkkaService(
          Namespace,
          CloudOwner,
          spec(paused = true).copy(serviceName = CloudOwner, port = None)
        )
      )
      .fieldManager("ankka-test")
      .forceConflicts()
      .serverSideApply(): Unit
    var owner: AnkkaService = null
    waitFor(30.seconds) {
      owner = resources.inNamespace(Namespace).withName(CloudOwner).get()
      owner != null && owner.getMetadata.getUid != null
    }
    owner

  private val probeCloud =
    CloudSettings("gcp", "probe-account", "probe-location", None, 2.minutes, 1.hour)

  private def probeRequest(owner: AnkkaService) =
    CloudRequests.bucket(
      probeCloud,
      CloudRequests.Requester.of(owner),
      CloudRequests.Purpose.Service,
      probeCloud.location,
      CloudRequests.BucketAsk()
    )

  private def cloudRequests = client.resources(classOf[CloudResource]).inNamespace(Namespace)

  private def refused(what: String)(attempt: => Any): Unit =
    val e = intercept[io.fabric8.kubernetes.client.KubernetesClientException](attempt)
    assertEquals(e.getCode, 403, s"$what should be refused by the API server: ${e.getMessage}")

  test("40. the operator's token writes a cloud request, and cannot answer or delete one") {
    val owner = cloudReady
    val asOperator =
      cloud.CloudProviderStack.client(client, mintToken("ankka-operator", "ankka-operator"))
    try
      val executor = new Fabric8Executor(asOperator)
      // The first apply of an absent object is a PATCH: "create" alone would be refused here.
      executor.execute(Action.EnsureCloudResource(probeRequest(owner)))
      val name = s"$CloudOwner-bucket"
      val seen = executor.observeCloudResource(Namespace, name).getOrElse(fail("not read back"))
      assertEquals(seen.generation, 1L)
      assertEquals(seen.status, None)
      val written = cloudRequests.withName(name).get()
      assertEquals(
        written.getMetadata.getOwnerReferences.asScala.map(_.getUid).toVector,
        Vector(owner.getMetadata.getUid)
      )
      refused("the operator's status write")(
        asOperator
          .resources(classOf[CloudResource])
          .inNamespace(Namespace)
          .withName(name)
          .editStatus { r =>
            r.setStatus(CloudResourceStatus(observedGeneration = Some(1L), phase = "Ready"))
            r
          }
      )
      refused("the operator's delete")(
        asOperator.resources(classOf[CloudResource]).inNamespace(Namespace).withName(name).delete()
      )
    finally asOperator.close()
  }

  test("41. the provider's token writes an answer and a Secret, and reads no Secret back") {
    // credential.feature: "the cloud provider can write a secret and cannot read one back", and
    // "the operator cannot read a storage credential the cloud provider made".
    cloudReady
    val name       = s"$CloudOwner-bucket"
    val asProvider = cloud.CloudProviderStack.client(client, cloud.CloudProviderStack.token(k3s))
    val asOperator =
      cloud.CloudProviderStack.client(client, mintToken("ankka-operator", "ankka-operator"))
    try
      asProvider
        .resources(classOf[CloudResource])
        .inNamespace(Namespace)
        .withName(name)
        .editStatus { r =>
          r.setStatus(CloudResourceStatus(observedGeneration = Some(1L), phase = "Ready"))
          r
        }: Unit
      assertEquals(cloudRequests.withName(name).get().getMetadata.getGeneration.longValue, 1L)

      val secret = new io.fabric8.kubernetes.api.model.SecretBuilder()
        .withMetadata(
          new ObjectMetaBuilder().withName("probe-storage").withNamespace(Namespace).build()
        )
        .withStringData(Map("ANKKA_S3_ACCESS_KEY" -> "one").asJava)
        .build()
      asProvider.secrets().inNamespace(Namespace).resource(secret).create(): Unit
      val again = intercept[io.fabric8.kubernetes.client.KubernetesClientException](
        asProvider.secrets().inNamespace(Namespace).resource(secret).create()
      )
      assertEquals(again.getCode, 409, "a second create learns that one is there")
      asProvider
        .secrets()
        .inNamespace(Namespace)
        .withName("probe-storage")
        .patch(
          io.fabric8.kubernetes.client.dsl.base.PatchContext
            .of(io.fabric8.kubernetes.client.dsl.base.PatchType.JSON_MERGE),
          new io.fabric8.kubernetes.api.model.SecretBuilder()
            .withStringData(Map("ANKKA_S3_ACCESS_KEY" -> "two").asJava)
            .build()
        ): Unit

      refused("the provider's get of a Secret")(
        asProvider.secrets().inNamespace(Namespace).withName("probe-storage").get()
      )
      refused("the provider's list of Secrets")(asProvider.secrets().inNamespace(Namespace).list())
      refused("the provider's change to what is asked")(
        asProvider
          .resources(classOf[CloudResource])
          .inNamespace(Namespace)
          .withName(name)
          .patch(
            io.fabric8.kubernetes.client.dsl.base.PatchContext
              .of(io.fabric8.kubernetes.client.dsl.base.PatchType.JSON_MERGE),
            """{"spec":{"provider":"other"}}"""
          )
      )
      refused("the provider's delete")(
        asProvider.resources(classOf[CloudResource]).inNamespace(Namespace).withName(name).delete()
      )
      refused("the operator's get of the credential")(
        asOperator.secrets().inNamespace(Namespace).withName("probe-storage").get()
      )
    finally
      asProvider.close()
      asOperator.close()
  }

  test("42. a change to what is asked raises the generation, and an answer does not") {
    val owner = cloudReady
    val name  = s"$CloudOwner-bucket"
    val asOperator =
      cloud.CloudProviderStack.client(client, mintToken("ankka-operator", "ankka-operator"))
    try
      val before = cloudRequests.withName(name).get().getMetadata.getGeneration.longValue
      val asked  = probeRequest(owner)
      asked.setSpec(
        asked.getSpec.copy(parameters = asked.getSpec.parameters + ("versioning" -> "true"))
      )
      new Fabric8Executor(asOperator).execute(Action.EnsureCloudResource(asked))
      val after = cloudRequests.withName(name).get().getMetadata.getGeneration.longValue
      assertEquals(after, before + 1)
      val asProvider = cloud.CloudProviderStack.client(client, cloud.CloudProviderStack.token(k3s))
      try
        asProvider
          .resources(classOf[CloudResource])
          .inNamespace(Namespace)
          .withName(name)
          .editStatus { r =>
            r.setStatus(CloudResourceStatus(observedGeneration = Some(after), phase = "Ready"))
            r
          }: Unit
      finally asProvider.close()
      assertEquals(cloudRequests.withName(name).get().getMetadata.getGeneration.longValue, after)
    finally asOperator.close()
  }

  test("43. a cloud request goes with its service, and the Secret it was answered with stays") {
    cloudReady
    resources.inNamespace(Namespace).withName(CloudOwner).delete(): Unit
    waitFor(60.seconds)(cloudRequests.withName(s"$CloudOwner-bucket").get() == null)
    assert(
      client.secrets().inNamespace(Namespace).withName("probe-storage").get() != null,
      "nothing that answered a request goes with it"
    )
  }

  test("11. deleting the resource cascades to the deployment with no operator involvement") {
    // Owner references, not a sweep. This is the design's central simplification, so it is
    // asserted against a real API server rather than a fake that would simply agree.
    operator.close()

    resources.inNamespace(Namespace).withName(Service).delete(): Unit
    waitFor(120.seconds)(deployment.isEmpty)
  }
