package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.{ProofLookup, ServiceStatus}
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
  InPod,
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

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * `features/mcp-endpoints/tools.feature` on k3s (feature 054): the scenarios only a deployed
 * service can show. The shopping cart sample is the service: its MCP endpoint at `/mcp` has the
 * tools `get_cart`, `add_item` and `checkout`, and its `/mcp-keys` routes issue and revoke client
 * keys. Every other scenario runs on the kit, in `mcp`'s `ToolsFeatures`.
 */
class McpEndpointsClusterFeatures
    extends McpEndpointsClusterSteps("../features/mcp-endpoints/tools.feature"):

  override protected def customHostnames: Boolean = true

  override protected def ranElsewhere: Map[String, String] =
    elsewhere(
      Set(
        "a request to an MCP endpoint is answered by whichever instance receives it",
        "an MCP endpoint is served at every hostname the service answers at",
        "a paused service's MCP endpoint answers nothing"
      ),
      "ToolsFeatures (mcp): one service on loopback"
    )

/**
 * `features/mcp-endpoints/client-keys.feature` on k3s: a revoked client key refused at three real
 * pods, each pod's first refusal timed from the revoke. The sample's cart stands in for the
 * feature's `plumbers`. Every other scenario runs on the kit, in `mcp`'s `ClientKeysFeatures`,
 * which also runs the revocation scenario on a kit and two peers.
 */
class McpClientKeysClusterFeatures
    extends McpEndpointsClusterSteps("../features/mcp-endpoints/client-keys.feature"):

  override protected def ranElsewhere: Map[String, String] =
    elsewhere(
      Set("a revoked client key is refused on its next request on every instance"),
      "ClientKeysFeatures (mcp): on a kit and two peers"
    )

