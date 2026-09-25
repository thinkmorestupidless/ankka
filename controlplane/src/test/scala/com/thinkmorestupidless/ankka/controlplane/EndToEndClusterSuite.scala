package com.thinkmorestupidless.ankka.controlplane

import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.auth.AuthConfig
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

  override val munitTimeout: FiniteDuration = 8.minutes

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

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null
  private var testKit: AnkkaTestKit = null
  private var url: String           = ""
  private var config: Path          = null

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

      val server = HttpServer.at("127.0.0.1", 0)(
        ControlPlane.endpoints(ControlPlane.aclFor(auth), deployConfig, Some(auth))*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
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
