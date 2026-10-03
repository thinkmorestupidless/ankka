package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.cli.Main
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
  InPod,
  Operator,
  PkiStack,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.gatewayapi.v1.HTTPRoute
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import io.grpc.reflection.v1.{
  ServerReflectionGrpc,
  ServerReflectionRequest,
  ServerReflectionResponse
}
import io.grpc.stub.StreamObserver
import io.grpc.{Grpc, ManagedChannel, Status, StatusRuntimeException, TlsChannelCredentials}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import shoppingcart.v1.cart.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{Await, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * gRPC on a real installation: the sample deployed by the platform with `"grpc": true`, called from
 * another deployed service through its own certificate, and from the host through the gateway with
 * the local CA trusted — the path a developer's machine takes to the kind cluster.
 *
 * In-cluster calls go through the sample's own `/callers/grpc/…` routes, driven by `curl` from a
 * `prober` pod holding the calling service's certificate, so a call is made by a platform workload
 * exactly as one service calls another. Calls from the host use a grpc-java channel to the
 * gateway's mapped port, with the hostname as the authority.
 *
 * The cases run in order and share one cluster; services a case needs only briefly are deleted when
 * it ends, because a k3s node running several sample JVMs answers in seconds rather than
 * milliseconds. Every scenario of `features/grpc-deployed/` that names a deployed service is here,
 * by name; the in-process `GrpcFeatures` covers what needs no cluster.
 */
class GrpcClusterSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout: FiniteDuration = 30.minutes

  override def munitIgnore: Boolean =
    sys.props.get("ankka.cluster.tests").contains("off") || !curlAvailable

  private def curlAvailable: Boolean =
    try new ProcessBuilder("curl", "--version").start().waitFor() == 0
    catch case _: Exception => false

  private val K3sImage      = "rancher/k3s:v1.35.1-k3s1"
  private val SampleImage   = "sample-shopping-cart:latest"
  private lazy val identity = TestIdentity()
  private lazy val Token =
    identity.token("tester", Some("tester@example.test"), expiresIn = 2.hours)
  private val Prefix     = "ankka"
  private val Project    = "shop"
  private val Namespace  = s"$Prefix-$Project"
  private val BaseDomain = "test.local"

  private val Cart     = "cart"
  private val Checkout = "checkout"

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null
  private var testKit: AnkkaTestKit = null
  private var url: String           = ""
  private var config: Path          = null
  private var ca: Path              = null
  private var httpsPort: Int        = 0
  private var channels              = Vector.empty[ManagedChannel]

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort, GatewayStack.HttpNodePort)
      k3s.start()
      ClusterImages.importInto(k3s, SampleImage)
      httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)

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
        val d = k8s
          .apps()
          .deployments()
          .inNamespace("cnpg-system")
          .withName("cnpg-controller-manager")
          .get()
        d != null && Option(d.getStatus).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
      }
      // The gateway, and with it the installation's authorities (PkiStack).
      GatewayStack.install(k3s, k8s, PkiStack.repoRoot, BaseDomain)
      ca = GatewayStack.exportCa(k8s)

      val operatorSettings =
        OperatorSettings.default.copy(resyncInterval = 2.seconds, baseDomain = Some(BaseDomain))
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

      // 170s: a project's first service pays for CNPG's cluster startup and its secret allowlist
      // window before a JVM has begun to boot.
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
        ControlPlane.endpoints(identity.acl(), deployConfig)*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

      config = Files.createTempFile("ankka-grpc-cluster", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString

  override def afterAll(): Unit =
    channels.foreach(c => Try(c.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)))
    if testKit != null then identity.stop()
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if ca != null then Files.deleteIfExists(ca): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  // ---- plumbing ------------------------------------------------------------------------------

  private def ankka(args: String*): (Int, String) =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Main.run(
      args ++ Seq("--url", url, "--token", Token),
      PrintStream(out, true, StandardCharsets.UTF_8),
      PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))

  /**
   * Retries `check` until it holds or `timeout` passes. A check that throws counts as not holding,
   * and the last throw is in the failure, as is `diagnostics` when given — so a wait that never
   * passed says what the cluster held at the time, not only that it waited.
   */
  private def waitFor(timeout: FiniteDuration, diagnostics: => String = "")(
      check: => Boolean
  ): Unit =
    val deadline                = System.nanoTime() + timeout.toNanos
    var passed                  = false
    var last: Option[Throwable] = None
    while !passed && System.nanoTime() < deadline do
      passed =
        try
          val held = check
          if held then last = None
          held
        catch
          case e: Throwable =>
            last = Some(e)
            false
      if !passed then Thread.sleep(500)
    if !passed then
      val thrown = last.fold("")(e => s"; the last check threw $e")
      val state  = if diagnostics.isEmpty then "" else s"\n$diagnostics"
      fail(s"condition did not hold within $timeout$thrown$state")

  /** The sample, as a descriptor: gRPC, HTTP, instances and the sample's switches. */
  private def apply(
      name: String,
      grpc: Boolean,
      http: Boolean = true,
      instances: Int = 1,
      env: Map[String, String] = Map.empty
  ): Unit =
    val variables =
      env.map((k, v) => s"""{"name":"$k","value":"$v"}""").mkString("[", ",", "]")
    val file = Files.createTempFile(name, ".json")
    Files.writeString(
      file,
      s"""{"name":"$name","service":{"image":"$SampleImage","grpc":$grpc,"http":$http,"env":$variables,""" +
        s""""resources":{"autoscaling":{"minInstances":$instances}}}}"""
    )
    val (code, out) = ankka("services", "apply", "-f", file.toString, "-p", Project)
    Files.deleteIfExists(file): Unit
    assertEquals(code, 0, s"apply failed: $out")

  private def status(name: String) =
    Option(k8s.resources(classOf[AnkkaService]).inNamespace(Namespace).withName(name).get())
      .flatMap(r => Option(r.getStatus))

  private def waitReady(name: String, instances: Int): Unit =
    val deadline = System.nanoTime() + 300.seconds.toNanos
    var ready    = false
    while !ready && System.nanoTime() < deadline do
      status(name).foreach { s =>
        assert(s.lifecycle != "Failed", s"$name Failed on the way up: ${s.detail}")
        ready = s.lifecycle == "Ready" && s.readyInstances == instances
      }
      if !ready then Thread.sleep(500)
    if !ready then
      // What the service's own process said, which is where a node that never joined, or an
      // extension that never became ready, says why; the status alone is only the probe's answer.
      val logs = pods(name).map { pod =>
        val podName = pod.getMetadata.getName
        val tail    = kubectl("logs", "-n", Namespace, podName, "--tail=40")._2
        s"--- $podName ---\n$tail"
      }
      fail(s"$name never reached $instances Ready; last: ${status(name)}\n${logs.mkString("\n")}")

  private def delete(name: String): Unit =
    assertEquals(ankka("services", "delete", name, "-p", Project)._1, 0)
    waitFor(180.seconds)(pods(name).isEmpty)

  private def pods(name: String): Vector[Pod] =
    k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("app.kubernetes.io/name", name)
      .list()
      .getItems
      .asScala
      .toVector

  private def podUids(name: String): Set[String] = pods(name).map(_.getMetadata.getUid).toSet

  private def service(name: String) =
    Option(k8s.services().inNamespace(Namespace).withName(name).get())

  private def routeOf(name: String): Option[HTTPRoute] =
    Option(k8s.resources(classOf[HTTPRoute]).inNamespace(Namespace).withName(name).get())

  private def host(name: String) = s"$name-$Project.$BaseDomain"

  /** A call made by `caller` — a platform workload holding its certificate — through its routes. */
  private def asService(caller: String, path: String): (Int, String) =
    InPod.prober(k3s, Namespace, caller): Unit
    InPod.curl(k3s, Namespace, "prober", s"https://$caller.$Namespace.svc.cluster.local:9000$path")

  /** A channel from the host to `name`'s hostname, through the gateway, trusting the local CA. */
  private def fromOutside(name: String): ManagedChannel =
    val credentials = TlsChannelCredentials.newBuilder().trustManager(ca.toFile).build()
    val channel = Grpc
      .newChannelBuilderForAddress("127.0.0.1", httpsPort, credentials)
      .overrideAuthority(host(name))
      .build()
    channels :+= channel
    channel

  private def code(call: => Any): Status.Code =
    try
      call: Unit
      Status.Code.OK
    catch case e: StatusRuntimeException => e.getStatus.getCode

  private def whoCalledFromOutside(name: String): String =
    CartServiceGrpc
      .blockingStub(fromOutside(name))
      .withDeadlineAfter(10, TimeUnit.SECONDS)
      .whoCalled(WhoCalledRequest())
      .caller

  private def curlHost(name: String, path: String, post: Option[String] = None): (Int, String) =
    val args = Vector(
      "curl",
      "-sS",
      "--cacert",
      ca.toString,
      "--resolve",
      s"${host(name)}:$httpsPort:127.0.0.1",
      "-m",
      "10",
      "-o",
      "-",
      "-w",
      "\n%{http_code}"
    ) ++ post.toVector.flatMap(b =>
      Vector("-X", "POST", "-H", "content-type: application/json", "-d", b)
    ) :+ s"https://${host(name)}:$httpsPort$path"
    val process = new ProcessBuilder(args*).redirectErrorStream(true).start()
    val output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    process.waitFor()
    val lines = output.linesIterator.toVector
    (lines.lastOption.flatMap(_.trim.toIntOption).getOrElse(0), lines.dropRight(1).mkString("\n"))

  private def addItemFromOutside(name: String, cartId: String, product: String): Unit =
    val (status, body) = curlHost(
      name,
      s"/carts/$cartId/items",
      Some(s"""{"productId":"$product","name":"$product","quantity":1}""")
    )
    assertEquals(status, 204, body)

  /** One reflection request from the host, and its one answer. */
  private def reflect(name: String, request: ServerReflectionRequest) =
    val answer = Promise[ServerReflectionResponse]()
    val requests = ServerReflectionGrpc
      .newStub(fromOutside(name))
      .serverReflectionInfo(new StreamObserver[ServerReflectionResponse]:
        def onNext(value: ServerReflectionResponse): Unit = answer.trySuccess(value): Unit
        def onError(t: Throwable): Unit                   = answer.tryFailure(t): Unit
        def onCompleted(): Unit                           = ())
    requests.onNext(request)
    requests.onCompleted()
    Try(Await.result(answer.future, 30.seconds)).toEither.left.map {
      case e: StatusRuntimeException => e.getStatus.getCode
      case other                     => fail(s"reflection failed: $other")
    }

  /** The loop route's answer: calls counted by the instance that answered them, and failures. */
  private def loop(caller: String, target: String, n: Int): (Map[String, Int], Int) =
    val (status, body) = asService(caller, s"/callers/grpc/$target/loop/$n")
    assertEquals(status, 200, body)
    val instances = """"([^"]+)":(\d+)""".r
      .findAllMatchIn(body.substring(0, body.indexOf("}") + 1))
      .map(m => m.group(1) -> m.group(2).toInt)
      .toMap
      .removed("instances")
    val failures = """"failures":(\d+)""".r.findFirstMatchIn(body).map(_.group(1).toInt)
    (instances, failures.getOrElse(fail(s"no failure count in $body")))

  private def kubectl(args: String*): (Int, String) =
    val result = k3s.execInContainer(("kubectl" +: args)*)
    (result.getExitCode, result.getStdout + result.getStderr)

  // ---- deployed.feature ------------------------------------------------------------------------

  test("a service that declares gRPC has a gRPC address other services reach") {
    assertEquals(ankka("organizations", "create", "acme", "--name", "Acme")._1, 0)
    assertEquals(ankka("projects", "create", Project, "--name", "Shop", "-O", "acme")._1, 0)
    apply(Cart, grpc = true)
    apply(Checkout, grpc = false, env = Map("CART_GRPC" -> "off"))
    waitReady(Cart, 1)
    waitReady(Checkout, 1)

    val ports = service(Cart).toVector.flatMap(_.getSpec.getPorts.asScala.map(_.getName))
    assert(ports.contains("grpc"), ports.toString)
    assert(service(s"$Cart-grpc-peers").isDefined, "the headless address callers balance over")

    val (status, caller) = asService(Checkout, s"/callers/grpc/$Cart")
    assertEquals((status, caller.trim), (200, s"service:$Project/$Checkout"))
  }

  test("a service that does not declare gRPC is deployed as it was before") {
    // Nothing to upgrade from in a fresh cluster, so this asserts what was rendered and that
    // reconciling it again — every resync — leaves it alone.
    val ports = service(Checkout).toVector.flatMap(_.getSpec.getPorts.asScala.map(_.getName))
    assertEquals(ports, Vector("http"))
    assertEquals(service(s"$Checkout-grpc-peers"), None)
    val policy = k8s.network().networkPolicies().inNamespace(Namespace).withName(s"$Checkout-grpc")
    assertEquals(Option(policy.get()), None)

    val pods    = podUids(Checkout)
    val version = service(Checkout).map(_.getMetadata.getResourceVersion)
    Thread.sleep(10000) // five resyncs
    assertEquals(podUids(Checkout), pods)
    assertEquals(service(Checkout).map(_.getMetadata.getResourceVersion), version)
  }

  test("a deployed gRPC endpoint reads its calling workload from the certificate") {
    val (status, caller) = asService(Checkout, s"/callers/grpc/$Cart")
    assertEquals((status, caller.trim), (200, s"service:$Project/$Checkout"))
  }

  test("a call that says it is from another service is not believed") {
    val (status, caller) = asService(Checkout, s"/callers/grpc/$Cart/claiming/billing")
    assertEquals((status, caller.trim), (200, s"service:$Project/$Checkout"))
  }

  test("a workload that is not of the installation cannot connect to a gRPC address") {
    kubectl("create", "namespace", "outsider"): Unit
    val manifest =
      """apiVersion: v1
        |kind: Pod
        |metadata: { name: outsider, namespace: outsider }
        |spec:
        |  containers:
        |    - { name: busybox, image: "busybox:1.36", command: ["sleep", "infinity"] }
        |""".stripMargin
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      "/tmp/outsider.yaml"
    )
    assertEquals(kubectl("apply", "-f", "/tmp/outsider.yaml")._1, 0)
    assertEquals(
      kubectl(
        "wait",
        "-n",
        "outsider",
        "--for=condition=Ready",
        "pod/outsider",
        "--timeout=180s"
      )._1,
      0
    )
    def connects(ip: String, port: Int): Boolean =
      kubectl(
        "exec",
        "-n",
        "outsider",
        "outsider",
        "--",
        "nc",
        "-z",
        "-w",
        "3",
        ip,
        port.toString
      )._1 == 0

    val grpcAddress = service(Cart).map(_.getSpec.getClusterIP).getOrElse(fail("no address"))
    val dns = Option(k8s.services().inNamespace("kube-system").withName("kube-dns").get())
      .map(_.getSpec.getClusterIP)
      .getOrElse(fail("no kube-dns"))
    // The same pod reaches an address no policy protects, so the refusal below is the policy's.
    assert(
      connects(dns, 53),
      "the outsider reaches nothing at all, so the next check proves nothing"
    )
    assert(
      !connects(grpcAddress, 9090),
      "a workload outside the installation reached the gRPC port"
    )
  }

  test("an instance that cannot yet answer a gRPC call is not ready") {
    // The pod became ready only once its gRPC server was listening: readiness asks the server.
    val pod = pods(Cart).headOption.getOrElse(fail("no cart pod"))
    val (_, log) =
      kubectl("logs", "--timestamps", "-n", Namespace, pod.getMetadata.getName, "-c", Cart)
    val listening = log.linesIterator
      .find(_.contains("ankka grpc listening"))
      .map(line => Instant.parse(line.takeWhile(_ != ' ')))
      .getOrElse(fail(s"the gRPC server never said it was listening:\n${log.take(4000)}"))
    val readyAt = pod.getStatus.getConditions.asScala
      .find(_.getType == "Ready")
      .map(c => Instant.parse(c.getLastTransitionTime))
      .getOrElse(fail("no Ready condition"))
    // The condition's time is whole seconds.
    assert(
      !readyAt.isBefore(listening.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)),
      s"ready at $readyAt, before gRPC was listening at $listening"
    )
  }

  test("a service that declares gRPC and serves none is reported as failed, with the reason") {
    val idle = "idle"
    apply(idle, grpc = true, env = Map("CART_GRPC" -> "off"))
    // First the reason, while the rollout is still in progress…
    waitFor(180.seconds)(
      status(idle).exists(s =>
        s.lifecycle != "Failed" && s.detail.exists(_.contains("registers no gRPC endpoint"))
      )
    )
    val (_, shown) = ankka("services", "get", idle, "-p", Project)
    assert(shown.contains("registers no gRPC endpoint"), shown)
    // …then Failed, once the deadline has passed, with the reason still there.
    waitFor(300.seconds)(status(idle).exists(_.lifecycle == "Failed"))
    assert(status(idle).exists(_.detail.exists(_.contains("registers no gRPC endpoint"))))
    delete(idle)
  }

  // ---- exposed.feature ---------------------------------------------------------------------------

  test("a service that declares gRPC and is not exposed answers no call from outside the cluster") {
    assertEquals(routeOf(Cart), None)
    assertNotEquals(code(whoCalledFromOutside(Cart)), Status.Code.OK)
  }

  test(
    "a call to an exposed service's hostname is answered, and its calling workload is the gateway"
  ) {
    val (exposed, out) = ankka("services", "expose", Cart, "-p", Project)
    assertEquals(exposed, 0, out)
    waitFor(90.seconds)(code(whoCalledFromOutside(Cart)) == Status.Code.OK)
    assertEquals(whoCalledFromOutside(Cart), "gateway")
  }

  test("an endpoint that admits the gateway admits a call from outside the cluster") {
    // The sample's endpoint admits its project and the gateway.
    val cart = CartServiceGrpc
      .blockingStub(fromOutside(Cart))
      .withDeadlineAfter(10, TimeUnit.SECONDS)
      .getCart(GetCartRequest("from-outside"))
    assertEquals(cart.cartId, "from-outside")
  }

  test(
    "a request to a route at the hostname of a service that serves gRPC is answered by its HTTP endpoint"
  ) {
    addItemFromOutside(Cart, "h1", "widget")
    val (status, body) = curlHost(Cart, "/carts/h1")
    assertEquals(status, 200, body)
    assert(body.contains("widget"), body)
  }

  test("a stream reaches a developer outside the cluster a part at a time") {
    val arrivals = ConcurrentLinkedQueue[(Long, Cart)]()
    val ended    = Promise[Status]()
    CartStreamsGrpc
      .stub(fromOutside(Cart))
      .watchCart(
        GetCartRequest("s1"),
        new StreamObserver[Cart]:
          def onNext(value: Cart): Unit   = arrivals.add(System.nanoTime() -> value): Unit
          def onError(t: Throwable): Unit = ended.trySuccess(Status.fromThrowable(t)): Unit
          def onCompleted(): Unit         = ended.trySuccess(Status.OK): Unit
      )
    waitFor(30.seconds)(!arrivals.isEmpty) // the cart as it is
    (1 to 3).foreach { i =>
      Thread.sleep(1000)
      addItemFromOutside(Cart, "s1", s"p$i")
    }
    waitFor(30.seconds)(arrivals.size >= 4)
    val changes = arrivals.asScala.toVector.drop(1).map(_._1)
    assert(
      changes.last - changes.head >= 1500.millis.toNanos,
      s"the parts arrived together: ${changes.map(t => (t - changes.head) / 1000000)}"
    )
    // And a stream goes on past the gateway's ordinary request timeout: a part sent at 25 seconds
    // still arrives.
    Thread.sleep(21000)
    addItemFromOutside(Cart, "s1", "late")
    waitFor(30.seconds)(arrivals.asScala.exists(_._2.items.exists(_.productId == "late")))
    assert(!ended.isCompleted, s"the stream ended: ${ended.future.value}")
  }

  test(
    "a method that takes a stream and answers with a stream is called from outside the cluster"
  ) {
    val heard = ConcurrentLinkedQueue[Line]()
    val requests = CartStreamsGrpc
      .stub(fromOutside(Cart))
      .converse(new StreamObserver[Line]:
        def onNext(value: Line): Unit   = heard.add(value): Unit
        def onError(t: Throwable): Unit = ()
        def onCompleted(): Unit         = ())
    requests.onNext(Line("hello"))
    // One part back while the stream is still open: the gateway does not wait for its end.
    waitFor(30.seconds)(heard.size == 1)
    assertEquals(heard.peek().text, "heard: hello")
    requests.onCompleted()
  }

  test("an exposed service that opts into reflection answers a tool outside the cluster") {
    val listed = reflect(Cart, ServerReflectionRequest.newBuilder().setListServices("").build())
      .getOrElse(fail("reflection was refused"))
      .getListServicesResponse
      .getServiceList
      .asScala
      .map(_.getName)
    assert(listed.contains("shoppingcart.v1.CartService"), listed.toString)
    val methods = reflect(
      Cart,
      ServerReflectionRequest
        .newBuilder()
        .setFileContainingSymbol("shoppingcart.v1.CartService")
        .build()
    ).getOrElse(fail("reflection was refused"))
      .getFileDescriptorResponse
      .getFileDescriptorProtoList
      .asScala
      .map(com.google.protobuf.DescriptorProtos.FileDescriptorProto.parseFrom)
      .flatMap(_.getServiceList.asScala)
      .find(_.getName == "CartService")
      .getOrElse(fail("CartService was not described"))
      .getMethodList
      .asScala
      .map(_.getName)
    assertEquals(methods.toSet, Set("GetCart", "AddItem", "WhoCalled"))
  }

  test("a service that reflection's ACL does not admit is refused at the gRPC address") {
    // The cart's reflection admits only the gateway; `checkout` asks at the in-cluster address.
    val (status, said) = asService(Checkout, s"/callers/grpc/$Cart/reflection")
    assertEquals((status, said.trim), (200, "failed: PERMISSION_DENIED"))
  }

  test("a service that opts into reflection answers another service at its gRPC address") {
    val open = "open"
    apply(open, grpc = true, env = Map("CART_REFLECTION_CALLER" -> Checkout))
    waitReady(open, 1)
    val (status, said) = asService(Checkout, s"/callers/grpc/$open/reflection")
    assertEquals(status, 200, said)
    assert(said.split(",").contains("shoppingcart.v1.CartService"), said)
    delete(open)
  }

  test("an exposed service that does not declare gRPC is exposed as it was before") {
    val before = podUids(Checkout)
    assertEquals(ankka("services", "expose", Checkout, "-p", Project)._1, 0)
    waitFor(90.seconds)(curlHost(Checkout, "/carts/x")._1 == 200)
    val rules = routeOf(Checkout).toVector.flatMap(_.getSpec.getRules.asScala)
    assertEquals(rules.size, 1, "one rule, for HTTP; no gRPC rule")
    assert(
      rules.flatMap(_.getBackendRefs.asScala).forall(_.getPort == 9000),
      rules.toString
    )
    assertEquals(podUids(Checkout), before)
  }

  test("an endpoint that admits only a named service refuses a call from outside the cluster") {
    val gated = "gated"
    apply(gated, grpc = true, env = Map("CART_GRPC_CALLER" -> Checkout, "CART_REFLECTION" -> "off"))
    waitReady(gated, 1)
    assertEquals(ankka("services", "expose", gated, "-p", Project)._1, 0)
    // The route answers HTTP once the gateway has it; then gRPC goes the same way.
    waitFor(90.seconds)(curlHost(gated, "/carts/x")._1 == 200)
    assertEquals(code(whoCalledFromOutside(gated)), Status.Code.PERMISSION_DENIED)
    // The service it names is admitted, so the refusal is the ACL's and not the address's.
    val (status, caller) = asService(Checkout, s"/callers/grpc/$gated")
    assertEquals((status, caller.trim), (200, s"service:$Project/$Checkout"))
    // And a deployment that has not opted into reflection answers none.
    assertEquals(
      reflect(
        gated,
        ServerReflectionRequest.newBuilder().setListServices("").build()
      ).left.toOption,
      Some(Status.Code.UNIMPLEMENTED)
    )
    delete(gated)
  }

  private val Ledger = "ledger"

  test("a service may serve gRPC and no HTTP") {
    apply(Ledger, grpc = true, http = false)
    waitReady(Ledger, 1)
    val ports = service(Ledger).toVector.flatMap(_.getSpec.getPorts.asScala.map(_.getName))
    assertEquals(ports, Vector("grpc"))
    assert(pods(Ledger).forall(_.getStatus.getContainerStatuses.asScala.forall(_.getReady)))
  }

  test("an exposed service that serves gRPC and no HTTP answers a gRPC call at its hostname") {
    assertEquals(ankka("services", "expose", Ledger, "-p", Project)._1, 0)
    waitFor(90.seconds)(code(whoCalledFromOutside(Ledger)) == Status.Code.OK)
    delete(Ledger)
  }

  // ---- calling.feature, against real pods --------------------------------------------------------

  test("a call to a service that cannot be found fails, naming the service") {
    val (status, said) = asService(Checkout, "/callers/grpc/basket/explained")
    assertEquals(status, 200, said)
    assert(said.startsWith("failed: cannot reach") && said.contains("basket"), said)
  }

  test("a call to a service that does not serve gRPC fails, saying so") {
    val (status, said) = asService(Cart, s"/callers/grpc/$Checkout/explained")
    assertEquals(status, 200, said)
    assertEquals(said.trim, s"failed: $Project/$Checkout serves no gRPC")
  }

  // ---- replacement.feature -----------------------------------------------------------------------

  test(
    "an instance added to a service comes to answer calls from a service that was already calling"
  ) {
    assertEquals(loop(Checkout, Cart, 20)._2, 0)
    val caller = podUids(Checkout)
    apply(Cart, grpc = true, instances = 3)
    waitReady(Cart, 3)
    val seen = scala.collection.mutable.Map.empty[String, Int]
    waitFor(5.minutes) {
      val (instances, failures) = loop(Checkout, Cart, 30)
      assertEquals(failures, 0)
      instances.foreach((i, n) => seen(i) = seen.getOrElse(i, 0) + n)
      seen.keySet.intersect(pods(Cart).map(_.getMetadata.getName).toSet).size == 3
    }
    assertEquals(podUids(Checkout), caller, "the calling service was restarted")
  }

  test("no call is refused while a service's instances are replaced one at a time") {
    val before = podUids(Cart)
    assertEquals(ankka("services", "restart", Cart, "-p", Project)._1, 0)
    var calls    = 0
    var failures = 0
    def replaced =
      val now = podUids(Cart)
      now.size == 3 && now.intersect(before).isEmpty &&
      status(Cart).exists(s => s.lifecycle == "Ready" && s.readyInstances == 3)
    val deadline = System.nanoTime() + 6.minutes.toNanos
    while (!replaced || calls < 1000) && System.nanoTime() < deadline do
      val (instances, failed) = loop(Checkout, Cart, 20)
      calls += instances.values.sum + failed
      failures += failed
    assert(replaced, "the restart never replaced every instance")
    assert(calls >= 1000, s"only $calls calls")
    assertEquals(failures, 0, s"$failures of $calls calls failed")
  }

  test(
    "no call from outside the cluster is refused while an exposed service's instances are replaced"
  ) {
    val before            = podUids(Cart)
    val ok                = AtomicInteger()
    val failures          = ConcurrentLinkedQueue[String]()
    @volatile var running = true
    val stub              = CartServiceGrpc.blockingStub(fromOutside(Cart))
    val load = new Thread(() =>
      while running do
        try
          stub.withDeadlineAfter(10, TimeUnit.SECONDS).whoCalled(WhoCalledRequest()): Unit
          ok.incrementAndGet(): Unit
        catch case e: StatusRuntimeException => failures.add(e.getStatus.toString): Unit
        Thread.sleep(50)
    )
    load.setDaemon(true)
    load.start()
    assertEquals(ankka("services", "restart", Cart, "-p", Project)._1, 0)
    waitFor(6.minutes) {
      val now = podUids(Cart)
      now.size == 3 && now.intersect(before).isEmpty &&
      status(Cart).exists(s => s.lifecycle == "Ready" && s.readyInstances == 3)
    }
    Thread.sleep(5000)
    running = false
    load.join(15000)
    assert(ok.get > 300, s"only ${ok.get} calls")
    assertEquals(failures.size, 0, failures.asScala.take(5).mkString(" | "))
  }

  test(
    "a stream on an instance that is stopping is given time to finish, then ends as unavailable"
  ) {
    // A conversation, not a watch: the watch polls the entity, and with every instance stopping at
    // once that call fails on its own before the server's grace has run — which is a fact about the
    // cluster going away, not the one this scenario states. An echo depends on nothing but the
    // server that holds it.
    val ended = Promise[(Status, Long)]()
    val heard = ConcurrentLinkedQueue[Line]()
    val requests = CartStreamsGrpc
      .stub(fromOutside(Cart))
      .converse(new StreamObserver[Line]:
        def onNext(value: Line): Unit = heard.add(value): Unit
        def onError(t: Throwable): Unit =
          ended.trySuccess(Status.fromThrowable(t) -> System.nanoTime()): Unit
        def onCompleted(): Unit = ended.trySuccess(Status.OK -> System.nanoTime()): Unit)
    requests.onNext(Line("hello"))
    waitFor(30.seconds)(heard.size == 1)
    // Which instance answers is the gateway's choice, so every instance is stopped at once.
    val stoppedAt = System.nanoTime()
    try
      pods(Cart).foreach(p =>
        k8s.pods().inNamespace(Namespace).withName(p.getMetadata.getName).delete(): Unit
      )
      val (status, at) = Await.result(ended.future, 2.minutes)
      assertEquals(status.getCode, Status.Code.UNAVAILABLE, status.toString)
      // A stopping pod keeps serving for its preStop sleep (5s), then its server gives calls in
      // progress the shutdown grace (5s) before ending them.
      val lasted = (at - stoppedAt).nanos
      assert(lasted >= 9.seconds, s"the stream ended after $lasted")
    finally
      waitReady(Cart, 3)
      // Every instance was replaced at once, which is a crash rather than a rollout: a caller's
      // channel still names the old addresses until grpc-java's DNS resolver looks again, which it
      // caches for up to 30 seconds. The cases after this one start from a caller that reaches the
      // cart again, rather than from that window.
      waitFor(90.seconds)(asService(Checkout, s"/callers/grpc/$Cart")._1 == 200)
  }

  // ---- what a member is shown --------------------------------------------------------------------

  test("a member is shown why an exposed service's gRPC cannot be reached at its hostname") {
    // The gateway's HTTPS listener is made to admit routes from no namespace of the installation.
    // The listener is the installation's, which the operator does not reconcile; the project's
    // namespace label, the other half of the rule, is re-applied by the operator on every
    // reconcile, so taking it away is a fault the platform heals before the gateway may notice.
    val selector = "/spec/listeners/1/allowedRoutes/namespaces/selector/matchLabels"
    def listener(labels: String) =
      kubectl(
        "patch",
        "gateway",
        "ankka",
        "-n",
        "ankka-gateway",
        "--type=json",
        s"""-p=[{"op":"replace","path":"$selector","value":$labels}]"""
      )
    val (patched, said) = listener("""{"ankka-test/admitted":"nobody"}""")
    assertEquals(patched, 0, said)
    def held =
      s"""services get: ${ankka("services", "get", Cart, "-p", Project)._2}
         |route conditions: ${kubectl(
          "get",
          "httproute",
          Cart,
          "-n",
          Namespace,
          "-o",
          "jsonpath={.status.parents[*].conditions}"
        )._2}
         |listener selector: ${kubectl(
          "get",
          "gateway",
          "ankka",
          "-n",
          "ankka-gateway",
          "-o",
          "jsonpath={.spec.listeners[1].allowedRoutes}"
        )._2}
         |resource status: ${kubectl(
          "get",
          "ankkaservice",
          Cart,
          "-n",
          Namespace,
          "-o",
          "jsonpath={.status}"
        )._2}""".stripMargin
    try
      waitFor(180.seconds, held)(
        ankka("services", "get", Cart, "-p", Project)._2.contains("route rejected")
      )
      val (_, shown) = ankka("services", "get", Cart, "-p", Project)
      assert(shown.contains("NotAllowedByListeners"), shown)
    finally
      val (restored, why) = listener("""{"app.kubernetes.io/managed-by":"ankka"}""")
      assertEquals(restored, 0, why)
      waitFor(120.seconds, held)(
        !ankka("services", "get", Cart, "-p", Project)._2.contains("route rejected")
      )
  }

  test("an unexposed service answers neither a gRPC call nor an HTTP request at its hostname") {
    assertEquals(ankka("services", "unexpose", Cart, "-p", Project)._1, 0)
    waitFor(60.seconds)(routeOf(Cart).isEmpty)
    waitFor(60.seconds)(curlHost(Cart, "/carts/h1")._1 == 404)
    assertNotEquals(code(whoCalledFromOutside(Cart)), Status.Code.OK)
    // Inside the cluster it is untouched.
    val (status, caller) = asService(Checkout, s"/callers/grpc/$Cart")
    assertEquals((status, caller.trim), (200, s"service:$Project/$Checkout"))
  }
