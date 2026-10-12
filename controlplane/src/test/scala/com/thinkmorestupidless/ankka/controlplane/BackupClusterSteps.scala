package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.{
  InstallationStatus,
  ProjectStatus,
  ServiceLifecycle,
  ServiceStatus
}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  DivergenceReader,
  Fabric8AnkkaServiceClient,
  PodLogs,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  BackupSettings,
  BackupStack,
  BackupTarget,
  ClusterImages,
  GarageStore,
  InPod,
  ObjectStoreStack,
  Operator,
  PkiStack,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.{Gauges, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * What every feature 041 suite on k3s shares: the control plane and the operator in this JVM,
 * CloudNativePG and the Barman Cloud plugin at their pinned releases, the installation's Garage
 * from its own component, and the shopping cart sample as every service's image.
 *
 * Each scenario has a project of its own (`shop` is `shop-<n>`), since a project database and its
 * backup bucket outlive every service and the platform never removes either.
 *
 * What a step asserts is the thing the scenario names, never a word in a status: a failure is the
 * status *changing* to failing after the key is refused; archiving is the archive's lag measured
 * under writes; "no instance replaced" is the pods' identities before and after.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the sample's image.
 */
abstract class BackupClusterSteps(feature: String) extends GherkinSuite(feature) with LogCapturing:

  override val munitTimeout: FiniteDuration = 25.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  protected val K3sImage    = "rancher/k3s:v1.35.1-k3s1"
  protected val Tag         = com.thinkmorestupidless.ankka.core.BuildInfo.version.replace('+', '-')
  protected val SampleImage = s"sample-shopping-cart:$Tag"
  protected val Prefix      = "ankka"

  protected lazy val identity = TestIdentity()
  protected lazy val Token = identity.token(
    "tester",
    Some("tester@example.test"),
    roles = Set("platform-admin"),
    expiresIn = 3.hours
  )

  protected var k3s: K3sContainer                 = null
  protected var k8s: KubernetesClient             = null
  protected var operator: Operator                = null
  protected var store: ObjectStoreStack.Installed = null
  protected var admin: GarageStore                = null
  protected var testKit: AnkkaTestKit             = null
  protected var url: String                       = ""

  /** The control plane's projector, so a failure can ask it directly what the endpoint was told. */
  protected var projectorOf: Option[ServiceProjector]                    = None
  protected var config: Path                                             = null
  protected var s3Forward: io.fabric8.kubernetes.client.LocalPortForward = null

  /** What the installation backs up, as both processes are told; switched by a scenario's Given. */
  @volatile private var backingUp: Boolean = true
  protected def controlPlaneBackups: BackupConfig =
    if backingUp then BackupConfig("object-store", 30, copyRequired = false)
    else BackupConfig.default

  /** The installation's object store: Garage on one node, as a local platform has it. */
  protected def installObjectStore(): ObjectStoreStack.Installed =
    ObjectStoreStack.install(k3s, k8s, repoRoot)

  /**
   * How the control plane asks a service what a restore cannot take back; none by default, so a
   * restore lists every service as not asked. Called once, after `installMore`.
   */
  protected def divergence: Option[DivergenceReader] = None

  /** The installation's broker, for a suite that installs one in `installMore`. */
  protected var broker: Option[com.thinkmorestupidless.ankka.operator.BrokerSettings] = None

  /** What a suite installs beyond the backups' stack, before the operator starts. */
  protected def installMore(): Unit = ()

  protected def operatorSettings: OperatorSettings =
    OperatorSettings.default.copy(
      broker = broker,
      resyncInterval = 5.seconds,
      objectStore = Some(store.settings),
      backups =
        if backingUp then BackupSettings(target = Some(BackupTarget.ObjectStore))
        else BackupSettings.none
    )

  protected def repoRoot: Path =
    var dir = Paths.get("").toAbsolutePath
    while !Files.exists(dir.resolve("build.sbt")) do dir = dir.getParent
    dir

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()
      ClusterImages.importInto(k3s, SampleImage)
      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      for crd <- Vector("/ankka/crd/ankkaservice.yaml", "/ankka/crd/ankkaproject.yaml") do
        k8s.load(getClass.getResourceAsStream(crd)).serverSideApply(): Unit
      k8s
        .load(getClass.getResourceAsStream("/ankka/install/operator.yaml"))
        .items()
        .asScala
        .filter(o =>
          Set("Namespace", "ServiceAccount", "ClusterRole", "ClusterRoleBinding")
            .contains(o.getKind)
        )
        .foreach(o => k8s.resource(o).serverSideApply(): Unit)
      PkiStack.install(k3s, k8s)
      BackupStack.install(k3s, k8s)
      store = installObjectStore()
      admin = GarageStore(store.settings.adminUrl, store.settings.adminToken)
      s3Forward = k8s.services().inNamespace("garage-system").withName("garage").portForward(3900)
      installMore()
      startOperator()

      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        progressDeadline = 300.seconds
      )
      val projector = ServiceProjector.withClient(
        deployConfig,
        new Fabric8AnkkaServiceClient(k8s, Prefix, resyncMillis = 2000L),
        controlPlaneBackups
      )
      projectorOf = Some(projector)
      val server = HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(
          identity.acl(),
          deployConfig,
          auth = Some(identity.config()),
          logs = Some(new PodLogs(k8s, Prefix)),
          topics = Some(projector),
          backups = controlPlaneBackups,
          platform = Some(projector),
          rehearsals = Some(projector),
          divergence = divergence
        )*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
      config = Files.createTempFile("ankka-backups", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString
      ok(ankka("organizations", "create", "acme", "--name", "Acme")): Unit

  override def afterAll(): Unit =
    writing.set(false)
    if testKit != null then identity.stop()
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if s3Forward != null then s3Forward.close()
    if store != null then store.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  protected def startOperator(): Unit =
    if operator != null then operator.close()
    val settings = operatorSettings
    operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
    operator.start()

  /** The installation backs up, or does not, from now on: both processes are told. */
  protected def installationBacksUp(yes: Boolean): Unit =
    if backingUp != yes || operator == null then
      backingUp = yes
      startOperator()

  // ── the scenario's names ──────────────────────────────────────────────────

  protected var scenario: Int                    = 0
  protected var podsBefore: Set[String]          = Set.empty
  protected var failingSince: Long               = 0L
  protected var refusals: Vector[(String, Int)]  = Vector.empty
  protected var installation: InstallationStatus = null
  protected val writing                          = new AtomicBoolean(false)

  /** This scenario's name for a project or a service a feature calls `logical`. */
  protected def real(logical: String): String = s"$logical-$scenario"

  /**
   * Whether a scenario of this suite has failed. Each waits minutes for a cluster before it gives
   * up, so a suite that ran on past its first failure ran out the workflow's clock before it said
   * why; it stops there instead, and reports the rest as skipped. `-Dankka.backups.keep-going=on`
   * runs every scenario regardless.
   */
  @volatile private var failed: Option[String] = None
  private val keepGoing = sys.props.get("ankka.backups.keep-going").contains("on")

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+ new TestTransform(
      "stop at the first failure",
      test =>
        // withBody, not withBodyMap: withBodyMap runs the body before handing over its result.
        test.withBody(() =>
          failed match
            case Some(first) if !keepGoing =>
              scala.concurrent.Future.failed(
                new org.junit.AssumptionViolatedException(s"skipped: '$first' failed first")
              )
            case _ =>
              test
                .body()
                .transform { result =>
                  result match
                    case scala.util.Failure(_: org.junit.AssumptionViolatedException) => ()
                    case scala.util.Failure(_) => failed = failed.orElse(Some(test.name))
                    case _                     => ()
                  result
                }(using munitExecutionContext)
        )
    )

  override def beforeEach(context: BeforeEach): Unit =
    if !munitIgnore && (failed.isEmpty || keepGoing) then
      writing.set(false)
      scenario += 1
      podsBefore = Set.empty
      failingSince = 0L
      refusals = Vector.empty
      installation = null

  // ── the CLI, the cluster and the store ─────────────────────────────────────

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
    var last     = Option.empty[Throwable]
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch
          case e: Throwable =>
            last = Some(e)
            false
      if !passed then Thread.sleep(2000)
    if !passed then
      fail(
        s"$what did not happen within $timeout" +
          last.fold("")(e => s"\nthe check last threw: $e") +
          s"\n$clusterState"
      )

  /**
   * Every project database and its archive as the cluster has them, for a wait that timed out: a
   * scenario waits minutes, and its log says nothing about where CNPG or the archiver stopped.
   */
  protected def clusterState: String =
    def run(args: String*) =
      scala.util
        .Try {
          val r = k3s.execInContainer(args*)
          r.getStdout + r.getStderr
        }
        .getOrElse("")
    run(
      "kubectl",
      "get",
      "cluster,scheduledbackup,backup,objectstore,certificate,pod",
      "-A",
      "-o",
      "wide"
    ).linesIterator
      .filter(l => l.startsWith("NAMESPACE") || l.contains("ankka") || l.contains("garage"))
      .mkString("\n") + "\n" +
      // The last lines of every pod that failed, a recovery's above all: why it stopped is only there.
      run(
        "sh",
        "-c",
        "kubectl get pods -A --no-headers | awk '$4 ~ /Error|CrashLoopBackOff/ { print $1, $2 }' | " +
          "head -3 | while read ns pod; do echo \"== $ns/$pod\"; " +
          // CNPG logs JSON, PostgreSQL's own words deep in a record: the messages, not the lines.
          "kubectl logs -n $ns $pod --all-containers --tail 60 2>&1 | " +
          "grep -oE '\"(message|msg|error|detail|hint)\":\"[^\"]*\"' | tail -30; done"
      ) + "\n" +
      // And the events of every pod that never started: a mount, a schedule or an init container.
      run(
        "sh",
        "-c",
        "kubectl get pods -A --no-headers | awk '$4 ~ /Pending|Init|ContainerCreating/ { print $1, $2 }' | " +
          "head -3 | while read ns pod; do echo \"== $ns/$pod\"; " +
          "kubectl describe pod -n $ns $pod | sed -n '/^Init Containers:/,/^Containers:/p' | " +
          "grep -E 'State|Reason|Exit Code|Started' ; " +
          "kubectl describe pod -n $ns $pod | sed -n '/^Events:/,$p' | tail -12; " +
          "kubectl logs -n $ns $pod -c schema-init --tail 15 2>&1; done"
      ) + "\n" +
      run(
        "kubectl",
        "get",
        "cluster",
        "-A",
        "-o",
        "jsonpath={range .items[*]}{.metadata.namespace}/{.metadata.name} {.status.phase} " +
          "({.status.phaseReason}): " +
          "{range .status.conditions[*]}{.type}={.status} ({.message}) {end}{\"\\n\"}{end}"
      )

  protected def namespace(project: String) = s"$Prefix-$project"

  protected def ensureProject(project: String): Unit =
    val run = ankka("projects", "get", project)
    if run.code != 0 then
      ok(ankka("projects", "create", project, "--name", project, "--organization", "acme")): Unit

  protected def statusOf(name: String, project: String): Option[ServiceStatus] =
    val run = ankka("services", "get", name, "-p", project, "-o", "json")
    Option.when(run.code == 0)(readFromString[ServiceStatus](run.out))

  protected def projectStatus(project: String): ProjectStatus =
    readFromString[ProjectStatus](ok(ankka("projects", "status", project, "-o", "json")).out)

  protected def installationStatus(): InstallationStatus =
    readFromString[InstallationStatus](ok(ankka("status", "-o", "json")).out)

  protected def pods(project: String, name: String): Vector[Pod] =
    k8s
      .pods()
      .inNamespace(namespace(project))
      .withLabel("app.kubernetes.io/name", name)
      .list()
      .getItems
      .asScala
      .toVector
      .filter(_.getMetadata.getDeletionTimestamp == null)

  protected def descriptor(name: String, env: Vector[String] = Vector.empty, http: Boolean = true) =
    val fields = Vector(
      Some(s""""image":"$SampleImage""""),
      Option.when(!http)(""""http":false"""),
      Option.when(env.nonEmpty)(env.mkString(""""env":[""", ",", "]"))
    ).flatten
    s"""{"name":"$name","service":{${fields.mkString(",")}}}"""

  protected def applyJson(project: String, json: String): Run =
    val file = Files.createTempFile("ankka-backups", ".json")
    try
      Files.writeString(file, json): Unit
      ankka("services", "apply", "-f", file.toString, "-p", project)
    finally Files.deleteIfExists(file): Unit

  /** What this scenario deployed, for a suite that removes it when the scenario ends. */
  private val deployed = java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()

  /** And the projects whose namespaces it made with no service in them. */
  protected val deployedProjects = scala.collection.mutable.Set.empty[String]

  /**
   * Whether a scenario's services and project namespaces are removed when it ends. One k3s node
   * holds every scenario's project databases, and a suite whose scenarios each run three instances
   * left the fifth scenario's service unschedulable. The services go through the control plane,
   * which would otherwise project them back, and then the namespace, database and all.
   */
  protected def removesEachScenario: Boolean = false

  override def afterEach(context: AfterEach): Unit =
    try
      if removesEachScenario then
        deployed.asScala.toVector.foreach { (project, name) =>
          ankka("services", "delete", name, "-p", project): Unit
        }
        (deployed.asScala.map(_._1).toSet ++ deployedProjects).foreach { project =>
          for ns <- Seq(namespace(project), s"${namespace(project)}-rehearsal") do
            scala.util.Try(k8s.namespaces().withName(ns).delete()): Unit
        }
    finally
      deployed.clear()
      deployedProjects.clear()
      super.afterEach(context)

  protected def deploy(project: String, name: String, json: String): Unit =
    ensureProject(project)
    deployed.add(project -> name): Unit
    ok(applyJson(project, json)): Unit
    try
      waitFor(400.seconds, s"$project/$name being Ready") {
        statusOf(name, project).exists(s => s.lifecycle == ServiceLifecycle.Ready && s.confirmed)
      }
    catch
      case e: munit.FailException =>
        val pods = k3s.execInContainer(
          "kubectl",
          "get",
          "pods,cluster",
          "-n",
          namespace(project),
          "-o",
          "wide"
        )
        fail(s"${e.getMessage}: ${statusOf(name, project)}\n${pods.getStdout}${pods.getStderr}")

  /** A cart's HTTP API, from inside its own pod with its own certificate. */
  protected def cart(
      project: String,
      service: String,
      method: String,
      path: String,
      body: Option[String] = None
  ) =
    val pod = pods(project, service).headOption.getOrElse(fail(s"$service has no pod"))
    InPod.curl(
      k3s,
      namespace(project),
      pod.getMetadata.getName,
      s"https://$service.${namespace(project)}.svc.cluster.local:9000$path",
      method = method,
      body = body,
      container = Some(service)
    )

  protected def addItem(project: String, service: String, cartId: String, product: String): Unit =
    val (code, body) = cart(
      project,
      service,
      "POST",
      s"/carts/$cartId/items",
      Some(s"""{"productId":"$product","name":"$product","quantity":1}""")
    )
    // The cart answers an added item with no body.
    assert(Set(200, 204)(code), s"adding $product to $cartId answered $code: $body")

  /** `psql` as postgres in a project database's primary, as the operator reads it. */
  protected def psql(project: String, sql: String, database: String = "postgres"): String =
    val pod = k8s
      .pods()
      .inNamespace(namespace(project))
      .withLabel("cnpg.io/cluster", "ankka-db")
      .withLabel("cnpg.io/instanceRole", "primary")
      .list()
      .getItems
      .asScala
      .headOption
      .getOrElse(fail(s"$project's database has no primary"))
    val out = new ByteArrayOutputStream
    val err = new ByteArrayOutputStream
    val watch = k8s
      .pods()
      .inNamespace(namespace(project))
      .withName(pod.getMetadata.getName)
      .inContainer("postgres")
      .writingOutput(out)
      .writingError(err)
      .exec("psql", "-U", "postgres", "-d", database, "-tA", "-v", "ON_ERROR_STOP=1", "-c", sql)
    try
      val code = watch.exitCode().get(60, TimeUnit.SECONDS)
      if code != 0 then fail(s"psql exited $code: ${err.toString(StandardCharsets.UTF_8)}")
      out.toString(StandardCharsets.UTF_8)
    finally watch.close()

  /** Writes to a project database until stopped, a segment archived at least every few seconds. */
  protected def keepWriting(project: String): Unit =
    writing.set(true)
    val thread = new Thread(() =>
      while writing.get() do
        try
          psql(
            project,
            "create table if not exists backups_suite(x int); insert into backups_suite values (1); " +
              "select pg_switch_wal()"
          ): Unit
        catch case _: Throwable => ()
        Thread.sleep(5000)
    )
    thread.setDaemon(true)
    thread.start()

  protected def line(project: String) = projectStatus(project).lines.headOption

  protected def awaitBackedUp(project: String): Unit =
    waitFor(10.minutes, s"$project being backed up") {
      line(project).exists(l => l.phase == "backing up" && l.lastBaseBackup.isDefined)
    }

  /** Asks the store's administration API to refuse or allow a key on a bucket. */
  protected def permit(bucket: String, allowed: Boolean): Unit =
    val info = admin.bucket(bucket).getOrElse(fail(s"no bucket $bucket"))
    val keys = admin.keysNamed(bucket)
    assert(keys.nonEmpty, s"no key named $bucket")
    val http = HttpClient.newHttpClient()
    for key <- keys do
      val body =
        s"""{"bucketId":"${info.id}","accessKeyId":"$key","permissions":{"read":true,"write":true,"owner":false}}"""
      val response = http.send(
        HttpRequest
          .newBuilder(
            URI.create(
              s"${store.settings.adminUrl}/v2/${if allowed then "Allow" else "Deny"}BucketKey"
            )
          )
          .header("Authorization", s"Bearer ${store.settings.adminToken}")
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assertEquals(response.statusCode(), 200, response.body())

  protected def gauge(name: String, project: String): Option[Double] =
    Gauges.global.snapshot(name).collectFirst {
      case (attributes, value) if attributes.get("ankka.project").contains(project) => value
    }

  /** The control plane's own database, as the local overlay renders it, applied once. */
  protected lazy val controlPlaneDatabase: Unit =
    val rendered =
      Process("kubectl", "kustomize", repoRoot.resolve("kustomization/overlays/local").toString)
    val wanted = rendered
      .split("\n---\n")
      .filter(_.contains("namespace: ankka-controlplane"))
      .filter(d =>
        Vector("kind: ConfigMap", "kind: Cluster", "kind: ObjectStore", "kind: ScheduledBackup")
          .exists(k => d.linesIterator.contains(k)) &&
          (d.contains("ankka-controlplane-schema") || !d.contains("kind: ConfigMap"))
      )
    assert(
      wanted.exists(_.contains("kind: Cluster")),
      "the overlay renders no control plane database"
    )
    val manifest = (Vector(
      """apiVersion: v1
        |kind: Namespace
        |metadata: { name: ankka-controlplane }""".stripMargin
    ) ++ wanted).mkString("\n---\n")
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      "/tmp/controlplane-db.yaml"
    )
    PkiStack.kubectl(
      k3s,
      "apply",
      "--server-side",
      "--force-conflicts",
      "-f",
      "/tmp/controlplane-db.yaml"
    ): Unit

  protected def Process(command: String*): String =
    val p   = new ProcessBuilder(command*).redirectErrorStream(true).start()
    val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    assertEquals(p.waitFor(), 0, out.take(2000))
    out

  /** A project's name in this suite: by default one per scenario, since a database outlives all. */
  protected def projectOf(logical: String): String = real(logical)

  /** A service's name in this suite: by default one per scenario. */
  protected def serviceOf(logical: String): String = real(logical)

  /** Whether a service of this name is deployed and ready in the project already. */
  protected def isReady(project: String, service: String): Boolean =
    statusOf(service, project).exists(s => s.lifecycle == ServiceLifecycle.Ready && s.confirmed)

  // ── Steps every backup feature uses ───────────────────────────────────────

  Given("an installation with a backup target")(() => installationBacksUp(true))

  Given("an installation with no backup target")(() => installationBacksUp(false))

  Given("a deployed service {string} in the project {string} with a provisioned database") {
    (service: String, project: String) =>
      val (p, s) = (projectOf(project), serviceOf(service))
      if !isReady(p, s) then deploy(p, s, descriptor(s))
  }

  Given("a project {string} that is backed up") { (project: String) =>
    val name = projectOf(project)
    ensureProject(name)
    if ok(ankka("services", "list", "-p", name, "-o", "json")).out.trim == "[]" then
      deploy(name, serviceOf("base"), descriptor(serviceOf("base")))
    awaitBackedUp(name)
  }
