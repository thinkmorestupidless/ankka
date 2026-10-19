package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.{ServiceLifecycle, ServiceStatus}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  Fabric8AnkkaServiceClient,
  PodLogs,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.crd.{AnkkaSerialization, Buckets}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  GarageStore,
  GatewayStack,
  InPod,
  ObjectStoreSettings,
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
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, ListObjectsV2Request}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * `features/storage-client/` on k3s (feature 055): what the platform does with a descriptor's
 * buckets, and the storage client in a deployed service, against Garage on the local overlay with
 * its web listener, cert-manager and Envoy Gateway in front, the control plane and the operator in
 * this JVM as `ObjectStorageClusterFeatures` runs them.
 *
 * The service is the shopping cart sample, which every k3s suite deploys: its `/storage` route
 * keeps and reads objects, makes signed URLs and answers public addresses through the storage
 * client, and admits only the sample's own instances, so a handler is asked from inside its pod
 * with its own certificate. A browser, or a person on the internet, is `curl` on the host through
 * the gateway's mapped port with `--resolve` and the installation's root, never `--insecure`.
 *
 * The developer's own object store, for a supplied bucket, is a bucket and a key this suite makes
 * in the installation's Garage through its admin API, with no help from the platform: what the
 * scenario asks is that the platform makes nothing for it, which the admin API answers either way.
 *
 * A span in minutes is a span in seconds here, a hundredth of it: "5 minutes" is three seconds, so
 * "a signed URL expires" waits seconds, not minutes, and still crosses the expiry it asks about.
 *
 * Every scenario has services of its own (`listings-<n>`), since the platform never deletes a
 * bucket. The other feature files of the directory are their own runners', named per file.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the sample's image.
 */
