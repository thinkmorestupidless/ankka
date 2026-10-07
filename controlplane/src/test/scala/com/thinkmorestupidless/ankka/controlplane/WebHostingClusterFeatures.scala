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
  ObjectStoreStack,
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
abstract class WebHostingClusterSteps(
    feature: String,
    withDatabases: Boolean = false,
    withObjectStore: Boolean = false
) extends GherkinSuite(feature)
    with LogCapturing:

  override val munitTimeout: FiniteDuration = 10.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  protected val K3sImage    = "rancher/k3s:v1.35.1-k3s1"
  protected val Tag         = com.thinkmorestupidless.ankka.core.BuildInfo.version.replace('+', '-')
  protected val ProxyImage  = s"ankka-proxy:$Tag"
  protected val SampleImage = s"sample-shopping-cart:$Tag"
  protected val BaseDomain  = "web.example.test"
  protected val Prefix      = "ankka"
  protected val Project     = "shop"
  protected val Namespace   = s"$Prefix-$Project"

  /**
   * The stand-in process, three ways: two builds that say which they are, and one that never
   * listens.
   */
  protected val Images = Map(
    "shop-web"   -> s"web-echo-1:$Tag",
    "shop-web:1" -> s"web-echo-1:$Tag",
    "shop-web:2" -> s"web-echo-2:$Tag",
    "silent"     -> s"web-echo-silent:$Tag"
  )

  protected lazy val identity = TestIdentity()
  protected lazy val Token = identity.token(
    "tester",
    Some("tester@example.test"),
    roles = Set("platform-admin"),
    expiresIn = 2.hours
  )

  protected var k3s: K3sContainer     = null
  protected var k8s: KubernetesClient = null
  protected var operator: Operator    = null
  protected var testKit: AnkkaTestKit = null
  protected var url: String           = ""
  protected var config: Path          = null
  protected var ca: Path              = null
  protected var httpsPort: Int        = 0

  protected def repoRoot: Path =
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
      GatewayStack.install(k3s, k8s, repoRoot, BaseDomain)
      ca = GatewayStack.exportCa(k8s)
      // A web-hosted service has no database; CloudNativePG is installed only for a suite that also
      // deploys a service that has one.
      if withDatabases then
        ClusterImages.importInto(k3s, SampleImage)
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
        waitFor(120.seconds, "CloudNativePG's controller") {
          val d = k8s
            .apps()
            .deployments()
            .inNamespace("cnpg-system")
            .withName("cnpg-controller-manager")
            .get()
          d != null && Option(d.getStatus).flatMap(st => Option(st.getReadyReplicas)).exists(_ > 0)
        }

      // The installation's object store, for a suite whose services ask for buckets (feature 034).
      if withObjectStore then objectStore = ObjectStoreStack.install(k3s, k8s, repoRoot)
      val operatorSettings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        proxyImage = ProxyImage,
        baseDomain = Some(BaseDomain),
        httpsPort = httpsPort,
        objectStore = Option(objectStore).map(_.settings)
      )
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        baseDomain = Some(BaseDomain),
        // Short enough that a process that never listens is Failed within a scenario; long enough,
        // with a database, for a project's first one to be provisioned.
        progressDeadline = if withDatabases then 240.seconds else 60.seconds
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
      ok(ankka("projects", "create", Project, "--name", "Shop", "--organization", "acme")): Unit

  override def afterAll(): Unit =
    stopBrowser()
    if testKit != null then identity.stop()
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if objectStore != null then objectStore.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  protected var objectStore: ObjectStoreStack.Installed = null

  /**
   * Builds the stand-in's three images under this build's tag; nothing is named by a literal tag.
   */
  protected def buildImages(): Unit =
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

  protected final case class Run(code: Int, out: String, err: String):
    def all: String = out + err

  protected def ankka(args: String*): Run =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Main.run(
      args ++ Seq("--url", url, "--token", Token),
      PrintStream(out, true, StandardCharsets.UTF_8),
      PrintStream(err, true, StandardCharsets.UTF_8)
    )
    Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  protected def ok(run: Run): Run =
    assertEquals(run.code, 0, run.all)
    run

  protected def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(500)
    if !passed then fail(s"$what did not happen within $timeout${diagnosis()}")

  protected def statusOf(name: String, project: String = Project): Option[ServiceStatus] =
    val run = ankka("services", "get", name, "-p", project, "-o", "json")
    Option.when(run.code == 0)(readFromString[ServiceStatus](run.out))

  protected def pods(name: String): Vector[Pod] =
    k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("app.kubernetes.io/name", name)
      .list()
      .getItems
      .asScala
      .toVector
      .filter(_.getMetadata.getDeletionTimestamp == null)

  protected def ready(pod: Pod): Boolean =
    Option(pod.getStatus)
      .flatMap(s => Option(s.getConditions))
      .exists(_.asScala.exists(c => c.getType == "Ready" && c.getStatus == "True"))

  protected def resource(name: String): Option[AnkkaService] =
    Option(k8s.resources(classOf[AnkkaService]).inNamespace(Namespace).withName(name).get())

  /** What to read when a wait fails: the resource's status and each pod's containers. */
  protected def diagnosis(): String =
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

  protected var service: String                                  = "web"
  protected var image: String                                    = Images("shop-web")
  protected var instances: Int                                   = 1
  protected var processPort: Option[Int]                         = None
  protected var instanceType: String                             = "small"
  protected var mounts: Vector[(String, String)]                 = Vector.empty
  protected var provisionObjectStorage: Boolean                  = false
  protected var appliedAt: Instant                               = Instant.EPOCH
  protected var last: Run                                        = Run(0, "", "")
  protected var lastStatus: Option[ServiceStatus]                = None
  protected var restartedAt: Instant                             = Instant.EPOCH
  protected var stoppedPod: String                               = ""
  protected var survivingPod: String                             = ""
  protected var gatewayReply: (Int, Map[String, String], String) = (0, Map.empty, "")

  override def beforeEach(context: BeforeEach): Unit =
    if !munitIgnore then
      stopBrowser()
      service = "web"
      image = Images("shop-web")
      instances = 1
      processPort = None
      instanceType = "small"
      mounts = Vector.empty
      provisionObjectStorage = false
      last = Run(0, "", "")
      lastStatus = None
      stoppedPod = ""
      survivingPod = ""
      // Each scenario starts from no web-hosted service: its own deployment, its own pods. Other
      // services a scenario deployed are deleted too; the cart, which takes a database a minute to
      // provision, is kept for whoever asks for it next.
      for name <- Vector("web", "orders") if statusOf(name).isDefined do
        ok(ankka("services", "delete", name, "-p", Project))
        waitFor(90.seconds, s"the previous scenario's $name going away") {
          k8s.apps().deployments().inNamespace(Namespace).withName(name).get() == null &&
          pods(name).isEmpty
        }

  protected def descriptor: String =
    val fields = Vector(
      Some(s""""image":"$image""""),
      Some(""""hosting":"web""""),
      processPort.map(p => s""""processPort":$p"""),
      Some(
        s""""resources":{"instanceType":"$instanceType","autoscaling":{"minInstances":$instances}}"""
      ),
      Option.when(provisionObjectStorage)(""""provisionObjectStorage":true"""),
      Option.when(mounts.nonEmpty)(
        mounts
          .map((path, svc) => s"""{"path":"$path","service":"$svc"}""")
          .mkString(""""mounts":[""", ",", "]")
      )
    ).flatten
    s"""{"name":"$service","service":{${fields.mkString(",")}}}"""

  protected def apply(project: String = Project): Run =
    val file = Files.createTempFile("ankka-web", ".json")
    try
      Files.writeString(file, descriptor): Unit
      appliedAt = Instant.now()
      ankka("services", "apply", "-f", file.toString, "-p", project)
    finally Files.deleteIfExists(file): Unit

  protected def readyWith(n: Int, within: FiniteDuration = 120.seconds): Unit =
    waitFor(within, s"$service being Ready with $n instance(s)") {
      statusOf(service).exists(s =>
        s.lifecycle == ServiceLifecycle.Ready && s.readyInstances == n && s.confirmed
      ) && pods(service).count(ready) == n
    }

  protected def deploy(): Unit =
    ok(apply())
    readyWith(instances)

  // ── the browser: curl on the host, through the gateway ────────────────────

  protected def hostname = s"$service-$Project.$BaseDomain"

  /** The status, the headers in lower case, and the body. 0 when there was no answer at all. */
  protected def browse(path: String = "/"): (Int, Map[String, String], String) =
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

  /**
   * Exposes the service and waits for its process to answer through the gateway. By default that is
   * the echo process, which names its instance; a process that does not passes what its own answer
   * looks like.
   */
  protected def expose(
      answers: ((Int, Map[String, String], String)) => Boolean = (status, headers, _) =>
        status == 200 && headers.contains("x-instance")
  ): Unit =
    ok(ankka("services", "expose", service, "-p", Project))
    waitFor(90.seconds, s"$hostname answering through the gateway") {
      answers(browse())
    }

  /** A page from the process itself, not an answer of the proxy's. */
  protected val aPage: ((Int, Map[String, String], String)) => Boolean = (status, headers, _) =>
    status == 200 && !headers.contains("x-ankka-answered-by")

  protected val browsing        = new AtomicBoolean(false)
  protected val answered        = new ConcurrentLinkedQueue[Int]()
  protected var browser: Thread = null

  protected def startBrowser(): Unit =
    answered.clear()
    browsing.set(true)
    browser =
      Thread.ofVirtual().start(() => while browsing.get() do answered.add(browse()._1): Unit)

  protected def stopBrowser(): Unit =
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

  When("a member reads the report of {string}") { (name: String) =>
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

  Then("the report says that {string} has no database") { (name: String) =>
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

  Then("the lifecycle of {string} is {string} and {string} has no instances") {
    (name: String, lifecycle: String, again: String) =>
      assertEquals(name, service)
      assertEquals(again, service)
      waitFor(90.seconds, s"$service being $lifecycle with no instances") {
        statusOf(service).exists(_.lifecycle.toString == lifecycle) && pods(service).isEmpty
      }
  }

  Then("the lifecycle of {string} is {string}") { (name: String, lifecycle: String) =>
    assertEquals(name, service)
    waitFor(180.seconds, s"$service being $lifecycle") {
      statusOf(service).exists(_.lifecycle.toString == lifecycle)
    }
  }

  Then("the report says that the process did not listen for requests") { () =>
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

  protected def processEnv(name: String): Map[String, String] =
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

  Then("the report says that the hosting of {string} is web hosting") { (name: String) =>
    assertEquals(name, service)
    assert(last.out.linesIterator.exists(_.matches("hosting\\s+web")), last.out)
    assertEquals(lastStatus.map(_.hosting), Some("web"))
  }

  Then("the report shows the mount of {string} at {string}") { (mounted: String, path: String) =>
    assert(
      last.out.linesIterator.exists(l => l.contains(path) && l.contains(s"→ $mounted")),
      last.out
    )
    assert(lastStatus.exists(_.mounts.exists(m => m.path == path && m.service == mounted)))
  }

  Then("the report shows which services {string} admits") { (name: String) =>
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

  Then("the report of {string} marks the mount of {string} as having no service") {
    (name: String, mounted: String) =>
      assertEquals(name, service)
      assert(
        statusOf(service).exists(
          _.mounts.exists(m => m.service == mounted && m.state == "no service")
        ),
        statusOf(service).toString
      )
  }

/**
 * `features/web-hosting/object-storage.feature` on k3s (feature 034): a web-hosted service with a
 * bucket, whose process keeps and reads an object with the variables it was given, and whose proxy
 * is given none of them.
 */
class WebHostingObjectStorageFeatures
    extends WebHostingClusterSteps(
      "../features/web-hosting/object-storage.feature",
      withObjectStore = true
    ):

  private def envOf(container: String): Map[String, String] =
    val pod = pods(service).find(ready).getOrElse(fail(s"$service has no ready pod"))
    val r = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      Namespace,
      pod.getMetadata.getName,
      "-c",
      container,
      "--",
      "env"
    )
    r.getStdout.linesIterator
      .flatMap(l => l.split("=", 2) match { case Array(k, v) => Some(k -> v); case _ => None })
      .toMap

  /** A signed request from inside the process's container: its status, and the body. */
  private def s3(method: String, obj: String, upload: Option[String] = None): (Int, String) =
    val pod = pods(service).find(ready).getOrElse(fail(s"$service has no ready pod"))
    // The payload's hash, sent as its own header: a curl before 8 signs without it, and the store
    // refuses a request that does not say it (`Missing X-Amz-Content-Sha256`).
    val body = upload.fold("printf '' > /tmp/upload && ")(content =>
      s"printf '%s' '$content' > /tmp/upload && "
    ) + "hash=$(sha256sum /tmp/upload | cut -d' ' -f1) && "
    val send = upload.fold("")(_ => "-T /tmp/upload ")
    val r = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      Namespace,
      pod.getMetadata.getName,
      "-c",
      s"$service-app",
      "--",
      "sh",
      "-c",
      body +
        s"""curl -s -o /tmp/answer -w '%{http_code}' -X $method $send-H "x-amz-content-sha256: $$hash" --aws-sigv4 "aws:amz:$$ANKKA_S3_REGION:s3" """ +
        s"""--user "$$ANKKA_S3_ACCESS_KEY:$$ANKKA_S3_SECRET_KEY" "$$ANKKA_S3_ENDPOINT/$$ANKKA_S3_BUCKET/$obj"; echo; cat /tmp/answer"""
    )
    val lines = (r.getStdout + r.getStderr).linesIterator.toVector
    (lines.headOption.flatMap(_.trim.toIntOption).getOrElse(0), lines.drop(1).mkString("\n"))

  private def awaitBucket(): Unit =
    waitFor(240.seconds, s"$service's bucket being provisioned") {
      statusOf(service).exists(
        _.objectStorage.exists(p => p == "provisioned" || p.startsWith("recovered"))
      )
    }

  Given("a descriptor for the web-hosted service {string} that asks for a bucket") {
    (name: String) =>
      service = name
      provisionObjectStorage = true
  }

  Given("a web-hosted service {string} deployed with a bucket") { (name: String) =>
    service = name
    provisionObjectStorage = true
    deploy()
    awaitBucket()
  }

  Then(
    "the process of {string} is given the variables {string}, {string}, {string}, {string} and {string}"
  ) { (name: String, a: String, b: String, c: String, d: String, e: String) =>
    assertEquals(name, service)
    readyWith(instances)
    val env = envOf(s"$service-app")
    for v <- Vector(a, b, c, d, e) do assert(env.get(v).exists(_.nonEmpty), s"$v is not set")
  }

  Then("the proxy of {string} is given none of them") { (name: String) =>
    assertEquals(name, service)
    assertEquals(envOf(service).keySet.filter(_.startsWith("ANKKA_S3_")), Set.empty[String])
  }

  When(
    "the process of {string} keeps the object {string} in its bucket with what its variables say"
  ) { (name: String, obj: String) =>
    assertEquals(name, service)
    val (code, body) = s3("PUT", obj, Some(s"contents of $obj"))
    assertEquals(code, 200, body)
  }

  Then("the process of {string} reads the object {string} back from its bucket") {
    (name: String, obj: String) =>
      assertEquals(name, service)
      assertEquals(s3("GET", obj), (200, s"contents of $obj"))
  }

/** `features/web-hosting/deploying.feature` on k3s. */
class DeployingWebHostingClusterFeatures
    extends WebHostingClusterSteps("../features/web-hosting/deploying.feature")

/**
 * `features/web-hosting/isolation.feature` on k3s: what the network refuses, which only a cluster
 * can show, and a real call from a web-hosted service's process to the shopping cart sample, read
 * from the cart's side.
 */
class IsolationWebHostingClusterFeatures
    extends WebHostingClusterSteps(
      "../features/web-hosting/isolation.feature",
      withDatabases = true
    ):

  /** A web-hosted service the scenario deploys beside "web", holding a certificate of its own. */
  private def deployAnother(name: String): Unit =
    val file = Files.createTempFile("ankka-other", ".json")
    try
      Files.writeString(
        file,
        s"""{"name":"$name","service":{"image":"${Images("shop-web")}","hosting":"web"}}"""
      ): Unit
      ok(ankka("services", "apply", "-f", file.toString, "-p", Project))
    finally Files.deleteIfExists(file): Unit
    waitFor(120.seconds, s"$name being Ready") {
      statusOf(name).exists(_.lifecycle == ServiceLifecycle.Ready) && pods(name).exists(ready)
    }

  /** curl's exit code and output, from the pod holding "orders"'s certificate. */
  private def fromOrders(url: String): (Int, String) =
    val prober = com.thinkmorestupidless.ankka.operator.InPod.prober(k3s, Namespace, "orders")
    val r = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      Namespace,
      prober,
      "--",
      "curl",
      "-sS",
      "-m",
      "5",
      "-o",
      "/dev/null",
      "-w",
      "%{http_code}",
      url
    )
    (r.getExitCode, r.getStdout + r.getStderr)

  private def webPod: Pod = pods("web").find(ready).getOrElse(fail("no ready instance of web"))

  private var connection: (Int, String) = (0, "")

  Given("a service {string} deployed in the project {string}") { (name: String, project: String) =>
    assertEquals(project, Project)
    deployAnother(name)
  }

  When("the service {string} connects to the process of {string} without the proxy") {
    (caller: String, name: String) =>
      assertEquals((caller, name), ("orders", "web"))
      val ip = webPod.getStatus.getPodIP
      // The control: the same pod reaches the same instance on the port the network admits, so a
      // refusal below is the policy's and not a prober, an address or a selector gone wrong.
      assertEquals(fromOrders(s"http://$ip:7627/ready"), (0, "200"))
      connection = fromOrders(s"http://$ip:8080/")
  }

  When("the service {string} connects to the calling address of an instance of {string}") {
    (caller: String, name: String) =>
      assertEquals((caller, name), ("orders", "web"))
      val pod = webPod
      assertEquals(fromOrders(s"http://${pod.getStatus.getPodIP}:7627/ready"), (0, "200"))
      connection = fromOrders(s"http://${pod.getStatus.getPodIP}:7630/")
      // And the calling address is there to be refused: the process beside the proxy reaches it,
      // and is answered by the proxy itself.
      val inside = k3s.execInContainer(
        "kubectl",
        "exec",
        "-n",
        Namespace,
        pod.getMetadata.getName,
        "-c",
        "web-app",
        "--",
        "node",
        "-e",
        "fetch('http://127.0.0.1:7630/').then(r=>console.log(r.status, r.headers.get('x-ankka-answered-by')))"
      )
      assertEquals(inside.getStdout.trim, "400 proxy", inside.getStderr)
  }

  Then("the connection is refused") { () =>
    val (code, output) = connection
    // 7: refused; 28: timed out, which is how a policy that drops a connection shows. A missing
    // curl, or a 2xx, is neither.
    assert(code == 7 || code == 28, s"curl exited $code: $output")
  }

  When("an instance of {string} starts") { (name: String) =>
    assertEquals(name, "web")
    waitFor(60.seconds, "an instance of web")(pods("web").exists(ready))
  }

  Then("the process holds no certificate") { () =>
    val app = webPod.getSpec.getContainers.asScala.find(_.getName == "web-app").get
    assertEquals(app.getVolumeMounts.asScala.toVector, Vector.empty)
    assert(
      !app.getEnv.asScala.exists(e => Option(e.getValueFrom).exists(_.getSecretKeyRef != null)),
      "the process is given a secret"
    )
  }

  Then("the proxy holds the certificate of {string}") { (name: String) =>
    val pod     = webPod
    val proxy   = pod.getSpec.getContainers.asScala.find(_.getName == name).get
    val mounted = proxy.getVolumeMounts.asScala.map(m => m.getName -> m.getMountPath).toMap
    assertEquals(mounted.get("ankka-service-tls"), Some("/var/run/secrets/ankka/service"))
    val volume = pod.getSpec.getVolumes.asScala.find(_.getName == "ankka-service-tls").get
    assertEquals(volume.getSecret.getSecretName, s"$name-service-tls")
  }

  test(
    "a web-hosted service's process calls the shopping cart as itself, and the cart's rule decides"
  ) {
    assume(!munitIgnore, "cluster tests are off")
    // The cart: a real ankka service with a database, not exposed.
    val file = Files.createTempFile("ankka-cart", ".json")
    try
      Files.writeString(file, s"""{"name":"cart","service":{"image":"$SampleImage"}}"""): Unit
      ok(ankka("services", "apply", "-f", file.toString, "-p", Project))
    finally Files.deleteIfExists(file): Unit
    waitFor(300.seconds, "the cart being Ready") {
      statusOf("cart").exists(_.lifecycle == ServiceLifecycle.Ready) && pods("cart").exists(ready)
    }
    service = "web"
    deploy()
    expose()
    // Through the gateway, the echo process fetches the cart at the calling address.
    def callThrough(path: String): String =
      val (status, _, body) = browse(s"/call?path=$path")
      assertEquals(status, 200, body)
      body
    val whoami = callThrough("/cart/callers/whoami")
    assert(whoami.contains(""""status":200"""), whoami)
    assert(whoami.contains(s"the web service in project $Project"), whoami)
    val refused = callThrough("/cart/callers/only-orders")
    assert(
      refused.contains(""""status":403"""),
      s"the cart's refusal did not reach the process: $refused"
    )
  }

  // ── features/web-hosting/template.feature, the two scenarios only a cluster can show ─────────

  private val SampleWebImage = s"sample-shopping-cart-web:$Tag"

  /** A request through the gateway with a JSON body: the status and the body. */
  private def post(path: String, json: String): (Int, String) =
    val command = Vector(
      "curl",
      "-sS",
      "-m",
      "10",
      "-o",
      "-",
      "-w",
      "\n%{http_code}",
      "--cacert",
      ca.toString,
      "--resolve",
      s"$hostname:$httpsPort:127.0.0.1",
      "-H",
      "Content-Type: application/json",
      "--data",
      json,
      s"https://$hostname:$httpsPort$path"
    )
    val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
    val output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    process.waitFor()
    val at = output.lastIndexOf('\n')
    (output.substring(at + 1).trim.toIntOption.getOrElse(0), output.substring(0, math.max(at, 0)))

  /** Applies a descriptor file, its image replaced by one this build made. */
  private def applyWithImage(descriptor: Path, image: String): Unit =
    val text =
      Files.readString(descriptor).replaceAll(""""image"\s*:\s*"[^"]*"""", s""""image": "$image"""")
    val file = Files.createTempFile("ankka-applied", ".json")
    try
      Files.writeString(file, text): Unit
      ok(ankka("services", "apply", "-f", file.toString, "-p", Project)): Unit
    finally Files.deleteIfExists(file): Unit

  private def ensureCart(): Unit =
    if !statusOf("cart").exists(_.lifecycle == ServiceLifecycle.Ready) then
      val file = Files.createTempFile("ankka-cart", ".json")
      try
        Files.writeString(file, s"""{"name":"cart","service":{"image":"$SampleImage"}}"""): Unit
        ok(ankka("services", "apply", "-f", file.toString, "-p", Project))
      finally Files.deleteIfExists(file): Unit
      waitFor(300.seconds, "the cart being Ready") {
        statusOf("cart").exists(_.lifecycle == ServiceLifecycle.Ready) && pods("cart").exists(ready)
      }

  test("the shopping cart sample has an interface") {
    assume(!munitIgnore, "cluster tests are off")
    ClusterImages.importInto(k3s, SampleWebImage)
    ensureCart()
    service = "cart-web"
    applyWithImage(repoRoot.resolve("samples/shopping-cart-web/service.json"), SampleWebImage)
    readyWith(1)
    expose(aPage)
    // Under the mount: the cart, reached at the interface's own address.
    val (added, addedBody) =
      post("/api/cart/carts/sample/items", """{"productId":"tea","name":"Tea","quantity":2}""")
    assert(
      added >= 200 && added < 300,
      s"adding an item under the mount answered $added: $addedBody"
    )
    val (read, _, cartBody) = browse("/api/cart/carts/sample")
    assertEquals(read, 200, cartBody)
    assert(cartBody.contains("Tea"), cartBody)
    // The interface's server, reading the cart at the calling address.
    val (summarised, _, summary) = browse("/summary?cart=sample")
    assertEquals(summarised, 200, summary)
    assert(summary.contains(""""status":200"""), summary)
    assert(summary.contains("2"), s"the total the server read: $summary")
    // The cart has no address of its own: it is not exposed and no route names it. Its endpoint
    // admits every caller, so it would answer another service too; a real application's backend
    // would admit only the internet and its interface.
    assert(ankka("services", "get", "cart", "-p", Project).out.contains("not exposed"))
    assertEquals(
      k8s
        .resources(classOf[io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute])
        .inNamespace(Namespace)
        .withName("cart")
        .get(),
      null
    )
    assertEquals(
      statusOf("cart-web").map(_.mounts.map(m => m.service -> m.state)),
      Some(Vector("cart" -> "ok"))
    )
  }

  test("a web-hosted service started from the template is deployed to a local platform") {
    assume(!munitIgnore, "cluster tests are off")
    val workspace = Files.createTempDirectory("ankka-web-template-cluster")
    val rendered  = workspace.resolve("shop-web")
    // `init` talks to no control plane, so it takes none of the connection flags `ankka` adds.
    val rendering = ByteArrayOutputStream()
    assertEquals(
      Main.run(
        Seq("init", "shop-web", "--language", "web", "--dir", workspace.toString),
        PrintStream(rendering, true, StandardCharsets.UTF_8),
        PrintStream(rendering, true, StandardCharsets.UTF_8)
      ),
      0,
      rendering.toString(StandardCharsets.UTF_8)
    )
    def inProject(command: String*): Unit =
      val process = new ProcessBuilder(command*).directory(rendered.toFile).inheritIO().start()
      assertEquals(process.waitFor(), 0, command.mkString(" "))
    // The lockfile its Dockerfile installs from, then the image, built with its own Dockerfile.
    inProject("npm", "install", "--no-audit", "--no-fund")
    val image = s"shop-web:$Tag"
    inProject("docker", "build", "-q", "-t", image, ".")
    ClusterImages.importInto(k3s, image)
    service = "shop-web"
    applyWithImage(rendered.resolve("service.json"), image)
    readyWith(1)
    expose(aPage)
    val (status, _, page) = browse("/")
    assertEquals(status, 200, page)
    assert(page.contains("""<div id="root">"""), page)
    assertEquals(
      statusOf("shop-web").map(_.mounts.map(m => m.service -> m.state)),
      Some(Vector("backend" -> "no service"))
    )
  }
