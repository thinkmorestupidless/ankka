package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.{
  ProjectEndpoint,
  ProjectId,
  ProjectTopic,
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
import com.thinkmorestupidless.ankka.controlplane.domain.Service
import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  BrokerStack,
  ClusterImages,
  InPod,
  Operator,
  PkiStack,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * `features/broker/` on k3s: the installation's broker as the `broker` component installs it
 * (`BrokerStack`), cert-manager and the installation's authorities, CloudNativePG for the services'
 * databases, the operator in this JVM told of the broker as the component's patch tells it, and the
 * control plane and the CLI as a member uses them.
 *
 * Every service is the shopping cart sample, deployed under the scenario's names: its checkout
 * notices are a consumer publishing to a topic (`CART_CHECKOUTS_TOPIC` names which), and its
 * `checkouts-seen` view reads them back, from its own topic or another service's. What reached the
 * broker is read with `BrokerProbe`, a pod holding a named service's certificate and nothing else,
 * so a refusal is the broker's own. A service is called from inside its namespace (`InPod`), as the
 * platform admits callers.
 *
 * The scenarios share one installation and run in order; a scenario that would leave something
 * another relies on in a state of its own works on services or a project of its own (`aliases`).
 *
 * Disable with `-Dankka.cluster.tests=off`.
 */
class BrokerClusterFeatures extends GherkinSuite("../features/broker") with LogCapturing:

  override val munitTimeout: FiniteDuration = 15.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  override protected def ranElsewhere: Map[String, String] = Map(
    "a topic is refused when it cannot be made"       -> "ProjectTopicsFeature",
    "a topic declared again is still one declaration" -> "ProjectTopicsFeature",
    "a declaration is made on a project of the member's organization only" ->
      "ProjectTopicsFeature",
    // The control plane reads a topology over the observe port, with its own certificate, which
    // the control plane this suite runs in its JVM does not hold; the read is the HTTP suite's,
    // over the runtime's topology, whose topic nodes the topology suites hold.
    "the status of a service names a topic it uses that its project has not declared" ->
      "ControlPlaneHttpSuite",
    "a topic is no longer named as undeclared once its project declares it" ->
      "ControlPlaneHttpSuite",
    "a service hosted as a process is ready with the broker its descriptor names" ->
      "SidecarClusterSuite",
    "a consumer of a service hosted as a process publishes to the broker its descriptor names" ->
      "SidecarClusterSuite",
    "a broker variable is given to both programs of a service hosted as a process" ->
      "ProcessHostingRenderingSuite",
    "an installation in a cluster has the broker a local platform has" -> "RemoteOverlaySuite",
    "a service of an installation with no broker is deployed as it was before" ->
      "BrokerRenderingSuite and BrokerProvisioningSuite",
    "a topic declared on an installation with no broker says why it is not made" ->
      "TopicProvisioningSuite and ProjectRenderingSuite"
  )

  private val K3sImage    = "rancher/k3s:v1.35.1-k3s1"
  private val Tag         = com.thinkmorestupidless.ankka.core.BuildInfo.version.replace('+', '-')
  private val SampleImage = s"sample-shopping-cart:$Tag"
  private val Prefix      = "ankka"
  private val Broker      = BrokerStack.Namespace

  private lazy val identity = TestIdentity()
  private lazy val Token = identity.token(
    "tester",
    Some("tester@example.test"),
    roles = Set("platform-admin"),
    expiresIn = 3.hours
  )

  private var k3s: K3sContainer       = null
  private var k8s: KubernetesClient   = null
  private var operator: Operator      = null
  private var testKit: AnkkaTestKit   = null
  private var url: String             = ""
  private var config: Path            = null
  private var operatorSettings        = OperatorSettings.default
  private var projects: Set[String]   = Set.empty
  private var strimziStopped: Boolean = false

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()
      ClusterImages.importInto(k3s, SampleImage)
      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      k8s.load(getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")).serverSideApply(): Unit
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
      val broker = BrokerStack.install(k3s)
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

      operatorSettings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        broker = Some(broker)
      )
      startOperator(operatorSettings)

      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        // A project's first database takes a minute or two on a busy node.
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
          logs = Some(new PodLogs(k8s, Prefix))
        )*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

      config = Files.createTempFile("ankka-broker-cluster", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString
      ok(ankka("organizations", "create", "acme", "--name", "Acme")): Unit

  override def afterAll(): Unit =
    if testKit != null then identity.stop()
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  private def startOperator(settings: OperatorSettings): Unit =
    if operator != null then operator.close()
    operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
    operator.start()

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

  private def node(args: String*): String =
    val r = k3s.execInContainer(args*)
    r.getStdout + r.getStderr

  /** The broker's objects and the scenario's namespaces, for a wait that failed. */
  private def diagnosis(): String =
    if k3s == null then ""
    else
      val namespaces = projects.toVector.map(p => s"$Prefix-$p")
      val pods       = namespaces.map(ns => node("kubectl", "get", "pods", "-n", ns, "-o", "wide"))
      val broker =
        node("kubectl", "get", "kafkatopics,kafkausers,kafka,pods", "-n", Broker, "-o", "wide")
      val resources = namespaces.map(ns =>
        node("kubectl", "get", "ankkaservices", "-n", ns, "-o", "jsonpath={.items[*].status}")
      )
      s"\n${pods.mkString("\n")}\n$broker\n${resources.mkString("\n")}"

  private def ns(project: String) = s"$Prefix-$project"

  private def statusOf(name: String, project: String): Option[ServiceStatus] =
    val run = ankka("services", "get", name, "-p", project, "-o", "json")
    Option.when(run.code == 0)(readFromString[ServiceStatus](run.out))

  private def ensureProject(project: String): Unit =
    if !projects(project) then
      val run = ankka("projects", "create", project, "--name", project, "--organization", "acme")
      assert(run.code == 0 || run.all.contains("already"), run.all)
      projects += project

  /** Every container of a service's Deployment, by name, with its plain variables. */
  private def environment(service: String, project: String): Map[String, Map[String, String]] =
    val deployment = k8s.apps().deployments().inNamespace(ns(project)).withName(service).get()
    if deployment == null then Map.empty
    else
      deployment.getSpec.getTemplate.getSpec.getContainers.asScala.map { c =>
        c.getName -> c.getEnv.asScala.map(e => e.getName -> Option(e.getValue).getOrElse("")).toMap
      }.toMap

  private def logsOf(service: String, project: String): String =
    node(
      "kubectl",
      "logs",
      "-n",
      ns(project),
      "-l",
      s"app.kubernetes.io/name=$service",
      "-c",
      service,
      "--tail=-1",
      "--prefix"
    )

  private def jsonPath(args: String*): String = PkiStack.jsonPath(k3s, args*)

  private def topicExists(name: String): Boolean =
    jsonPath("kafkatopic", "-n", Broker, name, "{.metadata.name}") == name

  private def topicReady(name: String): Boolean =
    jsonPath(
      "kafkatopic",
      "-n",
      Broker,
      name,
      """{.status.conditions[?(@.type=="Ready")].status}"""
    ) ==
      "True"

  private def userExists(name: String): Boolean =
    jsonPath("kafkauser", "-n", Broker, name, "{.metadata.name}") == name

  // ── the scenario's names ──────────────────────────────────────────────────

  /**
   * Scenarios that leave a service or a project in a state of their own work on names of their own,
   * so the next scenario finds what it expects: a deleted project cannot be made again, and a
   * service that names its own broker would otherwise be one that already has a credential.
   */
  private val aliasesByScenario: Map[String, Map[String, String]] = Map(
    "a deleted project's topics are kept" -> Map("money" -> "spent"),
    "a service whose descriptor names a broker is given nothing on the installation's" ->
      Map("wallet" -> "purse"),
    "both programs of a service hosted as a process are told where the installation's broker is" ->
      Map("wallet" -> "till"),
    "a web-hosted service is given nothing of the installation's broker" -> Map("web" -> "kiosk"),
    // A service applied again under its old name is recovered for as long as its resource lives,
    // and the kept scenarios do exactly that to "wallet"; the outline's rows need one that was not.
    "the status says how far the platform has got with a service's credential" -> Map(
      "wallet" -> "teller"
    ),
    "a local platform has a broker from the start" -> Map("shopping-cart" -> "shopping-cart")
  )

  /** Services a scenario made for itself alone, deleted when it ends. */
  private val disposable = Set("purse", "till", "kiosk", "shopping-cart")

  private var aliases: Map[String, String]   = Map.empty
  private var made: Vector[(String, String)] = Vector.empty
  private var scenarioName: String           = ""
  private def a(name: String): String        = aliases.getOrElse(name, name)
  private def project(name: String): String  = a(name)

  /** The partitions each topic of each project has been declared with, so later scenarios agree. */
  private var partitions: Map[(String, String), Int] = Map.empty
  private def partitionsOf(p: String, topic: String): Int =
    partitions.getOrElse((p, topic), if topic == "transactions" then 12 else 3)

  override def beforeEach(context: BeforeEach): Unit =
    if !munitIgnore then
      scenarioName = aliasesByScenario.keys.find(context.test.name.startsWith).getOrElse("")
      aliases = aliasesByScenario.getOrElse(scenarioName, Map.empty)
      made = Vector.empty
      descriptorOf = None
      lastProbe = None
      lastDeclared = None
      published = Map.empty
      consumers = Map.empty
      views = Map.empty
      listedFor = None

  override def afterEach(context: AfterEach): Unit =
    if !munitIgnore then
      if strimziStopped then startStrimzi()
      for (service, p) <- made.distinct if disposable(service) do
        ankka("services", "delete", service, "-p", p): Unit
      for (service, p) <- made.distinct if disposable(service) do
        waitFor(120.seconds, s"$service going away") {
          k8s.apps().deployments().inNamespace(ns(p)).withName(service).get() == null
        }

  // ── descriptors ───────────────────────────────────────────────────────────

  /** A service as the scenario describes it, before it is applied. */
  private final case class Desc(
      service: String,
      project: String,
      env: Map[String, String] = Map.empty,
      hosting: Option[String] = None,
      image: String = SampleImage
  ):
    def json: String =
      val fields = Vector(
        Some(s""""image":"$image""""),
        hosting.map(h => s""""hosting":"$h""""),
        Option.when(hosting.contains("process"))(""""protocol":"1.0""""),
        Option.when(hosting.contains("web"))(""""processPort":8080"""),
        Option.when(env.nonEmpty)(
          env
            .map((k, v) => s"""{"name":"$k","value":"$v"}""")
            .mkString(""""env":[""", ",", "]")
        )
      ).flatten
      s"""{"name":"$service","service":{${fields.mkString(",")}}}"""

  /** What each service was last applied as, for applying it again. */
  private var appliedAs: Map[(String, String), Desc] = Map.empty
  private var descriptorOf: Option[Desc]             = None
  private var lastApply: Run                         = Run(0, "", "")

  private def apply(d: Desc): Run =
    ensureProject(d.project)
    val file = Files.createTempFile("ankka-broker", ".json")
    try
      Files.writeString(file, d.json): Unit
      val run = ankka("services", "apply", "-f", file.toString, "-p", d.project)
      if run.code == 0 then
        appliedAs += (d.service, d.project) -> d
        made :+= (d.service, d.project)
      lastApply = run
      run
    finally Files.deleteIfExists(file): Unit

  private def ready(service: String, p: String): Unit =
    waitFor(360.seconds, s"$service of $p being Ready") {
      statusOf(service, p).exists(s =>
        s.lifecycle == ServiceLifecycle.Ready && s.readyInstances >= 1 && s.confirmed
      )
    }

  /**
   * The cart sample as `service` of `p`, its checkout notices published to (and read from)
   * `notices`.
   */
  private def deploy(service: String, p: String, notices: String = "cart-checkouts"): Desc =
    val d = Desc(service, p, Map("CART_CHECKOUTS_TOPIC" -> notices))
    ok(apply(d))
    ready(service, p)
    d

  /** The scenario's last declaration, and its project. */
  private var lastDeclared: Option[(String, String)] = None
  private var lastDeclare: Run                       = Run(0, "", "")

  /** Declares a topic on a project through the CLI, as a member does. */
  private def declare(t: String, p: String, n: Int = -1): Run =
    ensureProject(p)
    val count = if n < 0 then partitionsOf(p, t) else n
    val run =
      ankka("projects", "topics", "set", t, "--partitions", count.toString, "-p", p)
    if run.code == 0 then partitions += (p, t) -> count
    lastDeclared = Some((p, t))
    lastDeclare = run
    run

  private def topicMade(p: String, t: String): Unit =
    waitFor(180.seconds, s"$p.$t being made")(topicReady(s"$p.$t"))

  /** A topic's phase as `ankka projects topics list` shows it. */
  private def topicPhase(p: String, t: String): Option[String] =
    val run = ankka("projects", "topics", "list", "-p", p, "-o", "json")
    Option
      .when(run.code == 0)(readFromString[Vector[ProjectTopic]](run.out))
      .flatMap(_.find(_.name == t))
      .flatMap(_.phase)

  /** The topic a deployed cart's notices go to, changed by applying it again if it differs. */
  private def noticesTo(service: String, p: String, topic: String): Unit =
    val current = appliedAs.getOrElse((service, p), fail(s"$service of $p was never deployed"))
    if !current.env.get("CART_CHECKOUTS_TOPIC").contains(topic) then
      ok(apply(current.copy(env = current.env.updated("CART_CHECKOUTS_TOPIC", topic))))
      waitFor(240.seconds, s"$service of $p publishing to $topic") {
        environment(service, p)
          .get(service)
          .exists(_.get("CART_CHECKOUTS_TOPIC").contains(topic)) &&
        statusOf(service, p).exists(s =>
          s.lifecycle == ServiceLifecycle.Ready && s.confirmed && s.readyInstances >= 1
        ) && podsOf(service, p).forall(pod => podHas(pod, topic))
      }

  private def podsOf(service: String, p: String) =
    k8s
      .pods()
      .inNamespace(ns(p))
      .withLabel("app.kubernetes.io/name", service)
      .list()
      .getItems
      .asScala
      .toVector

  private def podHas(pod: io.fabric8.kubernetes.api.model.Pod, topic: String): Boolean =
    pod.getSpec.getContainers.asScala.exists(
      _.getEnv.asScala.exists(e => e.getName == "CART_CHECKOUTS_TOPIC" && e.getValue == topic)
    ) && Option(pod.getStatus.getConditions)
      .exists(_.asScala.exists(c => c.getType == "Ready" && c.getStatus == "True"))

  // ── calling a service, and the broker ─────────────────────────────────────

  private var probers: Set[String] = Set.empty

  /** A request to `service` from inside its project's namespace. */
  private def call(
      service: String,
      p: String,
      path: String,
      method: String = "GET",
      body: Option[String] = None
  ): (Int, String) =
    if !probers(p) then
      InPod.prober(k3s, ns(p), service): Unit
      probers += p
    InPod.curl(
      k3s,
      ns(p),
      "prober",
      s"https://$service.${ns(p)}.svc.cluster.local:9000$path",
      method = method,
      body = body
    )

  /** What a cart's checkout was published as: the cart's id, which the notice carries. */
  private var published: Map[String, String] = Map.empty

  private def checkout(service: String, p: String): String =
    val cart = s"cart-${java.util.UUID.randomUUID().toString.take(8)}"
    val add = call(
      service,
      p,
      s"/carts/$cart/items",
      "POST",
      Some("""{"productId":"pen","name":"Pen","quantity":1}""")
    )
    assert(Set(200, 204)(add._1), s"adding to $cart: $add")
    val done = call(service, p, s"/carts/$cart/checkout", "POST")
    assertEquals(done._1, 200, s"checking out $cart: $done")
    published += service -> cart
    cart

  private var probes: Map[(String, String), BrokerProbe] = Map.empty

  /** The probe holding `service`'s credential, started the first time it is asked for. */
  private def probe(service: String, p: String): BrokerProbe =
    probes.getOrElse(
      (service, p), {
        val made = BrokerProbe.holding(k3s, ns(p), service)
        probes += (service, p) -> made
        made
      }
    )

  /** A group of `service`'s own, as the broker's permission for it reads them. */
  private def groupOf(service: String, p: String) =
    s"ankka.$p.$service.probe.${java.util.UUID.randomUUID().toString.take(8)}"

  /** Reads `topic` as `service` until `cart` is on it, or fails naming what was read. */
  private def readUntil(service: String, p: String, topic: String, cart: String): Unit =
    var last: BrokerProbe.Result = BrokerProbe.Result(0, "")
    waitFor(120.seconds, s"$cart being read from $topic") {
      last = probe(service, p).read(topic, groupOf(service, p), waitMs = 15000)
      last.output.contains(cart)
    }

  private var lastProbe: Option[BrokerProbe.Result] = None

  /** The service each consumer and each view the scenario named belongs to. */
  private var consumers: Map[String, (String, String)] = Map.empty
  private var views: Map[String, (String, String)]     = Map.empty
  private var listedFor: Option[(String, String)]      = None

  private def consumer(name: String) = consumers.getOrElse(name, fail(s"no consumer '$name'"))
  private def view(name: String)     = views.getOrElse(name, fail(s"no view '$name'"))

  private def seen(reader: String, p: String, cart: String): (Int, String) =
    call(reader, p, s"/checkouts-seen/$cart")

  // ── Strimzi's operators, stopped and started ──────────────────────────────

  /**
   * Stops what makes the broker's objects: Strimzi's cluster operator, so nothing restarts the
   * entity operator, then the entity operator, whose topic and user operators make topics and
   * users. Nothing the broker already holds changes.
   */
  private def stopStrimzi(): Unit =
    strimziStopped = true
    PkiStack.kubectl(
      k3s,
      "scale",
      "deployment",
      "-n",
      Broker,
      "strimzi-cluster-operator",
      "--replicas=0"
    ): Unit
    PkiStack.kubectl(
      k3s,
      "scale",
      "deployment",
      "-n",
      Broker,
      s"${BrokerStack.Cluster}-entity-operator",
      "--replicas=0"
    ): Unit
    waitFor(120.seconds, "Strimzi's operators stopping") {
      jsonPath(
        "pods",
        "-n",
        Broker,
        "-l",
        "strimzi.io/name in (strimzi-cluster-operator,ankka-entity-operator)",
        "{.items[*].metadata.name}"
      ).isEmpty &&
      jsonPath(
        "pods",
        "-n",
        Broker,
        "-l",
        "name=strimzi-cluster-operator",
        "{.items[*].metadata.name}"
      ).isEmpty
    }

  private def startStrimzi(): Unit =
    PkiStack.kubectl(
      k3s,
      "scale",
      "deployment",
      "-n",
      Broker,
      "strimzi-cluster-operator",
      "--replicas=1"
    ): Unit
    waitFor(300.seconds, "Strimzi's entity operator being ready again") {
      jsonPath(
        "deployment",
        "-n",
        Broker,
        s"${BrokerStack.Cluster}-entity-operator",
        "{.status.readyReplicas}"
      ) == "1"
    }
    strimziStopped = false

  // ═══ Given ════════════════════════════════════════════════════════════════

  Given("an installation with a broker")(() => assert(operatorSettings.broker.isDefined))

  Given("a local platform, newly made") { () =>
    // The installation here is the broker component as the local overlay applies it, with nothing
    // done by hand beyond applying it.
    assert(operatorSettings.broker.isDefined)
  }

  Given("a project {string}")((p: String) => ensureProject(project(p)))

  Given("the topic {string} is declared on {string} with {int} partitions") {
    (t: String, p: String, n: Int) =>
      ok(declare(t, project(p), n))
      topicMade(project(p), t)
  }

  Given("the topic {string} is declared on {string}") { (t: String, p: String) =>
    ok(declare(t, project(p)))
    topicMade(project(p), t)
  }

  Given("a project with a declared topic") { () =>
    ensureProject(project("money"))
    ok(declare("orders", project("money")))
    topicMade(project("money"), "orders")
  }

  Given("a deployed service {string} in {string}") { (s: String, p: String) =>
    deploy(a(s), project(p)): Unit
  }

  Given("a deployed service {string} in the project {string}") { (s: String, p: String) =>
    deploy(a(s), project(p)): Unit
  }

  Given("a deployed service {string} in {string} hosted as a process") { (s: String, p: String) =>
    // Only the Deployment is read: the process need not be a real one.
    val d =
      Desc(a(s), project(p), hosting = Some("process"), image = "registry.k8s.io/pause:3.9")
    ok(apply(d))
    waitFor(120.seconds, s"${a(s)}'s Deployment") {
      environment(a(s), project(p)).size == 2
    }
  }

  Given(
    "a deployed service {string} in {string}, with a view {string} that reads the topic {string}"
  ) { (s: String, p: String, v: String, t: String) =>
    deploy(a(s), project(p), notices = t): Unit
    views += v -> (a(s), project(p))
  }

  Given("a descriptor for the web-hosted service {string} in {string}") { (s: String, p: String) =>
    descriptorOf = Some(
      Desc(a(s), project(p), hosting = Some("web"), image = "registry.k8s.io/pause:3.9")
    )
  }

  Given("a descriptor for a service {string} that gives the variable {string}") {
    (s: String, variable: String) =>
      descriptorOf = Some(Desc(a(s), project("money"), env = Map(variable -> "elsewhere:9092")))
  }

  Given("a descriptor for the sample {string} in that project that gives no broker variable") {
    (s: String) =>
      descriptorOf =
        Some(Desc(a(s), project("money"), env = Map("CART_CHECKOUTS_TOPIC" -> "orders")))
  }

  Given("a consumer {string} of {string} that publishes to the topic {string}") {
    (c: String, s: String, t: String) =>
      val p = currentProject(a(s))
      consumers += c -> (a(s), p)
      noticesTo(a(s), p, t)
  }

  Given(
    "a consumer {string} of {string} that has handled an event and waits to publish to the topic {string}"
  ) { (c: String, s: String, t: String) =>
    val p = currentProject(a(s))
    consumers += c -> (a(s), p)
    noticesTo(a(s), p, t)
    checkout(a(s), p): Unit
    waitFor(120.seconds, s"${a(s)}'s log naming $t") {
      logsOf(a(s), p).contains(s"could not publish to topic '$t'")
    }
  }

  Given("a view {string} of {string} that reads the topic {string}") {
    (v: String, s: String, t: String) =>
      val p = currentProject(a(s))
      views += v -> (a(s), p)
      noticesTo(a(s), p, t)
  }

  Given("a consumer of {string} has published to the topic {string}") { (s: String, t: String) =>
    val p = currentProject(a(s))
    noticesTo(a(s), p, t)
    val cart = checkout(a(s), p)
    // The probe is started now, holding the service's certificate, so it can read once the service
    // and its certificate are gone.
    readUntil(a(s), p, s"$p.$t", cart)
  }

  Given("{string} has since been deleted") { (s: String) =>
    deleteService(a(s), currentProject(a(s)))
  }

  Given("a view {string} of {string} that reads the topic {string} and shows what was published") {
    (v: String, s: String, t: String) =>
      val p = currentProject(a(s))
      views += v -> (a(s), p)
      val cart = published.getOrElse(a(s), fail(s"nothing was published by ${a(s)}"))
      noticesTo(a(s), p, t)
      waitFor(120.seconds, s"the view of ${a(s)} showing $cart")(seen(a(s), p, cart)._1 == 200)
  }

  Given("the declaration of the topic {string} has since been removed from {string}") {
    (t: String, p: String) =>
      ok(ankka("projects", "topics", "unset", t, "-p", project(p))): Unit
  }

  // the outlines' states -------------------------------------------------------

  /** The declared topic the outline's rows are about: the scenario's last declaration. */
  private def outlineTopic: (String, String) =
    lastDeclared.getOrElse(fail("nothing was declared"))

  Given("the installation's broker has not yet made the topic") { () =>
    val (p, t) = outlineTopic
    stopStrimzi()
    // What the operator finds for a topic nobody has made yet: the resource, and no report on it.
    PkiStack.kubectl(
      k3s,
      "patch",
      "kafkatopic",
      s"$p.$t",
      "-n",
      Broker,
      "--subresource=status",
      "--type=merge",
      "-p",
      """{"status":{"conditions":[]}}"""
    ): Unit
  }

  Given("the installation's broker has made the topic with fewer partitions so far") { () =>
    val (p, t) = outlineTopic
    stopStrimzi()
    ok(declare(t, p, partitionsOf(p, t) + 1))
  }

  Given("the installation's broker has made the topic") { () =>
    val (p, t) = outlineTopic
    topicMade(p, t)
  }

  Given("the installation's broker has a problem with the topic that will not clear") { () =>
    val (p, t) = outlineTopic
    val name   = s"$p.$t"
    val more   = partitionsOf(p, t) + 1
    // Grown by hand beyond what the project declares: the platform never makes a topic smaller, so
    // nothing it can do clears this. A reconcile already under way when the change lands applies
    // the declared count it read a moment before, so the change is made again until Strimzi has
    // acted on it; from then on the broker holds more partitions than are declared, and whether
    // the resource says so or Strimzi refuses the smaller count, the status is the same.
    waitFor(120.seconds, s"$name being grown to $more by hand") {
      if jsonPath("kafkatopic", "-n", Broker, name, "{.spec.partitions}") != more.toString then
        PkiStack.kubectl(
          k3s,
          "patch",
          "kafkatopic",
          name,
          "-n",
          Broker,
          "--type=merge",
          "-p",
          s"""{"spec":{"partitions":$more}}"""
        ): Unit
      Thread.sleep(3000)
      jsonPath("kafkatopic", "-n", Broker, name, "{.spec.partitions}") == more.toString &&
      jsonPath("kafkatopic", "-n", Broker, name, "{.status.observedGeneration}") ==
        jsonPath("kafkatopic", "-n", Broker, name, "{.metadata.generation}")
    }
    afterTheScenario = () => ok(declare(t, p, more)): Unit
  }

  Given("the installation's broker has not yet made the credential") { () =>
    val (s, p) = made.lastOption.getOrElse(fail("no service"))
    stopStrimzi()
    PkiStack.kubectl(
      k3s,
      "patch",
      "kafkauser",
      s"$p.$s",
      "-n",
      Broker,
      "--subresource=status",
      "--type=merge",
      "-p",
      """{"status":{"conditions":[]}}"""
    ): Unit
  }

  Given("the installation's broker has made the credential") { () =>
    val (s, p) = made.lastOption.getOrElse(fail("no service"))
    waitFor(120.seconds, "the user being made") {
      jsonPath(
        "kafkauser",
        "-n",
        Broker,
        s"$p.$s",
        """{.status.conditions[?(@.type=="Ready")].status}"""
      ) == "True"
    }
  }

  private var afterTheScenario: () => Unit = () => ()

  // ═══ When ═════════════════════════════════════════════════════════════════

  When("a member applies the descriptor") { () =>
    apply(descriptorOf.getOrElse(fail("no descriptor"))): Unit
  }

  When("a member declares the topic {string} on {string} with {int} partitions") {
    (t: String, p: String, n: Int) =>
      declare(t, project(p), n): Unit
  }

  When("a member declares the topic {string} on {string}") { (t: String, p: String) =>
    ok(declare(t, project(p))): Unit
  }

  When("a member removes the declaration of the topic {string} from {string}") {
    (t: String, p: String) =>
      ok(ankka("projects", "topics", "unset", t, "-p", project(p))): Unit
  }

  When("a member reads the topics of {string}")((_: String) => ())

  When("the environment of {string} is read")((_: String) => ())

  When("{string} connects to the installation's broker") { (s: String) =>
    val p = currentProject(a(s))
    lastProbe = Some(probe(a(s), p).topics())
  }

  When("{string} handles an event") { (c: String) =>
    val (s, p) = consumer(c)
    checkout(s, p): Unit
  }

  When("a consumer of {string} publishes to the topic {string}") { (s: String, t: String) =>
    val p = currentProject(a(s))
    noticesTo(a(s), p, t)
    checkout(a(s), p): Unit
  }

  When("a member reads the status of {string}")((_: String) => ())

  When("a service of {string} publishes to the topic {string}") { (p: String, t: String) =>
    val pp = project(p)
    if !appliedAs.contains(("wallet", pp)) then deploy("wallet", pp, notices = t): Unit
    noticesTo("wallet", pp, t)
    checkout("wallet", pp): Unit
  }

  When("something holding the credential of {string} reads {string} on the installation's broker") {
    (s: String, topic: String) =>
      val p = currentProject(a(s))
      lastProbe = Some(probe(a(s), p).read(topic, groupOf(a(s), p), waitMs = 15000))
  }

  When(
    "something holding the credential of {string} publishes to {string} on the installation's broker"
  ) { (s: String, topic: String) =>
    val p = currentProject(a(s))
    lastProbe = Some(probe(a(s), p).publish(topic, "from-another-project"))
  }

  When("what the installation's broker allows the credential of {string} is listed") {
    (s: String) =>
      val p = currentProject(a(s))
      listedFor = Some((a(s), p))
      lastProbe = Some(probe(a(s), p).topics())
  }

  When("a member deletes {string}") { (s: String) =>
    deleteService(a(s), currentProject(a(s)))
  }

  When("a member applies the descriptor for {string} again") { (s: String) =>
    val key = (a(s), currentProject(a(s)))
    ok(apply(appliedAs.getOrElse(key, fail(s"${a(s)} was never applied"))))
    ready(key._1, key._2)
  }

  When("a member deletes the project {string}") { (p: String) =>
    for (s, pp) <- appliedAs.keys if pp == project(p) && statusOf(s, pp).isDefined do
      deleteService(s, pp)
    ok(ankka("projects", "delete", project(p))): Unit
  }

  When("a workload that is not of the installation connects to the broker") { () =>
    lastProbe = Some(outsiderConnects())
  }

  When("the certificate of the broker is read")(() => ())

  // ═══ Then ═════════════════════════════════════════════════════════════════

  Then("the installation's broker has the topic {string} of {string} with {int} partitions") {
    (t: String, p: String, n: Int) =>
      assertEquals(lastDeclare.code, 0, lastDeclare.all)
      val name = s"${project(p)}.$t"
      waitFor(180.seconds, s"$name being made with $n partitions") {
        topicReady(name) &&
        jsonPath("kafkatopic", "-n", Broker, name, "{.spec.partitions}") == n.toString
      }
  }

  Then("the broker holds that topic under the name {string}") { (name: String) =>
    val (p, t) = name.splitAt(name.indexOf('.'))
    val held   = s"${project(p)}$t"
    assertEquals(jsonPath("kafkatopic", "-n", Broker, held, "{.status.topicName}"), held)
  }

  Then("the member is refused, and the refusal names the partitions") { () =>
    assertEquals(lastDeclare.code, 1, lastDeclare.all)
    assert(lastDeclare.all.contains("cannot have fewer"), lastDeclare.all)
  }

  Then("the topic {string} is {string}") { (t: String, word: String) =>
    val p      = lastDeclared.map(_._1).getOrElse(project("money"))
    val phrase = ProjectEndpoint.topicPhrase(word)
    waitFor(180.seconds, s"the topic $t of $p being '$phrase'") {
      topicPhase(p, t).contains(phrase)
    }
    afterTheScenario()
    afterTheScenario = () => ()
  }

  Then("it has the variable {string}, naming the installation's broker") { (variable: String) =>
    val (s, p) = made.lastOption.getOrElse(fail("no service"))
    assertEquals(
      environment(s, p).get(s).flatMap(_.get(variable)),
      Some(BrokerStack.settings.bootstrap)
    )
  }

  Then("the broker is told that it is {string} of {string} by the certificate of {string}") {
    (s: String, p: String, holder: String) =>
      val result = lastProbe.getOrElse(fail("nothing connected"))
      assertEquals(result.code, 0, result.output)
      val user = s"${project(p)}.${a(s)}"
      // The broker names the principal from the certificate's subject, and the certificate the
      // service already holds now carries its project and name.
      assertEquals(jsonPath("kafkauser", "-n", Broker, user, "{.status.username}"), s"CN=$user")
      assertEquals(
        jsonPath(
          "certificate",
          "-n",
          ns(project(p)),
          s"${a(holder)}-service",
          "{.spec.commonName}"
        ),
        user
      )
  }

  Then("the platform keeps no other credential for {string} on the broker") { (s: String) =>
    val p    = currentProject(a(s))
    val user = s"$p.${a(s)}"
    assertEquals(
      jsonPath("kafkauser", "-n", Broker, user, "{.spec.authentication.type}"),
      "tls-external"
    )
    // No password and no certificate of Strimzi's: tls-external makes no Secret.
    assertEquals(jsonPath("secret", "-n", Broker, user, "{.metadata.name}"), "")
  }

  Then("the platform's program of {string} is given the variable {string}") {
    (s: String, variable: String) =>
      val env = environment(a(s), currentProject(a(s)))
      assertEquals(env.get(a(s)).flatMap(_.get(variable)), Some(BrokerStack.settings.bootstrap))
  }

  Then("the process of {string} is given the variable {string}") { (s: String, variable: String) =>
    val env = environment(a(s), currentProject(a(s)))
    assertEquals(
      env.get(s"${a(s)}-app").flatMap(_.get(variable)),
      Some(BrokerStack.settings.bootstrap)
    )
  }

  Then("what {string} published is read from {string} on the installation's broker") {
    (c: String, topic: String) =>
      val (s, p) = consumer(c)
      val cart   = published.getOrElse(s, fail(s"$s published nothing"))
      readUntil(s, p, topic, cart)
  }

  Then("the view {string} shows what was published") { (v: String) =>
    val (reader, p) = view(v)
    val cart        = published.values.lastOption.getOrElse(fail("nothing was published"))
    waitFor(120.seconds, s"$reader's view showing $cart")(seen(reader, p, cart)._1 == 200)
  }

  Then("the status says that the broker of {string} is {string}") { (s: String, word: String) =>
    val p      = currentProject(a(s))
    val phrase = Service.brokerPhrase(word)
    waitFor(180.seconds, s"the status of ${a(s)} saying '$phrase'") {
      statusOf(a(s), p).flatMap(_.broker).contains(phrase)
    }
  }

  Then("the status of {string} says that its broker is {string}") { (s: String, word: String) =>
    val p      = currentProject(a(s))
    val phrase = Service.brokerPhrase(word)
    waitFor(180.seconds, s"the status of ${a(s)} saying '$phrase'") {
      statusOf(a(s), p).flatMap(_.broker).contains(phrase)
    }
  }

  Then("the installation's broker has no topic {string} of {string}") { (t: String, p: String) =>
    val name = s"${project(p)}.$t"
    assert(!topicExists(name), s"$name was made")
    val (s, pp) = liveServiceIn(project(p))
    val listed  = probe(s, pp).topics()
    assertEquals(listed.code, 0, listed.output)
    assert(!listed.messages.contains(name), listed.output)
  }

  Then("the logs of {string} name the topic {string}") { (s: String, t: String) =>
    val p = currentProject(a(s))
    waitFor(120.seconds, s"${a(s)}'s log naming $t") {
      logsOf(a(s), p).contains(s"could not publish to topic '$t' ($p.$t on the broker)")
    }
  }

  Then("{string} is ready") { (s: String) =>
    val p = currentProject(a(s))
    ready(a(s), p)
  }

  Then("what {string} waited to publish is read from {string} on the installation's broker") {
    (c: String, topic: String) =>
      val (s, p) = consumer(c)
      val cart   = published.getOrElse(s, fail(s"$s published nothing"))
      readUntil(s, p, topic, cart)
  }

  Then("the environment of {string} has no broker variable") { (s: String) =>
    val p = currentProject(a(s))
    waitFor(120.seconds, s"${a(s)}'s Deployment")(environment(a(s), p).nonEmpty)
    val broker = environment(a(s), p).flatMap((c, env) =>
      env.keys.filter(_.startsWith("ANKKA_KAFKA_")).map(k => s"$c:$k")
    )
    assertEquals(broker.toVector, Vector.empty)
  }

  Then("the installation's broker has no credential for {string}") { (s: String) =>
    val p = currentProject(a(s))
    waitFor(120.seconds, s"${a(s)}'s Deployment")(environment(a(s), p).nonEmpty)
    // Given time to make one, had it been going to.
    Thread.sleep(5000)
    assert(!userExists(s"$p.${a(s)}"), s"a credential was made for $p.${a(s)}")
  }

  Then("the sample is ready") { () =>
    val d = descriptorOf.getOrElse(fail("no descriptor"))
    assertEquals(lastApply.code, 0, lastApply.all)
    ready(d.service, d.project)
  }

  Then("what its consumer publishes is read from the installation's broker") { () =>
    val d    = descriptorOf.getOrElse(fail("no descriptor"))
    val cart = checkout(d.service, d.project)
    readUntil(d.service, d.project, s"${d.project}.orders", cart)
  }

  Then("the workload is refused") { () =>
    val outsider = lastProbe.getOrElse(fail("nothing connected"))
    assertNotEquals(
      outsider.code,
      0,
      s"a workload outside the installation connected: ${outsider.output}"
    )
    // The same connection from a workload of the installation is admitted, so the refusal is the
    // workload's, not the address's.
    val (s, p) = liveOrDeployedIn(project("money"))
    val inside = probe(s, p).topics()
    assertEquals(inside.code, 0, inside.output)
  }

  Then("the certificate names the platform and no project a member can have") { () =>
    val uris = jsonPath("certificate", "-n", Broker, "ankka-broker", "{.spec.uris[*]}")
    assertEquals(uris, "ankka://platform/broker")
    assert(ProjectId.Reserved.contains("platform"))
    // And the certificate the broker serves is the one issued for that identity.
    assertEquals(
      jsonPath(
        "secret",
        "-n",
        Broker,
        "ankka-broker-tls",
        "{.metadata.annotations.cert-manager\\.io/uri-sans}"
      ),
      "ankka://platform/broker"
    )
  }

  Then("the view {string} shows nothing of what was published") { (v: String) =>
    val p    = project("money")
    val cart = published.getOrElse("wallet", fail("nothing was published"))
    // It reached the topic of "money", so there was something to show.
    readUntil("wallet", p, s"$p.transactions", cart)
    val reader   = view(v)
    val deadline = 20.seconds.fromNow
    while deadline.hasTimeLeft() do
      assertEquals(seen(reader._1, reader._2, cart)._1, 404, s"${reader._1} showed $cart")
      Thread.sleep(2000)
  }

  Then("the logs of {string} name the topic {string} as one {string} has not declared") {
    (s: String, t: String, p: String) =>
      val name = s"${project(p)}.$t"
      waitFor(120.seconds, s"${a(s)}'s log naming $name as unknown") {
        logsOf(a(s), project(p)).linesIterator.exists(line =>
          line.contains(name) && line.contains("UNKNOWN_TOPIC_OR_PARTITION")
        )
      }
  }

  Then("the broker refuses it") { () =>
    val result = lastProbe.getOrElse(fail("nothing was asked"))
    assert(result.refused, s"the broker did not refuse: ${result.output}")
  }

  Then("the credential may read and publish to the topics of {string}") { (p: String) =>
    val (s, pp) = listedFor.getOrElse(fail("nothing was listed"))
    assertEquals(pp, project(p))
    val acls = aclsOf(s"$pp.$s")
    assert(
      acls.contains(("topic", s"$pp.", "prefix", Set("Read", "Write", "Describe"))),
      acls.toString
    )
  }

  Then("the credential may do nothing else on the broker") { () =>
    val (s, p) = listedFor.getOrElse(fail("nothing was listed"))
    val acls   = aclsOf(s"$p.$s")
    assertEquals(
      acls.toSet,
      Set(
        ("topic", s"$p.", "prefix", Set("Read", "Write", "Describe")),
        ("group", s"ankka.$p.$s.", "prefix", Set("Read"))
      )
    )
    // The broker lists it nothing of another project, and refuses it a group not its own.
    val listed = lastProbe.getOrElse(fail("nothing was listed"))
    assertEquals(listed.code, 0, listed.output)
    assert(listed.messages.forall(_.startsWith(s"$p.")), listed.output)
    val foreign = probe(s, p).read(s"$p.anything", "somebody-else", waitMs = 10000)
    assert(foreign.output.contains("GroupAuthorizationException"), foreign.output)
  }

  Then("the installation's broker still has the topic {string} of {string}") {
    (t: String, p: String) =>
      val name = s"${project(p)}.$t"
      Thread.sleep(5000)
      assert(topicExists(name), s"$name was removed")
      assert(topicReady(name), s"$name is not ready")
  }

  Then("what was published to it is still read from it") { () =>
    val p    = project("money")
    val cart = published.getOrElse("wallet", fail("nothing was published"))
    readUntil("wallet", p, s"$p.transactions", cart)
    assert(userExists(s"$p.wallet"), "the user was removed")
  }

  Then("what was published before {string} was deleted is still read from the topic {string}") {
    (s: String, t: String) =>
      val p    = currentProject(a(s))
      val cart = published.getOrElse(a(s), fail("nothing was published"))
      readUntil(a(s), p, s"$p.$t", cart)
  }

  Then("the view {string} shows what was published once and not twice") { (v: String) =>
    val (s, p) = view(v)
    val cart   = published.getOrElse(s, fail("nothing was published"))
    // Time for the view to read the topic again, had it started over.
    var last = (0, "")
    waitFor(120.seconds, s"$s's view showing $cart") {
      last = seen(s, p, cart)
      last._1 == 200
    }
    Thread.sleep(20000)
    last = seen(s, p, cart)
    assertEquals(last._1, 200, last._2)
    assert(last._2.contains(""""notices":1"""), last._2)
  }

  // ── helpers for the steps ─────────────────────────────────────────────────

  /** The project a service was last applied in. */
  private def currentProject(service: String): String =
    made.reverseIterator
      .collectFirst { case (s, p) if s == service => p }
      .orElse(appliedAs.keys.collectFirst { case (s, p) if s == service => p })
      .getOrElse(project("money"))

  /** A service of `p` that is running, deploying the cart as "wallet" when none is. */
  private def liveOrDeployedIn(p: String): (String, String) =
    appliedAs.keys
      .find((s, pp) =>
        pp == p && k8s.apps().deployments().inNamespace(ns(pp)).withName(s).get() != null
      )
      .getOrElse {
        deploy("wallet", p, notices = "transactions"): Unit
        ("wallet", p)
      }

  private def liveServiceIn(p: String): (String, String) =
    appliedAs.keys
      .find((s, pp) =>
        pp == p && k8s.apps().deployments().inNamespace(ns(pp)).withName(s).get() != null
      )
      .getOrElse(fail(s"no service of $p is deployed"))

  private def deleteService(service: String, p: String): Unit =
    ok(ankka("services", "delete", service, "-p", p))
    waitFor(180.seconds, s"$service going away") {
      k8s.apps().deployments().inNamespace(ns(p)).withName(service).get() == null &&
      podsOf(service, p).isEmpty
    }

  /** The ACL rules of a user: resource type, name, pattern, operations. */
  private def aclsOf(user: String): Vector[(String, String, String, Set[String])] =
    val raw = jsonPath(
      "kafkauser",
      "-n",
      Broker,
      user,
      """{range .spec.authorization.acls[*]}{.resource.type}|{.resource.name}|{.resource.patternType}|{.operations}{"\n"}{end}"""
    )
    raw.linesIterator
      .filter(_.nonEmpty)
      .map { line =>
        val Array(kind, name, pattern, ops) = line.split("\\|", 4)
        (kind, name, pattern, ops.replaceAll("[\\[\\]\"]", "").split(",").map(_.trim).toSet)
      }
      .toVector

  /**
   * A pod in a namespace the platform did not make, of the broker's own image, opening a connection
   * to the broker's port. Its exit code is 0 only when the connection was made.
   */
  private def outsiderConnects(): BrokerProbe.Result =
    val image = jsonPath(
      "pods",
      "-n",
      Broker,
      "-l",
      s"strimzi.io/cluster=${BrokerStack.Cluster},strimzi.io/name=${BrokerStack.Cluster}-kafka",
      "{.items[0].spec.containers[0].image}"
    )
    val manifest =
      s"""apiVersion: v1
         |kind: Namespace
         |metadata: { name: outsider }
         |---
         |apiVersion: v1
         |kind: Pod
         |metadata: { name: outsider, namespace: outsider }
         |spec:
         |  containers:
         |    - name: outsider
         |      image: $image
         |      imagePullPolicy: IfNotPresent
         |      command: ["sleep", "infinity"]
         |""".stripMargin
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      "/tmp/outsider.yaml"
    )
    PkiStack.kubectl(k3s, "apply", "-f", "/tmp/outsider.yaml"): Unit
    PkiStack.kubectl(
      k3s,
      "wait",
      "-n",
      "outsider",
      "--for=condition=Ready",
      "pod/outsider",
      "--timeout=180s"
    ): Unit
    val (host, port) = BrokerStack.settings.bootstrap.split(":") match
      case Array(h, p) => (h, p)
      case _           => fail(s"bootstrap ${BrokerStack.settings.bootstrap}")
    val r = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      "outsider",
      "outsider",
      "--",
      "bash",
      "-c",
      s"timeout 10 bash -c 'exec 3<>/dev/tcp/$host/$port' && echo connected"
    )
    BrokerProbe.Result(r.getExitCode, (r.getStdout + r.getStderr).trim)

  // ═══ the operator's grant on the broker ═══════════════════════════════════

  test("the operator may make and change the broker's topics and users, and remove none") {
    assume(!munitIgnore)
    // The component's grant, to the operator's own ServiceAccount, judged by the API server for a
    // token of that account: not the suite's administrator, and not the in-process operator's.
    node("kubectl", "create", "namespace", "ankka-operator"): Unit
    node("kubectl", "create", "serviceaccount", "ankka-operator", "-n", "ankka-operator"): Unit
    val token =
      PkiStack.kubectl(k3s, "create", "token", "ankka-operator", "-n", "ankka-operator").trim
    def asOperator(args: String*): (Int, String) =
      val r = k3s.execInContainer(
        (Vector(
          "kubectl",
          "--kubeconfig=/dev/null",
          "--server=https://127.0.0.1:6443",
          "--insecure-skip-tls-verify",
          s"--token=$token"
        ) ++ args)*
      )
      (r.getExitCode, (r.getStdout + r.getStderr).trim)
    def may(verb: String, resource: String): String =
      asOperator("auth", "can-i", verb, resource, "-n", Broker)._2
    for resource <- Seq("kafkatopics.kafka.strimzi.io", "kafkausers.kafka.strimzi.io") do
      assertEquals(may("create", resource), "yes", resource)
      assertEquals(may("patch", resource), "yes", resource)
      assertEquals(may("delete", resource), "no", resource)
    // And a removal attempted with that token is refused by the API server itself.
    val anyTopic = jsonPath("kafkatopics", "-n", Broker, "{.items[0].metadata.name}")
    assume(anyTopic.nonEmpty, "no topic to try")
    val (code, out) = asOperator("delete", "kafkatopic", anyTopic, "-n", Broker)
    assertNotEquals(code, 0, out)
    assert(out.contains("forbidden"), out)
    assert(topicExists(anyTopic), s"$anyTopic was removed")
  }

  // ═══ installing a broker on a running installation ═══════════════════════

  test(
    "installing a broker rolls a running service once, gives its certificate its name, and refuses no request meanwhile"
  ) {
    assume(!munitIgnore)
    // Everything else stops: an operator without a broker re-renders every service it finds.
    for ((s, p), _) <- appliedAs if statusOf(s, p).isDefined do deleteService(s, p)
    val p = "money"
    startOperator(operatorSettings.copy(broker = None))
    val d = Desc("early", p)
    ok(apply(d))
    ready("early", p)
    assert(!environment("early", p)("early").contains("ANKKA_KAFKA_BOOTSTRAP_SERVERS"))
    assertEquals(jsonPath("certificate", "-n", ns(p), "early-service", "{.spec.commonName}"), "")
    val before = replicaSets("early", p)

    // Requests throughout, from inside the project, as a caller of the service would make them.
    val failures = ConcurrentLinkedQueue[String]()
    val calling  = AtomicBoolean(true)
    val calls    = new java.util.concurrent.atomic.AtomicInteger(0)
    val caller = Thread.ofVirtual().start { () =>
      while calling.get() do
        val (status, body) = call("early", p, "/carts/steady")
        calls.incrementAndGet(): Unit
        if status != 200 then failures.add(s"$status $body"): Unit
        Thread.sleep(250)
    }

    startOperator(operatorSettings)
    waitFor(300.seconds, "early being given the broker and rolled") {
      environment("early", p)("early")
        .get("ANKKA_KAFKA_BOOTSTRAP_SERVERS")
        .contains(BrokerStack.settings.bootstrap) &&
      statusOf("early", p).exists(s => s.lifecycle == ServiceLifecycle.Ready && s.confirmed) &&
      podsOf("early", p).forall(pod => pod.getMetadata.getDeletionTimestamp == null) &&
      podsOf("early", p).size == 1 && podsOf("early", p).forall(pod =>
        pod.getSpec.getContainers.asScala
          .exists(_.getEnv.asScala.exists(_.getName == "ANKKA_KAFKA_BOOTSTRAP_SERVERS"))
      )
    }
    waitFor(120.seconds, "early's certificate being reissued with its name") {
      jsonPath(
        "secret",
        "-n",
        ns(p),
        "early-service-tls",
        "{.metadata.annotations.cert-manager\\.io/common-name}"
      ) == s"$p.early"
    }
    // A little longer, so a second roll would have begun.
    Thread.sleep(15000)
    calling.set(false)
    caller.join()

    assertEquals(replicaSets("early", p).size - before.size, 1, "early was not rolled exactly once")
    assert(calls.get() > 20, s"only ${calls.get()} requests were made")
    assertEquals(failures.asScala.toVector, Vector.empty)
  }

  /** The ReplicaSets of a service's Deployment, one per rollout. */
  private def replicaSets(service: String, p: String): Set[String] =
    k8s
      .apps()
      .replicaSets()
      .inNamespace(ns(p))
      .withLabel("app.kubernetes.io/name", service)
      .list()
      .getItems
      .asScala
      .map(_.getMetadata.getName)
      .toSet
