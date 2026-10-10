package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.{ServiceLifecycle, ServiceStatus}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  Fabric8AnkkaServiceClient,
  ObjectStoreKind,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.crd.{
  AnkkaSerialization,
  CloudKinds,
  CloudResource,
  CloudSubject
}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.cloud.{
  CloudProviderStack,
  ScriptedCloudProvider,
  ScriptedFulfilment,
  ScriptedStore
}
import com.thinkmorestupidless.ankka.operator.{
  BucketNames,
  CloudSettings,
  ClusterImages,
  GarageStore,
  GcsSettings,
  Names,
  ObjectStoreBackend,
  ObjectStoreStack,
  Operator,
  PkiStack,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, PutObjectRequest}
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * `features/object-storage-gcs/` on k3s (feature 039): an installation whose object store is Google
 * Cloud Storage, with Garage standing in for Google. The control plane and the operator run in this
 * JVM, the operator told its backend is `gcs` with the prefix `t` and the cloud provider `gcp`; the
 * scripted cloud provider answers on a client minted from the shipped `cloud-provider` grant, in
 * its Garage-backed mode, so a bucket it reports is a real bucket in the installation's Garage
 * under the contract's name and a credential it writes is a real Garage key. A service on the cloud
 * path therefore keeps and reads objects for real, with the client it would use against Google.
 * Garage is installed too, as an installation moving its buckets has both.
 *
 * Google's own behaviour (versions, retention, workload identity, IAM) cannot be stood in for and
 * is in `ranOutside`, each with the test that proves it against a real bucket. Every scenario has
 * services of its own: a feature's `"kyc"` is `kyc-<n>` here.
 *
 * Disable with `-Dankka.cluster.tests=off`, which also skips building the sample's image.
 */
