package nakka.controlplane

import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import nakka.cli.Main
import nakka.controlplane.api.ControlPlaneAcl
import nakka.controlplane.deploy.{DeployConfig, Fabric8NakkaServiceClient, ServiceProjector}
import nakka.crd.{NakkaSerialization, NakkaService}
import nakka.http.HttpServer
import nakka.operator.{Operator, ServiceReconciler, Settings as OperatorSettings}
import nakka.runtime.ProjectionRuntime
import nakka.testkit.NakkaTestKit
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * A real nakka application, deployed by the platform, used over HTTP.
 *
 * Every other cluster test deploys `registry.k8s.io/pause`, chosen because it needs nothing: it
 * opens no port, reads no environment and connects to no database. That makes it the right workload
 * for testing the platform's plumbing and useless for testing the platform's *claim* — apply a
 * descriptor, get a running service. This is the only test in the repository where a nakka runtime,
 * a platform-provisioned database, the platform-applied schema, single-node cluster formation, the
 * readiness probe and the Service are all exercised at once, by doing what a user would: adding an
 * item to a cart and reading it back.
 *
 * Separate from `EndToEndClusterSuite` on purpose. That suite's subject is the two halves agreeing
 * about the resource, and the workload is irrelevant to it; here the workload is the entire point.
 * One suite with two unrelated reasons to fail would be worse than two.
 *
 * Needs `sample-shopping-cart:latest` in the local Docker daemon. `sbt test` builds it first;
 * `testOnly` does not, and `ClusterImages` says so rather than timing out.
 *
 * Disable with `-Dnakka.cluster.tests=off`, which also skips building the image.
 */
class SampleDeploymentClusterSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 10.minutes

  override def munitIgnore: Boolean = sys.props.get("nakka.cluster.tests").contains("off")

  private val K3sImage    = "rancher/k3s:v1.31.2-k3s1"
  private val SampleImage = "sample-shopping-cart:latest"
  private val Token       = "sample-test-token"
  private val Prefix      = "nakka"
  private val Project     = "checkout"
  private val Namespace   = s"$Prefix-$Project"
  private val Service     = "cart"

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null
  private var testKit: NakkaTestKit = null
  private var url: String           = ""
  private var config: Path          = null

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()

      // Before anything is deployed: the operator renders imagePullPolicy IfNotPresent, so the
      // image has to be on the node by the time the pod is scheduled.
      ClusterImages.importInto(k3s, SampleImage)

      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(NakkaSerialization())
        .build()

      k8s.load(getClass.getResourceAsStream("/nakka/crd/nakkaservice.yaml")).serverSideApply(): Unit
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

      val operatorSettings = OperatorSettings.default.copy(resyncInterval = 2.seconds)
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

      // 170s: a project's first service pays for CNPG's cluster startup and its 20-40s secret
      // allowlist window (feature 002, research R5) before a JVM has even begun to boot.
      val deployConfig = DeployConfig.default
        .copy(namespacePrefix = Prefix, sweepInterval = 2.seconds, progressDeadline = 170.seconds)
      val projector = ServiceProjector.withClient(
        deployConfig,
        new Fabric8NakkaServiceClient(k8s, Prefix, resyncMillis = 2000L)
      )
      val server = HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(ControlPlaneAcl.bearer(Token))*
      )
      testKit = NakkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

      config = Files.createTempFile("nakka-sample", ".json")
      Files.delete(config)
      sys.props("nakka.config") = config.toString

  override def afterAll(): Unit =
    sys.props.remove("nakka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  private def nakka(args: String*): (Int, String) =
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

  private def resource = Option(
    k8s.resources(classOf[NakkaService]).inNamespace(Namespace).withName(Service).get()
  )

  private def status = resource.flatMap(r => Option(r.getStatus))

  /**
   * An HTTP request from the k3s node to the Service's clusterIP — deliberately not a port-forward.
   *
   * The test JVM is outside the cluster and cannot reach a ClusterIP. fabric8's port-forward would
   * be the easy answer and the wrong one: it goes API server → pod and *bypasses the Service
   * entirely*, so a Service with the wrong selector or the wrong targetPort would pass every
   * assertion here — exactly the regression this suite exists to catch. From the node, the request
   * takes the real path: Service → endpoints → pod.
   *
   * By IP, never by name: pods resolve `cart.nakka-checkout.svc.cluster.local`, the node does not
   * (it does not use CoreDNS). Both verified during planning, research R11.
   */
  private def nodeHttp(path: String, post: Option[String] = None): (Int, String) =
    val service = k8s.services().inNamespace(Namespace).withName(Service).get()
    val target =
      s"http://${service.getSpec.getClusterIP}:${service.getSpec.getPorts.get(0).getPort}$path"
    val command = post match
      case None => Seq("wget", "-qO-", "-T", "10", target)
      case Some(body) =>
        Seq(
          "wget",
          "-qO-",
          "-T",
          "10",
          "--header",
          "Content-Type: application/json",
          "--post-data",
          body,
          target
        )
    val result = k3s.execInContainer(command*)
    (result.getExitCode, result.getStdout + result.getStderr)

  private def psql(database: String, sql: String): (Int, String) =
    val result = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      Namespace,
      "nakka-db-1",
      "-c",
      "postgres",
      "--",
      "psql",
      "-U",
      "postgres",
      "-d",
      database,
      "-tA",
      "-c",
      sql
    )
    (result.getExitCode, result.getStdout + result.getStderr)

  test("1. a descriptor naming only an image becomes a running nakka service") {
    assertEquals(nakka("organizations", "create", "acme", "--name", "Acme")._1, 0)
    assertEquals(nakka("projects", "create", Project, "--name", "Checkout", "-O", "acme")._1, 0)

    // Nothing about databases. Nothing about ports. That is the whole descriptor.
    val file = Files.createTempFile("cart", ".json")
    Files.writeString(file, s"""{"name":"$Service","service":{"image":"$SampleImage"}}""")
    val (code, out) = nakka("services", "apply", "-f", file.toString, "-p", Project)
    Files.deleteIfExists(file): Unit
    assertEquals(code, 0, s"apply failed: $out")

    // Never Failed on the way, not merely Ready at the end: the transient windows (CNPG's
    // allowlist, a JVM that has started but not yet bound) must read as "in progress".
    val deadline = System.nanoTime() + 300.seconds.toNanos
    var ready    = false
    while !ready && System.nanoTime() < deadline do
      status.foreach { s =>
        assert(s.lifecycle != "Failed", s"reported Failed on the way up: ${s.detail}")
        ready = s.lifecycle == "Ready"
      }
      if !ready then Thread.sleep(500)
    assert(ready, s"did not reach Ready; last status: $status; pods: ${podSummary()}")

    assertEquals(status.flatMap(_.database).map(_.phase), Some("Provisioned"))
    assertEquals(resource.map(_.getSpec.port), Some(Some(9000)), "the default port, resolved")
  }

  private def podSummary(): String =
    k8s
      .pods()
      .inNamespace(Namespace)
      .list()
      .getItems
      .asScala
      .map(p => s"${p.getMetadata.getName}=${p.getStatus.getPhase}")
      .mkString(", ")

  test("2. it serves HTTP through its Service: an item added to a cart can be read back") {
    val (postCode, postOut) = nodeHttp(
      "/carts/c1/items",
      post = Some("""{"productId":"p1","name":"Widget","quantity":2}""")
    )
    assertEquals(postCode, 0, postOut)

    val (getCode, cart) = nodeHttp("/carts/c1")
    assertEquals(getCode, 0, cart)
    assert(cart.contains("Widget"), cart)

    val (totalCode, total) = nodeHttp("/carts/c1/total")
    assertEquals(totalCode, 0, total)
    assertEquals(total.trim, "2")
  }

  test("3. the cart survives its pod — it was in Postgres, not in memory") {
    val before = k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("app.kubernetes.io/name", Service)
      .list()
      .getItems
      .asScala
      .map(_.getMetadata.getName)
      .toSet
    assert(before.nonEmpty)
    before.foreach(name => k8s.pods().inNamespace(Namespace).withName(name).delete(): Unit)

    // A *different* pod answering, with the same cart. Waiting on the answer rather than on the
    // status, because the status can lag the deletion and still say Ready for the pod that is gone.
    waitFor(240.seconds) {
      val now = k8s
        .pods()
        .inNamespace(Namespace)
        .withLabel("app.kubernetes.io/name", Service)
        .list()
        .getItems
        .asScala
        .map(_.getMetadata.getName)
        .toSet
      val (code, cart) = nodeHttp("/carts/c1")
      now.nonEmpty && now.intersect(before).isEmpty && code == 0 && cart.contains("Widget")
    }
  }

  test("4. a restart never runs two pods at once — two would be two writers to one journal") {
    // The cluster-level half of RenderingSuite's Recreate test. Kubernetes' default strategy
    // surges a whole extra pod at one replica, so old and new ran side by side on every rollout,
    // each its own single-node cluster over the same journal, with the Service routing to both.
    // Invisible with `pause` and no probe; found the first time a real service was deployed and
    // two pods were standing there. Every pod counts here, terminating ones included: Recreate
    // does not create the new pod until the old one is entirely gone.
    def pods = k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("app.kubernetes.io/name", Service)
      .list()
      .getItems
      .asScala
    val before = pods.map(_.getMetadata.getName).toSet

    assertEquals(nakka("services", "restart", Service, "-p", Project)._1, 0)

    var most     = 0
    var replaced = false
    val deadline = System.nanoTime() + 240.seconds.toNanos
    while !replaced && System.nanoTime() < deadline do
      val now = pods
      most = most.max(now.size)
      replaced = now.nonEmpty && now.map(_.getMetadata.getName).toSet.intersect(before).isEmpty &&
        nodeHttp("/carts/c1")._1 == 0
      if !replaced then Thread.sleep(250)

    assert(replaced, "the restart never produced a new, serving pod")
    assertEquals(most, 1, "more than one pod existed at once during the rollout")
    assert(nodeHttp("/carts/c1")._2.contains("Widget"), "and the cart came through it")
  }

  test("5. the events are in the service's own database, owned by its own role") {
    val (countCode, count) =
      psql(Service, "select count(*) from event_journal where persistence_id like '%c1';")
    assertEquals(countCode, 0, count)
    assert(count.trim.toInt >= 1, s"expected the cart's events in the '$Service' database: $count")

    val (ownerCode, owner) =
      psql(Service, "select tableowner from pg_tables where tablename = 'event_journal';")
    assertEquals(ownerCode, 0, owner)
    assertEquals(owner.trim, Service)
  }
