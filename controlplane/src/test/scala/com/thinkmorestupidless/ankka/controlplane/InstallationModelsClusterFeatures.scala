package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.{
  Installation,
  ModelLine,
  ServiceLifecycle,
  ServiceStatus
}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  Fabric8AnkkaServiceClient,
  InstallationModelsReader,
  PodLogs,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  InPod,
  ModelServerStack,
  Operator,
  PkiStack,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Base64
import scala.concurrent.{blocking, Await, ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * `features/installation-models/` on k3s (feature 053): the control plane and the operator in this
 * JVM, as `ObjectStorageClusterFeatures` runs them, the model server from its own component with
 * the real Ollama image and real models from Ollama's registry, CloudNativePG and cert-manager
 * beneath, and the shopping cart sample as every service's image. A service names an installation
 * model with the two variables a descriptor gives (`ANKKA_MODEL_LOCAL_PRESET`, `_MODEL`), and the
 * sample, reading them, registers its stock helper on that model (`/helper/ask`), which the
 * scenarios ask from a prober holding the service's certificate: a real tool call answered by the
 * real model server, two calls at once, a call past the model timeout and a call cut by a restart.
 *
 * The operator reads the status document through a forward to the keeper's status port, and the
 * control plane reads the door through a forward with a certificate cert-manager issued it as
 * `ankka://platform/controlplane`, which is the control plane's own identity in a cluster.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the images.
 */
class InstallationModelsClusterFeatures
    extends GherkinSuite("../features/installation-models")
    with LogCapturing:

  override val munitTimeout: FiniteDuration = 20.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  override protected def ranElsewhere: Map[String, String] = Map(
    "a developer's program is refused by the model server" ->
      "DoorSuite (a connection without a certificate fails at the handshake) and RenderingGoldenSuite (a process's container mounts no service certificate)",
    "the platform's program of the same service is answered by the model server" ->
      "DoorSuite (a service's certificate is admitted) and InstallationModelsSuite (the address reaches the sidecar of a process and not the process)",
    "a served model stays served while no call comes"                     -> "KeeperSuite",
    "a served model is fetched once and its digest recorded"              -> "KeeperSuite",
    "the model server's metrics reach the installation's telemetry store" -> "KeeperMetricsSuite",
    "a call to an installation model is a span in the turn's trace" ->
      "InstallationModelsSuite (the call carries the turn's traceparent) and DoorSuite (the door records its span under it)",
    "the model server is placed and sized by the installation's own settings" -> "RemoteOverlaySuite",
    "a local platform asked for the model server serves one small model on a CPU" ->
      "RemoteOverlaySuite",
    "a local platform not asked for the model server has none" -> "RemoteOverlaySuite",
    "a service names an installation model with a preset and a model and no key" ->
      "ModelsSuite (the sidecar resolves the preset to the model server, reached with the service's certificate) and InstallationModelsSuite (the operator gives the address to a process's sidecar)",
    "every call to an installation model is a turn or an iteration in a session" ->
      "InstallationModelsSuite (an installation model is a model provider like any other, so the agent loop records its calls as any provider's)",
    "an agent with tools on a model that cannot call tools is told so at the first call" ->
      "InstallationModelsSuite (agent): nothing is sent to the model",
    "effort on a model that cannot think is told so at the first call" ->
      "InstallationModelsSuite (agent): nothing is sent to the model",
    "a capability failure is the model provider's failure and the service is unaffected" ->
      "InstallationModelsSuite (the refusal is the provider's ModelCallFailed) and IterationFailureSuite (a provider's failure is a failed iteration)",
    "a service run outside an installation that names the preset installation is told there is no model server" ->
      "ModelsSuite (the sidecar) and ProviderConfigSuite (ModelProviders.installation)"
  )

  override protected def ranOutside: Map[String, String] = Map(
    "a service naming an installation model on a cluster without a model server fails to start saying so" ->
      "waits on feature 050, remote clusters, which has no code; InstallationModelsSuite holds the operator's refusal on a cluster with no server"
  )

  private val K3sImage    = "rancher/k3s:v1.35.1-k3s1"
  private val Tag         = com.thinkmorestupidless.ankka.core.BuildInfo.version.replace('+', '-')
  private val SampleImage = s"sample-shopping-cart:$Tag"
  private val KeeperImage = s"ankka-model-keeper:$Tag"
  private val Prefix      = "ankka"
  private val Project     = "shop"
  private val Small       = "qwen3:0.6b"

  private lazy val identity = TestIdentity()
  private lazy val Token = identity.token(
    "tester",
    Some("tester@example.test"),
    roles = Set("platform-admin"),
    expiresIn = 3.hours
  )

  private var k3s: K3sContainer                          = null
  private var k8s: KubernetesClient                      = null
  private var operator: Operator                         = null
  private var operatorSettings: OperatorSettings         = null
  private var server: ModelServerStack.Installed         = null
  private var testKit: AnkkaTestKit                      = null
  private var url: String                                = ""
  private var config: Path                               = null
  private var readerCertificate: Path                    = null
  @volatile private var reader: InstallationModelsReader = InstallationModelsReader.none

  private def repoRoot: Path =
    var dir = Paths.get("").toAbsolutePath
    while !Files.exists(dir.resolve("build.sbt")) do dir = dir.getParent
    dir

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.withExposedPorts(6443)
      k3s.start()
      ClusterImages.importInto(k3s, SampleImage)
      ClusterImages.importInto(k3s, KeeperImage)
      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      k8s.load(getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")).serverSideApply(): Unit
      k8s
        .load(
          URI
            .create(
              "https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.30/releases/cnpg-1.30.0.yaml"
            )
            .toURL
            .openStream()
        )
        .serverSideApply(): Unit
      waitFor(120.seconds, "CloudNativePG's controller") {
        Option(
          k8s
            .apps()
            .deployments()
            .inNamespace("cnpg-system")
            .withName("cnpg-controller-manager")
            .get()
        ).flatMap(d => Option(d.getStatus)).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
      }
      PkiStack.install(k3s, k8s)
      server = ModelServerStack.install(k3s, k8s, repoRoot, KeeperImage, Vector(Small))
      readerCertificate = issueReaderCertificate()
      reader = InstallationModelsReader.Door(server.door, Some(readerCertificate))

      operatorSettings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        models = Some(server.settings)
      )
      startOperator(operatorSettings)

      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        progressDeadline = 300.seconds
      )
      val projector = ServiceProjector.withClient(
        deployConfig,
        new Fabric8AnkkaServiceClient(k8s, Prefix, resyncMillis = 2000L)
      )
      val http = HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(
          identity.acl(),
          deployConfig,
          auth = Some(identity.config()),
          logs = Some(new PodLogs(k8s, Prefix)),
          installationModels = () => reader.read()
        )*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, http)
      )
      url = s"http://127.0.0.1:${http.boundPort.getOrElse(fail("server did not bind"))}"
      config = Files.createTempFile("ankka-installation-models", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString
      ok(ankka("organizations", "create", "acme", "--name", "Acme"))
      ok(ankka("projects", "create", Project, "--name", Project, "--organization", "acme")): Unit

  override def afterAll(): Unit =
    if testKit != null then identity.stop()
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if server != null then server.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  private def startOperator(settings: OperatorSettings): Unit =
    if operator != null then operator.close()
    operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
    operator.start()

  /**
   * The control plane's own certificate, as cert-manager issues it in a cluster, written where the
   * reader looks for `tls.key`, `tls.crt` and `ca.crt`. The suite holds the cluster's admin
   * credential, so it reads the Secret; the control plane in a cluster is mounted it.
   */
  private def issueReaderCertificate(): Path =
    val manifest =
      """apiVersion: cert-manager.io/v1
        |kind: Certificate
        |metadata:
        |  name: controlplane-reader
        |  namespace: default
        |spec:
        |  secretName: controlplane-reader-tls
        |  uris:
        |    - ankka://platform/controlplane
        |  usages:
        |    - client auth
        |  duration: 24h
        |  privateKey:
        |    algorithm: RSA
        |    size: 2048
        |    encoding: PKCS8
        |  issuerRef:
        |    name: ankka-service
        |    kind: ClusterIssuer
        |    group: cert-manager.io
        |""".stripMargin
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      "/tmp/controlplane-reader.yaml"
    )
    PkiStack.kubectl(k3s, "apply", "-f", "/tmp/controlplane-reader.yaml"): Unit
    var data: Map[String, String] = Map.empty
    waitFor(120.seconds, "the control plane's reading certificate") {
      data = Option(k8s.secrets().inNamespace("default").withName("controlplane-reader-tls").get())
        .flatMap(s => Option(s.getData))
        .map(_.asScala.toMap)
        .getOrElse(Map.empty)
      Vector("tls.key", "tls.crt", "ca.crt").forall(k => data.get(k).exists(_.nonEmpty))
    }
    val dir = Files.createTempDirectory("ankka-controlplane-reader")
    for (name, value) <- data if Set("tls.key", "tls.crt", "ca.crt").contains(name) do
      Files.write(dir.resolve(name), Base64.getDecoder.decode(value)): Unit
    dir

  // ── the scenario's state ──────────────────────────────────────────────────

  private var scenario: Int                 = 0
  private var declared: Vector[String]      = Vector(Small)
  private var deployed: Vector[String]      = Vector.empty
  private var pending: Option[String]       = None
  private var status: Option[Installation]  = None
  private var podsBefore: Set[String]       = Set.empty
  private var digestBefore: Option[String]  = None
  private var workloadAnswer: (Int, String) = (0, "")
  private var operatorChanged: Boolean      = false

  /** This scenario's name for a service a feature calls `logical`. */
  private def real(logical: String): String = s"$logical-$scenario"

  override def beforeEach(context: BeforeEach): Unit =
    if !munitIgnore then
      if operatorChanged then
        startOperator(operatorSettings)
        reader = InstallationModelsReader.Door(server.door, Some(readerCertificate))
        operatorChanged = false
      for name <- deployed if statusOf(name).isDefined do
        ankka("services", "delete", name, "-p", Project): Unit
      deployed = Vector.empty
      scenario += 1
      pending = None
      status = None
      podsBefore = Set.empty
      digestBefore = None
      workloadAnswer = (0, "")
      answers = Vector.empty
      modelTimeout = None
      helperService = ""

  // ── the CLI, the cluster and the server ───────────────────────────────────

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
      if !passed then Thread.sleep(1000)
    if !passed then fail(s"$what did not happen within $timeout")

  private def statusOf(name: String): Option[ServiceStatus] =
    val run = ankka("services", "get", name, "-p", Project, "-o", "json")
    Option.when(run.code == 0)(readFromString[ServiceStatus](run.out))

  private def installation(): Installation =
    readFromString[Installation](ok(ankka("installation", "-o", "json")).out)

  private def lines(): Vector[ModelLine] =
    installation().models.toVector.flatMap(_.clusters).flatMap(_.models)

  private def line(model: String): Option[ModelLine] = lines().find(_.name == model)

  private def servicePods = ServicePods(k3s, k8s, p => s"$Prefix-$p")

  private def pods(name: String): Vector[Pod] = servicePods.pods(Project, name)

  private def literal(name: String, value: String) = s"""{"name":"$name","value":"$value"}"""

  private def naming(preset: String, model: String, more: Vector[String] = Vector.empty) =
    Vector(
      literal("ANKKA_MODEL_LOCAL_PRESET", preset),
      literal("ANKKA_MODEL_LOCAL_MODEL", model)
    ) ++
      more

  private def descriptor(name: String, env: Vector[String]): String =
    val fields = Vector(
      Some(s""""image":"$SampleImage""""),
      Option.when(env.nonEmpty)(env.mkString(""""env":[""", ",", "]"))
    ).flatten
    s"""{"name":"$name","service":{${fields.mkString(",")}}}"""

  private def applyJson(json: String): Run =
    val file = Files.createTempFile("ankka-installation-models", ".json")
    try
      Files.writeString(file, json): Unit
      ankka("services", "apply", "-f", file.toString, "-p", Project)
    finally Files.deleteIfExists(file): Unit

  private def applyPending(): Unit =
    val json = pending.getOrElse(fail("no descriptor"))
    ok(applyJson(json)): Unit

  private def awaitReady(name: String): Unit =
    waitFor(360.seconds, s"$name being Ready") {
      statusOf(name).exists(s => s.lifecycle == ServiceLifecycle.Ready && s.confirmed) &&
      pods(name).exists(ServicePods.running)
    }

  private def declare(models: Vector[String]): Unit =
    declared = models
    ModelServerStack.redeclare(k3s, models)

  private def awaitServed(model: String, within: FiniteDuration = 900.seconds): Unit =
    waitFor(within, s"$model being served") {
      line(model).exists(_.state == "served")
    }

  private def lifecycleIs(logical: String, lifecycle: String): Unit =
    val name = real(logical)
    waitFor(120.seconds, s"$name being $lifecycle") {
      statusOf(name).exists(_.lifecycle.toString == lifecycle)
    }

  private def detailOf(logical: String): String =
    statusOf(real(logical)).flatMap(_.detail).getOrElse("")

  // ── the installation and its model server ──────────────────────────────────

  Given("an installation with a model server") { () =>
    assert(operatorSettings.models.isDefined, "the suite's operator was given no model server")
  }

  Given("an installation with no model server") { () =>
    startOperator(operatorSettings.copy(models = None))
    reader = InstallationModelsReader.none
    operatorChanged = true
  }

  Given("an installation whose model server declares the models {string} and {string}") {
    (a: String, b: String) => declare(Vector(a, b))
  }

  Given("an installation whose model server declares the model {string}") { (model: String) =>
    declare(Vector(model))
  }

  Given("an installation whose model server serves the model {string}") { (model: String) =>
    declare(Vector(model))
    awaitServed(model)
  }

  Given("an installation whose model server serves the models {string} and {string}") {
    (a: String, b: String) =>
      declare(Vector(a, b))
      awaitServed(a)
      awaitServed(b)
  }

  Given("an installation whose model server declares the model {string} and is still fetching it") {
    (model: String) =>
      // Removed first, so the fetch this scenario watches is a fetch, not a model already held.
      declare(Vector(Small))
      waitFor(120.seconds, s"$model leaving the status")(line(model).isEmpty)
      declare(Vector(Small, model))
      // The kubelet brings a ConfigMap's change to the mounted file within its sync period, a
      // minute or more, before the keeper can see it.
      waitFor(180.seconds, s"$model being fetched")(line(model).exists(_.state == "fetching"))
  }

  When("a platform administrator adds the model {string} to the model server's declaration") {
    (model: String) => declare(declared :+ model)
  }

  When("a platform administrator removes the model {string} from the model server's declaration") {
    (model: String) => declare(declared.filterNot(_ == model))
  }

  When("the model server restarts") { () =>
    digestBefore = line(Small).flatMap(_.digest)
    ModelServerStack.restart(k3s)
  }

  When("the status of the installation is read") { () => status = Some(installation()) }

  When("a developer reads the status of the installation") { () =>
    status = Some(installation())
  }

  // ── the status of the installation ─────────────────────────────────────────

  Then("the status of the installation says that {string} and {string} are served models") {
    (a: String, b: String) =>
      awaitServed(a)
      awaitServed(b)
  }

  Then("the status of the installation says that {string} is a served model") { (model: String) =>
    awaitServed(model)
  }

  Then("the status of the installation says that {string} failed, and why") { (model: String) =>
    waitFor(300.seconds, s"$model being reported failed") {
      line(model).exists(l => l.state == "failed" && l.detail.exists(_.nonEmpty))
    }
  }

  Then("the status of the installation names the model server of each cluster") { () =>
    val clusters =
      status.getOrElse(fail("the status was not read")).models.toVector.flatMap(_.clusters)
    assertEquals(clusters.map(_.cluster), Vector(InstallationModelsReader.Home), clusters.toString)
    assert(clusters.forall(_.server.nonEmpty), clusters.toString)
  }

  Then(
    "the status of the installation says, for each declared model, whether it is a served model, still being fetched, or failed and why"
  ) { () =>
    waitFor(120.seconds, "every declared model in the status") {
      declared.forall(m => line(m).isDefined)
    }
    val known = lines()
    for model <- declared do
      val l = known.find(_.name == model).getOrElse(fail(s"$model is not in the status: $known"))
      assert(Set("served", "fetching", "failed").contains(l.state), l.toString)
      if l.state == "failed" then assert(l.detail.exists(_.nonEmpty), l.toString)
  }

  Then("the status of the installation names no model server") { () =>
    assertEquals(installation().models, None)
  }

  Then("{string} becomes a served model")((model: String) => awaitServed(model))

  Then("{string} is not a served model") { (model: String) =>
    waitFor(300.seconds, s"$model no longer served")(!line(model).exists(_.state == "served"))
  }

  Then("{string} is a served model again without being fetched again") { (model: String) =>
    awaitServed(model, 600.seconds)
    assertEquals(line(model).flatMap(_.digest), digestBefore)
    val log = ModelServerStack.keeperLog(k3s)
    assert(!log.contains(s"fetching $model"), s"the restarted keeper fetched $model:\n$log")
  }

  // ── a service that names an installation model ─────────────────────────────

  Given("a descriptor for a service {string}") { (logical: String) =>
    pending = Some(descriptor(real(logical), Vector.empty))
    deployed = deployed :+ real(logical)
  }

  Given(
    "a descriptor for a service {string} that names a model provider with the preset {string} and the model {string}"
  ) { (logical: String, preset: String, model: String) =>
    pending = Some(descriptor(real(logical), naming(preset, model)))
    deployed = deployed :+ real(logical)
  }

  Given(
    "a descriptor for a service {string} that names a model provider with the preset {string}, the model {string} and an address"
  ) { (logical: String, preset: String, model: String) =>
    val address = literal("ANKKA_MODEL_LOCAL_URL", "http://example.test/v1")
    pending = Some(descriptor(real(logical), naming(preset, model, Vector(address))))
    deployed = deployed :+ real(logical)
  }

  Given(
    "a descriptor for a service {string} that names a model provider with the preset {string}, the model {string} and a key"
  ) { (logical: String, preset: String, model: String) =>
    val key = literal("ANKKA_MODEL_LOCAL_KEY", "not-a-key")
    pending = Some(descriptor(real(logical), naming(preset, model, Vector(key))))
    deployed = deployed :+ real(logical)
  }

  Given("a deployed service {string} on the installation model {string}") {
    (logical: String, model: String) =>
      val name = real(logical)
      ok(applyJson(descriptor(name, naming("installation", model))))
      deployed = deployed :+ name
      awaitReady(name)
      podsBefore = pods(name).map(_.getMetadata.getName).toSet
  }

  When("a member applies the descriptor")(() => applyPending())

  When(
    "a member applies a descriptor for a service {string} that names a model provider with the preset {string} and the model {string}"
  ) { (logical: String, preset: String, model: String) =>
    pending = Some(descriptor(real(logical), naming(preset, model)))
    deployed = deployed :+ real(logical)
    applyPending()
  }

  Then("the lifecycle of {string} is {string}") { (logical: String, lifecycle: String) =>
    lifecycleIs(logical, lifecycle)
  }

  Then("the lifecycle of {string} is {string}, because {string} is still being fetched") {
    (logical: String, lifecycle: String, model: String) =>
      // Both in one wait: an apply reads UpdateInProgress at once, before the operator has said why.
      val name = real(logical)
      val why  = s"installation model \"$model\" is still being fetched"
      var seen = Option.empty[ServiceStatus]
      try
        waitFor(120.seconds, s"$name being $lifecycle because $model is being fetched") {
          seen = statusOf(name)
          seen.exists(s => s.lifecycle.toString == lifecycle && s.detail.exists(_.contains(why)))
        }
      catch
        case e: munit.FailException =>
          fail(s"${e.getMessage}; last seen ${seen
              .map(s => s"${s.lifecycle}: ${s.detail.getOrElse("")}")}")
  }

  Then("when {string} becomes a served model, {string} is ready") {
    (model: String, logical: String) =>
      awaitServed(model)
      awaitReady(real(logical))
  }

  Then(
    "the detail of {string} says that the installation model {string} is not served, and names {string}"
  ) { (logical: String, model: String, served: String) =>
    waitFor(60.seconds, "the detail naming the served models") {
      val detail = detailOf(logical)
      detail.contains(s"installation model \"$model\" is not served") && detail.contains(served)
    }
  }

  Then("the detail of {string} says that an installation model takes no {word}") {
    (logical: String, what: String) =>
      waitFor(60.seconds, s"the detail refusing the $what") {
        detailOf(logical).contains(s"an installation model takes no $what")
      }
  }

  Then("{string} is deployed as it was before the installation could run a model server") {
    (logical: String) =>
      val name = real(logical)
      awaitReady(name)
      val env = servicePods.environment(Project, name)
      assert(!env.contains("ANKKA_MODELS_ADDRESS"), env.keys.toVector.sorted.mkString(", "))
  }

  Then("no instance of {string} restarted") { (logical: String) =>
    val now = pods(real(logical))
    assertEquals(now.map(_.getMetadata.getName).toSet, podsBefore)
    for pod <- now; status <- pod.getStatus.getContainerStatuses.asScala do
      assertEquals(status.getRestartCount.intValue, 0, pod.getMetadata.getName)
  }

  // ── who the door admits ───────────────────────────────────────────────────

  When(
    "a workload that holds no service certificate of the installation connects to the model server"
  ) { () =>
    // In a project's namespace and labelled as a platform workload, so the network policy admits it
    // and what refuses it is the door; `-k` so the refusal is the door's, not curl's own distrust.
    // The namespace first: these scenarios run before any service has made it.
    val namespace = s"$Prefix-$Project"
    val manifest =
      s"""apiVersion: v1
         |kind: Namespace
         |metadata:
         |  name: $namespace
         |  labels: { app.kubernetes.io/managed-by: ankka }
         |---
         |apiVersion: v1
         |kind: Pod
         |metadata:
         |  name: uncertified
         |  namespace: $namespace
         |  labels: { app.kubernetes.io/managed-by: ankka }
         |spec:
         |  containers:
         |    - name: curl
         |      image: curlimages/curl:8.11.1
         |      command: ["sleep", "infinity"]
         |""".stripMargin
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      "/tmp/uncertified.yaml"
    )
    PkiStack.kubectl(k3s, "apply", "-f", "/tmp/uncertified.yaml"): Unit
    PkiStack.kubectl(
      k3s,
      "wait",
      "-n",
      namespace,
      "--for=condition=Ready",
      "pod/uncertified",
      "--timeout=180s"
    ): Unit
    val r = k3s.execInContainer(
      "kubectl",
      "exec",
      "-n",
      namespace,
      "uncertified",
      "--",
      "curl",
      "-sS",
      "-k",
      "-o",
      "/dev/null",
      "-w",
      "%{http_code}",
      "--max-time",
      "10",
      s"${ModelServerStack.InClusterAddress}/ankka/models"
    )
    workloadAnswer = (r.getStdout.trim.toIntOption.getOrElse(0), r.getStderr)
  }

  Then("the workload is refused before anything is sent") { () =>
    val (code, err) = workloadAnswer
    // No HTTP answer at all: curl prints 000 and says why. A 403 would mean the door read a request.
    assertEquals(code, 0, err)
    assert(err.toLowerCase.contains("ssl") || err.toLowerCase.contains("alert"), err)
  }

  // ── an agent on an installation model ─────────────────────────────────────

  /** What the stock helper's callers were answered: the status and the body. */
  private var answers: Vector[Future[(Int, String)]] = Vector.empty
  private var completionsBefore: Int                 = 0
  private var modelTimeout: Option[Int]              = None
  private var helperService: String                  = ""

  private val asking: ExecutionContext =
    ExecutionContext.fromExecutor(java.util.concurrent.Executors.newCachedThreadPool())

  /**
   * The shopping cart with its stock helper on the installation model `model`: the descriptor names
   * a provider `local` with the preset, as any service does, and the sample registers the agent
   * when it reads one.
   */
  private def deployHelper(logical: String, model: String): Unit =
    if !declared.contains(model) then declare(declared :+ model)
    awaitServed(model)
    val name    = real(logical)
    val timeout = modelTimeout.toVector.map(s => literal("CART_MODEL_TIMEOUT", s.toString))
    ok(applyJson(descriptor(name, naming("installation", model) ++ timeout)))
    deployed = deployed :+ name
    awaitReady(name)
    InPod.prober(k3s, s"$Prefix-$Project", name): Unit
    helperService = name
    podsBefore = pods(name).map(_.getMetadata.getName).toSet

  /** A question to the stock helper, from the prober holding the service's own certificate. */
  private def ask(question: String): Future[(Int, String)] =
    val name = helperService
    val q    = URLEncoder.encode(question, StandardCharsets.UTF_8)
    Future(
      blocking(
        InPod.curl(
          k3s,
          s"$Prefix-$Project",
          "prober",
          s"https://$name.$Prefix-$Project.svc:9000/helper/ask?question=$q",
          verifyHost = false,
          maxSeconds = 900
        )
      )
    )(using asking)

  private def answered(i: Int = 0): (Int, String) =
    Await.result(answers.lift(i).getOrElse(fail(s"no message $i was sent")), 16.minutes)

  private val ToolQuestion = "Are apples in stock? Use the lookup tool."
  private val LongQuestion =
    "Write a story of at least two thousand words about a lighthouse keeper, chapter by chapter."

  Given(
    "a deployed service {string} with an agent {string} that names the model provider {string}, an installation model"
  ) { (logical: String, agent: String, provider: String) =>
    assertEquals((agent, provider), ("helper", "local"), "the sample's agent and provider")
    deployHelper(logical, Small)
  }

  Given("a deployed service {string} with an agent {string} on the installation model {string}") {
    (logical: String, agent: String, model: String) =>
      assertEquals(agent, "helper", "the sample's agent")
      deployHelper(logical, model)
  }

  Given("a deployed service {string} with an agent {string} on an installation model") {
    (logical: String, agent: String) =>
      assertEquals(agent, "helper", "the sample's agent")
      deployHelper(logical, Small)
  }

  Given("a tool {string} of {string}") { (tool: String, agent: String) =>
    assertEquals((tool, agent), ("lookup", "helper"), "the sample's tool")
  }

  Given(
    "an installation whose model server serves the model {string}, which has the capability {string}"
  ) { (model: String, _: String) =>
    declare(Vector(model))
    awaitServed(model)
  }

  When("a caller sends a message to {string} that makes the model call {string}") {
    (_: String, _: String) =>
      completionsBefore = ModelServerStack.completionsAnswered(k3s)
      answers = Vector(ask(ToolQuestion))
  }

  Then("the tool {string} runs") { (_: String) =>
    val (code, body) = answered()
    assertEquals(code, 200, body)
    val runs =
      body.linesIterator.nextOption().map(_.stripPrefix("lookups=")).flatMap(_.toIntOption)
    assert(runs.exists(_ > 0), s"the tool did not run: $body")
  }

  Then("the caller is answered") { () =>
    val (code, body) = answered()
    assertEquals(code, 200, body)
    assert(body.linesIterator.drop(1).mkString.trim.nonEmpty, s"an empty answer: $body")
  }

  Then(
    "every call the turn made was answered by the model server of the cluster, and none by anything outside the installation"
  ) { () =>
    // A turn with a tool round is two model calls at least, and the service names no address of
    // its own: the platform gave it the model server's, and the model server answered both.
    val env = servicePods.environment(Project, helperService)
    assert(!env.contains("ANKKA_MODEL_LOCAL_URL"), env.keys.toVector.sorted.mkString(", "))
    assertEquals(env.get("ANKKA_MODELS_ADDRESS"), Some(ModelServerStack.InClusterAddress))
    val made = ModelServerStack.completionsAnswered(k3s) - completionsBefore
    assert(made >= 2, s"the model server answered $made completions for a turn with a tool round")
  }

  // ── a call in flight, calls at once, a call past the timeout ──────────────

  Given("a message to {string} the model server is answering") { (_: String) =>
    answers = Vector(ask(LongQuestion))
    // Long enough for the request to reach the model server and the model to start answering.
    Thread.sleep(5000)
    assert(!answers.head.isCompleted, s"answered before the restart: ${answers.head.value}")
  }

  Then("the caller of {string} is told the model provider is unavailable") { (_: String) =>
    val (code, body) = answered()
    assertEquals(code, 503, body)
    assert(body.contains("local"), s"the failure does not name the provider: $body")
  }

  Then("{string} is unaffected") { (logical: String) =>
    awaitReady(real(logical))
    val now = pods(real(logical))
    assertEquals(now.map(_.getMetadata.getName).toSet, podsBefore)
    for pod <- now; status <- pod.getStatus.getContainerStatuses.asScala do
      assertEquals(status.getRestartCount.intValue, 0, pod.getMetadata.getName)
  }

  Given("an installation whose model server runs one call at once") { () =>
    assertEquals(ModelServerStack.parallel(k3s), "1")
    awaitServed(Small)
  }

  Given("two agents on the installation model {string}") { (model: String) =>
    deployHelper("support", model)
  }

  When("each agent is sent a message at the same moment") { () =>
    answers = Vector(ask(ToolQuestion), ask(ToolQuestion))
  }

  Then("both messages are answered") { () =>
    for i <- answers.indices do
      val (code, body) = answered(i)
      assertEquals(code, 200, s"message $i: $body")
  }

  Then("neither caller is told the model provider is unavailable before the model timeout") { () =>
    for i <- answers.indices do
      val (code, body) = answered(i)
      assert(code != 503 && code != 504, s"message $i: $code $body")
  }

  Given("a model timeout of {string}") { (timeout: String) =>
    modelTimeout = Some(timeout.stripSuffix(" seconds").trim.toInt)
  }

  Given(
    "an agent on the installation model {string} whose message the model server is answering slowly"
  ) { (model: String) =>
    deployHelper("support", model)
    answers = Vector(ask(LongQuestion))
    Thread.sleep(2000)
  }

  When("a second agent on the same installation model is sent a message") { () =>
    answers = answers :+ ask(ToolQuestion)
  }

  Then("the second caller is told the model provider timed out after {string}") {
    (timeout: String) =>
      val (code, body) = answered(1)
      assertEquals(code, 504, body)
      assert(body.contains(s"within $timeout"), body)
  }