class ObjectStorageGcsClusterFeatures
    extends GherkinSuite("../features/object-storage-gcs")
    with LogCapturing:

  override val munitTimeout: FiniteDuration = 15.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  override protected def ranElsewhere: Map[String, String] = Map(
    "neither the operator nor the cloud provider can read a storage credential back" ->
      "OperatorClusterSuite, case 41, and CloudProviderClusterFeatures under the provider's own token",
    "the operator holds no Google client, credential or permission and reads only the cloud provider's status" ->
      "OperatorClusterSuite's minted-token cases and CloudProviderClusterFeatures",
    // This installation's object store is Google Cloud Storage; the refusal is the control plane's.
    "a descriptor that declines a storage credential is refused on an installation whose object store is Garage" ->
      "ServiceProjectionSuite, which projects the same descriptor against an installation on Garage"
  )

  override protected def ranOutside: Map[String, String] = Map(
    "a service reaches its bucket through its workload identity with no storage credential" ->
      "Google's workload identity: ankka-gcp's nightly run",
    "a service reaching Google Cloud Storage through its workload identity is refused by another service's bucket" ->
      "Google's IAM: ankka-gcp's nightly run",
    "a storage credential is refused by another service's Google Cloud Storage bucket" ->
      "Google's IAM: ankka-gcp's nightly run",
    "the cloud provider reaches Google Cloud through its workload identity and holds no Google key" ->
      "ankka-gcp's own deployment",
    "a service's cloud identity is granted on its own bucket and on nothing else" ->
      "Google's IAM: ankka-gcp's nightly run",
    "an object that is overwritten can be read as it was before" ->
      "GcsCompatibilitySuite, against a real bucket (the gcs workflow)",
    "an object that is deleted can be read back as its noncurrent version" ->
      "GcsCompatibilitySuite, against a real bucket (the gcs workflow)",
    "deleting every version of an object leaves none listed" ->
      "GcsCompatibilitySuite, against a real bucket (the gcs workflow)",
    "no bucket is made with a retention policy" -> "ankka-gcp's nightly run",
    "a request without a signed URL is refused by every bucket" ->
      "GcsCompatibilitySuite, against a real bucket (the gcs workflow)"
  )

  private val K3sImage    = "rancher/k3s:v1.35.1-k3s1"
  private val Tag         = com.thinkmorestupidless.ankka.core.BuildInfo.version.replace('+', '-')
  private val SampleImage = s"sample-shopping-cart:$Tag"
  private val MoverImage  = s"ankka-storage-mover:$Tag"
  private val Prefix      = "ankka"
  private val NamePrefix  = "t"
  private val Projects    = Vector("shop", "shop-a", "casino", "bank")
  private val WrappingKey =
    "projects/scripted-account/locations/europe-west2/keyRings/ankka/cryptoKeys/buckets"
  private val Grace = 20.seconds

  private val cloud =
    CloudSettings("gcp", "scripted-account", "europe-west2", None, 30.seconds, Grace)

  private lazy val identity = TestIdentity()
  private lazy val Token = identity.token(
    "tester",
    Some("tester@example.test"),
    roles = Set("platform-admin"),
    expiresIn = 3.hours
  )

  private var k3s: K3sContainer                                        = null
  private var k8s: KubernetesClient                                    = null
  private var providerClient: KubernetesClient                         = null
  private var operator: Operator                                       = null
  private var operatorSettings: OperatorSettings                       = null
  private var store: ObjectStoreStack.Installed                        = null
  private var admin: GarageStore                                       = null
  private var fulfilment: ScriptedFulfilment                           = null
  private var provider: Option[ScriptedCloudProvider]                  = None
  private var testKit: AnkkaTestKit                                    = null
  private var url: String                                              = ""
  private var config: Path                                             = null
  private var s3Forward: io.fabric8.kubernetes.client.LocalPortForward = null

  private def repoRoot: Path =
    var dir = Paths.get("").toAbsolutePath
    while !Files.exists(dir.resolve("build.sbt")) do dir = dir.getParent
    dir

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()
      ClusterImages.importInto(k3s, SampleImage)
      ClusterImages.importInto(k3s, MoverImage)
      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      for crd <- Vector("ankkaservice.yaml", "ankkaproject.yaml") do
        k8s.load(getClass.getResourceAsStream(s"/ankka/crd/$crd")).serverSideApply(): Unit
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
      store = ObjectStoreStack.install(k3s, k8s, repoRoot)
      admin = GarageStore(store.settings.adminUrl, store.settings.adminToken)
      // The store's S3 port from the host, where a browser and an old credential are tried.
      s3Forward = k8s.services().inNamespace("garage-system").withName("garage").portForward(3900)

      // The cloud provider, as any provider runs: under the shipped grant, answering from Garage.
      CloudProviderStack.install(k3s, k8s)
      fulfilment = ScriptedFulfilment(
        cloud.provider,
        cloud.account,
        cloud.location,
        Grace,
        store = ScriptedStore.InGarage(admin, store.settings.endpoint, store.settings.region)
      )
      providerClient = CloudProviderStack.client(k8s, CloudProviderStack.token(k3s))
      val p = ScriptedCloudProvider(providerClient, fulfilment)
      p.start()
      provider = Some(p)

      operatorSettings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        objectStore = Some(store.settings),
        objectStoreBackend = Some(ObjectStoreBackend.Gcs),
        gcs = Some(GcsSettings(NamePrefix, GcsSettings.DefaultSoftDeleteDays)),
        cloud = Some(cloud),
        rotationGrace = Grace,
        storageMoverImage = MoverImage
      )
      startOperator(operatorSettings)

      // The control plane holds the installation's store as the operator does: Google Cloud Storage.
      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        progressDeadline = 300.seconds,
        objectStore = ObjectStoreKind.Gcs,
        objectStorePrefix = Some(NamePrefix),
        cloudProvider = Some(cloud.provider)
      )
      val projector = ServiceProjector.withClient(
        deployConfig,
        new Fabric8AnkkaServiceClient(k8s, Prefix, resyncMillis = 2000L)
      )
      val server = HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(identity.acl(), deployConfig, auth = Some(identity.config()))*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
      config = Files.createTempFile("ankka-object-storage-gcs", ".json")
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
    provider.foreach(_.stop())
    if providerClient != null then providerClient.close()
    if s3Forward != null then s3Forward.close()
    if store != null then store.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  private def startOperator(settings: OperatorSettings): Unit =
    if operator != null then operator.close()
    operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
    operator.start()

  // ── the scenario's names ──────────────────────────────────────────────────

  private var scenario: Int                      = 0
  private var deployed: Vector[(String, String)] = Vector.empty
  private var projectOf: Map[String, String]     = Map.empty
  private var pending: Option[(String, String)]  = None // (project, descriptor JSON)
  private var pendingName: String                = ""
  private var unreachable: Boolean               = false
  private var lastStatus: Option[ServiceStatus]  = None
  private var operatorChanged: Boolean           = false
  private var locationsNamed: Set[String]        = Set.empty
  private var keyBefore: (String, String)        = ("", "")
  private var reportedAt: Instant                = Instant.EPOCH
  private var preflightAllowed: Boolean          = false

  /** This scenario's name for a service a feature calls `logical`. */
  private def real(logical: String): String = s"$logical-$scenario"

  private def projectFor(logical: String): String = projectOf.getOrElse(logical, "shop")

  override def beforeEach(context: BeforeEach): Unit =
    if !munitIgnore then
      fulfilment.proceed()
      if operatorChanged then
        startOperator(operatorSettings)
        operatorChanged = false
      for project <- locationsNamed do ankka("projects", "location", "clear", "-p", project): Unit
      locationsNamed = Set.empty
      for (project, name) <- deployed if statusOf(name, project).isDefined do
        ankka("services", "delete", name, "-p", project): Unit
      deployed = Vector.empty
      projectOf = Map.empty
      scenario += 1
      pending = None
      pendingName = ""
      unreachable = false
      lastStatus = None
      keyBefore = ("", "")
      reportedAt = Instant.EPOCH
      preflightAllowed = false

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

  /** A descriptor as it would be on an installation whose object store is Garage: nothing more. */
  private def descriptor(name: String): String =
    s"""{"name":"$name","service":{"image":"$SampleImage","provisionObjectStorage":true}}"""

  /**
   * A descriptor that asks for more of its bucket: reachable from the internet, origins, no key.
   */
  private def descriptorWith(
      name: String,
      expose: Boolean = false,
      origins: Vector[String] = Vector.empty,
      credential: Boolean = true
  ): String =
    val fields = Vector(
      Some(s""""image":"$SampleImage""""),
      Some(""""provisionObjectStorage":true"""),
      Option.when(expose)(""""exposeObjectStorage":true"""),
      Option.when(origins.nonEmpty)(
        origins.map(o => s""""$o"""").mkString(""""objectStorageOrigins":[""", ",", "]")
      ),
      Option.when(!credential)(""""objectStorageCredential":false""")
    ).flatten
    s"""{"name":"$name","service":{${fields.mkString(",")}}}"""

  private def applyJson(project: String, json: String): Run =
    val file = Files.createTempFile("ankka-object-storage-gcs", ".json")
    try
      Files.writeString(file, json): Unit
      ankka("services", "apply", "-f", file.toString, "-p", project)
    finally Files.deleteIfExists(file): Unit

  private def bucketRequestName(name: String): String =
    Names.CloudRequest.ofService(name, Names.CloudRequest.BucketSuffix)

  private def apply(project: String, logical: String, json: Option[String] = None): String =
    val name = real(logical)
    if unreachable then
      fulfilment.stalling(
        bucketRequestName(name),
        "Google Cloud Storage cannot be reached at present"
      )
    ok(applyJson(project, json.getOrElse(descriptor(name))))
    deployed = deployed :+ (project  -> name)
    projectOf = projectOf + (logical -> project)
    name

  private def credentialRequestName(name: String): String =
    Names.CloudRequest.ofService(name, Names.CloudRequest.StorageCredentialSuffix)

  private def request(project: String, name: String): Option[CloudResource] =
    Option(
      k8s.resources(classOf[CloudResource]).inNamespace(namespace(project)).withName(name).get()
    )

  /** The key and secret one instance of a service was started with, from its own environment. */
  private def keyInPod(logical: String): (String, String) =
    val env = environment(logical)
    (env("ANKKA_S3_ACCESS_KEY"), env("ANKKA_S3_SECRET_KEY"))

  /** The store's answer to a listing of `bucket` with `key`, from the host: 200, or its refusal. */
  private def storeAnswer(key: (String, String), bucket: String): Int =
    val client = ServicePods.s3Client(s"http://127.0.0.1:${s3Forward.getLocalPort}", "garage", key)
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

  /** What `curl` from the host was answered: the status, and the headers in lower case. */
  private def curl(args: String*): (Int, Map[String, String]) =
    val command = Vector("curl", "-sS", "-m", "20", "-D", "-", "-o", "/dev/null") ++ args
    val process = new ProcessBuilder(command*).redirectErrorStream(false).start()
    val output  = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    process.waitFor()
    val heads  = output.split("\r\n\r\n").toVector.filter(_.startsWith("HTTP/"))
    val head   = heads.lastOption.getOrElse("").split("\r\n").toVector
    val status = head.headOption.flatMap(_.split(" ").lift(1)).flatMap(_.toIntOption).getOrElse(0)
    val headers = head
      .drop(1)
      .flatMap(line =>
        line.split(":", 2) match
          case Array(k, v) => Some(k.trim.toLowerCase -> v.trim)
          case _           => None
      )
      .toMap
    (status, headers)

  /**
   * A URL the service signs for keeping `obj`, with the credential it was started with, for the
   * store as a browser reaches it: here, through the forward from the host.
   */
  private def signForKeeping(logical: String, obj: String): String =
    val env = environment(logical)
    val presigner = S3Presigner
      .builder()
      .endpointOverride(URI.create(s"http://127.0.0.1:${s3Forward.getLocalPort}"))
      .region(Region.of(env("ANKKA_S3_REGION")))
      .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
      .credentialsProvider(
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(env("ANKKA_S3_ACCESS_KEY"), env("ANKKA_S3_SECRET_KEY"))
        )
      )
      .build()
    try
      presigner
        .presignPutObject(
          PutObjectPresignRequest
            .builder()
            .signatureDuration(java.time.Duration.ofMinutes(30))
            .putObjectRequest(
              PutObjectRequest.builder().bucket(env("ANKKA_S3_BUCKET")).key(obj).build()
            )
            .build()
        )
        .url()
        .toString
    finally presigner.close()

  /** A browser on `origin` asks first, as a browser must, and sends only when it is let. */
  private def browserSends(origin: String, logical: String, obj: String): Unit =
    val url = signForKeeping(logical, obj)
    val (status, headers) = curl(
      "-X",
      "OPTIONS",
      "-H",
      s"Origin: $origin",
      "-H",
      "Access-Control-Request-Method: PUT",
      url
    )
    preflightAllowed = status == 200 &&
      headers.get("access-control-allow-origin").exists(o => o == origin || o == "*")
    if preflightAllowed then
      val (put, _) =
        curl("-X", "PUT", "-H", s"Origin: $origin", "--data-binary", s"contents of $obj", url)
      assertEquals(put, 200, s"the signed PUT from $origin")

  private def awaitReady(project: String, name: String): Unit =
    waitFor(360.seconds, s"$project/$name being Ready") {
      statusOf(name, project).exists(s =>
        s.lifecycle == ServiceLifecycle.Ready && s.confirmed &&
          s.objectStorage.exists(p => p == "provisioned" || p.startsWith("recovered"))
      ) && pods(project, name).exists(ServicePods.running)
    }

  private def deploy(project: String, logical: String): String =
    val name = apply(project, logical)
    awaitReady(project, name)
    name

  private def environment(logical: String): Map[String, String] =
    servicePods.environment(projectFor(logical), real(logical))

  /** The name the scripted provider reported for a service's bucket. */
  private def reportedBucket(logical: String): String =
    fulfilment
      .outputsFor(CloudKinds.Bucket, CloudSubject(projectFor(logical), real(logical)))
      .flatMap(_.get("bucket"))
      .getOrElse(fail(s"the cloud provider reported no bucket for ${real(logical)}"))

  private def bucketRequest(logical: String): CloudResource =
    Option(
      k8s
        .resources(classOf[CloudResource])
        .inNamespace(namespace(projectFor(logical)))
        .withName(bucketRequestName(real(logical)))
        .get()
    ).getOrElse(fail(s"no bucket request for ${real(logical)}"))

  // ── Given ─────────────────────────────────────────────────────────────────

  Given("an installation whose object store is Google Cloud Storage") { () =>
    assertEquals(operatorSettings.bucketBackend, Some(ObjectStoreBackend.Gcs))
  }

  Given("a descriptor for a service {string} that asks for a bucket") { (logical: String) =>
    pending = Some("shop" -> descriptor(real(logical)))
    pendingName = logical
  }

  Given("a deployed service {string} with a bucket") { (logical: String) =>
    deploy("shop", logical): Unit
  }

  Given("a deployed service {string} in the project {string} with a bucket") {
    (logical: String, project: String) =>
      deploy(project, logical): Unit
  }

  Given(
    "a bucket named as the cloud provider would name the bucket of {string}, held by someone outside the installation"
  ) { (logical: String) =>
    val name = BucketNames.name(NamePrefix, "shop", real(logical))
    fulfilment.failing(
      bucketRequestName(real(logical)),
      s"the bucket name $name is held by another customer of the cloud"
    )
  }

  Given("Google Cloud Storage cannot be reached at present") { () =>
    unreachable = true
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("a member applies the descriptor") { () =>
    val (project, _) = pending.getOrElse(fail("no descriptor"))
    apply(project, pendingName): Unit
  }

  When(
    "a member applies a descriptor for the service {string} in the project {string} that asks for a bucket"
  ) { (logical: String, project: String) =>
    deploy(project, logical): Unit
  }

  When(
    "{string} keeps the object {string} in its bucket with what its variables say, through the client it used against Garage"
  ) { (logical: String, obj: String) =>
    val bucket = environment(logical)("ANKKA_S3_BUCKET")
    val (code, body) = servicePods.s3(
      projectFor(logical),
      real(logical),
      "PUT",
      s"/$bucket/$obj",
      Some(s"contents of $obj")
    )
    assertEquals(code, 200, body)
  }

  When("a member reads the status of {string}") { (logical: String) =>
    lastStatus = statusOf(real(logical), projectFor(logical))
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  Then(
    "{string} starts with the variables {string}, {string}, {string}, {string} and {string} set"
  ) { (logical: String, a: String, b: String, c: String, d: String, e: String) =>
    awaitReady(projectFor(logical), real(logical))
    val env = environment(logical)
    for variable <- Vector(a, b, c, d, e) do
      assert(env.get(variable).exists(_.nonEmpty), s"$variable is not set: ${env.keySet}")
  }

  Then(
    "the variable {string} names a bucket the cloud provider made for {string} in Google Cloud Storage"
  ) { (variable: String, logical: String) =>
    val named = environment(logical)(variable)
    assertEquals(named, reportedBucket(logical))
    assertEquals(named, BucketNames.name(NamePrefix, projectFor(logical), real(logical)))
    assert(admin.bucket(named).isDefined, s"no bucket $named in the store standing in for Google")
  }

  Then(
    "the descriptor is the one {string} would have on an installation whose object store is Garage"
  ) { (logical: String) =>
    // Nothing of Google's: the image and the ask for a bucket, as on Garage.
    assertEquals(
      pending.map(_._2),
      Some(s"""{"name":"${real(
          logical
        )}","service":{"image":"$SampleImage","provisionObjectStorage":true}}""")
    )
  }

  Then("{string} reads the object {string} back from its bucket") {
    (logical: String, obj: String) =>
      val bucket       = environment(logical)("ANKKA_S3_BUCKET")
      val (code, body) = servicePods.s3(projectFor(logical), real(logical), "GET", s"/$bucket/$obj")
      assertEquals(code, 200, body)
      assertEquals(body, s"contents of $obj")
  }

  Then(
    "the status says that {string} has a bucket in Google Cloud Storage, and names it as the cloud provider reported it"
  ) { (logical: String) =>
    val status = lastStatus.getOrElse(fail("no status"))
    assertEquals(status.objectStore, Some("gcs"))
    assertEquals(status.bucket, Some(reportedBucket(logical)))
  }

  Then(
    "the status names the location of the bucket of {string} as the cloud provider reported it"
  ) { (logical: String) =>
    val reported = Option(bucketRequest(logical).getStatus).map(_.location).filter(_.nonEmpty)
    assert(reported.isDefined, "the cloud provider reported no location")
    assertEquals(lastStatus.flatMap(_.bucketLocation), reported)
  }

  Then("the status says that the bucket of {string} is {string}, naming the bucket") {
    (logical: String, phase: String) =>
      assertEquals(phase, "Failed")
      val name = BucketNames.name(NamePrefix, "shop", real(logical))
      waitFor(120.seconds, "the operator reporting the bucket failed") {
        statusOf(real(logical)).exists(s =>
          s.objectStorage.contains("object storage provisioning failed") &&
            s.detail.exists(_.contains(name))
        )
      }
  }

  Then("nothing of {string} is granted on that bucket") { (logical: String) =>
    val name = BucketNames.name(NamePrefix, "shop", real(logical))
    assert(admin.bucket(name).isEmpty, s"the refused bucket $name was made")
    assert(
      !fulfilment.issued.exists(_.secretName.startsWith(real(logical))),
      s"a credential was issued for ${real(logical)}"
    )
  }

  Then("no instance of {string} starts") { (logical: String) =>
    val name = real(logical)
    for _ <- 1 to 15 do
      assert(!pods("shop", name).exists(ServicePods.running), "an instance started")
      Thread.sleep(1000)
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

  Then("the bucket of {string} is not the bucket of {string}") { (first: String, second: String) =>
    val a = environment(first)("ANKKA_S3_BUCKET")
    val b = environment(second)("ANKKA_S3_BUCKET")
    assertNotEquals(a, b)
  }

  Then("the status of each names its own bucket") { () =>
    for (project, name) <- deployed do
      val logical = projectOf.collectFirst {
        case (l, p) if p == project && real(l) == name => l
      }.get
      assertEquals(
        statusOf(name, project).flatMap(_.bucket),
        Some(environment(logical)("ANKKA_S3_BUCKET")),
        name
      )
  }

  // ── US2: a credential issued again (isolation.feature) ─────────────────────

  When("a member asks for the storage credential of {string} to be issued again") {
    (logical: String) =>
      keyBefore = keyInPod(logical)
      ok(ankka("services", "storage", "reissue", real(logical), "-p", projectFor(logical))): Unit
  }

  /** Waits for the provider to report the second credential in place, and keeps when it did. */
  private def awaitReissued(logical: String): Unit =
    val name = credentialRequestName(real(logical))
    waitFor(120.seconds, s"$name reporting generation 2") {
      request(projectFor(logical), name)
        .flatMap(r => Option(r.getStatus))
        .exists(_.credentialGeneration.contains(2L))
    }
    reportedAt = Instant.parse(
      request(projectFor(logical), name).get.getStatus.credentialReportedAt
        .getOrElse(fail("no report time"))
    )

  Then("{string} is restarted once the fulfilment says the new storage credential is in place") {
    (logical: String) =>
      awaitReissued(logical)
      val (project, name) = (projectFor(logical), real(logical))
      waitFor(300.seconds, s"$name's instances on the new storage credential") {
        val running = pods(project, name).filter(ServicePods.running)
        running.nonEmpty && running.forall(p =>
          Option(p.getMetadata.getAnnotations)
            .flatMap(a =>
              Option(a.get(com.thinkmorestupidless.ankka.operator.Labels.StorageCredentialKey))
            )
            .contains("2") &&
            servicePods.accessKeyIn(project, name, p).exists(_ != keyBefore._1)
        )
      }
      awaitReady(project, name)
  }

  Then(
    "{string} reads its bucket with a storage credential that is not the one it had before, once it is restarted"
  ) { (logical: String) =>
    assertNotEquals(keyInPod(logical)._1, keyBefore._1)
    val bucket = environment(logical)("ANKKA_S3_BUCKET")
    val (code, body) = servicePods.s3(
      projectFor(logical),
      real(logical),
      "PUT",
      s"/$bucket/after-reissue.txt",
      Some("after")
    )
    assertEquals(code, 200, body)
  }

  Then(
    "the storage credential {string} had before reaches its bucket until the rotation grace has passed since the fulfilment, and Google Cloud Storage refuses it afterwards"
  ) { (logical: String) =>
    val bucket = reportedBucket(logical)
    val ends   = reportedAt.plusMillis(Grace.toMillis)
    // While the grace runs, with a margin for the clocks: still served.
    if Instant.now().isBefore(ends.minusSeconds(5)) then
      assertEquals(storeAnswer(keyBefore, bucket), 200)
    while Instant.now().isBefore(ends) do Thread.sleep(500)
    waitFor(60.seconds, "the old storage credential refused")(storeAnswer(keyBefore, bucket) == 403)
  }

  Then("the history of {string} says that its storage credential was issued again") {
    (logical: String) =>
      val run =
        ok(ankka("services", "history", real(logical), "-p", projectFor(logical), "-o", "json"))
      assert(run.out.contains("\"storage-credential-reissued\""), run.out)
  }

  Given(
    "a deployed service {string} with a bucket, whose storage credential a member has asked to be issued again"
  ) { (logical: String) =>
    deploy("shop", logical): Unit
    keyBefore = keyInPod(logical)
    ok(ankka("services", "storage", "reissue", real(logical), "-p", "shop")): Unit
    awaitReissued(logical)
  }

  Given(
    "an instance of {string} that has not been replaced when the rotation grace has passed since the fulfilment"
  ) { (_: String) =>
    // The instance is the credential it was started with, read from it before the reissue: what
    // happens to its pod after that changes nothing the store decides.
    val ends = reportedAt.plusMillis(Grace.toMillis)
    while Instant.now().isBefore(ends) do Thread.sleep(500)
  }

  When("that instance reads the bucket of {string} with the storage credential it was given") {
    (_: String) => ()
  }

  Then("Google Cloud Storage refuses it") { () =>
    val bucket = reportedBucket(projectOf.keys.headOption.getOrElse(fail("no service")))
    waitFor(60.seconds, "the old storage credential refused")(storeAnswer(keyBefore, bucket) == 403)
  }

  // ── US5: a service that declines a storage credential (keyless.feature) ────

  Given(
    "a descriptor for a service {string} that asks for a bucket and declines a storage credential"
  ) { (logical: String) =>
    pending = Some("shop" -> descriptorWith(real(logical), credential = false))
    pendingName = logical
  }

  Then("{string} starts with no variable {string} and no variable {string}") {
    (logical: String, a: String, b: String) =>
      awaitReady(projectFor(logical), real(logical))
      val env = environment(logical)
      assert(!env.contains(a) && !env.contains(b), env.keySet.toString)
      for variable <- Vector("ANKKA_S3_ENDPOINT", "ANKKA_S3_REGION", "ANKKA_S3_BUCKET") do
        assert(env.get(variable).exists(_.nonEmpty), s"$variable is not set")
  }

  Then("no storage credential is issued for {string}") { (logical: String) =>
    val name = real(logical)
    assertEquals(request(projectFor(logical), credentialRequestName(name)), None)
    assert(!fulfilment.issued.exists(_.secretName.startsWith(name)), fulfilment.issued.toString)
  }

  // ── US3: retention, the wrapping key and the location (retention.feature) ──

  Given(
    "a deployed service {string} with a bucket, on an installation that keeps a deleted object for {string}"
  ) { (logical: String, days: String) =>
    assertEquals(operatorSettings.gcs.map(g => s"${g.softDeleteDays} days"), Some(days))
    deploy("shop", logical): Unit
  }

  Then("the status says that a deleted object of {string} can still be recovered for {string}") {
    (_: String, days: String) =>
      assertEquals(lastStatus.flatMap(_.softDeleteDays).map(d => s"$d days"), Some(days))
  }

  Given("the installation names a wrapping key for its buckets") { () =>
    startOperator(operatorSettings.copy(cloud = Some(cloud.copy(kmsKey = Some(WrappingKey)))))
    operatorChanged = true
  }

  Then("the bucket of {string} is encrypted with the installation's wrapping key") {
    (logical: String) =>
      // The request is what the provider is asked; Garage, standing in, encrypts with nothing.
      waitFor(120.seconds, "the bucket asked for with the installation's key") {
        request(projectFor(logical), bucketRequestName(real(logical)))
          .exists(_.getSpec.parameters.get("kmsKey").contains(WrappingKey))
      }
  }

  Given("the installation names the location {string} for its buckets") { (location: String) =>
    assertEquals(cloud.location, location)
  }

  Given(
    "a descriptor for a service {string} in the project {string} that asks for a bucket, where {string} names no location"
  ) { (logical: String, project: String, _: String) =>
    ankka("projects", "location", "clear", "-p", project): Unit
    pending = Some(project -> descriptor(real(logical)))
    pendingName = logical
  }

  Given("a descriptor for a service {string} in the project {string} that asks for a bucket") {
    (logical: String, project: String) =>
      pending = Some(project -> descriptor(real(logical)))
      pendingName = logical
  }

  Given("a member has named the location {string} for the project {string}") {
    (location: String, project: String) =>
      ok(ankka("projects", "location", "set", location, "-p", project)): Unit
      locationsNamed = locationsNamed + project
      // The operator reads it from the project's resource: the bucket is asked for once, there.
      waitFor(60.seconds, s"$project's resource naming $location") {
        Option(
          k8s
            .resources(classOf[com.thinkmorestupidless.ankka.crd.AnkkaProject])
            .inNamespace(namespace(project))
            .withName(project)
            .get()
        ).flatMap(p => Option(p.getSpec)).flatMap(_.bucketLocation).contains(location)
      }
  }

  Then("the bucket of {string} is in the location {string}") { (logical: String, location: String) =>
    waitFor(120.seconds, s"the bucket of ${real(logical)} made in $location") {
      request(projectFor(logical), bucketRequestName(real(logical)))
        .flatMap(r => Option(r.getStatus))
        .exists(_.location == location)
    }
  }

  Then("the status of {string} names the location {string}") { (logical: String, location: String) =>
    waitFor(120.seconds, s"the status of ${real(logical)} naming $location") {
      statusOf(real(logical), projectFor(logical)).flatMap(_.bucketLocation).contains(location)
    }
  }

  // ── US4: a bucket reachable from a browser (reachable.feature) ─────────────

  Given("a descriptor for a service {string} that asks for a bucket reachable from the internet") {
    (logical: String) =>
      pending = Some("shop" -> descriptorWith(real(logical), expose = true))
      pendingName = logical
  }

  Then(
    "{string} starts with the variable {string} set, naming its bucket in Google Cloud Storage"
  ) { (logical: String, variable: String) =>
    awaitReady(projectFor(logical), real(logical))
    val endpoint = request(projectFor(logical), bucketRequestName(real(logical)))
      .flatMap(r => Option(r.getStatus))
      .flatMap(_.outputs.get("endpoint"))
      .getOrElse(fail("the provider answered no endpoint"))
    assertEquals(environment(logical).get(variable), Some(endpoint))
  }

  Then("the status shows the address of the bucket of {string} on the internet") {
    (logical: String) =>
      val endpoint = environment(logical)("ANKKA_S3_PUBLIC_ENDPOINT")
      waitFor(60.seconds, "the status showing the bucket's address") {
        statusOf(real(logical), projectFor(logical))
          .flatMap(_.bucketAddress)
          .contains(s"${endpoint.stripSuffix("/")}/${reportedBucket(logical)}")
      }
  }

  Given(
    "a deployed service {string} whose bucket is reachable from the internet, whose descriptor names the origin {string}"
  ) { (logical: String, origin: String) =>
    apply(
      "shop",
      logical,
      Some(descriptorWith(real(logical), expose = true, origins = Vector(origin)))
    ): Unit
    awaitReady("shop", real(logical))
    // The provider sets the bucket's rule from the request; nothing the service does.
    waitFor(60.seconds, "the bucket admitting the origin") {
      admin.bucket(reportedBucket(logical)).exists(_.corsOrigins.contains(origin))
    }
  }

  When(
    "a browser on the origin {string} sends the object {string} to a signed URL that {string} made for keeping {string}"
  ) { (origin: String, obj: String, logical: String, _: String) =>
    browserSends(origin, logical, obj)
  }

  When(
    "a browser on the hostname of {string} sends the object {string} to a signed URL that {string} made for keeping {string}"
  ) { (named: String, obj: String, logical: String, _: String) =>
    // The service's own hostname, which no descriptor here names as an origin.
    browserSends(s"https://${real(named)}-shop.example.test", logical, obj)
  }

  Then("the browser is refused") { () =>
    assert(!preflightAllowed, "the store let the browser send")
  }

  Then("the bucket of {string} does not hold the object {string}") {
    (logical: String, obj: String) =>
      val client = ServicePods.s3Client(
        s"http://127.0.0.1:${s3Forward.getLocalPort}",
        "garage",
        keyInPod(logical)
      )
      try
        val held =
          try
            client.getObjectAsBytes(
              GetObjectRequest.builder().bucket(reportedBucket(logical)).key(obj).build()
            ): Unit
            true
          catch case _: software.amazon.awssdk.services.s3.model.S3Exception => false
        assert(!held, s"the bucket holds $obj")
      finally client.close()
  }
