package com.thinkmorestupidless.ankka.controlplane

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.ControlPlaneAcl
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  Fabric8AnkkaServiceClient,
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
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * Feature 005, from outside the cluster: a service exposed through the real CLI and driven by
 * `curl` on the *host* — through the gateway's mapped NodePort, with the certificate verified
 * against the local CA's exported root, `--resolve` standing in for DNS. The same path a
 * developer's machine takes to the kind cluster, and the only proof that the whole chain — control
 * plane, resource, operator, route, gateway, certificate — routes a request to a ready instance.
 *
 * Nothing here ever disables verification; the suite asserts that about its own curl arguments.
 */
class ExposureClusterSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 30.minutes

  override def munitIgnore: Boolean =
    sys.props.get("ankka.cluster.tests").contains("off") || !curlAvailable

  private def curlAvailable: Boolean =
    try new ProcessBuilder("curl", "--version").start().waitFor() == 0
    catch case _: Exception => false

  private val K3sImage    = "rancher/k3s:v1.35.1-k3s1"
  private val SampleImage = "sample-shopping-cart:latest"
  private val Token       = "exposure-token"
  private val Prefix      = "ankka"
  private val Project     = "checkout"
  private val Other       = "returns"
  private val Service     = "cart"
  private val BaseDomain  = "test.local"

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null
  private var testKit: AnkkaTestKit = null
  private var url: String           = ""
  private var config: Path          = null
  private var ca: Path              = null
  private var httpsPort: Int        = 0
  private var httpPort: Int         = 0

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort, GatewayStack.HttpNodePort)
      k3s.start()
      ClusterImages.importInto(k3s, SampleImage)
      httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)
      httpPort = k3s.getMappedPort(GatewayStack.HttpNodePort)

      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      k8s.load(getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")).serverSideApply(): Unit
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
      waitFor(120.seconds) {
        val d =
          k8s
            .apps()
            .deployments()
            .inNamespace("cnpg-system")
            .withName("cnpg-controller-manager")
            .get()
        d != null && Option(d.getStatus).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
      }
      GatewayStack.install(k3s, k8s, repoRoot, BaseDomain)
      ca = GatewayStack.exportCa(k8s)

      val operatorSettings =
        OperatorSettings.default.copy(resyncInterval = 2.seconds, baseDomain = Some(BaseDomain))
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        progressDeadline = 170.seconds,
        baseDomain = Some(BaseDomain)
      )
      val projector = ServiceProjector.withClient(
        deployConfig,
        new Fabric8AnkkaServiceClient(k8s, Prefix, resyncMillis = 2000L)
      )
      val server = HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(ControlPlaneAcl.bearer(Token), deployConfig)*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

      config = Files.createTempFile("ankka-exposure", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString

  override def afterAll(): Unit =
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if ca != null then Files.deleteIfExists(ca): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  // ---- plumbing ------------------------------------------------------------------------------

  private def repoRoot: Path =
    var dir = java.nio.file.Paths.get("").toAbsolutePath
    while !Files.exists(dir.resolve("build.sbt")) do dir = dir.getParent
    dir

  private def ankka(args: String*): (Int, String) =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Main.run(
      args ++ Seq("--url", url, "--token", Token),
      PrintStream(out, true, StandardCharsets.UTF_8),
      PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))

  private def waitFor(timeout: FiniteDuration)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(500)
    if !passed then fail(s"condition did not hold within $timeout")

  private def apply(project: String, name: String, instances: Int = 1): Unit =
    val file = Files.createTempFile(name, ".json")
    Files.writeString(
      file,
      s"""{"name":"$name","service":{"image":"$SampleImage","resources":{"autoscaling":{"minInstances":$instances}}}}"""
    )
    val (code, out) = ankka("services", "apply", "-f", file.toString, "-p", project)
    Files.deleteIfExists(file): Unit
    assertEquals(code, 0, s"apply failed: $out")

  private def status(project: String, name: String) =
    Option(
      k8s.resources(classOf[AnkkaService]).inNamespace(s"$Prefix-$project").withName(name).get()
    ).flatMap(r => Option(r.getStatus))

  private def waitReady(project: String, name: String, instances: Int): Unit =
    val deadline = System.nanoTime() + 300.seconds.toNanos
    var ready    = false
    while !ready && System.nanoTime() < deadline do
      status(project, name).foreach { s =>
        assert(s.lifecycle != "Failed", s"$project/$name Failed on the way up: ${s.detail}")
        ready = s.lifecycle == "Ready" && s.readyInstances == instances
      }
      if !ready then Thread.sleep(500)
    assert(ready, s"$project/$name never reached $instances Ready; last: ${status(project, name)}")

  private def pods(project: String, name: String): Vector[Pod] =
    k8s
      .pods()
      .inNamespace(s"$Prefix-$project")
      .withLabel("app.kubernetes.io/name", name)
      .list()
      .getItems
      .asScala
      .toVector

  private def routeOf(project: String, name: String): Option[HTTPRoute] =
    Option(
      k8s.resources(classOf[HTTPRoute]).inNamespace(s"$Prefix-$project").withName(name).get()
    )

  private def host(project: String, name: String) = s"$name-$project.$BaseDomain"

  /**
   * `curl` on the host, through the gateway's mapped port, verifying against the exported root.
   * `--resolve` stands in for DNS; SNI and hostname verification still happen against the real
   * name. Returns the HTTP status and the body.
   */
  private def curl(
      hostname: String,
      path: String,
      method: String = "GET",
      body: Option[String] = None,
      https: Boolean = true
  ): (Int, String) =
    val port   = if https then httpsPort else httpPort
    val scheme = if https then "https" else "http"
    val args = Vector(
      "curl",
      "-sS",
      "--cacert",
      ca.toString,
      "--resolve",
      s"$hostname:$port:127.0.0.1",
      "-m",
      "10",
      "-o",
      "-",
      "-w",
      "\n%{http_code}",
      "-X",
      method
    ) ++ body.toVector.flatMap(b => Vector("-H", "content-type: application/json", "-d", b)) :+
      s"$scheme://$hostname:$port$path"
    // SC-010, asserted about this suite's own arguments: nothing here skips verification.
    assert(!args.exists(a => a == "-k" || a == "--insecure"), args.mkString(" "))
    val process = new ProcessBuilder(args*).redirectErrorStream(true).start()
    val output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    process.waitFor()
    val lines = output.linesIterator.toVector
    val code  = lines.lastOption.flatMap(_.trim.toIntOption).getOrElse(0)
    (code, lines.dropRight(1).mkString("\n"))

  private def addItem(project: String, name: String, cart: String): Unit =
    val (code, out) = curl(
      host(project, name),
      s"/carts/$cart/items",
      "POST",
      Some(s"""{"productId":"p-$cart","name":"Widget-$cart","quantity":1}""")
    )
    // The sample answers an added item with 204.
    assertEquals(code, 204, out)

  // ---- US1: expose and call --------------------------------------------------------------------

  test("1. scaffold: a three-instance service, Ready, and the gateway serving TLS for the domain") {
    assertEquals(ankka("organizations", "create", "acme", "--name", "Acme")._1, 0)
    assertEquals(ankka("projects", "create", Project, "--name", "Checkout", "-O", "acme")._1, 0)
    apply(Project, Service, instances = 3)
    waitReady(Project, Service, 3)
    // The gateway answers for the domain — with nothing routed yet, a 404 that verified.
    val (code, _) = curl(host(Project, Service), "/")
    assertEquals(code, 404)
  }

  test("2. private by default: nothing answers at the hostname before expose") {
    assertEquals(routeOf(Project, Service), None)
    val (code, body) = curl(host(Project, Service), "/carts/c1")
    assertEquals(code, 404, body)
    assert(!body.contains("cartId"), body)
  }

  test("3. expose: the CLI prints the URL, and within 60s a cart written by hostname reads back") {
    val (code, out) = ankka("services", "expose", Service, "-p", Project)
    assertEquals(code, 0, out)
    assertEquals(out.trim, s"https://${host(Project, Service)}")

    waitFor(60.seconds)(curl(host(Project, Service), "/carts/c1")._1 == 200)
    addItem(Project, Service, "c1")
    val (_, body) = curl(host(Project, Service), "/carts/c1")
    assert(body.contains("Widget-c1"), body)

    // The route the gateway accepted, and the status the resource carries.
    waitFor(30.seconds)(status(Project, Service).exists(_.route.contains("accepted")))
    val (_, got) = ankka("services", "get", Service, "-p", Project)
    assert(got.contains(s"https://${host(Project, Service)}"), got)
    assert(!got.contains("detail"), got)
  }

  test("4. plain HTTP is redirected to HTTPS, never served") {
    val (code, body) = curl(host(Project, Service), "/carts/c1", https = false)
    assertEquals(code, 301, body)
    assert(!body.contains("cartId"), body)
    // The Location's scheme is what matters; its port is the installation's (research R3).
    val locate = new ProcessBuilder(
      "curl",
      "-sS",
      "-o",
      "/dev/null",
      "-w",
      "%{redirect_url}",
      "--resolve",
      s"${host(Project, Service)}:$httpPort:127.0.0.1",
      s"http://${host(Project, Service)}:$httpPort/carts/c1"
    ).start()
    val location = new String(locate.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    assert(location.startsWith(s"https://${host(Project, Service)}"), location)
  }

  // ---- US2: the hostname -------------------------------------------------------------------------

  test("5. the same name in another project is another hostname with its own state") {
    assertEquals(ankka("projects", "create", Other, "--name", "Returns", "-O", "acme")._1, 0)
    apply(Other, Service)
    waitReady(Other, Service, 1)
    assertEquals(ankka("services", "expose", Service, "-p", Other)._1, 0)
    waitFor(60.seconds)(curl(host(Other, Service), "/carts/r1")._1 == 200)

    addItem(Other, Service, "r1")
    val (_, returns) = curl(host(Other, Service), "/carts/r1")
    assert(returns.contains("Widget-r1"), returns)
    // The other project's cart is not there, and this project's is not there either.
    val (_, crossed) = curl(host(Other, Service), "/carts/c1")
    assert(!crossed.contains("Widget-c1"), crossed)
    val (_, checkout) = curl(host(Project, Service), "/carts/r1")
    assert(!checkout.contains("Widget-r1"), checkout)
  }

  test("6. restart, re-apply and pause/resume leave the hostname — and the route — in place") {
    val before = routeOf(Project, Service).map(_.getMetadata.getUid)
    assertEquals(ankka("services", "restart", Service, "-p", Project)._1, 0)
    waitReady(Project, Service, 3)
    assertEquals(routeOf(Project, Service).map(_.getMetadata.getUid), before)

    apply(Project, Service, instances = 4)
    waitReady(Project, Service, 4)
    assertEquals(routeOf(Project, Service).map(_.getMetadata.getUid), before)
    assert(curl(host(Project, Service), "/carts/c1")._2.contains("Widget-c1"))

    // Paused: the route stays, and the gateway has nothing to send to.
    assertEquals(ankka("services", "pause", Service, "-p", Project)._1, 0)
    waitFor(120.seconds)(pods(Project, Service).isEmpty)
    assertEquals(routeOf(Project, Service).map(_.getMetadata.getUid), before)
    val (paused, _) = curl(host(Project, Service), "/carts/c1")
    assert(paused >= 500 || paused == 404, s"expected no backend, got $paused")
    assertEquals(ankka("services", "resume", Service, "-p", Project)._1, 0)
    waitReady(Project, Service, 4)
    waitFor(60.seconds)(curl(host(Project, Service), "/carts/c1")._2.contains("Widget-c1"))
  }

  // ---- SC-003: zero-downtime by hostname ---------------------------------------------------------

  test("7. a rolling restart under load by hostname: every request answered by a ready instance") {
    val ok                = AtomicInteger(0)
    val bad               = AtomicInteger(0)
    val stale             = AtomicInteger(0)
    @volatile var running = true
    val failures          = java.util.concurrent.ConcurrentLinkedQueue[String]()
    val load = new Thread(() =>
      while running do
        val (code, body) = curl(host(Project, Service), "/carts/c1")
        if code != 200 then
          bad.incrementAndGet(); failures.add(s"$code $body"): Unit
        else if !body.contains("Widget-c1") then stale.incrementAndGet(): Unit
        else ok.incrementAndGet(): Unit
        Thread.sleep(200)
    )
    load.setDaemon(true); load.start()

    val before = pods(Project, Service).map(_.getMetadata.getUid).toSet
    assertEquals(ankka("services", "restart", Service, "-p", Project)._1, 0)
    waitFor(300.seconds) {
      val now = pods(Project, Service)
      now.size == 4 && now.map(_.getMetadata.getUid).toSet.intersect(before).isEmpty &&
      status(Project, Service).exists(s => s.lifecycle == "Ready" && s.readyInstances == 4)
    }
    Thread.sleep(5000)
    running = false; load.join(15000)

    val total = ok.get + bad.get + stale.get
    assert(total > 30, s"only $total requests issued")
    assertEquals(bad.get, 0, s"failed requests: ${failures.asScala.take(5).mkString(" | ")}")
    assertEquals(stale.get, 0, "a response without the cart's state")
  }

  // ---- US1: unexpose ------------------------------------------------------------------------------

  test("8. unexpose removes only the route: 404 at the hostname, the service untouched") {
    val podUids     = pods(Project, Service).map(_.getMetadata.getUid).toSet
    val (code, out) = ankka("services", "unexpose", Service, "-p", Project)
    assertEquals(code, 0, out)
    waitFor(30.seconds)(routeOf(Project, Service).isEmpty)
    waitFor(30.seconds)(curl(host(Project, Service), "/carts/c1")._1 == 404)

    assertEquals(pods(Project, Service).map(_.getMetadata.getUid).toSet, podUids)
    assert(status(Project, Service).exists(_.lifecycle == "Ready"))
    val (_, got) = ankka("services", "get", Service, "-p", Project)
    assert(got.contains("not exposed"), got)
    // The other project's service is still exposed: unexposing one touched nothing else.
    assertEquals(curl(host(Other, Service), "/carts/r1")._1, 200)
  }

  // ---- US5: a route cannot outlive or escape what it exposes ---------------------------------------

  test("9. deleting a project takes its route with it — nothing in the cluster names it") {
    assertEquals(ankka("services", "delete", Service, "-p", Other)._1, 0)
    waitFor(60.seconds)(routeOf(Other, Service).isEmpty)
    waitFor(60.seconds)(ankka("services", "list", "-p", Other)._2.contains("no results"))
    assertEquals(ankka("projects", "delete", Other)._1, 0)
    val everywhere = k8s
      .resources(classOf[HTTPRoute])
      .inAnyNamespace()
      .list()
      .getItems
      .asScala
      .filter(_.getSpec.getHostnames.asScala.exists(_.contains(s"-$Other.")))
    assertEquals(everywhere.size, 0, everywhere.map(_.getMetadata.getName).toString)
    assertEquals(curl(host(Other, Service), "/carts/r1")._1, 404)
  }