class StorageClientClusterFeatures
    extends GherkinSuite("../features/storage-client")
    with LogCapturing:

  override val munitTimeout: FiniteDuration = 15.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  /** The two files this suite runs; every other file of the directory is its own runner's. */
  private lazy val Here = Set("buckets.feature", "signed-urls.feature")

  private lazy val Runners = Map(
    "client.feature"    -> "StorageClientFeatures (testkit)",
    "contexts.feature"  -> "StorageContextsFeatures (testkit)",
    "testkit.feature"   -> "StorageTestKitFeatures (testkit)",
    "local.feature"     -> "StorageLocalFeatures (testkit)",
    "languages.feature" -> "ConformanceSuite (sidecar), against each language's reference"
  )

  /** The one scenario of another file that only a deployed service can answer. */
  private lazy val Deployed = "a deployed service serves no signed URL and no public address itself"

  override protected def ranElsewhere: Map[String, String] =
    otherFiles ++ Map(
      "a service reaches a named bucket through the storage client" ->
        "StorageBucketsFeatures (testkit): the client's own choice of bucket",
      "a bucket name the descriptor does not give is refused" -> "StorageBucketsFeatures (testkit)",
      "a signed URL or a public address is refused on a bucket whose exposure does not allow it" ->
        "StorageBucketsFeatures (testkit)",
      "the address of an object in a public bucket is answered without a request to the object store" ->
        "StorageBucketsFeatures (testkit), against a stand-in store that counts requests",
      "a service's code is the same whether or not a content delivery network stands in front of its public buckets" ->
        "StorageBucketsFeatures (testkit), with a stand-in content delivery network",
      "a service's buckets are moved together" -> "ObjectStorageGcsClusterFeatures (k3s)",
      "a signed URL is made without a request to the object store" ->
        "S3BucketSuite (runtime): signed with the store unreachable, and nothing sent",
      // languages.feature, one runner per scenario: the conformance suite runs each case against
      // the Scala reference in process, the Python and TypeScript references and the Rust module.
      "an object is kept and read through the storage client in every language" ->
        "ConformanceSuite (sidecar): storage.round-trip, against each language's reference",
      "the storage client lists, tells what an object is and deletes in every language" ->
        "ConformanceSuite (sidecar): storage.list, storage.stat and storage.delete-twice",
      "no object passes through the platform's own program" ->
        "ConformanceSuite (sidecar): storage.large, an object above the storage import's bound that the Python and TypeScript references keep and a module is refused",
      "every SDK refuses the same object names for the same reasons" ->
        "ConformanceSuite (sidecar): storage.rules, every row of the fixture",
      "the storage client in every language keeps its objects in a directory on a developer's machine" ->
        "StorageLocalFeatures (testkit) for Scala; each SDK's own directory store tests for Python, TypeScript and Rust"
    )

  private def otherFiles: Map[String, String] =
    val dir  = Paths.get("../features/storage-client")
    val ours = Here.flatMap(f => scenarioNames(dir.resolve(f)))
    Files
      .list(dir)
      .iterator
      .asScala
      .map(_.getFileName.toString)
      .filter(f => f.endsWith(".feature") && !Here(f))
      .toVector
      .flatMap { file =>
        val runner = Runners.getOrElse(
          file,
          throw AssertionError(s"$file has no runner named in StorageClientClusterFeatures")
        )
        scenarioNames(dir.resolve(file)).filterNot(_ == Deployed).map { name =>
          // A name shared with a scenario here would skip ours too: say so, rather than skip it.
          if ours(name) then
            throw AssertionError(s"'$name' is a scenario of $file and of this suite's")
          name -> runner
        }
      }
      .toMap

  private def scenarioNames(file: Path): Vector[String] =
    Files
      .readAllLines(file)
      .asScala
      .map(_.trim)
      .collect {
        case l if l.startsWith("Scenario: ")         => l.stripPrefix("Scenario: ")
        case l if l.startsWith("Scenario Outline: ") => l.stripPrefix("Scenario Outline: ")
      }
      .toVector

  private val K3sImage    = "rancher/k3s:v1.35.1-k3s1"
  private val Tag         = com.thinkmorestupidless.ankka.core.BuildInfo.version.replace('+', '-')
  private val SampleImage = s"sample-shopping-cart:$Tag"
  private val BaseDomain  = "store.example.test"
  private val Prefix      = "ankka"
  private val Project     = "shop"

  private lazy val identity = TestIdentity()
  private lazy val Token = identity.token(
    "tester",
    Some("tester@example.test"),
    roles = Set("platform-admin"),
    expiresIn = 3.hours
  )

  private var k3s: K3sContainer                                        = null
  private var k8s: KubernetesClient                                    = null
  private var operator: Operator                                       = null
  private var store: ObjectStoreStack.Installed                        = null
  private var admin: GarageStore                                       = null
  private var testKit: AnkkaTestKit                                    = null
  private var url: String                                              = ""
  private var config: Path                                             = null
  private var ca: Path                                                 = null
  private var httpsPort: Int                                           = 0
  private var s3Forward: io.fabric8.kubernetes.client.LocalPortForward = null

  private def repoRoot: Path =
    var dir = Paths.get("").toAbsolutePath
    while !Files.exists(dir.resolve("build.sbt")) do dir = dir.getParent
    dir

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort, GatewayStack.HttpNodePort)
      k3s.start()
      httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)
      ClusterImages.importInto(k3s, SampleImage)
      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      k8s.load(getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")).serverSideApply(): Unit
      k8s
        .load(getClass.getResourceAsStream("/ankka/install/operator.yaml"))
        .items()
        .asScala
        .filter(o =>
          Set("Namespace", "ServiceAccount", "ClusterRole", "ClusterRoleBinding")
            .contains(o.getKind)
        )
        .foreach(o => k8s.resource(o).serverSideApply(): Unit)
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
      GatewayStack.install(k3s, k8s, repoRoot, BaseDomain)
      ca = GatewayStack.exportCa(k8s)
      store = ObjectStoreStack.install(k3s, k8s, repoRoot)
      admin = GarageStore(store.settings.adminUrl, store.settings.adminToken)
      s3Forward = k8s.services().inNamespace("garage-system").withName("garage").portForward(3900)

      // The store's web endpoint, as the garage component tells the operator (feature 055).
      val settings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        baseDomain = Some(BaseDomain),
        httpsPort = httpsPort,
        objectStore = Some(
          store.settings.copy(webService =
            Some(
              ObjectStoreSettings.ServiceRef(
                store.settings.service.namespace,
                store.settings.service.name,
                3902
              )
            )
          )
        )
      )
      operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
      operator.start()

      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        baseDomain = Some(BaseDomain),
        httpsPort = httpsPort,
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
          secrets = Some(projector)
        )*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
      config = Files.createTempFile("ankka-storage-client", ".json")
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
    if s3Forward != null then s3Forward.close()
    if store != null then store.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  // ── the scenario's state ───────────────────────────────────────────────────

  private var scenario: Int                              = 0
  private var deployed: Vector[String]                   = Vector.empty
  private var pending: Option[String]                    = None // descriptor JSON
  private var last: Run                                  = Run(0, "", "")
  private var told: (Int, String)                        = (0, "")
  private var shown: (Int, Map[String, String], String)  = (0, Map.empty, "")
  private var signed: String                             = ""
  private var status: Option[ServiceStatus]              = None
  private var supplied: Option[(String, String, String)] = None // bucket, key id, secret
  private var suiteReader: Option[(String, String)]      = None

  private def real(logical: String): String = s"$logical-$scenario"

  private def named(logical: String, bucket: String): String =
    Buckets.named(Project, real(logical), bucket)

  private def unnamed(logical: String): String = Buckets.name(Project, real(logical))

  override def beforeEach(context: BeforeEach): Unit =
    if !munitIgnore then
      for name <- deployed if statusOf(name).isDefined do
        ankka("services", "delete", name, "-p", Project): Unit
      deployed = Vector.empty
      scenario += 1
      pending = None
      last = Run(0, "", "")
      told = (0, "")
      shown = (0, Map.empty, "")
      signed = ""
      status = None

  // ── the CLI and the cluster ────────────────────────────────────────────────

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

  private def namespace = s"$Prefix-$Project"

  private def servicePods = ServicePods(k3s, k8s, _ => namespace)

  private def pods(name: String): Vector[Pod] = servicePods.pods(Project, name)

  // ── descriptors ────────────────────────────────────────────────────────────

  private def bucketJson(name: String, exposure: String, origins: Vector[String] = Vector.empty) =
    val fields = Vector(
      Some(s""""name":"$name""""),
      Option.when(exposure != "private")(s""""exposure":"$exposure""""),
      Option.when(origins.nonEmpty)(
        origins.map(o => s""""$o"""").mkString(""""origins":[""", ",", "]")
      )
    ).flatten
    fields.mkString("{", ",", "}")

  private def suppliedJson(name: String, bucket: String, secret: String) =
    s"""{"name":"$name","supplied":{"endpoint":"${store.settings.endpoint}","region":"garage",""" +
      s""""bucket":"$bucket","credential":"$secret"}}"""

  private def descriptor(
      name: String,
      provision: Boolean,
      buckets: Vector[String],
      expose: Boolean = false
  ): String =
    val fields = Vector(
      Some(s""""image":"$SampleImage""""),
      Option.when(provision)(""""provisionObjectStorage":true"""),
      Option.when(expose)(""""exposeObjectStorage":true"""),
      Option.when(buckets.nonEmpty)(buckets.mkString(""""buckets":[""", ",", "]"))
    ).flatten
    s"""{"name":"$name","service":{${fields.mkString(",")}}}"""

  private def applyJson(json: String): Run =
    val file = Files.createTempFile("ankka-storage-client", ".json")
    try
      Files.writeString(file, json): Unit
      ankka("services", "apply", "-f", file.toString, "-p", Project)
    finally Files.deleteIfExists(file): Unit

  private def deploy(
      logical: String,
      provision: Boolean,
      buckets: Vector[String],
      expose: Boolean = false
  ): String =
    val name = real(logical)
    ok(applyJson(descriptor(name, provision, buckets, expose)))
    if !deployed.contains(name) then deployed = deployed :+ name
    awaitReady(name)
    name

  private def awaitReady(name: String): Unit =
    waitFor(360.seconds, s"$name being Ready") {
      statusOf(name).exists(s =>
        s.lifecycle == ServiceLifecycle.Ready && s.confirmed &&
          s.buckets.forall(b => Set("Provisioned", "Recovered", "Supplied", "Kept")(b.phase))
      ) && pods(name).exists(ServicePods.running)
    }

  private def environment(name: String): Map[String, String] =
    servicePods.environment(Project, name)

  // ── the service's handler, asked from inside its own pod ───────────────────

  private def enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

  private def query(params: (String, Option[String])*): String =
    params.collect { case (k, Some(v)) => s"$k=${enc(v)}" }.mkString("?", "&", "")

  private def handler(
      name: String,
      method: String,
      path: String,
      body: Option[String] = None
  ): (Int, String) =
    val pod = pods(name).find(ServicePods.running).getOrElse(fail(s"$name has no running pod"))
    InPod.curl(
      k3s,
      namespace,
      pod.getMetadata.getName,
      s"https://127.0.0.1:9000$path",
      method = method,
      body = body,
      verifyHost = false,
      container = Some(name),
      maxSeconds = 20
    )

  private def contentTypeOf(obj: String): String =
    if obj.endsWith(".jpg") then "image/jpeg"
    else if obj.endsWith(".pdf") then "application/pdf"
    else if obj.endsWith(".csv") then "text/csv"
    else "text/plain"

  private def contents(obj: String) = s"contents of $obj"

  private def keep(logical: String, obj: String, bucket: Option[String]): Unit =
    val bucketField = bucket.fold("null")(b => s""""$b"""")
    val (code, body) = handler(
      real(logical),
      "POST",
      "/storage/objects",
      Some(
        s"""{"bucket":$bucketField,"name":"$obj","contentType":"${contentTypeOf(
            obj
          )}","text":"${contents(obj)}"}"""
      )
    )
    assert(code == 200 || code == 204, s"$code $body")

  private def readBack(logical: String, obj: String, bucket: Option[String]): (Int, String) =
    handler(
      real(logical),
      "GET",
      "/storage/objects" + query("bucket" -> bucket, "name" -> Some(obj))
    )

  private def madeUrl(
      logical: String,
      path: String,
      params: (String, Option[String])*
  ): String =
    val (code, body) = handler(real(logical), "GET", path + query(params*))
    assertEquals(code, 200, body)
    // A string is answered as text, not as a JSON string.
    body.trim

  // ── the internet ───────────────────────────────────────────────────────────

  private val StorageHost = s"storage.$BaseDomain"

  /** `curl` on the host through the gateway: status, headers, body. Never `--insecure`. */
  private def browse(address: String, extra: String*): (Int, Map[String, String], String) =
    val command = Vector(
      "curl",
      "-sS",
      "-m",
      "20",
      "-D",
      "-",
      "--cacert",
      ca.toString,
      "--resolve",
      s"$StorageHost:$httpsPort:127.0.0.1"
    ) ++ extra :+ address
    val process = new ProcessBuilder(command*).redirectErrorStream(false).start()
    var output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    process.waitFor()
    while output.startsWith("HTTP/1.1 100") && output.contains("\r\n\r\n") do
      output = output.substring(output.indexOf("\r\n\r\n") + 4)
    val split = output.indexOf("\r\n\r\n")
    if split < 0 then (0, Map.empty, output)
    else
      val head = output.substring(0, split).split("\r\n").toVector
      val code = head.headOption.flatMap(_.split(" ").lift(1)).flatMap(_.toIntOption).getOrElse(0)
      val headers = head
        .drop(1)
        .flatMap(line =>
          line.split(":", 2) match
            case Array(k, v) => Some(k.trim.toLowerCase -> v.trim)
            case _           => None
        )
        .toMap
      (code, headers, output.substring(split + 4))

  private def publicBase = s"https://$StorageHost:$httpsPort"

  private def assertShown(obj: String): Unit =
    assertEquals(shown._1, 200, shown._3)
    assertEquals(shown._3, contents(obj))

  // ── the store, as its administrator ────────────────────────────────────────

  private def storeClient(key: (String, String)) =
    ServicePods.s3Client(s"http://127.0.0.1:${s3Forward.getLocalPort}", "garage", key)

  /** A key of the suite's own, allowed to read `bucket`: how a kept object is looked at. */
  private def readAsSuite(bucket: String, obj: String): Option[String] =
    val key = suiteReader.getOrElse {
      val issued = admin.createKey("suite-reader")
      val pair   = (issued.accessKeyId, issued.secretAccessKey)
      suiteReader = Some(pair)
      pair
    }
    admin
      .bucket(bucket)
      .foreach(b => if !b.allowedKeys(key._1) then admin.allow(b.id, key._1, false))
    val client = storeClient(key)
    try
      Some(
        client
          .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(obj).build())
          .asUtf8String()
      )
    catch case _: Exception => None
    finally client.close()

  /** The developer's own bucket: made with a key of its own, and that key in a project secret. */
  private def developersOwn(bucket: String, secret: String): Unit =
    val made   = admin.bucket(bucket).getOrElse(admin.createBucket(bucket))
    val issued = admin.createKey(s"developer-$bucket")
    admin.allow(made.id, issued.accessKeyId, true)
    supplied = Some((bucket, issued.accessKeyId, issued.secretAccessKey))
    ok(
      ankka(
        "projects",
        "secrets",
        "set",
        secret,
        s"access-key=${issued.accessKeyId}",
        s"secret-key=${issued.secretAccessKey}",
        "-p",
        Project
      )
    ): Unit

  /**
   * A signed request from inside the pod, with the key the pod was given, for a listing of
   * `bucket`.
   */
  private def listWithServiceKey(logical: String, bucket: String): Int =
    servicePods.s3(Project, real(logical), "GET", s"/$bucket/")._1

  // ── a descriptor and its application ───────────────────────────────────────

  Given(
    "a descriptor for a service {string} that asks for a bucket and names the buckets {string} as public, {string} as signed and {string} as private"
  ) { (logical: String, public: String, signedName: String, priv: String) =>
    pending = Some(
      descriptor(
        real(logical),
        provision = true,
        Vector(
          bucketJson(public, "public"),
          bucketJson(signedName, "signed"),
          bucketJson(priv, "private")
        )
      )
    )
  }

  Given("a descriptor for a service {string} that names the bucket {string} twice") {
    (logical: String, bucket: String) =>
      pending = Some(
        descriptor(
          real(logical),
          provision = true,
          Vector(bucketJson(bucket, "private"), bucketJson(bucket, "public"))
        )
      )
  }

  Given("a descriptor for a service {string} that names a bucket {string}") {
    (logical: String, bucket: String) =>
      pending =
        Some(descriptor(real(logical), provision = true, Vector(bucketJson(bucket, "private"))))
  }

  When("a member applies the descriptor") { () =>
    val json = pending.getOrElse(fail("no descriptor"))
    last = applyJson(json)
    val name = json.drop(9).takeWhile(_ != '"')
    if last.code == 0 then
      deployed = deployed :+ name
      awaitReady(name)
  }

  Then("the descriptor is refused") { () =>
    assertNotEquals(last.code, 0, last.all)
  }

  Then("the refusal names the bucket {string} as named twice") { (bucket: String) =>
    assert(last.all.contains(s"'$bucket' is named twice"), last.all)
  }

  Then("the refusal names the rule for bucket names") { () =>
    assert(
      last.all.contains("lower-case letters, digits and '-', 1 to 32 characters"),
      last.all
    )
  }

  // ── the variables ──────────────────────────────────────────────────────────

  Then("{string} starts with the variables {string}, {string} and {string} set") {
    (logical: String, a: String, b: String, c: String) =>
      val env = environment(real(logical))
      Seq(a, b, c).foreach(v => assert(env.get(v).exists(_.nonEmpty), s"$v in ${env.keySet}"))
  }

  Then("{string} starts with the variable {string} set") { (logical: String, variable: String) =>
    val env = environment(real(logical))
    assert(env.get(variable).exists(_.nonEmpty), s"$variable in ${env.keySet}")
  }

  Then("{string} has no variable {string} and no variable {string}") {
    (logical: String, a: String, b: String) =>
      val env = environment(real(logical))
      assert(!env.contains(a) && !env.contains(b), env.keySet.toString)
  }

  Then("{string} has no variable {string}") { (logical: String, variable: String) =>
    waitFor(180.seconds, s"${real(logical)} rolled without $variable") {
      !environment(real(logical)).contains(variable)
    }
  }

  // ── deployed services ──────────────────────────────────────────────────────

  Given("a deployed service {string} with a public bucket {string}") {
    (logical: String, bucket: String) =>
      deploy(logical, provision = true, Vector(bucketJson(bucket, "public"))): Unit
  }

  Given("a deployed service {string} with a bucket and a public bucket {string}") {
    (logical: String, bucket: String) =>
      deploy(logical, provision = true, Vector(bucketJson(bucket, "public"))): Unit
  }

  Given("a deployed service {string} with a bucket and a named bucket {string}") {
    (logical: String, bucket: String) =>
      deploy(logical, provision = true, Vector(bucketJson(bucket, "private"))): Unit
  }

  Given("a deployed service {string} with a named bucket {string}") {
    (logical: String, bucket: String) =>
      deploy(logical, provision = false, Vector(bucketJson(bucket, "private"))): Unit
  }

  Given(
    "a deployed service {string} with a bucket, a public bucket {string} and a private bucket {string}"
  ) { (logical: String, public: String, priv: String) =>
    deploy(
      logical,
      provision = true,
      Vector(bucketJson(public, "public"), bucketJson(priv, "private"))
    ): Unit
  }

  Given(
    "a deployed service {string} with a signed bucket {string}, whose descriptor names the origin {string}"
  ) { (logical: String, bucket: String, origin: String) =>
    deploy(logical, provision = false, Vector(bucketJson(bucket, "signed", Vector(origin)))): Unit
  }

  Given(
    "a deployed service {string} with a bucket, a public bucket {string}, a signed bucket {string} and a supplied bucket {string}"
  ) { (logical: String, public: String, signedName: String, archive: String) =>
    val bucket = s"acme-$archive-$scenario"
    developersOwn(bucket, s"$archive-store")
    deploy(
      logical,
      provision = true,
      Vector(
        bucketJson(public, "public"),
        bucketJson(signedName, "signed"),
        suppliedJson(archive, bucket, s"$archive-store")
      )
    ): Unit
  }

  Given("a deployed service {string} whose bucket is reachable from the internet") {
    (logical: String) => deploy(logical, provision = true, Vector.empty, expose = true): Unit
  }

  Given(
    "a deployed service {string} with a bucket, whose descriptor does not ask that the bucket be reachable from the internet"
  )((logical: String) => deploy(logical, provision = true, Vector.empty): Unit)

  // ── keeping and reading through the storage client ─────────────────────────

  Given(
    "a handler of {string} has kept the object {string} in the bucket {string} through the storage client"
  ) { (logical: String, obj: String, bucket: String) =>
    keep(logical, obj, Some(bucket))
  }

  Given("a handler of {string} has kept the object {string} in the bucket {string}") {
    (logical: String, obj: String, bucket: String) => keep(logical, obj, Some(bucket))
  }

  Given("a handler of {string} has kept the object {string} in the bucket of {string}") {
    (logical: String, obj: String, owner: String) =>
      assertEquals(owner, logical)
      keep(logical, obj, None)
  }

  Given("a handler of {string} has kept the object {string}") { (logical: String, obj: String) =>
    keep(logical, obj, None)
  }

  When(
    "a handler of {string} keeps the object {string} in the bucket {string} through the storage client"
  ) { (logical: String, obj: String, bucket: String) =>
    keep(logical, obj, Some(bucket))
  }

  Then("a handler of {string} reads the object {string} back through the storage client") {
    (logical: String, obj: String) =>
      val (code, body) = readBack(logical, obj, None)
      assertEquals(code, 200, body)
      assertEquals(body, contents(obj))
  }

  // ── the internet ───────────────────────────────────────────────────────────

  When(
    "a person on the internet sends a request to the public address of {string} in the bucket {string} without a signed URL"
  ) { (obj: String, bucket: String) =>
    val logical = "listings"
    val address = madeUrl(logical, "/storage/url", "bucket" -> Some(bucket), "name" -> Some(obj))
    assertEquals(address, s"$publicBase/${named(logical, bucket)}/$obj")
    shown = browse(address)
  }

  Then("the person is shown the object {string}") { (obj: String) =>
    assertShown(obj)
    assertEquals(shown._2.get("content-type"), Some(contentTypeOf(obj)))
  }

  When(
    "a person on the internet sends a request for the object {string} in the bucket of {string} without a signed URL"
  ) { (obj: String, logical: String) =>
    shown = browse(s"$publicBase/${unnamed(logical)}/$obj")
  }

  When(
    "a person on the internet sends a request for the object {string} in the bucket {string} without a signed URL"
  ) { (obj: String, bucket: String) =>
    val logical = "listings"
    shown = browse(s"$publicBase/${named(logical, bucket)}/$obj")
    // Nor through the public bucket's route, which serves its own bucket and no other.
    val around =
      browse(s"$publicBase/${named(logical, "photos")}/../${named(logical, bucket)}/$obj")
    assert(!around._3.contains(contents(obj)), around.toString)
  }

  // A refusal is the gateway's or the store's; either way, no object.
  Then("the object store refuses the request") { () =>
    if signed.nonEmpty || told._1 == 0 then
      assert(shown._1 != 200 && shown._1 != 0, shown.toString)
      assert(!shown._3.startsWith("contents of"), shown.toString)
    else assertEquals(told._1, 403, told._2)
  }

  When(
    "a browser on the origin {string} sends a request to a signed URL that {string} made through the storage client for reading {string} in the bucket {string}"
  ) { (origin: String, logical: String, obj: String, bucket: String) =>
    signed = madeUrl(
      logical,
      "/storage/signed",
      "bucket" -> Some(bucket),
      "name"   -> Some(obj),
      "access" -> Some("read")
    )
    assert(signed.startsWith(s"$publicBase/${named(logical, bucket)}/"), signed)
    shown = browse(signed, "-H", s"Origin: $origin")
    assertEquals(shown._2.get("access-control-allow-origin"), Some(origin), shown.toString)
  }

  Then("the browser is shown the object {string}")((obj: String) => assertShown(obj))

  When(
    "a browser sends a request to a signed URL that {string} made through the storage client for reading {string}"
  ) { (logical: String, obj: String) =>
    signed = madeUrl(logical, "/storage/signed", "name" -> Some(obj), "access" -> Some("read"))
    assert(signed.startsWith(s"$publicBase/${unnamed(logical)}/"), signed)
    shown = browse(signed)
  }

  When(
    "a browser sends the object {string} to a signed URL that {string} made through the storage client for keeping {string}"
  ) { (obj: String, logical: String, target: String) =>
    assertEquals(obj, target)
    signed = madeUrl(logical, "/storage/signed", "name" -> Some(obj), "access" -> Some("keep"))
    val file = Files.createTempFile("ankka-upload", ".bin")
    try
      Files.writeString(file, contents(obj)): Unit
      shown = browse(
        signed,
        "-X",
        "PUT",
        "-T",
        file.toString,
        "-H",
        s"Content-Type: ${contentTypeOf(obj)}"
      )
      assertEquals(shown._1, 200, shown.toString)
    finally Files.deleteIfExists(file): Unit
  }

  /** "5 minutes" is three seconds here: a hundredth, so an expiry is crossed in seconds. */
  private def scaled(span: String): FiniteDuration =
    span.split(" ").toList match
      case n :: unit :: Nil if unit.startsWith("minute") => (n.toInt * 600).millis
      case n :: unit :: Nil if unit.startsWith("day")    => (n.toLong * 24 * 3600).seconds
      case _                                             => fail(s"no span '$span'")

  Given("{string} made a signed URL through the storage client for reading {string} for {string}") {
    (logical: String, obj: String, span: String) =>
      signed = madeUrl(
        logical,
        "/storage/signed",
        "name"    -> Some(obj),
        "access"  -> Some("read"),
        "seconds" -> Some(math.max(1L, scaled(span).toSeconds).toString)
      )
      // It reads now: what changes is only the time.
      val now = browse(signed)
      assertEquals(now._1, 200, now.toString)
  }

  When("a browser sends a request to the signed URL after {string}") { (after: String) =>
    Thread.sleep(scaled(after).toMillis + 1000)
    shown = browse(signed)
  }

  When(
    "a handler of {string} asks the storage client for a signed URL for reading {string} for {string}"
  ) { (logical: String, obj: String, span: String) =>
    // A span the bound refuses is the client's to refuse, so the days are days.
    val seconds = span.split(" ").toList match
      case n :: unit :: Nil if unit.startsWith("day") => n.toLong * 24 * 3600
      case _                                          => scaled(span).toSeconds
    told = handler(
      real(logical),
      "GET",
      "/storage/signed" + query(
        "name"    -> Some(obj),
        "access"  -> Some("read"),
        "seconds" -> Some(seconds.toString)
      )
    )
  }

  When("a handler of {string} asks the storage client for a signed URL for reading {string}") {
    (logical: String, obj: String) =>
      told = handler(
        real(logical),
        "GET",
        "/storage/signed" + query("name" -> Some(obj), "access" -> Some("read"))
      )
  }

  Then("the handler is refused") { () =>
    assert(told._1 == 400 || told._1 == 503, s"${told._1} ${told._2}")
  }

  Then("the refusal names the bound of {string}") { (bound: String) =>
    assert(told._2.contains(s"at most $bound"), told._2)
  }

  Then(
    "the refusal says that the bucket is not reachable from the internet and names {string}"
  ) { (setting: String) =>
    assert(told._2.contains("not reachable from the internet"), told._2)
    assert(told._2.contains(setting), told._2)
  }

  // ── a supplied bucket ──────────────────────────────────────────────────────

  Given(
    "an object store of the developer's own with a bucket {string} and a project secret {string} holding its credential"
  )((bucket: String, secret: String) => developersOwn(bucket, secret))

  Given(
    "a descriptor for a service {string} that names the bucket {string} as supplied from {string} with the project secret {string}"
  ) { (logical: String, name: String, bucket: String, secret: String) =>
    pending =
      Some(descriptor(real(logical), provision = false, Vector(suppliedJson(name, bucket, secret))))
  }

  Then("the bucket {string} in the object store of the developer's own holds the object {string}") {
    (bucket: String, obj: String) =>
      val (_, id, secret) = supplied.getOrElse(fail("no store of the developer's own"))
      val client          = storeClient((id, secret))
      try
        assertEquals(
          client
            .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(obj).build())
            .asUtf8String(),
          contents(obj)
        )
      finally client.close()
  }

  Then("the platform made no bucket and no storage credential for {string}") { (name: String) =>
    val logical = "listings"
    assertEquals(admin.bucket(named(logical, name)), None)
    // The developer's bucket is reached by the developer's key alone.
    val (bucket, id, _) = supplied.getOrElse(fail("no store of the developer's own"))
    assertEquals(admin.bucket(bucket).map(_.allowedKeys), Some(Set(id)))
    // And a service with no bucket of the platform's is given no key of the platform's.
    assertEquals(
      Option(k8s.secrets().inNamespace(namespace).withName(Buckets.secret(real(logical))).get()),
      None
    )
  }

  Then("the status says that the bucket {string} is supplied") { (name: String) =>
    val report = statusOf(real("listings")).flatMap(_.buckets.find(_.name == name))
    assertEquals(report.map(_.phase), Some("Supplied"), report.toString)
    assertEquals(report.map(_.exposure), Some("supplied"))
  }

  // ── one credential, every bucket of the service's and no other's ───────────

  When("{string} uses its storage credential against the bucket {string} of {string}") {
    (logical: String, bucket: String, other: String) =>
      told = (listWithServiceKey(logical, named(other, bucket)), "")
  }

  Then("{string} reaches its bucket and the bucket {string} with the same storage credential") {
    (logical: String, bucket: String) =>
      assertEquals(listWithServiceKey(logical, unnamed(logical)), 200)
      assertEquals(listWithServiceKey(logical, named(logical, bucket)), 200)
  }

  // ── the status ─────────────────────────────────────────────────────────────

  When("a member reads the status of {string}") { (logical: String) =>
    status = statusOf(real(logical))
    val human = ok(ankka("services", "get", real(logical), "-p", Project)).out
    assert(human.contains("bucket "), human)
  }

  Then("the status names the bucket of {string} and the buckets {string}, {string} and {string}") {
    (logical: String, a: String, b: String, c: String) =>
      val s = status.getOrElse(fail("no status"))
      assertEquals(s.bucket, Some(unnamed(logical)))
      assertEquals(s.buckets.map(_.name).toSet, Set(a, b, c))
  }

  Then("the status says that {string} is public and names its public address") { (name: String) =>
    val b = status.flatMap(_.buckets.find(_.name == name)).getOrElse(fail(s"no $name"))
    assertEquals(b.exposure, "public")
    assertEquals(b.publicAddress, Some(s"$publicBase/${named("listings", name)}"))
  }

  Then("the status says that {string} is signed and names its address on the internet") {
    (name: String) =>
      val b = status.flatMap(_.buckets.find(_.name == name)).getOrElse(fail(s"no $name"))
      assertEquals(b.exposure, "signed")
      assertEquals(b.publicAddress, Some(s"$publicBase/${named("listings", name)}"))
  }

  Then("the status says that {string} is supplied") { (name: String) =>
    val b = status.flatMap(_.buckets.find(_.name == name)).getOrElse(fail(s"no $name"))
    assertEquals((b.phase, b.exposure), ("Supplied", "supplied"))
  }

  // ── keeping what the descriptor stops naming ───────────────────────────────

  When("a member deletes {string}") { (logical: String) =>
    ok(ankka("services", "delete", real(logical), "-p", Project))
    waitFor(120.seconds, s"${real(logical)} gone")(pods(real(logical)).isEmpty)
  }

  When("a member applies a descriptor for {string} that no longer names the bucket {string}") {
    (logical: String, dropped: String) =>
      ok(applyJson(descriptor(real(logical), provision = true, Vector.empty)))
      waitFor(180.seconds, s"$dropped written back kept") {
        statusOf(real(logical)).exists(_.buckets.exists(b => b.name == dropped && b.kept))
      }
  }

  Then("the bucket {string} of {string} and the object {string} are kept") {
    (bucket: String, logical: String, obj: String) =>
      assert(admin.bucket(named(logical, bucket)).isDefined, s"${named(logical, bucket)} is gone")
      assertEquals(readAsSuite(named(logical, bucket), obj), Some(contents(obj)))
  }

  Then("the status says that the bucket {string} is kept and no longer named") { (name: String) =>
    val logical = "listings"
    val b =
      statusOf(real(logical)).flatMap(_.buckets.find(_.name == name)).getOrElse(fail(s"no $name"))
    assertEquals((b.phase, b.kept), ("Kept", true))
    val human = ok(ankka("services", "get", real(logical), "-p", Project)).out
    assert(human.contains("(no longer named)"), human)
    // And the route it had is gone: an address signed or public before reaches nothing now.
    waitFor(60.seconds, "the kept bucket's route removed") {
      Option(
        k8s
          .genericKubernetesResources("gateway.networking.k8s.io/v1", "HTTPRoute")
          .inNamespace(namespace)
          .withName(s"${real(logical)}-storage-$name")
          .get()
      ).isEmpty
    }
  }

  // ── a deployed service serves nothing at the directory store's path ────────

  When(
    "a browser sends a request to {string} at the path a service on a developer's machine serves objects from"
  ) { (logical: String) =>
    told =
      handler(real(logical), "GET", s"/_ankka/storage/${named(logical, "photos")}/anything.jpg")
  }

  Then("{string} answers that there is nothing there") { (_: String) =>
    assertEquals(told._1, 404, told._2)
  }

  // Kept for the listing check above; a bucket the suite reads lists with its own key.
  locally {
    val _ = ListObjectsV2Request.builder()
  }
