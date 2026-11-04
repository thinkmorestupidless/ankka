package com.thinkmorestupidless.ankka.controlplane

import com.github.dockerjava.api.model.ExposedPort
import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.{GrantDetail, MachineTokenEndpoint}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.auth.{MachineKeys, MachineSettings}
import com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig
import com.thinkmorestupidless.ankka.controlplane.tenancy.ErasureSettings
import com.thinkmorestupidless.ankka.core.keyring.{DecryptReply, DecryptRequest, KeyReply, RawJson}
import com.thinkmorestupidless.ankka.core.keyring.KeyringWire.given
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.operator.{BrokerStack, GatewayStack}
import com.thinkmorestupidless.ankka.runtime.RuntimeExtension
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.{Duration, Instant}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * `features/erasure/other-projects.feature`'s machine outside the installation on k3s (feature
 * 042), and the installation-wide half of erasure the in-process suites cannot show.
 *
 * The installation of `ErasureClusterSteps` with the installation's broker and its Gateway at the
 * base domain `127.0.0.1.sslip.io`, the node's HTTPS NodePort published on a port of this host, and
 * the keyring's route for machines applied from its component. The machine is this JVM: its token
 * is the control plane's, signed with the keys the control plane serves (a key set inside the
 * cluster, which the keyring is pointed at, since the control plane runs outside it), and it calls
 * `https://keyring.127.0.0.1.sslip.io/v1/decrypt` through the Gateway with `curl`, verifying the
 * installation's root. What it decrypts is the message `players` published: the bytes read from the
 * topic `brand.players` by a probe holding `players`' own certificate, since the machine's own
 * Kafka client is the cross-project machine suite's subject, not this one's.
 *
 * Beside the scenarios: the keyring runs two instances; an erasure reaches both services of the
 * project and a consumer in another project holding a decrypt grant within 60 seconds (SC-004); and
 * a control plane configured as the local overlay configures it refuses a descriptor that declares
 * personal fields.
 *
 * Off with `-Dankka.cluster.tests=off`.
 */
