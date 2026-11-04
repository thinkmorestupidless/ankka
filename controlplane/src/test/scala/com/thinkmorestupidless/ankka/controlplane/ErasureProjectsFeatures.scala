package com.thinkmorestupidless.ankka.controlplane

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.MachineTokenEndpoint
import com.thinkmorestupidless.ankka.controlplane.auth.{MachineKeys, MachineSettings}
import com.thinkmorestupidless.ankka.controlplane.erasure.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.keyring.{
  DecryptReply,
  DecryptRequest,
  ErasureOrder,
  KeyReply,
  RawJson
}
import com.thinkmorestupidless.ankka.core.keyring.KeyringWire.given
import com.thinkmorestupidless.ankka.crd.{AnkkaProjectSpec, ProjectGrantEntry}
import com.thinkmorestupidless.ankka.http.MachineTokens
import com.thinkmorestupidless.ankka.keyring.{
  Admission,
  Keyring as KeyringServer,
  LogCopy,
  LogEntry,
  Main,
  ProjectGrants,
  RootKey
}
import com.thinkmorestupidless.ankka.operator.ProjectConfig
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  IncomingMessage,
  InMemoryBroker,
  MessageSubscriber,
  ProjectionRuntime,
  RotatingTls,
  ServiceIdentity,
  SqlFragment,
  Subscribed,
  TopicSubscription
}
import com.thinkmorestupidless.ankka.runtime.keyring.RemoteKeyStoreClient
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}
import com.typesafe.config.ConfigFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate}
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/** A cardholder as `payments` keeps one: the player's name, read from `brand`'s topic. */
final case class CardholderRow(playerId: String, name: Personal[String])

/** `payments`' view of the players `brand` publishes, keeping each one's name. */
final class CardholdingsView extends View[PlayerEvent, CardholderRow]:
  def onChange(event: PlayerEvent): Effect = event match
    case r: PlayerRegistered => effects.updateRow(CardholderRow(r.playerId, r.name))
    case _: EmailChanged     => effects.ignore()

object Cardholdings
    extends View.Companion[CardholdingsView, PlayerEvent, CardholderRow](
      componentId = ComponentId("cardholdings"),
      source = ChangeSource.fromTopic(
        "brand",
        "players",
        Codecs.serializer[PlayerEvent]("player-event"),
        StartFrom.Earliest
      ),
      rowSerializer = Codecs.serializer[CardholderRow]("cardholder-row")
    ):
  def create(ctx: ViewComponentContext) = new CardholdingsView

  val byPlayer = query("by-player")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player_id"
  )

/**
 * `cardholders`: reads `brand`'s topic `players` and keeps the name each registration was handed.
 */
final class Cardholders extends Consumer[PlayerEvent, Nothing]:
  def onMessage(event: PlayerEvent): Effect =
    event match
      case r: PlayerRegistered => Cardholders.handed.add(r.playerId -> r.name): Unit
      case _                   => ()
    effects.done()

object Cardholders
    extends Consumer.Companion[Cardholders, PlayerEvent, Nothing](
      componentId = ComponentId("cardholders"),
      source = ChangeSource.fromTopic(
        "brand",
        "players",
        Codecs.serializer[PlayerEvent]("player-event"),
        StartFrom.Earliest
      )
    ):
  val handed: ConcurrentLinkedQueue[(String, Personal[String])] = ConcurrentLinkedQueue()
  def create(ctx: ConsumerContext)                              = new Cardholders

/**
 * `features/erasure/other-projects.feature`, in process (feature 042, user story 5): two projects
 * in one JVM, `brand`'s `players` publishing to its topic and `payments`' `cardholders` reading it,
 * both reaching the keyring's own server over loopback mutual TLS, whose admission reads the grants
 * as the operator renders them. A machine outside the installation reaches the same server as the
 * gateway relays it: the gateway's certificate and the machine's token, signed by the control
 * plane's own issuer. What only the installation's gateway can show, the route to the keyring, is
 * `ErasureClusterFeatures`' on k3s.
 */
