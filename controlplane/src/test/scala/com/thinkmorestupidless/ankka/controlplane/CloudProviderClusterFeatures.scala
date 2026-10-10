package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.ServiceStatus
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  Fabric8AnkkaServiceClient,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.crd.{
  AnkkaSerialization,
  AnkkaService,
  Buckets,
  CloudKinds,
  CloudResource,
  CloudSubject
}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.cloud.{
  CloudProviderStack,
  ScriptedCloudProvider,
  ScriptedFulfilment
}
import com.thinkmorestupidless.ankka.operator.{
  CloudSettings,
  Labels,
  Names,
  Operator,
  PkiStack,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.fabric8.kubernetes.api.model.{Container, ObjectMetaBuilder, SecretBuilder}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * `features/cloud-provider/` on k3s (feature 044): the control plane and the operator in this JVM,
 * the operator told the installation's cloud provider is `gcp` and given no store of its own, and
 * the scripted cloud provider answering — on a client minted from the `ankka-cloud-provider`
 * ServiceAccount under the shipped grant, so it can do exactly what any provider may and the API
 * server refuses the rest.
 *
 * Every service is `pause` with no HTTP and no database: what the scenarios assert is on the
 * resources, the Deployment the operator rendered and the scripted provider's records, never on a
 * running program. A feature's `"reports"` is `reports-<n>` here, so what an earlier scenario left
 * in the pretend account — and nothing there is ever deleted — is never mistaken for this one's.
 *
 * With `-Dankka.cloud.external=<kubeconfig>` it starts no k3s and no scripted provider, and runs
 * against that cluster's own provider with the installation's `ANKKA_CLOUD_*` from the environment;
 * a step that reads the scripted provider's records is then skipped, and one that scripts a refusal
 * cannot run. Disable with `-Dankka.cluster.tests=off`.
 */
class CloudProviderClusterFeatures
    extends GherkinSuite("../features/cloud-provider")
    with LogCapturing:

  override val munitTimeout: FiniteDuration = 15.minutes

  private val external: Option[String] = sys.props.get("ankka.cloud.external").filter(_.nonEmpty)

  override def munitIgnore: Boolean =
    external.isEmpty && sys.props.get("ankka.cluster.tests").contains("off")

  override protected def ranElsewhere: Map[String, String] = Map(
    "the cloud provider can write a secret and cannot read one back" ->
      "OperatorClusterSuite, case 41, under the provider's own token",
    "the operator cannot read a storage credential the cloud provider made" ->
      "OperatorClusterSuite, case 41, under the operator's own token",
    "a setting that needs a cloud provider is refused when the installation has none" ->
      "CloudProviderNeededSuite, until features 038, 039, 041 and 042 add those settings",
    "an installation with no cloud provider serves everything itself" ->
      "RenderingGoldenSuite and RenderingUnchangedSuite: no cloud request in any other record",
    "a service whose project secrets are kept in the cloud account asks for an identity and access to its secrets" ->
      "CloudRequestsSuite, until feature 038 writes the request",
    "a project whose project secrets are kept in the cloud account asks for them to be kept in step" ->
      "CloudRequestsSuite and ScriptedCloudProviderSuite, until feature 038 writes the request",
    "a project whose backups are kept in the cloud account asks for a backup bucket and a credential for its database" ->
      "CloudRequestsSuite and ScriptedCloudProviderSuite, until feature 041 writes the requests",
    "a keyring on an installation that names a wrapping key asks to wrap with it" ->
      "CloudRequestsSuite, until feature 042 writes the request",
    "a keyring on an installation that names no wrapping key keeps its own secret" ->
      "CloudProviderNeededSuite, until feature 042 adds the setting",
    "no cloud request is in a cloud's own words" -> "CloudRequestsSuite",
    "a cloud provider for a real cloud is tested with the same features against a real cloud account" ->
      "ankka-gcp's nightly run of this suite with -Dankka.cloud.external",
    "a cloud provider for another cloud needs only its name known to the platform" ->
      "SettingsSuite and CloudRequestsSuite"
  )

  private val K3sImage  = "rancher/k3s:v1.35.1-k3s1"
  private val Image     = "registry.k8s.io/pause:3.9"
  private val Prefix    = "ankka"
  private val Project   = "shop"
  private val Bound     = 30.seconds
  private val Grace     = 20.seconds
  private val Namespace = s"$Prefix-$Project"

  private val cloud: CloudSettings = external match
    case None =>
      CloudSettings("gcp", "scripted-account", "scripted-location", None, Bound, Grace)
    case Some(_) =>
      CloudSettings
        .read((_, variable) => sys.env.get(variable).filter(_.nonEmpty))
        .getOrElse(fail("-Dankka.cloud.external needs ANKKA_CLOUD_PROVIDER and its settings"))

  private lazy val identity = TestIdentity()
  private lazy val Token = identity.token(
    "tester",
    Some("tester@example.test"),
    roles = Set("platform-admin"),
    expiresIn = 3.hours
  )

  private var k3s: K3sContainer                       = null
  private var k8s: KubernetesClient                   = null
  private var providerClient: KubernetesClient        = null
  private var operator: Operator                      = null
  private var fulfilment: ScriptedFulfilment          = null
  private var provider: Option[ScriptedCloudProvider] = None
  private var testKit: AnkkaTestKit                   = null
  private var url: String                             = ""
  private var config: Path                            = null

  /** Every scenario that did not pass, for the last scenario of `providers.feature`. */
  private val failures = java.util.concurrent.ConcurrentLinkedQueue[String]()

  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+ new TestTransform(
      "record what failed",
      test =>
        test.withBody(() =>
          test
            .body()
            .transform { result =>
              if result.isFailure then failures.add(test.name): Unit
              result
            }(using munitExecutionContext)
        )
    )

  override def beforeAll(): Unit =
    if !munitIgnore then
      external match
        case Some(kubeconfig) =>
          k8s = new KubernetesClientBuilder()
            .withConfig(Config.fromKubeconfig(Files.readString(Paths.get(kubeconfig))))
            .withKubernetesSerialization(AnkkaSerialization())
            .build()
        case None =>
          k3s = new K3sContainer(DockerImageName.parse(K3sImage))
          k3s.start()
          k8s = new KubernetesClientBuilder()
            .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
            .withKubernetesSerialization(AnkkaSerialization())
            .build()
          for crd <- Vector("ankkaservice.yaml", "ankkaproject.yaml") do
            k8s.load(getClass.getResourceAsStream(s"/ankka/crd/$crd")).serverSideApply(): Unit
          CloudProviderStack.install(k3s, k8s)
          PkiStack.install(k3s, k8s)
          fulfilment = ScriptedFulfilment(cloud.provider, cloud.account, cloud.location, Grace)
          providerClient = CloudProviderStack.client(k8s, CloudProviderStack.token(k3s))
          startProvider()

      val settings = OperatorSettings.default.copy(resyncInterval = 2.seconds, cloud = Some(cloud))
      operator = new Operator(k8s, settings, ServiceReconciler(k8s, settings))
      operator.start()

      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        progressDeadline = 300.seconds
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
      config = Files.createTempFile("ankka-cloud-provider", ".json")
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
    provider.foreach(_.stop())
    if providerClient != null then providerClient.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  private def startProvider(): Unit =
    if provider.isEmpty && external.isEmpty then
      val p = ScriptedCloudProvider(providerClient, fulfilment)
      p.start()
      provider = Some(p)

  private def stopProvider(): Unit =
    assume(external.isEmpty, "a real provider is not this suite's to stop")
    provider.foreach(_.stop())
    provider = None

  /** The scripted provider's records, which a real provider does not keep. */
  private def scripted: ScriptedFulfilment =
    assume(external.isEmpty, "this step reads the scripted cloud provider's records")
    fulfilment

  // ── the scenario's names ──────────────────────────────────────────────────

  private var scenario: Int            = 0
  private var deployed: Vector[String] = Vector.empty
  private var pending: Option[String]  = None
  private var appliedAt: Instant       = Instant.EPOCH
  private var reason: String           = ""
  private var bucketBefore: String     = ""
  private var issuedBefore: Int        = 0
  private var endedBefore: Int         = 0
  private var keyBefore: String        = ""
  private var reportedAt: Instant      = Instant.EPOCH
  private var startedAt: Instant       = Instant.EPOCH

  private def real(logical: String): String = s"$logical-$scenario"

  /** `reports-storage` in a feature is this scenario's service's storage Secret. */
  private def realSecret(logical: String): String =
    Buckets.cloudSecret(real(logical.stripSuffix("-cloud-storage")))

  override def beforeEach(context: BeforeEach): Unit =
    if !munitIgnore then
      startProvider()
      for name <- deployed if statusOf(name).isDefined do
        ankka("services", "delete", name, "-p", Project): Unit
      deployed = Vector.empty
      scenario += 1
      pending = None
      reason = ""

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
      if !passed then Thread.sleep(500)
    if !passed then fail(s"$what did not happen within $timeout")

  /** Waits until `deadline`, an instant, has passed `check`. */
  private def waitUntil(deadline: Instant, what: String)(check: => Boolean): Unit =
    val left = JDuration.between(Instant.now(), deadline).toMillis.max(0L)
    waitFor(left.millis + 1.second, what)(check)

  private def statusOf(name: String): Option[ServiceStatus] =
    val run = ankka("services", "get", name, "-p", Project, "-o", "json")
    Option.when(run.code == 0)(readFromString[ServiceStatus](run.out))

  private def descriptor(name: String): String =
    s"""{"name":"$name","service":{"image":"$Image","http":false,"database":"none","provisionObjectStorage":true}}"""

  private def apply(name: String): Unit =
    val file = Files.createTempFile("ankka-cloud-provider", ".json")
    try
      Files.writeString(file, descriptor(name)): Unit
      ok(ankka("services", "apply", "-f", file.toString, "-p", Project))
    finally Files.deleteIfExists(file): Unit
    appliedAt = Instant.now()
    if !deployed.contains(name) then deployed = deployed :+ name

  private def request(name: String): Option[CloudResource] =
    Option(k8s.resources(classOf[CloudResource]).inNamespace(Namespace).withName(name).get())

  private def requestOf(logical: String, suffix: String): CloudResource =
    val name = Names.CloudRequest.ofService(real(logical), suffix)
    waitFor(60.seconds, s"the request $name")(request(name).isDefined)
    request(name).get

  private def identityRequest(logical: String) =
    requestOf(logical, Names.CloudRequest.IdentitySuffix)
  private def bucketRequest(logical: String) = requestOf(logical, Names.CloudRequest.BucketSuffix)
  private def credentialRequest(logical: String) =
    requestOf(logical, Names.CloudRequest.StorageCredentialSuffix)

  private def answered(r: CloudResource): Boolean =
    Option(r.getStatus)
      .flatMap(_.observedGeneration)
      .contains(r.getMetadata.getGeneration.longValue)

  private def answeredAs(logical: String, phase: String): Boolean =
    Vector(identityRequest(logical), bucketRequest(logical), credentialRequest(logical))
      .forall(r => answered(r) && r.getStatus.phase == phase)

  private def resource(name: String): Option[AnkkaService] =
    Option(k8s.resources(classOf[AnkkaService]).inNamespace(Namespace).withName(name).get())

  private def developer(name: String): Option[Container] =
    Option(k8s.apps().deployments().inNamespace(Namespace).withName(name).get())
      .flatMap(_.getSpec.getTemplate.getSpec.getContainers.asScala.find(_.getName == name))

  private def literals(c: Container): Map[String, String] =
    c.getEnv.asScala.filter(_.getValue != null).map(e => e.getName -> e.getValue).toMap

  private def secretsFrom(c: Container): Vector[String] =
    Option(c.getEnvFrom).toVector
      .flatMap(_.asScala)
      .flatMap(f => Option(f.getSecretRef).map(_.getName))

  private def secret(name: String) =
    Option(k8s.secrets().inNamespace(Namespace).withName(name).get())

  private def keyIn(name: String): String =
    val data = secret(name).getOrElse(fail(s"no secret $name")).getData.asScala
    new String(
      java.util.Base64.getDecoder.decode(data("ANKKA_S3_ACCESS_KEY")),
      StandardCharsets.UTF_8
    )

  private def deployAndWait(logical: String): Unit =
    apply(real(logical))
    waitFor(120.seconds, s"${real(logical)}'s bucket")(
      statusOf(real(logical))
        .flatMap(_.objectStorage)
        .exists(p => p == "provisioned" || p.startsWith("recovered"))
    )

  // ── Background and setup ──────────────────────────────────────────────────

  Given("an installation whose cloud provider is {string}") { (name: String) =>
    assertEquals(cloud.provider, name)
  }

  Given("the object store of the installation is its cloud account's") { () =>
    // The operator was given a cloud and no store of its own, so every bucket takes the cloud path.
    assert(external.nonEmpty || fulfilment != null)
  }

  Given("no cloud provider is running")(() => stopProvider())

  // ── bucket.feature ────────────────────────────────────────────────────────

  Given("a descriptor for a service {string} in the project {string} that asks for a bucket") {
    (logical: String, project: String) =>
      assertEquals(project, Project)
      pending = Some(real(logical))
  }

  When("a member applies the descriptor") { () =>
    apply(pending.getOrElse(fail("no descriptor")))
  }

  Then("the operator writes an identity request for {string}") { (logical: String) =>
    val r = identityRequest(logical)
    assertEquals(r.getSpec.kind, CloudKinds.Identity)
    assertEquals(r.getSpec.provider, cloud.provider)
    assertEquals(r.getSpec.subject, CloudSubject(Project, real(logical)))
    assertEquals(r.getSpec.parameters, Map("serviceAccount" -> Names.serviceAccount(real(logical))))
    val owner = r.getMetadata.getOwnerReferences.asScala.head
    assertEquals(owner.getKind -> owner.getName, "AnkkaService" -> real(logical))
  }

  Then(
    "the operator writes a bucket request for {string} with the purpose {string}, naming the project {string}, the location of {string} or else the installation's, and what the descriptor asks of the bucket"
  ) { (logical: String, purpose: String, project: String, _: String) =>
    val r = bucketRequest(logical)
    assertEquals(r.getSpec.kind, CloudKinds.Bucket)
    assertEquals(r.getSpec.subject.project, project)
    assertEquals(r.getSpec.parameters("purpose"), purpose)
    // A project names no location of its own until feature 039: the installation's, verbatim.
    assertEquals(r.getSpec.parameters("location"), cloud.location)
    assertEquals(r.getSpec.parameters("versioning"), "false")
    assertEquals(r.getSpec.parameters("corsOrigins"), "")
  }

  Then(
    "the operator writes a bucket credential request for {string} naming that bucket, the cloud identity of {string} and the secret {string}"
  ) { (logical: String, identityOf: String, secretName: String) =>
    val r = credentialRequest(logical)
    assertEquals(r.getSpec.kind, CloudKinds.BucketCredential)
    assertEquals(
      r.getSpec.parameters("bucket"),
      bucketRequest(logical).getStatus.outputs("bucket"),
      "the bucket the provider made"
    )
    assertEquals(
      r.getSpec.parameters("identity"),
      identityRequest(identityOf).getStatus.outputs("identity"),
      "the identity the provider made"
    )
    assertEquals(r.getSpec.parameters("secretName"), realSecret(secretName))
    assertEquals(r.getSpec.credentialGeneration, 1L)
  }

  Given("a bucket request and a bucket credential request for {string}") { (logical: String) =>
    apply(real(logical))
  }

  When("the cloud provider fulfils them") { () =>
    val logical = deployed.last.stripSuffix(s"-$scenario")
    // SC-001: Provisioned within 60 seconds of apply, with the scripted provider.
    waitUntil(appliedAt.plusSeconds(60), "every request answered")(answeredAs(logical, "Ready"))
  }

  Then("each fulfilment is acknowledged and says {string}") { (phase: String) =>
    val logical = deployed.last.stripSuffix(s"-$scenario")
    assert(answeredAs(logical, phase))
  }

  Then("the fulfilment of the bucket request names the bucket as the cloud provider made it") {
    () =>
      val logical = deployed.last.stripSuffix(s"-$scenario")
      val bucket  = bucketRequest(logical).getStatus.outputs("bucket")
      assert(bucket.nonEmpty)
      if external.isEmpty then
        assertEquals(
          bucket,
          com.thinkmorestupidless.ankka.operator.BucketNames
            .name(cloud.account, Project, real(logical))
        )
  }

  Then("the fulfilment of the bucket credential request names the secret {string}") {
    (secretName: String) =>
      val logical = deployed.last.stripSuffix(s"-$scenario")
      assertEquals(
        credentialRequest(logical).getStatus.outputs("secretName"),
        realSecret(secretName)
      )
  }

  Then(
    "{string} starts with the variables {string}, {string}, {string}, {string} and {string} set from the fulfilments and the secret"
  ) {
    (
        logical: String,
        endpoint: String,
        region: String,
        bucket: String,
        access: String,
        secretKey: String
    ) =>
      val name = real(logical)
      waitFor(60.seconds, s"$name's Deployment told of its bucket")(
        developer(name).exists(c => literals(c).contains(bucket))
      )
      val c       = developer(name).get
      val outputs = bucketRequest(logical).getStatus.outputs
      assertEquals(literals(c)(endpoint), outputs("endpoint"))
      assertEquals(literals(c)(region), outputs("region"))
      assertEquals(literals(c)(bucket), outputs("bucket"))
      assertEquals(secretsFrom(c), Vector(Buckets.cloudSecret(name)))
      val held =
        secret(Buckets.cloudSecret(name)).getOrElse(fail("no storage credential")).getData.asScala
      assert(held.contains(access) && held.contains(secretKey), held.keySet.toString)
  }

  Then(
    "the status says that {string} has a bucket, and names the bucket as the cloud provider made it"
  ) { (logical: String) =>
    val bucket = bucketRequest(logical).getStatus.outputs("bucket")
    waitFor(30.seconds, "the status naming the bucket")(
      statusOf(real(logical)).exists(s =>
        s.objectStorage.contains("provisioned") && s.bucket.contains(bucket)
      )
    )
  }

  private def refusing(logical: String, why: String): Unit =
    reason = why
    scripted.failing(
      Names.CloudRequest.ofService(real(logical), Names.CloudRequest.BucketSuffix),
      why
    )
    apply(real(logical))

  Given(
    "a bucket request for {string} that the cloud provider cannot fulfil because the name of the bucket is taken in another account"
  )((logical: String) => refusing(logical, "the name of the bucket is taken in another account"))

  Given(
    "a bucket request for {string} that the cloud provider cannot fulfil because the location is refused"
  ) { (logical: String) =>
    refusing(logical, "the location is refused")
  }

  When("the cloud provider says {string} and gives its reason") { (phase: String) =>
    val logical = deployed.last.stripSuffix(s"-$scenario")
    waitFor(60.seconds, s"the bucket request answered $phase")(
      answered(bucketRequest(logical)) && bucketRequest(logical).getStatus.phase == phase
    )
    assertEquals(bucketRequest(logical).getStatus.detail, Some(reason))
  }

  Then("the status of {string} is {string} with the reason the cloud provider gave, word for word") {
    (logical: String, phase: String) =>
      waitFor(30.seconds, s"${real(logical)} reported $phase")(
        resource(real(logical))
          .flatMap(r => Option(r.getStatus))
          .flatMap(_.objectStorage)
          .exists(s => s.phase == phase && s.detail.contains(reason)) && statusOf(real(logical))
          .flatMap(_.detail)
          .exists(_.contains(s"object storage: $reason"))
      )
  }

  Then("{string} starts with no variable whose name starts with {string}") {
    (logical: String, prefix: String) =>
      val name = real(logical)
      waitFor(60.seconds, s"$name's Deployment")(developer(name).isDefined)
      val c = developer(name).get
      assertEquals(literals(c).keySet.filter(_.startsWith(prefix)), Set.empty[String])
      assertEquals(secretsFrom(c).filter(_.endsWith("-storage")), Vector.empty[String])
  }

  Given("a deployed service {string} with a bucket the cloud provider made") { (logical: String) =>
    deployAndWait(logical)
    bucketBefore = bucketRequest(logical).getStatus.outputs("bucket")
    if external.isEmpty then
      issuedBefore = fulfilment.issued.size
      endedBefore = fulfilment.ended.size
  }

  Given("{string} has since been deleted") { (logical: String) =>
    val name = real(logical)
    ok(ankka("services", "delete", name, "-p", Project))
    waitFor(60.seconds, s"$name's resource and requests going away")(
      resource(name).isEmpty &&
        Vector("identity", "bucket", "storage-credential")
          .forall(s => request(Names.CloudRequest.ofService(name, s)).isEmpty)
    )
  }

  When("a member applies the descriptor for {string} again") { (logical: String) =>
    apply(real(logical))
  }

  Then(
    "the operator writes the same identity request, the same bucket request and the same bucket credential request again"
  ) { () =>
    val logical = deployed.last.stripSuffix(s"-$scenario")
    identityRequest(logical)
    assertEquals(bucketRequest(logical).getSpec.parameters("purpose"), "service")
    waitFor(60.seconds, "the credential request again")(answered(credentialRequest(logical)))
  }

  Then("the cloud provider finds the bucket it made before and says {string}") { (phase: String) =>
    val logical = deployed.last.stripSuffix(s"-$scenario")
    val bucket  = bucketRequest(logical)
    assertEquals(bucket.getStatus.phase, phase)
    assert(bucket.getStatus.recovered)
    assertEquals(bucket.getStatus.outputs("bucket"), bucketBefore)
  }

  Then(
    "the cloud provider offers a storage credential, is told that one is already there, and names the secret that holds it"
  ) { () =>
    val logical    = deployed.last.stripSuffix(s"-$scenario")
    val secretName = Buckets.cloudSecret(real(logical))
    assertEquals(credentialRequest(logical).getStatus.outputs("secretName"), secretName)
    assertEquals(scripted.issued.size, issuedBefore + 1, "one offered")
    assertEquals(
      scripted.ended.drop(endedBefore).map(e => e.secretName -> e.why),
      Vector(secretName -> "conflict"),
      "and the one just made ended, since the Secret was there"
    )
  }

  Then("the status says that {string} was given the bucket it had before") { (logical: String) =>
    waitFor(30.seconds, "the status saying recovered")(
      statusOf(real(logical)).exists(s =>
        s.objectStorage.exists(_.startsWith("recovered")) && s.bucket.contains(bucketBefore)
      )
    )
  }

  // ── credential.feature ────────────────────────────────────────────────────

  Given("a bucket credential request for {string}") { (logical: String) =>
    pending = Some(real(logical))
  }

  Given("no secret {string} exists") { (secretName: String) =>
    assertEquals(secret(realSecret(secretName)), None)
  }

  Given("the secret {string} already exists") { (secretName: String) =>
    k8s
      .secrets()
      .inNamespace(Namespace)
      .resource(
        new SecretBuilder()
          .withMetadata(new ObjectMetaBuilder().withName(realSecret(secretName)).build())
          .withStringData(
            Map("ANKKA_S3_ACCESS_KEY" -> "before", "ANKKA_S3_SECRET_KEY" -> "before").asJava
          )
          .build()
      )
      .create(): Unit
  }

  When("the cloud provider fulfils it") { () =>
    val name    = pending.getOrElse(fail("no request"))
    val logical = name.stripSuffix(s"-$scenario")
    apply(name)
    waitUntil(appliedAt.plusSeconds(60), "the credential answered")(
      answered(credentialRequest(logical)) && credentialRequest(logical).getStatus.phase == "Ready"
    )
  }

  Then(
    "the cloud provider makes a storage credential that reaches the bucket of {string} and no other"
  ) { (logical: String) =>
    val secretName = Buckets.cloudSecret(real(logical))
    assertEquals(scripted.issued.filter(_.secretName == secretName).map(_.generation), Vector(1L))
    assertEquals(
      credentialRequest(logical).getSpec.parameters("bucket"),
      bucketRequest(logical).getStatus.outputs("bucket"),
      "granted on this service's bucket"
    )
    val naming = k8s
      .resources(classOf[CloudResource])
      .inNamespace(Namespace)
      .list()
      .getItems
      .asScala
      .filter(_.getSpec.parameters.get("secretName").contains(secretName))
    assertEquals(naming.size, 1, "one request, one bucket")
  }

  Then("the cloud provider writes it into the secret {string}") { (secretName: String) =>
    assert(keyIn(realSecret(secretName)).startsWith(s"scripted-${realSecret(secretName)}-1"))
  }

  Then("the fulfilment says {string} and names the secret {string}") {
    (phase: String, secretName: String) =>
      val logical = deployed.last.stripSuffix(s"-$scenario")
      val status  = credentialRequest(logical).getStatus
      assertEquals(status.phase, phase)
      assertEquals(status.outputs("secretName"), realSecret(secretName))
  }

  Then("the cloud provider ends the storage credential it had just made") { () =>
    val logical    = deployed.last.stripSuffix(s"-$scenario")
    val secretName = Buckets.cloudSecret(real(logical))
    assertEquals(
      scripted.ended.filter(_.secretName == secretName).map(e => e.generation -> e.why),
      Vector(1L -> "conflict")
    )
  }

  Then("the storage credential of {string} is the one it had before") { (logical: String) =>
    assertEquals(keyIn(Buckets.cloudSecret(real(logical))), "before")
  }

  Given("a deployed service {string} with a storage credential at credential generation {string}") {
    (logical: String, generation: String) =>
      deployAndWait(logical)
      assertEquals(
        credentialRequest(logical).getStatus.credentialGeneration,
        Some(generation.toLong)
      )
      keyBefore = keyIn(Buckets.cloudSecret(real(logical)))
  }

  When(
    "the credential generation of the bucket credential request of {string} is raised to {string}"
  ) { (logical: String, generation: String) =>
    // As a member does (feature 039): each `storage reissue` raises the service's count by one,
    // and the request asks for that count plus one, a provider's generations starting at 1.
    val name  = real(logical)
    val asked = generation.toInt - 1
    while resource(name).exists(_.getSpec.storageCredentialGeneration < asked) do
      val before = resource(name).get.getSpec.storageCredentialGeneration
      ok(ankka("services", "storage", "reissue", name, "-p", Project))
      waitFor(60.seconds, "the credential issued again projected")(
        resource(name).exists(_.getSpec.storageCredentialGeneration > before)
      )
  }

  Then("the cloud provider makes a new storage credential and writes it into the secret {string}") {
    (secretName: String) =>
      waitFor(60.seconds, "a new credential in the same Secret")(
        keyIn(realSecret(secretName)) != keyBefore
      )
      assert(
        scripted.issued.exists(i => i.secretName == realSecret(secretName) && i.generation == 2L)
      )
  }

  Then("the fulfilment says that credential generation {string} is in place") {
    (generation: String) =>
      val logical = deployed.last.stripSuffix(s"-$scenario")
      waitFor(60.seconds, s"generation $generation in place")(
        credentialRequest(logical).getStatus.credentialGeneration.contains(generation.toLong)
      )
      reportedAt = Instant.parse(
        credentialRequest(logical).getStatus.credentialReportedAt.getOrElse(fail("no report time"))
      )
  }

  Then(
    "the instances of {string} are replaced, and the new ones are given the new storage credential"
  ) { (logical: String) =>
    val name = real(logical)
    def annotation(template: io.fabric8.kubernetes.api.model.PodTemplateSpec) =
      Option(template.getMetadata.getAnnotations).flatMap(a =>
        Option(a.get(Labels.StorageCredentialKey))
      )
    waitFor(60.seconds, "a new ReplicaSet for the new credential")(
      k8s
        .apps()
        .replicaSets()
        .inNamespace(Namespace)
        .withLabel("app.kubernetes.io/name", name)
        .list()
        .getItems
        .asScala
        .exists(rs => annotation(rs.getSpec.getTemplate).contains("2"))
    )
    // A restart re-projects the resource, and the control plane owns the count it raised, so
    // the projection carries it unchanged.
    val before = resource(name).get.getSpec.restarts
    ok(ankka("services", "restart", name, "-p", Project))
    waitFor(60.seconds, "the restart projected")(resource(name).exists(_.getSpec.restarts > before))
    assertEquals(resource(name).get.getSpec.storageCredentialGeneration, 1)
  }

  Then(
    "the storage credential of credential generation {string} reaches the bucket until the rotation grace has passed since the fulfilment, and is refused by the bucket afterwards"
  ) { (generation: String) =>
    val logical    = deployed.last.stripSuffix(s"-$scenario")
    val secretName = Buckets.cloudSecret(real(logical))
    def ended      = scripted.ended.filter(e => e.secretName == secretName && e.why == "rotated")
    waitUntil(reportedAt.plusMillis(Grace.toMillis).plusSeconds(30), "the old credential ended")(
      ended.nonEmpty
    )
    val end = ended.head
    assertEquals(end.generation, generation.toLong)
    assert(
      !end.at.isBefore(reportedAt.plusMillis(Grace.toMillis)),
      s"ended at ${end.at}, before the grace from $reportedAt had passed"
    )
  }

  Given(
    "a deployed service {string} with a bucket, a cloud identity and a storage credential the cloud provider made"
  ) { (logical: String) =>
    deployAndWait(logical)
    if external.isEmpty then endedBefore = fulfilment.ended.size
  }

  When("a member deletes {string}") { (logical: String) =>
    ok(ankka("services", "delete", real(logical), "-p", Project))
  }

  Then("the cloud requests of {string} go with it") { (logical: String) =>
    val name = real(logical)
    waitFor(60.seconds, s"$name's requests going away")(
      Vector("identity", "bucket", "storage-credential")
        .forall(s => request(Names.CloudRequest.ofService(name, s)).isEmpty)
    )
  }

  Then(
    "the cloud account still holds the bucket, the cloud identity and the storage credential of {string}"
  ) { (logical: String) =>
    val subject = CloudSubject(Project, real(logical))
    for kind <- Vector(CloudKinds.Bucket, CloudKinds.Identity, CloudKinds.BucketCredential) do
      assert(scripted.holds(kind, subject), s"$kind of $subject was deleted")
    assertEquals(scripted.ended.size, endedBefore, "no credential ended by a removal")
  }

  Then("the secret {string} still exists") { (secretName: String) =>
    assert(secret(realSecret(secretName)).isDefined)
  }

  // ── absent.feature ────────────────────────────────────────────────────────

  Given("a deployed service {string} whose bucket request is not acknowledged") {
    (logical: String) =>
      apply(real(logical))
      val r = bucketRequest(logical)
      assert(!answered(r), "nothing is running to answer it")
  }

  When("the acknowledgement bound passes") { () =>
    val wait = JDuration.between(Instant.now(), appliedAt.plusMillis(Bound.toMillis)).toMillis
    if wait > 0 then Thread.sleep(wait)
  }

  Then("the status of {string} is {string}") { (logical: String, phase: String) =>
    waitUntil(appliedAt.plusMillis(Bound.toMillis).plusSeconds(30), s"$phase reported")(
      resource(real(logical))
        .flatMap(r => Option(r.getStatus))
        .exists(s => s.objectStorage.exists(_.phase == phase) && s.lifecycle == "UpdateInProgress")
    )
  }

  Then("the status says that no cloud provider for {string} has answered") { (name: String) =>
    val logical = deployed.last.stripSuffix(s"-$scenario")
    // SC-004: within the bound and thirty seconds of apply.
    waitUntil(appliedAt.plusMillis(Bound.toMillis).plusSeconds(30), "the absence reported")(
      statusOf(real(logical))
        .flatMap(_.detail)
        .exists(_.contains(s"no provider for $name has answered"))
    )
  }

  Given("a deployed service {string} reported as {string} because no cloud provider has answered") {
    (logical: String, phase: String) =>
      stopProvider()
      apply(real(logical))
      waitUntil(appliedAt.plusMillis(Bound.toMillis).plusSeconds(30), "the absence reported")(
        resource(real(logical))
          .flatMap(r => Option(r.getStatus))
          .exists(s =>
            s.objectStorage
              .exists(o => o.phase == phase && o.detail.exists(_.contains("has answered")))
          )
      )
  }

  When("a cloud provider for {string} starts and fulfils the bucket request of {string}") {
    (name: String, _: String) =>
      assertEquals(name, cloud.provider)
      startedAt = Instant.now()
      startProvider()
  }

  Then("the status of {string} says what the cloud provider answered") { (logical: String) =>
    // SC-004: the status recovers within thirty seconds of a provider starting.
    waitUntil(startedAt.plusSeconds(30), "the status recovered")(
      statusOf(real(logical)).exists(s =>
        s.objectStorage.contains("provisioned") &&
          s.bucket.contains(bucketRequest(logical).getStatus.outputs("bucket"))
      )
    )
  }

  // ── providers.feature ─────────────────────────────────────────────────────

  Given("the scripted cloud provider") { () =>
    assume(external.isEmpty, "against a real provider this scenario is the real provider's")
    assert(provider.isDefined)
  }

  When("the platform's own tests of cloud requests run") { () =>
    // They have: this is the last feature in the directory, and every scenario above ran first.
    assert(scenario > 1)
  }

  Then("they reach no cloud and need no credential of any cloud") { () =>
    assertEquals(fulfilment.reached.get, 0)
    // The one credential the provider holds is its ServiceAccount's token, for this cluster.
    assertEquals(providerClient.getConfiguration.getOauthToken.split('.').length, 3)
  }

  Then("every one of them passes") { () =>
    assertEquals(failures.asScala.toVector, Vector.empty[String])
  }
