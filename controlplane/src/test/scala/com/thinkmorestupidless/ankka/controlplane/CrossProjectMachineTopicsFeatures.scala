package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.auth.oidc.TestIssuer
import com.thinkmorestupidless.ankka.controlplane.api.{GrantDetail, MachineRegistered}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.auth.MachineSettings
import com.thinkmorestupidless.ankka.operator.{
  GatewayStack,
  MachineDefaults,
  PkiStack,
  Settings as OperatorSettings
}
import com.github.dockerjava.api.model.{ExposedPort, PortBinding, Ports}
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.{
  ByteArrayDeserializer,
  ByteArraySerializer,
  StringDeserializer,
  StringSerializer
}
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.Properties
import scala.concurrent.duration.{Deadline, DurationInt}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * `features/cross-project/machine-topics.feature` on k3s: the installation of
 * `BrokerClusterFeatures` with the Gateway in front of it (`GatewayStack`) and the
 * `broker-external` component applied as an installation applies it, its values filled in for this
 * node: the base domain `127.0.0.1.sslip.io`, the public certificate from a ClusterIssuer over the
 * local root, and the node's NodePort 30094 published on the host's 9094, so
 * `broker.127.0.0.1.sslip.io:9094` from this JVM reaches the Gateway as a partner's client on the
 * internet would.
 *
 * The machine's client is Apache Kafka's own Java client in this JVM, configured as the contract's
 * partner properties say: SASL_SSL, OAUTHBEARER, client credentials against the control plane's
 * token route. The control plane runs in this JVM, so the broker reads the keys it signs with from
 * a key set served inside the cluster, and its token re-authentication interval is set to a minute,
 * so the deleted machine's scenario does not wait fifteen.
 *
 * Disable with `-Dankka.cluster.tests=off`.
 */
