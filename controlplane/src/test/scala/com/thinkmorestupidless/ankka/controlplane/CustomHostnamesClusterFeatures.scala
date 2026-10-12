package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.{HistoryEntry, ProofLookup, ServiceStatus}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  Fabric8AnkkaServiceClient,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  AcmeStack,
  ClusterImages,
  GatewayStack,
  Operator,
  PkiStack,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import io.grpc.{Grpc, ManagedChannel, TlsChannelCredentials}
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName
import shoppingcart.v1.cart.*

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * `features/exposure/custom-hostnames.feature` on k3s (feature 045): the operator, the gateway,
 * cert-manager and an ACME authority (Pebble, `AcmeStack`) doing what a cloud installation does,
 * with the control plane in this JVM.
 *
 * Every request from the internet is `curl` on the host through the gateway's mapped ports, with
 * `--resolve` standing in for DNS and the certificate verified against the installation's roots:
 * Pebble's for a custom hostname, the local authority's for the derived one. Nothing here skips
 * verification.
 *
 * The control plane reads its proof records through a lookup the steps set, beside the resolver the
 * cluster asks: this JVM cannot reach a DNS server inside the node over UDP. That the lookup reads
 * DNS is `ProofLookupSuite`'s, against the same resolver image.
 */
class CustomHostnamesClusterFeatures
    extends GherkinSuite("../features/exposure/custom-hostnames.feature")
    with LogCapturing:

  override val munitTimeout: FiniteDuration = 15.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  override protected def ranElsewhere: Map[String, String] = Map(
    "the console shows a service's custom hostnames beside the one the platform derived" ->
      "console/e2e/tests/services.spec.ts (US3-9), against the fake control plane",
    "a custom hostname is refused when the installation names no authority for custom hostnames" ->
      "CliEndToEndSuite, whose control plane names no issuer"
  )

  private val K3sImage = "rancher/k3s:v1.35.1-k3s1"
  private val Sample =
    s"sample-shopping-cart:${com.thinkmorestupidless.ankka.core.BuildInfo.imageTag}"
  private val Pause      = "registry.k8s.io/pause:3.9"
  private val BaseDomain = "example.test"
  private val Prefix     = "ankka"

  private lazy val identity = TestIdentity()

  /**
   * The organization's owner, who is also a platform administrator: members join by an invitation
   * claimed at a verified sign-in, which this suite has no browser for. Every act is attributed to
   * the role as well, which is what the take-away scenario reads.
   */
  private lazy val Admin =
    identity.token(
      "root",
      Some("root@example.test"),
      roles = Set("platform-admin"),
      expiresIn = 2.hours
    )
  private def Member = Admin

  private var k3s: K3sContainer         = null
  private var k8s: KubernetesClient     = null
  private var operator: Operator        = null
  private var testKit: AnkkaTestKit     = null
  private var acme: AcmeStack.Installed = null
  private var url: String               = ""
  private var config: Path              = null
  private var roots: Path               = null
  private var httpsPort: Int            = 0
  private var httpPort: Int             = 0
  private var channels                  = Vector.empty[ManagedChannel]

  /** What DNS says of each proof record, by record name; absent is no record. */
  private val proofRecords = TrieMap.empty[String, Vector[String]]
  private val proofs: ProofLookup = name =>
    proofRecords.get(name).toRight(ProofLookup.Failure.NoRecord)

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort, GatewayStack.HttpNodePort)
      k3s.start()
      ClusterImages.importInto(k3s, Sample)
      httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)
      httpPort = k3s.getMappedPort(GatewayStack.HttpNodePort)

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
      waitFor(120.seconds, "CloudNativePG's controller") {
        Option(
          k8s
            .apps()
            .deployments()
            .inNamespace("cnpg-system")
            .withName("cnpg-controller-manager")
            .get()
        )
          .flatMap(d => Option(d.getStatus))
          .flatMap(s => Option(s.getReadyReplicas))
          .exists(_ > 0)
      }
      GatewayStack.install(k3s, k8s, PkiStack.repoRoot, BaseDomain)
      acme = AcmeStack.install(k3s)
      roots = AcmeStack.bundle(GatewayStack.exportCa(k8s), acme.root)

      val operatorSettings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        baseDomain = Some(BaseDomain),
        httpsPort = httpsPort,
        hostnameIssuer = Some(AcmeStack.Issuer)
      )
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        progressDeadline = 240.seconds,
        baseDomain = Some(BaseDomain),
        httpsPort = httpsPort,
        hostnameIssuer = Some(AcmeStack.Issuer)
      )
      val projector = ServiceProjector.withClient(
        deployConfig,
        new Fabric8AnkkaServiceClient(k8s, Prefix, resyncMillis = 2000L)
      )
      val server = HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(identity.acl(), deployConfig, proofs = Some(proofs))*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

      config = Files.createTempFile("ankka-hostnames-cluster", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString

      ok(ankkaAs(Admin, "organizations", "create", "acme", "--name", "Acme")): Unit
      for project <- Vector("checkout", "billing") do
        ok(
          ankkaAs(Admin, "projects", "create", project, "--name", project, "--organization", "acme")
        ): Unit

  override def afterAll(): Unit =
    channels.foreach(c => Try(c.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)))
    if testKit != null then identity.stop()
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  // ── the CLI ───────────────────────────────────────────────────────────────

  private final case class Run(code: Int, out: String, err: String):
    def all: String = out + err

  private def ankkaAs(token: String, args: String*): Run =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Main.run(
      args ++ Seq("--url", url, "--token", token),
      PrintStream(out, true, StandardCharsets.UTF_8),
      PrintStream(err, true, StandardCharsets.UTF_8)
    )
    Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  /** As the platform administrator who made the tenancy; a member's own acts are `asMember`. */
  private def ankka(args: String*): Run = ankkaAs(Admin, args*)

  private def ok(run: Run): Run =
    assertEquals(run.code, 0, run.all)
    run

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    var last     = ""
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch
          case e: Throwable =>
            last = e.toString
            false
      if !passed then Thread.sleep(1000)
    if !passed then
      fail(
        s"$what did not happen within $timeout${if last.isEmpty then "" else s"; last: $last"}${diagnosis()}"
      )

  /** What to read when a wait fails: the resources' statuses and what serves each hostname. */
  private def diagnosis(): String =
    if k3s == null then ""
    else
      def node(args: String*): String =
        val r = k3s.execInContainer(args*)
        r.getStdout + r.getStderr
      def each(kind: String, path: String) =
        node(
          "kubectl",
          "get",
          kind,
          "-A",
          "-o",
          s"jsonpath={range .items[*]}{.metadata.name}: $path{\"\\n\"}{end}"
        )
      Vector(
        "ankkaservices" -> each(
          "ankkaservices",
          "{.spec.customHostnames} {.status.hostnames} written {.status.lastTransitionTime} gen {.status.observedGeneration}/{.metadata.generation} lifecycle {.status.lifecycle}"
        ),
        "now" -> java.time.Instant.now().toString,
        "read fresh" -> Try {
          val views = new com.thinkmorestupidless.ankka.operator.Fabric8Executor(k8s)
            .observeHostnames("ankka-checkout", "cart", held("cart"))
          s"$views => ${views.values.map(com.thinkmorestupidless.ankka.operator.HostnameRules.status)}"
        }.fold(_.toString, s => s),
        "certificates" -> node("kubectl", "get", "certificates", "-A", "-o", "wide"),
        "challenges"   -> each("challenges", "{.spec.dnsName}|{.status.state}|{.status.reason}"),
        "listenersets" -> each("listenersets", "{.status.listeners}"),
        "routes"       -> each("httproutes", "{.status.parents}"),
        "cart"         -> statusOf("cart").map(_.toString).getOrElse("-"),
        "history"      -> ankka("services", "history", "cart", "-p", "checkout").all
      ).map((k, v) => s"\n── $k\n$v").mkString

  private val projectOf = TrieMap("cart" -> "checkout")

  private def statusOf(name: String): Option[ServiceStatus] =
    val run = ankka("services", "get", name, "-p", projectOf(name), "-o", "json")
    Option.when(run.code == 0)(readFromString[ServiceStatus](run.out))

  private def derived(name: String): String = s"$name-${projectOf(name)}.$BaseDomain"

  // ── the internet ──────────────────────────────────────────────────────────

  private final case class Answer(status: Int, body: String, certificate: String)

  /** `curl` on the host, through the gateway, verifying against the installation's roots. */
  private def internet(host: String, path: String = "/carts/c1", https: Boolean = true): Answer =
    val port   = if https then httpsPort else httpPort
    val scheme = if https then "https" else "http"
    val args = Vector(
      "curl",
      "-sS",
      "-v",
      "--cacert",
      roots.toString,
      "--resolve",
      s"$host:$port:127.0.0.1",
      "-m",
      "10",
      "-o",
      "-",
      "-w",
      "\n%{http_code} %{redirect_url}",
      s"$scheme://$host:$port$path"
    )
    assert(!args.exists(a => a == "-k" || a == "--insecure"), args.mkString(" "))
    val process = new ProcessBuilder(args*).start()
    val body    = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val err     = new String(process.getErrorStream.readAllBytes(), StandardCharsets.UTF_8)
    process.waitFor()
    val lines = body.linesIterator.toVector
    val code =
      lines.lastOption.flatMap(_.trim.split(' ').headOption.flatMap(_.toIntOption)).getOrElse(0)
    lastRedirect = lines.lastOption.flatMap(_.trim.split(' ').lift(1)).getOrElse("")
    Answer(code, lines.dropRight(1).mkString("\n"), err)

  private var lastRedirect = ""

  /** Answered by the shopping cart: its route for a cart, 200. Nothing else here serves one. */
  private def answeredByCart(host: String): Boolean = internet(host).status == 200

  private def nothingAnswers(host: String): Boolean = internet(host).status != 200

  // ── the scenario's state ──────────────────────────────────────────────────

  private var last: Run                             = Run(0, "", "")
  private var lastAnswer: Answer                    = Answer(0, "", "")
  private var podsBefore: Map[String, Int]          = Map.empty
  private var grpcCaller: Either[Throwable, String] = Left(new IllegalStateException("no call"))
  private val usedHostnames                         = TrieMap.empty[String, Unit]

  private def pods(name: String) =
    k8s
      .pods()
      .inNamespace(s"$Prefix-${projectOf(name)}")
      .withLabel("app.kubernetes.io/name", name)
      .list()
      .getItems
      .asScala
      .toVector
      .filter(_.getMetadata.getDeletionTimestamp == null)

  private def podState(name: String): Map[String, Int] =
    pods(name).map { p =>
      p.getMetadata.getUid ->
        Option(p.getStatus.getContainerStatuses)
          .map(_.asScala.map(_.getRestartCount.intValue).sum)
          .getOrElse(0)
    }.toMap

  private def add(name: String, hostname: String, token: String = Member): Run =
    usedHostnames.put(hostname, ())
    ankkaAs(token, "services", "hostnames", "add", name, hostname, "-p", projectOf(name))

  private def held(name: String): Vector[String] =
    statusOf(name).toVector.flatMap(_.customHostnames.map(_.hostname))

  private def standing(name: String, hostname: String) =
    statusOf(name).flatMap(_.customHostnames.find(_.hostname == hostname))

  private def apply(name: String, project: String, descriptor: String): Unit =
    projectOf.put(name, project)
    val file = Files.createTempFile(name, ".json")
    Files.writeString(file, descriptor)
    try ok(ankka("services", "apply", "-f", file.toString, "-p", project)): Unit
    finally Files.deleteIfExists(file): Unit

  /** The shopping cart, with gRPC, or a stand-in that serves nothing and has no database. */
  private def ensureDeployed(name: String, project: String): Unit =
    projectOf.put(name, project)
    if statusOf(name).isEmpty then
      if name == "cart" then
        apply(
          name,
          project,
          s"""{"name":"cart","service":{"image":"$Sample","grpc":true,"http":true}}"""
        )
        waitFor(600.seconds, "cart is ready") {
          statusOf(name).exists(s => s.lifecycle.toString == "Ready")
        }
      else
        apply(name, project, s"""{"name":"$name","service":{"image":"$Pause","database":"none"}}""")

  // ── Background ────────────────────────────────────────────────────────────

  Given("an installation whose base domain is {string}") { (base: String) =>
    assertEquals(base, BaseDomain)
    // Each scenario starts with no custom hostname anywhere and no name resolving: taken away by
    // the administrator, then waited for until the certificates and their keys are gone.
    for (name, _) <- projectOf.toVector do
      for hostname <- held(name) do
        ok(ankka("services", "hostnames", "remove", name, hostname, "-p", projectOf(name))): Unit
    for hostname <- usedHostnames.keys do acme.clearA(hostname)
    proofRecords.clear()
    waitFor(120.seconds, "every hostname certificate and its key are gone") {
      Vector("checkout", "billing").forall { project =>
        val ns = s"$Prefix-$project"
        k8s
          .genericKubernetesResources("cert-manager.io/v1", "Certificate")
          .inNamespace(ns)
          .withLabel("ankka.thinkmorestupidless.com/hostname-certificate", "true")
          .list()
          .getItems
          .isEmpty &&
        k8s
          .secrets()
          .inNamespace(ns)
          .list()
          .getItems
          .asScala
          .forall(!_.getMetadata.getName.contains('.'))
      }
    }
    last = Run(0, "", "")
  }

  Given("a deployed service {string} of the project {string} that is exposed") {
    (name: String, project: String) =>
      ensureDeployed(name, project)
      ok(ankka("services", "expose", name, "-p", project)): Unit
  }

  Given("a deployed service {string} of the project {string} that is not exposed") {
    (name: String, project: String) =>
      ensureDeployed(name, project)
      ok(ankka("services", "unexpose", name, "-p", project)): Unit
  }

  Given("the name {string} carries the proof record of the project {string}") {
    (hostname: String, project: String) =>
      proofRecords.put(ProofLookup.recordName(hostname), Vector(ProofLookup.recordValue(project)))
  }

  Given("the name {string} carries no proof record") { (hostname: String) =>
    proofRecords.remove(ProofLookup.recordName(hostname)): Unit
  }

  Given("the name {string} resolves to the installation") { (hostname: String) =>
    usedHostnames.put(hostname, ())
    acme.resolveToInstallation(hostname)
  }

  Given("the name {string} resolves to nothing") { (hostname: String) =>
    usedHostnames.put(hostname, ())
    acme.clearA(hostname)
  }

  Given(
    "the authority for custom hostnames issues a certificate for a name only when the name resolves to the installation"
  ) { () =>
    () // Pebble checks every HTTP-01 challenge through the name's DNS: AcmeStack.
  }

  Given("the authority for custom hostnames refuses to issue a certificate for {string}") {
    (hostname: String) =>
      // The name resolves somewhere nothing answers on port 80, so every challenge fails.
      acme.resolveToNothingThatAnswers(hostname)
  }

  Given("a member has added the custom hostname {string} to {string}") {
    (hostname: String, name: String) =>
      if !proofRecords.contains(ProofLookup.recordName(hostname)) then
        proofRecords.put(
          ProofLookup.recordName(hostname),
          Vector(ProofLookup.recordValue(projectOf(name)))
        ): Unit
      ok(add(name, hostname))
      // The holder check reads the listing, a projection a moment behind the entity.
      waitFor(30.seconds, s"the listing shows $hostname on $name") {
        ankka("services", "list", "-p", projectOf(name), "-o", "json").out.contains(hostname)
      }
  }

  Given(
    "a member has added {string} custom hostnames to {string}, each carrying the proof record of the project {string}"
  ) { (count: String, name: String, project: String) =>
    for i <- 1 to count.toInt do
      val hostname = s"a$i.example.com"
      proofRecords.put(ProofLookup.recordName(hostname), Vector(ProofLookup.recordValue(project)))
      ok(add(name, hostname)): Unit
  }

  Given("{string} declares gRPC") { (name: String) =>
    assertEquals(name, "cart") // deployed with "grpc": true
  }

  Given("a platform administrator has taken the custom hostname {string} away from {string}") {
    (hostname: String, name: String) =>
      proofRecords.put(
        ProofLookup.recordName(hostname),
        Vector(ProofLookup.recordValue(projectOf(name)))
      )
      ok(add(name, hostname))
      ok(ankka("services", "hostnames", "remove", name, hostname, "-p", projectOf(name))): Unit
  }

  Given("{string} is serving") { (hostname: String) =>
    waitFor(300.seconds, s"$hostname is serving") {
      projectOf.keys.exists(name => standing(name, hostname).exists(_.state == "serving"))
    }
  }

  Given("a member has unexposed {string}") { (name: String) =>
    ok(ankkaAs(Member, "services", "unexpose", name, "-p", projectOf(name))): Unit
  }

  // ── When ──────────────────────────────────────────────────────────────────

  When("a member adds the custom hostname {string} to {string}") {
    (hostname: String, name: String) =>
      podsBefore = podState(name)
      last = add(name, hostname)
  }

  When("a person on the internet sends a request to {string}") { (host: String) =>
    lastAnswer = internet(host)
  }

  When("a person on the internet sends a request to {string} in the clear") { (host: String) =>
    waitFor(300.seconds, s"$host is serving")(answeredByCart(host))
    lastAnswer = internet(host, https = false)
  }

  When("a member reads the service {string}") { (name: String) =>
    last = ok(ankkaAs(Member, "services", "get", name, "-p", projectOf(name)))
  }

  When("a gRPC client on the internet calls a method of {string} at {string}") {
    (name: String, hostname: String) =>
      waitFor(300.seconds, s"$hostname is serving") {
        standing(name, hostname).exists(_.state == "serving")
      }
      val credentials = TlsChannelCredentials.newBuilder().trustManager(roots.toFile).build()
      val channel = Grpc
        .newChannelBuilderForAddress("127.0.0.1", httpsPort, credentials)
        .overrideAuthority(hostname)
        .build()
      channels :+= channel
      grpcCaller = Try(
        CartServiceGrpc
          .blockingStub(channel)
          .withDeadlineAfter(10, TimeUnit.SECONDS)
          .whoCalled(WhoCalledRequest())
          .caller
      ).toEither
  }

  When("a platform administrator takes the custom hostname {string} away from {string}") {
    (hostname: String, name: String) =>
      last =
        ankkaAs(Admin, "services", "hostnames", "remove", name, hostname, "-p", projectOf(name))
  }

  When("a member removes the custom hostname {string} from {string}") {
    (hostname: String, name: String) =>
      podsBefore = podState(name)
      last = ok(
        ankkaAs(Member, "services", "hostnames", "remove", name, hostname, "-p", projectOf(name))
      )
  }

  When("a member unexposes {string}") { (name: String) =>
    last = ok(ankkaAs(Member, "services", "unexpose", name, "-p", projectOf(name)))
  }

  When("a member exposes {string}") { (name: String) =>
    last = ok(ankkaAs(Member, "services", "expose", name, "-p", projectOf(name)))
  }

  When("a member deletes {string}") { (name: String) =>
    last = ok(ankkaAs(Member, "services", "delete", name, "-p", projectOf(name)))
  }

  // ── Then ──────────────────────────────────────────────────────────────────

  Then("the member is refused") { () =>
    assertNotEquals(last.code, 0, last.all)
  }

  Then("the refusal says that {string} does not carry the proof record of the project {string}") {
    (hostname: String, project: String) =>
      assert(
        last.all.contains(s"'$hostname' does not carry the proof record of project '$project'"),
        last.all
      )
  }

  Then("the refusal tells the member the proof record to create for {string}") { (hostname: String) =>
    assert(last.all.contains(s"create TXT _ankka.$hostname with the value"), last.all)
  }

  Then("{string} does not hold {string}") { (name: String, hostname: String) =>
    assert(!held(name).contains(hostname), held(name).toString)
  }

  Then("{string} no longer holds {string}") { (name: String, hostname: String) =>
    assertEquals(last.code, 0, last.all)
    assert(!held(name).contains(hostname), held(name).toString)
  }

  Then("{string} still holds {string}") { (name: String, hostname: String) =>
    assert(held(name).contains(hostname), held(name).toString)
  }

  Then("the custom hostname {string} is held by {string}") { (hostname: String, name: String) =>
    assertEquals(last.code, 0, last.all)
    assert(held(name).contains(hostname), held(name).toString)
  }

  Then(
    "within {string} seconds a request from the internet to {string}, checking its certificate against the authority of the installation, is answered by {string}"
  ) { (seconds: String, host: String, name: String) =>
    assertEquals(last.code, 0, last.all)
    waitFor(seconds.toInt.seconds, s"$host answers for $name") {
      lastAnswer = internet(host)
      lastAnswer.status == 200
    }
  }

  Then("the certificate is for {string}") { (hostname: String) =>
    // curl -v: `subjectAltName: host "<name>" matched cert's "<name>"`, and an issuer that is Pebble's.
    assert(
      lastAnswer.certificate.contains(s"""host "$hostname" matched cert's "$hostname""""),
      lastAnswer.certificate
    )
    assert(lastAnswer.certificate.contains("Pebble"), lastAnswer.certificate)
  }

  Then("the request is answered by {string}") { (name: String) =>
    assertEquals(name, "cart") // only the shopping cart serves /carts/c1
    assertEquals(lastAnswer.status, 200, lastAnswer.body)
  }

  Then("the member is told to create a record for {string} that points at {string}") {
    (hostname: String, target: String) =>
      assertEquals(last.code, 0, last.all)
      assert(last.out.contains(s"create CNAME $hostname → $target"), last.all)
  }

  Then("the member is told the same whenever the member reads the service {string}") {
    (name: String) =>
      val get = ok(ankkaAs(Member, "services", "get", name, "-p", projectOf(name)))
      assert(get.out.contains(s"create CNAME app.example.com → ${derived(name)}"), get.out)
  }

  Then(
    "the member is told the proof record of the project {string} whenever the member reads the service {string}"
  ) { (project: String, name: String) =>
    val get = ok(ankkaAs(Member, "services", "get", name, "-p", projectOf(name)))
    assert(get.out.contains(s"""TXT _ankka.<hostname> "ankka-project=$project""""), get.out)
  }

  Then("the member is shown that {string} is waiting for its certificate") { (hostname: String) =>
    waitFor(120.seconds, s"$hostname is shown waiting for its certificate") {
      projectOf.keys.exists(name =>
        standing(name, hostname).exists(h =>
          h.state == "pending" && h.reason.exists(_.startsWith("waiting for the certificate"))
        )
      )
    }
  }

  Then("the member is shown the authority's reason, that it could not reach {string}") {
    (hostname: String) =>
      waitFor(120.seconds, s"the authority's reason names $hostname") {
        standing("cart", hostname).exists(_.reason.exists(r => r.contains(s"http://$hostname/")))
      }
  }

  Then("the member is shown the authority's reason") { () =>
    waitFor(120.seconds, "the authority's reason is shown") {
      standing("cart", "app.example.com").exists(
        _.reason.exists(r =>
          r.startsWith(
            "waiting for the certificate: "
          ) && r.length > "waiting for the certificate: ".length
        )
      )
    }
  }

  Then("a request from the internet to {string} reaches no instance of {string}") {
    (host: String, _: String) =>
      assert(nothingAnswers(host), s"$host answered")
  }

  Then("the member is shown the hostname {string}") { (hostname: String) =>
    assert(last.out.contains(hostname), last.out)
  }

  Then("the member is shown the custom hostname {string} and that it is serving") {
    (hostname: String) =>
      waitFor(300.seconds, s"$hostname is serving") {
        ankkaAs(Member, "services", "get", "cart", "-p", "checkout").out.linesIterator
          .exists(l => l.contains(hostname) && l.contains("serving"))
      }
  }

  Then("the call is answered by {string}") { (name: String) =>
    assertEquals(name, "cart")
    // The sample's whoCalled answers with the caller it read: from outside, the gateway.
    assertEquals(grpcCaller.map(_.contains("gateway")), Right(true), grpcCaller.toString)
  }

  Then("the person is redirected to the same address, not in the clear") { () =>
    assertEquals(lastAnswer.status, 301, lastAnswer.body)
    assert(lastRedirect.startsWith(s"https://app.example.com"), lastRedirect)
    assert(lastRedirect.endsWith("/carts/c1"), lastRedirect)
  }

  Then("no handler runs") { () =>
    // The redirect is the gateway's answer: nothing a service handles answers 301 to /carts/c1.
    assertEquals(lastAnswer.status, 301)
  }

  Then("the refusal says that {string} is not exposed") { (name: String) =>
    assert(last.all.contains(s"service '$name' is not exposed"), last.all)
  }

  Then("the refusal says that {string} is held by {string} of the project {string}") {
    (hostname: String, holder: String, project: String) =>
      assert(
        last.all.contains(s"'$hostname' is held by service '$holder' in project '$project'"),
        last.all
      )
  }

  Then("the refusal says that a custom hostname cannot be under the base domain") { () =>
    assert(last.all.contains("a custom hostname cannot be under the base domain"), last.all)
  }

  Then("the refusal says that a custom hostname cannot be a wildcard") { () =>
    assert(last.all.contains("a custom hostname cannot be a wildcard"), last.all)
  }

  Then("the refusal says that a custom hostname is a name alone") { () =>
    assert(last.all.contains("a custom hostname is a name alone"), last.all)
  }

  Then("the refusal says that a service holds at most {string} custom hostnames") { (n: String) =>
    assert(last.all.contains(s"holds $n custom hostnames, the most a service can hold"), last.all)
  }

  Then("nothing answers at {string}") { (host: String) =>
    waitFor(60.seconds, s"nothing answers at $host")(nothingAnswers(host))
  }

  Then("within {string} seconds nothing answers at {string}") { (seconds: String, host: String) =>
    waitFor(seconds.toInt.seconds, s"nothing answers at $host")(nothingAnswers(host))
  }

  Then("the history of {string} says who took it away, and when") { (name: String) =>
    val history = readFromString[Vector[HistoryEntry]](
      ok(ankka("services", "history", name, "-p", projectOf(name), "-o", "json")).out
    )
    val taken = history.find(_.kind == "hostname taken away").getOrElse(fail(history.toString))
    assertEquals(taken.actor.map(a => (a.subject, a.administrative)), Some(("root", true)))
    assert(taken.at.isDefined, taken.toString)
    assertEquals(taken.hostname, Some("app.example.com"))
  }

  Then("a request from the internet to {string} is answered by {string}") {
    (host: String, name: String) =>
      waitFor(60.seconds, s"$host answers for $name")(answeredByCart(host))
  }

  Then("within {string} seconds a request from the internet to {string} is answered by {string}") {
    (seconds: String, host: String, name: String) =>
      waitFor(seconds.toInt.seconds, s"$host answers for $name")(answeredByCart(host))
  }

  Then("every instance of {string} is ready") { (name: String) =>
    waitFor(30.seconds, s"every instance of $name is ready") {
      pods(name).nonEmpty && pods(name).forall(p =>
        Option(p.getStatus.getConditions)
          .exists(_.asScala.exists(c => c.getType == "Ready" && c.getStatus == "True"))
      )
    }
  }

  Then("no instance of {string} is restarted") { (name: String) =>
    assertEquals(podState(name), podsBefore)
  }

  Then("a member can add the custom hostname {string} to {string}") {
    (hostname: String, name: String) =>
      waitFor(60.seconds, s"$name can add $hostname")(add(name, hostname).code == 0)
  }
