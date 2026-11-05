package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.erasure.*
import com.thinkmorestupidless.ankka.core.{
  Codecs,
  CommandError,
  DataSubject,
  Done,
  EntityId,
  ErrorCode,
  KeyAnswer,
  Keyring,
  KeyringUnavailable,
  Personal,
  PersonalCipher
}
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  InMemoryBroker,
  ProjectionRuntime,
  ServiceIdentity,
  SqlFragment
}
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  GherkinSuite,
  InMemoryKeyring,
  LogCapturing
}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.util.Base64
import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * `features/erasure/`, in process (feature 042): services under `AnkkaTestKit` with the in-memory
 * keyring, or the keyring's own server over loopback, and the control plane's erasure requests,
 * routes and CLI in this JVM. In the control plane's tests rather than the test kit's because an
 * erasure request is the control plane's, and its tests can see the kits, the CLI and the keyring.
 *
 * What only a cluster can show is delegated, by name, to the two k3s suites; what each language's
 * SDK writes is proved by that SDK's suites against the shared fixtures and by the conformance
 * suite. `features/installation/facts.feature`'s erasure scenarios run in
 * `InstallationFactsFeatures`, which runs that file whole.
 */
class ErasureFeatures(
    features: String = "../features/erasure",
    /** Through the keyring's own server over loopback, rather than the keyring in memory. */
    onKeyring: Boolean = false
) extends GherkinSuite(features)
    with LogCapturing:

  /** munit's: the whole directory, with the keyring in memory. */
  def this() = this("../features/erasure", false)

  override val munitTimeout = 4.minutes

  override protected def ranElsewhere: Map[String, String] = Map(
    // A session's and an instance's own suite: kits with agents and a scripted model.
    "a session started with a data subject keeps its conversation encrypted under the subject key" ->
      "ErasureAgentsFeatures",
    "a session of an erased data subject reads as erased and a new turn begins with nothing" ->
      "ErasureAgentsFeatures",
    "an agent instance or a task tagged with an erased data subject reads as erased and the agent instance is stopped" ->
      "ErasureAgentsFeatures",
    "a session started with no data subject is unchanged by any erasure" ->
      "ErasureAgentsFeatures",
    // Two installations of one service in this JVM, and the test kit's keyring relay.
    "a follower of a replicated entity reads a personal field with the subject key the home's keyring gives it" ->
      "ErasureReplicationFeatures",
    "an erasure at the home reaches a follower of a replicated entity within a minute" ->
      "ErasureReplicationFeatures",
    "a follower that cannot reach the home's keyring and has no subject key retries the recovery of the entity" ->
      "ErasureReplicationFeatures",
    // The console is the console package's own: its Playwright suite, against the fake control
    // plane, which ControlPlaneFixturesSuite holds to the real one's answers.
    "a member asks for an erasure request through the console" ->
      "the console's Playwright suite (just test-console)",
    "the console shows the erasure requests of a project with where each one stands and each service's completion" ->
      "the console's Playwright suite (just test-console)",
    "a member fetches the erasure certificate of an applied erasure request through the console" ->
      "the console's Playwright suite (just test-console)",
    "only an owner overrides a hold, an owner through the console" ->
      "the console's Playwright suite (just test-console)",
    "only an owner overrides a hold, a member who is not an owner through the console" ->
      "the console's Playwright suite (just test-console)",
    // A restored database and a restored keyring are a cluster's: CNPG's recovery and the hold.
    "a service restored to before an erasure is not ready until it has applied the erasure log to its own tables" ->
      "ErasureRestoreClusterFeatures, on k3s",
    "the keyring restored to before an erasure applies the erasure log before it answers any request" ->
      "ErasureRestoreClusterFeatures, on k3s",
    // Two projects and a machine, against the keyring's own server and its real admission.
    "a consumer in another project with a grant that allows decryption reads the value" ->
      "ErasureProjectsFeatures",
    "a consumer in another project with a grant that does not allow decryption reads the field as erased" ->
      "ErasureProjectsFeatures",
    "an erasure in the producing project reaches what another project kept of the data subject" ->
      "ErasureProjectsFeatures",
    "a revoked grant ends another project's reads of the producing project's data subjects" ->
      "ErasureProjectsFeatures",
    "a machine outside the installation with a grant that does not allow decryption reads every personal field as erased" ->
      "ErasureProjectsFeatures, and ErasureClusterFeatures through the gateway on k3s",
    "a machine outside the installation with a grant that allows decryption asks the keyring to decrypt each field" ->
      "ErasureProjectsFeatures, and ErasureClusterFeatures through the gateway on k3s",
    "the keyring refuses to decrypt for a machine outside the installation once its grant is revoked or the data subject is erased" ->
      "ErasureProjectsFeatures, and ErasureClusterFeatures through the gateway on k3s",
    // Each language writes the envelope itself.
    "an SDK writes the same personal envelope and reads it back into its own personal type" ->
      ("ConformanceSuite's personal.* and erasure.handler-runs cases against each language's " +
        "reference (uv run conformance, npm run conformance, go run ./cmd/conformance, " +
        "./conformance.sh), RemoteEntitySuite for the bytes the sidecar journals, and each " +
        "SDK's suite against protocol/fixtures/personal"),
    "a service written in a language of the JVM writes the personal envelope a service written in Scala writes" ->
      "PersonalJavaSuite (testkit-java) and the Kotlin SDK's PersonalTest"
  )

  // ── the service ────────────────────────────────────────────────────────────

  private var kits: Vector[AnkkaTestKit] = Vector.empty
  private var broker: InMemoryBroker     = InMemoryBroker()
  private var keyring: InMemoryKeyring   = InMemoryKeyring()
  private var project: String            = "brand"

  /** The scenario's first kit: `players` in its project. */
  private def kit: AnkkaTestKit = kits.headOption.getOrElse(fail("no service was started"))

  /** The endpoint of `edge.feature`, served by the first kit, on a port of its own. */
  private var server: Option[com.thinkmorestupidless.ankka.http.HttpServer] = None

  private val descriptors = Seq(
    PlayerEntity.descriptor,
    PlayerContactEntity.descriptor,
    Profiles.descriptor,
    RegistrationPublisher.descriptor,
    Engagement.descriptor
  )

  /** The services' names, as `kits` holds them. */
  private var names: Vector[String] = Vector.empty

  /**
   * Each run of `players`' erasure handler: the data subject, and how many objects it erased. It
   * holds one object per data subject until a run erases it, so a second run has nothing to do.
   */
  private val handlerRuns = java.util.concurrent.ConcurrentLinkedQueue[(String, Long)]()
  private val objectsHeld = java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()

  private val handler = com.thinkmorestupidless.ankka.sdk.ErasureHandler { _ => subject =>
    val erased = if objectsHeld.remove(subject.value) != null then 1L else 0L
    handlerRuns.add(subject.value -> erased)
    com.thinkmorestupidless.ankka.sdk.ErasureReport(erased)
  }

  // ── the keyring's own server, for `ErasureKeyringFeatures` ──────────────────

  private lazy val pki     = com.thinkmorestupidless.ankka.testpki.TestPki.root("erasure-features")
  private lazy val pkiRoot = java.nio.file.Files.createTempDirectory("erasure-features")
  private var keyringKit   = Option.empty[AnkkaTestKit]
  private var keyringStarted = Option.empty[com.thinkmorestupidless.ankka.keyring.Keyring.Started]
  private val instances      = java.util.concurrent.atomic.AtomicInteger()

  /** The certificate of `uri`, issued by the suite's authority, in a directory of its own. */
  private def certificate(uri: String, dns: Seq[String] = Nil): java.nio.file.Path =
    val dir = pkiRoot.resolve(uri.replaceAll("[^a-z0-9]+", "-"))
    if java.nio.file.Files.isRegularFile(dir.resolve("tls.crt")) then dir
    else pki.issue(uris = Seq(uri), dnsNames = dns).writeTo(dir)

  private def keyringDatabase: Database =
    Database()(using keyringKit.getOrElse(fail("no keyring is running")).service.system)

  private def keyringAddress: String =
    s"https://localhost:${keyringStarted.getOrElse(fail("no keyring is running")).server.ports.main}"

  /** The erasure log as the control plane holds it, for the keyring to replay. */
  private object planeLog extends com.thinkmorestupidless.ankka.keyring.LogCopy:
    val name             = "control plane"
    def after(seq: Long) =
      plane.application.fold(Vector.empty[com.thinkmorestupidless.ankka.keyring.LogEntry])(a =>
        Await
          .result(a.log.after(seq), 30.seconds)
          .map(e =>
            com.thinkmorestupidless.ankka.keyring
              .LogEntry(e.seq, e.project, e.subject, e.rid, e.appliedAt)
          )
      )

  /**
   * A keyring of its own for each scenario: its tables emptied, its replay done before it answers.
   */
  private def restartKeyring(): Unit =
    keyringStarted.foreach(_.stop())
    val host = keyringKit.getOrElse {
      val k = AnkkaTestKit.start(Seq.empty)
      keyringKit = Some(k)
      k
    }
    given org.apache.pekko.actor.typed.ActorSystem[?] = host.service.system
    Await.result(
      Database().execute(SqlFragment.raw("DROP SCHEMA IF EXISTS keyring CASCADE")),
      30.seconds
    ): Unit
    val started = com.thinkmorestupidless.ankka.keyring.Keyring.start(
      Database(),
      com.thinkmorestupidless.ankka.keyring.RootKey.Local(IArray.fill[Byte](32)(5), "suite"),
      com.thinkmorestupidless.ankka.keyring.ProjectGrants.none,
      Vector(planeLog),
      com.thinkmorestupidless.ankka.runtime.RotatingTls(
        certificate(com.thinkmorestupidless.ankka.keyring.Main.Identity, Seq("localhost")),
        1.minute
      ),
      port = 0,
      probePort = 0,
      machines = None,
      replica = "keyring-0",
      retryEvery = 100.millis
    )
    keyringStarted = Some(started)
    eventually("the keyring ready")(Option.when(started.replay.done)(()))
    admin = KeyringAdmin.Door(
      keyringAddress,
      certificate(
        s"ankka://${com.thinkmorestupidless.ankka.keyring.Admission.ControlPlane.project}/controlplane"
      )
    )

  /** The keyring as `service` of `in` reaches it: what a kit is handed, and what the steps read. */
  private def remoteAs(in: String, service: String) =
    com.thinkmorestupidless.ankka.runtime.keyring.RemoteKeyStoreClient(
      keyringAddress,
      Some(
        com.thinkmorestupidless.ankka.runtime
          .RotatingTls(certificate(s"ankka://$in/$service"), 1.minute)
      ),
      in,
      service,
      s"$service-${instances.incrementAndGet()}",
      10.seconds
    )

  // What the steps ask of the keyring, of whichever one the scenario runs.

  private def keyOf(in: String, subject: String): KeyAnswer =
    if onKeyring then
      val probe = remoteAs(in, "players")
      try probe.key(in, DataSubject(subject), create = false)
      finally probe.close()
    else keyring.key(in, DataSubject(subject), create = false)

  private def holdsKey(in: String, subject: String): Boolean =
    if onKeyring then keyOf(in, subject).isInstanceOf[KeyAnswer.Key]
    else keyring.holdsKey(in, DataSubject(subject))

  private def keysMade(in: String, subject: String): Int =
    if onKeyring then
      // One row per data subject, by its key: a second key made would have replaced nothing.
      Await
        .result(
          keyringDatabase.query(
            SqlFragment.raw(
              s"SELECT count(*) AS n FROM keyring.subject_key WHERE project = '$in' AND subject = '$subject'"
            )
          )(_.get("n", classOf[java.lang.Long]).intValue),
          30.seconds
        )
        .headOption
        .getOrElse(0)
    else keyring.keysMade(in, DataSubject(subject))

  /** The last re-run sent, by its sequence. */
  private def lastRerun: Option[Long] =
    if onKeyring then
      Await
        .result(
          keyringDatabase.query(
            SqlFragment.raw(
              "SELECT erasure_seq FROM keyring.notice_outbox WHERE rerun ORDER BY id DESC LIMIT 1"
            )
          )(_.get("erasure_seq", classOf[java.lang.Long]).longValue),
          30.seconds
        )
        .headOption
    else keyring.sent.filter(_.rerun).lastOption.map(_.seq)

  /** `service`'s acknowledgement of `seq`, as the keyring recorded it. */
  private def ackOf(
      seq: Long,
      service: String
  ): Option[com.thinkmorestupidless.ankka.core.keyring.Completion] =
    if onKeyring then
      Await.result(
        keyringDatabase.queryOne(
          SqlFragment.raw(
            s"SELECT objects_erased, finality, error FROM keyring.notice_ack WHERE erasure_seq = $seq AND service = '$service'"
          )
        )(r =>
          com.thinkmorestupidless.ankka.core.keyring.Completion(
            0,
            0,
            0,
            Option(r.get("finality", classOf[String])).map(f =>
              com.thinkmorestupidless.ankka.core.keyring.HandlerReport(
                Option(r.get("objects_erased", classOf[java.lang.Long])).fold(0L)(_.longValue),
                f,
                Option(r.get("error", classOf[String]))
              )
            )
          )
        ),
        30.seconds
      )
    else keyring.acks.find(a => a.seq == seq && a.service == service).map(_.completion)

  /** The window a soft-delete scenario's store keeps a deleted object for. */
  private val SoftDeleteWindow: FiniteDuration = 7.days

  /** The data subject the last `is erased in` step erased. */
  @volatile private var lastErased: Option[String] = None

  /** Each run of a bucket's handler (`objects.feature`): the service, the subject, the count. */
  private val bucketRuns = java.util.concurrent.ConcurrentLinkedQueue[(String, String, Long)]()

  /** The handler of every service but `players`: it erases its unnamed bucket's objects. */
  private def bucketHandler(service: String) =
    com.thinkmorestupidless.ankka.sdk.ErasureHandler { ctx => subject =>
      val removed = ctx.storage.default.erase(subject)
      bucketRuns.add((service, subject.value, removed.count))
      com.thinkmorestupidless.ankka.sdk.ErasureReport(removed.count, removed.finality)
    }

  private def startKit(
      name: String = "players",
      storage: com.thinkmorestupidless.ankka.testkit.StorageBackendChoice =
        com.thinkmorestupidless.ankka.testkit.StorageBackendChoice.Memory()
  ): AnkkaTestKit =
    val http = com.thinkmorestupidless.ankka.http.HttpServer.at("127.0.0.1", 0)(clients =>
      PlayersEndpoint(clients)
    )
    if kits.isEmpty then server = Some(http)
    val started = AnkkaTestKit.start(
      descriptors,
      Seq(ProjectionRuntime.withBroker(broker, broker), http),
      configure = b =>
        val handled =
          if name == "players" then b.withErasureHandler(handler)
          else b.withErasureHandler(bucketHandler(name))
        if onKeyring then handled.withKeyStore((_, in) => remoteAs(in, name)) else handled
      ,
      serviceIdentity = ServiceIdentity.deployed(project, name),
      keyring = keyring,
      storage = storage
    )
    kits :+= started
    names :+= name
    started

  private def kitNamed(name: String): AnkkaTestKit =
    names.indexOf(name) match
      case -1 => fail(s"no service '$name' was started")
      case i  => kits(i)

  private def stopKits(): Unit =
    kits.foreach(k => scala.util.Try(k.stop()))
    kits = Vector.empty
    names = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    stopKits()
    broker = InMemoryBroker()
    keyring = InMemoryKeyring()
    Engagement.handed.clear()
    bucketRuns.clear()
    written = None
    admin = TestKeyringAdmin(keyring, () => names)
    if onKeyring then restartKeyring()
    asked = Map.empty
    failing = None
    failedApply = None
    logBucket.refuse = false
    handlerRuns.clear()
    objectsHeld.clear()
    // The control plane's clock, as a scenario that moved it found it.
    if harnessStarted && moved != java.time.Duration.ZERO then
      harness.identity.clock.advance(moved.negated())
    moved = java.time.Duration.ZERO
    filer = "ada"

  override def afterAll(): Unit =
    stopKits()
    keyringStarted.foreach(_.stop())
    keyringKit.foreach(_.stop())
    if harnessStarted then harness.stop()

  // ── the control plane, for what a member applies ──────────────────────────

  private var erasureOn                   = false
  private var harnessStarted              = false
  private lazy val harness: GrantsHarness =
    val h = GrantsHarness(
      erasure =
        () => com.thinkmorestupidless.ankka.controlplane.tenancy.ErasureSettings(erasureOn, 2),
      erasurePlane = Some(plane)
    )
    h.start()
    h.organization("acme", "ada")
    // holds.feature's people: ada owns acme, bob is a member, cyd a member who files requests.
    h.member("bob", "acme", "ada")
    h.member("cyd", "acme", "ada")
    h.project("brand", "acme", "ada")
    harnessStarted = true
    h

  private var applied: (Int, String) = (0, "")

  /**
   * The installation's keyring as the control plane orders it: the scenario's, which each scenario
   * replaces, so the plane the control plane started with reaches the current one.
   */
  private var admin: KeyringAdmin = TestKeyringAdmin(keyring)
  private object currentKeyring extends KeyringAdmin:
    // The control plane's project for this scenario is a fresh one; the services' is `project`.
    def destroy(order: com.thinkmorestupidless.ankka.core.keyring.ErasureOrder) =
      admin.destroy(order.copy(project = project))
    def resend(seq: Long) = admin.resend(seq)
    def status(seq: Long) = admin.status(seq)

  private val logBucket = MemoryErasureLogBucket()
  private val plane     =
    // The keyring reports in real time, so the plane reads real time, moved as far as the
    // scenario moved the endpoints' clock: a hold lapses when the endpoints' date passes.
    ErasurePlane(
      currentKeyring,
      Some(logBucket),
      clock = () => java.time.Clock.offset(java.time.Clock.systemUTC(), moved)
    )
  private lazy val sweep = ErasureSweep(plane, () => 30.days)

  private def apply(service: String, personal: Boolean): (Int, String) =
    val said = if personal then ""","personal":true""" else ""
    harness.send(
      "PUT",
      s"/services/brand/$service",
      Some(s"""{"name":"$service","service":{"image":"$service:1.0"$said}}"""),
      "ada"
    )

  private def eventually[A](what: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = within.fromNow
    var last: Option[A] = None
    while last.isEmpty && deadline.hasTimeLeft() do
      last = scala.util.Try(check).toOption.flatten
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$what did not happen within $within"))

  private def database(k: AnkkaTestKit): Database = Database()(using k.service.system)

  private def bytes(
      table: String,
      column: String,
      where: String,
      k: => AnkkaTestKit = kit
  ): Vector[Array[Byte]] =
    Await.result(
      database(k).query(SqlFragment.raw(s"SELECT $column FROM $table WHERE $where"))(row =>
        row.get(0) match
          case b: java.nio.ByteBuffer =>
            val out = new Array[Byte](b.remaining)
            b.get(out)
            out
          case b: Array[Byte] => b
          case other          => String.valueOf(other).getBytes(UTF_8)
      ),
      30.seconds
    )

  private val Field =
    """"%s":(\{"\$personal":1,"project":"[^"]*","subject":"[^"]*"(?:,"c":"[^"]*")?\})"""

  /** The envelope of `field` in `text`, as written. */
  private def envelopeOf(text: String, field: String): Option[String] =
    Field
      .format(java.util.regex.Pattern.quote(field).stripPrefix("\\Q").stripSuffix("\\E"))
      .r
      .findFirstMatchIn(text)
      .map(_.group(1))

  private final case class Envelope(project: String, subject: String, c: Option[String])
  private val EnvelopeParts =
    """\{"\$personal":1,"project":"([^"]*)","subject":"([^"]*)"(?:,"c":"([^"]*)")?\}""".r

  private def parse(envelope: String): Envelope = envelope match
    case EnvelopeParts(p, s, c) => Envelope(p, s, Option(c))
    case other                  => fail(s"not a personal envelope: $other")

  /**
   * Asserts `envelope` is sealed under `subject`'s key in the keyring, and opens under `manifest`.
   */
  private def sealedUnder(envelope: String, subject: String, manifest: String): String =
    val e = parse(envelope)
    assertEquals(e.subject, subject)
    val c = e.c.getOrElse(fail(s"the envelope holds no value: $envelope"))
    keyOf(e.project, subject) match
      case KeyAnswer.Key(key) =>
        val opened = PersonalCipher.open(
          key,
          Base64.getDecoder.decode(c),
          PersonalCipher.associatedData(subject, e.project, manifest)
        )
        new String(
          opened.getOrElse(fail(s"$envelope does not open under the key of $subject")),
          UTF_8
        )
      case other => fail(s"the keyring has no key for $subject: $other")

  private def contact(id: String)           = kit.componentClient.forKeyValueEntity(EntityId(id))
  private def idOf(subject: String): String = subject.stripPrefix("player/")

  private def register(k: AnkkaTestKit, id: String, email: String): Unit =
    assertEquals(
      k.componentClient
        .forEventSourcedEntity(EntityId(id))
        .call(PlayerEntity.register)
        .invoke(RegisterPlayer(email, "Ada", LocalDate.parse("1990-04-01"), "EUR")),
      Done
    )

  private def journalText(id: String): String =
    bytes("event_journal", "event_payload", s"persistence_id = 'player|$id' ORDER BY seq_nr")
      .map(b => new String(b, UTF_8))
      .mkString("\n")

  // ── personal-fields.feature ────────────────────────────────────────────────

  Given("a service {string} in the project {string}") { (service: String, in: String) =>
    assertEquals(service, "players")
    project = in
    startKit(): Unit
  }

  Given(
    "the event {string} of {string} has the fields {string}, {string} and {string} marked as personal fields of the data subject {string}"
  ) { (event: String, _: String, a: String, b: String, c: String, _: String) =>
    assertEquals(event, "PlayerRegistered")
    assertEquals(Set(a, b, c), Set("email", "name", "dateOfBirth"))
  }

  Given(
    "the event {string} of {string} has the fields {string} and {string} that are not personal fields"
  ) { (event: String, _: String, a: String, b: String) =>
    assertEquals(event, "PlayerRegistered")
    assertEquals(Set(a, b), Set("currency", "registeredAt"))
  }

  private var written: Option[(String, String)] = None

  When("an entity of {string} records a {string} with the email {string}") {
    (_: String, _: String, email: String) =>
      register(kit, "8c1f", email)
      written = Some("8c1f" -> email)
  }

  Then(
    "the journal of {string} holds the field {string} of that event encrypted under the subject key of {string}"
  ) { (_: String, field: String, subject: String) =>
    val text     = journalText(idOf(subject))
    val envelope = envelopeOf(text, field).getOrElse(fail(s"no envelope of '$field' in $text"))
    val opened   = sealedUnder(envelope, subject, "player-event")
    assertEquals(opened, "\"" + written.map(_._2).getOrElse("") + "\"")
  }

  Then("the journal holds the data subject {string} readable beside it") { (subject: String) =>
    assert(journalText(idOf(subject)).contains(s"\"subject\":\"$subject\""))
  }

  Then("the journal holds the fields {string} and {string} of that event as they were written") {
    (a: String, b: String) =>
      val text = journalText("8c1f")
      assert(text.contains(s"\"$a\":\"EUR\""), text)
      assert(text.contains(s"\"$b\":\"2026-11-04T09:00:00Z\""), text)
  }

  Then("nothing in the database of {string} reads as {string}") { (_: String, value: String) =>
    kit.assertNoPersonalValue(value)
  }

  Given("an entity of {string} whose state has the personal field {string} of {string}") {
    (_: String, _: String, subject: String) =>
      register(kit, idOf(subject), "ada@example.com")
      written = Some(idOf(subject) -> "ada@example.com")
  }

  When("the entity's snapshot is kept") { () =>
    // The player keeps a snapshot after every event.
    val id = written.map(_._1).getOrElse(fail("no entity"))
    eventually("the snapshot is kept")(
      bytes("snapshot", "snapshot", s"persistence_id = 'player|$id'").headOption
    ): Unit
  }

  Then("the snapshot holds the field {string} encrypted under the subject key of {string}") {
    (field: String, subject: String) =>
      val text = bytes("snapshot", "snapshot", s"persistence_id = 'player|${idOf(subject)}'")
        .map(new String(_, UTF_8))
        .mkString
      val envelope = envelopeOf(text, field).getOrElse(fail(s"no envelope of '$field' in $text"))
      assertEquals(sealedUnder(envelope, subject, "player"), "\"ada@example.com\"")
      kit.assertNoPersonalValue("ada@example.com")
  }

  Given("a key value entity of {string} whose state has the personal field {string} of {string}") {
    (_: String, _: String, subject: String) =>
      written = Some(idOf(subject) -> "ada@example.com")
  }

  When("the key value entity's state is changed") { () =>
    val (id, email) = written.getOrElse(fail("no entity"))
    assertEquals(contact(id).call(PlayerContactEntity.setEmail).invoke(email), Done)
  }

  Then(
    "the database of {string} holds the field {string} of that state encrypted under the subject key of {string}"
  ) { (_: String, field: String, subject: String) =>
    val text =
      bytes("durable_state", "state_payload", s"persistence_id = 'player-contact|${idOf(subject)}'")
        .map(new String(_, UTF_8))
        .mkString
    val envelope = envelopeOf(text, field).getOrElse(fail(s"no envelope of '$field' in $text"))
    assertEquals(sealedUnder(envelope, subject, "player-contact"), "\"ada@example.com\"")
    assertEquals(contact(idOf(subject)).call(PlayerContactEntity.email).invoke(), "ada@example.com")
    kit.assertNoPersonalValue("ada@example.com")
  }

  Given(
    "a view {string} of {string} that reads {string} into a row with the personal field {string}"
  ) { (view: String, _: String, _: String, _: String) =>
    assertEquals(view, "profiles")
  }

  When("{string} writes the row for {string}") { (_: String, subject: String) =>
    register(kit, idOf(subject), "ada@example.com")
    written = Some(idOf(subject) -> "ada@example.com")
    eventually("the row is written")(
      kit.service.viewClient.forView(Profiles).get(idOf(subject))
    ): Unit
  }

  Then(
    "the table of {string} holds the field {string} of that row encrypted under the subject key of {string}"
  ) { (_: String, field: String, subject: String) =>
    val text = bytes(Profiles.table, "payload", s"row_key = '${idOf(subject)}'")
      .map(new String(_, UTF_8))
      .mkString
    val envelope =
      envelopeOf(text.replace(" ", ""), field).getOrElse(fail(s"no envelope of '$field' in $text"))
    assertEquals(sealedUnder(envelope, subject, "profile-row"), "\"ada@example.com\"")
  }

  Then("a read of the row by its row key is told the email {string}") { (email: String) =>
    val id  = written.map(_._1).getOrElse(fail("no row"))
    val row = kit.service.viewClient.forView(Profiles).get(id).getOrElse(fail("no row"))
    assertEquals(row.email.orElse("erased"), email)
  }

  Then("a declared query of {string} that reads the row is told the email {string}") {
    (_: String, email: String) =>
      val id   = written.map(_._1).getOrElse(fail("no row"))
      val rows = kit.service.viewClient.forView(Profiles).ask(Profiles.byPlayer, "player_id" -> id)
      assertEquals(rows.map(_.email.orElse("erased")), Vector(email))
      kit.assertNoPersonalValue(email)
  }

  Given("a consumer of {string} that publishes {string} to the topic {string}") {
    (_: String, _: String, topic: String) => assertEquals(topic, "players")
  }

  Given("a consumer {string} in the project {string} that reads the topic {string}") {
    (consumer: String, _: String, _: String) => assertEquals(consumer, "engagement")
  }

  When("{string} publishes a {string} with the email {string}") {
    (_: String, _: String, email: String) =>
      register(kit, "8c1f", email)
      written = Some("8c1f" -> email)
      eventually("the message is published")(broker.publishedTo("players").headOption): Unit
  }

  Then(
    "the message on the broker holds the field {string} encrypted under the subject key of {string}"
  ) { (field: String, subject: String) =>
    val text     = broker.publishedTo("players").map(_.text).mkString
    val envelope = envelopeOf(text, field).getOrElse(fail(s"no envelope of '$field' in $text"))
    assertEquals(sealedUnder(envelope, subject, "player-event"), "\"ada@example.com\"")
    kit.assertNoPersonalValueIn(broker)("ada@example.com")
  }

  Then("the handler of {string} is handed the email {string}") { (_: String, email: String) =>
    val handed = eventually("engagement is handed the registration")(
      Engagement.handed.asScala.headOption
    )
    assertEquals(handed._2.orElse("erased"), email)
  }

  Given("the keyring holds no subject key for {string}") { (subject: String) =>
    assert(!holdsKey(project, subject))
  }

  Given("two instances of {string}") { (_: String) =>
    // A second service of the same project over the same keyring: what a second instance is to it.
    startKit(): Unit
  }

  When("one instance records an event with a personal field of {string}") { (subject: String) =>
    register(kits(0), idOf(subject), "first@example.com")
  }

  Then("the keyring holds one subject key for {string}, made with that write") {
    (subject: String) =>
      assert(holdsKey(project, subject))
      assertEquals(keysMade(project, subject), 1)
  }

  Then(
    "an event with a personal field of {string} that the other instance records is encrypted under the same subject key"
  ) { (subject: String) =>
    val id = idOf(subject)
    register(kits(1), id, "second@example.com")
    val text = bytes("event_journal", "event_payload", s"persistence_id = 'player|$id'", kits(1))
      .map(new String(_, UTF_8))
      .mkString
    val envelope = envelopeOf(text, "email").getOrElse(fail(s"no envelope in $text"))
    // It opens under the one key the keyring holds for the data subject, and no second was made.
    assertEquals(sealedUnder(envelope, subject, "player-event"), "\"second@example.com\"")
    assertEquals(keysMade(project, subject), 1)
  }

  // The outline of where no service runs. Neither the command line nor a test with no test kit
  // starts a service, so nothing installs a keyring there: the one it holds refuses every personal
  // field. Bound here on this thread, since other scenarios' services share the JVM.
  private var refusal: Option[Throwable] = None

  Given("the routes of {string} listed by the command line")((_: String) => stopKits())

  Given("a test of a component of {string} with no test kit")((_: String) => stopKits())

  When("a personal field is written") { () =>
    val event: PlayerEvent = EmailChanged(Personal(DataSubject("player/8c1f"), "ada@example.com"))
    refusal = scala.util
      .Try(
        Keyring.binding(Keyring.unavailable)(PlayerEntity.eventSerializer.toBytes(event))
      )
      .failed
      .toOption
  }

  Then("the write is refused as unavailable") { () =>
    val e = refusal.flatMap(CommandError.from).getOrElse(fail(s"nothing was refused: $refusal"))
    assertEquals(e.code, ErrorCode.Unavailable)
  }

  Then("the refusal names the keyring") { () =>
    assert(refusal.exists(_.getMessage.contains("keyring")), String.valueOf(refusal))
  }

  Then("nothing is written") { () =>
    assert(refusal.exists(_.isInstanceOf[KeyringUnavailable]), String.valueOf(refusal))
  }

  Given("an installation that runs no keyring") { () =>
    erasureOn = false
  }

  When("a member applies a service whose descriptor declares that it holds personal fields") { () =>
    applied = apply("players", personal = true)
  }

  Then("the service is refused") { () =>
    assertEquals(applied._1, 400, applied._2)
    // The same service declaring none is accepted: it is the personal fields that were refused.
    val plain = apply("players", personal = false)
    assert(plain._1 < 300, plain.toString)
  }

  Then("the refusal says the installation's erasure is off") { () =>
    assert(applied._2.contains("erasure is off"), applied._2)
    assert(applied._2.contains("ANKKA_ERASURE"), applied._2)
  }

  // ── edge.feature ───────────────────────────────────────────────────────────

  private val http                  = java.net.http.HttpClient.newHttpClient()
  private var answer: (Int, String) = (0, "")

  private def call(method: String, path: String, body: Option[String] = None): (Int, String) =
    val port    = server.flatMap(_.boundPort).getOrElse(fail("the endpoint is not bound"))
    val builder =
      java.net.http.HttpRequest.newBuilder(java.net.URI.create(s"http://127.0.0.1:$port$path"))
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
        builder.method(method, java.net.http.HttpRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, java.net.http.HttpRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  Given(
    "a service {string} whose event {string} has the field {string} marked as a personal field of the data subject {string}"
  ) { (service: String, event: String, field: String, subject: String) =>
    assertEquals(
      (service, event, field, subject),
      ("players", "PlayerRegistered", "email", "player/8c1f")
    )
    startKit(): Unit
  }

  Given("an endpoint of {string} that answers with the {string} of an entity") {
    (_: String, _: String) => assert(server.nonEmpty)
  }

  Given("an entity of {string} recorded a {string} with the email {string}") {
    (_: String, _: String, email: String) =>
      register(kit, "8c1f", email)
      written = Some("8c1f" -> email)
  }

  Given("an erasure of {string} in the project of {string} is applied") {
    (subject: String, _: String) =>
      kit.erase(DataSubject(subject)): Unit
      // What an entity already holds in memory is the erasure duties' (story 2); recovered from
      // its journal, as here, it reads the field as erased.
      kit.restartService()
  }

  When("a caller the endpoint allows asks for the entity") { () =>
    answer = call("GET", "/players/8c1f")
    assertEquals(answer._1, 200, answer._2)
  }

  Then(
    "the answer holds the field {string} as the value {string} with the data subject {string} beside it"
  ) { (field: String, value: String, subject: String) =>
    assert(
      answer._2.contains(s""""$field":{"subject":"$subject","value":"$value"}"""),
      answer._2
    )
  }

  Then("the answer holds no personal envelope") { () =>
    assert(!answer._2.contains("$personal"), answer._2)
  }

  Then("the answer holds the field {string} as the data subject {string} alone") {
    (field: String, subject: String) =>
      assert(answer._2.contains(s""""$field":{"subject":"$subject"}"""), answer._2)
  }

  When(
    "a caller the endpoint allows sends a {string} whose field {string} is the value {string} with the data subject {string} beside it"
  ) { (_: String, field: String, value: String, subject: String) =>
    val edge = s"""{"subject":"$subject","value":"$value"}"""
    val body =
      s"""{"playerId":"8c1f","$field":$edge,"name":{"subject":"$subject","value":"Ada"},""" +
        s""""dateOfBirth":{"subject":"$subject","value":"1990-04-01"},"currency":"EUR",""" +
        """"registeredAt":"2026-11-04T09:00:00Z"}"""
    answer = call("POST", "/players/8c1f", Some(body))
    assertEquals(answer._1, 200, answer._2)
    written = Some("8c1f" -> value)
  }

  Then(
    "the entity of {string} records the {string} with the email {string} as a personal field of {string}"
  ) { (_: String, _: String, email: String, subject: String) =>
    val text     = journalText(idOf(subject))
    val envelope = envelopeOf(text, "email").getOrElse(fail(s"no envelope of 'email' in $text"))
    assertEquals(sealedUnder(envelope, subject, "player-event"), "\"" + email + "\"")
    kit.assertNoPersonalValue(email)
  }

  // ── asking.feature ─────────────────────────────────────────────────────────

  /** A project of the feature as the control plane knows it in this scenario. */
  private def planeOf(name: String): String =
    if name == project then planeProject else s"$name-$scenarios"

  private var lastAsk: (Int, String) = (0, "")

  Given("a project {string}") { (name: String) =>
    harness.project(planeOf(name), "acme", "ada")
  }

  private def grantErasure(grantor: String, service: String, of: String): Unit =
    val (status, body) = harness.send(
      "POST",
      s"/projects/${planeOf(grantor)}/grants",
      Some(s"""{"grantee":"service:${planeOf(of)}/$service","target":{"kind":"erasure"}}"""),
      as = "ada"
    )
    assert(status / 100 == 2 || status == 409, s"$status $body")

  Given("{string} grants {string} the right to ask for an erasure") {
    (grantor: String, service: String) => grantErasure(grantor, service, project)
  }

  Given("{string} grants {string} of {string} the right to ask for an erasure") {
    (grantor: String, service: String, of: String) => grantErasure(grantor, service, of)
  }

  Given("{string} grants {string} of {string} nothing") { (_: String, _: String, _: String) =>
    ()
  }

  private def askAs(
      service: String,
      subject: String,
      in: String,
      correlation: Option[String] = None
  ): (Int, String) =
    harness.sendAsService(
      "POST",
      s"/projects/${planeOf(in)}/erasures",
      Some(
        s"""{"subject":"$subject"${correlation.fold("")(c => s""","correlation":"$c"""")}}"""
      ),
      planeOf(project),
      service
    )

  When("{string} asks for an erasure request for {string} in {string}") {
    (service: String, subject: String, in: String) =>
      lastAsk = askAs(service, subject, in)
      if lastAsk._1 / 100 == 2 then settled(in, lastAsk._2)
  }

  /**
   * Waits for an accepted ask to be applied: applied later, it would reach the next scenario's
   * keyring and erase a data subject that scenario records.
   */
  private def settled(in: String, answer: String): Unit =
    val id = com.github.plokhotnyuk.jsoniter_scala.core
      .readFromString(answer)(using
        com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.erasureRequestViewCodec
      )
      .id
    eventually(s"erasure request $id applied")(
      Some(harness.send("GET", s"/projects/${planeOf(in)}/erasures/$id", as = "ada")._2)
        .filter(_.contains(""""state":"applied""""))
    ): Unit

  private def askedView: com.thinkmorestupidless.ankka.controlplane.api.ErasureRequestView =
    com.github.plokhotnyuk.jsoniter_scala.core.readFromString(lastAsk._2)(using
      com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.erasureRequestViewCodec
    )

  Then("the erasure request is accepted") { () =>
    assert(lastAsk._1 == 201 || lastAsk._1 == 200, s"${lastAsk._1} ${lastAsk._2}")
  }

  Then("it names the service {string} as who asked for it") { (service: String) =>
    assertEquals(askedView.askedBy, Some(s"ankka://${planeOf(project)}/$service"))
  }

  Then("it names the service {string} of {string} as who asked for it") {
    (service: String, of: String) =>
      assertEquals(askedView.askedBy, Some(s"ankka://${planeOf(of)}/$service"))
  }

  Then("{string} is refused") { (_: String) =>
    assertEquals(lastAsk._1, 403, lastAsk._2)
  }

  Then("the refusal is recorded in the history of {string}") { (in: String) =>
    val (status, body) = harness.send("GET", s"/projects/${planeOf(in)}/history", as = "ada")
    assertEquals(status, 200, body)
    assert(body.contains(""""kind":"erasure-refused""""), body)
    assert(body.contains(s"ankka://${planeOf(project)}/"), body)
  }

  /** A correlation id of this scenario: the requests outlive it, and an outline runs twice. */
  private def correlationOf(id: String): String = s"$id-$scenarios"

  Given(
    "{string} has asked for an erasure request for {string} in {string} with the correlation id {string}"
  ) { (service: String, subject: String, in: String, correlation: String) =>
    grantErasure(in, service, project)
    val (status, body) = askAs(service, subject, in, Some(correlationOf(correlation)))
    assert(status == 201, s"$status $body")
    settled(in, body)
  }

  private var listed: Vector[com.thinkmorestupidless.ankka.controlplane.api.ErasureRequestSummary] =
    Vector.empty

  When("a member lists the erasure requests of {string} with the correlation id {string}") {
    (in: String, correlation: String) =>
      // The listing is a view: retried until both requests are in it.
      listed = eventually(s"both requests listed from $in") {
        val (status, body) =
          harness.send("GET", s"/erasures?correlation=${correlationOf(correlation)}", as = "ada")
        assertEquals(status, 200, body)
        Some(
          com.github.plokhotnyuk.jsoniter_scala.core.readFromString(body)(using
            com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.erasureSummariesCodec
          )
        ).filter(_.size >= 2)
      }
      assert(listed.exists(_.project.contains(planeOf(in))), s"$in is not among $listed")
  }

  Then("both erasure requests are listed, each with where it stands") { () =>
    assertEquals(listed.size, 2, listed.toString)
    assertEquals(listed.flatMap(_.project).toSet, Set(planeOf(project), planeOf("payments")))
    assert(listed.forall(r => ErasureState.parse(r.state).isDefined), listed.toString)
  }

  // ── places.feature: the command line and the platform's MCP server ─────────

  /** A scratch config file, so nothing the CLI resolves reads a developer's own. */
  private lazy val cliConfig: Unit =
    val file = java.nio.file.Files.createTempFile("ankka-erasure-cli", ".json")
    java.nio.file.Files.delete(file)
    sys.props("ankka.config") = file.toString

  /** `ankka <args>` as `person`, in this scenario's project: the exit code and what it printed. */
  private def cli(person: String, stdin: String, args: String*): (Int, String) =
    cliConfig
    val out  = java.io.ByteArrayOutputStream()
    val err  = java.io.ByteArrayOutputStream()
    val outs = java.io.PrintStream(out, true, UTF_8)
    val errs = java.io.PrintStream(err, true, UTF_8)
    val all  =
      args ++ Seq("--url", harness.url, "--token", harness.tokenOf(person), "-p", planeProject)
    val code = Console.withIn(java.io.StringReader(stdin))(
      Console.withOut(outs)(
        Console.withErr(errs)(com.thinkmorestupidless.ankka.cli.Main.run(all, outs, errs))
      )
    )
    (code, if code == 0 then out.toString(UTF_8) else err.toString(UTF_8) + out.toString(UTF_8))

  /** One tool of `ankka mcp`, called as `person`: whether it was an error, and its text. */
  private def mcp(person: String, tool: String, arguments: String): (Boolean, String) =
    val init =
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"suite","version":"1"}}}"""
    val call =
      s"""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""
    val (code, out) = cli(person, s"$init\n$call\n", "mcp")
    assertEquals(code, 0, out)
    val reply = out.linesIterator.find(_.contains(""""id":2""")).getOrElse(fail(s"no reply: $out"))
    val error = reply.contains(""""isError":true""")
    val text  = """"text":"((?:[^"\\]|\\.)*)"""".r
      .findFirstMatchIn(reply)
      .map(m => StringContext.processEscapes(m.group(1)))
      .getOrElse(fail(s"no text: $reply"))
    (error, text)

  private def placeAnswer(ok: Boolean, text: String): (Int, String) =
    (if ok then 200 else 400, text)

  When(
    "a member of {string} asks for an erasure request for {string} in {string} through the command line"
  ) { (_: String, subject: String, _: String) =>
    filer = "bob"
    val (code, out) = cli("bob", "", "projects", "erasures", "file", subject, "-o", "json")
    lastAsk = placeAnswer(code == 0, out)
    RequestId.findFirstMatchIn(out).foreach(m => asked += subject -> m.group(1))
    if code == 0 then settled(project, out)
  }

  When(
    "a member of {string} asks for an erasure request for {string} in {string} through the platform's MCP server"
  ) { (_: String, subject: String, _: String) =>
    filer = "bob"
    val (error, text) = mcp("bob", "erasure_file", s"""{"subject":"$subject"}""")
    lastAsk = placeAnswer(!error, text)
    """"id": "([0-9A-Z]{26})"""".r
      .findFirstMatchIn(text)
      .foreach(m => asked += subject -> m.group(1))
    if !error then settled(project, text)
  }

  Then("it names the member as who asked for it") { () =>
    val (status, me) = harness.send("GET", "/auth/whoami", as = "bob")
    assertEquals(status, 200, me)
    val by = askedView.askedBy.getOrElse(fail(s"no one asked: ${lastAsk._2}"))
    assert(me.contains(s""""$by""""), s"$by is not bob: $me")
  }

  Given("an applied erasure request for {string} in {string}") { (subject: String, in: String) =>
    assertEquals(in, project)
    val (status, body) = askErasure(subject)
    assertEquals(status, 201, body)
    assert(untilFinal(subject).contains(""""state":"applied""""))
  }

  Given("a held erasure request for {string} in {string}") { (subject: String, in: String) =>
    assertEquals(in, project)
    held(subject, "2099-01-31", None)
  }

  private var fetched: String = ""

  When("a member of {string} fetches its erasure certificate through the command line") {
    (_: String) =>
      val rid         = requestOf(asked.keys.head)
      val (code, out) = cli("bob", "", "projects", "erasures", "certificate", rid, "-o", "json")
      assertEquals(code, 0, out)
      fetched = out
  }

  When("a member of {string} fetches its erasure certificate through the platform's MCP server") {
    (_: String) =>
      val (error, text) =
        mcp("bob", "erasure_certificate", s"""{"request":"${requestOf(asked.keys.head)}"}""")
      assert(!error, text)
      fetched = text
  }

  Then("the member is given the erasure certificate of the erasure request") { () =>
    val subject = asked.keys.head
    // The certificate names its request by its full id, `<project>/<request id>`.
    assert(
      """"request":\s*"([^"]+)"""".r
        .findFirstMatchIn(fetched)
        .exists(_.group(1).endsWith(requestOf(subject))),
      fetched
    )
    assert(fetched.replace(" ", "").contains(s""""subject":"$subject""""), fetched)
    assert(fetched.contains("keyDestroyedAt"), fetched)
  }

  private def overrideByMcp(person: String, reason: String): Unit =
    val (error, text) = mcp(
      person,
      "erasure_override",
      s"""{"request":"${requestOf(asked.keys.head)}","reason":"$reason"}"""
    )
    answered =
      (if error then if text.contains("owner role required") then 403 else 500 else 200, text)

  When(
    "an owner of {string} overrides its hold with the reason {string} through the platform's MCP server"
  )((_: String, reason: String) => overrideByMcp("ada", reason))

  When(
    "a member who is not an owner of {string} overrides its hold with the reason {string} through the platform's MCP server"
  )((_: String, reason: String) => overrideByMcp("bob", reason))

  Then("the override is refused") { () =>
    assertEquals(answered._1, 403, answered._2)
  }

  // ── erasing.feature ────────────────────────────────────────────────────────

  /** The request asked for each data subject in this scenario: its id within the project. */
  private var asked: Map[String, String] = Map.empty

  /**
   * The project as the control plane knows it in this scenario: one of its own, since the control
   * plane outlives the scenarios and a request applied in one would answer the next one's ask.
   */
  private var planeProject: String = ""
  private var scenarios: Int       = 0

  private def emailOf(subject: String): String = s"${idOf(subject)}@example.com"

  private val RequestId = """"id":"([0-9A-Z]{26})"""".r

  private def application: ErasureApplication =
    eventually("the control plane's erasure plane")(plane.application)

  private def requestOf(subject: String): String =
    asked.getOrElse(subject, fail(s"no erasure request was asked for $subject"))

  private def readRequest(subject: String): String =
    val (status, body) =
      harness.send("GET", s"/projects/$planeProject/erasures/${requestOf(subject)}", as = "ada")
    assertEquals(status, 200, body)
    body

  /** A member asks; the answer's request id is kept for `subject`. */
  private def askErasure(
      subject: String,
      notBefore: Option[String] = None,
      reason: Option[String] = None,
      as: String = "ada"
  ): (Int, String) =
    filer = as
    val fields = Vector(s""""subject":"$subject"""") ++
      notBefore.map(d => s""""notBefore":"${d}T00:00:00Z"""") ++
      reason.map(r => s""""reason":"$r"""")
    val answer = harness.send(
      "POST",
      s"/projects/$planeProject/erasures",
      Some(fields.mkString("{", ",", "}")),
      as
    )
    RequestId.findFirstMatchIn(answer._2).foreach(m => asked += subject -> m.group(1))
    answer

  /**
   * Carries the request for `subject` until it is final: applied, and every service's completion
   * read from the keyring, as the control plane's sweep does.
   */
  private def untilFinal(subject: String): String =
    val id   = s"$planeProject/${requestOf(subject)}"
    var seen = ""
    try
      eventually("the erasure request is final", 60.seconds) {
        val now = Await.result(application.apply(id), 30.seconds)
        seen = now.toString
        Option.when(now.finalAt.isDefined)(readRequest(subject))
      }
    catch case e: munit.FailException => fail(s"${e.getMessage}; last read: $seen")

  private def erased(subject: String): String =
    val (status, body) = askErasure(subject)
    assertEquals(status, 201, body)
    untilFinal(subject)

  private def emailOf(k: AnkkaTestKit, subject: String): String =
    k.componentClient
      .forEventSourcedEntity(EntityId(idOf(subject)))
      .call(PlayerEntity.email)
      .invoke()

  private def currencyOf(k: AnkkaTestKit, subject: String): String =
    k.componentClient
      .forEventSourcedEntity(EntityId(idOf(subject)))
      .call(PlayerEntity.currency)
      .invoke()

  private def rowOf(k: AnkkaTestKit, subject: String): Option[ProfileRow] =
    k.service.viewClient.forView(Profiles).get(idOf(subject))

  /** Every registration of `subject` on the topic, read as each service's consumer reads it. */
  private def deliveredTo(k: AnkkaTestKit, subject: String): Vector[Personal[String]] =
    val serializer = Codecs.serializer[PlayerEvent]("player-event")
    broker
      .publishedTo("players")
      .toVector
      .map(d =>
        com.thinkmorestupidless.ankka.runtime.keyring.ServiceKeyring
          .binding(k.service.system)(serializer.fromBytes(d.message.payload))
      )
      .collect { case r: PlayerRegistered if r.playerId == idOf(subject) => r.email }

  private def journalRows(k: AnkkaTestKit, subject: String): Int =
    bytes("event_journal", "event_payload", s"persistence_id = 'player|${idOf(subject)}'", k).size

  Given("a project {string} with the services {string}, {string} and {string}") {
    (in: String, a: String, b: String, c: String) =>
      project = in
      erasureOn = true
      scenarios += 1
      planeProject = s"$in-$scenarios"
      harness.project(planeProject, "acme", "ada")
      Seq(a, b, c).foreach(startKit(_): Unit)
  }

  Given(
    "each of them has recorded personal fields of the data subject {string} and of the data subject {string}"
  )((first: String, second: String) => recordPersonal(first, second))

  private def recordPersonal(subjects: String*): Unit =
    for k <- kits; s <- subjects do register(k, idOf(s), emailOf(s))
    subjects.foreach(s => objectsHeld.put(s, true))
    for k <- kits; s <- subjects do
      eventually(s"the profile of $s")(rowOf(k, s)): Unit
      eventually(s"the registration of $s on the topic")(deliveredTo(k, s).headOption): Unit

  // ── restores.feature, in process ───────────────────────────────────────────

  Given("a project {string} with the service {string}") { (in: String, service: String) =>
    project = in
    erasureOn = true
    scenarios += 1
    planeProject = s"$in-$scenarios"
    logBucket.refuse = false
    failing = None
    harness.project(planeProject, "acme", "ada")
    startKit(service): Unit
    recordPersonal("player/8c1f", "player/9d2e")
  }

  Given("the keyring has not been restored")(() => ())

  Given("the database of {string} has been restored to a point before the erasure") { (_: String) =>
    // An erasure rewrites nothing, so the journal holds what a restore to before it gives back:
    // every event of the erased data subject, its personal fields sealed under the destroyed key.
    val k    = kitNamed("players")
    val text = bytes("event_journal", "event_payload", "persistence_id = 'player|8c1f'", k)
      .map(new String(_, UTF_8))
      .mkString
    assert(envelopeOf(text, "email").flatMap(e => parse(e).c).isDefined, text)
  }

  private var replayed: Option[com.thinkmorestupidless.ankka.keyring.ErasureLogReplay.Report] =
    None
  private var behindCopy = Option.empty[(String, Long)]

  Given("one copy of the erasure log is behind the other") { () =>
    val latest = logBucket.entries().map(_.seq).maxOption.getOrElse(fail("the log is empty"))
    behindCopy = Some("bucket" -> latest)
  }

  When("the keyring applies the erasure log") { () =>
    val (copy, missing) = behindCopy.getOrElse(fail("no copy is behind"))
    val behind          = new com.thinkmorestupidless.ankka.keyring.LogCopy:
      val name             = copy
      def after(seq: Long) =
        logBucket
          .entries()
          .filter(e => e.seq > seq && e.seq != missing)
          .map(e =>
            com.thinkmorestupidless.ankka.keyring
              .LogEntry(e.seq, e.project, e.subject, e.rid, e.appliedAt)
          )
    given org.apache.pekko.actor.typed.ActorSystem[?] = kits(0).service.system
    given scala.concurrent.ExecutionContext           = kits(0).service.system.executionContext
    val database                                      = Database()
    Await.result(
      database.execute(SqlFragment.raw("DROP SCHEMA IF EXISTS keyring CASCADE")),
      30.seconds
    ): Unit
    val store = com.thinkmorestupidless.ankka.keyring.KeyStore(database)
    Await.result(store.ensure(), 30.seconds)
    val replay =
      com.thinkmorestupidless.ankka.keyring.ErasureLogReplay(store, Vector(planeLog, behind))
    replayed = Some(Await.result(replay.run(), 60.seconds))
    keyStoreOfReplay = Some(store)
  }

  private var keyStoreOfReplay = Option.empty[com.thinkmorestupidless.ankka.keyring.KeyStore]

  Then("it applies every entry that either copy holds") { () =>
    val store = keyStoreOfReplay.getOrElse(fail("nothing was replayed"))
    val seqs  = (logBucket.entries().map(_.seq) ++ planeLog.after(0).map(_.seq)).distinct
    assert(seqs.nonEmpty)
    seqs.foreach(seq =>
      assert(Await.result(store.applied(seq), 30.seconds).isDefined, s"entry $seq")
    )
    assert(replayed.exists(_.finished), replayed.toString)
  }

  Then("it reports which copy was behind") { () =>
    val (copy, missing) = behindCopy.getOrElse(fail("no copy is behind"))
    assertEquals(
      replayed.map(_.behind.map(b => b.copy -> b.missing)),
      Some(Vector(copy -> Vector(missing)))
    )
  }

  private var failedApply: Option[Throwable] = None
  private var failing: Option[String]        = None

  Given("an erasure request for {string} in {string} whose write to the erasure log fails") {
    (subject: String, in: String) =>
      assertEquals(in, project)
      assert(holdsKey(project, subject), s"$subject has no key to destroy")
      logBucket.refuse = true
      failing = Some(subject)
      val (status, body) = askErasure(subject)
      assertEquals(status, 201, body)
  }

  Then("no subject key is destroyed") { () =>
    val subject = failing.getOrElse(fail("no request was refused its log write"))
    assert(failedApply.isDefined, "the application did not fail")
    assert(holdsKey(project, subject), s"the subject key of $subject was destroyed")
  }

  Then("the erasure request is not applied, and says why") { () =>
    val subject = failing.getOrElse(fail("no request was refused its log write"))
    val reply   = readRequest(subject)
    logBucket.refuse = false
    assert(reply.contains(""""state":"applying""""), reply)
    assert(reply.contains(""""lastFailure":{"reason":""""), reply)
    assert(reply.contains("erasure log"), reply)
  }

  Given("each of them has recorded fields of {string} that are not personal") { (subject: String) =>
    kits.foreach(k => assertEquals(currencyOf(k, subject), "EUR"))
  }

  When(
    "a member asks for an erasure request for {string} in {string} with no not-before date"
  ) { (subject: String, in: String) =>
    assertEquals(in, project)
    val (status, body) = askErasure(subject)
    assertEquals(status, 201, body)
    assert(body.contains(""""state":"applying""""), body)
    untilFinal(subject): Unit
  }

  Then(
    "within 60 seconds every view and every consumer of each service of {string} reads every personal field of {string} as erased"
  ) { (_: String, subject: String) =>
    kits.foreach { k =>
      eventually(s"the profile of $subject redacted", 60.seconds)(
        rowOf(k, subject).filter(_.email.isErased)
      ): Unit
      eventually(s"the consumer reads $subject as erased", 60.seconds)(
        Option.when(deliveredTo(k, subject).forall(_.isErased))(())
      )
    }
  }

  Then("every entity of {string} recovered from then on reads its personal fields as erased") {
    (subject: String) =>
      kits.foreach { k =>
        k.restartService()
        assertEquals(emailOf(k, subject), "erased")
        assertEquals(currencyOf(k, subject), "EUR")
      }
  }

  Then("every service of {string} reads every personal field of {string} as it was written") {
    (_: String, subject: String) =>
      kits.foreach { k =>
        assertEquals(emailOf(k, subject), emailOf(subject))
        assertEquals(rowOf(k, subject).map(_.email.orElse("erased")), Some(emailOf(subject)))
        assert(deliveredTo(k, subject).nonEmpty)
        assert(
          deliveredTo(k, subject).forall(_.orElse("erased") == emailOf(subject)),
          deliveredTo(k, subject).toString
        )
      }
  }

  Given("{string} has been erased in {string}") { (subject: String, in: String) =>
    assertEquals(in, project)
    erased(subject): Unit
  }

  When("an entity of {string} holding events of {string} is recovered") {
    (service: String, subject: String) =>
      kitNamed(service).restartService()
      written = Some(service -> subject)
  }

  private def recovered: (AnkkaTestKit, String) =
    written
      .map((service, subject) => kitNamed(service) -> subject)
      .getOrElse(fail("nothing recovered"))

  Then("the entity is recovered") { () =>
    val (k, subject) = recovered
    assertEquals(currencyOf(k, subject), "EUR")
  }

  Then("its state holds erased in each personal field of {string}") { (subject: String) =>
    val (k, _) = recovered
    assertEquals(emailOf(k, subject), "erased")
    val registration = k.componentClient
      .forEventSourcedEntity(EntityId(idOf(subject)))
      .call(PlayerEntity.registration)
      .invoke()
    assert(registration.email.isErased, registration.toString)
  }

  Then("every field of its state that is not personal is as it was written") { () =>
    val (k, subject) = recovered
    assertEquals(currencyOf(k, subject), "EUR")
  }

  Given("a view of {string} holding rows with personal fields of {string}") {
    (service: String, subject: String) =>
      assert(rowOf(kitNamed(service), subject).nonEmpty)
      written = Some(service -> subject)
  }

  When("the view is declared at a higher version and rebuilt") { () =>
    val (k, _) = recovered
    k.stopService()
    k.startService(descriptors = descriptors.map {
      case v if v.componentId == Profiles.descriptor.componentId =>
        Profiles.descriptor.copy(version = Some(2))
      case d => d
    })
  }

  Then("the rebuild finishes") { () =>
    val (k, subject) = recovered
    eventually("the view rebuilt", 60.seconds)(rowOf(k, subject)): Unit
    // Every row of the source is back, not only the erased data subject's.
    eventually("every row rebuilt", 60.seconds)(rowOf(k, "player/9d2e")): Unit
  }

  Then("its rows hold erased in each personal field of {string}") { (subject: String) =>
    val (k, _) = recovered
    assert(rowOf(k, subject).exists(_.email.isErased), rowOf(k, subject).toString)
    assertEquals(
      rowOf(k, "player/9d2e").map(_.email.orElse("erased")),
      Some(emailOf("player/9d2e"))
    )
  }

  private var refused: Option[Throwable] = None
  private var rowsBefore: Int            = 0

  When("a command of {string} records an event with a personal field of {string}") {
    (service: String, subject: String) =>
      val k = kitNamed(service)
      written = Some(service -> subject)
      rowsBefore = journalRows(k, subject)
      refused = scala.util
        .Try(
          k.componentClient
            .forEventSourcedEntity(EntityId(idOf(subject)))
            .call(PlayerEntity.changeEmail)
            .invoke("again@example.com")
        )
        .failed
        .toOption
  }

  Then("the command is refused") { () =>
    assert(refused.nonEmpty, "the command was accepted")
  }

  Then("the refusal names {string} as erased") { (subject: String) =>
    assert(refused.exists(_.getMessage.contains(s"'$subject' is erased")), String.valueOf(refused))
  }

  Then("nothing is recorded") { () =>
    val (k, subject) = recovered
    assertEquals(journalRows(k, subject), rowsBefore)
    k.assertNoPersonalValue("again@example.com")
  }

  private var reply: String = ""

  When("a member reads the erasure request for {string} in {string}") {
    (subject: String, in: String) =>
      assertEquals(in, project)
      reply = readRequest(subject)
  }

  // One wording, two uses: restores.feature applies a request whose log write is refused (a When),
  // erasing.feature reads that a request was applied (a Then).
  Then("the erasure request is applied") { () =>
    failing match
      case Some(subject) =>
        failedApply = scala.util
          .Try(Await.result(application.apply(s"$planeProject/${requestOf(subject)}"), 30.seconds))
          .failed
          .toOption
      case None => assert(reply.contains(""""state":"applied""""), reply)
  }

  Then("it says when the subject key was destroyed") { () =>
    assert(reply.contains(""""destroyedAt":""""), reply)
  }

  Then("it names each service of {string} with the time it completed") { (_: String) =>
    names.foreach(name =>
      assert(reply.contains(s""""service":"$name","completedAt":""""), s"$name in $reply")
    )
  }

  Then("it says when the erasure became final") { () =>
    assert(reply.contains(""""finalAt":""""), reply)
  }

  When("a member reads every table of every service of {string}")((_: String) => ())

  Then(
    "no table holds a value from which a personal field of {string} can be recovered without the destroyed subject key"
  ) { (subject: String) =>
    kits.foreach(_.assertNoPersonalValue(emailOf(subject), "1990-04-01"))
    kit.assertNoPersonalValueIn(broker)(emailOf(subject))
    // What the tables hold of the data subject is sealed under a key no one can fetch any more.
    val sealedUnder = kits.flatMap(k =>
      bytes("event_journal", "event_payload", s"persistence_id = 'player|${idOf(subject)}'", k)
        .map(new String(_, UTF_8))
    )
    assert(sealedUnder.exists(_.contains(s""""subject":"$subject","c":"""")), sealedUnder.toString)
    assert(
      !keyOf(project, subject).isInstanceOf[KeyAnswer.Key],
      "the keyring still answers a key"
    )
  }

  When("a member fetches the erasure certificate for {string} in {string}") {
    (subject: String, in: String) =>
      assertEquals(in, project)
      val (status, body) = harness.send(
        "GET",
        s"/projects/$planeProject/erasures/${requestOf(subject)}/certificate",
        as = "ada"
      )
      assertEquals(status, 200, body)
      reply = body
  }

  Then(
    "the erasure certificate names the erasure request, the data subject {string} and who asked for it"
  ) { (subject: String) =>
    assert(reply.contains(s""""request":"$planeProject/${requestOf(subject)}""""), reply)
    assert(reply.contains(s""""subject":"$subject""""), reply)
    assert(reply.contains(""""askedBy":"ada""""), reply)
  }

  Then("it holds no personal field") { () =>
    assert(!reply.contains("$personal"), reply)
    asked.keys.foreach(subject => assert(!reply.contains(emailOf(subject)), reply))
  }

  Given("{string} has an erasure handler") { (service: String) =>
    assertEquals(service, "players")
  }

  When("an erasure request for {string} in {string} is applied") { (subject: String, in: String) =>
    assertEquals(in, project)
    erased(subject): Unit
    eventually("the handler ran")(handlerRuns.asScala.find(_._1 == subject)): Unit
  }

  Then("it is applied again later") { () =>
    val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
    val first   = requestOf(subject)
    // Later: once the project's listing shows the request applied, which follows the request.
    eventually("the request listed as applied") {
      val (_, list) =
        harness.send("GET", s"/projects/$planeProject/erasures?state=applied", as = "ada")
      Option.when(list.contains(first))(())
    }
    val (status, body) = askErasure(subject)
    assert(status / 100 == 2, s"$status $body")
    assertEquals(requestOf(subject), first, "a second request was made for an applied data subject")
  }

  Then("the erasure handler of {string} ran for {string} each time") {
    (_: String, subject: String) =>
      eventually("the handler ran twice")(
        Option.when(handlerRuns.asScala.count(_._1 == subject) >= 2)(())
      )
  }

  Then("the second run, with nothing of its own to do, completed at once") { () =>
    val runs = handlerRuns.asScala.toVector
    assertEquals(runs.map(_._2).take(2), Vector(1L, 0L))
    val rerun = eventually("a re-run sent")(lastRerun)
    val ack   = eventually("players acknowledged the re-run")(ackOf(rerun, "players"))
    assertEquals(
      ack.handler.map(h => (h.objectsErased, h.finality, h.error)),
      Some((0L, "immediate", None))
    )
  }

  // ── objects.feature ────────────────────────────────────────────────────────

  private def storeOf(
      service: String
  ): com.thinkmorestupidless.ankka.runtime.storage.MemoryObjectStorage =
    kitNamed(service).storage match
      case m: com.thinkmorestupidless.ankka.runtime.storage.MemoryObjectStorage => m
      case other => fail(s"$service keeps its objects in $other, not in memory")

  private def prefixOf(subject: String): String =
    com.thinkmorestupidless.ankka.sdk.ObjectRules.subjectPrefix(DataSubject(subject))

  private def keep(service: String, name: String): Unit =
    storeOf(service).default.putBytes(name, name.getBytes("UTF-8"), "application/octet-stream")

  /** What a member reads of the request for `subject`. */
  private def viewOf(
      subject: String
  ): com.thinkmorestupidless.ankka.controlplane.api.ErasureRequestView =
    com.github.plokhotnyuk.jsoniter_scala.core.readFromString(readRequest(subject))(using
      com.thinkmorestupidless.ankka.controlplane.api.ErasureWire.erasureRequestViewCodec
    )

  /**
   * Applies the request for `subject` until every service has completed it, as the sweep does, and
   * answers the request then. Not `untilFinal`: a request whose handler failed is not final.
   */
  private def appliedToEveryone(
      subject: String
  ): com.thinkmorestupidless.ankka.controlplane.api.ErasureRequestView =
    val id = s"$planeProject/${requestOf(subject)}"
    eventually("every service completed the erasure", 60.seconds) {
      Await.result(application.apply(id), 30.seconds): Unit
      val view = viewOf(subject)
      Option.when(
        names.forall(n => view.completions.exists(c => c.service == n && c.handler.nonEmpty))
      )(view)
    }

  private def completionOf(service: String) =
    val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
    appliedToEveryone(subject).completions
      .find(_.service == service)
      .flatMap(_.handler)
      .getOrElse(fail(s"no completion of $service with its handler's report"))

  Given("a service {string} in the project {string} with a bucket") {
    (service: String, in: String) =>
      project = in
      erasureOn = true
      scenarios += 1
      planeProject = s"$in-$scenarios"
      harness.project(planeProject, "acme", "ada")
      startKit(service): Unit
  }

  Given("a service {string} in the project {string} whose descriptor asks for no bucket") {
    (service: String, in: String) =>
      assertEquals(in, project)
      startKit(
        service,
        com.thinkmorestupidless.ankka.testkit.StorageBackendChoice.Memory(unnamed = false)
      ): Unit
  }

  Given("{string} has an erasure handler that erases the objects of the data subject") {
    (service: String) => kitNamed(service): Unit
  }

  Given(
    "{string} has kept the objects {string} and {string} under the subject prefix of {string}"
  ) { (service: String, a: String, b: String, subject: String) =>
    keep(service, prefixOf(subject) + a)
    keep(service, prefixOf(subject) + b)
  }

  Given("{string} has kept objects under the subject prefix of {string}") {
    (service: String, subject: String) =>
      keep(service, prefixOf(subject) + "passport.pdf")
      keep(service, prefixOf(subject) + "selfie.jpg")
  }

  Given("{string} has kept the object {string} outside any subject prefix") {
    (service: String, name: String) => keep(service, name)
  }

  Given("the object store of the installation keeps every version of an object") { () =>
    names.filter(_ != "players").foreach { service =>
      val store = storeOf(service)
      store.keepsVersions = true
      // A second version of each object kept so far, so a noncurrent one exists to erase.
      store.default.list("").toVector.foreach(e => keep(service, e.name))
    }
  }

  // The window is the store's; on the platform the operator tells a service its cloud bucket's as
  // ANKKA_S3_SOFT_DELETE_DAYS (ObjectStorageRenderingSuite), and GcsCompatibilitySuite erases under
  // a subject prefix of a real bucket that keeps one.
  Given(
    "the object store of the installation keeps every version of an object behind a soft-delete window"
  ) { () =>
    names.filter(_ != "players").foreach { service =>
      val store = storeOf(service)
      store.keepsVersions = true
      store.softDelete = Some(SoftDeleteWindow)
    }
  }

  Given("the object store of the installation keeps one version of an object") { () =>
    names.filter(_ != "players").foreach(service => storeOf(service).keepsVersions = false)
  }

  Given("{string} has since kept the object {string} under the subject prefix of {string}") {
    (service: String, name: String, subject: String) => keep(service, prefixOf(subject) + name)
  }

  When("{string} is erased in {string}") { (subject: String, in: String) =>
    assertEquals(in, project)
    lastErased = Some(subject)
    val (status, body) = askErasure(subject)
    assertEquals(status, 201, body)
    appliedToEveryone(subject): Unit
  }

  When("the erasure request for {string} is applied again") { (subject: String) =>
    // The re-run the sweep sends once its cadence is due; sent here, since an earlier scenario's
    // sweep may have reset the cadence.
    val id      = s"$planeProject/${requestOf(subject)}"
    val current = plane.componentClient
      .getOrElse(fail("the erasure plane has not started"))
      .forEventSourcedEntity(EntityId(id))
      .call(com.thinkmorestupidless.ankka.controlplane.erasure.ErasureRequestEntity.get)
      .invoke()
    Await.result(application.resend(current), 30.seconds)
  }

  Then(
    "the bucket of {string} holds no version of {string} or of {string} under the subject prefix of {string}"
  ) { (service: String, a: String, b: String, subject: String) =>
    Vector(a, b).foreach(name =>
      assertEquals(storeOf(service).versions(None, prefixOf(subject) + name), 0, name)
    )
  }

  Then("the bucket of {string} still holds {string}") { (service: String, name: String) =>
    assert(storeOf(service).default.stat(name).nonEmpty, s"$name is gone")
  }

  Then("the completion of {string} records that {int} objects were erased") {
    (service: String, count: Int) =>
      val report = completionOf(service)
      assertEquals(report.objectsErased, count.toLong, report.toString)
      assertEquals(report.error, None)
  }

  Then(
    "the erasure request says that the erasure of objects becomes final when the soft-delete window has passed"
  ) { () =>
    val subject = lastErased.getOrElse(fail("nothing was erased"))
    val applied = java.time.Instant.now()
    val request = eventually("the request says when objects are final")(
      Option(
        plane.componentClient
          .getOrElse(fail("the erasure plane has not started"))
          .forEventSourcedEntity(EntityId(s"$planeProject/${requestOf(subject)}"))
          .call(com.thinkmorestupidless.ankka.controlplane.erasure.ErasureRequestEntity.get)
          .invoke()
      ).filter(_.objectsFinalAt.nonEmpty)
    )
    val finalAt = request.objectsFinalAt.get
    val window  = java.time.Duration.ofMillis(SoftDeleteWindow.toMillis)
    // Final no sooner than the window after the erasure, and not a minute later.
    assert(!finalAt.isBefore(applied.plus(window).minusSeconds(60)), s"$finalAt, applied $applied")
    assert(!finalAt.isAfter(applied.plus(window).plusSeconds(60)), s"$finalAt, applied $applied")
    names.filter(_ != "players").foreach(service => storeOf(service).softDelete = None)
  }

  Then("the erasure handler of {string} ran again") { (service: String) =>
    eventually("the handler ran again")(
      Option.when(bucketRuns.asScala.count(_._1 == service) >= 2)(())
    )
  }

  Then("the bucket of {string} holds no {string} under the subject prefix of {string}") {
    (service: String, name: String, subject: String) =>
      eventually(s"$name erased")(
        Option.when(storeOf(service).versions(None, prefixOf(subject) + name) == 0)(())
      )
  }

  Then("the erasure handler of {string} is refused") { (service: String) =>
    assert(completionOf(service).error.nonEmpty, completionOf(service).toString)
  }

  Then("the refusal names the missing bucket") { () =>
    val refused = completionOf("ledger").error.getOrElse("")
    assert(refused.contains("no bucket"), refused)
  }

  Then("the completion of {string} says so") { (service: String) =>
    val report = completionOf(service)
    assertEquals((report.objectsErased, report.finality), (0L, "failed"), report.toString)
  }

  // ── holds.feature ──────────────────────────────────────────────────────────

  /** How far this scenario moved the control plane's clock; moved back before the next. */
  private var moved: java.time.Duration = java.time.Duration.ZERO

  /** Who asked for this scenario's request. */
  private var filer: String = "ada"

  private var answered: (Int, String) = (0, "")

  private def personOf(who: String): String = who match
    case "whoever asked for it"                                     => filer
    case "a member" | "the member" | "a member who is not an owner" => "bob"
    case "an owner"                                                 => "ada"
    case other                                                      => fail(s"no one is '$other'")

  /** The control plane's erasure sweep, once, at the control plane's clock (T059). */
  private def sweepOnce(): Unit =
    // The sweep reads the requests' view; a pass before it lists a request would not see it.
    asked.keys.foreach { subject =>
      eventually(s"the list shows the request for $subject") {
        val (_, list) =
          harness.send("GET", s"/projects/$planeProject/erasures?subject=$subject", as = "ada")
        Option.when(list.contains(requestOf(subject)))(())
      }
    }
    Await.result(sweep.runOnce(), 2.minutes): Unit

  private def ridPath(subject: String) = s"/projects/$planeProject/erasures/${requestOf(subject)}"

  private def held(subject: String, date: String, reason: Option[String]): Unit =
    val (status, body) = askErasure(subject, Some(date), reason, as = "cyd")
    assertEquals(status, 201, body)
    assert(body.contains(""""state":"held""""), body)

  /** The project's history once it holds a line of `kind`: the view follows the request. */
  private def historyWith(kind: String): String =
    eventually(s"$kind in the project's history")(
      Some(projectHistory).filter(_.contains(s""""kind":"$kind""""))
    )

  private def projectHistory: String =
    val (status, body) = harness.send("GET", s"/projects/$planeProject/history", as = "ada")
    assertEquals(status, 200, body)
    body

  Given("{string} has recorded personal fields of the data subject {string}") {
    (service: String, subject: String) =>
      eventually(s"the profile of $subject")(rowOf(kitNamed(service), subject)): Unit
  }

  Given(
    "an erasure request for {string} in {string} with the not-before date {string} and the reason {string}"
  ) { (subject: String, in: String, date: String, reason: String) =>
    assertEquals(in, project)
    held(subject, date, Some(reason))
  }

  Given("a held erasure request for {string} in {string} with the not-before date {string}") {
    (subject: String, in: String, date: String) =>
      assertEquals(in, project)
      held(subject, date, None)
  }

  When("the not-before date has not passed") { () =>
    val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
    assert(readRequest(subject).contains(""""notBefore":"""), "no not-before date")
    sweepOnce()
  }

  When("the not-before date passes") { () =>
    val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
    val reply   = readRequest(subject)
    val date    = """"notBefore":"([^"]+)"""".r
      .findFirstMatchIn(reply)
      .map(m => java.time.Instant.parse(m.group(1)))
      .getOrElse(fail(s"no not-before date: $reply"))
    val by = java.time.Duration.between(harness.identity.clock.instant(), date).plusMinutes(1)
    harness.identity.clock.advance(by)
    moved = moved.plus(by)
    sweepOnce()
  }

  Then("the keyring still holds the subject key of {string}") { (subject: String) =>
    assert(holdsKey(project, subject), s"the subject key of $subject was destroyed")
  }

  Then(
    "the erasure request is held, with the not-before date {string} and the reason {string}"
  ) { (date: String, reason: String) =>
    val reply = readRequest(asked.keys.headOption.getOrElse(fail("nothing was asked")))
    assert(reply.contains(""""state":"held""""), reply)
    assert(reply.contains(s""""notBefore":"${date}T00:00:00Z""""), reply)
    assert(reply.contains(s""""reason":"$reason""""), reply)
  }

  Then("within 15 minutes and 60 seconds the erasure request is applied without anyone acting") {
    () =>
      val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
      // The sweep runs every five minutes, well inside the fifteen; nobody asked it to.
      assert(ErasureSweep.Every <= 15.minutes, ErasureSweep.Every.toString)
      val reply = untilFinal(subject)
      assert(reply.contains(""""state":"applied""""), reply)
      assert(reply.contains(""""lapsedAt":"""), reply)
      // The control plane lapsed it: no member is named on the line.
      val lapsed = eventually("the lapse in the project's history")(
        """erasure-hold-lapsed[^}]*""".r.findFirstIn(projectHistory)
      )
      assert(Seq("ada", "bob", "cyd").forall(p => !lapsed.contains(p)), lapsed)
  }

  Then("every service of {string} reads every personal field of {string} as erased") {
    (_: String, subject: String) =>
      kits.foreach { k =>
        k.restartService()
        assertEquals(emailOf(k, subject), "erased")
      }
  }

  for who <- Seq("whoever asked for it", "a member") do
    When(s"$who withdraws it before the not-before date") { () =>
      val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
      answered = harness.send("POST", s"${ridPath(subject)}/withdraw", None, personOf(who))
      withdrawer = personOf(who)
    }

  private var withdrawer = ""

  Then("the erasure request is withdrawn") { () =>
    assertEquals(answered._1, 200, answered._2)
    val reply = readRequest(asked.keys.headOption.getOrElse(fail("nothing was asked")))
    assert(reply.contains(""""state":"withdrawn""""), reply)
  }

  Then("it records who withdrew it") { () =>
    val history = historyWith("erasure-withdrawn")
    assert(history.contains(withdrawer), history)
  }

  When(
    "whoever asked for it replaces the held erasure request with the not-before date {string}"
  ) { (date: String) =>
    val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
    replacedWith = date
    answered = harness.send(
      "PUT",
      ridPath(subject),
      Some(s"""{"notBefore":"${date}T00:00:00Z"}"""),
      filer
    )
  }

  private var replacedWith = ""

  When(
    "a member asks again for an erasure request for {string} in {string} with no not-before date"
  ) { (subject: String, in: String) =>
    assertEquals(in, project)
    // The refusal reads the requests' listing: ask once it lists the held request.
    eventually("the list shows the held request") {
      val (_, list) =
        harness.send("GET", s"/projects/$planeProject/erasures?subject=$subject", as = "ada")
      Option.when(list.contains(requestOf(subject)))(())
    }
    answered = askErasure(subject)
  }

  Then("the ask is refused, naming the held erasure request") { () =>
    val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
    assertEquals(answered._1, 409, answered._2)
    assert(answered._2.contains(requestOf(subject)), answered._2)
  }

  Then("the erasure request is still held with the not-before date {string}") { (date: String) =>
    val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
    sweepOnce()
    val reply = readRequest(subject)
    assert(reply.contains(""""state":"held""""), reply)
    assert(reply.contains(s""""notBefore":"${date}T00:00:00Z""""), reply)
    // Nothing else was asked for the subject: still one request.
    val (_, list) =
      harness.send("GET", s"/projects/$planeProject/erasures?subject=$subject", as = "ada")
    assertEquals(RequestId.findAllMatchIn(list).size, 1, list)
  }

  Then("the new erasure request replaces the held one") { () =>
    assertEquals(answered._1, 200, answered._2)
    val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
    val reply   = readRequest(subject)
    assert(reply.contains(""""state":"held""""), reply)
    assert(reply.contains(s""""notBefore":"${replacedWith}T00:00:00Z""""), reply)
    // One request, listed with its new date: replaced in place, not asked again.
    val list = eventually("the list shows the new date") {
      val (_, l) =
        harness.send("GET", s"/projects/$planeProject/erasures?subject=$subject", as = "ada")
      Option.when(l.contains(s"${replacedWith}T00:00:00Z"))(l)
    }
    assertEquals(RequestId.findAllMatchIn(list).size, 1, list)
  }

  Then("the history of the erasure request keeps both") { () =>
    val history = historyWith("erasure-replaced")
    assert(history.contains("erasure-asked") && history.contains("2031-10-08"), history)
    assert(history.contains("erasure-replaced") && history.contains(replacedWith), history)
  }

  When("an owner overrides the hold with the reason {string}") { (reason: String) =>
    val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
    answered = harness.send(
      "POST",
      s"${ridPath(subject)}/override",
      Some(s"""{"reason":"$reason"}"""),
      "ada"
    )
  }

  When("a member who is not an owner overrides the hold with a reason") { () =>
    val subject = asked.keys.headOption.getOrElse(fail("nothing was asked"))
    answered = harness.send(
      "POST",
      s"${ridPath(subject)}/override",
      Some("""{"reason":"I would like it gone"}"""),
      "bob"
    )
  }

  Then("the erasure request is applied at once") { () =>
    assertEquals(answered._1, 200, answered._2)
    val reply = untilFinal(asked.keys.headOption.getOrElse(fail("nothing was asked")))
    assert(reply.contains(""""state":"applied""""), reply)
  }

  Then("it records the override, the owner and the reason {string}") { (reason: String) =>
    val reply = readRequest(asked.keys.headOption.getOrElse(fail("nothing was asked")))
    assert(reply.contains(""""holdOverride":{"""), reply)
    assert(reply.contains(s""""reason":"$reason""""), reply)
    historyWith("erasure-hold-overridden"): Unit
  }

  Then("the member is refused") { () =>
    assertEquals(answered._1, 403, answered._2)
  }

  Then("the erasure request is still held") { () =>
    val reply = readRequest(asked.keys.headOption.getOrElse(fail("nothing was asked")))
    assert(reply.contains(""""state":"held""""), reply)
  }

  for who <- Seq("whoever asked for it", "a member", "an owner") do
    When(s"$who withdraws the erasure request for {string} in {string}") {
      (subject: String, in: String) =>
        assertEquals(in, project)
        answered = harness.send("POST", s"${ridPath(subject)}/withdraw", None, personOf(who))
    }
    Then(s"$who is refused") { () =>
      assertEquals(answered._1, 409, answered._2)
    }

  Then("the erasure request is still applied") { () =>
    val reply = readRequest(asked.keys.headOption.getOrElse(fail("nothing was asked")))
    assert(reply.contains(""""state":"applied""""), reply)
  }

/**
 * `erasing.feature` through the keyring's own server on loopback (feature 042, T053): every key a
 * service holds fetched over mutual TLS, every erasure ordered by the control plane's door and
 * heard on each instance's notices socket.
 */
class ErasureKeyringFeatures
    extends ErasureFeatures("../features/erasure/erasing.feature", onKeyring = true):
  // Every scenario this suite leaves to another is in another file of the directory.
  override protected def ranElsewhere: Map[String, String] = Map.empty
