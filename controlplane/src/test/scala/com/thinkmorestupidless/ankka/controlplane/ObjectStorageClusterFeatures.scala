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
  ObjectStoreStack,
  Operator,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.{
  Config,
  ConfigBuilder,
  KubernetesClient,
  KubernetesClientBuilder,
  KubernetesClientException
}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, PutObjectRequest}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.{
  GetObjectPresignRequest,
  PutObjectPresignRequest
}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Duration
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * `features/object-storage/` on k3s (feature 034): the control plane and the operator in this JVM,
 * as `EndToEndClusterSuite` runs them, the installation's object store from its own component,
 * CloudNativePG, cert-manager and Envoy Gateway in front, and the shopping cart sample as every
 * service's image — it has a shell and `curl`, which keep and read an object from inside the pod
 * with exactly the variables the pod was given.
 *
 * A browser is `curl` on the host through the gateway's mapped port with `--resolve`; a URL is
 * signed on the host with the AWS SDK's presigner, an S3 client nobody here wrote, with the
 * credential read out of the pod's own environment. A request counts by its status, never by curl's
 * exit code, and a refusal counts only when it is the store's own.
 *
 * Every scenario has services of its own: a feature's `"reports"` is `reports-<n>` here, so a
 * bucket an earlier scenario made — and the platform never deletes one — is never mistaken for this
 * one's.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the sample's image.
 */
