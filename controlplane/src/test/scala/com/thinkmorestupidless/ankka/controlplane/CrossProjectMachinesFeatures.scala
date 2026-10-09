package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.auth.oidc.TestIssuer
import com.thinkmorestupidless.ankka.cli.Main
import com.thinkmorestupidless.ankka.controlplane.api.{
  GrantDetail,
  MachineRegistered,
  MachineSummary,
  ServiceLifecycle,
  ServiceStatus
}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.auth.{MachineKeys, MachineSettings}
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  Fabric8AnkkaServiceClient,
  PodLogs,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.crd.AnkkaSerialization
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{
  ClusterImages,
  InPod,
  Operator,
  PkiStack,
  ServiceReconciler,
  Settings as OperatorSettings
}
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer
import org.testcontainers.utility.DockerImageName

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Base64
import scala.concurrent.duration.{Deadline, DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * `features/cross-project/machines.feature` on k3s: cert-manager and the installation's
 * authorities, CloudNativePG, the operator in this JVM, and the control plane and the CLI as an
 * owner uses them — the token route included, which a machine reaches here at the control plane's
 * own address.
 *
 * `affiliates` is the cart sample with `CART_WALLET=on`, whose `/v1/affiliates` routes are the
 * feature's. The gateway's hop to the service is made by a pod holding the gateway's own identity,
 * `ankka://gateway`, issued by the installation's service authority: what the service decides from
 * is the certificate it is handed and the bearer the request carries, which is exactly the hop a
 * request through Envoy arrives on (Envoy and the hostname are `ExposureClusterSuite`'s).
 *
 * The keys machine tokens are signed with are the control plane's, in this JVM; the cluster reads
 * them from a key set served inside it, at the address the operator writes into every project's
 * `machines.json`, as it would read the control plane's `keys` port.
 *
 * Disable with `-Dankka.cluster.tests=off`.
 */
class CrossProjectMachinesFeatures
    extends GherkinSuite("../features/cross-project/machines.feature")
    with LogCapturing:

  override val munitTimeout: FiniteDuration = 15.minutes

  override def munitIgnore: Boolean = sys.props.get("ankka.cluster.tests").contains("off")

  private val K3sImage = "rancher/k3s:v1.35.1-k3s1"
  private val SampleImage =
    s"sample-shopping-cart:${com.thinkmorestupidless.ankka.core.BuildInfo.imageTag}"
  private val Prefix      = "ankka"
  private val Edge        = "ankka-edge"
  private val Issuer      = "https://api.k3s.test"
  private val JwksUrl     = s"http://jwks.$Edge.svc.cluster.local/jwks.json"
  private val ReloadSlack = 12.seconds

  private lazy val identity = TestIdentity()
  private def tokenOf(person: String) =
    identity.token(person, Some(s"$person@example.test"), roles = Set.empty, expiresIn = 3.hours)

  private val keys = MachineKeys.inMemory()

  private var k3s: K3sContainer     = null
  private var k8s: KubernetesClient = null
  private var operator: Operator    = null
  private var testKit: AnkkaTestKit = null
  private var url: String           = ""
  private var config: Path          = null
  private val http                  = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    if !munitIgnore then
      k3s = new K3sContainer(DockerImageName.parse(K3sImage))
      k3s.start()
      ClusterImages.importInto(k3s, SampleImage)
      k8s = new KubernetesClientBuilder()
        .withConfig(Config.fromKubeconfig(k3s.getKubeConfigYaml))
        .withKubernetesSerialization(AnkkaSerialization())
        .build()
      for crd <- Seq("ankkaservice.yaml", "ankkaproject.yaml") do
        k8s.load(getClass.getResourceAsStream(s"/ankka/crd/$crd")).serverSideApply(): Unit
      PkiStack.install(k3s, k8s)
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
      waitFor(180.seconds, "CloudNativePG's controller") {
        PkiStack.jsonPath(
          k3s,
          "deployment",
          "-n",
          "cnpg-system",
          "cnpg-controller-manager",
          "{.status.readyReplicas}"
        ) == "1"
      }
      // The key set, served inside the cluster before any service starts to read it.
      keys.rotate(): Unit
      edge()

      val operatorSettings = OperatorSettings.default.copy(
        resyncInterval = 2.seconds,
        machines = Some(OperatorSettings.MachineIssuer(Issuer, JwksUrl))
      )
      operator = new Operator(k8s, operatorSettings, ServiceReconciler(k8s, operatorSettings))
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
        ControlPlane.endpoints(
          identity.acl(),
          deployConfig,
          auth = Some(identity.config()),
          logs = Some(new PodLogs(k8s, Prefix)),
          secrets = Some(projector),
          topics = Some(projector),
          schemas = Some(projector),
          machineSettings = MachineSettings(Issuer, tokenRate = 12),
          machineKeys = Some(keys)
        )*
      )
      testKit = AnkkaTestKit.start(
        ControlPlane.componentsWith(projector),
        Seq(ProjectionRuntime(), projector, server)
      )
      url = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"
      config = Files.createTempFile("ankka-machines", ".json")
      Files.delete(config)
      sys.props("ankka.config") = config.toString

  override def afterAll(): Unit =
    if testKit != null then identity.stop()
    sys.props.remove("ankka.config"): Unit
    if config != null then Files.deleteIfExists(config): Unit
    if testKit != null then testKit.stop()
    if operator != null then operator.close()
    if k8s != null then k8s.close()
    if k3s != null then k3s.stop()

  // ── the edge: the gateway's identity, and the key set ─────────────────────

  /**
   * A namespace of the platform's holding a pod with the gateway's certificate, and a plain server
   * of the key set: the hop from the gateway to a service, and the address the services read keys
   * at.
   */
  private def edge(): Unit =
    val jwks = writeToString(keys.jwks)
    val manifest =
      s"""apiVersion: v1
         |kind: Namespace
         |metadata:
         |  name: $Edge
         |  labels: { app.kubernetes.io/managed-by: ankka }
         |---
         |apiVersion: cert-manager.io/v1
         |kind: Certificate
         |metadata: { name: gateway, namespace: $Edge }
         |spec:
         |  secretName: gateway-tls
         |  uris: [ "ankka://gateway" ]
         |  usages: [server auth, client auth]
         |  privateKey: { algorithm: RSA, size: 2048, encoding: PKCS8 }
         |  issuerRef: { name: ankka-service, kind: ClusterIssuer, group: cert-manager.io }
         |---
         |apiVersion: v1
         |kind: ConfigMap
         |metadata: { name: jwks, namespace: $Edge }
         |data:
         |  jwks.json: '$jwks'
         |---
         |apiVersion: v1
         |kind: Pod
         |metadata:
         |  name: jwks
         |  namespace: $Edge
         |  labels: { app: jwks }
         |spec:
         |  containers:
         |    - name: httpd
         |      image: busybox:1.36
         |      command: ["httpd", "-f", "-p", "8080", "-h", "/www"]
         |      volumeMounts: [{ name: jwks, mountPath: /www }]
         |  volumes: [{ name: jwks, configMap: { name: jwks } }]
         |---
         |apiVersion: v1
         |kind: Service
         |metadata: { name: jwks, namespace: $Edge }
         |spec:
         |  selector: { app: jwks }
         |  ports: [{ port: 80, targetPort: 8080 }]
         |""".stripMargin
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      "/tmp/edge.yaml"
    )
    PkiStack.kubectl(k3s, "apply", "-f", "/tmp/edge.yaml"): Unit
    waitFor(180.seconds, "the gateway's certificate") {
      PkiStack.jsonPath(
        k3s,
        "certificate",
        "-n",
        Edge,
        "gateway",
        """{.status.conditions[?(@.type=="Ready")].status}"""
      ) == "True"
    }
    val gateway =
      s"""apiVersion: v1
         |kind: Pod
         |metadata:
         |  name: gateway
         |  namespace: $Edge
         |  labels: { app.kubernetes.io/managed-by: ankka }
         |spec:
         |  containers:
         |    - name: curl
         |      image: curlimages/curl:8.11.1
         |      command: ["sleep", "infinity"]
         |      volumeMounts:
         |        - { name: service, mountPath: /var/run/secrets/ankka/service, readOnly: true }
         |  volumes:
         |    - name: service
         |      secret: { secretName: gateway-tls }
         |""".stripMargin
    k3s.copyFileToContainer(
      Transferable.of(gateway.getBytes(StandardCharsets.UTF_8)),
      "/tmp/gateway.yaml"
    )
    PkiStack.kubectl(k3s, "apply", "-f", "/tmp/gateway.yaml"): Unit
    for pod <- Seq("gateway", "jwks") do
      PkiStack.kubectl(
        k3s,
        "wait",
        "-n",
        Edge,
        "--for=condition=Ready",
        s"pod/$pod",
        "--timeout=180s"
      ): Unit

  // ── the CLI and the cluster ───────────────────────────────────────────────

  private final case class Run(code: Int, out: String, err: String):
    def all: String = out + err

  private def ankka(as: String, args: String*): Run =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val code = Main.run(
      args ++ Seq("--url", url, "--token", as),
      PrintStream(out, true, StandardCharsets.UTF_8),
      PrintStream(err, true, StandardCharsets.UTF_8)
    )
    Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private def ok(run: Run): Run =
    assertEquals(run.code, 0, run.all)
    run

  private def waitFor(timeout: FiniteDuration, what: String)(check: => Boolean): Unit =
    val deadline = timeout.fromNow
    var passed   = false
    while !passed && deadline.hasTimeLeft() do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(1000)
    if !passed then fail(s"$what did not happen within $timeout")

  private def ns(project: String) = s"$Prefix-$project"

  private var people: Map[String, String] = Map.empty
  private def person(name: String): String =
    people.getOrElse(name, { val t = tokenOf(name); people += name -> t; t })

  private var organizations: Set[String]      = Set.empty
  private var deployed: Set[(String, String)] = Set.empty
  private var projectOf: Map[String, String]  = Map.empty
  private var ownerOf: Map[String, String]    = Map.empty

  private def statusOf(name: String, project: String, as: String): Option[ServiceStatus] =
    val run = ankka(as, "services", "get", name, "-p", project, "-o", "json")
    Option.when(run.code == 0)(readFromString[ServiceStatus](run.out))

  private def apply(service: String, project: String, env: Map[String, String], as: String): Unit =
    val variables = env.map((k, v) => s"""{"name":"$k","value":"$v"}""").mkString(",")
    val file      = Files.createTempFile("ankka-machines", ".json")
    try
      Files.writeString(
        file,
        s"""{"name":"$service","service":{"image":"$SampleImage","env":[$variables]}}"""
      ): Unit
      ok(ankka(as, "services", "apply", "-f", file.toString, "-p", project)): Unit
    finally Files.deleteIfExists(file): Unit

  private def podsOf(service: String, project: String) =
    k8s
      .pods()
      .inNamespace(ns(project))
      .withLabel("app.kubernetes.io/name", service)
      .list()
      .getItems
      .asScala
      .toVector

  private def ready(service: String, project: String, as: String): Unit =
    waitFor(360.seconds, s"$service of $project being Ready") {
      statusOf(service, project, as).exists(s =>
        s.lifecycle == ServiceLifecycle.Ready && s.readyInstances >= 1 && s.confirmed
      ) && podsOf(service, project).forall(pod =>
        Option(pod.getStatus.getConditions)
          .exists(_.asScala.exists(c => c.getType == "Ready" && c.getStatus == "True"))
      )
    }

  /** Each pod of `service` by uid, with how often its containers have restarted. */
  private def restarts(service: String, project: String): Map[String, Int] =
    podsOf(service, project).map { pod =>
      pod.getMetadata.getUid -> Option(pod.getStatus.getContainerStatuses)
        .map(_.asScala.map(_.getRestartCount.intValue).sum)
        .getOrElse(0)
    }.toMap

  /** A file every running pod of `service` mounts in its project's ConfigMap. */
  private def mounted(service: String, project: String, file: String): Vector[String] =
    podsOf(service, project)
      .filter(_.getStatus.getPhase == "Running")
      .map(pod =>
        PkiStack.kubectl(
          k3s,
          "exec",
          "-n",
          ns(project),
          pod.getMetadata.getName,
          "-c",
          service,
          "--",
          "cat",
          s"/var/run/ankka/project/$file"
        )
      )

  // ── the scenario's state ──────────────────────────────────────────────────

  private var registered: Option[MachineRegistered]           = None
  private var registration: Run                               = Run(0, "", "")
  private var machineOrganization: String                     = ""
  private var held: Option[String]                            = None
  private var tokenAnswer: (Int, String, Map[String, String]) = (0, "", Map.empty)
  private var lastResponse: (Int, String)                     = (0, "")
  private var lastRoute: Option[String]                       = None
  private var lastBearer: Option[String]                      = None
  private var grants: Vector[(String, String)]                = Vector.empty
  private var revokedAt: Option[Deadline]                     = None
  private var restartsBefore: Map[String, Int]                = Map.empty
  private var issuers: Map[String, TestIssuer]                = Map.empty

  override def beforeEach(context: BeforeEach): Unit =
    registered = None
    registration = Run(0, "", "")
    held = None
    tokenAnswer = (0, "", Map.empty)
    lastResponse = (0, "")
    lastRoute = None
    lastBearer = None
    grants = Vector.empty
    revokedAt = None
    restartsBefore = Map.empty

  override def afterEach(context: AfterEach): Unit =
    if !munitIgnore then
      val owner = ownerOf.getOrElse(machineOrganization, "")
      for (id, p) <- grants do
        ankka(person(owner), "projects", "grants", "revoke", id, "-p", p): Unit
      registered.foreach(m =>
        ankka(
          person(owner),
          "organizations",
          "machines",
          "delete",
          machineOrganization,
          m.name
        ): Unit
      )
      issuers.values.foreach(_.stop())
      issuers = Map.empty

  // ── requests ──────────────────────────────────────────────────────────────

  /** A request through the gateway's hop, with `bearer` when there is one. */
  private def through(service: String, route: String, bearer: Option[String]): (Int, String) =
    val (_, path) = route.trim.span(_ != ' ')
    lastRoute = Some(route)
    lastBearer = bearer
    InPod.curl(
      k3s,
      Edge,
      "gateway",
      s"https://$service.${ns(projectOf(service))}.svc.cluster.local:9000${path.trim}",
      headers = bearer.toSeq.map(t => s"Authorization: Bearer $t")
    )

  private def tokenRoute(clientId: String, secret: String): (Int, String, Map[String, String]) =
    val form =
      s"grant_type=client_credentials&client_id=${java.net.URLEncoder.encode(clientId, StandardCharsets.UTF_8)}" +
        s"&client_secret=${java.net.URLEncoder.encode(secret, StandardCharsets.UTF_8)}"
    val response = http.send(
      HttpRequest
        .newBuilder(URI.create(s"$url/oauth/token"))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    val headers =
      response.headers().map().asScala.map((k, v) => k.toLowerCase -> v.asScala.mkString(",")).toMap
    (response.statusCode, response.body, headers)

  private def accessToken(body: String): String =
    "\"access_token\":\"([^\"]+)\"".r.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))

  private def claimsOf(token: String): String =
    String(Base64.getUrlDecoder.decode(token.split('.')(1)), StandardCharsets.UTF_8)

  private def machine: MachineRegistered = registered.getOrElse(fail("no machine was registered"))

  private def holdToken(): String =
    val answer = tokenRoute(machine.clientId, machine.clientSecret)
    assertEquals(answer._1, 200, answer._2)
    val token = accessToken(answer._2)
    held = Some(token)
    token

  private def register(owner: String, name: String, organization: String): Run =
    machineOrganization = organization
    registration = ankka(
      person(owner),
      "organizations",
      "machines",
      "register",
      organization,
      name,
      "-o",
      "json"
    )
    if registration.code == 0 then
      registered = Some(readFromString[MachineRegistered](registration.out))
    registration

  // ═══ Given ════════════════════════════════════════════════════════════════

  Given("an organization {string} whose owner is {string}") {
    (organization: String, owner: String) =>
      if !organizations(organization) then
        val run =
          ankka(person(owner), "organizations", "create", organization, "--name", organization)
        assert(run.code == 0 || run.all.contains("already"), run.all)
        organizations += organization
      ownerOf += organization -> owner
  }

  Given("a deployed service {string} in the project {string} of {string}") {
    (service: String, project: String, organization: String) =>
      val owner = person(ownerOf(organization))
      projectOf += service -> project
      if !deployed((service, project)) then
        val created =
          ankka(
            owner,
            "projects",
            "create",
            project,
            "--name",
            project,
            "--organization",
            organization
          )
        assert(created.code == 0 || created.all.contains("already"), created.all)
        apply(service, project, Map("CART_WALLET" -> "on"), owner)
        ready(service, project, owner)
        deployed += ((service, project))
      // Where machines' tokens come from reaches every pod of the project from its creation.
      waitFor(180.seconds, s"$service reading where machines' tokens come from") {
        val files = mounted(service, project, "machines.json")
        files.nonEmpty && files.forall(_.contains(JwksUrl))
      }
  }

  Given("{string} is exposed")((service: String) =>
    // The gateway's hop is the edge pod's; the hostname and Envoy are ExposureClusterSuite's.
    assert(projectOf.contains(service))
  )

  Given(
    "{string} has an HTTP endpoint whose ACL admits granted callers, with the route {string}"
  ) { (service: String, route: String) =>
    assertEquals((service, route), ("affiliates", "GET /v1/affiliates/attribution"))
  }

  Given("{string} has the route {string} whose ACL allows all") { (service: String, route: String) =>
    assertEquals((service, route), ("affiliates", "GET /v1/affiliates/feed"))
  }

  Given("{string} has registered {string} as a machine of {string}") {
    (owner: String, name: String, organization: String) =>
      ok(register(owner, name, organization)): Unit
  }

  Given(
    "{string} has granted the registered machine {string} of {string} the route {string} of {string}"
  ) { (owner: String, name: String, organization: String, route: String, service: String) =>
    val project      = projectOf(service)
    val (method, at) = route.trim.span(_ != ' ')
    val run = ok(
      ankka(
        person(owner),
        "projects",
        "grants",
        "make",
        s"machine:$organization/$name",
        "route",
        service,
        method,
        at.trim,
        "-p",
        project,
        "-o",
        "json"
      )
    )
    val grant = readFromString[GrantDetail](run.out)
    grants :+= (grant.id -> project)
    waitFor(180.seconds, s"grant ${grant.id} reaching every pod of $service") {
      val files = mounted(service, project, "grants.json")
      files.nonEmpty && files.forall(_.contains(grant.id))
    }
    Thread.sleep(ReloadSlack.toMillis)
  }

  Given("the registered machine holds a machine token")(() => holdToken(): Unit)

  Given(
    "the registered machine has been served that route with a machine token issued a moment ago"
  ) { () =>
    val token = holdToken()
    lastResponse = through("affiliates", "GET /v1/affiliates/attribution", Some(token))
    assertEquals(lastResponse._1, 200, lastResponse._2)
    restartsBefore = restarts("affiliates", projectOf("affiliates"))
  }

  Given("an issuer {string} that signs tokens for the audience {string}") {
    (name: String, audience: String) =>
      issuers += name -> TestIssuer(
        issuer = s"https://$name.example.test/realms/$name",
        name = name,
        defaultAudience = audience
      )
  }

  Given(
    "{string} lists the issuer {string} with the audience {string}, and no issuer for machine tokens"
  ) { (service: String, name: String, audience: String) =>
    val project = projectOf(service)
    val owner   = person(ownerOf.values.head)
    val issuer  = issuers.getOrElse(name, fail(s"no issuer '$name'"))
    val upper   = name.toUpperCase
    apply(
      service,
      project,
      Map(
        "CART_WALLET"                 -> "on",
        "ANKKA_AUTH_ISSUERS"          -> name,
        s"ANKKA_AUTH_${upper}_ISSUER" -> issuer.issuer,
        // Fetched only when a token of the issuer is presented, which no scenario here does.
        s"ANKKA_AUTH_${upper}_JWKS_URL" -> s"${issuer.issuer}/protocol/openid-connect/certs",
        s"ANKKA_AUTH_${upper}_AUDIENCE" -> audience
      ),
      owner
    )
    waitFor(360.seconds, s"$service listing the issuer '$name'") {
      podsOf(service, project).nonEmpty && podsOf(service, project).forall(pod =>
        pod.getSpec.getContainers.asScala.exists(
          _.getEnv.asScala.exists(e => e.getName == "ANKKA_AUTH_ISSUERS" && e.getValue == name)
        ) && Option(pod.getStatus.getConditions)
          .exists(_.asScala.exists(c => c.getType == "Ready" && c.getStatus == "True"))
      )
    }
  }

  Given("{string} has the authenticated route {string}") { (service: String, route: String) =>
    assertEquals((service, route), ("affiliates", "GET /v1/affiliates/report"))
  }

  // ═══ When ═════════════════════════════════════════════════════════════════

  When("{string} registers {string} as a machine of {string}") {
    (owner: String, name: String, organization: String) =>
      register(owner, name, organization): Unit
  }

  When(
    "the registered machine asks the token route for a machine token with its client id and client secret"
  ) { () =>
    tokenAnswer = tokenRoute(machine.clientId, machine.clientSecret)
  }

  When(
    "the registered machine sends a request with its machine token to the route {string} at the hostname of {string}"
  ) { (route: String, service: String) =>
    lastResponse = through(service, route, Some(held.getOrElse(fail("the machine holds no token"))))
  }

  When(
    "a person on the internet sends a request with no token to the route {string} at the hostname of {string}"
  ) { (route: String, service: String) =>
    lastResponse = through(service, route, None)
  }

  When(
    "a person on the internet sends a request with a token from {string} to the route {string} at the hostname of {string}"
  ) { (name: String, route: String, service: String) =>
    val issuer = issuers.getOrElse(name, fail(s"no issuer '$name'"))
    lastResponse = through(service, route, Some(issuer.token("eve")))
  }

  When("{string} revokes the grant") { (owner: String) =>
    val (id, project) = grants.lastOption.getOrElse(fail("no grant was made"))
    ok(ankka(person(owner), "projects", "grants", "revoke", id, "-p", project)): Unit
    grants = grants.dropRight(1)
    revokedAt = Some(Deadline.now)
  }

  When("{string} deletes the registered machine {string}") { (owner: String, name: String) =>
    ok(ankka(person(owner), "organizations", "machines", "delete", machineOrganization, name)): Unit
  }

  When(
    "the registered machine asks the token route for a machine token more often than the installation allows"
  ) { () =>
    var answers = Vector.empty[(Int, String, Map[String, String])]
    while answers.size < 20 && !answers.lastOption.exists(_._1 == 429) do
      answers :+= tokenRoute(machine.clientId, machine.clientSecret)
    tokenAnswer = answers.last
  }

  // ═══ Then ═════════════════════════════════════════════════════════════════

  Then("{string} is shown the client id and the client secret of the registered machine {string}") {
    (_: String, name: String) =>
      assertEquals(registration.code, 0, registration.all)
      assertEquals(machine.name, name)
      assertEquals(machine.clientId, s"machine:$machineOrganization/$name")
      assert(machine.clientSecret.matches("[0-9a-f]{64}"), machine.clientSecret)
  }

  Then("the client secret is never shown again") { () =>
    val owner = person(ownerOf(machineOrganization))
    val listed =
      ankka(owner, "organizations", "machines", "list", machineOrganization, "-o", "json")
    assert(!listed.all.contains(machine.clientSecret), listed.all)
  }

  Then(
    "the list of the registered machines of {string} shows {string} with who registered it and when, and no client secret"
  ) { (organization: String, name: String) =>
    val owner = person(ownerOf(organization))
    var rows  = Vector.empty[MachineSummary]
    waitFor(60.seconds, s"$name listed") {
      val run = ankka(owner, "organizations", "machines", "list", organization, "-o", "json")
      rows = readFromString[Vector[MachineSummary]](run.out)
      rows.exists(_.name == name)
    }
    val row = rows.find(_.name == name).get
    assert(row.registeredBy.isDefined && row.registeredAt.isDefined, row.toString)
  }

  Then("it is given a machine token that names {string} of {string} and no grant") {
    (name: String, organization: String) =>
      assertEquals(tokenAnswer._1, 200, tokenAnswer._2)
      val claims = claimsOf(accessToken(tokenAnswer._2))
      assert(claims.contains(s""""sub":"machine:$organization/$name""""), claims)
      assert(!claims.contains("grant"), claims)
  }

  Then("the machine token expires {string} minutes after it was issued") { (minutes: String) =>
    val claims = claimsOf(accessToken(tokenAnswer._2))
    def number(claim: String) =
      s""""$claim":(\\d+)""".r
        .findFirstMatchIn(claims)
        .map(_.group(1).toLong)
        .getOrElse(fail(claims))
    assertEquals(number("exp") - number("iat"), minutes.toLong * 60)
  }

  Then("the handler runs") { () =>
    assertEquals(lastResponse._1, 200, lastResponse._2)
  }

  Then(
    "the handler reads the calling workload as the registered machine {string} of the organization {string}"
  ) { (name: String, organization: String) =>
    assert(lastResponse._2.endsWith(s"machine:$organization/$name"), lastResponse._2)
  }

  Then("the request is refused")(() => assertEquals(lastResponse._1, 403, lastResponse._2))

  Then("no handler runs") { () =>
    // Every affiliates handler answers 200 with its value; a 403 is the ACL's, before any handler.
    assertEquals(lastResponse._1, 403, lastResponse._2)
    assert(!lastResponse._2.contains("attribution for"), lastResponse._2)
  }

  Then("the refusal says nothing of which grants exist") { () =>
    for word <- Vector("grant", "machine", "affiliate-network") do
      assert(!lastResponse._2.toLowerCase.contains(word), lastResponse._2)
    // The token was the installation's and was read: the same machine reaches a route open to all.
    val feed = through("affiliates", "GET /v1/affiliates/feed", held)
    assert(feed._2.endsWith(s"machine:$machineOrganization/${machine.name}"), feed._2)
  }

  Then("the handler reads the calling workload as the gateway") { () =>
    assertEquals(lastResponse, (200, "feed for gateway"))
  }

  Then("the same request to {string} is refused") { (route: String) =>
    val bearer = lastBearer
    assertEquals(through("affiliates", route, bearer)._1, 403)
  }

  Then(
    "within {string} seconds the registered machine is refused that route with the same machine token"
  ) { (seconds: String) =>
    val deadline = revokedAt.getOrElse(fail("nothing was revoked")) + seconds.toInt.seconds
    val route    = lastRoute.getOrElse(fail("no route was served"))
    var last     = (0, "")
    while deadline.hasTimeLeft() && last._1 != 403 do
      last = through("affiliates", route, held)
      if last._1 != 403 then Thread.sleep(2000)
    assertEquals(last._1, 403, s"still served $seconds seconds after the revocation: $last")
  }

  Then("no instance of {string} is restarted") { (service: String) =>
    assertEquals(restarts(service, projectOf(service)), restartsBefore)
  }

  Then("the token route refuses the client id and client secret of {string}") { (_: String) =>
    assertEquals(tokenRoute(machine.clientId, machine.clientSecret)._1, 401)
  }

  Then("{string} is no longer listed among the registered machines of {string}") {
    (name: String, organization: String) =>
      val owner = person(ownerOf(organization))
      waitFor(60.seconds, s"$name gone from the listing") {
        val run = ankka(owner, "organizations", "machines", "list", organization, "-o", "json")
        !readFromString[Vector[MachineSummary]](run.out).exists(_.name == name)
      }
      registered = None
  }

  Then(
    "the handler is told a principal whose subject names the registered machine {string} of {string}"
  ) { (name: String, organization: String) =>
    assert(
      lastResponse._2.startsWith(s"report for machine:$organization/$name as "),
      lastResponse._2
    )
  }

  Then("the token route refuses its client id for a while") { () =>
    assertEquals(tokenAnswer._1, 429, tokenAnswer._2)
    assert(tokenAnswer._3.get("retry-after").exists(_.toLong >= 1), tokenAnswer._3.toString)
  }

  Then("the machine token it already holds still admits it") { () =>
    val feed = through("affiliates", "GET /v1/affiliates/feed", held)
    assertEquals(feed, (200, s"feed for machine:$machineOrganization/${machine.name}"))
  }
