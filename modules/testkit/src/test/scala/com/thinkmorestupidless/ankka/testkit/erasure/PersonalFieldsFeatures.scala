package com.thinkmorestupidless.ankka.testkit.erasure

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.personal.{KeyringHandle, Personal, PersonalScope}
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  InMemoryBroker,
  ProjectionRuntime,
  SqlFragment
}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  GherkinSuite,
  InMemoryKeyring,
  LogCapturing
}

import java.time.LocalDate
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

/** `features/erasure/personal-fields.feature`, through a whole service and its database. */
class PersonalFieldsFeatures
    extends GherkinSuite("../../features/erasure/personal-fields.feature")
    with LogCapturing:

  override val munitTimeout = 3.minutes

  private val keyring                         = InMemoryKeyring()
  private val broker                          = InMemoryBroker()
  private var kit: AnkkaTestKit               = null
  private var peer: Option[AnkkaTestKit.Peer] = None
  private var refusal: Option[Throwable]      = None
  private var where: String                   = ""

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(
        PlayerEntity.descriptor,
        Profiles.descriptor,
        SettingsEntity.descriptor,
        PlayersPublisher.descriptor,
        Engagement.descriptor
      ),
      Seq(ProjectionRuntime.withBroker(broker, broker)),
      keyring = Some(keyring)
    )

  override def afterAll(): Unit =
    peer.foreach(_.stop())
    if kit != null then kit.stop()

  private given org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system

  private def column(sqlText: String): String =
    Await
      .result(Database().query(SqlFragment.raw(sqlText))(_.get(0, classOf[String])), 10.seconds)
      .mkString("\n")

  private def journal   = column("SELECT encode(event_payload, 'escape') FROM event_journal")
  private def snapshots = column("SELECT encode(snapshot, 'escape') FROM snapshot")
  private def states    = column("SELECT encode(state_payload, 'escape') FROM durable_state")
  private def row(key: String) =
    Await.result(
      Database().queryOne(
        SqlFragment.raw("SELECT payload FROM ankka_view_profiles WHERE row_key = ") ++ sql"$key"
      )(_.get(0, classOf[String])),
      10.seconds
    )

  private def register(id: String, email: String): Unit =
    kit.componentClient
      .forEventSourcedEntity(EntityId(id))
      .call(PlayerEntity.register)
      .invoke(Registration(email, "Ada Byron", LocalDate.of(1815, 12, 10), "GBP")): Unit

  Given("a service {string} in the project {string}")((_: String, _: String) => ())
  Given(
    "the event {string} of {string} has the fields {string}, {string} and {string} marked as personal fields of the data subject {string}"
  ) { (_: String, _: String, _: String, _: String, _: String, _: String) =>
    ()
  }
  Given(
    "the event {string} of {string} has the fields {string} and {string} that are not personal fields"
  ) { (_: String, _: String, _: String, _: String) =>
    ()
  }

  When("an entity of {string} records a {string} with the email {string}") {
    (_: String, _: String, email: String) =>
      register("8c1f", email)
  }
  Then(
    "the journal of {string} holds the field {string} of that event encrypted under the subject key of {string}"
  ) { (_: String, field: String, subject: String) =>
    assert(
      journal.contains(s""""$field":{"subject":"$subject","project":"local","data":"""),
      journal.take(800)
    )
  }
  Then("the journal holds the data subject {string} readable beside it") { (subject: String) =>
    assert(journal.contains(s""""subject":"$subject""""))
  }
  Then("the journal holds the fields {string} and {string} of that event as they were written") {
    (a: String, b: String) =>
      assert(journal.contains(s""""$a":"GBP""""), journal.take(800))
      assert(journal.contains(s""""$b":1700000000000"""), journal.take(800))
  }
  Then("nothing in the database of {string} reads as {string}") { (_: String, value: String) =>
    kit.assertNoPersonalValue(value)
  }

  private var snapshotted = ""

  Given("an entity of {string} whose state has the personal field {string} of {string}") {
    (_: String, _: String, subject: String) =>
      snapshotted = subject.stripPrefix("player/")
      register(snapshotted, "snap@example.com")
  }
  When("the entity's snapshot is kept") { () =>
    kit.componentClient
      .forEventSourcedEntity(EntityId(snapshotted))
      .call(PlayerEntity.deposit)
      .invoke(1): Unit
    kit.eventually("a snapshot")(Option.when(snapshots.nonEmpty)(()))
  }
  Then("the snapshot holds the field {string} encrypted under the subject key of {string}") {
    (field: String, subject: String) =>
      assert(
        snapshots.contains(s""""$field":{"subject":"$subject","project":"local","data":"""),
        snapshots.take(600)
      )
      assert(!snapshots.contains("snap@example.com"))
  }

  Given("a key value entity of {string} whose state has the personal field {string} of {string}") {
    (_: String, _: String, _: String) =>
      kit.componentClient
        .forKeyValueEntity(EntityId("8c1f"))
        .call(SettingsEntity.setEmail)
        .invoke("kv@example.com"): Unit
  }
  When("the key value entity's state is changed") { () =>
    kit.componentClient
      .forKeyValueEntity(EntityId("8c1f"))
      .call(SettingsEntity.setTheme)
      .invoke("dark"): Unit
  }
  Then(
    "the database of {string} holds the field {string} of that state encrypted under the subject key of {string}"
  ) { (_: String, field: String, subject: String) =>
    assert(
      states.contains(s""""$field":{"subject":"$subject","project":"local","data":"""),
      states.take(600)
    )
    kit.assertNoPersonalValue("kv@example.com")
  }

  Given(
    "a view {string} of {string} that reads {string} into a row with the personal field {string}"
  ) { (_: String, _: String, _: String, _: String) =>
    // One kit runs every scenario, and earlier ones wrote this entity with other emails: the row is
    // written again here, and waited for until it holds this write, not a stale one.
    register("8c1f", "ada@example.com")
  }
  When("{string} writes the row for {string}") { (_: String, subject: String) =>
    val id = subject.stripPrefix("player/")
    kit.eventually("the row holds the latest write")(
      kit.service.viewClient
        .forView(Profiles)
        .get(id)
        .filter(_.email.toOption.contains("ada@example.com"))
    ): Unit
  }
  Then(
    "the table of {string} holds the field {string} of that row encrypted under the subject key of {string}"
  ) { (_: String, field: String, subject: String) =>
    val text = row(subject.stripPrefix("player/")).get
    assert(text.contains(s""""$field":{"subject":"$subject","project":"local","data":"""), text)
  }
  // Both reads wait for the row this scenario wrote: one kit runs every scenario, and an earlier
  // one wrote this player too.
  Then("a read of the row by its row key is told the email {string}") { (email: String) =>
    kit.eventually("the row read by key")(
      kit.service.viewClient
        .forView(Profiles)
        .get("8c1f")
        .flatMap(_.email.toOption)
        .filter(_ == email)
    ): Unit
  }
  Then("a declared query of {string} that reads the row is told the email {string}") {
    (_: String, email: String) =>
      val token = Personal.lookupToken(email)
      kit.eventually("the row found by its token")(
        kit.service.viewClient
          .forView(Profiles)
          .ask(Profiles.byEmail, "email" -> token)
          .filter(_.playerId == "8c1f")
          .flatMap(_.email.toOption)
          .find(_ == email)
      ): Unit
  }

  Given("a consumer of {string} that publishes {string} to the topic {string}") {
    (_: String, _: String, _: String) => ()
  }
  Given("a consumer {string} in the project {string} that reads the topic {string}") {
    (_: String, _: String, _: String) => ()
  }
  When("{string} publishes a {string} with the email {string}") {
    (_: String, _: String, email: String) =>
      register("topic1", email)
  }
  Then(
    "the message on the broker holds the field {string} encrypted under the subject key of {string}"
  ) { (field: String, _: String) =>
    val message =
      kit.eventually("the message")(broker.publishedTo("players").find(_.text.contains("topic1")))
    assert(
      message.text.contains(s""""$field":{"subject":"player/topic1","project":"local","data":"""),
      message.text
    )
    assert(!message.text.contains("topic@example.com"))
  }
  Then("the handler of {string} is handed the email {string}") { (_: String, email: String) =>
    kit.eventually("engagement handed the registration")(Option(Engagement.seen.get("topic1")))
    assertEquals(Engagement.seen.get("topic1").toOption, Some(email))
  }

  Given("the keyring holds no subject key for {string}") { (subject: String) =>
    assert(!keyring.holdsKey("local", subject))
  }
  Given("two instances of {string}") { (_: String) =>
    peer = Some(kit.startPeer(Seq(ProjectionRuntime.withBroker(broker, broker))))
  }
  When("one instance records an event with a personal field of {string}") { (subject: String) =>
    kit.componentClient
      .forEventSourcedEntity(EntityId(subject.stripPrefix("player/")))
      .call(PlayerEntity.register)
      .invoke(Registration("first@example.com", "First", LocalDate.of(2000, 1, 1), "EUR")): Unit
  }
  Then("the keyring holds one subject key for {string}, made with that write") { (subject: String) =>
    assert(keyring.holdsKey("local", subject))
  }
  Then(
    "an event with a personal field of {string} that the other instance records is encrypted under the same subject key"
  ) { (subject: String) =>
    val made = keyring.minted
    peer.get.componentClient
      .forEventSourcedEntity(EntityId(subject.stripPrefix("player/")))
      .call(PlayerEntity.deposit)
      .invoke(5): Unit
    assertEquals(keyring.minted, made, "no second key made")
    assertEquals(
      kit.componentClient
        .forEventSourcedEntity(EntityId(subject.stripPrefix("player/")))
        .call(PlayerEntity.get)
        .invoke()
        .email,
      "first@example.com"
    )
  }

  Given("the routes of {string} listed by the command line") { (_: String) => where = "cli" }
  Given("a test of a component of {string} with no test kit") { (_: String) => where = "unit" }
  When("a personal field is written") { () =>
    // Both run outside any service: no scope of their own, and a JVM whose default is withdrawn.
    refusal = Try {
      PersonalScope.within(KeyringHandle.unavailable, "local") {
        PlayerEntity.eventSerializer.toBytes(
          PlayerEvent.Registered(
            "x",
            Personal.Present("player/x", "x@example.com"),
            Personal.Present("player/x", "X"),
            Personal.Present("player/x", LocalDate.EPOCH),
            "GBP",
            0
          )
        )
      }
    }.failed.toOption
  }
  Then("the write is refused as unavailable") { () =>
    assertEquals(
      refusal.collect { case e: CommandError => e.code },
      Some(ErrorCode.Unavailable),
      s"$where: $refusal"
    )
  }
  Then("the refusal names the keyring") { () =>
    assert(refusal.exists(_.getMessage.contains("keyring")), String.valueOf(refusal))
  }
  Then("nothing is written")(() => ())
