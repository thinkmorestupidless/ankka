package com.thinkmorestupidless.ankka.controlplane

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.ControlPlaneAcl
import com.thinkmorestupidless.ankka.controlplane.auth.{AuthConfig, DeployTokenIndex}
import com.thinkmorestupidless.ankka.operator.{GatewayStack, KeycloakStack}
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  Fabric8AnkkaServiceClient,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.crd.{AnkkaSerialization, AnkkaService}
import com.thinkmorestupidless.ankka.operator.cnpg.{
  PostgresCluster,
  PostgresDatabase,
  PostgresDatabaseRole
}
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  Operator,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import org.slf4j.LoggerFactory
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.Base64
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * Everything, at once: the CLI, the control plane, Postgres, the operator and a real cluster.
 *
 * This is the only test that can catch the two halves disagreeing about the custom resource — the
 * same argument the build already makes for `controlplane` taking `cli % Test`, applied to the
 * second wire format this feature introduces.
 *
 * Disable with `-Dankka.cluster.tests=off`.
 */
class EndToEndClusterSuite extends munit.FunSuite:

  override val munitTimeout: FiniteDuration = 14.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  private val Image = "rancher/k3s:v1.35.1-k3s1"
  // Feature 008: the real identity provider, deployed as deploy-local.sh deploys it, and a real
  // token from it through the gateway. The control plane runs in this JVM but verifies against
  // Keycloak's key set exactly as a deployed one would (over a port-forward that stands in for the
  // cluster network), and every request below carries this token.
  private val BaseDomain                                             = "test.local"
  private var ca: Path                                               = null
  private var forward: io.fabric8.kubernetes.client.LocalPortForward = null
  private var httpsPort: Int                                         = 0
  private lazy val Token: String =
    KeycloakStack.mintToken(ca, BaseDomain, httpsPort, "e2e-cli", "e2e-secret")
  private val Prefix    = "ankka"
  private val Project   = "checkout"
  private val Namespace = s"$Prefix-$Project"
  private val Service   = "cart"

  /**
   * The sample, under its two tags.
   *
   * Feature 001 used `pause` here: the smallest thing that just sits there, which was all a
   * reconciliation test needed from a workload. Since feature 004 readiness means cluster
   * membership, and an image with no ankka runtime can never be Ready — by design (FR-022). So the
   * workload is now the real sample, and "change the image" is the same image under its other tag,
   * which still changes the pod template and still rolls.
   *
   * The other tag is the build's own version (`Docker / version`: dynver with `+` → `-`), never a
   * literal. It was `0.1.0-SNAPSHOT` until feature 006 deleted `ThisBuild / version`, and the suite
   * kept passing on a stale image of that name in the Docker daemon — one built before the rename,
   * reading environment variables the operator no longer sets, so it could start but never be
   * `Ready`. Case 5 hid it (a rolling update keeps the old pod `Ready`); the first case where that
   * image was the only pod timed out.
   */
  private val FirstImage = "sample-shopping-cart:latest"
  private val SecondImage =
    s"sample-shopping-cart:${com.thinkmorestupidless.ankka.core.BuildInfo.version.replace('+', '-')}"

  private var k3s: K3sContainer        = null
  private var k8s: KubernetesClient    = null
  private var operator: Operator       = null
  private var testKit: AnkkaTestKit    = null
  private var tokens: DeployTokenIndex = null
  private var url: String              = ""
  private var config: Path             = null

  // Feature 011: a spoke — a second control plane with no identity provider of its own and its own
  // base domain, trusting the hub's realm by explicit configuration. Its own journal (its own
  // Postgres), because two installations never share one.
  private val SpokeDomain                = s"spoke.$BaseDomain"
  private var hubAuth: AuthConfig        = null
  private var spokeTestKit: AnkkaTestKit = null
  private var spokeUrl: String           = ""

  // Captures every log event in this JVM for the life of the suite, so T053 can assert a
  // generated database password never appears in the operator's own logs — the only way to
  // check that with the operator running in-process rather than as a separate container whose
  // stdout could be grepped.
  private val logAppender = new ListAppender[ILoggingEvent]()

  override def beforeAll(): Unit =
    if !munitIgnore then
      val root =
        LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
      logAppender.start()
      root.addAppender(logAppender)

      k3s = new K3sContainer(DockerImageName.parse(Image))
      k3s.withExposedPorts(6443, GatewayStack.HttpsNodePort, GatewayStack.HttpNodePort)
      k3s.start()
      httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)

      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()

      k8s.load(getClass.getResourceAsStream("/ankka/crd/ankkaservice.yaml")).serverSideApply(): Unit

      ClusterImages.importInto(k3s, FirstImage)
      ClusterImages.importInto(k3s, SecondImage)

      // CloudNativePG too — the control plane always projects provisionDatabase = true until
      // the escape hatch exists, so every service applied in this suite goes through real
      // provisioning, not just the ones that specifically test it.
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
      waitFor(90.seconds) {
        val d = k8s
          .apps()
          .deployments()
          .inNamespace("cnpg-system")
          .withName("cnpg-controller-manager")
          .get()
        d != null && Option(d.getStatus).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
      }

      GatewayStack.install(k3s, k8s, repoRoot, BaseDomain)
      ca = GatewayStack.exportCa(k8s)
      KeycloakStack.install(
        k3s,
        k8s,
        repoRoot,
        BaseDomain,
        k3s.getMappedPort(GatewayStack.HttpsNodePort)
      )
      KeycloakStack.createServiceClient(k3s, "e2e-cli", "e2e-secret", platformAdmin = true)
      forward = KeycloakStack.forwardService(k8s)
      val auth = AuthConfig(
        issuer = AuthConfig.derivedIssuer(BaseDomain, httpsPort),
        jwksUrl =
          s"http://127.0.0.1:${forward.getLocalPort}/realms/ankka/protocol/openid-connect/certs",
        audience = "ankka-controlplane",
        clientId = "ankka-cli",
        realmHint = "ankka",
        clockSkew = 60.seconds
      )
      hubAuth = auth

      val operatorSettings = OperatorSettings.default.copy(resyncInterval = 2.seconds)
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

      val deployConfig = DeployConfig.default
        .copy(namespacePrefix = Prefix, sweepInterval = 2.seconds, progressDeadline = 170.seconds)
      val projector = ServiceProjector.withClient(
        deployConfig,
        new Fabric8AnkkaServiceClient(k8s, Prefix, resyncMillis = 2000L)
      )

      // Deploy tokens (feature 013): the acl answers from this index, and the index only fills
      // because it is registered as an extension — the pair `aclWithTokens` returns for a deployed
      // control plane, assembled by hand here because this suite supplies its own AuthConfig.
      tokens = new DeployTokenIndex()
      val server = HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(
          ControlPlaneAcl.composite(tokens, ControlPlane.aclFor(auth), auth),
          deployConfig,
          Some(auth),
          tokens = Some(tokens),
          // The projector is the `RegistryWriter`, exactly as `ControlPlane.builder` wires it.
          registry = Some(projector)
        )*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server, tokens)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

      // The spoke's issuer and key source are the hub's, named explicitly — exactly what a spoke's
      // overlay sets through ANKKA_AUTH_ISSUER and ANKKA_AUTH_JWKS_URL — while its base domain is
      // its own, so the issuer it would otherwise derive is a different string.
      val spokeAuth = hubAuth
      val spokeDeploy =
        DeployConfig.default.copy(namespacePrefix = "spoke", baseDomain = Some(SpokeDomain))
      val spokeServer = HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(ControlPlane.aclFor(spokeAuth), spokeDeploy, Some(spokeAuth))*
      )
      spokeTestKit =
        AnkkaTestKit.start(ControlPlane.components, Seq(ProjectionRuntime(), spokeServer))
      spokeUrl =
        s"http://127.0.0.1:${spokeServer.boundPort.getOrElse(fail("spoke server did not bind"))}"

      config = Files.createTempFile("ankka-e2e", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString

  override def afterAll(): Unit =
    if forward != null then forward.close()
    if ca != null then Files.deleteIfExists(ca): Unit
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if spokeTestKit != null then spokeTestKit.stop()
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()
    val root =
      LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    root.detachAppender(logAppender): Unit

  private def cli(args: String*): (Int, String) =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val outs = PrintStream(out, true, StandardCharsets.UTF_8)
    val errs = PrintStream(err, true, StandardCharsets.UTF_8)
    val code = Main.run(args, outs, errs)
    val text = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8)
    (code, text)

  private def ankka(args: String*): (Int, String) =
    cli((args ++ Seq("--url", url, "--token", Token))*)

  private def waitFor(timeout: FiniteDuration)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(250)
    if !passed then fail(s"condition did not hold within $timeout")

  private def deployment = Option(
    k8s.apps().deployments().inNamespace(Namespace).withName(Service).get()
  )

  private def resource = Option(
    k8s.resources(classOf[AnkkaService]).inNamespace(Namespace).withName(Service).get()
  )

  private def descriptorJson(image: String): Path =
    val file = Files.createTempFile("cart", ".json")
    Files.writeString(
      file,
      s"""{"name":"$Service","service":{"image":"$image"}}"""
    )
    file

  private def applyCart(image: String): Unit =
    val file        = descriptorJson(image)
    val (code, out) = ankka("services", "apply", "-f", file.toString, "-p", Project)
    Files.deleteIfExists(file): Unit
    assertEquals(code, 0, s"apply failed: $out")

  private def listed: String = ankka("services", "list", "-p", Project)._2

  private val SuppliedService = "external"

  private def applySupplied(): Unit =
    val file = Files.createTempFile("external", ".json")
    Files.writeString(
      file,
      // `pause`, deliberately, and never Ready: a supplied database the sample cannot reach would
      // fail its startup just the same, and what this case asserts is what the platform does NOT
      // provision, plus the reported phase — neither needs the workload up.
      s"""{"name":"$SuppliedService","service":{"image":"registry.k8s.io/pause:3.9","http":false,""" +
        """"env":[{"name":"ANKKA_DB_HOST","value":"external-db.example.com"}]}}"""
    )
    val (code, out) = ankka("services", "apply", "-f", file.toString, "-p", Project)
    Files.deleteIfExists(file): Unit
    assertEquals(code, 0, s"apply failed: $out")

  private def repoRoot: Path =
    var dir = Paths.get("").toAbsolutePath
    while !Files.exists(dir.resolve("build.sbt")) do dir = dir.getParent
    dir

  test("0. nothing answers without a token from the installation's own issuer (SC-001)") {
    val http = java.net.http.HttpClient.newHttpClient()
    def status(path: String, token: Option[String]): Int =
      val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + path))
      token.foreach(t => builder.header("Authorization", s"Bearer $t"): Unit)
      http
        .send(builder.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
        .statusCode
    for path <- Vector("/organizations", "/projects", s"/services/$Project", "/auth/whoami") do
      assertEquals(status(path, None), 401, path)
      assertEquals(status(path, Some("dev-local-token")), 401, s"$path with the old shared token")
    assertEquals(status("/auth", None), 200, "discovery is the one open route")
    // The whole point of the derivation: what Keycloak writes into `iss` through the gateway must
    // be exactly what the control plane derived from the base domain and port.
    val discovery  = KeycloakStack.discovery(ca, BaseDomain, httpsPort)
    val advertised = "\"issuer\":\"([^\"]+)\"".r.findFirstMatchIn(discovery).map(_.group(1))
    assertEquals(advertised, Some(AuthConfig.derivedIssuer(BaseDomain, httpsPort)), discovery)
    val builder = java.net.http.HttpRequest
      .newBuilder(java.net.URI.create(url + "/auth/whoami"))
      .header("Authorization", s"Bearer $Token")
    val verified =
      http.send(builder.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())
    assertEquals(
      verified.statusCode,
      200,
      s"a real token from Keycloak should verify; challenge: ${verified.headers.firstValue("WWW-Authenticate").orElse("")}; " +
        s"claims: ${com.thinkmorestupidless.ankka.operator.KeycloakAdmin.claims(Token)}"
    )
  }

  test("0b. a spoke trusts the hub's realm and no other issuer (feature 011, SC-005)") {
    val http = java.net.http.HttpClient.newHttpClient()
    def get(base: String, path: String, token: Option[String]) =
      val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
      token.foreach(t => builder.header("Authorization", s"Bearer $t"): Unit)
      http.send(builder.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString())

    // Discovery sends `ankka login` to the hub's realm, not to one the spoke's domain would derive.
    val discovery = get(spokeUrl, "/auth", None)
    assertEquals(discovery.statusCode, 200)
    val advertised = "\"issuer\":\"([^\"]+)\"".r.findFirstMatchIn(discovery.body).map(_.group(1))
    assertEquals(advertised, Some(hubAuth.issuer), discovery.body)
    assertNotEquals(advertised, Some(AuthConfig.derivedIssuer(SpokeDomain, httpsPort)))

    // One user, registered once at the hub, is the same caller on both installations.
    val onHub   = get(url, "/auth/whoami", Some(Token))
    val onSpoke = get(spokeUrl, "/auth/whoami", Some(Token))
    assertEquals(onHub.statusCode, 200, onHub.body)
    assertEquals(onSpoke.statusCode, 200, onSpoke.body)
    val subject = "\"subject\":\"([^\"]+)\"".r
    assertEquals(
      subject.findFirstMatchIn(onSpoke.body).map(_.group(1)),
      subject.findFirstMatchIn(onHub.body).map(_.group(1))
    )

    // Anything else is refused: another realm, and a token claiming the spoke's own derived issuer.
    // Both are signed under the key id the hub's realm really uses, so the verifier finds a key and
    // refuses on the signature without refetching. A key id it has never seen would trigger a
    // refetch instead, and a second one inside the refetch window answers 503 from the rate limit —
    // a property of the verifier, not of the trust decision this case is about.
    // Keycloak writes its header with spaces around the colons (`"kid" : "…"`).
    val header =
      String(Base64.getUrlDecoder.decode(Token.takeWhile(_ != '.')), StandardCharsets.UTF_8)
    val hubKid = "\"kid\"\\s*:\\s*\"([^\"]+)\"".r.findFirstMatchIn(header).map(_.group(1))
    assert(hubKid.isDefined, s"no key id in the hub token's header: $header")
    val elsewhere = TestIdentity("https://auth.other.test/realms/ankka")
    val ownDomain = TestIdentity(AuthConfig.derivedIssuer(SpokeDomain, httpsPort))
    try
      for (who, token) <- Vector(
          "another realm"              -> elsewhere.token("mallory", kid = hubKid),
          "the spoke's derived issuer" -> ownDomain.token("mallory", kid = hubKid)
        )
      do assertEquals(get(spokeUrl, "/auth/whoami", Some(token)).statusCode, 401, who)
    finally
      elsewhere.stop()
      ownDomain.stop()
  }

  test("1. an organization and a project are created through the CLI") {
    assertEquals(ankka("organizations", "create", "acme", "--name", "Acme")._1, 0)
    assertEquals(ankka("projects", "create", Project, "--name", "Checkout", "-O", "acme")._1, 0)
  }

  test("2. a project id that cannot become a namespace is refused") {
    // The validation this feature added: the id becomes part of a namespace name, so it
    // has to be refused at creation rather than when its first service fails to deploy.
    val (code, out) = ankka("projects", "create", "Not_Valid", "--name", "Bad", "-O", "acme")
    assertNotEquals(code, 0)
    assert(out.toLowerCase.contains("project id"), out)
  }

  test("3. applying a descriptor with no database configuration provisions one and reaches Ready") {
    // The headline, twice over: this is what "the deployment is not happening" stopped being
    // true at (feature 001), and what "an operator has to provision a database by hand first"
    // stops being true at (this feature). The descriptor above names only an image.
    applyCart(FirstImage)

    waitFor(120.seconds)(resource.isDefined)
    waitFor(180.seconds)(deployment.isDefined)
    waitFor(180.seconds)(listed.contains("Ready"))

    assertEquals(
      deployment.get.getSpec.getTemplate.getSpec.getContainers.get(0).getImage,
      FirstImage
    )

    // The database chain is real, not just "the pod is Ready because nothing needed it":
    // a project cluster, and this service's own role and database within it.
    assert(
      Option(
        k8s.resources(classOf[PostgresCluster]).inNamespace(Namespace).withName("ankka-db").get()
      ).isDefined,
      "expected the project's shared Postgres capacity to exist"
    )
    assertEquals(
      resource.flatMap(r => Option(r.getStatus)).flatMap(_.database).map(_.phase),
      Some("Provisioned")
    )
  }

  test("4. the reported status is confirmed, not a guess") {
    val (_, single) = ankka("services", "get", Service, "-p", Project)
    assert(!single.contains("unconfirmed"), single)
  }

  test("5. changing the image rolls the workload") {
    applyCart(SecondImage)
    waitFor(180.seconds)(
      deployment.exists(_.getSpec.getTemplate.getSpec.getContainers.get(0).getImage == SecondImage)
    )
    waitFor(180.seconds)(listed.contains("Ready"))
  }

  test("6. pausing stops the workload and resuming brings it back") {
    assertEquals(ankka("services", "pause", Service, "-p", Project)._1, 0)
    waitFor(120.seconds)(deployment.exists(_.getSpec.getReplicas.intValue == 0))
    waitFor(120.seconds)(listed.contains("Paused"))

    assertEquals(ankka("services", "resume", Service, "-p", Project)._1, 0)
    try waitFor(180.seconds)(deployment.exists(_.getSpec.getReplicas.intValue == 1))
    catch
      case failure: Throwable =>
        fail(
          s"the deployment did not scale back up: ${deployment.map(_.getSpec.getReplicas)}; resource: ${resource.map(_.getSpec)}",
          failure
        )
    try waitFor(180.seconds)(listed.contains("Ready"))
    catch
      case failure: Throwable =>
        val (_, got) = ankka("services", "get", Service, "-p", Project, "-o", "json")
        fail(
          s"the listing never said Ready; listing: $listed; get: $got; history: ${ankka("services", "history", Service, "-p", Project)._2}",
          failure
        )
  }

  test("7. restarting replaces the instance without changing the descriptor") {
    val before = resource.get.getSpec.generation
    assertEquals(ankka("services", "restart", Service, "-p", Project)._1, 0)

    waitFor(120.seconds)(resource.exists(_.getSpec.generation > before))
    // A restart is a restart count, on the pod template — the generation is not there any more,
    // or a pure scale would replace every pod (feature 004, FR-016).
    waitFor(120.seconds)(
      deployment.exists(
        _.getSpec.getTemplate.getMetadata.getAnnotations
          .get("ankka.thinkmorestupidless.com/restarts") == resource.get.getSpec.restarts.toString
      )
    )
    assertEquals(resource.get.getSpec.restarts, 1)
    assertEquals(
      deployment.get.getSpec.getTemplate.getSpec.getContainers.get(0).getImage,
      SecondImage,
      "a restart must not change the descriptor"
    )
  }

  test("8. a workload deleted out of band is restored without an operator command") {
    k8s.apps().deployments().inNamespace(Namespace).withName(Service).delete(): Unit
    waitFor(120.seconds)(deployment.isDefined)
    waitFor(180.seconds)(listed.contains("Ready"))
  }

  test("9. a descriptor supplying its own ANKKA_DB_* env var is the escape hatch") {
    applySupplied()
    waitFor(120.seconds)(
      Option(
        k8s.resources(classOf[AnkkaService]).inNamespace(Namespace).withName(SuppliedService).get()
      ).isDefined
    )
    waitFor(180.seconds)(
      Option(
        k8s.apps().deployments().inNamespace(Namespace).withName(SuppliedService).get()
      ).isDefined
    )

    assert(
      Option(
        k8s
          .resources(classOf[PostgresDatabase])
          .inNamespace(Namespace)
          .withName(SuppliedService)
          .get()
      ).isEmpty,
      "no Database may be provisioned for a service bringing its own"
    )
    assert(
      Option(
        k8s
          .resources(classOf[PostgresDatabaseRole])
          .inNamespace(Namespace)
          .withName(SuppliedService)
          .get()
      ).isEmpty,
      "no DatabaseRole may be provisioned for a service bringing its own"
    )
    assert(
      Option(k8s.secrets().inNamespace(Namespace).withName(s"$SuppliedService-db").get()).isEmpty,
      "no credential secret may be generated for a service bringing its own database"
    )

    // What the operator reports, not what the Deployment's existence implies: the status is folded
    // back on the next reconcile, so wait on the value that changes.
    waitFor(60.seconds)(
      ankka("services", "get", SuppliedService, "-p", Project)._2.contains("supplied")
    )
  }

  test(
    "10. the generated password for a provisioned service never appears in status, spec or logs"
  ) {
    val secret   = k8s.secrets().inNamespace(Namespace).withName(s"$Service-db").get()
    val password = new String(Base64.getDecoder.decode(secret.getData.get("password")))

    val statusJson = resource.flatMap(r => Option(r.getStatus)).map(_.toString).getOrElse("")
    assert(!statusJson.contains(password), "the AnkkaService status must never carry the password")

    val deploymentSpec = deployment.map(_.getSpec.toString).getOrElse("")
    assert(
      !deploymentSpec.contains(password),
      "the Deployment spec references the secret by name, never the password's literal value"
    )

    val logged = logAppender.list.asScala.exists(_.getFormattedMessage.contains(password))
    assert(!logged, "the generated password must never appear in any log line this JVM produced")
  }

  test(
    "11. deleting a service leaves its workload behind nothing, but its database untouched (FR-024)"
  ) {
    assertEquals(ankka("services", "delete", Service, "-p", Project)._1, 0)
    waitFor(120.seconds)(resource.isEmpty)
    // The Deployment goes with the resource, by owner reference, not by a sweep.
    waitFor(180.seconds)(deployment.isEmpty)

    // No owner reference ties the project's Cluster or this service's Database to the
    // AnkkaService that requested them (a deliberate CnpgRendering design choice) — deleting
    // the last service that used them must not touch either.
    assert(
      Option(
        k8s.resources(classOf[PostgresCluster]).inNamespace(Namespace).withName("ankka-db").get()
      ).isDefined,
      "the project's shared Postgres capacity must survive deleting its only service"
    )
    assert(
      Option(
        k8s.resources(classOf[PostgresDatabase]).inNamespace(Namespace).withName(Service).get()
      ).isDefined,
      "the deleted service's own Database must survive — nothing in this platform destroys one"
    )
  }

  test("12. a deploy token deploys against a real cluster, and revoking it stops the next call") {
    // **One control plane instance.** This suite runs it in this JVM against real k3s, so what is
    // proved here is the end-to-end path — a token minted through the CLI deploying a real service
    // into a real cluster — and the write-through eviction on the node that handled the revoke.
    //
    // It is *not* the multi-node proof. Revocation reaching a node that did not handle it travels
    // through the token journal, and the only suite that runs several control plane instances is
    // `ControlPlaneClusterSuite`, which deploys the image. The delay was measured directly instead
    // (feature 013, research V2: p50 3,021ms at the 3s default, which is why the control plane sets
    // `refresh-interval` to 500ms), and `DeployTokenIndexSuite` pins the fold that consumes it.
    val (createCode, created) =
      ankka("organizations", "tokens", "create", "acme", "--label", "e2e")
    assertEquals(createCode, 0, created)
    val secret = created.linesIterator
      .map(_.trim)
      .find(_.startsWith("ankka_"))
      .getOrElse(fail(s"no secret in:\n$created"))

    def asToken(args: String*): (Int, String) =
      cli((args ++ Seq("--url", url, "--token", secret))*)

    // Usable at once, which is the write-through admit: without it the token would not work on the
    // very node that minted it until the next read-refresh.
    assertEquals(asToken("organizations", "get", "acme")._1, 0)
    assertEquals(asToken("services", "list", "-p", Project)._1, 0)

    // And it can deploy — a member's job, done with the credential a CI job would hold. The
    // descriptor names one image and the command line another, which is the whole point of
    // `services deploy`: the applied service must report the one from the command line.
    val file = descriptorJson(FirstImage)
    val (deployCode, deployed) =
      asToken("services", "deploy", Service, SecondImage, "-f", file.toString, "-p", Project)
    assertEquals(deployCode, 0, deployed)
    assert(deployed.contains(SecondImage), deployed)
    // The file on disk is untouched, so a checked-in descriptor stays as its author wrote it.
    assert(Files.readString(file).contains(FirstImage), Files.readString(file))

    // The listing is a projection, and everything above it took under a tenth of a second, so the
    // row is not there yet on a machine where the projector lags. Retry on the thing that changes —
    // the row appearing — and assert on the identity that does not.
    var found = Option.empty[String]
    waitFor(30.seconds) {
      found = ankka("organizations", "tokens", "list", "acme")._2.linesIterator
        .find(_.contains("e2e"))
        .map(_.trim.takeWhile(!_.isWhitespace))
      found.isDefined
    }
    val id = found.get
    assertEquals(ankka("organizations", "tokens", "revoke", "acme", id)._1, 0)

    // The very next call, with no polling: this node evicted when it handled the revoke.
    val (refused, refusedOut) = asToken("organizations", "get", "acme")
    assertEquals(refused, 1, refusedOut)
    assert(refusedOut.contains("token was rejected"), refusedOut)

    // And it stays refused rather than reappearing when the journal is replayed.
    Thread.sleep(2000)
    assertEquals(asToken("organizations", "get", "acme")._1, 1)
  }

  /**
   * A private registry in the cluster, and a service that can only start because a credential was
   * registered for it.
   *
   * The registry runs inside the cluster and is reached through a NodePort at `127.0.0.1`, which is
   * the one address containerd treats as insecure by default — so no TLS and no per-node containerd
   * configuration is needed to make the pull work. It requires a password, which is the whole
   * point: without the credential the pull is `unauthorized`, so "the pod is Ready" means the
   * credential was used and not merely present.
   *
   * The negative half needs a tag the node has never seen. Every workload renders
   * `imagePullPolicy: IfNotPresent`, so once an image is on the node a restart succeeds with no
   * credential at all — a "clear the registry and restart" test would pass whether or not clearing
   * did anything. Two tags, and the second is deployed only after the credential is gone.
   */
  private val RegistryPort     = 30500
  private val RegistryHost     = s"127.0.0.1:$RegistryPort"
  private val RegistryUser     = "testuser"
  private val RegistryPassword = "testpass"

  /**
   * `testuser:testpass`, bcrypt, as `registry:2` requires.
   *
   * Deliberately public, like the `admin`/`admin` in this repository's Keycloak development secret:
   * it authenticates a registry that exists for the length of one test run inside a throwaway
   * container, reachable only from that container's loopback address.
   */
  private val Htpasswd =
    "testuser:$2y$05$m/vqALkk099g370Ua1osBOnKO728UAM4x3Tn0oZ0z2fylr54oLhHG"

  private val PrivateService = "private-cart"
  private val PrivateFirst   = s"$RegistryHost/cart:first"
  private val PrivateSecond  = s"$RegistryHost/cart:second"

  private def onNode(command: String*): (Int, String) =
    val result = k3s.execInContainer(command*)
    (result.getExitCode, result.getStdout + result.getStderr)

  /**
   * Waits for `services get` to say something, and fails with what it last said.
   *
   * `waitFor` reports only "condition did not hold", which for a pull failure is the least useful
   * sentence available: every interesting outcome — still provisioning, pulled when it should not
   * have, a control plane that stopped answering — looks identical from outside.
   */
  private def waitForService(what: String, timeout: FiniteDuration)(
      check: String => Boolean
  ): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var last     = ""
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      last = ankka("services", "get", PrivateService, "-o", "json", "-p", Project)._2
      passed = check(last)
      if !passed then Thread.sleep(2000)
    if !passed then fail(s"$what did not happen within $timeout. Last status:\n$last")

  /** The kubelet's own words for a pull it could not authenticate. */
  private def pullFailed(status: String): Boolean =
    Vector("ImagePull", "unauthorized", "authentication required", "401").exists(status.contains)

  private def installRegistry(): Unit =
    val manifest =
      s"""apiVersion: v1
kind: ConfigMap
metadata:
  name: registry-auth
  namespace: default
data:
  htpasswd: "$Htpasswd"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: registry
  namespace: default
spec:
  replicas: 1
  selector:
    matchLabels: { app: registry }
  template:
    metadata:
      labels: { app: registry }
    spec:
      containers:
        - name: registry
          image: registry:2
          ports: [{ containerPort: 5000 }]
          env:
            - { name: REGISTRY_AUTH, value: htpasswd }
            - { name: REGISTRY_AUTH_HTPASSWD_REALM, value: Registry }
            - { name: REGISTRY_AUTH_HTPASSWD_PATH, value: /auth/htpasswd }
          volumeMounts:
            - { name: auth, mountPath: /auth }
      volumes:
        - name: auth
          configMap:
            name: registry-auth
---
apiVersion: v1
kind: Service
metadata:
  name: registry
  namespace: default
spec:
  type: NodePort
  selector: { app: registry }
  ports:
    - port: 5000
      targetPort: 5000
      nodePort: $RegistryPort
"""
    val path = "/tmp/registry.yaml"
    val write = k3s.execInContainer(
      "sh",
      "-c",
      s"cat > $path <<'MANIFEST'\n$manifest\nMANIFEST"
    )
    assertEquals(write.getExitCode, 0, write.getStderr)
    // The exit code, not a grep of the output: `kubectl apply` of a multi-document manifest applies
    // everything else and exits 1 when one document fails.
    val applied = k3s.execInContainer("kubectl", "apply", "-f", path)
    assertEquals(applied.getExitCode, 0, applied.getStdout + applied.getStderr)

    waitFor(120.seconds) {
      val d = k8s.apps().deployments().inNamespace("default").withName("registry").get()
      d != null && Option(d.getStatus).flatMap(s => Option(s.getReadyReplicas)).exists(_ > 0)
    }

  private val ctr = Vector("ctr", "-a", "/run/k3s/containerd/containerd.sock", "-n", "k8s.io")

  /**
   * The reference containerd actually holds for a locally built image.
   *
   * An image imported from a `docker save` tar is stored under its *fully qualified* name,
   * `docker.io/library/sample-shopping-cart:latest`. The kubelet expands a short name to that form
   * before looking it up, so a pod naming `sample-shopping-cart:latest` finds it — and `ctr` does
   * no such expansion, answering `image "sample-shopping-cart:latest": not found` for an image
   * sitting right there. Asking containerd what it has, rather than assuming the prefix, also fails
   * with the listing attached instead of a bare exit code.
   */
  private def containerdRef(image: String): String =
    val (code, out) = onNode((ctr ++ Vector("images", "ls", "-q"))*)
    assertEquals(code, 0, out)
    val refs = out.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    refs
      .find(ref => ref == image || ref.endsWith(s"/$image"))
      .getOrElse(
        fail(s"containerd holds no image matching '$image'. It holds:\n${refs.mkString("\n")}")
      )

  /** The node's own platform, so an index can be filtered down to the one manifest it can run. */
  private lazy val nodePlatform: String =
    val (code, out) = onNode("uname", "-m")
    assertEquals(code, 0, out)
    out.trim match
      case "aarch64" | "arm64" => "linux/arm64"
      case "x86_64" | "amd64"  => "linux/amd64"
      case other               => fail(s"unrecognised node architecture '$other'")

  /**
   * Puts a locally built image in the in-cluster registry, pushed from the node.
   *
   * **Converted, not tagged.** A locally built image is an OCI *index* carrying an attestation
   * manifest for the pseudo-platform `unknown/unknown` beside the real one, and a `docker save`
   * round trip into containerd does not bring that manifest's content with it. Pushing the index
   * then fails with `content digest sha256:…: not found` for a blob that was never on this node —
   * which reads like a broken registry and is a complete image with an incomplete index above it.
   * Filtering to the node's own platform drops the attestation, and what reaches the registry is
   * exactly the manifest the kubelet would have pulled.
   */
  private def pushToRegistry(source: String, target: String): Unit =
    val (convertCode, convertOut) = onNode(
      (ctr ++ Vector(
        "images",
        "convert",
        "--platform",
        nodePlatform,
        "--oci",
        containerdRef(source),
        target
      ))*
    )
    assertEquals(convertCode, 0, convertOut)
    val (pushCode, pushOut) = onNode(
      (ctr ++ Vector(
        "images",
        "push",
        "--plain-http",
        "--user",
        s"$RegistryUser:$RegistryPassword",
        target
      ))*
    )
    assertEquals(pushCode, 0, pushOut)
    // And the image is gone from the node, so a later pull is a real pull rather than a cache hit.
    val (rmCode, rmOut) = onNode((ctr ++ Vector("images", "rm", target))*)
    assertEquals(rmCode, 0, rmOut)

  test(
    "13. a private registry: the credential is what makes the pull work, and clearing it shows"
  ) {
    // One real sample JVM on this node at a time. Two of them, plus CNPG, plus the identity
    // provider, plus a registry holding the image, starves a k3s container: every entity command
    // began timing out at ten seconds and the token index stopped following its own journal, which
    // looks exactly like the feature being broken. `cart` has finished its work by now (case 12 was
    // the last case to need it), so it goes before this case brings up its own.
    assertEquals(ankka("services", "delete", Service, "-p", Project)._1, 0)
    waitFor(120.seconds)(!ankka("services", "list", "-p", Project)._2.contains(Service))

    installRegistry()
    pushToRegistry(FirstImage, PrivateFirst)
    pushToRegistry(FirstImage, PrivateSecond)

    val descriptor = Files.createTempFile("private", ".json")
    Files.writeString(
      descriptor,
      s"""{"name":"$PrivateService","service":{"image":"$PrivateFirst"}}"""
    )

    // Without a credential the image cannot be pulled. Proving that first is what makes the rest
    // mean anything: a registry that turned out to allow anonymous pulls would make every later
    // assertion pass for the wrong reason.
    assertEquals(ankka("services", "apply", "-f", descriptor.toString, "-p", Project)._1, 0)
    // Generous, because the pod does not exist until this service's own database is provisioned, and
    // the pull cannot fail before there is something to pull.
    waitForService("the pull failed for want of a credential", 300.seconds)(pullFailed)

    // Register the credential and the same image starts. Nothing about the service changed, so this
    // is the sweep reading the project and re-projecting, not an apply carrying it.
    val (setCode, setOut) = ankka(
      "projects",
      "registry",
      "set",
      Project,
      "--server",
      RegistryHost,
      "--username",
      RegistryUser,
      "--password",
      RegistryPassword
    )
    assertEquals(setCode, 0, setOut)

    // The Secret is in the project's namespace, of the right type. Read as an admin: the control
    // plane itself cannot, which is the point of the grant.
    waitFor(60.seconds) {
      val secret = k8s.secrets().inNamespace(Namespace).withName("ankka-registry").get()
      secret != null && secret.getType == "kubernetes.io/dockerconfigjson"
    }
    // And the resource names it, so the operator puts it on the pod.
    waitFor(60.seconds) {
      Option(
        k8s.resources(classOf[AnkkaService]).inNamespace(Namespace).withName(PrivateService).get()
      )
        .flatMap(r => Option(r.getSpec))
        .flatMap(_.imagePullSecret)
        .contains("ankka-registry")
    }

    assertEquals(ankka("services", "restart", PrivateService, "-p", Project)._1, 0)
    waitForService("the service became Ready from the private image", 300.seconds)(
      _.contains("\"lifecycle\":\"Ready\"")
    )

    // Clear it, then deploy the *other* tag — one this node has never held, so the pull is real.
    assertEquals(ankka("projects", "registry", "clear", Project)._1, 0)
    val (deployCode, deployed) =
      ankka(
        "services",
        "deploy",
        PrivateService,
        PrivateSecond,
        "-f",
        descriptor.toString,
        "-p",
        Project
      )
    assertEquals(deployCode, 0, deployed)

    waitForService("the pull failed once the credential was cleared", 240.seconds)(pullFailed)

    Files.deleteIfExists(descriptor): Unit
  }