class ErasureClusterFeatures
    extends ErasureClusterSteps("../features/erasure/other-projects.feature"):

  override protected def ranElsewhere: Map[String, String] = Map(
    "a consumer in another project with a grant that allows decryption reads the value" ->
      "ErasureProjectsFeatures, two projects in one JVM",
    "a consumer in another project with a grant that does not allow decryption reads the field as erased" ->
      "ErasureProjectsFeatures, two projects in one JVM",
    "an erasure in the producing project reaches what another project kept of the data subject" ->
      "ErasureProjectsFeatures, two projects in one JVM",
    "a revoked grant ends another project's reads of the producing project's data subjects" ->
      "ErasureProjectsFeatures, two projects in one JVM"
  )

  private val BaseDomain = "127.0.0.1.sslip.io"
  private val Issuer     = s"https://api.$BaseDomain"
  private val Edge       = "ankka-edge"
  private val JwksUrl    = s"http://jwks.$Edge.svc.cluster.local/jwks.json"
  private val Subject    = "player/8c1f"
  private val Id         = "8c1f"
  private val Machine    = "machine:acme/warehouse"

  // One installation and one pair of projects for every scenario: the feature's names, as written.
  override protected def projectOf(logical: String): String = logical
  override protected def serviceOf(logical: String): String = logical

  private lazy val keys: MachineKeys =
    val k = MachineKeys.inMemory()
    k.rotate(): Unit
    k

  override protected def machineSettings: MachineSettings = MachineSettings(Issuer)
  override protected def machineKeys: Option[MachineKeys] = Some(keys)

  override protected def keyringEnvironment: Vector[(String, String)] =
    Vector("ANKKA_MACHINE_ISSUER" -> Issuer, "ANKKA_MACHINE_JWKS_URL" -> JwksUrl)

  override protected def keyringRoute: Boolean = true
  override protected def routeDomain: String   = BaseDomain

  override protected def configure(container: K3sContainer): K3sContainer =
    container
      .withCreateContainerCmdModifier(cmd =>
        cmd.withExposedPorts(
          (Option(cmd.getExposedPorts).toVector.flatten :+
            ExposedPort.tcp(GatewayStack.HttpsNodePort)).asJava
        ): Unit
      )
      .asInstanceOf[K3sContainer]

  private var httpsPort: Int = 0

  override protected def installErasureMore(): Unit =
    broker = Some(BrokerStack.install(k3s))
    GatewayStack.install(k3s, k8s, repoRoot, BaseDomain)
    httpsPort = k3s.getMappedPort(GatewayStack.HttpsNodePort)
    val jwks = writeToString(keys.jwks)
    applyYaml(
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
      "jwks"
    )

  // ── a control plane configured as the local overlay configures it ───────────

  /** What the local overlay tells its control plane about erasure, read from its render. */
  private lazy val overlayErasure: (String, ErasureSettings) =
    val rendered = Process(
      "kubectl",
      "kustomize",
      repoRoot.resolve("kustomization/overlays/local").toString
    )
    val deployment = rendered
      .split("\n---\n")
      .find(d =>
        d.linesIterator.contains("kind: Deployment") && d.contains("name: ankka-controlplane\n")
      )
      .getOrElse(fail("the local overlay renders no control plane"))
    val value = "(?s)name: ANKKA_ERASURE\\s*\\n\\s*value: \"?([a-z]+)\"?".r
      .findFirstMatchIn(deployment)
      .map(_.group(1))
      .getOrElse(fail("the local overlay's control plane is told nothing of erasure"))
    value -> ErasureSettings.from(
      com.typesafe.config.ConfigFactory.parseString(
        s"""${ErasureSettings.EnabledKey} = "$value""""
      )
    )

  private lazy val overlayServer: HttpServer =
    HttpServer.at("127.0.0.1", 0)(
      ControlPlane.endpoints(
        identity.acl(),
        DeployConfig.default.copy(namespacePrefix = Prefix),
        auth = Some(identity.config()),
        erasure = overlayErasure._2
      )*
    )

  override protected def moreExtensions: Seq[RuntimeExtension] =
    super.moreExtensions :+ overlayServer

  // ── the gateway, as a machine on the internet reaches it ─────────────────────

  private lazy val roots = GatewayStack.exportCa(k8s)

  private def token: String =
    keys.sign(
      MachineTokenEndpoint.claims(
        MachineSettings(Issuer),
        "acme",
        "warehouse",
        java.time.Clock.systemUTC()
      )
    )

  /** `curl` on this host to the keyring's address, through the Gateway; (status, body). */
  private def internet(path: String, body: String): (Int, String) =
    val host = s"keyring.$BaseDomain"
    val file = Files.createTempFile("ankka-machine", ".json")
    try
      Files.writeString(file, body): Unit
      val args = Vector(
        "curl",
        "-sS",
        "--cacert",
        roots.toString,
        "--resolve",
        s"$host:$httpsPort:127.0.0.1",
        "-m",
        "30",
        "-X",
        "POST",
        "-H",
        "Content-Type: application/json",
        "-H",
        s"Authorization: Bearer $token",
        "--data-binary",
        s"@$file",
        "-w",
        "\n%{http_code}",
        s"https://$host:$httpsPort$path"
      )
      val process = new ProcessBuilder(args*).redirectErrorStream(true).start()
      val out     = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      process.waitFor(): Unit
      val (answer, code) = out.splitAt(out.lastIndexOf('\n') + 1)
      (code.trim.toIntOption.getOrElse(0), answer.stripSuffix("\n"))
    finally Files.deleteIfExists(file): Unit

  // ── grants, as the control plane makes them and the keyring reads them ──────

  private var grantId: Option[String]       = None
  private var paymentsGrant: Option[String] = None

  private def makeGrant(grantee: String, decrypt: Boolean): String =
    val run = ok(
      ankka(
        (Vector("projects", "grants", "make", grantee, "topic", "players", "consume") ++
          Option.when(decrypt)("--decrypt") ++ Vector("-p", "brand", "-o", "json"))*
      )
    )
    val grant = readFromString[GrantDetail](run.out)
    awaitGrantRendered(grant.id, present = true)
    grant.id

  /** Until `brand`'s project ConfigMap, which the keyring re-reads, holds the grant or does not. */
  private def awaitGrantRendered(id: String, present: Boolean): Unit =
    waitFor(3.minutes, s"grant $id ${if present then "in" else "gone from"} brand's ConfigMap") {
      val grants = Option(
        k8s.configMaps().inNamespace(namespace("brand")).withName("ankka-project").get()
      ).flatMap(c => Option(c.getData)).flatMap(d => Option(d.get("grants.json"))).getOrElse("")
      grants.contains(id) == present
    }

  /** Past the keyring's re-read of the project ConfigMaps, which is every 30 seconds. */
  private def pastKeyringRefresh(): Unit = Thread.sleep(40.seconds.toMillis)

  // ── what the machine read ────────────────────────────────────────────────────

  private val EnvelopeOf =
    """"name":(\{"\$personal":1,"project":"([^"]*)","subject":"([^"]*)"(?:,"c":"[^"]*")?\})""".r

  private var registered = false

  /** The registration of 8c1f as published to `brand.players`: what a consumer of it reads. */
  private def published: String =
    if !registered then
      register("brand", "players", Id, "Ada Byron")
      registered = true
    var text = ""
    waitFor(3.minutes, "the registration of 8c1f on brand.players") {
      text = BrokerProbe
        .holding(k3s, namespace("brand"), "players")
        .read(
          "brand.players",
          s"ankka.brand.players.probe-${java.util.UUID.randomUUID().toString.take(8)}",
          waitMs = 15000
        )
        .messages
        .filter(_.contains(Subject))
        .mkString("\n")
      text.nonEmpty
    }
    text

  private def envelope: String =
    EnvelopeOf
      .findAllMatchIn(published.replace(" ", ""))
      .find(_.group(3) == Subject)
      .map(_.group(1))
      .getOrElse(fail(s"no envelope of 'name' of $Subject in $published"))

  private def decryptName(): (Int, String) =
    internet(
      "/v1/decrypt",
      writeToString(
        DecryptRequest(
          grantId.getOrElse(fail("the machine holds no grant")),
          "brand",
          "player-event",
          Vector(RawJson(envelope.getBytes(StandardCharsets.UTF_8)))
        )
      )
    )

  private def keyAsked: (Int, String) =
    internet("/v1/keys", s"""{"project":"brand","subject":"$Subject","create":false}""")

  private def count(table: String, where: String): Int =
    keyringSql(s"select count(*) from keyring.$table where $where").toInt

  private var asked: Option[(Int, String)] = None
  private var machineRegistered            = false
  private var decryptionsBefore            = 0
  private var refusalsBefore               = 0

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    asked = None

  // ── Background ────────────────────────────────────────────────────────────────

  Given(
    "a project {string} with the service {string}, which publishes {string} to the topic {string}"
  ) { (project: String, service: String, event: String, topic: String) =>
    assertEquals(
      (project, service, event, topic),
      ("brand", "players", "PlayerRegistered", "players")
    )
    if !isReady("brand", "players") then
      ensureProject("brand")
      ok(ankka("projects", "topics", "set", "players", "--partitions", "1", "-p", "brand")): Unit
      deploy("brand", "players", playersDescriptor("players", "players"))
  }

  Given(
    "the event {string} has the field {string} marked as a personal field of the data subject {string}"
  ) { (event: String, field: String, subject: String) =>
    // The players image's PlayerRegistered seals its name under player/<id>.
    assertEquals((event, field, subject), ("PlayerRegistered", "name", Subject))
  }

  Given("a project {string} with a consumer {string} that reads the topic {string} of {string}") {
    (project: String, consumer: String, topic: String, of: String) =>
      assertEquals((project, consumer, topic, of), ("payments", "cardholders", "players", "brand"))
      if !isReady("payments", "cardholders") then
        ensureProject("payments")
        // Its read of brand's topic is a grant's: made before it starts, so it starts reading.
        if paymentsGrant.isEmpty then
          paymentsGrant = Some(makeGrant("service:payments/cardholders", decrypt = true))
        deploy(
          "payments",
          "cardholders",
          playersDescriptor("cardholders", "cardholders", from = Some("brand"))
        )
  }

  // ── a machine outside the installation ────────────────────────────────────────

  private def machineGrant(decrypt: Boolean): Unit =
    if !machineRegistered then
      ok(ankka("organizations", "machines", "register", "acme", "warehouse", "-o", "json")): Unit
      machineRegistered = true
    // One grant at a time: an earlier scenario's would admit what this one's refuses.
    grantId.foreach { id =>
      ankka("projects", "grants", "revoke", id, "-p", "brand"): Unit
      awaitGrantRendered(id, present = false)
    }
    grantId = Some(makeGrant(Machine, decrypt))
    pastKeyringRefresh()
    if decrypt then
      // Admitted, as the keyring reads the grant now: the refusals that follow are its own.
      waitFor(2.minutes, "the keyring decrypting for the machine")(decryptName()._1 == 200)

  Given(
    "a machine outside the installation with a grant on the topic {string} of {string} that does not allow decryption"
  ) { (topic: String, of: String) =>
    assertEquals((topic, of), ("players", "brand"))
    machineGrant(decrypt = false)
  }

  Given(
    "a machine outside the installation with a grant on the topic {string} of {string} that allows decryption"
  ) { (topic: String, of: String) =>
    assertEquals((topic, of), ("players", "brand"))
    machineGrant(decrypt = true)
  }

  private def ask(): Unit =
    decryptionsBefore = count("decryption", s"machine = '$Machine'")
    refusalsBefore = count("fetch_refusal", s"caller = '$Machine'")
    asked = Some(decryptName())

  When("the machine reads a {string} of {string}") { (event: String, subject: String) =>
    assertEquals((event, subject), ("PlayerRegistered", Subject))
    ask()
  }

  Then("the machine reads the field {string} as erased") { (field: String) =>
    assertEquals(field, "name")
    // What it read holds no value in the clear, and the keyring opens the field for no grant of it.
    assert(!published.contains("Ada Byron"), published)
    val (status, body) = asked.getOrElse(fail("the machine asked nothing"))
    assertEquals(status, 403, body)
    assert(readFromString[KeyReply](body).refused.exists(_.contains("holds no grant")), body)
    assertEquals(count("decryption", s"machine = '$Machine'"), decryptionsBefore)
  }

  When(
    "the machine reads a {string} of {string} and asks the keyring to decrypt the field {string}"
  ) { (event: String, subject: String, field: String) =>
    assertEquals((event, subject, field), ("PlayerRegistered", Subject, "name"))
    ask()
  }

  Then("the machine is told the name {string}") { (name: String) =>
    val (status, body) = asked.getOrElse(fail("the machine asked nothing"))
    assertEquals(status, 200, body)
    assertEquals(
      readFromString[DecryptReply](body).values.map(_.map(_.text)),
      Vector(Some(s"\"$name\""))
    )
  }

  Then("no subject key leaves the installation") { () =>
    val (_, body) = asked.getOrElse(fail("the machine asked nothing"))
    assert(!body.contains("\"key\""), body)
    // The keyring's key fetch has no route through the Gateway: only the decryption does.
    val (status, refused) = keyAsked
    assertEquals(status, 404, refused)
    assert(!refused.contains("\"key\""), refused)
  }

  Then("the keyring records the decryption against the machine and the grant") { () =>
    val id = grantId.getOrElse(fail("the machine holds no grant"))
    assertEquals(
      keyringSql(
        "select machine, grant_id, project, field_count from keyring.decryption " +
          s"where grant_id = '$id' and subject = '$Subject' order by at desc limit 1"
      ),
      s"$Machine|$id|brand|1"
    )
    assertEquals(count("decryption", s"machine = '$Machine'"), decryptionsBefore + 1)
  }

  Given("{string} has since revoked the grant") { (project: String) =>
    assertEquals(project, "brand")
    val id = grantId.getOrElse(fail("the machine holds no grant"))
    ok(ankka("projects", "grants", "revoke", id, "-p", "brand")): Unit
    awaitGrantRendered(id, present = false)
    pastKeyringRefresh()
  }

  Given("{string} has since been erased in {string}") { (subject: String, project: String) =>
    assertEquals((subject, project), (Subject, "brand"))
    // The machine has read the registration first; then the subject is erased.
    envelope: Unit
    erase("brand", subject): Unit
  }

  When(
    "the machine asks the keyring to decrypt the field {string} of a {string} of {string}"
  ) { (field: String, event: String, subject: String) =>
    assertEquals((field, event, subject), ("name", "PlayerRegistered", Subject))
    ask()
  }

  Then("the machine is refused") { () =>
    val (status, body) = asked.getOrElse(fail("the machine asked nothing"))
    assertEquals(status, 403, body)
    assert(readFromString[KeyReply](body).refused.nonEmpty, body)
    assert(!body.contains("Ada Byron"), body)
    assertEquals(
      count("decryption", s"machine = '$Machine'"),
      decryptionsBefore,
      "a refused decryption was recorded as one"
    )
    assert(
      count("fetch_refusal", s"caller = '$Machine'") > refusalsBefore,
      "the refusal was not recorded"
    )
  }

  // ── the installation as a whole ───────────────────────────────────────────────

  test("the keyring runs two instances, each ready only with the erasure log applied") {
    val ready = readyKeyringPods()
    assertEquals(ready.size, 2, keyringPods().map(_.getMetadata.getName).toString)
    for pod <- ready do
      val status = keyringStatus(pod)
      assert(status.contains("\"finished\":true"), s"$pod: $status")
      assert(!status.contains("\"unread\":[{"), s"$pod: $status")
  }

  test(
    "an erasure reaches both services of the project and a consumer in another project holding a decrypt grant within 60 seconds"
  ) {
    assert(isReady("brand", "players") && isReady("payments", "cardholders"), "the Background ran")
    if !isReady("brand", "engagement") then
      deploy("brand", "engagement", playersDescriptor("engagement", "engagement"))
    val id = "4b7e"
    register("brand", "players", id, "Grace Hopper")
    def reads: Vector[(String, Option[String])] = Vector(
      "players' entity"   -> players("brand", "players", s"/players/$id"),
      "players' view"     -> players("brand", "players", s"/players/$id/profile"),
      "engagement's view" -> players("brand", "engagement", s"/players/$id"),
      "cardholders' view" -> players("payments", "cardholders", s"/players/$id")
    ).map((what, answer) =>
      what -> Option.when(answer._1 == 200)(answer._2).flatMap(field(_, "name"))
    )
    waitFor(4.minutes, "every service reading Grace Hopper's name") {
      reads.forall(_._2.contains("Grace Hopper"))
    }
    val asked = readFromString(
      ok(ankka("projects", "erasures", "file", s"player/$id", "-p", "brand", "-o", "json")).out
    )(using api.ErasureWire.erasureRequestViewCodec)
    var allErased: Option[Instant]             = None
    var last: Vector[(String, Option[String])] = Vector.empty
    var destroyed: Option[Instant]             = None
    waitFor(5.minutes, "every service reading the name as erased, and the key's destruction") {
      if allErased.isEmpty then
        last = reads
        if last.forall(_._2.contains("erased")) then allErased = Some(Instant.now())
      destroyed = requestOf("brand", asked.id).destroyedAt
      allErased.isDefined && destroyed.isDefined
    }
    val within = Duration.between(destroyed.get, allErased.get)
    assert(
      within.compareTo(Duration.ofSeconds(60)) <= 0,
      s"every service read erased $within after the key's destruction: $last"
    )
    // What is not personal is as it was.
    assertEquals(field(players("brand", "players", s"/players/$id")._2, "currency"), Some("EUR"))
  }

  test(
    "a control plane configured as the local overlay refuses a service that declares personal fields"
  ) {
    val (said, settings) = overlayErasure
    assertEquals(said, "off")
    assert(!settings.enabled)
    val port = overlayServer.boundPort.getOrElse(fail("the overlay's control plane did not bind"))
    val file = Files.createTempFile("ankka-personal", ".json")
    try
      Files.writeString(file, playersDescriptor("ledger", "players")): Unit
      val out  = java.io.ByteArrayOutputStream()
      val err  = java.io.ByteArrayOutputStream()
      val code = com.thinkmorestupidless.ankka.cli.Main.run(
        Seq(
          "services",
          "apply",
          "-f",
          file.toString,
          "-p",
          "brand",
          "--url",
          s"http://127.0.0.1:$port",
          "--token",
          Token
        ),
        java.io.PrintStream(out, true, StandardCharsets.UTF_8),
        java.io.PrintStream(err, true, StandardCharsets.UTF_8)
      )
      val said = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8)
      assertNotEquals(code, 0, said)
      assert(said.contains("erasure is off"), said)
      // The installation's own control plane, erasure on, took the same descriptor.
      assert(statusOf("ledger", "brand").isEmpty, "the refused service was recorded")
      assert(
        isReady("brand", "players"),
        "brand's players, which declares the same, is not running"
      )
    finally Files.deleteIfExists(file): Unit
  }
