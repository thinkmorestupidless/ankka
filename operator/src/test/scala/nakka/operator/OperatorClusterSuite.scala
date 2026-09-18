package nakka.operator

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import nakka.crd.{NakkaSerialization, NakkaService, NakkaServiceSpec}
import nakka.operator.cnpg.{PostgresCluster, PostgresDatabase, PostgresDatabaseRole}
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
 * Disable with `-Dnakka.cluster.tests=off`; everything else in the module runs offline.
 */
class OperatorClusterSuite extends munit.FunSuite:

  // 8, not 6: CNPG's own install and startup, plus the realistic first-service provisioning
  // window in tests 12-14 (up to 170s, per research R5), pushed this past the original budget.
  override val munitTimeout: FiniteDuration = 8.minutes

  override def munitIgnore: Boolean = sys.props.get("nakka.cluster.tests").contains("off")

  private val Image     = "rancher/k3s:v1.31.2-k3s1"
  private val Prefix    = "nakka"
  private val Project   = "checkout"
  private val Namespace = s"$Prefix-$Project"
  private val Service   = "cart"

  private var k3s: K3sContainer        = null
  private var client: KubernetesClient = null
  private var operator: Operator       = null

  private val settings = Settings.default.copy(resyncInterval = 2.seconds)

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(Image))
      k3s.withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("k3s")))
      k3s.start()

      client = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(NakkaSerialization())
        .build()

      // From the shipped manifest, not an inline copy: a CRD that does not install cannot
      // pass CI, which is the same rule the runtime's DDL follows.
      val crd = getClass.getResourceAsStream("/nakka/crd/nakkaservice.yaml")
      client.load(crd).serverSideApply(): Unit

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

      waitFor(60.seconds)(
        client
          .apiextensions()
          .v1()
          .customResourceDefinitions()
          .list()
          .getItems
          .asScala
          .exists(_.getMetadata.getName == "nakkaservices.nakka.thinkmorestupidless.com")
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
    if !passed then fail(s"condition did not hold within $timeout")

  private def spec(generation: Long = 1L, image: String = "busybox:1.36", paused: Boolean = false) =
    NakkaServiceSpec(
      projectId = Project,
      serviceName = Service,
      generation = generation,
      paused = paused,
      image = image,
      progressDeadlineSeconds = 30,
      // These are feature 001's own tests, about Deployment rendering and lifecycle — not
      // database provisioning. The escape hatch keeps them independent of CNPG being ready and
      // of a schema-init container that has nothing to do with what they assert on. The
      // provisioned path gets its own tests and its own service name, below.
      provisionDatabase = false
    )

  private def resources = client.resources(classOf[NakkaService])

  private def write(s: NakkaServiceSpec): Unit =
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
      .resource(NakkaService(Namespace, Service, s))
      .fieldManager("nakka-test")
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
    assertEquals(d.getMetadata.getLabels.get(Labels.ManagedByKey), "nakka")
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

  test("3. the generation reaches the pod template, which is what rolls a restart") {
    waitFor(60.seconds)(
      deployment.exists(
        _.getSpec.getTemplate.getMetadata.getAnnotations.get(Labels.GenerationKey) == "2"
      )
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

  private def dbSpec(generation: Long = 1L) = NakkaServiceSpec(
    projectId = Project,
    serviceName = DbService,
    generation = generation,
    image = "registry.k8s.io/pause:3.9", // stays up with no command, unlike busybox (see 001)
    // Generous, matching SC-002's own 3-minute budget for a project's *first* service: CNPG's
    // secret-RBAC-allowlist propagation alone can take 20-40s (research R5), on top of the
    // cluster's own startup time. A tight deadline here does not test provisioning — it tests
    // whether Kubernetes' rollout timeout loses a race against CNPG's, and a first cut of this
    // test did exactly that (progressDeadlineSeconds=60 failed with ProgressDeadlineExceeded
    // before provisioning had a chance to finish).
    progressDeadlineSeconds = 170
    // provisionDatabase defaults to true — this is the whole point of these two tests.
  )

  private def writeDb(s: NakkaServiceSpec): Unit =
    resources
      .inNamespace(Namespace)
      .resource(NakkaService(Namespace, s.serviceName, s))
      .fieldManager("nakka-test")
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
    val secretBefore   = client.secrets().inNamespace(Namespace).withName(s"$DbService-db").get()
    val passwordBefore = secretBefore.getData.get("password")
    val resourceVersionBefore = secretBefore.getMetadata.getResourceVersion

    (1 to 10).foreach { _ =>
      writeDb(dbSpec()) // identical spec, same generation — a genuine no-op apply
      Thread.sleep(300)
    }
    Thread.sleep(3000) // let a few resync/reconcile passes actually happen

    val secretAfter = client.secrets().inNamespace(Namespace).withName(s"$DbService-db").get()
    assertEquals(
      secretAfter.getData.get("password"),
      passwordBefore,
      "the password must never change"
    )
    assertEquals(
      secretAfter.getMetadata.getResourceVersion,
      resourceVersionBefore,
      "the credential secret must not be rewritten at all in steady state"
    )
  }

  private val DbService2 = "with-db-2"

  private def dbSpec2(generation: Long = 1L) = NakkaServiceSpec(
    projectId = Project,
    serviceName = DbService2,
    generation = generation,
    image = "registry.k8s.io/pause:3.9",
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

  private def psqlAs(role: String, password: String, database: String, sql: String): (Int, String) =
    execInPostgres(
      Seq(
        "env",
        s"PGPASSWORD=$password",
        "psql",
        "-h",
        "localhost",
        "-U",
        role,
        "-d",
        database,
        "-tA",
        "-c",
        sql
      )
    )

  private def execInPostgres(command: Seq[String]): (Int, String) =
    val out = new java.io.ByteArrayOutputStream()
    val watch = client
      .pods()
      .inNamespace(Namespace)
      .withName("nakka-db-1")
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

  private def passwordOf(secretName: String): String =
    val secret = client.secrets().inNamespace(Namespace).withName(secretName).get()
    new String(java.util.Base64.getDecoder.decode(secret.getData.get("password")))

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

  test("16. each service's own database is reachable with its own credentials") {
    val pw          = passwordOf(s"$DbService-db")
    val (code, out) = psqlAs(DbService, pw, DbService, "select current_database();")
    assertEquals(code, 0, out)
    assertEquals(out.trim, DbService)
  }

  test(
    "17. cross-service CONNECT is refused in both directions — the property research R9 exists for"
  ) {
    val pw1 = passwordOf(s"$DbService-db")
    val pw2 = passwordOf(s"$DbService2-db")

    val (code12, out12) = psqlAs(DbService, pw1, DbService2, "select 1;")
    assertNotEquals(
      code12,
      0,
      s"expected $DbService to be refused CONNECT to $DbService2's database: $out12"
    )
    assert(out12.contains("CONNECT"), out12)

    val (code21, out21) = psqlAs(DbService2, pw2, DbService, "select 1;")
    assertNotEquals(
      code21,
      0,
      s"expected $DbService2 to be refused CONNECT to $DbService's database: $out21"
    )
    assert(out21.contains("CONNECT"), out21)
  }

  test("18. each service's timers table exists independently, not shared") {
    // The concrete case that motivated this feature (SC-005): nakka_timers has no service
    // column, so two services sharing a database would delete each other's timers. Separate
    // databases make that structurally impossible — confirmed here, not assumed.
    val (code, out) = psqlAsPostgres(
      "select datname from pg_database where datname in ('with-db', 'with-db-2') order by 1;"
    )
    assertEquals(code, 0, out)
    assertEquals(out.trim.linesIterator.toVector, Vector("with-db", "with-db-2"))

    val pw1      = passwordOf(s"$DbService-db")
    val pw2      = passwordOf(s"$DbService2-db")
    val (c1, o1) = psqlAs(DbService, pw1, DbService, "select count(*) from nakka_timers;")
    val (c2, o2) = psqlAs(DbService2, pw2, DbService2, "select count(*) from nakka_timers;")
    assertEquals(c1, 0, o1)
    assertEquals(c2, 0, o2)
  }

  test("19. deleting a service preserves its database and data; re-applying it recovers both") {
    val pw = passwordOf(s"$DbService2-db")
    val (createCode, createOut) =
      psqlAs(
        DbService2,
        pw,
        DbService2,
        "create table scratch_marker(id int); insert into scratch_marker values (42);"
      )
    assertEquals(createCode, 0, createOut)

    resources.inNamespace(Namespace).withName(DbService2).delete(): Unit
    waitFor(120.seconds)(
      Option(client.apps().deployments().inNamespace(Namespace).withName(DbService2).get()).isEmpty
    )

    // No owner reference ties a Database, a DatabaseRole or a credential secret to the
    // NakkaService that requested them (CnpgRendering's deliberate design) — deleting the
    // resource must not cascade to any of them, unlike the Deployment above.
    assert(
      Option(
        client
          .resources(classOf[PostgresDatabase])
          .inNamespace(Namespace)
          .withName(DbService2)
          .get()
      ).isDefined,
      "the Database must survive deleting the NakkaService that requested it"
    )
    val (survivedCode, survivedOut) =
      psqlAs(DbService2, pw, DbService2, "select id from scratch_marker;")
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
      psqlAs(DbService2, pw, DbService2, "select id from scratch_marker;")
    assertEquals(recoveredCode, 0, recoveredOut)
    assertEquals(
      recoveredOut.trim,
      "42",
      "the same row, from before the delete, not a fresh database"
    )
  }

  test("20. the shipped RBAC, both ways: a withheld verb is refused, a granted one really works") {
    // Applied from the shipped manifest, same discipline as the CRD and CNPG above: a ClusterRole
    // that does not actually grant what the operator needs (or grants more than it should) cannot
    // pass this suite. Only the RBAC objects are extracted — the manifest's own Deployment is not
    // applied, since this suite already runs the operator in-process against the admin kubeconfig.
    val rbacObjects = client
      .load(getClass.getResourceAsStream("/nakka/install/operator.yaml"))
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
        "nakka-operator",
        "-n",
        "nakka-operator",
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
      .withKubernetesSerialization(NakkaSerialization())
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
      val probeSpec = NakkaServiceSpec(
        projectId = "probe",
        serviceName = "probe",
        generation = 1L,
        image = "registry.k8s.io/pause:3.9",
        provisionDatabase = false,
        port = Some(80)
      )
      val owner = client
        .resources(classOf[NakkaService])
        .inNamespace(probeNamespace)
        .resource(NakkaService(probeNamespace, "probe", probeSpec))
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
    finally restricted.close()
  }

  // --- Feature 003: a deployed service can be reached -------------------------------------

  private val WebService   = "web"
  private val DeafService  = "deaf"
  private val QuietService = "quiet"

  /** nginx listens on 80 with no command, which a NakkaServiceSpec has no way to supply. */
  private def webSpec(generation: Long = 1L, port: Option[Int] = Some(80)) = NakkaServiceSpec(
    projectId = Project,
    serviceName = WebService,
    generation = generation,
    image = "nginx:1.27-alpine",
    progressDeadlineSeconds = 120,
    provisionDatabase = false,
    port = port
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
    assertEquals(service.getSpec.getPorts.get(0).getPort.intValue, 80)

    // The selector really matches the pod: an Endpoints object with a ready address is the
    // cluster's own statement of that, not ours.
    waitFor(30.seconds) {
      val endpoints = client.endpoints().inNamespace(Namespace).withName(WebService).get()
      endpoints != null && endpoints.getSubsets.asScala.exists(!_.getAddresses.isEmpty)
    }

    // From the node, to the clusterIP: Service -> endpoints -> pod, the real path. A port-forward
    // would go API server -> pod and bypass the Service, passing with a broken selector. By IP,
    // not name — the node does not resolve cluster DNS (both verified during planning).
    val ip     = service.getSpec.getClusterIP
    val result = k3s.execInContainer("wget", "-qO-", "-T", "10", s"http://$ip:80/")
    assertEquals(result.getExitCode, 0, result.getStderr)
    assert(result.getStdout.toLowerCase.contains("nginx"), result.getStdout)
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

  test("23. Ready follows the port, not the process — and a port that never opens ends Failed") {
    // `pause` runs happily and opens nothing. Before the probe it was Ready in seconds; now it
    // must never be, and the Deployment's own deadline — not a clock of ours — must end it.
    writeDb(
      NakkaServiceSpec(
        projectId = Project,
        serviceName = DeafService,
        generation = 1L,
        image = "registry.k8s.io/pause:3.9",
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

  test("24. a service that serves no HTTP still reaches Ready, and gets no address") {
    writeDb(
      NakkaServiceSpec(
        projectId = Project,
        serviceName = QuietService,
        generation = 1L,
        image = "registry.k8s.io/pause:3.9",
        progressDeadlineSeconds = 60,
        provisionDatabase = false,
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
    assert(container.getPorts.isEmpty)
    assertEquals(container.getReadinessProbe, null)
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
        NakkaServiceSpec(
          projectId = Project,
          serviceName = QuietService,
          generation = generation.toLong,
          image = "registry.k8s.io/pause:3.9",
          progressDeadlineSeconds = 60,
          provisionDatabase = false,
          port = None
        )
      )
      Thread.sleep(2500)
    }
    waitFor(60.seconds)(statusNamed(QuietService).exists(_.generation == 4L))
    assert(serviceNamed(QuietService).isDefined, "the operator deleted a Service it did not create")
  }

  test("26. a Deployment created before Recreate was rendered is migrated, not wedged forever") {
    // Every Deployment this operator created before it rendered a strategy carries Kubernetes'
    // defaulted RollingUpdate block, owned by no field manager. Server-side apply cannot remove a
    // field nobody owns, and the API server rejects type=Recreate while rollingUpdate is still
    // set — so the first apply after upgrading failed with `invalid: spec.strategy`, retried
    // forever, and the service could never be changed again. Only an *existing* object shows
    // this; every other case here starts from nothing. Found on a real cluster with a real
    // leftover Deployment.
    //
    // In a namespace the in-process operator does not watch, so it cannot interfere.
    val ns = "migration-probe"
    client
      .namespaces()
      .resource(
        new io.fabric8.kubernetes.api.model.NamespaceBuilder()
          .withMetadata(new ObjectMetaBuilder().withName(ns).build())
          .build()
      )
      .serverSideApply(): Unit

    val legacySpec = NakkaServiceSpec(
      projectId = "probe",
      serviceName = "legacy",
      generation = 1L,
      image = "registry.k8s.io/pause:3.9",
      provisionDatabase = false
    )
    val owner = client
      .resources(classOf[NakkaService])
      .inNamespace(ns)
      .resource(NakkaService(ns, "legacy", legacySpec))
      .create()

    // As it used to be rendered: no strategy at all, so the API server defaults RollingUpdate.
    val asItWas = Rendering.deployment(owner, legacySpec, ns)
    asItWas.getSpec.setStrategy(null)
    client.apps().deployments().inNamespace(ns).resource(asItWas).create(): Unit
    def live = client.apps().deployments().inNamespace(ns).withName("legacy").get()
    assertEquals(live.getSpec.getStrategy.getType, "RollingUpdate", "the premise of this test")

    new Fabric8Executor(client).execute(
      Action.ApplyDeployment(Rendering.deployment(owner, legacySpec, ns))
    )

    assertEquals(live.getSpec.getStrategy.getType, "Recreate")
    assertEquals(live.getSpec.getStrategy.getRollingUpdate, null)
  }

  test("11. deleting the resource cascades to the deployment with no operator involvement") {
    // Owner references, not a sweep. This is the design's central simplification, so it is
    // asserted against a real API server rather than a fake that would simply agree.
    operator.close()

    resources.inNamespace(Namespace).withName(Service).delete(): Unit
    waitFor(120.seconds)(deployment.isEmpty)
  }