class ObjectStorageClusterFeatures
    extends GherkinSuite("../features/object-storage")
    with LogCapturing:

  override val munitTimeout: FiniteDuration = 15.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  override protected def ranElsewhere: Map[String, String] = Map(
    "the variables of a bucket are given to the process and not to the platform's own program" ->
      "SidecarClusterSuite",
    "a module that asks for a variable of its bucket is told its value" -> "SidecarClusterSuite",
    "the console shows what a service has for object storage beside its database" ->
      "the console's browser suite, console/e2e/tests/services.spec.ts"
  )

  private val K3sImage    = "rancher/k3s:v1.35.1-k3s1"
  private val Tag         = com.thinkmorestupidless.ankka.core.BuildInfo.version.replace('+', '-')
  private val SampleImage = s"sample-shopping-cart:$Tag"
  private val BaseDomain  = "store.example.test"
  private val Prefix      = "ankka"
  private val Projects    = Vector("shop", "bank")

  private val RotationGrace = 30.seconds

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
  private var operatorSettings: OperatorSettings                       = null
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
      // The operator's own RBAC, from the shipped manifest, so a token can be minted for it.
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

      operatorSettings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        baseDomain = Some(BaseDomain),
        httpsPort = httpsPort,
        objectStore = Some(store.settings),
        // An old storage credential ends this long after a new one is issued (feature 039): an hour
        // by default, which no scenario can wait out.
        rotationGrace = RotationGrace
      )
      startOperator(operatorSettings)

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
          logs = Some(new PodLogs(k8s, Prefix))
        )*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
      config = Files.createTempFile("ankka-object-storage", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString
      ok(ankka("organizations", "create", "acme", "--name", "Acme"))
      for project <- Projects do
        ok(ankka("projects", "create", project, "--name", project, "--organization", "acme")): Unit

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

  private def startOperator(settings: OperatorSettings): Unit =
    if operator != null then operator.close()
    operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
    operator.start()

  // ── the scenario's names ──────────────────────────────────────────────────

  private var scenario: Int                                    = 0
  private var deployed: Vector[(String, String)]               = Vector.empty
  private var pending: Option[(String, String)]                = None // (project, descriptor JSON)
  private var pendingName: String                              = ""
  private var last: Run                                        = Run(0, "", "")
  private var lastStatus: Option[ServiceStatus]                = None
  private var credentialBefore: (String, String)               = ("", "")
  private var keyBefore: (String, String)                      = ("", "")
  private var originsNamed: Vector[String]                     = Vector.empty
  private var signedUrl: String                                = ""
  private var browserReply: (Int, Map[String, String], String) = (0, Map.empty, "")
  private var operatorChanged: Boolean                         = false

  /** This scenario's name for a service a feature calls `logical`. */
  private def real(logical: String): String = s"$logical-$scenario"

  private def bucketOf(project: String, logical: String): String =
    Buckets.name(project, real(logical))

  override def beforeEach(context: BeforeEach): Unit =
    if !munitIgnore then
      if operatorChanged then
        startOperator(operatorSettings)
        operatorChanged = false
      for (project, name) <- deployed if statusOf(name, project).isDefined do
        ankka("services", "delete", name, "-p", project): Unit
      deployed = Vector.empty
      scenario += 1
      pending = None
      pendingName = ""
      last = Run(0, "", "")
      lastStatus = None
      signedUrl = ""
      browserReply = (0, Map.empty, "")

  // ── the CLI, the cluster and the store ─────────────────────────────────────

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

  private def statusOf(name: String, project: String = "shop"): Option[ServiceStatus] =
    val run = ankka("services", "get", name, "-p", project, "-o", "json")
    Option.when(run.code == 0)(readFromString[ServiceStatus](run.out))

  private def namespace(project: String) = s"$Prefix-$project"

  private def servicePods = ServicePods(k3s, k8s, namespace)

  private def pods(project: String, name: String): Vector[Pod] = servicePods.pods(project, name)

  private def running(pod: Pod): Boolean = ServicePods.running(pod)

  private def descriptor(
      name: String,
      provision: Boolean = false,
      expose: Boolean = false,
      env: Vector[String] = Vector.empty,
      origins: Vector[String] = Vector.empty
  ): String =
    val fields = Vector(
      Some(s""""image":"$SampleImage""""),
      Option.when(provision)(""""provisionObjectStorage":true"""),
      Option.when(expose)(""""exposeObjectStorage":true"""),
      Option.when(origins.nonEmpty)(
        origins.map(o => s""""$o"""").mkString(""""objectStorageOrigins":[""", ",", "]")
      ),
      Option.when(env.nonEmpty)(env.mkString(""""env":[""", ",", "]"))
    ).flatten
    s"""{"name":"$name","service":{${fields.mkString(",")}}}"""

  private def literal(name: String, value: String) = s"""{"name":"$name","value":"$value"}"""

  private def applyJson(project: String, json: String): Run =
    val file = Files.createTempFile("ankka-object-storage", ".json")
    try
      Files.writeString(file, json): Unit
      ankka("services", "apply", "-f", file.toString, "-p", project)
    finally Files.deleteIfExists(file): Unit

  private def deploy(
      project: String,
      logical: String,
      provision: Boolean,
      expose: Boolean = false,
      origins: Vector[String] = Vector.empty
  ): String =
    val name = real(logical)
    ok(applyJson(project, descriptor(name, provision, expose, origins = origins)))
    deployed = deployed :+ (project -> name)
    awaitReady(project, name)
    name

  private def awaitReady(project: String, name: String): Unit =
    waitFor(360.seconds, s"$project/$name being Ready") {
      statusOf(name, project).exists(s =>
        s.lifecycle == ServiceLifecycle.Ready && s.confirmed &&
          (s.bucket.isEmpty || s.objectStorage.exists(p =>
            p == "provisioned" || p.startsWith("recovered")
          ))
      ) && pods(project, name).exists(running)
    }

  private def environment(project: String, name: String): Map[String, String] =
    servicePods.environment(project, name)

  /** A signed request from inside the pod: its status, and the body. */
  private def s3(
      project: String,
      name: String,
      method: String,
      path: String,
      upload: Option[String] = None
  ): (Int, String) =
    servicePods.s3(project, name, method, path, upload)

  private def keep(project: String, logical: String, obj: String): Unit =
    val (code, body) =
      s3(
        project,
        real(logical),
        "PUT",
        s"/${bucketOf(project, logical)}/$obj",
        Some(s"contents of $obj")
      )
    assertEquals(code, 200, body)

  private def readBack(project: String, logical: String, obj: String): Unit =
    val (code, body) = s3(project, real(logical), "GET", s"/${bucketOf(project, logical)}/$obj")
    assertEquals(code, 200, body)
    assertEquals(body, s"contents of $obj")

  private def presigner(project: String, name: String): S3Presigner =
    val env = environment(project, name)
    S3Presigner
      .builder()
      .endpointOverride(URI.create(env("ANKKA_S3_PUBLIC_ENDPOINT")))
      .region(Region.of(env("ANKKA_S3_REGION")))
      .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
      .credentialsProvider(
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(env("ANKKA_S3_ACCESS_KEY"), env("ANKKA_S3_SECRET_KEY"))
        )
      )
      .build()

  private def signForReading(project: String, logical: String, obj: String): String =
    presigner(project, real(logical))
      .presignGetObject(
        GetObjectPresignRequest
          .builder()
          .signatureDuration(Duration.ofMinutes(30))
          .getObjectRequest(
            GetObjectRequest.builder().bucket(bucketOf(project, logical)).key(obj).build()
          )
          .build()
      )
      .url()
      .toString

  private def signForKeeping(project: String, logical: String, obj: String): String =
    presigner(project, real(logical))
      .presignPutObject(
        PutObjectPresignRequest
          .builder()
          .signatureDuration(Duration.ofMinutes(30))
          .putObjectRequest(
            PutObjectRequest.builder().bucket(bucketOf(project, logical)).key(obj).build()
          )
          .build()
      )
      .url()
      .toString

  private val StorageHost = s"storage.$BaseDomain"

  /**
   * `curl` on the host, through the gateway's mapped port: status, headers, body. An interim answer
   * (`100 Continue` to an upload) is skipped: the last head is the one that counts.
   */
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
    assert(!command.exists(a => a == "-k" || a == "--insecure"))
    val process = new ProcessBuilder(command*).redirectErrorStream(false).start()
    var output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    process.waitFor()
    while output.startsWith("HTTP/1.1 100") && output.contains("\r\n\r\n") do
      output = output.substring(output.indexOf("\r\n\r\n") + 4)
    val split = output.indexOf("\r\n\r\n")
    if split < 0 then (0, Map.empty, output)
    else
      val head   = output.substring(0, split).split("\r\n").toVector
      val status = head.headOption.flatMap(_.split(" ").lift(1)).flatMap(_.toIntOption).getOrElse(0)
      val headers = head
        .drop(1)
        .flatMap(line =>
          line.split(":", 2) match
            case Array(k, v) => Some(k.trim.toLowerCase -> v.trim)
            case _           => None
        )
        .toMap
      (status, headers, output.substring(split + 4))

  private def storeAnswered(reply: (Int, Map[String, String], String)): Boolean =
    reply._3.contains("<Error>") || reply._3.contains("<?xml")

  /** The access key and secret the platform holds for a service's bucket now. */
  private def storageKeyOf(project: String, logical: String): (String, String) =
    val secret = k8s
      .secrets()
      .inNamespace(namespace(project))
      .withName(Buckets.secret(real(logical)))
      .get()
    val data = secret.getData.asScala.view.mapValues(v =>
      new String(java.util.Base64.getDecoder.decode(v), StandardCharsets.UTF_8)
    )
    (data("ANKKA_S3_ACCESS_KEY"), data("ANKKA_S3_SECRET_KEY"))

  /** An S3 client on the host, through a forward to the store, holding `key`. */
  private def storeClient(key: (String, String)): software.amazon.awssdk.services.s3.S3Client =
    ServicePods.s3Client(s"http://127.0.0.1:${s3Forward.getLocalPort}", "garage", key)

  /** An object read with the service's own credential through a forward to the store. */
  private def objectInStore(project: String, logical: String, obj: String): Option[String] =
    val client = storeClient(storageKeyOf(project, logical))
    try
      Some(
        client
          .getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucketOf(project, logical)).key(obj).build()
          )
          .asUtf8String()
      )
    catch case _: Exception => None
    finally client.close()

  /** The store's answer to a listing of `bucket` with `key`: 200, or the status it refused with. */
  private def storeAnswer(key: (String, String), bucket: String): Int =
    val client = storeClient(key)
    try
      client.listObjectsV2(
        software.amazon.awssdk.services.s3.model.ListObjectsV2Request
          .builder()
          .bucket(bucket)
          .maxKeys(1)
          .build()
      ): Unit
      200
    catch case e: software.amazon.awssdk.services.s3.model.S3Exception => e.statusCode()
    finally client.close()

  /** The storage access key one instance of a service was started with. */
  private def accessKeyIn(project: String, name: String, pod: Pod): Option[String] =
    servicePods.accessKeyIn(project, name, pod)

  private def credentialOf(project: String, logical: String): (String, String) =
    val s =
      k8s.secrets().inNamespace(namespace(project)).withName(Buckets.secret(real(logical))).get()
    assert(s != null, s"${Buckets.secret(real(logical))} does not exist")
    (s.getMetadata.getUid, s.getMetadata.getResourceVersion)

  private def awaitGone(project: String, name: String): Unit =
    waitFor(120.seconds, s"$name going away") {
      statusOf(name, project).isEmpty &&
      k8s.apps().deployments().inNamespace(namespace(project)).withName(name).get() == null &&
      pods(project, name).isEmpty
    }

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("a descriptor for a service {string} that asks for a bucket") { (logical: String) =>
    pendingName = real(logical)
    pending = Some("shop" -> descriptor(pendingName, provision = true))
  }

  Given("a descriptor for a service {string} that asks for no bucket") { (logical: String) =>
    pendingName = real(logical)
    pending = Some("shop" -> descriptor(pendingName))
  }

  Given("a descriptor for a service {string} that asks for a bucket reachable from the internet") {
    (logical: String) =>
      pendingName = real(logical)
      pending = Some("shop" -> descriptor(pendingName, provision = true, expose = true))
  }

  Given(
    "a descriptor that asks for a bucket for a service whose name is longer than a bucket's name may be"
  ) { () =>
    // `shop.` and 60 characters is 65, over a bucket name's 63, and a valid service name.
    pendingName = "reports-" + "x" * 52
    pending = Some("shop" -> descriptor(pendingName, provision = true))
  }

  Given(
    "a descriptor for a service {string} that gives the variables {string}, {string}, {string}, {string} and {string}"
  ) { (logical: String, a: String, b: String, c: String, d: String, e: String) =>
    pendingName = real(logical)
    val own = Vector(a, b, c, d, e).map(v => literal(v, s"own-${v.toLowerCase}"))
    pending = Some("shop" -> descriptor(pendingName, env = own))
  }

  Given(
    "a descriptor for a service {string} that asks for a bucket and gives the variable {string}"
  ) { (logical: String, variable: String) =>
    pendingName = real(logical)
    pending = Some(
      "shop" -> descriptor(pendingName, provision = true, env = Vector(literal(variable, "x")))
    )
  }

  Given(
    "a descriptor for a service {string} that asks that its bucket be reachable from the internet and asks for no bucket"
  ) { (logical: String) =>
    pendingName = real(logical)
    pending = Some("shop" -> descriptor(pendingName, expose = true))
  }

  Given(
    "a descriptor for a service {string} that asks that its bucket be reachable from the internet and gives the variable {string}"
  ) { (logical: String, variable: String) =>
    pendingName = real(logical)
    pending =
      Some("shop" -> descriptor(pendingName, expose = true, env = Vector(literal(variable, "x"))))
  }

  Given(
    "a descriptor for a service {string} in the project {string} with a variable taken from the secret that holds the storage credential of {string}"
  ) { (logical: String, project: String, holder: String) =>
    pendingName = real(logical)
    val ref =
      s"""{"name":"BORROWED","secretKeyRef":{"name":"${Buckets.secret(
          real(holder)
        )}","key":"ANKKA_S3_SECRET_KEY"}}"""
    pending = Some(project -> descriptor(pendingName, env = Vector(ref)))
  }

  Given("a deployed service {string} with a bucket") { (logical: String) =>
    deploy("shop", logical, provision = true): Unit
  }

  Given("a deployed service {string} with a bucket in the project {string}") {
    (logical: String, project: String) => deploy(project, logical, provision = true): Unit
  }

  Given("a deployed service {string} that has kept the object {string} in its bucket") {
    (logical: String, obj: String) =>
      deploy("shop", logical, provision = true)
      keep("shop", logical, obj)
  }

  Given("a deployed service {string} whose bucket is reachable from the internet") {
    (logical: String) => deploy("shop", logical, provision = true, expose = true): Unit
  }

  Given(
    "a deployed service {string} whose bucket is reachable from the internet, whose descriptor names the origin {string}"
  ) { (logical: String, origin: String) =>
    originsNamed = Vector(origin)
    deploy("shop", logical, provision = true, expose = true, origins = originsNamed): Unit
  }

  Given("{string} has set nothing on its bucket") { (logical: String) =>
    // Nothing in this scenario writes the bucket's rule but the operator, and its rule names the
    // descriptor's origins and no other: whatever admits the browser is the platform's.
    waitFor(120.seconds, "the operator setting the bucket's rule") {
      admin.bucket(bucketOf("shop", logical)).exists(_.corsOrigins == originsNamed)
    }
  }

  Given(
    "a deployed service {string} with a bucket, whose descriptor does not ask that the bucket be reachable from the internet"
  ) { (logical: String) =>
    deploy("shop", logical, provision = true): Unit
  }

  Given("{string} has kept the object {string} in its bucket") { (logical: String, obj: String) =>
    keep("shop", logical, obj)
  }

  Given("{string} has since been deleted") { (logical: String) =>
    ok(ankka("services", "delete", real(logical), "-p", "shop"))
    awaitGone("shop", real(logical))
  }

  Given("an installation with no object store") { () =>
    startOperator(operatorSettings.copy(objectStore = None))
    operatorChanged = true
  }

  Given("an installation whose object store cannot be reached at present") { () =>
    startOperator(
      operatorSettings.copy(objectStore =
        Some(store.settings.copy(adminUrl = "http://127.0.0.1:1"))
      )
    )
    operatorChanged = true
  }

  Given("a signed URL that {string} made for reading {string}") { (logical: String, obj: String) =>
    keep("shop", logical, obj)
    signedUrl = signForReading("shop", logical, obj)
    // It works while the bucket is reachable: the scenario is about it ceasing to.
    waitFor(120.seconds, "the signed URL working")(browse(signedUrl)._1 == 200)
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("a member applies the descriptor") { () =>
    val (project, json) = pending.getOrElse(fail("no descriptor to apply"))
    last = applyJson(project, json)
    if last.code == 0 then deployed = deployed :+ (project -> pendingName)
  }

  When("a member applies the descriptor for {string} again") { (logical: String) =>
    ok(applyJson("shop", descriptor(real(logical), provision = true)))
    deployed = deployed :+ ("shop" -> real(logical))
    awaitReady("shop", real(logical))
  }

  When("a member applies the descriptor of {string} again") { (logical: String) =>
    credentialBefore = credentialOf("shop", logical)
    ok(applyJson("shop", descriptor(real(logical), provision = true)))
    // Several reconcile passes, each of which ensures the credential.
    Thread.sleep(10_000)
    awaitReady("shop", real(logical))
  }

  When(
    "a member applies the descriptor of {string} without asking that the bucket be reachable from the internet"
  ) { (logical: String) =>
    ok(applyJson("shop", descriptor(real(logical), provision = true)))
  }

  When("a member asks for the storage credential of {string} to be issued again") {
    (logical: String) =>
      keyBefore = storageKeyOf("shop", logical)
      assertEquals(storeAnswer(keyBefore, bucketOf("shop", logical)), 200)
      ok(ankka("services", "storage", "reissue", real(logical), "-p", "shop")): Unit
  }

  When("a member deletes {string}") { (logical: String) =>
    ok(ankka("services", "delete", real(logical), "-p", "shop"))
    awaitGone("shop", real(logical))
  }

  When("a member reads the status of {string}") { (logical: String) =>
    lastStatus = statusOf(real(logical))
  }

  When("{string} keeps the object {string} in its bucket with what its variables say") {
    (logical: String, obj: String) => keep("shop", logical, obj)
  }

  When("{string} reads the bucket of {string} with its own storage credential") {
    (logical: String, other: String) =>
      val project = deployed.find(_._2 == real(other)).map(_._1).getOrElse("shop")
      val (code, body) =
        s3("shop", real(logical), "GET", s"/${bucketOf(project, other)}/")
      last = Run(code, body, "")
  }

  When("the platform tries to read the storage credential of {string}") { (logical: String) =>
    val token = k3s
      .execInContainer(
        "kubectl",
        "create",
        "token",
        "ankka-operator",
        "-n",
        "ankka-operator",
        "--duration=10m"
      )
      .getStdout
      .trim
    val restricted = new KubernetesClientBuilder()
      .withConfig(
        new ConfigBuilder()
          .withMasterUrl(k8s.getConfiguration.getMasterUrl)
          .withTrustCerts(true)
          .withOauthToken(token)
          .build()
      )
      .build()
    try
      restricted
        .secrets()
        .inNamespace(namespace("shop"))
        .withName(Buckets.secret(real(logical)))
        .get(): Unit
      last = Run(200, "read", "")
    catch case e: KubernetesClientException => last = Run(e.getCode, e.getMessage, "")
    finally restricted.close()
  }

  When("a browser sends a request to a signed URL that {string} made for reading {string}") {
    (logical: String, obj: String) =>
      signedUrl = signForReading("shop", logical, obj)
      waitFor(120.seconds, "the gateway routing the bucket") {
        browserReply = browse(signedUrl)
        browserReply._1 != 404
      }
  }

  When(
    "a browser on the origin {string} sends the object {string} to a signed URL that {string} made for keeping {string}"
  ) { (origin: String, obj: String, logical: String, same: String) =>
    assertEquals(obj, same)
    // The service sets no rule: the one the browser meets is the operator's, from the descriptor.
    signedUrl = signForKeeping("shop", logical, obj)
    // What a browser does first, cross-origin: ask.
    waitFor(120.seconds, "the preflight being allowed through the gateway") {
      val preflight = browse(
        signedUrl,
        "-X",
        "OPTIONS",
        "-H",
        s"Origin: $origin",
        "-H",
        "Access-Control-Request-Method: PUT"
      )
      preflight._1 == 200 && preflight._2.get("access-control-allow-origin").contains(origin)
    }
    val body = Files.createTempFile("upload", ".png")
    Files.writeString(body, s"contents of $obj")
    browserReply =
      browse(signedUrl, "-X", "PUT", "-H", s"Origin: $origin", "--data-binary", s"@$body")
    assertEquals(browserReply._1, 200, browserReply._3)
  }

  When(
    "a person on the internet sends a request for the object {string} in the bucket of {string}"
  ) { (obj: String, logical: String) =>
    browserReply = browse(s"https://$StorageHost:$httpsPort/${bucketOf("shop", logical)}/$obj")
  }

  When("a person on the internet sends a request for the object {string} without a signed URL") {
    (obj: String) =>
      val logical = "reports"
      waitFor(120.seconds, "the gateway routing the bucket") {
        browserReply = browse(s"https://$StorageHost:$httpsPort/${bucketOf("shop", logical)}/$obj")
        browserReply._1 != 404
      }
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  Then(
    "{string} starts with the variables {string}, {string}, {string}, {string} and {string} set"
  ) { (logical: String, a: String, b: String, c: String, d: String, e: String) =>
    awaitReady("shop", real(logical))
    val env = environment("shop", real(logical))
    for v <- Vector(a, b, c, d, e) do assert(env.get(v).exists(_.nonEmpty), s"$v is not set")
  }

  Then("the variable {string} names a bucket the platform made for {string}") {
    (variable: String, logical: String) =>
      val bucket = environment("shop", real(logical))(variable)
      assertEquals(bucket, bucketOf("shop", logical))
      assert(admin.bucket(bucket).isDefined, s"the store has no bucket $bucket")
  }

  Then("{string} reads the object {string} back from its bucket") { (logical: String, obj: String) =>
    readBack("shop", logical, obj)
  }

  Then("the status says that {string} has a bucket, and names it") { (logical: String) =>
    val status = lastStatus.getOrElse(fail("no status was read"))
    assertEquals(status.bucket, Some(bucketOf("shop", logical)))
    assert(status.objectStorage.contains("provisioned"), status.toString)
  }

  Then("no bucket exists for {string}") { (logical: String) =>
    assertEquals(admin.bucket(bucketOf("shop", logical)), None)
  }

  Then("{string} starts with no variable whose name starts with {string}") {
    (logical: String, prefix: String) =>
      awaitReady("shop", real(logical))
      val found = environment("shop", real(logical)).keySet.filter(_.startsWith(prefix))
      assertEquals(found, Set.empty[String])
  }

  Then("the status says that {string} has no bucket") { (logical: String) =>
    val status = statusOf(real(logical)).getOrElse(fail("no status"))
    assertEquals((status.bucket, status.objectStorage), (None, None))
  }

  Then("no instance of {string} starts") { (logical: String) =>
    val name = real(logical)
    waitFor(120.seconds, "the operator reporting why") {
      statusOf(name).exists(_.objectStorage.contains("object storage provisioning failed"))
    }
    // A pod is scheduled, and its container is never created: it names a credential that is not.
    for _ <- 1 to 10 do
      assert(!pods("shop", name).exists(running), "an instance started with no credential")
      Thread.sleep(1000)
  }

  Then("the status says that {string} has no bucket because the installation has no object store") {
    (logical: String) =>
      val status = statusOf(real(logical)).getOrElse(fail("no status"))
      assertEquals(status.objectStorage, Some("object storage provisioning failed"))
      assert(
        status.detail.exists(_.contains("the installation has no object store")),
        status.toString
      )
  }

  Then("the status says that the bucket of {string} is still being made") { (logical: String) =>
    waitFor(120.seconds, "the operator reporting the bucket waiting") {
      statusOf(real(logical)).exists(_.objectStorage.contains("waiting for object storage"))
    }
  }

  Then("the status of {string} is not {string}") { (logical: String, word: String) =>
    for _ <- 1 to 10 do
      val status = statusOf(real(logical)).getOrElse(fail("no status"))
      assertNotEquals(status.lifecycle.toString, word)
      assert(!status.objectStorage.exists(_.contains("failed")), status.toString)
      Thread.sleep(1000)
  }

  Then("the member is refused") { () =>
    assertNotEquals(last.code, 0, last.all)
  }

  Then("the refusal names the limit") { () =>
    assert(last.all.contains("63 character limit"), last.all)
  }

  Then("the refusal names the variable and the secret") { () =>
    assert(last.all.contains("BORROWED") && last.all.contains("-storage"), last.all)
  }

  Then(
    "the refusal says that a descriptor cannot both ask for a bucket and give the variable {string}"
  ) { (variable: String) =>
    assert(last.all.contains(s"cannot be combined with env var '$variable'"), last.all)
  }

  Then("the refusal says that only a bucket the platform made can be reachable from the internet") {
    () =>
      assert(
        last.all.contains(
          "only a bucket the platform made can be reached from outside the cluster"
        ),
        last.all
      )
  }

  Then("the object store refuses {string}") { (_: String) =>
    assertEquals(last.code, 403, last.out)
    assert(last.out.contains("AccessDenied"), last.out)
  }

  Then("the platform is refused") { () =>
    assertEquals(last.code, 403, last.out)
  }

  Then("the storage credential of {string} is the one it had before") { (logical: String) =>
    assertEquals(credentialOf("shop", logical), credentialBefore)
  }

  Then(
    "{string} reads its bucket with a storage credential that is not the one it had before, once it is restarted"
  ) { (logical: String) =>
    val name = real(logical)
    // The credential's generation is on the pod template, so the reissue replaces every instance.
    waitFor(300.seconds, s"every instance of $name started with a new storage credential") {
      val now = pods("shop", name)
      now.nonEmpty && now.forall(running) &&
      now.forall(p => accessKeyIn("shop", name, p).exists(_ != keyBefore._1))
    }
    awaitReady("shop", name)
    assertNotEquals(storageKeyOf("shop", logical)._1, keyBefore._1)
    keep("shop", logical, "after-reissue.txt")
    readBack("shop", logical, "after-reissue.txt")
  }

  Then("the object store refuses the storage credential {string} had before") { (logical: String) =>
    // Refused by the store's own clock once the rotation grace has passed, which the suite sets.
    waitFor(RotationGrace + 120.seconds, "the old storage credential being refused") {
      storeAnswer(keyBefore, bucketOf("shop", logical)) == 403
    }
  }

  Then("the history of {string} says that its storage credential was issued again") {
    (logical: String) =>
      val run = ok(ankka("services", "history", real(logical), "-p", "shop", "-o", "json"))
      assert(run.out.contains("\"storage-credential-reissued\""), run.out)
  }

  Then("the object store still holds the bucket of {string} with the object {string}") {
    (logical: String, obj: String) =>
      assert(admin.bucket(bucketOf("shop", logical)).isDefined)
      assertEquals(objectInStore("shop", logical, obj), Some(s"contents of $obj"))
  }

  Then("the status says that {string} was given the bucket it had before") { (logical: String) =>
    val status = statusOf(real(logical)).getOrElse(fail("no status"))
    assertEquals(status.objectStorage, Some("recovered existing bucket"))
  }

  Then("the status says that {string} has an object store of its own") { (logical: String) =>
    waitFor(120.seconds, "the operator reporting the store as supplied") {
      statusOf(real(logical)).exists(s => s.objectStorage.contains("supplied") && s.bucket.isEmpty)
    }
  }

  Then("the platform makes no storage credential for {string}") { (logical: String) =>
    awaitReady("shop", real(logical))
    assertEquals(
      k8s.secrets().inNamespace(namespace("shop")).withName(Buckets.secret(real(logical))).get(),
      null
    )
    assertEquals(admin.keysNamed(bucketOf("shop", logical)), Vector.empty[String])
  }

  Then("{string} starts with the variable {string} set") { (logical: String, variable: String) =>
    awaitReady("shop", real(logical))
    assertEquals(
      environment("shop", real(logical)).get(variable),
      Some(Buckets.publicEndpoint(BaseDomain, httpsPort))
    )
  }

  Then("the status shows the address of the bucket of {string} on the internet") {
    (logical: String) =>
      val status = statusOf(real(logical)).getOrElse(fail("no status"))
      assertEquals(
        status.bucketAddress,
        Some(Buckets.publicAddress("shop", real(logical), BaseDomain, httpsPort))
      )
  }

  Then("the browser is shown the object {string}") { (obj: String) =>
    assertEquals(browserReply._1, 200, browserReply._3)
    assertEquals(browserReply._3, s"contents of $obj")
  }

  Then("the request does not reach the object store") { () =>
    assertEquals(browserReply._1, 404, browserReply._3)
    assert(!storeAnswered(browserReply), browserReply._3)
  }

  Then("{string} has no variable {string}") { (logical: String, variable: String) =>
    assertEquals(environment("shop", real(logical)).get(variable), None)
  }

  Then("the object store refuses the request") { () =>
    assertEquals(browserReply._1, 403, browserReply._3)
    assert(browserReply._3.contains("AccessDenied"), browserReply._3)
  }

  Then("a request a browser sends to the signed URL does not reach the object store") { () =>
    waitFor(120.seconds, "the bucket's route going") {
      browserReply = browse(signedUrl)
      browserReply._1 == 404
    }
    assert(!storeAnswered(browserReply), browserReply._3)
  }
