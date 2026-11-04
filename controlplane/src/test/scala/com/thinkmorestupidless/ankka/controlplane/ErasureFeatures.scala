package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.erasure.*
import com.thinkmorestupidless.ankka.core.{
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
class ErasureFeatures extends GherkinSuite("../features/erasure") with LogCapturing:

  override val munitTimeout = 4.minutes

  override protected def ranElsewhere: Map[String, String] = Map(
    // A restored database and a restored keyring are a cluster's: CNPG's recovery and the hold.
    "a service restored to before an erasure is not ready until it has applied the erasure log to its own tables" ->
      "ErasureRestoreClusterFeatures, on k3s",
    "an entity recovered from a restored database reads an erased data subject as erased, because the keyring was not restored" ->
      "ErasureRestoreClusterFeatures, on k3s",
    "the keyring restored to before an erasure applies the erasure log before it answers any request" ->
      "ErasureRestoreClusterFeatures, on k3s",
    // A machine outside the installation reaches the keyring through the installation's gateway.
    "a machine outside the installation with a grant that does not allow decryption reads every personal field as erased" ->
      "ErasureClusterFeatures, on k3s, through the gateway",
    "a machine outside the installation with a grant that allows decryption asks the keyring to decrypt each field" ->
      "ErasureClusterFeatures, on k3s, through the gateway",
    "the keyring refuses to decrypt for a machine outside the installation once its grant is revoked or the data subject is erased" ->
      "ErasureClusterFeatures, on k3s, through the gateway",
    // A soft-delete window is the cloud store's (GCS), which only the GCS cluster suite reaches.
    "the erasure request says when the erasure of objects becomes final on an object store with a soft-delete window" ->
      "ObjectStorageGcsClusterFeatures, against a real bucket",
    // Each language writes the envelope itself.
    "an SDK writes the same personal envelope and reads it back into its own personal type" ->
      "each SDK's suite against protocol/fixtures/personal, and ConformanceSuite's personal.* cases",
    "a service written in a language of the JVM writes the personal envelope a service written in Scala writes" ->
      "PersonalJavaSuite (testkit-java) and the Kotlin SDK's PersonalTest",
    // The console is tested by its own Playwright suite against the fake control plane.
    "the console shows the erasure requests of a project with where each one stands and each service's completion" ->
      "console/e2e/tests/erasures.spec.ts, against the fake control plane"
  )

  // ── the service ────────────────────────────────────────────────────────────

  private var kits: Vector[AnkkaTestKit] = Vector.empty
  private var broker: InMemoryBroker     = InMemoryBroker()
  private var keyring: InMemoryKeyring   = InMemoryKeyring()
  private var project: String            = "brand"

  /** The scenario's first kit: `players` in its project. */
  private def kit: AnkkaTestKit = kits.headOption.getOrElse(fail("no service was started"))

  private def startKit(): AnkkaTestKit =
    val started = AnkkaTestKit.start(
      Seq(
        PlayerEntity.descriptor,
        PlayerContactEntity.descriptor,
        Profiles.descriptor,
        RegistrationPublisher.descriptor,
        Engagement.descriptor
      ),
      Seq(ProjectionRuntime.withBroker(broker, broker)),
      serviceIdentity = ServiceIdentity.deployed(project, "players"),
      keyring = keyring
    )
    kits :+= started
    started

  private def stopKits(): Unit =
    kits.foreach(k => scala.util.Try(k.stop()))
    kits = Vector.empty

  override def beforeEach(context: BeforeEach): Unit =
    stopKits()
    broker = InMemoryBroker()
    keyring = InMemoryKeyring()
    Engagement.handed.clear()
    written = None

  override def afterAll(): Unit =
    stopKits()
    if harnessStarted then harness.stop()

  // ── the control plane, for what a member applies ──────────────────────────

  private var erasureOn                   = false
  private var harnessStarted              = false
  private lazy val harness: GrantsHarness =
    val h = GrantsHarness(erasure =
      () => com.thinkmorestupidless.ankka.controlplane.tenancy.ErasureSettings(erasureOn, 2)
    )
    h.start()
    h.organization("acme", "ada")
    h.project("brand", "acme", "ada")
    harnessStarted = true
    h

  private var applied: (Int, String) = (0, "")

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
    keyring.key(e.project, DataSubject(subject), create = false) match
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
    assert(!keyring.holdsKey(project, DataSubject(subject)))
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
      assert(keyring.holdsKey(project, DataSubject(subject)))
      assertEquals(keyring.keysMade(project, DataSubject(subject)), 1)
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
    assertEquals(keyring.keysMade(project, DataSubject(subject)), 1)
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