/** The k3s installation both suites deploy the sample into, and the steps of both features. */
abstract class McpEndpointsClusterSteps(feature: String)
    extends GherkinSuite(feature)
    with LogCapturing:

  override val munitTimeout: FiniteDuration = 15.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  /** Whether the suite needs an ACME authority, for a custom hostname. */
  protected def customHostnames: Boolean = false

  /**
   * Every scenario of the feature but `here`, each named as run by `suite`: read from the feature
   * itself, so a scenario added to it is run somewhere by default and named here.
   */
  protected def elsewhere(here: Set[String], suite: String): Map[String, String] =
    val names = Files
      .readAllLines(Paths.get(feature))
      .asScala
      .map(_.trim)
      .collect {
        case l if l.startsWith("Scenario Outline:") => l.stripPrefix("Scenario Outline:").trim
        case l if l.startsWith("Scenario:")         => l.stripPrefix("Scenario:").trim
      }
      .toSet
    assert(here.subsetOf(names), s"not scenarios of $feature: ${here.diff(names)}")
    names.diff(here).map(_ -> suite).toMap

  private val K3sImage = "rancher/k3s:v1.35.1-k3s1"
  private val Sample =
    s"sample-shopping-cart:${com.thinkmorestupidless.ankka.core.BuildInfo.imageTag}"
  private val BaseDomain = "example.test"
  private val Prefix     = "ankka"
  private val Project    = "checkout"
  private val Namespace  = s"$Prefix-$Project"
  private val Service    = "cart"
  private val Derived    = s"$Service-$Project.$BaseDomain"

  private lazy val identity = TestIdentity()
  private lazy val Admin =
    identity.token(
      "root",
      Some("root@example.test"),
      roles = Set("platform-admin"),
      expiresIn = 2.hours
    )

  private var k3s: K3sContainer         = null
  private var k8s: KubernetesClient     = null
  private var operator: Operator        = null
  private var testKit: AnkkaTestKit     = null
  private var acme: AcmeStack.Installed = null
  private var url: String               = ""
  private var config: Path              = null
  private var roots: Path               = null
  private var httpsPort: Int            = 0

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
      if customHostnames then acme = AcmeStack.install(k3s)
      roots =
        if customHostnames then AcmeStack.bundle(GatewayStack.exportCa(k8s), acme.root)
        else GatewayStack.exportCa(k8s)

      val issuer = Option.when(customHostnames)(AcmeStack.Issuer)
      val operatorSettings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        baseDomain = Some(BaseDomain),
        httpsPort = httpsPort,
        hostnameIssuer = issuer
      )
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
      operator.start()

      val deployConfig = DeployConfig.default.copy(
        namespacePrefix = Prefix,
        sweepInterval = 2.seconds,
        progressDeadline = 240.seconds,
        baseDomain = Some(BaseDomain),
        httpsPort = httpsPort,
        hostnameIssuer = issuer
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

      config = Files.createTempFile("ankka-mcp-cluster", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString

      ok(ankka("organizations", "create", "acme", "--name", "Acme")): Unit
      ok(ankka("projects", "create", Project, "--name", Project, "--organization", "acme")): Unit

  override def afterAll(): Unit =
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

  private def ankka(args: String*): Run =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Main.run(
      args ++ Seq("--url", url, "--token", Admin),
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
      fail(s"$what did not happen within $timeout${if last.isEmpty then "" else s"; last: $last"}")

  private def statusOf(name: String): Option[ServiceStatus] =
    val run = ankka("services", "get", name, "-p", Project, "-o", "json")
    Option.when(run.code == 0)(readFromString[ServiceStatus](run.out))

  private def lifecycle: String = statusOf(Service).map(_.lifecycle.toString).getOrElse("")

  private def pods: Vector[Pod] =
    k8s
      .pods()
      .inNamespace(Namespace)
      .withLabel("app.kubernetes.io/name", Service)
      .list()
      .getItems
      .asScala
      .toVector
      .filter(_.getMetadata.getDeletionTimestamp == null)

  private def readyPods: Vector[Pod] =
    pods.filter(p =>
      Option(p.getStatus.getContainerStatuses).exists(_.asScala.forall(_.getReady.booleanValue))
    )

  /**
   * The sample with `instances` instances, ready, and not paused: a scenario that paused it is
   * resumed by the next one that needs it.
   */
  private def deployed(instances: Int): Unit =
    val file = Files.createTempFile(Service, ".json")
    try
      Files.writeString(
        file,
        s"""{"name":"$Service","service":{"image":"$Sample","resources":{"autoscaling":{"minInstances":$instances}}}}"""
      ): Unit
      ok(ankka("services", "apply", "-f", file.toString, "-p", Project)): Unit
    finally Files.deleteIfExists(file): Unit
    if lifecycle == "Paused" then ok(ankka("services", "resume", Service, "-p", Project)): Unit
    waitFor(600.seconds, s"$Service is ready with $instances instances") {
      lifecycle == "Ready" && readyPods.size == instances
    }
    prober = None
    key = None

  // ── requests from inside the cluster and from the internet ───────────────

  /** A curl pod holding the cart's certificate (`InPod.prober`), made once the cart has one. */
  private var prober: Option[String] = None

  private def proberPod: String =
    prober.getOrElse {
      val made = InPod.prober(k3s, Namespace, Service)
      prober = Some(made)
      made
    }

  private val Tls = Vector(
    "--cert",
    "/var/run/secrets/ankka/service/tls.crt",
    "--key",
    "/var/run/secrets/ankka/service/tls.key",
    "--cacert",
    "/var/run/secrets/ankka/service/ca.crt"
  )

  private val McpHeaders = Vector(
    "Content-Type: application/json",
    "Accept: application/json, text/event-stream",
    "MCP-Protocol-Version: 2025-06-18"
  )

  /** An answer: its status, its headers in lower case, its body. */
  private final case class Answer(status: Int, headers: Map[String, String], body: String)

  private def parse(out: String): Answer =
    val lines = out.split("\n", -1).toVector
    val code  = lines.lastOption.map(_.trim).flatMap(_.toIntOption).getOrElse(0)
    // With `-D -` curl writes every response's headers, a blank line after each, then the body.
    val text      = lines.dropRight(1).mkString("\n").replace("\r", "")
    val lastBlock = text.lastIndexOf("\nHTTP/")
    val response  = if lastBlock >= 0 then text.drop(lastBlock + 1) else text
    val (head, body) = response.indexOf("\n\n") match
      case -1 => (response, "")
      case i  => (response.take(i), response.drop(i + 2))
    val headers = head.linesIterator
      .drop(1)
      .flatMap(l =>
        l.indexOf(':') match
          case -1 => None
          case i  => Some(l.take(i).trim.toLowerCase -> l.drop(i + 1).trim)
      )
      .toMap
    Answer(code, headers, body)

  /**
   * From the prober, presenting the cart's certificate, to `address` (a pod's IP or the Service).
   */
  private def inCluster(
      address: String,
      path: String,
      body: Option[String],
      headers: Seq[String]
  ): Answer =
    val method = "POST"
    val args =
      Vector("kubectl", "exec", "-n", Namespace, proberPod, "--", "curl", "-sS", "-m", "10") ++
        Vector("-X", method, "-D", "-", "-w", "\n%{http_code}") ++ Tls ++
        // A pod is reached by IP, which no certificate names; the server's check of ours is the one that matters.
        Vector("--insecure") ++
        headers.flatMap(h => Vector("-H", h)) ++
        body.toVector.flatMap(b => Vector("--data", b)) :+ s"https://$address:9000$path"
    val result = k3s.execInContainer(args*)
    if result.getExitCode != 0 then Answer(0, Map.empty, result.getStdout + result.getStderr)
    else parse(result.getStdout)

  /** `curl` on the host through the gateway, verifying against the installation's roots. */
  private def internet(
      host: String,
      path: String,
      method: String = "POST",
      body: Option[String] = None,
      headers: Seq[String] = Nil
  ): Answer =
    val args = Vector(
      "curl",
      "-sS",
      "--cacert",
      roots.toString,
      "--resolve",
      s"$host:$httpsPort:127.0.0.1",
      "-m",
      "10",
      "-X",
      method,
      "-D",
      "-",
      "-w",
      "\n%{http_code}"
    ) ++ headers.flatMap(h => Vector("-H", h)) ++ body.toVector.flatMap(b => Vector("--data", b)) :+
      s"https://$host:$httpsPort$path"
    assert(!args.exists(a => a == "-k" || a == "--insecure"), args.mkString(" "))
    val process = new ProcessBuilder(args*).start()
    val out     = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    process.getErrorStream.readAllBytes(): Unit
    process.waitFor()
    parse(out)

  private def rpc(method: String, params: String): String =
    s"""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""

  private def listTools: String = rpc("tools/list", "{}")

  private def callTool(name: String, arguments: String): String =
    rpc("tools/call", s"""{"name":"$name","arguments":$arguments}""")

  private def toolsIn(answer: Answer): Vector[String] =
    """"name":"([a-z_]+)"""".r.findAllMatchIn(answer.body).map(_.group(1)).toVector.distinct

  // ── client keys ───────────────────────────────────────────────────────────

  private final case class Key(id: String, clientKey: String)

  private var key: Option[Key] = None

  /** A client key the sample issues through its own route, for `subject`. */
  private def issue(subject: String): Key =
    val answer = inCluster(
      s"$Service.$Namespace.svc.cluster.local",
      "/mcp-keys",
      body = Some(s"""{"subject":"$subject","label":"the cluster suite"}"""),
      headers = Vector("Content-Type: application/json")
    )
    assertEquals(answer.status, 200, answer.body)
    val id     = """"id":"([0-9a-f]+)"""".r.findFirstMatchIn(answer.body).map(_.group(1))
    val secret = """"clientKey":"([^"]+)"""".r.findFirstMatchIn(answer.body).map(_.group(1))
    Key(id.getOrElse(fail(answer.body)), secret.getOrElse(fail("no key in the issue answer")))

  /** The scenario's key, for a subject of the suite's own when a scenario names none. */
  private def carried: Key =
    key.getOrElse {
      val made = issue("cluster-suite")
      key = Some(made)
      made
    }

  private def bearer(k: Key) = s"Authorization: Bearer ${k.clientKey}"

  // ── Background: tools.feature ─────────────────────────────────────────────

  Given(
    "a service {string} with an MCP endpoint at {string} whose tools are {string}, {string} and {string}"
  ) { (name: String, path: String, a: String, b: String, c: String) =>
    // The sample's MCP endpoint, as `CartMcpEndpoint` declares it.
    assertEquals(
      (name, path, Vector(a, b, c)),
      (Service, "/mcp", Vector("get_cart", "add_item", "checkout"))
    )
  }

  Given("the ACL of the MCP endpoint allows all") { () =>
    // The sample's endpoint admits its own client keys, so every request here carries one: a key
    // admits what an ACL that allows all would, and nothing these scenarios check depends on which.
    ()
  }

  // ── Background: client-keys.feature ───────────────────────────────────────

  Given(
    "a service {string} with an MCP endpoint whose tools are {string}, {string}, {string} and {string}"
  ) { (name: String, _: String, _: String, _: String, _: String) =>
    // The sample's cart stands in for the plumbers: what is proved here is the key, not the tools.
    assertEquals(name, "plumbers")
  }

  Given("the ACL of the MCP endpoint admits client keys") { () =>
    () // `CartMcpEndpoint.acl` is `ClientKeys.authenticate()`.
  }

  // ── Whichever instance receives it ────────────────────────────────────────

  Given("{string} is deployed with {int} instances") { (_: String, instances: Int) =>
    deployed(instances)
  }

  private var perPod: Vector[(String, Answer)] = Vector.empty
  private var initialized: Option[Answer]      = None

  When("an MCP client calls {string} at each instance in turn") { (tool: String) =>
    val ips = readyPods.map(_.getStatus.getPodIP)
    assertEquals(ips.size, 3, ips.toString)
    val cart = s"c-${java.util.UUID.randomUUID().toString.take(8)}"
    // What the cart holds, written through the first instance, so every instance's answer says it.
    val added = inCluster(
      ips.head,
      "/mcp",
      body = Some(callTool("add_item", s"""{"cartId":"$cart","productId":"tea","quantity":2}""")),
      headers = McpHeaders :+ bearer(carried)
    )
    assertEquals(added.status, 200, added.body)
    initialized = Some(
      inCluster(
        ips.head,
        "/mcp",
        body = Some(
          rpc(
            "initialize",
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"cluster","version":"1"}}"""
          )
        ),
        headers = McpHeaders :+ bearer(carried)
      )
    )
    perPod = ips.map(ip =>
      ip -> inCluster(
        ip,
        "/mcp",
        body = Some(callTool(tool, s"""{"cartId":"$cart"}""")),
        headers = McpHeaders :+ bearer(carried)
      )
    )
  }

  Then("each instance answers the tool call itself") { () =>
    // Each call went to one pod's own address, so the answer is that pod's.
    perPod.foreach { (ip, answer) =>
      assertEquals(answer.status, 200, s"$ip: ${answer.body}")
      assert(answer.body.contains("\"isError\":false"), s"$ip: ${answer.body}")
      assert(
        answer.body.contains("tea"),
        s"$ip does not answer what the cart holds: ${answer.body}"
      )
    }
  }

  Then("the MCP endpoint asks the MCP client to keep nothing between the calls") { () =>
    val answers = initialized.toVector ++ perPod.map(_._2)
    assertEquals(initialized.map(_.status), Some(200), initialized.toString)
    answers.foreach(a => assert(!a.headers.contains("mcp-session-id"), a.headers.toString))
  }

  // ── Every hostname ────────────────────────────────────────────────────────

  private var toolsAnswer: Option[Answer] = None

  Given("{string} is deployed and exposed with the custom hostname {string}") {
    (_: String, hostname: String) =>
      deployed(1)
      ok(ankka("services", "expose", Service, "-p", Project)): Unit
      proofRecords.put(ProofLookup.recordName(hostname), Vector(ProofLookup.recordValue(Project)))
      acme.resolveToInstallation(hostname)
      if !statusOf(Service).exists(_.customHostnames.exists(_.hostname == hostname)) then
        ok(ankka("services", "hostnames", "add", Service, hostname, "-p", Project)): Unit
      waitFor(300.seconds, s"$hostname is serving") {
        statusOf(Service).exists(
          _.customHostnames.exists(h => h.hostname == hostname && h.state == "serving")
        )
      }
  }

  private def toolsAt(host: String): Unit =
    val k = carried
    waitFor(120.seconds, s"the tools of $Service at $host") {
      val answer = internet(host, "/mcp", body = Some(listTools), headers = McpHeaders :+ bearer(k))
      toolsAnswer = Some(answer)
      answer.status == 200
    }

  When("an MCP client asks for the tools of {string} at the hostname the platform derived") {
    (_: String) =>
      toolsAt(Derived)
  }

  When("an MCP client asks for the tools of {string} at {string}") { (_: String, hostname: String) =>
    toolsAt(hostname)
  }

  Then("the MCP client is told the tools {string}, {string} and {string}") {
    (a: String, b: String, c: String) =>
      val answer = toolsAnswer.getOrElse(fail("no tools were asked for"))
      assertEquals(toolsIn(answer), Vector(a, b, c), answer.body)
  }

  // ── Paused ────────────────────────────────────────────────────────────────

  private var pausedAnswers: Option[(Answer, Answer)] = None

  Given("{string} is deployed and paused") { (_: String) =>
    deployed(1)
    ok(ankka("services", "expose", Service, "-p", Project)): Unit
    // The key is issued while the cart runs, so what a paused cart refuses is not a missing key.
    carried: Unit
    waitFor(120.seconds, s"$Service serving at $Derived") {
      internet(
        Derived,
        "/mcp",
        body = Some(listTools),
        headers = McpHeaders :+ bearer(carried)
      ).status == 200
    }
    ok(ankka("services", "pause", Service, "-p", Project)): Unit
    waitFor(120.seconds, s"$Service paused")(lifecycle == "Paused" && pods.isEmpty)
  }

  When("an MCP client asks for the tools of {string}") { (_: String) =>
    val k = carried
    waitFor(60.seconds, s"the gateway answering for paused $Service as it does for a route") {
      val mcp = internet(Derived, "/mcp", body = Some(listTools), headers = McpHeaders :+ bearer(k))
      val route = internet(Derived, "/carts/c1", method = "GET")
      pausedAnswers = Some(mcp -> route)
      mcp.status == route.status
    }
  }

  Then("the MCP client is told nothing, as a client of a route of {string} is") { (_: String) =>
    val (mcp, route) = pausedAnswers.getOrElse(fail("nothing was asked"))
    assertEquals(mcp.status, route.status, s"MCP ${mcp.body}; route ${route.body}")
    assert(mcp.status / 100 != 2, s"a paused service answered: ${mcp.body}")
    assert(!mcp.body.contains("get_cart"), mcp.body)
  }

  // ── Revoked on every instance ─────────────────────────────────────────────

  Given("a client key of {string} for the subject {string}") { (_: String, subject: String) =>
    val k = issue(subject)
    key = Some(k)
    // Admitted at every instance before the revoke, so a refusal after it is the revoke's.
    readyPods.map(_.getStatus.getPodIP).foreach { ip =>
      waitFor(30.seconds, s"the key admitted at $ip") {
        inCluster(
          ip,
          "/mcp",
          body = Some(listTools),
          headers = McpHeaders :+ bearer(k)
        ).status == 200
      }
    }
  }

  /** Each pod's IP and how long after the revoke it first refused the key, in milliseconds. */
  private var refusals: Vector[(String, Long)] = Vector.empty

  When("a handler of {string} revokes the client key") { (_: String) =>
    val k   = key.getOrElse(fail("no client key was issued"))
    val ips = readyPods.map(_.getStatus.getPodIP)
    assertEquals(ips.size, 3, ips.toString)
    // The revoke and the watch run in the prober, as one script, so the time measured is the
    // service's, not `kubectl exec`'s: `/proc/uptime` in hundredths of a second, from the moment
    // the revoke answered, polling each instance until it refuses, for at most five seconds.
    val curl    = (Tls :+ "--insecure").mkString(" ")
    val headers = (McpHeaders :+ bearer(k)).map(h => s"-H '$h'").mkString(" ")
    val script =
      s"""t() { tr -d . < /proc/uptime | cut -d' ' -f1; }
         |code=$$(curl -sS -m 10 -o /dev/null -w '%{http_code}' $curl -X DELETE https://$Service.$Namespace.svc.cluster.local:9000/mcp-keys/${k.id})
         |start=$$(t)
         |echo "revoke $$code"
         |left="${ips.mkString(" ")}"
         |while [ -n "$$left" ] && [ $$(( $$(t) - start )) -lt 500 ]; do
         |  next=""
         |  for ip in $$left; do
         |    got=$$(curl -s -m 2 -o /dev/null -w '%{http_code}' $curl $headers --data '${listTools}' https://$$ip:9000/mcp)
         |    if [ "$$got" = "401" ]; then echo "$$ip $$(( $$(t) - start ))"; else next="$$next $$ip"; fi
         |  done
         |  left=$$next
         |done
         |""".stripMargin
    val result =
      k3s.execInContainer("kubectl", "exec", "-n", Namespace, proberPod, "--", "sh", "-c", script)
    val out = result.getStdout
    assert(out.linesIterator.exists(_ == "revoke 200"), out + result.getStderr)
    refusals = out.linesIterator.toVector.flatMap { l =>
      l.split(' ') match
        case Array(ip, centis) if ips.contains(ip) => centis.toLongOption.map(c => ip -> c * 10)
        case _                                     => None
    }
    println(s"revoke-to-refusal (ms): ${refusals.map(_._2).mkString(", ")}")
  }

  Then("within one second a request carrying the client key is refused at every instance") { () =>
    assertEquals(refusals.map(_._1).toSet.size, 3, refusals.toString)
    refusals.foreach((ip, ms) => assert(ms <= 1000, s"$ip refused the key only after $ms ms"))
  }

  Then("no handler of a tool is run for it") { () =>
    val revoked = key.getOrElse(fail("no client key was issued"))
    val cart    = s"never-${java.util.UUID.randomUUID().toString.take(8)}"
    readyPods.map(_.getStatus.getPodIP).foreach { ip =>
      val refused = inCluster(
        ip,
        "/mcp",
        body = Some(
          callTool("add_item", s"""{"cartId":"$cart","productId":"untouched","quantity":1}""")
        ),
        headers = McpHeaders :+ bearer(revoked)
      )
      assertEquals(refused.status, 401, s"$ip: ${refused.body}")
    }
    // Read with a key that works: the refused calls added nothing.
    val fresh = issue("reader")
    val read = inCluster(
      s"$Service.$Namespace.svc.cluster.local",
      "/mcp",
      body = Some(callTool("get_cart", s"""{"cartId":"$cart"}""")),
      headers = McpHeaders :+ bearer(fresh)
    )
    assertEquals(read.status, 200, read.body)
    assert(!read.body.contains("untouched"), read.body)
  }
