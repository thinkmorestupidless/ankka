package nakka.controlplane

import io.fabric8.kubernetes.api.model.{ConfigMapBuilder, ObjectMetaBuilder, Pod}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import nakka.crd.NakkaSerialization
import nakka.operator.{
  ClusterImages,
  Membership,
  Operator,
  ServiceReconciler,
  Settings as OperatorSettings
}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * The control plane itself at three instances — deployed INTO k3s from the shipped manifests,
 * because "three instances" has no meaning in-process.
 *
 * Driven through its HTTP API from the k3s node rather than through the CLI: the client has to
 * survive the pod it was talking to being replaced (case 5), and a port-forward would not. The CLI
 * is a thin client over exactly these routes, and `EndToEndClusterSuite` already proves the two
 * agree.
 *
 * What it settles (research R12): that the projector sweeps once, that a status seen by three nodes
 * is recorded once, and that nothing else in the control plane assumed it was alone.
 */
class ControlPlaneClusterSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 20.minutes

  override def munitIgnore: Boolean = sys.props.get("nakka.cluster.tests").contains("off")

  private val K3sImage          = "rancher/k3s:v1.31.2-k3s1"
  private val ControlPlaneImage = "nakka-controlplane:latest"
  private val SampleImage       = "sample-shopping-cart:latest"
  private val Token             = "dev-local-token" // what token-secret.yaml ships
  private val Namespace         = "nakka-controlplane"
  private val Project           = "checkout"

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null

  /** The repository root, from wherever sbt forked us. */
  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("kustomization")))
      .getOrElse(fail("could not find the repository root"))

  private def applyManifest(relative: String): Unit =
    k8s.load(Files.newInputStream(repoRoot.resolve(relative))).serverSideApply(): Unit

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()
      ClusterImages.importInto(k3s, ControlPlaneImage)
      ClusterImages.importInto(k3s, SampleImage)

      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(NakkaSerialization())
        .build()

      // What deploy-local.sh does, in the same order: CNPG, the CRD, the namespace, the schema
      // ConfigMap from the single-copy DDL plus the grants, then the manifests.
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
      applyManifest("kustomization/components/crd/nakkaservice.yaml")
      applyManifest("kustomization/components/controlplane/namespace.yaml")

      val ddl = repoRoot.resolve("modules/runtime/src/main/resources/nakka/ddl")
      val data = Files
        .list(ddl)
        .iterator()
        .asScala
        .filter(_.toString.endsWith(".sql"))
        .map { f =>
          f.getFileName.toString -> Files.readString(f)
        }
        .toMap + ("99-grants.sql" ->
        "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO nakka; GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO nakka;")
      k8s
        .configMaps()
        .inNamespace(Namespace)
        .resource(
          new ConfigMapBuilder()
            .withMetadata(
              new ObjectMetaBuilder()
                .withName("nakka-controlplane-schema")
                .withNamespace(Namespace)
                .build()
            )
            .withData(data.asJava)
            .build()
        )
        .serverSideApply(): Unit

      applyManifest("kustomization/components/postgres/cluster.yaml")
      applyManifest("kustomization/components/controlplane/controlplane-rbac.yaml")
      applyManifest("kustomization/components/controlplane/token-secret.yaml")
      applyManifest("kustomization/components/controlplane/service.yaml")
      applyManifest("kustomization/components/controlplane/deployment.yaml")

      // The operator, in-process on admin credentials: not what this suite is about.
      val operatorSettings = OperatorSettings.default.copy(resyncInterval = 2.seconds)
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

  override def afterAll(): Unit =
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  private def waitFor(timeout: FiniteDuration)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(500)
    if !passed then fail(s"condition did not hold within $timeout")

  private def pods: Vector[Pod] =
    k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("app.kubernetes.io/name", "nakka-controlplane")
      .list()
      .getItems
      .asScala
      .toVector

  private def readyPods: Vector[Pod] =
    pods.filter(p =>
      Option(p.getStatus.getContainerStatuses).exists(_.asScala.headOption.exists(_.getReady))
    )

  private def nodeExec(command: String*): (Int, String) =
    val result = k3s.execInContainer(command*)
    (result.getExitCode, result.getStdout + result.getStderr)

  private val MemberPattern = """\{"node":"([^"]+)"[^}]*?"status":"([A-Za-z]+)"""".r

  private def membership(pod: Pod): Set[String] =
    Option(pod.getStatus.getPodIP).fold(Set.empty[String]) { ip =>
      val (code, body) = nodeExec("wget", "-qO-", "-T", "3", s"http://$ip:7626/cluster/members")
      if code != 0 then Set.empty
      else
        MemberPattern
          .findAllMatchIn(body)
          .collect { case m if m.group(2) == "Up" => m.group(1) }
          .toSet
    }

  /** The control plane's API, through its Service's clusterIP, from the node. */
  private def api(method: String, path: String, body: Option[String] = None): (Int, String) =
    val service = k8s.services().inNamespace(Namespace).withName("nakka-controlplane").get()
    val target  = s"http://${service.getSpec.getClusterIP}:9000$path"
    val auth    = Vector("--header", s"Authorization: Bearer $Token")
    val command = method match
      case "GET" => Vector("wget", "-qO-", "-T", "10") ++ auth :+ target
      case "PUT" | "POST" =>
        Vector("wget", "-qO-", "-T", "10", "--method", method) ++ auth ++
          Vector(
            "--header",
            "Content-Type: application/json",
            "--body-data",
            body.getOrElse("{}")
          ) :+ target
    nodeExec(command*)

  private def psql(sql: String): (Int, String) =
    nodeExec(
      "kubectl",
      "exec",
      "-n",
      Namespace,
      "nakka-controlplane-db-1",
      "-c",
      "postgres",
      "--",
      "psql",
      "-U",
      "postgres",
      "-d",
      "nakka",
      "-tA",
      "-c",
      sql
    )

  private def journalEvents(serviceName: String): Int =
    val (code, out) = psql(
      s"select count(*) from event_journal where persistence_id = 'service|$Project/$serviceName';"
    )
    assertEquals(code, 0, out)
    out.trim.toInt

  private def descriptor(name: String) =
    s"""{"name":"$name","service":{"image":"$SampleImage"}}"""

  test("1. three control-plane instances form one cluster from the shipped manifests") {
    waitFor(420.seconds)(readyPods.size == 3)
    val views = pods.map(membership)
    assertEquals(Membership.disjointClusters(views), 1, views.toString)
    views.foreach(v => assertEquals(v.size, 3, v.toString))
    // One cluster, and every instance answers.
    for pod <- pods do
      val (code, out) = nodeExec(
        "wget",
        "-qO-",
        "-T",
        "5",
        "--header",
        s"Authorization: Bearer $Token",
        s"http://${pod.getStatus.getPodIP}:9000/organizations/"
      )
      assertEquals(code, 0, s"${pod.getMetadata.getName}: $out")
  }

  test("2. commands through the Service reach whichever instance, and land once") {
    assertEquals(api("POST", "/organizations/acme", Some("""{"name":"Acme"}"""))._1, 0)
    assertEquals(
      api(
        "POST",
        s"/projects/$Project",
        Some("""{"name":"Checkout","organizationId":"acme"}""")
      )._1,
      0
    )
    for i <- 1 to 5 do
      val (code, out) = api("PUT", s"/services/$Project/svc$i", Some(descriptor(s"svc$i")))
      assertEquals(code, 0, out)
    // Projected exactly once each: one NakkaService per service, at generation 1.
    waitFor(120.seconds) {
      (1 to 5).forall { i =>
        val r = k8s
          .resources(classOf[nakka.crd.NakkaService])
          .inNamespace(s"nakka-$Project")
          .withName(s"svc$i")
          .get()
        r != null && r.getSpec.generation == 1L
      }
    }
    // The projector is a singleton and the trigger a sharded projection: one apply, one journal
    // event — however many instances there are.
    for i <- 1 to 5 do assertEquals(journalEvents(s"svc$i"), 1, s"svc$i")
  }

  test(
    "3. a status seen by three instances is recorded once — the journal does not grow in steady state"
  ) {
    // svc1's operator reports arrive at all three nodes' watches. The entity's "identical
    // observation is refused" guard is what makes that cost commands, not events.
    waitFor(300.seconds) {
      val (_, out) = api("GET", s"/services/$Project/svc1")
      out.contains("\"lifecycle\":\"Ready\"")
    }
    val settled = journalEvents("svc1")
    Thread.sleep(45.seconds.toMillis)
    assertEquals(
      journalEvents("svc1"),
      settled,
      "the journal grew with nothing changing — duplicate observations"
    )
    assert(settled <= 6, s"$settled events to reach Ready is more than one report per transition")
  }

  test("4. the sweeper runs on one instance, and moves when that instance goes") {
    // Which node hosts the singleton is in its log; kill that pod and another must take over,
    // seen by an out-of-band deletion being repaired within a sweep or two.
    val host = pods
      .find(p =>
        k8s
          .pods()
          .inNamespace(Namespace)
          .withName(p.getMetadata.getName)
          .getLog
          .contains("service projector started")
      )
      .getOrElse(pods.head)
    k8s.pods().inNamespace(Namespace).withName(host.getMetadata.getName).delete(): Unit
    waitFor(300.seconds)(
      readyPods.size == 3 && !pods.exists(_.getMetadata.getName == host.getMetadata.getName)
    )
    k8s
      .resources(classOf[nakka.crd.NakkaService])
      .inNamespace(s"nakka-$Project")
      .withName("svc2")
      .delete(): Unit
    waitFor(120.seconds) {
      k8s
        .resources(classOf[nakka.crd.NakkaService])
        .inNamespace(s"nakka-$Project")
        .withName("svc2")
        .get() != null
    }
  }

  test("5. commands keep being accepted while an instance is replaced") {
    val accepted          = AtomicInteger(0); val refused = AtomicInteger(0)
    @volatile var running = true
    val load = new Thread(() =>
      var n = 0
      while running do
        n += 1
        val (list, _) = api("GET", s"/services/$Project")
        if list == 0 then accepted.incrementAndGet(): Unit else refused.incrementAndGet(): Unit
        if n % 5 == 0 then
          val (code, _) =
            api("PUT", s"/services/$Project/under-load-$n", Some(descriptor(s"under-load-$n")))
          if code == 0 then accepted.incrementAndGet(): Unit else refused.incrementAndGet(): Unit
        Thread.sleep(200)
    )
    load.setDaemon(true); load.start()

    val victim = pods.head
    k8s.pods().inNamespace(Namespace).withName(victim.getMetadata.getName).delete(): Unit
    waitFor(300.seconds)(
      readyPods.size == 3 && !pods.exists(_.getMetadata.getName == victim.getMetadata.getName)
    )
    Thread.sleep(5000)
    running = false; load.join(10000)

    val total = accepted.get + refused.get
    assert(total > 20, s"only $total commands issued")
    val rate = accepted.get.toDouble / total
    assert(rate >= 0.99, f"$rate%.3f accepted (${refused.get} refused of $total)")
    // Everything applied under load exists.
    val (_, listing) = api("GET", s"/services/$Project")
    for name <- "under-load-\\d+".r.findAllIn(listing).toSet do assert(listing.contains(name))
  }
