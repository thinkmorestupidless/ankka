package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.{
  GrantDetail,
  GrantState,
  ServiceLifecycle,
  ServiceStatus
}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  Fabric8AnkkaServiceClient,
  PodLogs,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  InPod,
  Operator,
  PkiStack,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.fabric8.kubernetes.api.model.{ConfigMapBuilder, ObjectMetaBuilder}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.{Deadline, DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/**
 * `features/cross-project/route-grants.feature` on k3s: cert-manager and the installation's
 * authorities, CloudNativePG for the wallet's database, the operator in this JVM, and the control
 * plane and the CLI as an owner uses them. No broker: nothing here publishes.
 *
 * `wallet` is the cart sample with `CART_WALLET=on`, the one service that must answer. `merchant`
 * and `psp-gateway` are `pause` with no database and no HTTP, deployed only so the platform issues
 * them their certificates: a call "from merchant" is a pod in merchant's namespace holding
 * merchant's certificate and nothing else (`curl` for HTTP and sockets, `grpcurl` for gRPC), so the
 * caller the wallet reads is the one the platform established. A k3s node running several sample
 * JVMs answers in seconds, which is why the callers run none.
 *
 * A grant is made, listed and revoked through the CLI. Whether the cluster has it is read where the
 * runtime reads it, the file every wallet pod mounts, so a step waits for the grant itself rather
 * than for a guessed interval; the reload interval is added once the file holds it.
 *
 * Disable with `-Dankka.cluster.tests=off`.
 */
class CrossProjectRouteGrantsFeatures
    extends GherkinSuite("../features/cross-project/route-grants.feature")
    with LogCapturing:

  override val munitTimeout: FiniteDuration = 15.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  override protected def ranElsewhere: Map[String, String] = Map(
    // That the refusal is recorded as refused, and says nothing, is read from the service's own
    // recorder, which the control plane reads with a certificate this JVM does not hold. The
    // refusal itself is this suite's, in the scenarios that follow.
    "a service of another project is refused a route nobody granted it" -> "GrantedCallerSuite",
    // A platform too old to mount grants cannot be deployed from this build; the status word and
    // the effect it gives a grant are the ingest's and the listing's.
    "a service deployed before the feature gains its grants volume on its next rollout" ->
      "StatusIngestSuite and GrantEffectSuite"
  )

  private val K3sImage = "rancher/k3s:v1.35.1-k3s1"
  private val SampleImage =
    s"sample-shopping-cart:${com.thinkmorestupidless.ankka.core.BuildInfo.imageTag}"
  private val GrpcurlImage = "fullstorydev/grpcurl:v1.9.1-alpine"
  private val Prefix       = "ankka"
  private val ReloadSlack  = 12.seconds

  private lazy val identity = TestIdentity()
  private lazy val Token = identity.token(
    "owner",
    Some("owner@example.test"),
    roles = Set("platform-admin"),
    expiresIn = 3.hours
  )

  private var k3s: K3sContainer               = null
  private var k8s: KubernetesClient           = null
  private var operator: Operator              = null
  private var testKit: AnkkaTestKit           = null
  private var url: String                     = ""
  private var config: Path                    = null
  private var organizations: Set[String]      = Set.empty
  private var projects: Set[String]           = Set.empty
  private var deployed: Set[(String, String)] = Set.empty

  private given ExecutionContext = ExecutionContext.global

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()
      ClusterImages.importInto(k3s, SampleImage)
      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      // Both resources: without `AnkkaProject` no grant reaches the operator, whose informer for it
      // is skipped quietly on a cluster that lacks the type.
      for crd <- Seq("ankkaservice.yaml", "ankkaproject.yaml", "ankkamachine.yaml") do
        k8s.load(getClass.getResourceAsStream(s"/ankka/crd/$crd")).serverSideApply(): Unit
      PkiStack.install(k3s, k8s)
      k8s
        .load(
          java.net.URI
            .create(
              "https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.30/releases/cnpg-1.30.0.yaml"
            )
            .toURL
            .openStream()
        )
        .serverSideApply(): Unit
      waitFor(180.seconds, "CloudNativePG's controller") {
        PkiStack.jsonPath(
          k3s,
          "deployment",
          "-n",
          "cnpg-system",
          "cnpg-controller-manager",
          "{.status.readyReplicas}"
        ) == "1"
      }
      val operatorSettings = OperatorSettings.default.copy(resyncInterval = 2.seconds)
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        progressDeadline = 300.seconds
      )
      val projector = ServiceProjector.withClient(
        deployConfig,
        new Fabric8AnkkaServiceClient(k8s, Prefix, resyncMillis = 2000L)
      )
      val server = HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(
          identity.acl(),
          deployConfig,
          auth = Some(identity.config()),
          logs = Some(new PodLogs(k8s, Prefix)),
          secrets = Some(projector),
          topics = Some(projector),
          schemas = Some(projector)
        )*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
      config = Files.createTempFile("ankka-route-grants", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString

  override def afterAll(): Unit =
    if testKit != null then identity.stop()
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  // ── the scenario's state ──────────────────────────────────────────────────

  /** The grants this scenario made, in order, each with the project that holds it. */
  private var grants: Vector[(String, String)]              = Vector.empty
  private var lastResponse: (Int, String)                   = (0, "")
  private var lastRoute: Option[(String, String, String)]   = None // caller, route, service
  private var restartsBefore: Map[String, Map[String, Int]] = Map.empty
  private var effectiveAfter: Option[FiniteDuration]        = None
  private var revokedAt: Option[Deadline]                   = None
  private var held: Vector[(String, Future[(Int, String)])] = Vector.empty
  private var lastGrpc: String                              = ""

  /** A player of this scenario's own, so no idempotency key or balance crosses scenarios. */
  private def player: String = s"p${math.abs(scenarioId.hashCode)}"

  override def beforeEach(context: BeforeEach): Unit =
    grants = Vector.empty
    lastResponse = (0, "")
    lastRoute = None
    restartsBefore = Map.empty
    effectiveAfter = None
    revokedAt = None
    held = Vector.empty
    lastGrpc = ""

  override def afterEach(context: AfterEach): Unit =
    if !munitIgnore then
      // A grant a scenario left live would open a route for the next one.
      for (id, project) <- grants do ankka("projects", "grants", "revoke", id, "-p", project): Unit
      if grants.nonEmpty then
        val ids = grants.map(_._1)
        waitFor(180.seconds, "the scenario's grants leaving the wallet's file") {
          mounted("wallet", "spinvibe").forall(file => ids.forall(id => !file.contains(id)))
        }
        Thread.sleep(ReloadSlack.toMillis)

  // ── the CLI and the cluster ───────────────────────────────────────────────

  private final case class Run(code: Int, out: String, err: String):
    def all: String = out + err

  private def ankka(args: String*): Run =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Main.run(
      args ++ Seq("--url", url, "--token", Token),
      PrintStream(out, true, StandardCharsets.UTF_8),
      PrintStream(err, true, StandardCharsets.UTF_8)
    )
    Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private def ok(run: Run): Run =
    assertEquals(run.code, 0, run.all)
    run

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = timeout.fromNow
    var passed   = false
    while !passed && deadline.hasTimeLeft() do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(1000)
    if !passed then fail(s"$what did not happen within $timeout${diagnosis()}")

  private def diagnosis(): String =
    if k3s == null then ""
    else
      projects.toVector
        .map(p => PkiStack.kubectl(k3s, "get", "pods,configmaps", "-n", ns(p), "-o", "wide"))
        .mkString("\n", "\n", "")

  private def ns(project: String) = s"$Prefix-$project"

  private def statusOf(name: String, project: String): Option[ServiceStatus] =
    val run = ankka("services", "get", name, "-p", project, "-o", "json")
    Option.when(run.code == 0)(readFromString[ServiceStatus](run.out))

  private def ensureOrganization(organization: String): Unit =
    if !organizations(organization) then
      val run = ankka("organizations", "create", organization, "--name", organization)
      assert(run.code == 0 || run.all.contains("already"), run.all)
      organizations += organization

  private def ensureProject(project: String, organization: String): Unit =
    if !projects(project) then
      val run =
        ankka("projects", "create", project, "--name", project, "--organization", organization)
      assert(run.code == 0 || run.all.contains("already"), run.all)
      projects += project

  /**
   * `wallet` as the sample serving the wallet over HTTP and gRPC; any other as `pause`, which needs
   * no database and serves nothing, and is given its certificate all the same.
   */
  private def deploy(service: String, project: String): Unit =
    if !deployed((service, project)) then
      val body =
        if service == "wallet" then
          s""""image":"$SampleImage","grpc":true,"env":[{"name":"CART_WALLET","value":"on"}]"""
        else """"image":"registry.k8s.io/pause:3.9","http":false,"database":"none""""
      val file = Files.createTempFile("ankka-route-grants", ".json")
      try
        Files.writeString(file, s"""{"name":"$service","service":{$body}}"""): Unit
        ok(ankka("services", "apply", "-f", file.toString, "-p", project)): Unit
      finally Files.deleteIfExists(file): Unit
      waitFor(360.seconds, s"$service of $project being Ready") {
        statusOf(service, project).exists(s =>
          s.lifecycle == ServiceLifecycle.Ready && s.readyInstances >= 1 && s.confirmed
        )
      }
      waitFor(120.seconds, s"$service's certificate") {
        k8s.secrets().inNamespace(ns(project)).withName(s"$service-service-tls").get() != null
      }
      deployed += ((service, project))

  private def podsOf(service: String, project: String) =
    k8s
      .pods()
      .inNamespace(ns(project))
      .withLabel("app.kubernetes.io/name", service)
      .list()
      .getItems
      .asScala
      .toVector

  /** Each pod of `service` by uid, with how often its containers have restarted. */
  private def restarts(service: String, project: String): Map[String, Int] =
    podsOf(service, project).map { pod =>
      pod.getMetadata.getUid ->
        Option(pod.getStatus.getContainerStatuses)
          .map(_.asScala.map(_.getRestartCount.intValue).sum)
          .getOrElse(0)
    }.toMap

  /** The grants file as each running pod of `service` mounts it. */
  private def mounted(service: String, project: String): Vector[String] =
    podsOf(service, project)
      .filter(_.getStatus.getPhase == "Running")
      .map(pod =>
        PkiStack.kubectl(
          k3s,
          "exec",
          "-n",
          ns(project),
          pod.getMetadata.getName,
          "-c",
          service,
          "--",
          "cat",
          "/var/run/ankka/project/grants.json"
        )
      )

  /** Which project each service of the background was deployed in. */
  private var projectOf: Map[String, String] = Map.empty

  // ── the callers ───────────────────────────────────────────────────────────

  private var holders: Set[String] = Set.empty

  /**
   * A pod in `service`'s namespace holding `service`'s certificate and nothing else, labelled as a
   * platform workload so the callee's network policy admits it. `curl` for HTTP and sockets,
   * `grpcurl` (with the wallet's proto) for gRPC.
   */
  private def holder(service: String, grpc: Boolean = false): String =
    val project = projectOf(service)
    val name    = if grpc then s"grpc-$service" else s"as-$service"
    if !holders(name) then
      if grpc then
        k8s
          .configMaps()
          .inNamespace(ns(project))
          .resource(
            new ConfigMapBuilder()
              .withMetadata(
                new ObjectMetaBuilder().withName("wallet-proto").withNamespace(ns(project)).build()
              )
              .withData(
                Map(
                  "wallet.proto" -> Files.readString(
                    Path.of(
                      "../samples/shopping-cart-api/src/main/protobuf/shoppingcart/v1/wallet.proto"
                    )
                  )
                ).asJava
              )
              .build()
          )
          .serverSideApply(): Unit
      val image = if grpc then GrpcurlImage else "curlimages/curl:8.11.1"
      val protos =
        if grpc then """        - { name: protos, mountPath: /protos, readOnly: true }
            |""".stripMargin
        else ""
      val protoVolume =
        if grpc then """    - name: protos
            |      configMap: { name: wallet-proto }
            |""".stripMargin
        else ""
      val manifest =
        s"""apiVersion: v1
           |kind: Pod
           |metadata:
           |  name: $name
           |  namespace: ${ns(project)}
           |  labels: { app.kubernetes.io/managed-by: ankka }
           |spec:
           |  containers:
           |    - name: caller
           |      image: $image
           |      command: ["sleep", "infinity"]
           |      volumeMounts:
           |        - { name: service, mountPath: /var/run/secrets/ankka/service, readOnly: true }
           |$protos  volumes:
           |    - name: service
           |      secret: { secretName: $service-service-tls }
           |$protoVolume""".stripMargin
      k3s.copyFileToContainer(
        Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
        s"/tmp/$name.yaml"
      )
      PkiStack.kubectl(k3s, "apply", "-f", s"/tmp/$name.yaml"): Unit
      PkiStack.kubectl(
        k3s,
        "wait",
        "-n",
        ns(project),
        "--for=condition=Ready",
        s"pod/$name",
        "--timeout=180s"
      ): Unit
      holders += name
    name

  /** A route's text, `METHOD /path/{template}`, as a path with this scenario's values. */
  private def split(route: String): (String, String) =
    val (method, template) = route.trim.span(_ != ' ')
    val path = template.trim
      .replace("{player}", player)
      .replace("{currency}", "eur")
    (method.toUpperCase, path)

  private def walletUrl(path: String, scheme: String = "https"): String =
    s"$scheme://wallet.${ns(projectOf("wallet"))}.svc.cluster.local:9000$path"

  /** A request from `caller` to `route` of `service`, as one platform workload calls another. */
  private def request(caller: String, route: String): (Int, String) =
    val (method, path) = split(route)
    val deposit        = path.endsWith("/deposits")
    InPod.curl(
      k3s,
      ns(projectOf(caller)),
      holder(caller),
      walletUrl(path),
      method = method,
      body = Option.when(deposit)("""{"amount":5}"""),
      headers = if deposit then Seq(s"Idempotency-Key: ${java.util.UUID.randomUUID()}") else Nil
    )

  /** A gRPC call from `caller` to `method` of the wallet, answered as the status's words. */
  private def grpcCall(caller: String, definition: String, method: String): String =
    val project = projectOf(caller)
    val dir     = "/var/run/secrets/ankka/service"
    val data =
      if method == "Deposit" then
        s"""{"player":"$player","currency":"eur","amount":5,"idempotencyKey":"${java.util.UUID
            .randomUUID()}"}"""
      else s"""{"player":"$player","currency":"eur"}"""
    val result = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      ns(project),
      holder(caller, grpc = true),
      "--",
      "grpcurl",
      "-cert",
      s"$dir/tls.crt",
      "-key",
      s"$dir/tls.key",
      "-cacert",
      s"$dir/ca.crt",
      "-import-path",
      "/protos",
      "-proto",
      "wallet.proto",
      "-d",
      data,
      s"wallet.${ns(projectOf("wallet"))}.svc.cluster.local:9090",
      s"shoppingcart.v1.$definition/$method"
    )
    if result.getExitCode == 0 then "ok"
    else
      val said = result.getStdout + result.getStderr
      "Code: (\\w+)".r
        .findFirstMatchIn(said)
        .map(_.group(1).replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase)
        .getOrElse(s"no status: $said")

  // ── grants ────────────────────────────────────────────────────────────────

  /**
   * Makes a grant through the CLI and waits until every wallet pod's file holds it, and the reload
   * interval after: the grant is then what the wallet decides by.
   */
  private def grant(grantee: String, target: Seq[String], project: String): Unit =
    if restartsBefore.isEmpty then
      restartsBefore = Map(
        "wallet"   -> restarts("wallet", projectOf("wallet")),
        "merchant" -> restarts("merchant", projectOf("merchant"))
      )
    val made = Deadline.now
    val run = ok(
      ankka(
        Seq("projects", "grants", "make", grantee) ++ target ++ Seq("-p", project, "-o", "json")*
      )
    )
    val detail = readFromString[GrantDetail](run.out)
    assertEquals(detail.state, GrantState.Accepted, run.all)
    grants :+= (detail.id -> project)
    waitFor(180.seconds, s"grant ${detail.id} reaching every wallet pod") {
      val files = mounted("wallet", projectOf("wallet"))
      files.nonEmpty && files.forall(_.contains(detail.id))
    }
    Thread.sleep(ReloadSlack.toMillis)
    effectiveAfter = Some(-made.timeLeft)

  private def routeTarget(service: String, route: String): Seq[String] =
    val (method, template) = route.trim.span(_ != ' ')
    Seq("route", service, method, template.trim)

  private def revokeAll(): Unit =
    for (id, project) <- grants do
      ok(ankka("projects", "grants", "revoke", id, "-p", project)): Unit
    revokedAt = Some(Deadline.now)
    grants = Vector.empty

  private def assertNoRestarts(): Unit =
    for (service, before) <- restartsBefore do
      assertEquals(restarts(service, projectOf(service)), before, s"$service's pods and restarts")

  // ═══ Given ════════════════════════════════════════════════════════════════

  Given("the projects {string} and {string} of the organization {string}") {
    (a: String, b: String, organization: String) =>
      ensureOrganization(organization)
      ensureProject(a, organization)
      ensureProject(b, organization)
  }

  Given("a deployed service {string} in {string}") { (service: String, project: String) =>
    projectOf += service -> project
    deploy(service, project)
  }

  Given(
    "{string} has an HTTP endpoint whose ACL admits granted callers, with the route {string}"
  ) { (service: String, route: String) =>
    // The sample's WalletEndpoint, which the wallet serves under CART_WALLET=on.
    assertEquals(service, "wallet")
    assert(route.contains("/v1/wallets/"), route)
  }

  Given(
    "{string} has an HTTP endpoint whose ACL admits only the service {string}, with the route {string}"
  ) { (service: String, only: String, route: String) =>
    // The sample's `GET /v1/wallets/{player}`, under `Callers.service("lobby")`.
    assertEquals((service, only, route), ("wallet", "lobby", "GET /v1/wallets/{player}"))
  }

  Given("the endpoint has the route {string} too")((route: String) =>
    assertEquals(route, "GET /v1/wallets/{player}/{currency}")
  )

  Given(
    "the endpoint declares the socket route {string} and the route {string}, which answers as a stream"
  ) { (socket: String, stream: String) =>
    assertEquals(
      (socket, stream),
      ("/v1/wallets/{player}/events", "GET /v1/wallets/{player}/ledger")
    )
  }

  Given(
    "{string} has a gRPC endpoint for the service definition {string} whose ACL admits granted callers"
  ) { (service: String, definition: String) =>
    assertEquals((service, definition), ("wallet", "WalletService"))
  }

  Given(
    "an owner of {string} has granted the service {string} of {string} the route {string} of {string}"
  ) { (_: String, grantee: String, granteeProject: String, route: String, service: String) =>
    grant(s"service:$granteeProject/$grantee", routeTarget(service, route), projectOf(service))
  }

  Given(
    "an owner of {string} has granted the service {string} of {string} the socket route {string} of {string}"
  ) { (_: String, grantee: String, granteeProject: String, path: String, service: String) =>
    // A socket is opened by a GET, and granted as one.
    grant(
      s"service:$granteeProject/$grantee",
      routeTarget(service, s"GET $path"),
      projectOf(service)
    )
  }

  Given("the owner has granted the service {string} of {string} the route {string} of {string}") {
    (grantee: String, granteeProject: String, route: String, service: String) =>
      grant(s"service:$granteeProject/$grantee", routeTarget(service, route), projectOf(service))
  }

  Given(
    "an owner of {string} has granted the service {string} of {string} the method {string} of {string}"
  ) { (_: String, grantee: String, granteeProject: String, method: String, service: String) =>
    grant(s"service:$granteeProject/$grantee", Seq("method", service, method), projectOf(service))
  }

  Given("{string} seconds have passed") { (seconds: String) =>
    val within = seconds.toInt.seconds
    val took   = effectiveAfter.getOrElse(fail("no grant was made"))
    assert(took <= within, s"the grant took $took to reach the wallet, more than $within")
  }

  Given("{string} has been served that route") { (caller: String) =>
    val route = "POST /v1/wallets/{player}/{currency}/deposits"
    lastResponse = request(caller, route)
    assertEquals(lastResponse._1, 200, lastResponse._2)
    lastRoute = Some((caller, route, "wallet"))
  }

  Given(
    "{string} holds a socket open to {string} of {string} and is being answered a stream by {string}"
  ) { (caller: String, socket: String, service: String, stream: String) =>
    val project         = projectOf(caller)
    val pod             = holder(caller)
    val (_, socketPath) = split(s"GET $socket")
    val (_, streamPath) = split(stream)
    // Both outlive any revocation the scenario waits for, so ending is the platform's doing.
    held = Vector(
      "socket" -> Future(
        InPod.curl(k3s, ns(project), pod, walletUrl(socketPath, "wss"), maxSeconds = 300)
      ),
      "stream" -> Future(
        InPod.curl(k3s, ns(project), pod, walletUrl(streamPath), maxSeconds = 300)
      )
    )
    Thread.sleep(5000)
    for (what, open) <- held do
      assert(!open.isCompleted, s"the $what ended before anything was revoked: ${open.value}")
    assertEquals(service, "wallet")
  }

  // ═══ When ═════════════════════════════════════════════════════════════════

  When("{string} sends a request to the route {string} of {string}") {
    (caller: String, route: String, service: String) =>
      lastResponse = request(caller, route)
      lastRoute = Some((caller, route, service))
  }

  When("the owner revokes the grant")(() => revokeAll())

  When("the owner revokes both grants") { () =>
    assertEquals(grants.size, 2)
    revokeAll()
  }

  When("{string} calls the method {string} of {string} of {string}") {
    (caller: String, method: String, definition: String, service: String) =>
      assertEquals(service, "wallet")
      lastGrpc = grpcCall(caller, definition, method)
  }

  // ═══ Then ═════════════════════════════════════════════════════════════════

  Then("{string} is refused") { (caller: String) =>
    assertEquals(lastRoute.map(_._1), Some(caller))
    assertEquals(lastResponse._1, 403, lastResponse._2)
  }

  Then("no handler runs") { () =>
    // Every wallet handler answers 200 with its value; a 403 is the ACL's, before any handler.
    assertEquals(lastResponse._1, 403, lastResponse._2)
    assert(!lastResponse._2.contains("balance"), lastResponse._2)
  }

  Then("the handler runs") { () =>
    assertEquals(lastResponse._1, 200, lastResponse._2)
    assert(lastResponse._2.contains("\"applied\":true"), lastResponse._2)
  }

  Then("the handler reads the calling workload as the service {string} of the project {string}") {
    (service: String, project: String) =>
      // The deposit answers the caller its handler read.
      assertEquals(lastResponse._1, 200, lastResponse._2)
      assert(lastResponse._2.contains(s"\"by\":\"service:$project/$service\""), lastResponse._2)
  }

  Then("no instance of {string} or of {string} was restarted")((_: String, _: String) =>
    assertNoRestarts()
  )

  Then("no instance of {string} or of {string} is restarted")((_: String, _: String) =>
    assertNoRestarts()
  )

  Then("within {string} seconds {string} is refused that route") {
    (seconds: String, caller: String) =>
      val (_, route, _) = lastRoute.getOrElse(fail("no route was served"))
      val deadline      = revokedAt.getOrElse(fail("nothing was revoked")) + seconds.toInt.seconds
      var last          = (0, "")
      while deadline.hasTimeLeft() && last._1 != 403 do
        last = request(caller, route)
        if last._1 != 403 then Thread.sleep(2000)
      assertEquals(last._1, 403, s"still answered within $seconds seconds of the revocation: $last")
  }

  Then("the grant is shown as not in effect, {string}, in the grants of {string}") {
    (word: String, project: String) =>
      val id    = grants.lastOption.map(_._1).getOrElse(fail("no grant was made"))
      var shown = ""
      waitFor(120.seconds, s"grant $id shown as '$word'") {
        val run = ankka("projects", "grants", "list", "-p", project, "-o", "json")
        shown =
          readFromString[Vector[GrantDetail]](run.out).find(_.id == id).fold("absent")(_.effect)
        shown == word
      }
  }

  Then("the call ends with the status {string}")((status: String) => assertEquals(lastGrpc, status))

  Then(
    "a call from {string} to the method {string} of {string} of {string} ends with the status {string}"
  ) { (caller: String, method: String, definition: String, service: String, status: String) =>
    assertEquals(service, "wallet")
    assertEquals(grpcCall(caller, definition, method), status)
  }

  Then("within {string} seconds the socket is closed and the stream is ended") {
    (seconds: String) =>
      val deadline = revokedAt.getOrElse(fail("nothing was revoked")) + seconds.toInt.seconds
      for (what, open) <- held do
        val left = deadline.timeLeft
        val ended =
          try Some(Await.result(open, left.max(1.second)))
          catch case _: java.util.concurrent.TimeoutException => None
        assert(ended.isDefined, s"the $what was still open $seconds seconds after the revocation")
  }

  Then("{string} is refused when it opens the socket again") { (caller: String) =>
    val (_, path) = split("GET /v1/wallets/{player}/events")
    val again = InPod.curl(
      k3s,
      ns(projectOf(caller)),
      holder(caller),
      walletUrl(path, "wss"),
      maxSeconds = 10
    )
    assertEquals(again._1, 403, again._2)
  }