class CrossProjectMachineTopicsFeatures
    extends BrokerClusterFeatures("machine-topics.feature", "cross-project"):

  private val BaseDomain = "127.0.0.1.sslip.io"
  private val Bootstrap  = s"broker.$BaseDomain:9094"
  private val Issuer     = s"https://api.$BaseDomain"
  private val Edge       = "ankka-edge"
  private val JwksUrl    = s"http://jwks.$Edge.svc.cluster.local/jwks.json"

  /** A consume rate small enough to throttle measurably on one node. */
  private val ConsumeRate = 65536L

  override protected def ranElsewhere: Map[String, String] = Map(
    // One installation per suite: this one exposes its broker. The effect an installation that does
    // not reports is the listing's, and the user a machine is given the operator's rendering.
    "an installation that has not exposed its broker reports a machine's topic grant as not in effect" ->
      "CrossProjectListingFeatures and MachineRenderingSuite"
  )

  override protected def configure(container: K3sContainer): K3sContainer =
    container
      .withCreateContainerCmdModifier(cmd =>
        val bindings = Ports()
        bindings.bind(ExposedPort.tcp(30094), Ports.Binding.bindPort(9094))
        cmd.getHostConfig.withPortBindings(
          (Option(cmd.getHostConfig.getPortBindings)
            .map(_.getBindings.asScala.toMap)
            .getOrElse(Map.empty) ++
            bindings.getBindings.asScala).toVector
            .flatMap((port, bs) => Option(bs).toVector.flatten.map(b => PortBinding(b, port)))
            .asJava
        ): Unit
        cmd.withExposedPorts(
          (Option(cmd.getExposedPorts).toVector.flatten :+ ExposedPort.tcp(30094)).asJava
        ): Unit
      )
      .asInstanceOf[K3sContainer]

  override protected def adjust(settings: OperatorSettings): OperatorSettings =
    settings.copy(machineDefaults = MachineDefaults(consumeBytesPerSecond = ConsumeRate))

  override protected def machineSettings: MachineSettings =
    MachineSettings(Issuer, brokerBootstrap = Some(Bootstrap))

  /** The Gateway, the key set inside the cluster, and the broker exposed through it. */
  override protected def installed(): Unit =
    GatewayStack.install(k3s, k8s, PkiStack.repoRoot, BaseDomain)
    // Strimzi was started before the Gateway API's types existed here. Restarted, so a `tlsroute`
    // listener finds `TLSRoute` served, whether or not its operator reads the API at start only.
    node("kubectl", "rollout", "restart", "deployment/strimzi-cluster-operator", "-n", Broker): Unit
    node(
      "kubectl",
      "rollout",
      "status",
      "deployment/strimzi-cluster-operator",
      "-n",
      Broker,
      "--timeout=180s"
    ): Unit
    machineKeys.rotate(): Unit
    val jwks = writeToString(machineKeys.jwks)
    apply(
      s"""apiVersion: v1
         |kind: Namespace
         |metadata: { name: $Edge }
         |---
         |apiVersion: v1
         |kind: ConfigMap
         |metadata: { name: jwks, namespace: $Edge }
         |data:
         |  jwks.json: '$jwks'
         |---
         |apiVersion: v1
         |kind: Pod
         |metadata: { name: jwks, namespace: $Edge, labels: { app: jwks } }
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
         |""".stripMargin,
      "edge"
    )
    // The public issuer the component names: a ClusterIssuer over the local root, whose key a
    // ClusterIssuer reads from cert-manager's own namespace.
    val root = k8s.secrets().inNamespace("ankka-gateway").withName("ankka-root-ca").get()
    apply(
      s"""apiVersion: v1
         |kind: Secret
         |metadata: { name: ankka-root-ca, namespace: cert-manager }
         |type: kubernetes.io/tls
         |data:
         |  tls.crt: ${root.getData.get("tls.crt")}
         |  tls.key: ${root.getData.get("tls.key")}
         |  ca.crt: ${root.getData.get("ca.crt")}
         |---
         |apiVersion: cert-manager.io/v1
         |kind: ClusterIssuer
         |metadata: { name: ankka-public }
         |spec:
         |  ca: { secretName: ankka-root-ca }
         |""".stripMargin,
      "public-issuer"
    )
    val component = PkiStack.repoRoot.resolve("kustomization/components/broker-external")
    def filled(file: String) =
      Files
        .readString(component.resolve(file))
        // Not the variable that carries it: `ANKKA_BASE_DOMAIN` keeps its name.
        .replaceAll("(?<!ANKKA_)BASE_DOMAIN", BaseDomain)
        .replace("PUBLIC_ISSUER", "ankka-public")
        .replace("\"BROKER_MAX_CONNECTIONS_PER_IP\"", "\"64\"")
        .replace("\"BROKER_EXTERNAL_CONNECTION_RATE\"", "\"20\"")
    apply(filled("certificate.yaml"), "external-certificate")
    // The listener as the component writes it, but for where the keys are read and how soon a token
    // must be renewed: this suite's control plane runs outside the cluster.
    val listener = filled("kafka-listener.yaml")
      .replace(
        "oauth.jwks.endpoint.uri=\"https://ankka-controlplane.ankka-controlplane.svc:7629/.well-known/jwks.json\"",
        s"oauth.jwks.endpoint.uri=\"$JwksUrl\""
      )
      // The suite's key set is plain HTTP, and strimzi-kafka-oauth refuses a truststore for one.
      // Whole lines: a blank line left in the folded JAAS block breaks the JAAS entry.
      .replaceAll("(?m)^[ \\t]*oauth\\.ssl\\.truststore\\.[a-z]+=\"[^\"]*\"\\n", "")
      .replace("connections.max.reauth.ms: 900000", "connections.max.reauth.ms: 60000")
    assert(
      listener.contains(JwksUrl) && listener.contains("reauth.ms: 60000") && !listener.contains(
        "truststore"
      ),
      listener
    )
    patch(
      "kafkanodepool",
      "dual",
      Broker,
      filled("nodepool-options.yaml").replace(
        "https://ankka-controlplane.ankka-controlplane.svc:7629/.well-known/jwks.json",
        JwksUrl
      ),
      "nodepool-options"
    ): Unit
    val patched = patch("kafka", "ankka", Broker, listener, "listener")
    // The listener in the broker's spec, or the suite says what kubectl answered.
    val listeners = jsonPath("kafka", "-n", Broker, "ankka", "{.spec.kafka.listeners[*].name}")
    assert(
      listeners.split(' ').contains("external"),
      s"listeners after the patch: $listeners; kubectl: $patched"
    )
    patch("gateway", "ankka", "ankka-gateway", filled("gateway-listener.yaml"), "gateway-listener")
    patch(
      "envoyproxy",
      "ankka",
      "ankka-gateway",
      """- op: add
        |  path: /spec/provider/kubernetes/envoyService/patch/value/spec/ports/-
        |  value: { name: tls-9094, port: 9094, nodePort: 30094 }
        |""".stripMargin,
      "envoy-port"
    )
    val listening = scala.util.Try(
      waitFor(420.seconds, "the broker listener Programmed and the bootstrap route Accepted") {
        jsonPath(
          "gateway",
          "-n",
          "ankka-gateway",
          "ankka",
          """{.status.listeners[?(@.name=="broker")].conditions[?(@.type=="Programmed")].status}"""
        ) == "True" &&
        node(
          "kubectl",
          "get",
          "tlsroute",
          "-n",
          Broker,
          "-o",
          "jsonpath={.items[*].status.parents[*].conditions[?(@.type==\"Accepted\")].status}"
        )
          .contains("True")
      }
    )
    // What decides it, printed when it did not happen: the broker's conditions, the Gateway's
    // listener, the routes and the cluster operator's own complaints.
    listening.failed.foreach { e =>
      val detail = Vector(
        node(
          "kubectl",
          "get",
          "kafka",
          "ankka",
          "-n",
          Broker,
          "-o",
          "jsonpath={.status.conditions}"
        ),
        node(
          "kubectl",
          "get",
          "kafka",
          "ankka",
          "-n",
          Broker,
          "-o",
          "jsonpath={.status.listeners}"
        ),
        node(
          "kubectl",
          "get",
          "gateway",
          "ankka",
          "-n",
          "ankka-gateway",
          "-o",
          "jsonpath={.status.listeners}"
        ),
        node("kubectl", "get", "tlsroute", "-A", "-o", "wide"),
        node("kubectl", "logs", "deployment/strimzi-cluster-operator", "-n", Broker, "--tail=60")
      ).mkString("\n---\n")
      fail(s"${e.getMessage}\n$detail")
    }
    // The broker rolls onto the new listener after the routes exist: ready again, or why not.
    val rolled = scala.util.Try(waitFor(600.seconds, "the broker ready on its external listener") {
      jsonPath("kafka", "-n", Broker, "ankka", "{.status.listeners[*].name}")
        .split(' ')
        .contains("external") &&
      jsonPath(
        "pod",
        "-n",
        Broker,
        "ankka-dual-0",
        """{.status.conditions[?(@.type=="Ready")].status}"""
      ) == "True"
    })
    rolled.failed.foreach { e =>
      val detail = Vector(
        node("kubectl", "logs", "ankka-dual-0", "-n", Broker, "--previous", "--tail=120"),
        node("kubectl", "logs", "ankka-dual-0", "-n", Broker, "--tail=60"),
        node(
          "kubectl",
          "get",
          "kafka",
          "ankka",
          "-n",
          Broker,
          "-o",
          "jsonpath={.status.conditions}"
        )
      ).mkString("\n---\n")
      fail(s"${e.getMessage}\n$detail")
    }
    System.setProperty(
      "org.apache.kafka.sasl.oauthbearer.allowed.urls",
      s"$controlPlaneUrl/oauth/token"
    ): Unit

  private def apply(manifest: String, name: String): Unit =
    k3s.copyFileToContainer(
      Transferable.of(manifest.getBytes(StandardCharsets.UTF_8)),
      s"/tmp/$name.yaml"
    )
    PkiStack.kubectl(
      k3s,
      "apply",
      "--server-side",
      "--force-conflicts",
      "-f",
      s"/tmp/$name.yaml"
    ): Unit

  /**
   * A JSON patch, refused loudly: kubectl writes a client-side failure as `error:`, not `Error`.
   */
  private def patch(
      kind: String,
      name: String,
      namespace: String,
      json6902: String,
      file: String
  ): String =
    k3s.copyFileToContainer(
      Transferable.of(json6902.getBytes(StandardCharsets.UTF_8)),
      s"/tmp/$file.yaml"
    )
    val r = k3s.execInContainer(
      "kubectl",
      "patch",
      kind,
      name,
      "-n",
      namespace,
      "--type",
      "json",
      "--patch-file",
      s"/tmp/$file.yaml"
    )
    val out = r.getStdout + r.getStderr
    assert(r.getExitCode == 0 && !out.toLowerCase.contains("error"), s"patching $kind $name: $out")
    out

  // ── the machine's client ──────────────────────────────────────────────────

  private lazy val ca: String = Files.readString(GatewayStack.exportCa(k8s))

  private var machines: Map[String, MachineRegistered]         = Map.empty
  private var grants: Vector[(String, String)]                 = Vector.empty
  private var revokedAt: Option[Deadline]                      = None
  private var restartsBefore: Map[String, Int]                 = Map.empty
  private var held: Option[KafkaConsumer[String, Array[Byte]]] = None
  private var strangers: Option[TestIssuer]                    = None
  private var lastRead: Vector[(String, Map[String, String])]  = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    grants = Vector.empty
    revokedAt = None
    restartsBefore = Map.empty
    lastRead = Vector.empty

  override def afterEach(context: AfterEach): Unit =
    held.foreach(c => Try(c.close(Duration.ofSeconds(5))))
    held = None
    strangers.foreach(_.stop())
    strangers = None
    if !munitIgnore then
      for (id, p) <- grants do ankka("projects", "grants", "revoke", id, "-p", p): Unit
    super.afterEach(context)

  private def clientProperties(
      machine: MachineRegistered,
      extra: Map[String, String]
  ): Properties =
    val p = Properties()
    (Map(
      "bootstrap.servers"           -> Bootstrap,
      "security.protocol"           -> "SASL_SSL",
      "ssl.truststore.type"         -> "PEM",
      "ssl.truststore.certificates" -> ca,
      "sasl.mechanism"              -> "OAUTHBEARER",
      "sasl.login.callback.handler.class" ->
        "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginCallbackHandler",
      "sasl.oauthbearer.token.endpoint.url" -> s"$controlPlaneUrl/oauth/token",
      "sasl.jaas.config" ->
        ("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required " +
          s"""clientId="${machine.clientId}" clientSecret="${machine.clientSecret}";"""),
      "request.timeout.ms"     -> "15000",
      "default.api.timeout.ms" -> "30000"
    ) ++ extra).foreach((k, v) => p.put(k, v))
    p

  private def consumer(
      machine: MachineRegistered,
      group: String
  ): KafkaConsumer[String, Array[Byte]] =
    KafkaConsumer(
      clientProperties(
        machine,
        Map(
          ConsumerConfig.GROUP_ID_CONFIG          -> group,
          ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest",
          ConsumerConfig.MAX_POLL_RECORDS_CONFIG  -> "500"
        )
      ),
      StringDeserializer(),
      ByteArrayDeserializer()
    )

  private def producer(machine: MachineRegistered): KafkaProducer[String, Array[Byte]] =
    KafkaProducer(
      clientProperties(machine, Map(ProducerConfig.MAX_BLOCK_MS_CONFIG -> "20000")),
      StringSerializer(),
      ByteArraySerializer()
    )

  private def groupOf(machine: String) = s"ankka.machine.affiliates.$machine.attribution"

  /** Reads `topic` until `count` records are held or `within` passes; a refusal is the result's. */
  private def readAll(
      machine: MachineRegistered,
      topic: String,
      count: Int,
      within: scala.concurrent.duration.FiniteDuration = 60.seconds,
      group: Option[String] = None
  ): (Vector[(String, Map[String, String])], Option[Throwable]) =
    val c = consumer(
      machine,
      group.getOrElse(s"${groupOf(machine.name)}.${java.util.UUID.randomUUID().toString.take(6)}")
    )
    try
      c.subscribe(java.util.List.of(topic))
      val deadline = within.fromNow
      var read     = Vector.empty[(String, Map[String, String])]
      var failure  = Option.empty[Throwable]
      while read.size < count && deadline.hasTimeLeft() && failure.isEmpty do
        try
          c.poll(Duration.ofSeconds(2)).asScala.foreach { r =>
            read :+= (
              String(r.value(), StandardCharsets.UTF_8),
              r.headers().asScala.map(h => h.key -> String(h.value(), StandardCharsets.UTF_8)).toMap
            )
          }
        catch case e: Exception => failure = Some(e)
      (read, failure)
    finally Try(c.close(Duration.ofSeconds(5))): Unit

  private def refusedBy(failure: Option[Throwable]): BrokerProbe.Result =
    val text = failure.fold("read nothing, and nothing refused it")(f =>
      Iterator
        .iterate(f)(_.getCause)
        .takeWhile(_ != null)
        .map(e => s"${e.getClass.getName}: ${e.getMessage}")
        .mkString("\n")
    )
    BrokerProbe.Result(if failure.isDefined then 1 else 0, text)

  private def notice(cart: String) =
    s"""{"cartId":"$cart","at":${System.currentTimeMillis()}}""".getBytes(StandardCharsets.UTF_8)

  private def record(topic: String, cart: String) =
    val r = ProducerRecord[String, Array[Byte]](topic, cart, notice(cart))
    for (k, v) <- Seq(
        "ce-specversion" -> "1.0",
        "ce-id"          -> java.util.UUID.randomUUID().toString,
        "ce-type"        -> "checkout-notice",
        "ce-subject"     -> cart,
        "content-type"   -> "application/json"
      )
    do r.headers().add(k, v.getBytes(StandardCharsets.UTF_8)): Unit
    r

  private def machine(name: String): MachineRegistered =
    machines.getOrElse(name, fail(s"no machine '$name' was registered"))

  private def grantTo(
      machineName: String,
      topic: String,
      right: String,
      project: String
  ): GrantDetail =
    val run = ok(
      ankka(
        "projects",
        "grants",
        "make",
        s"machine:affiliates/$machineName",
        "topic",
        topic,
        right,
        "-p",
        this.project(project),
        "-o",
        "json"
      )
    )
    val grant = readFromString[GrantDetail](run.out)
    grants :+= (grant.id -> this.project(project))
    grant

  private def accept(grant: GrantDetail): Unit =
    // Another organization's grant waits for that organization to accept it, once it is offered
    // there: the organization's record of it is written after the grant is made.
    var last  = ankka("organizations", "grants", "accept", "affiliates", grant.id)
    val until = 60.seconds.fromNow
    while last.code != 0 && last.all.contains("is offered to") && until.hasTimeLeft() do
      Thread.sleep(2000)
      last = ankka("organizations", "grants", "accept", "affiliates", grant.id)
    ok(last): Unit

  private def userGrants(machineName: String, topic: String, operations: Set[String]): Boolean =
    aclsOf(s"machine.affiliates.$machineName").contains(("topic", topic, "literal", operations))

  // ═══ Given ════════════════════════════════════════════════════════════════

  Given("an organization {string} whose owner is {string}") { (organization: String, _: String) =>
    val run = ankka("organizations", "create", organization, "--name", organization)
    assert(run.code == 0 || run.all.contains("already"), run.all)
  }

  Given("{string} has registered {string} as a machine of {string}") {
    (_: String, name: String, organization: String) =>
      if !machines.contains(name) then
        val run = ankka("organizations", "machines", "register", organization, name, "-o", "json")
        if run.code == 0 then machines += name -> readFromString[MachineRegistered](run.out)
        else
          // Deleted by an earlier scenario: registered again, a new machine.
          ok(run): Unit
  }

  Given("the topic {string} is declared on {string}, a project of the organization {string}") {
    (t: String, p: String, _: String) =>
      ok(declare(t, project(p))): Unit
      topicMade(project(p), t)
  }

  Given(
    "the registered machine {string} runs an ordinary broker client outside the installation, holding the hostname of the installation's broker, its client id and client secret, and the token route"
  ) { (name: String) =>
    assert(machines.contains(name))
  }

  Given("the installation exposes its broker to registered machines") { () =>
    assertEquals(machineSettings.brokerBootstrap, Some(Bootstrap))
  }

  Given(
    "an owner of {string} has granted the registered machine {string} of {string} to consume the topic {string} of {string}, and {string} has accepted the grant"
  ) { (_: String, name: String, _: String, topic: String, p: String, _: String) =>
    accept(grantTo(name, topic, "consume", p))
    waitFor(120.seconds, s"$name's user reading $topic") {
      userGrants(name, s"${project(p)}.$topic", Set("Read", "Describe"))
    }
  }

  Given(
    "an owner of {string} has granted the registered machine {string} of {string} to produce to the topic {string} of {string}, and {string} has accepted the grant"
  ) { (_: String, name: String, _: String, topic: String, p: String, _: String) =>
    accept(grantTo(name, topic, "produce", p))
    waitFor(120.seconds, s"$name's user publishing to $topic") {
      userGrants(name, s"${project(p)}.$topic", Set("Write", "Describe"))
    }
  }

  Given(
    "a service of {string} has published {int} messages with distinct subjects to the topic {string}"
  ) { (p: String, n: Int, t: String) =>
    if !appliedAs.contains(("wallet", project(p))) then
      deploy("wallet", project(p), notices = t): Unit
    noticesTo("wallet", project(p), t)
    for _ <- 1 to n do checkout("wallet", project(p)): Unit
  }

  Given(
    "a deployed service {string} in {string} with a view {string} that reads the topic {string}"
  ) { (s: String, p: String, v: String, t: String) =>
    deploy(a(s), project(p), notices = t): Unit
    views += v -> (a(s), project(p))
  }

  Given("the client of {string} has read {string}") { (name: String, topic: String) =>
    val (read, failure) = readAll(machine(name), topic, 1, group = Some(groupOf(name)))
    assert(read.nonEmpty, refusedBy(failure).output)
    restartsBefore = podsInProject("spinvibe")
  }

  Given("the client of {string} is reading {string} with a machine token issued a moment ago") {
    (name: String, topic: String) =>
      val c = consumer(machine(name), groupOf(name))
      c.subscribe(java.util.List.of(topic))
      c.poll(Duration.ofSeconds(5)): Unit
      held = Some(c)
  }

  Given("an issuer {string} that signs tokens for the audience {string}") {
    (name: String, audience: String) =>
      strangers = Some(
        TestIssuer(
          issuer = s"https://$name.example.test/realms/$name",
          name = name,
          defaultAudience = audience
        )
      )
  }

  Given("the byte rate of {string} is the installation's default") { (name: String) =>
    waitFor(120.seconds, s"$name's user throttled to the default") {
      jsonPath(
        "kafkauser",
        "-n",
        Broker,
        s"machine.affiliates.$name",
        "{.spec.quotas.consumerByteRate}"
      ) ==
        ConsumeRate.toString
    }
  }

  // ═══ When ═════════════════════════════════════════════════════════════════

  When("the client of {string} reads {string} from the earliest message under the group {string}") {
    (name: String, topic: String, group: String) =>
      val (read, failure) = readAll(machine(name), topic, 100, group = Some(group))
      lastRead = read
      lastProbe = Some(refusedBy(failure))
  }

  When("the client of {string} reads {string}") { (name: String, topic: String) =>
    val (_, failure) = readAll(machine(name), topic, 1, within = 30.seconds)
    lastProbe = Some(refusedBy(failure))
  }

  When("the client of {string} publishes to {string}") { (name: String, topic: String) =>
    val p = producer(machine(name))
    try
      val failure = Try(
        p.send(record(topic, "refused")).get(30, java.util.concurrent.TimeUnit.SECONDS)
      ).failed.toOption
      lastProbe = Some(refusedBy(failure))
    finally p.close(Duration.ofSeconds(5))
  }

  When("the client of {string} publishes a message to {string}") { (name: String, topic: String) =>
    val p    = producer(machine(name))
    val cart = s"from-$name-${java.util.UUID.randomUUID().toString.take(8)}"
    try p.send(record(topic, cart)).get(30, java.util.concurrent.TimeUnit.SECONDS): Unit
    finally p.close(Duration.ofSeconds(5))
    published += ("machine" -> cart)
  }

  When(
    "what the installation's broker allows the credential of the registered machine {string} is listed"
  ) { (name: String) =>
    listedMachine = Some(name)
  }

  When("the owner revokes the grant") { () =>
    val (id, p) = grants.lastOption.getOrElse(fail("no grant was made"))
    ok(ankka("projects", "grants", "revoke", id, "-p", p)): Unit
    grants = grants.dropRight(1)
    revokedAt = Some(Deadline.now)
  }

  When("{string} deletes the registered machine {string}") { (_: String, name: String) =>
    ok(ankka("organizations", "machines", "delete", "affiliates", name)): Unit
    revokedAt = Some(Deadline.now)
    // Registered again by the next scenario that names it, whatever this one goes on to assert.
    machines -= name
  }

  When(
    "a client outside the installation connects to the installation's broker at its hostname with no machine token"
  ) { () =>
    lastProbe = Some(
      connectWith(
        Map(
          "sasl.mechanism" -> "PLAIN",
          "sasl.jaas.config" ->
            "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"nobody\" password=\"none\";"
        )
      )
    )
  }

  When(
    "a client outside the installation connects to the installation's broker at its hostname with a token from {string}"
  ) { (_: String) =>
    // A token naming the other issuer, as its holder would present it; it is not the
    // installation's, whatever signed it.
    val issuer = strangers.getOrElse(fail("no issuer")).issuer
    lastProbe = Some(
      connectWith(
        Map(
          "sasl.jaas.config" ->
            ("org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required " +
              s"""unsecuredLoginStringClaim_sub="eve" unsecuredLoginStringClaim_iss="$issuer";"""),
          "sasl.login.callback.handler.class" ->
            "org.apache.kafka.common.security.oauthbearer.internals.unsecured.OAuthBearerUnsecuredLoginCallbackHandler"
        )
      )
    )
  }

  When("the client of {string} reads {string} as fast as it can") { (name: String, topic: String) =>
    // A topic holding more than the window can carry at the rate: produced by a machine of its own,
    // granted to produce, so the reader's quota is the only limit on what it reads.
    val feeder = machines.getOrElse(
      "feeder", {
        val run =
          ok(ankka("organizations", "machines", "register", "affiliates", "feeder", "-o", "json"))
        val m = readFromString[MachineRegistered](run.out)
        machines += "feeder" -> m
        m
      }
    )
    accept(grantTo("feeder", topic.split('.').drop(1).mkString("."), "produce", "spinvibe"))
    waitFor(120.seconds, "the feeder publishing")(
      userGrants("feeder", topic, Set("Write", "Describe"))
    )
    val p = producer(feeder)
    try
      for i <- 1 to 400 do
        val r = ProducerRecord[String, Array[Byte]](
          topic,
          s"bulk-$i",
          Array.fill[Byte](10240)('x'.toByte)
        )
        p.send(r): Unit
      p.flush()
    finally p.close(Duration.ofSeconds(10))
    val c = consumer(machine(name), s"${groupOf(name)}.bulk")
    try
      c.subscribe(java.util.List.of(topic))
      c.poll(Duration.ofSeconds(5)): Unit
      val started = System.nanoTime()
      var bytes   = 0L
      while System.nanoTime() - started < 30_000_000_000L do
        c.poll(Duration.ofMillis(500))
          .asScala
          .foreach(r => bytes += Option(r.value()).fold(0)(_.length))
      fetchedInWindow = Some(bytes)
    finally c.close(Duration.ofSeconds(5))
  }

  private var listedMachine: Option[String] = None
  private var fetchedInWindow: Option[Long] = None

  private def connectWith(sasl: Map[String, String]): BrokerProbe.Result =
    val base = Map(
      "bootstrap.servers"           -> Bootstrap,
      "security.protocol"           -> "SASL_SSL",
      "ssl.truststore.type"         -> "PEM",
      "ssl.truststore.certificates" -> ca,
      "sasl.mechanism"              -> "OAUTHBEARER",
      "default.api.timeout.ms"      -> "20000",
      "request.timeout.ms"          -> "15000"
    )
    val p = Properties()
    (base ++ sasl).foreach((k, v) => p.put(k, v))
    val admin = org.apache.kafka.clients.admin.AdminClient.create(p)
    try
      refusedBy(
        Try(
          admin.listTopics().names().get(30, java.util.concurrent.TimeUnit.SECONDS)
        ).failed.toOption
      )
    finally admin.close(Duration.ofSeconds(5))

  private def podsInProject(p: String): Map[String, Int] =
    k8s
      .pods()
      .inNamespace(ns(project(p)))
      .list()
      .getItems
      .asScala
      .map(pod =>
        pod.getMetadata.getUid -> Option(pod.getStatus.getContainerStatuses)
          .map(_.asScala.map(_.getRestartCount.intValue).sum)
          .getOrElse(0)
      )
      .toMap

  // ═══ Then ═════════════════════════════════════════════════════════════════

  Then("it reads every one of the {int} messages") { (n: Int) =>
    assertEquals(
      lastRead.map(_._2.getOrElse("ce-subject", "")).distinct.size,
      n,
      lastProbe.map(_.output).getOrElse("")
    )
  }

  Then("each message carries its attributes with it") { () =>
    assert(
      lastRead.forall(r =>
        r._2.contains("ce-subject") && r._2.contains("ce-type") && r._2.contains("ce-id")
      ),
      lastRead.take(3).toString
    )
  }

  Then("the view {string} shows what was published") { (v: String) =>
    val (reader, p) = view(v)
    val cart        = published.getOrElse("machine", fail("the machine published nothing"))
    waitFor(120.seconds, s"$reader's view showing $cart")(seen(reader, p, cart)._1 == 200)
  }

  Then("the credential may read {string}") { (topic: String) =>
    val name = listedMachine.getOrElse(fail("nothing was listed"))
    assert(
      userGrants(name, topic, Set("Read", "Describe")),
      aclsOf(s"machine.affiliates.$name").toString
    )
  }

  Then("the credential may read any group named under {string} and no other") { (prefix: String) =>
    val name   = listedMachine.getOrElse(fail("nothing was listed"))
    val groups = aclsOf(s"machine.affiliates.$name").filter(_._1 == "group")
    assertEquals(groups, Vector(("group", prefix, "prefix", Set("Read"))))
  }

  override protected def mayDoNothingElse(): Unit =
    val name = listedMachine.getOrElse(fail("nothing was listed"))
    val acls = aclsOf(s"machine.affiliates.$name")
    assert(
      acls.forall(a => (a._1 == "topic" && a._3 == "literal") || a._1 == "group"),
      acls.toString
    )
    assert(!acls.exists(_._4.exists(Set("Create", "Delete", "Alter", "All"))), acls.toString)

  Then("within {string} seconds the broker refuses the next read by the client of {string}") {
    (seconds: String, name: String) =>
      val topic    = s"${project("spinvibe")}.affiliates.attribution"
      val deadline = revokedAt.getOrElse(fail("nothing was revoked")) + seconds.toInt.seconds
      var last     = BrokerProbe.Result(0, "")
      while !last.refused && deadline.hasTimeLeft() do
        last = refusedBy(readAll(machine(name), topic, 1, within = 10.seconds)._2)
        if !last.refused then Thread.sleep(2000)
      assert(last.refused, s"still read $seconds seconds after the revocation: ${last.output}")
  }

  Then("no service of {string} is restarted") { (p: String) =>
    assertEquals(podsInProject(p), restartsBefore)
  }

  Then("the broker makes the client prove itself again before {string} minutes have passed") {
    (minutes: String) =>
      // The suite's listener asks for a new token every minute; the contract's fifteen bound it.
      val reauth = jsonPath(
        "kafka",
        "-n",
        Broker,
        "ankka",
        """{.spec.kafka.listeners[?(@.name=="external")].authentication.listenerConfig.connections\.max\.reauth\.ms}"""
      )
      assert(reauth.toLongOption.exists(_ <= minutes.toLong * 60 * 1000), reauth)
  }

  Then(
    "the client, given no new machine token by the token route, has its connection ended by the broker"
  ) { () =>
    val c       = held.getOrElse(fail("no client is reading"))
    val ended   = 150.seconds.fromNow
    var failure = Option.empty[Throwable]
    while failure.isEmpty && ended.hasTimeLeft() do
      try c.poll(Duration.ofSeconds(2)): Unit
      catch case e: Exception => failure = Some(e)
    assert(failure.isDefined, "the deleted machine's client was still connected")
    val text = refusedBy(failure).output
    assert(
      text.contains("Authentication") || text.contains("SaslAuthentication") || text.contains(
        "Authorization"
      ),
      text
    )
  }

  Then("the credential of {string} on the broker is kept, with no topic") { (name: String) =>
    waitFor(120.seconds, s"$name's user holding no topic") {
      val acls = aclsOf(s"machine.affiliates.$name")
      acls.nonEmpty && !acls.exists(_._1 == "topic")
    }
    machines -= name
  }

  Then("the broker refuses the connection before anything is exchanged") { () =>
    val result = lastProbe.getOrElse(fail("nothing connected"))
    assertEquals(result.code, 1, result.output)
    assert(
      result.output.contains("Authentication") || result.output.contains("SaslAuthentication") ||
        result.output.contains("UnsupportedSaslMechanism"),
      result.output
    )
  }

  Then("the registered machine {string} is throttled to its byte rate") { (_: String) =>
    val bytes = fetchedInWindow.getOrElse(fail("nothing was read"))
    assert(
      bytes <= (ConsumeRate * 30 * 1.25).toLong,
      s"read $bytes bytes in 30 seconds at a rate of $ConsumeRate"
    )
    assert(bytes > 0, "read nothing at all")
  }

  Then("the view {string} reads the topic as fast as it did before") { (v: String) =>
    // The service's own credential has no quota: what it was published is read at once.
    val (reader, p) = view(v)
    val spinvibe    = project("spinvibe")
    if !appliedAs.contains(("wallet", spinvibe)) then
      deploy("wallet", spinvibe, notices = "affiliates.attribution"): Unit
    else noticesTo("wallet", spinvibe, "affiliates.attribution")
    val cart = checkout("wallet", spinvibe)
    waitFor(60.seconds, s"$reader's view reading $cart")(seen(reader, p, cart)._1 == 200)
  }
