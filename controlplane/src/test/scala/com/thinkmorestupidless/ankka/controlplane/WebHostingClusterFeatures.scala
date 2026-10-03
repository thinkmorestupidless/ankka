package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.{
  InstanceType,
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
import com.thinkmorestupidless.ankka.crd.{AnkkaSerialization, AnkkaService}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  GatewayStack,
  Operator,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * `features/web-hosting/deploying.feature` on k3s, with the control plane and the operator running
 * in this JVM as `EndToEndClusterSuite` runs them, cert-manager and the installation's authorities,
 * and Envoy Gateway in front.
 *
 * Every workload is real: the operator's rendering, the proxy's image, and a stand-in process image
 * a developer could have written, built here under this build's tag. A browser is `curl` on the
 * host through the gateway's mapped port with `--resolve`, the route a developer's browser takes; a
 * request counts as answered by its status, never by curl's exit code.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the images.
 */
class DeployingWebHostingClusterFeatures
    extends GherkinSuite("../features/web-hosting/deploying.feature")
    with LogCapturing:

  override val munitTimeout: FiniteDuration = 10.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  private val K3sImage   = "rancher/k3s:v1.35.1-k3s1"
  private val Tag        = com.thinkmorestupidless.ankka.core.BuildInfo.version.replace('+', '-')
  private val ProxyImage = s"ankka-proxy:$Tag"
  private val BaseDomain = "web.example.test"
  private val Prefix     = "ankka"
  private val Project    = "shop"
  private val Namespace  = s"$Prefix-$Project"

  /**
   * The stand-in process, three ways: two builds that say which they are, and one that never
   * listens.
   */
  private val Images = Map(
    "shop-web"   -> s"web-echo-1:$Tag",
    "shop-web:1" -> s"web-echo-1:$Tag",
    "shop-web:2" -> s"web-echo-2:$Tag",
    "silent"     -> s"web-echo-silent:$Tag"
  )

  private lazy val identity = TestIdentity()
  private lazy val Token = identity.token(
    "tester",
    Some("tester@example.test"),
    roles = Set("platform-admin"),
    expiresIn = 2.hours
  )

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null
  private var testKit: AnkkaTestKit = null
  private var url: String           = ""
  private var config: Path          = null
  private var ca: Path              = null
  private var httpsPort: Int        = 0

  private def repoRoot: Path =
    var dir = Paths.get("").toAbsolutePath
    while !Files.exists(dir.resolve("build.sbt")) do dir = dir.getParent
    dir

  override def beforeAll(): Unit =
    if !munitIgnore then
      buildImages()
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort, GatewayStack.HttpNodePort)
      k3s.start()
      httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)
      for image <- ProxyImage +: Images.values.toVector.distinct do
        ClusterImages.importInto(k3s, image)

      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      k8s.load(getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")).serverSideApply(): Unit
      // No CloudNativePG: a web-hosted service has no database, and nothing here asks for one.
      GatewayStack.install(k3s, k8s, repoRoot, BaseDomain)
      ca = GatewayStack.exportCa(k8s)

      val operatorSettings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        proxyImage = ProxyImage,
        baseDomain = Some(BaseDomain),
        httpsPort = httpsPort
      )
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        baseDomain = Some(BaseDomain),
        // Short enough that a process that never listens is Failed within a scenario.
        progressDeadline = 60.seconds
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
          logs = Some(new PodLogs(k8s, Prefix))
        )*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

      config = Files.createTempFile("ankka-web-cluster", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString

      ok(ankka("organizations", "create", "acme", "--name", "Acme"))
      ok(ankka("projects", "create", Project, "--name", "Shop", "--organization", "acme"))

  override def afterAll(): Unit =
    stopBrowser()
    if testKit != null then identity.stop()
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  /**
   * Builds the stand-in's three images under this build's tag; nothing is named by a literal tag.
   */
  private def buildImages(): Unit =
    val dir = repoRoot.resolve("controlplane/src/test/docker/echo-process").toString
    def build(image: String, args: String*): Unit =
      val command = Vector("docker", "build", "-q", "-t", image) ++
        args.flatMap(a => Vector("--build-arg", a)) :+ dir
      val result = new ProcessBuilder(command*).redirectErrorStream(true).start()
      val output = new String(result.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      if result.waitFor() != 0 then fail(s"docker build of $image failed:\n$output")
    build(Images("shop-web:1"), "BUILD=1")
    build(Images("shop-web:2"), "BUILD=2")
    build(Images("silent"), "BUILD=silent", "LISTEN=none")

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
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(500)
    if !passed then fail(s"$what did not happen within $timeout${diagnosis()}")

  private def statusOf(name: String, project: String = Project): Option[ServiceStatus] =
    val run = ankka("services", "get", name, "-p", project, "-o", "json")
    Option.when(run.code == 0)(readFromString[ServiceStatus](run.out))

  private def pods(name: String): Vector[Pod] =
    k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("app.kubernetes.io/name", name)
      .list()
      .getItems
      .asScala
      .toVector
      .filter(_.getMetadata.getDeletionTimestamp == null)

  private def ready(pod: Pod): Boolean =
    Option(pod.getStatus)
      .flatMap(s => Option(s.getConditions))
      .exists(_.asScala.exists(c => c.getType == "Ready" && c.getStatus == "True"))

  private def resource(name: String): Option[AnkkaService] =
    Option(k8s.resources(classOf[AnkkaService]).inNamespace(Namespace).withName(name).get())

  /** What to read when a wait fails: the resource's status and each pod's containers. */
  private def diagnosis(): String =
    if k8s == null then ""
    else
      val status = resource(service).flatMap(r => Option(r.getStatus)).map(_.toString).getOrElse("")
      val podLines = pods(service).map { p =>
        val cs =
          Option(p.getStatus.getContainerStatuses).map(_.asScala.toVector).getOrElse(Vector.empty)
        s"${p.getMetadata.getName}: " + cs
          .map(c => s"${c.getName} ready=${c.getReady}")
          .mkString(", ")
      }
      // Every pod of the namespace, terminating ones too, and what each container last printed:
      // a container that crashed says why only in its own log.
      def node(args: String*): String =
        val r = k3s.execInContainer(args*)
        r.getStdout + r.getStderr
      val described = node("kubectl", "get", "pods", "-n", Namespace, "-o", "wide")
      val logs = k8s.pods().inNamespace(Namespace).list().getItems.asScala.toVector.flatMap { pod =>
        val name = pod.getMetadata.getName
        pod.getSpec.getContainers.asScala.toVector.map { c =>
          val current = node("kubectl", "logs", "-n", Namespace, name, "-c", c.getName, "--tail=30")
          val previous = node(
            "kubectl",
            "logs",
            "-n",
            Namespace,
            name,
            "-c",
            c.getName,
            "--previous",
            "--tail=30"
          )
          s"── $name/${c.getName}\n$current\n(previous)\n$previous"
        }
      }
      val events = node("kubectl", "get", "events", "-n", Namespace, "--sort-by=.lastTimestamp")
      s"\nresource status: $status\npods:\n${podLines.mkString("\n")}\n$described\n" +
        s"${logs.mkString("\n")}\nevents:\n${events.linesIterator.toVector.takeRight(30).mkString("\n")}"

  // ── the scenario's service ────────────────────────────────────────────────

  private var service: String                                  = "web"
  private var image: String                                    = Images("shop-web")
  private var instances: Int                                   = 1
  private var processPort: Option[Int]                         = None
  private var instanceType: String                             = "small"
  private var mounts: Vector[(String, String)]                 = Vector.empty
  private var appliedAt: Instant                               = Instant.EPOCH
  private var last: Run                                        = Run(0, "", "")
  private var lastStatus: Option[ServiceStatus]                = None
  private var restartedAt: Instant                             = Instant.EPOCH
  private var stoppedPod: String                               = ""
  private var survivingPod: String                             = ""
  private var gatewayReply: (Int, Map[String, String], String) = (0, Map.empty, "")

  override def beforeEach(context: BeforeEach): Unit =
    if !munitIgnore then
      stopBrowser()
      service = "web"
      image = Images("shop-web")
      instances = 1
      processPort = None
      instanceType = "small"
      mounts = Vector.empty
      last = Run(0, "", "")
      lastStatus = None
      stoppedPod = ""
      survivingPod = ""
      // Each scenario starts from no service at all: its own deployment, its own pods.
      if statusOf("web").isDefined then
        ok(ankka("services", "delete", "web", "-p", Project))
        waitFor(90.seconds, "the previous scenario's web going away") {
          k8s.apps().deployments().inNamespace(Namespace).withName("web").get() == null &&
          pods("web").isEmpty
        }

  private def descriptor: String =
    val fields = Vector(
      Some(s""""image":"$image""""),
      Some(""""hosting":"web""""),
      processPort.map(p => s""""processPort":$p"""),
      Some(
        s""""resources":{"instanceType":"$instanceType","autoscaling":{"minInstances":$instances}}"""
      ),
      Option.when(mounts.nonEmpty)(
        mounts
          .map((path, svc) => s"""{"path":"$path","service":"$svc"}""")
          .mkString(""""mounts":[""", ",", "]")
      )
    ).flatten
    s"""{"name":"$service","service":{${fields.mkString(",")}}}"""

  private def apply(project: String = Project): Run =
    val file = Files.createTempFile("ankka-web", ".json")
    try
      Files.writeString(file, descriptor): Unit
      appliedAt = Instant.now()
      ankka("services", "apply", "-f", file.toString, "-p", project)
    finally Files.deleteIfExists(file): Unit

  private def readyWith(n: Int, within: FiniteDuration = 120.seconds): Unit =
    waitFor(within, s"$service being Ready with $n instance(s)") {
      statusOf(service).exists(s =>
        s.lifecycle == ServiceLifecycle.Ready && s.readyInstances == n && s.confirmed
      ) && pods(service).count(ready) == n
    }

  private def deploy(): Unit =
    ok(apply())
    readyWith(instances)

  // ── the browser: curl on the host, through the gateway ────────────────────

  private def hostname = s"$service-$Project.$BaseDomain"

  /** The status, the headers in lower case, and the body. 0 when there was no answer at all. */
  private def browse(path: String = "/"): (Int, Map[String, String], String) =
    val command = Vector(
      "curl",
      "-sS",
      "-m",
      "10",
      "-D",
      "-",
      "--cacert",
      ca.toString,
      "--resolve",
      s"$hostname:$httpsPort:127.0.0.1",
      s"https://$hostname:$httpsPort$path"
    )
    val process = new ProcessBuilder(command*).redirectErrorStream(false).start()
    val output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val errors  = new String(process.getErrorStream.readAllBytes(), StandardCharsets.UTF_8)
    val code    = process.waitFor()
    val split   = output.indexOf("\r\n\r\n")
    // No answer at all: curl's own exit code and message, so a failure says which kind it was.
    if split < 0 then (0, Map.empty, s"curl exited $code: ${errors.trim} ${output.take(200)}")
    else
      val head   = output.substring(0, split).split("\r\n").toVector
      val status = head.headOption.flatMap(_.split(" ").lift(1)).flatMap(_.toIntOption).getOrElse(0)
      val headers = head
        .drop(1)
        .flatMap { line =>
          line.split(":", 2) match
            case Array(k, v) => Some(k.trim.toLowerCase -> v.trim)
            case _           => None
        }
        .toMap
      (status, headers, output.substring(split + 4))

  private def expose(): Unit =
    ok(ankka("services", "expose", service, "-p", Project))
    waitFor(90.seconds, s"$hostname answering through the gateway") {
      val (status, headers, _) = browse()
      status == 200 && headers.contains("x-instance")
    }

  private val browsing        = new AtomicBoolean(false)
  private val answered        = new ConcurrentLinkedQueue[Int]()
  private var browser: Thread = null

  private def startBrowser(): Unit =
    answered.clear()
    browsing.set(true)
    browser =
      Thread.ofVirtual().start(() => while browsing.get() do answered.add(browse()._1): Unit)

  private def stopBrowser(): Unit =
    browsing.set(false)
    if browser != null then browser.join(15_000)
    browser = null

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("an image {string} whose process serves requests and has nothing of the platform in it") {
    (name: String) => assert(Images.contains(name), name)
  }

  Given("an image {string} whose process listens for nothing") { (name: String) =>
    assertEquals(Images(name), Images("silent"))
  }

  Given("a web-hosted service {string} deployed in the project {string}") {
    (name: String, project: String) =>
      assertEquals(project, Project)
      service = name
      deploy()
  }

  Given("a web-hosted service {string} deployed in the project {string}, with {int} instance(s)") {
    (name: String, project: String, n: Int) =>
      assertEquals(project, Project)
      service = name
      instances = n
      deploy()
  }

  Given("a web-hosted service {string} deployed in the project {string}, paused") {
    (name: String, project: String) =>
      assertEquals(project, Project)
      service = name
      deploy()
      ok(ankka("services", "pause", service, "-p", Project))
      waitFor(90.seconds, s"$service pausing")(pods(service).isEmpty)
  }

  Given("a web-hosted service {string} deployed with the image {string} and {int} instance(s)") {
    (name: String, imageName: String, n: Int) =>
      service = name
      image = Images(imageName)
      instances = n
      deploy()
  }

  Given("a web-hosted service {string} deployed with {int} instance(s)") { (name: String, n: Int) =>
    service = name
    instances = n
    deploy()
  }

  Given("a web-hosted service {string} whose process has printed {string}") {
    (name: String, line: String) =>
      assertEquals(line, "listening", "the stand-in prints only this")
      service = name
      deploy()
  }

  Given(
    "a web-hosted service {string} deployed in the project {string} with {string} mounted at {string}"
  ) { (name: String, project: String, mounted: String, path: String) =>
    assertEquals(project, Project)
    service = name
    mounts = Vector(path -> mounted)
    deploy()
  }

  Given("{string} is not exposed") { (name: String) =>
    assertEquals(name, service)
    ok(ankka("services", "unexpose", service, "-p", Project))
  }

  Given("{string} is exposed") { (name: String) =>
    assertEquals(name, service)
    expose()
  }

  Given("a browser sending requests to {string} one after another") { (name: String) =>
    assertEquals(name, service)
    expose()
    startBrowser()
  }

  Given("a descriptor for the web-hosted service {string} that states no port for its process") {
    (name: String) =>
      service = name
      processPort = None
  }

  Given(
    "a descriptor for the web-hosted service {string} that states the port {string} for its process"
  ) { (name: String, port: String) =>
    service = name
    processPort = Some(port.toInt)
  }

  Given("a descriptor for the web-hosted service {string} that asks for the size {string}") {
    (name: String, size: String) =>
      service = name
      instanceType = size
  }

  Given("an organization that has every service its quota allows") { () =>
    ok(ankka("organizations", "create", "full", "--name", "Full"))
    ok(ankka("projects", "create", "full-shop", "--name", "Full shop", "--organization", "full"))
    ok(ankka("organizations", "quota", "set", "full", "--services", "0"))
  }

  Given("no service {string} in the project {string}") { (name: String, project: String) =>
    assertEquals(statusOf(name, project), None)
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When(
    "a member applies a descriptor for the web-hosted service {string} with the image {string}"
  ) { (name: String, imageName: String) =>
    service = name
    image = Images(imageName)
    last = ok(apply())
  }

  When("a member applies the descriptor") { () =>
    last = ok(apply())
  }

  When("a member applies the descriptor of {string} with the image {string}") {
    (name: String, imageName: String) =>
      assertEquals(name, service)
      image = Images(imageName)
      last = ok(apply())
  }

  When("a member applies the descriptor of {string} with {string} mounted at {string}") {
    (name: String, mounted: String, path: String) =>
      assertEquals(name, service)
      mounts = Vector(path -> mounted)
      last = ok(apply())
  }

  When(
    "a member applies a descriptor for the web-hosted service {string} in a project of that organization"
  ) { (name: String) =>
    service = name
    last = apply(project = "full-shop")
  }

  When("a member reads the status of {string}") { (name: String) =>
    assertEquals(name, service)
    last = ok(ankka("services", "get", service, "-p", Project))
    lastStatus = statusOf(service)
  }

  When("a person on the internet sends a request to the hostname of {string}") { (name: String) =>
    assertEquals(name, service)
    // Long enough for a route, had one been rendered, to have been accepted.
    Thread.sleep(5_000)
    gatewayReply = browse()
  }

  When("a browser sends a request to the hostname of {string}") { (name: String) =>
    assertEquals(name, service)
    gatewayReply = browse()
  }

  When("a member scales {string} to {int} instances") { (name: String, n: Int) =>
    assertEquals(name, service)
    instances = n
    last = ok(apply())
  }

  When("a member restarts {string}") { (name: String) =>
    assertEquals(name, service)
    restartedAt = Instant.now()
    last = ok(ankka("services", "restart", service, "-p", Project))
  }

  When("a member pauses {string}") { (name: String) =>
    assertEquals(name, service)
    last = ok(ankka("services", "pause", service, "-p", Project))
  }

  When("a member resumes {string}") { (name: String) =>
    assertEquals(name, service)
    last = ok(ankka("services", "resume", service, "-p", Project))
  }

  When("the process of {int} instance stops listening for requests") { (n: Int) =>
    assertEquals(n, 1)
    expose()
    val Vector(first, second) = pods(service).map(_.getMetadata.getName).sorted: @unchecked
    stoppedPod = first
    survivingPod = second
    val result = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      Namespace,
      stoppedPod,
      "-c",
      s"$service-app",
      "--",
      "node",
      "-e",
      s"fetch('http://127.0.0.1:${processPort.getOrElse(8080)}/stop-listening',{method:'POST'}).then(r=>console.log(r.status))"
    )
    assertEquals(result.getStdout.trim, "200", result.getStderr)
  }

  When("a member reads the logs of {string}") { (name: String) =>
    assertEquals(name, service)
    last = ok(ankka("services", "logs", service, "-p", Project))
  }

  When("a member reads the logs of the proxy of {string}") { (name: String) =>
    assertEquals(name, service)
    last = ok(ankka("services", "logs", service, "-p", Project, "--platform"))
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  Then("{string} is ready once its process listens for requests") { (name: String) =>
    assertEquals(name, service)
    readyWith(1)
    // SC-003: Ready within thirty seconds of the apply, with the image already on the node.
    val took = java.time.Duration.between(appliedAt, Instant.now())
    // How long the proxy's JVM took from its container starting to serving, for research R19.
    val pod   = pods(service).head
    val start = pod.getStatus.getContainerStatuses.asScala.find(_.getName == service).get.getState
    val log = k3s
      .execInContainer("kubectl", "logs", "-n", Namespace, pod.getMetadata.getName, "-c", service)
      .getStdout
    println(
      s"R19: proxy container started ${Option(start.getRunning).map(_.getStartedAt).getOrElse("?")}; " +
        s"its log: ${log.linesIterator.toVector.take(3).mkString(" | ")}; apply to Ready ${took.toMillis}ms"
    )
    assert(took.toSeconds < 30, s"$service took ${took.toSeconds}s from apply to Ready")
  }

  Then("{string} is ready once its process listens there") { (name: String) =>
    assertEquals(name, service)
    readyWith(1)
  }

  Then("{string} is ready") { (name: String) =>
    assertEquals(name, service)
    readyWith(instances)
  }

  Then("the status says that {string} has no database") { (name: String) =>
    assertEquals(name, service)
    assertEquals(lastStatus.flatMap(_.database), Some("none"))
    assert(last.out.linesIterator.exists(_.matches("database\\s+none")), last.out)
  }

  Then("no database exists for {string}") { (name: String) =>
    assertEquals(name, service)
    // Nothing reported about one, no credential written for one, and nothing in the pod for one.
    assertEquals(resource(service).flatMap(r => Option(r.getStatus)).flatMap(_.database), None)
    assertEquals(k8s.secrets().inNamespace(Namespace).withName(s"$service-db").get(), null)
    for pod <- pods(service) do
      assert(pod.getSpec.getInitContainers.isEmpty, pod.getSpec.getInitContainers.toString)
      for c <- pod.getSpec.getContainers.asScala do
        assert(!c.getEnv.asScala.exists(_.getName.startsWith("ANKKA_DB_")), c.getName)
  }

  Then("the request reaches no instance of {string}") { (name: String) =>
    assertEquals(name, service)
    val (status, headers, body) = gatewayReply
    assertNotEquals(status, 200, body)
    assert(!headers.contains("x-instance"), s"an instance answered: $headers")
  }

  Then("the browser is shown what the process of {string} answered") { (name: String) =>
    assertEquals(name, service)
    val (status, headers, body) = gatewayReply
    assertEquals(status, 200, body)
    assert(body.contains("build 1"), body)
    assert(
      pods(service).map(_.getMetadata.getName).contains(headers.getOrElse("x-instance", "")),
      headers.toString
    )
  }

  Then("{string} is ready with {int} instance(s)") { (name: String, n: Int) =>
    assertEquals(name, service)
    readyWith(n)
  }

  Then("{string} is ready with {int} instance that started after the restart") {
    (name: String, n: Int) =>
      assertEquals(name, service)
      readyWith(n)
      waitFor(120.seconds, "every instance being one started after the restart") {
        val current = pods(service)
        current.size == n && current.forall(p =>
          Instant.parse(p.getMetadata.getCreationTimestamp).isAfter(restartedAt.minusSeconds(1))
        )
      }
  }

  Then("the status of {string} is {string} and {string} has no instances") {
    (name: String, lifecycle: String, again: String) =>
      assertEquals(name, service)
      assertEquals(again, service)
      waitFor(90.seconds, s"$service being $lifecycle with no instances") {
        statusOf(service).exists(_.lifecycle.toString == lifecycle) && pods(service).isEmpty
      }
  }

  Then("the status of {string} is {string}") { (name: String, lifecycle: String) =>
    assertEquals(name, service)
    waitFor(180.seconds, s"$service being $lifecycle") {
      statusOf(service).exists(_.lifecycle.toString == lifecycle)
    }
  }

  Then("the status says that the process did not listen for requests") { () =>
    val detail = statusOf(service).flatMap(_.detail).getOrElse("")
    println(s"the detail k3s gave a process that never listened: $detail")
    assert(detail.startsWith("the process is not listening on port 8080"), detail + diagnosis())
  }

  Then("every request the browser sends is answered") { () =>
    readyWith(instances, within = 180.seconds)
    waitFor(120.seconds, "the old instances leaving") {
      pods(service).forall(p => p.getSpec.getContainers.asScala.exists(_.getImage == image))
    }
    stopBrowser()
    val all = answered.asScala.toVector
    assert(all.size >= 20, s"only ${all.size} requests were sent")
    assertEquals(all.filterNot(_ == 200), Vector.empty, s"of ${all.size} requests")
  }

  Then("{string} is ready with the image {string}") { (name: String, imageName: String) =>
    assertEquals(name, service)
    assertEquals(statusOf(service).map(_.image), Some(Images(imageName)))
    // An old instance keeps serving through its preStop pause while it leaves, so the new build is
    // what every request is answered by once the old instances are gone, not at the first moment.
    val build = s"build ${imageName.stripPrefix("shop-web:")}"
    waitFor(30.seconds, s"every request answered by $build") {
      Vector.fill(5)(browse()).forall((status, _, body) => status == 200 && body.contains(build))
    }
  }

  private def processEnv(name: String): Map[String, String] =
    val pod = pods(name).headOption.getOrElse(fail(s"no pod of $name"))
    pod.getSpec.getContainers.asScala
      .find(_.getName == s"$name-app")
      .getOrElse(fail("no process container"))
      .getEnv
      .asScala
      .map(e => e.getName -> Option(e.getValue).getOrElse(""))
      .toMap

  Then("the process of {string} is told to listen on the port the platform gives") {
    (name: String) =>
      assertEquals(name, service)
      waitFor(60.seconds, "a pod")(pods(service).nonEmpty)
      assertEquals(processEnv(service).get("PORT"), Some("8080"))
  }

  Then("the process of {string} is told to listen on the port {string}") {
    (name: String, port: String) =>
      assertEquals(name, service)
      waitFor(60.seconds, "a pod")(pods(service).nonEmpty)
      assertEquals(processEnv(service).get("PORT"), Some(port))
  }

  Then("that instance is not ready") { () =>
    waitFor(60.seconds, s"$stoppedPod going not ready") {
      pods(service).find(_.getMetadata.getName == stoppedPod).exists(p => !ready(p))
    }
  }

  Then("every request to {string} is answered by the process of the other instance") {
    (name: String) =>
      assertEquals(name, service)
      // The gateway learns the endpoint is gone within a moment of the pod going not ready.
      Thread.sleep(3_000)
      val replies = Vector.fill(10)(browse())
      for (status, headers, body) <- replies do
        assertEquals(status, 200, body)
        assertEquals(headers.get("x-instance"), Some(survivingPod))
  }

  Then("the logs show {string}") { (line: String) =>
    assert(last.out.contains(line), last.out)
  }

  Then("the logs show nothing the proxy printed") { () =>
    assert(!last.out.contains("serving shop/"), last.out)
  }

  Then("the logs show what the proxy printed") { () =>
    assert(last.out.contains(s"serving $Project/$service on port 9000"), last.out)
  }

  Then("the logs show nothing the process printed") { () =>
    assert(!last.out.contains("listening\n"), last.out)
  }

  Then("the member is refused") { () =>
    assertEquals(last.code, 1, last.all)
  }

  Then("the refusal says that the organization has every service its quota allows") { () =>
    assert(last.err.contains("has reached its quota of 0 service(s)"), last.err)
  }

  Then("the status says that the hosting of {string} is web hosting") { (name: String) =>
    assertEquals(name, service)
    assert(last.out.linesIterator.exists(_.matches("hosting\\s+web")), last.out)
    assertEquals(lastStatus.map(_.hosting), Some("web"))
  }

  Then("the status shows the mount of {string} at {string}") { (mounted: String, path: String) =>
    assert(
      last.out.linesIterator.exists(l => l.contains(path) && l.contains(s"→ $mounted")),
      last.out
    )
    assert(lastStatus.exists(_.mounts.exists(m => m.path == path && m.service == mounted)))
  }

  Then("the status shows which services {string} admits") { (name: String) =>
    assertEquals(name, service)
    assert(last.out.linesIterator.exists(_.matches("callers\\s+the internet.*")), last.out)
  }

  Then("the process of {string} has the size {string}") { (name: String, size: String) =>
    assertEquals(name, service)
    waitFor(60.seconds, "a pod")(pods(service).nonEmpty)
    val expected = InstanceType.byName(size).getOrElse(fail(size))
    val app =
      pods(service).head.getSpec.getContainers.asScala.find(_.getName == s"$service-app").get
    // As amounts: the API server writes 1000m back as 1 and 1024Mi as 1Gi.
    val limits = app.getResources.getLimits.asScala.view
      .mapValues(q =>
        BigDecimal(
          io.fabric8.kubernetes.api.model.Quantity.getAmountInBytes(q)
        ).bigDecimal.stripTrailingZeros
      )
      .toMap
    assertEquals(
      limits,
      Map(
        "cpu" -> io.fabric8.kubernetes.api.model.Quantity
          .getAmountInBytes(new io.fabric8.kubernetes.api.model.Quantity(s"${expected.cpuMillis}m"))
          .stripTrailingZeros,
        "memory" -> io.fabric8.kubernetes.api.model.Quantity
          .getAmountInBytes(
            new io.fabric8.kubernetes.api.model.Quantity(s"${expected.memoryMiB}Mi")
          )
          .stripTrailingZeros
      )
    )
    assertEquals(app.getResources.getRequests, app.getResources.getLimits)
  }

  Then("the proxy takes nothing from the size of the process") { () =>
    val proxy = pods(service).head.getSpec.getContainers.asScala.find(_.getName == service).get
    assertEquals(
      proxy.getResources.getLimits.asScala.view.mapValues(_.toString).toMap,
      Map("cpu" -> "250m", "memory" -> "192Mi")
    )
  }

  Then("the status of {string} marks the mount of {string} as having no service") {
    (name: String, mounted: String) =>
      assertEquals(name, service)
      assert(
        statusOf(service).exists(
          _.mounts.exists(m => m.service == mounted && m.state == "no service")
        ),
        statusOf(service).toString
      )
  }
