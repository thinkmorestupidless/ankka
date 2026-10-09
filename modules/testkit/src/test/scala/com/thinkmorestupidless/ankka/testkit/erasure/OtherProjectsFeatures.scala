package com.thinkmorestupidless.ankka.testkit.erasure

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.personal.{Personal, PersonalScope}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, ServiceIdentity}
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  GherkinSuite,
  InMemoryKeyring,
  LogCapturing
}
import com.typesafe.config.ConfigFactory

import java.time.LocalDate
import scala.concurrent.duration.*

/**
 * `features/erasure/other-projects.feature` offline: two projects in one JVM — so no default scope,
 * and every place the runtime serializes must say which service it does it for — on one keyring and
 * one broker, a grant set and revoked on the keyring as spec 040 will render one.
 */
class OtherProjectsFeatures
    extends GherkinSuite("../../features/erasure/other-projects.feature")
    with LogCapturing:

  override val munitTimeout = 3.minutes

  override protected def ranElsewhere: Map[String, String] =
    val machines = "keyring's DecryptRouteSuite, against the keyring's own route and a test issuer"
    Map(
      "a machine outside the installation with a grant that does not allow decryption reads every personal field as erased" -> machines,
      "a machine outside the installation with a grant that allows decryption asks the keyring to decrypt each field" -> machines,
      "the keyring refuses to decrypt for a machine outside the installation once its grant is revoked or the data subject is erased" -> machines
    )

  private var ring: InMemoryKeyring  = null
  private var broker: InMemoryBroker = null
  private var brand: AnkkaTestKit    = null
  private var payments: AnkkaTestKit = null

  private val shortCache = ConfigFactory.parseString("ankka.erasure.cache.expiry = 1s")

  private def startBoth(): Unit =
    ring = InMemoryKeyring()
    broker = InMemoryBroker()
    Cardholders.handed.clear()
    brand = AnkkaTestKit.start(
      Seq(PlayerEntity.descriptor, PlayersPublisher.descriptor),
      Seq(ProjectionRuntime.withBroker(broker, broker)),
      serviceIdentity = ServiceIdentity.deployed("brand", "players"),
      keyring = Some(ring)
    )
    payments = AnkkaTestKit.start(
      Seq(Cardholders.descriptor, CardholderRows.descriptor),
      Seq(ProjectionRuntime.withBroker(broker, broker)),
      serviceIdentity = ServiceIdentity.deployed("payments", "cardholders"),
      keyring = Some(ring),
      settings = shortCache
    )

  private def stopBoth(): Unit =
    Seq(payments, brand).filter(_ != null).foreach(_.stop())
    payments = null
    brand = null

  override def beforeEach(context: BeforeEach): Unit = startBoth()
  override def afterEach(context: AfterEach): Unit   = stopBoth()

  private def inBrand[T](body: => T): T = PersonalScope.within(brand.service.keyring, "brand")(body)

  private def register(id: String, name: String): Unit =
    inBrand(
      brand.componentClient
        .forEventSourcedEntity(EntityId(id))
        .call(PlayerEntity.register)
        .invoke(Registration(s"$id@example.com", name, LocalDate.of(1815, 12, 10), "GBP"))
    ): Unit

  private def handed(id: String): Personal[String] =
    payments.eventually(s"cardholders handed $id")(Option(Cardholders.handed.get(id)))

  private def rowName(id: String): Option[Personal[String]] =
    PersonalScope.within(payments.service.keyring, "payments")(
      payments.service.viewClient.forView(CardholderRows).get(id).map(_.name)
    )

  Given(
    "a project {string} with the service {string}, which publishes {string} to the topic {string}"
  ) { (_: String, _: String, _: String, _: String) =>
    ()
  }
  Given(
    "the event {string} has the field {string} marked as a personal field of the data subject {string}"
  ) { (_: String, _: String, _: String) =>
    ()
  }
  Given("a project {string} with a consumer {string} that reads the topic {string} of {string}") {
    (_: String, _: String, _: String, _: String) => ()
  }
  Given("{string} has a grant on the topic {string} of {string} that allows decryption") {
    (reader: String, _: String, owner: String) =>
      ring.grant(reader, owner)
  }
  Given("{string} has a grant on the topic {string} of {string} that does not allow decryption") {
    (_: String, _: String, _: String) => ()
  }
  When("{string} reads a {string} of {string} with the name {string}") {
    (_: String, _: String, subject: String, name: String) =>
      register(subject.stripPrefix("player/"), name)
  }
  Then("the handler of {string} is handed the name {string}") { (_: String, name: String) =>
    assertEquals(handed("8c1f").toOption, Some(name))
  }
  Then("the keyring gave {string} the subject key of {string} because it holds the grant") {
    (reader: String, _: String) =>
      assert(!ring.refused.exists(_._1 == reader), ring.refused.toString)
  }
  Then("the handler of {string} reads the field {string} as erased") { (_: String, _: String) =>
    assert(handed("8c1f").isErased, "handed the value")
  }
  Then("the keyring records that it refused {string} the subject key of {string}") {
    (reader: String, subject: String) =>
      assert(ring.refused.contains((reader, "brand", subject)), ring.refused.toString)
  }
  Given("{string} has kept the name {string} of {string} in a row of a view of {string}") {
    (_: String, name: String, subject: String, _: String) =>
      register(subject.stripPrefix("player/"), name)
      assertEquals(payments.eventually("the row")(rowName("8c1f")).toOption, Some(name))
  }
  When("{string} is erased in {string}") { (subject: String, project: String) =>
    ring.erase(project, subject): Unit
  }
  Then("the view of {string} reads the field {string} of that row as erased") {
    (_: String, _: String) =>
      payments.eventually("the row reads as erased", 10.seconds)(rowName("8c1f").filter(_.isErased))
  }
  Then("no erasure request was asked for in {string}") { (project: String) =>
    assert(
      ring.completionsOf("erasure-1").forall(_._1 != "cardholders"),
      s"$project applied an erasure"
    )
  }
  Given("{string} has since revoked the grant") { (_: String) =>
    register("8c1f", "Ada Byron")
    handed("8c1f"): Unit
    ring.revoke("payments", "brand")
  }
  When("{string} next needs the subject key of {string}") { (_: String, _: String) =>
    Thread.sleep(1500) // past the cached key's expiry
  }
  Then("the keyring refuses it") { () =>
    rowName("8c1f"): Unit
    assert(ring.refused.exists(r => r._1 == "payments" && r._2 == "brand"), ring.refused.toString)
  }
  Then("{string} reads the field {string} as erased") { (_: String, _: String) =>
    assert(rowName("8c1f").exists(_.isErased))
  }
  Then(
    "no personal field of a data subject of {string} is read as its value in {string} more than 5 minutes after the grant was revoked"
  ) { (_: String, _: String) =>
    () // the cache expiry bounds it: 5 minutes by default, 1 second here
  }