class ErasureProjectsFeatures
    extends GherkinSuite("../features/erasure/other-projects.feature")
    with LogCapturing:

  override val munitTimeout = 4.minutes

  /** How long `payments` holds a key of `brand`'s: 30 seconds on the platform, short here. */
  private val foreignTtl = 2.seconds

  // ── the keyring, over loopback mutual TLS ───────────────────────────────────

  private val pki     = com.thinkmorestupidless.ankka.testpki.TestPki.root("erasure-projects")
  private val pkiRoot = Files.createTempDirectory("erasure-projects")
  private val grantsRoot: Path = Files.createTempDirectory("erasure-projects-grants")

  private def certificate(uri: String, dns: Seq[String] = Nil): Path =
    val dir = pkiRoot.resolve(uri.replaceAll("[^a-z0-9]+", "-"))
    if Files.isRegularFile(dir.resolve("tls.crt")) then dir
    else pki.issue(uris = Seq(uri), dnsNames = dns).writeTo(dir)

  private val issuer      = "https://api.example.test"
  private val machineKeys = MachineKeys.inMemory()
  machineKeys.rotate(): Unit
  private val machines =
    MachineTokens.withKeys(issuer, machineKeys.keys.map(k => k.kid -> k.publicKey).toMap)

  /** No erasure was ever logged: the keyring's replay has nothing to apply. */
  private object noLog extends LogCopy:
    val name                               = "none"
    def after(seq: Long): Vector[LogEntry] = Vector.empty

  private var host: Option[AnkkaTestKit]             = None
  private var keyring: Option[KeyringServer.Started] = None
  private def started: KeyringServer.Started         = keyring.getOrElse(fail("no keyring"))
  private def keyringAddress: String    = s"https://localhost:${started.server.ports.main}"
  private def keyringDatabase: Database =
    Database()(using host.getOrElse(fail("no keyring")).service.system)

  private def restartKeyring(): Unit =
    keyring.foreach(_.stop())
    val h = host.getOrElse {
      val k = AnkkaTestKit.start(Seq.empty)
      host = Some(k)
      k
    }
    given org.apache.pekko.actor.typed.ActorSystem[?] = h.service.system
    Await.result(
      Database().execute(SqlFragment.raw("DROP SCHEMA IF EXISTS keyring CASCADE")),
      30.seconds
    ): Unit
    val s = KeyringServer.start(
      Database(),
      RootKey.Local(IArray.fill[Byte](32)(7), "suite"),
      ProjectGrants.fromDirectory(grantsRoot),
      Vector(noLog),
      RotatingTls(certificate(Main.Identity, Seq("localhost")), 1.minute),
      port = 0,
      probePort = 0,
      machines = Some(machines),
      replica = "keyring-0",
      retryEvery = 100.millis
    )
    keyring = Some(s)
    eventually("the keyring ready")(Option.when(s.replay.done)(()))

  private def remoteAs(in: String, service: String) =
    RemoteKeyStoreClient(
      keyringAddress,
      Some(RotatingTls(certificate(s"ankka://$in/$service"), 1.minute)),
      in,
      service,
      s"$service-${instances.incrementAndGet()}",
      10.seconds
    )
  private val instances = java.util.concurrent.atomic.AtomicInteger()

  /** What the keyring recorded: each row of `table` matching `where`, its columns as text. */
  private def records(table: String, where: String): Vector[Map[String, String]] =
    Await.result(
      keyringDatabase.query(SqlFragment.raw(s"SELECT * FROM keyring.$table WHERE $where"))(row =>
        val meta = row.getMetadata.getColumnMetadatas.asScala.map(_.getName).toVector
        meta.map(c => c -> String.valueOf(row.get(c))).toMap
      ),
      30.seconds
    )

  // ── the grants, as the operator renders them into `brand`'s project ConfigMap ──

  private var brandGrants = Vector.empty[ProjectGrantEntry]

  private def writeGrants(): Unit =
    val dir = Files.createDirectories(grantsRoot.resolve("brand"))
    Files.writeString(
      dir.resolve("grants.json"),
      ProjectConfig.renderGrants(AnkkaProjectSpec(projectId = "brand", grants = brandGrants.toList))
    ): Unit

  private def grantTopic(id: String, grantee: String, decrypt: Boolean): Unit =
    brandGrants = brandGrants.filterNot(_.id == id) :+ ProjectGrantEntry(
      id = id,
      grantee = grantee,
      kind = "topic",
      topic = Some("players"),
      right = Some("consume"),
      decrypt = decrypt
    )
    writeGrants()

  private val PaymentsGrant  = "g-payments"
  private val WarehouseGrant = "g-warehouse"

  // ── the two projects ─────────────────────────────────────────────────────────

  private var broker: InMemoryBroker = InMemoryBroker()

  /**
   * The broker as `payments` reaches it: another project's topic `<project>/<name>` is the topic
   * the producing project publishes as `<name>`, as Kafka holds both as `<project>.<name>`.
   */
  private final class Qualified(underlying: InMemoryBroker) extends MessageSubscriber:
    private def own(topic: String) = topic.split("/", 2) match
      case Array("brand", name) => name
      case _                    => topic
    def subscribe(
        subscription: TopicSubscription,
        handle: IncomingMessage => Future[org.apache.pekko.Done]
    ): Subscribed =
      underlying.subscribe(subscription.copy(topic = own(subscription.topic)), handle)
    def earliestRetained(topic: String) = underlying.earliestRetained(own(topic))
    def stop(): Unit                    = ()

  private var players: Option[AnkkaTestKit]     = None
  private var cardholders: Option[AnkkaTestKit] = None
  private def brandKit: AnkkaTestKit            = players.getOrElse(fail("no players"))
  private def paymentsKit: AnkkaTestKit         = cardholders.getOrElse(fail("no cardholders"))

  private def startKits(): Unit =
    players = Some(
      AnkkaTestKit.start(
        Seq(PlayerEntity.descriptor, RegistrationPublisher.descriptor),
        Seq(ProjectionRuntime.withBroker(broker, broker)),
        configure = _.withKeyStore((_, in) => remoteAs(in, "players")),
        serviceIdentity = ServiceIdentity.deployed("brand", "players")
      )
    )
    cardholders = Some(
      AnkkaTestKit.start(
        Seq(Cardholders.descriptor, Cardholdings.descriptor),
        Seq(ProjectionRuntime.withBroker(broker, Qualified(broker))),
        configure = _.withKeyStore((_, in) => remoteAs(in, "cardholders")),
        serviceIdentity = ServiceIdentity.deployed("payments", "cardholders"),
        settings = ConfigFactory.parseString(
          s"ankka.keyring.cache.foreign-ttl = ${foreignTtl.toMillis}ms"
        )
      )
    )

  private def stopKits(): Unit =
    (players.toVector ++ cardholders.toVector).foreach(k => scala.util.Try(k.stop()))
    players = None
    cardholders = None

  override def beforeEach(context: BeforeEach): Unit =
    stopKits()
    broker = InMemoryBroker()
    brandGrants = Vector.empty
    writeGrants()
    Cardholders.handed.clear()
    registered = false
    machine = false
    revokedAt = None
    readAfter = None
    asked = None
    restartKeyring()

  override def afterAll(): Unit =
    stopKits()
    keyring.foreach(_.stop())
    host.foreach(_.stop())

  private def eventually[A](what: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = within.fromNow
    var last: Option[A] = None
    while last.isEmpty && deadline.hasTimeLeft() do
      last = scala.util.Try(check).toOption.flatten
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$what did not happen within $within"))

  // ── what the steps do ───────────────────────────────────────────────────────

  private val Subject = "player/8c1f"
  private val Id      = "8c1f"

  private var registered = false

  /** `brand` records the player's registration, which its publisher puts on the topic. */
  private def register(name: String): Unit =
    if !registered then
      assertEquals(
        brandKit.componentClient
          .forEventSourcedEntity(EntityId(Id))
          .call(PlayerEntity.register)
          .invoke(RegisterPlayer("ada@example.com", name, LocalDate.parse("1815-12-10"), "EUR")),
        Done
      )
      registered = true
    eventually("the registration is published")(broker.publishedTo("players").headOption): Unit

  private def row: Option[CardholderRow] =
    paymentsKit.service.viewClient
      .forView(Cardholdings)
      .ask(Cardholdings.byPlayer, "player_id" -> Id)
      .headOption

  private def handed: Personal[String] =
    eventually("cardholders is handed the registration")(Cardholders.handed.asScala.headOption)._2

  private val EnvelopeOf =
    """"name":(\{"\$personal":1,"project":"([^"]*)","subject":"([^"]*)"(?:,"c":"[^"]*")?\})""".r

  /** The envelope of `name` in `text`, and the project it names. */
  private def nameEnvelope(text: String): (String, String) =
    EnvelopeOf
      .findFirstMatchIn(text.replace(" ", ""))
      .map(m => m.group(1) -> m.group(2))
      .getOrElse(fail(s"no envelope of 'name' in $text"))

  private def brandKey: IArray[Byte] =
    val probe = remoteAs("brand", "players")
    try
      probe.key("brand", DataSubject(Subject), create = false) match
        case KeyAnswer.Key(k) => k
        case other            => fail(s"brand holds no key of $Subject: $other")
    finally probe.close()

  // ── background ──────────────────────────────────────────────────────────────

  Given(
    "a project {string} with the service {string}, which publishes {string} to the topic {string}"
  ) { (project: String, service: String, event: String, topic: String) =>
    assertEquals(
      (project, service, event, topic),
      ("brand", "players", "PlayerRegistered", "players")
    )
  }

  Given(
    "the event {string} has the field {string} marked as a personal field of the data subject {string}"
  ) { (event: String, field: String, subject: String) =>
    assertEquals((event, field, subject), ("PlayerRegistered", "name", Subject))
  }

  Given("a project {string} with a consumer {string} that reads the topic {string} of {string}") {
    (project: String, consumer: String, topic: String, of: String) =>
      assertEquals((project, consumer, topic, of), ("payments", "cardholders", "players", "brand"))
      startKits()
  }

  // ── a consumer in another project ───────────────────────────────────────────

  Given("{string} has a grant on the topic {string} of {string} that allows decryption") {
    (grantee: String, topic: String, of: String) =>
      assertEquals((grantee, topic, of), ("payments", "players", "brand"))
      grantTopic(PaymentsGrant, "service:payments/cardholders", decrypt = true)
  }

  Given("{string} has a grant on the topic {string} of {string} that does not allow decryption") {
    (grantee: String, topic: String, of: String) =>
      assertEquals((grantee, topic, of), ("payments", "players", "brand"))
      grantTopic(PaymentsGrant, "service:payments/cardholders", decrypt = false)
  }

  When("{string} reads a {string} of {string} with the name {string}") {
    (_: String, event: String, subject: String, name: String) =>
      assertEquals((event, subject), ("PlayerRegistered", Subject))
      register(name)
  }

  Then("the handler of {string} is handed the name {string}") { (_: String, name: String) =>
    assertEquals(handed.orElse("erased"), name)
  }

  Then("the keyring gave {string} the subject key of {string} because it holds the grant") {
    (grantee: String, subject: String) =>
      val read = records(
        "granted_fetch",
        s"caller = 'service:$grantee/cardholders' AND project = 'brand' AND subject = '$subject'"
      )
      assertEquals(read.map(_("grant_id")).distinct, Vector(PaymentsGrant), read)
      // The name it was handed travelled sealed under brand's key, never in the clear.
      val (_, project) = nameEnvelope(broker.publishedTo("players").map(_.text).mkString)
      assertEquals(project, "brand")
      paymentsKit.assertNoPersonalValueIn(broker)("Ada Byron")
  }

  Then("the handler of {string} reads the field {string} as erased") { (_: String, field: String) =>
    assertEquals(field, "name")
    assert(handed.isErased, "cardholders was handed the name in the clear")
  }

  Then("the keyring records that it refused {string} the subject key of {string}") {
    (grantee: String, subject: String) =>
      val refused = records(
        "fetch_refusal",
        s"caller = 'service:$grantee/cardholders' AND project = 'brand' AND subject = '$subject'"
      )
      assert(refused.nonEmpty, "no refusal was recorded")
      assert(refused.forall(_("reason").contains("no grant")), refused)
      assertEquals(records("granted_fetch", "true"), Vector.empty)
  }

  // ── an erasure in the producing project ─────────────────────────────────────

  Given("{string} has kept the name {string} of {string} in a row of a view of {string}") {
    (_: String, name: String, subject: String, _: String) =>
      assertEquals(subject, Subject)
      register(name)
      val kept = eventually("the row is kept")(row)
      assertEquals(kept.name.orElse("erased"), name)
      // payments' own table keeps the name sealed under brand's key, as brand wrote it (FR-027).
      val text = Await
        .result(
          Database()(using paymentsKit.service.system).query(
            SqlFragment.raw(s"SELECT payload FROM ${Cardholdings.table}")
          )(r =>
            r.get(0) match
              case b: java.nio.ByteBuffer =>
                val out = new Array[Byte](b.remaining)
                b.get(out)
                new String(out, UTF_8)
              case b: Array[Byte] => new String(b, UTF_8)
              case other          => String.valueOf(other)
          ),
          30.seconds
        )
        .mkString
      val (envelope, project) = nameEnvelope(text)
      assertEquals(project, "brand", s"payments sealed the name under its own key: $envelope")
      val c =
        "\"c\":\"([^\"]*)\"".r.findFirstMatchIn(envelope).map(_.group(1)).getOrElse(fail(envelope))
      val opened = PersonalCipher.open(
        brandKey,
        Base64.getDecoder.decode(c),
        PersonalCipher.associatedData(Subject, "brand", "cardholder-row")
      )
      assertEquals(opened.map(new String(_, UTF_8)), Some(s"\"$name\""))
      paymentsKit.assertNoPersonalValue(name)
  }

  private val seqs = java.util.concurrent.atomic.AtomicLong()

  private def erase(subject: String, in: String): Unit =
    val admin = KeyringAdmin.Door(
      keyringAddress,
      certificate(s"ankka://${Admission.ControlPlane.project}/controlplane")
    )
    Await.result(admin.destroy(ErasureOrder(seqs.incrementAndGet(), in, subject)), 30.seconds): Unit

  When("{string} is erased in {string}") { (subject: String, in: String) =>
    erase(subject, in)
  }

  Then("the view of {string} reads the field {string} of that row as erased") {
    (_: String, field: String) =>
      assertEquals(field, "name")
      // Within the time payments holds a key of brand's, and no notice of brand's reaching it.
      val read = eventually("the row reads as erased", foreignTtl + 10.seconds)(
        row.filter(_.name.isErased)
      )
      assert(read.name.isErased)
  }

  Then("no erasure request was asked for in {string}") { (in: String) =>
    assertEquals(records("applied_log", s"project = '$in'"), Vector.empty)
    assertEquals(records("notice_outbox", s"project = '$in'"), Vector.empty)
    assertEquals(records("applied_log", "project = 'brand'").map(_("subject")), Vector(Subject))
  }

  // ── a revoked grant ─────────────────────────────────────────────────────────

  private var revokedAt: Option[Instant]               = None
  private var readAfter: Option[Option[CardholderRow]] = None

  Given("{string} has since revoked the grant") { (of: String) =>
    assertEquals(of, "brand")
    if !machine then
      // payments read the name before the revocation, and holds brand's key for it.
      register("Ada Byron")
      assertEquals(eventually("the row is kept")(row).name.orElse("erased"), "Ada Byron")
    brandGrants = Vector.empty
    writeGrants()
    revokedAt = Some(Instant.now())
  }

  When("{string} next needs the subject key of {string}") { (_: String, subject: String) =>
    assertEquals(subject, Subject)
    val since = revokedAt.getOrElse(fail("no grant was revoked"))
    // The key payments held lapses at foreign-ttl; the next read asks the keyring.
    val wait = foreignTtl.toMillis - java.time.Duration.between(since, Instant.now()).toMillis
    if wait > 0 then Thread.sleep(wait + 200)
    readAfter = Some(row)
  }

  Then("the keyring refuses it") { () =>
    val since   = revokedAt.getOrElse(fail("no grant was revoked"))
    val refused = records(
      "fetch_refusal",
      s"caller = 'service:payments/cardholders' AND project = 'brand' AND subject = '$Subject'"
    )
    assert(refused.nonEmpty, "the keyring recorded no refusal after the revocation")
    val probe = remoteAs("payments", "cardholders")
    try
      assert(
        probe.key("brand", DataSubject(Subject), create = false).isInstanceOf[KeyAnswer.Refused]
      )
    finally probe.close()
    assert(Instant.now().isAfter(since))
  }

  Then("{string} reads the field {string} as erased") { (_: String, field: String) =>
    assertEquals(field, "name")
    val read = readAfter.flatten.getOrElse(fail("cardholders read no row"))
    assert(read.name.isErased, "the row was read in the clear after the revocation")
  }

  Then(
    "no personal field of a data subject of {string} is read as its value in {string} more than 5 minutes after the grant was revoked"
  ) { (_: String, _: String) =>
    // The bound is foreign-ttl: what the platform holds by default, and what this suite held.
    val platform = FiniteDuration(
      ConfigFactory.defaultReference().getDuration("ankka.keyring.cache.foreign-ttl").toMillis,
      java.util.concurrent.TimeUnit.MILLISECONDS
    )
    assert(platform <= 5.minutes, s"the platform holds another project's key for $platform")
    assert(foreignTtl <= platform)
    val since = revokedAt.getOrElse(fail("no grant was revoked"))
    assert(row.forall(_.name.isErased))
    assert(java.time.Duration.between(since, Instant.now()).toMillis >= foreignTtl.toMillis)
  }

  // ── a machine outside the installation ──────────────────────────────────────

  private var machine = false

  private val machineClient = HttpClient
    .newBuilder()
    .sslContext(RotatingTls(certificate(RotatingTls.GatewayUri), 1.minute).sslContext)
    .build()

  /** The machine's token, as the control plane issues it, and its call through the gateway. */
  private def asMachine(path: String, body: String): (Int, String) =
    val token = machineKeys.sign(
      MachineTokenEndpoint.claims(
        MachineSettings(issuer),
        "acme",
        "warehouse",
        java.time.Clock.systemUTC()
      )
    )
    val response = machineClient.send(
      HttpRequest
        .newBuilder(URI.create(keyringAddress + path))
        .timeout(java.time.Duration.ofSeconds(30))
        .header("Content-Type", "application/json")
        .header("Authorization", s"Bearer $token")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    (response.statusCode, response.body)

  /** The envelope of `name` the machine read from the topic. */
  private def machineReads(): String =
    register("Ada Byron")
    nameEnvelope(broker.publishedTo("players").map(_.text).mkString)._1

  private def decrypt(envelope: String): (Int, String) =
    asMachine(
      "/v1/decrypt",
      writeToString(
        DecryptRequest(
          WarehouseGrant,
          "brand",
          "player-event",
          Vector(RawJson(envelope.getBytes(UTF_8)))
        )
      )
    )

  private var asked: Option[(Int, String)] = None

  Given(
    "a machine outside the installation with a grant on the topic {string} of {string} that does not allow decryption"
  ) { (topic: String, of: String) =>
    assertEquals((topic, of), ("players", "brand"))
    machine = true
    grantTopic(WarehouseGrant, "machine:acme/warehouse", decrypt = false)
  }

  Given(
    "a machine outside the installation with a grant on the topic {string} of {string} that allows decryption"
  ) { (topic: String, of: String) =>
    assertEquals((topic, of), ("players", "brand"))
    machine = true
    grantTopic(WarehouseGrant, "machine:acme/warehouse", decrypt = true)
  }

  When("the machine reads a {string} of {string}") { (event: String, subject: String) =>
    assertEquals((event, subject), ("PlayerRegistered", Subject))
    asked = Some(decrypt(machineReads()))
  }

  Then("the machine reads the field {string} as erased") { (field: String) =>
    assertEquals(field, "name")
    // What it read holds no value in the clear, and neither door opens the field for it.
    assert(!broker.publishedTo("players").map(_.text).mkString.contains("Ada Byron"))
    val (status, body) = asked.getOrElse(fail("the machine asked nothing"))
    assertEquals(status, 403, body)
    assert(readFromString[KeyReply](body).refused.exists(_.contains("holds no grant")), body)
    val (keyStatus, keyBody) =
      asMachine("/v1/keys", s"""{"project":"brand","subject":"$Subject","create":false}""")
    assertEquals(keyStatus, 403, keyBody)
    assertEquals(records("decryption", "true"), Vector.empty)
  }

  When(
    "the machine reads a {string} of {string} and asks the keyring to decrypt the field {string}"
  ) { (event: String, subject: String, field: String) =>
    assertEquals((event, subject, field), ("PlayerRegistered", Subject, "name"))
    asked = Some(decrypt(machineReads()))
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
    val key       = Base64.getEncoder.encodeToString(IArray.genericWrapArray(brandKey).toArray)
    assert(!body.contains(key) && !body.contains("\"key\""), body)
    val (status, refused) =
      asMachine("/v1/keys", s"""{"project":"brand","subject":"$Subject","create":false}""")
    assertEquals(status, 403, refused)
    assert(!refused.contains(key))
  }

  Then("the keyring records the decryption against the machine and the grant") { () =>
    val recorded = records("decryption", s"subject = '$Subject'")
    assertEquals(
      recorded.map(r => (r("machine"), r("grant_id"), r("project"), r("field_count"))),
      Vector(("machine:acme/warehouse", WarehouseGrant, "brand", "1"))
    )
  }

  Given("{string} has since been erased in {string}") { (subject: String, in: String) =>
    // The machine has read the registration first; then the subject is erased.
    register("Ada Byron")
    erase(subject, in)
  }

  When(
    "the machine asks the keyring to decrypt the field {string} of a {string} of {string}"
  ) { (field: String, event: String, subject: String) =>
    assertEquals((field, event, subject), ("name", "PlayerRegistered", Subject))
    asked = Some(decrypt(machineReads()))
  }

  Then("the machine is refused") { () =>
    val (status, body) = asked.getOrElse(fail("the machine asked nothing"))
    assertEquals(status, 403, body)
    assert(readFromString[KeyReply](body).refused.nonEmpty, body)
    assert(!body.contains("Ada Byron"))
    assertEquals(
      records("decryption", "true"),
      Vector.empty,
      "a refused decryption was recorded as one"
    )
    assert(
      records("fetch_refusal", "caller = 'machine:acme/warehouse'").nonEmpty,
      "the refusal was not recorded"
    )
  }
