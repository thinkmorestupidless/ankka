package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  GherkinSuite,
  LogCapturing,
  TestKeyringRelay
}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.*

/** A player's registration, whose email is a personal field of the player (feature 042). */
final case class RegisterPlayer(email: String, replicateTo: Vector[String])

enum PlayerEvent:
  case Registered(email: Personal[String])

final case class PlayerCard(email: Option[Personal[String]] = None)

final class ReplicatedPlayerEntity(ctx: EventSourcedEntityContext)
    extends EventSourcedEntity[PlayerCard, PlayerEvent]:

  def emptyState: PlayerCard = PlayerCard()

  def applyEvent(event: PlayerEvent): PlayerCard = event match
    case PlayerEvent.Registered(email) => PlayerCard(Some(email))

  def register(input: RegisterPlayer): Effect[Done] =
    val subject = DataSubject(s"player/${ctx.entityId}")
    input.replicateTo
      .foldLeft(effects.persist(PlayerEvent.Registered(Personal(subject, input.email))))(
        _.replicateTo(_)
      )
      .thenReply(_ => Done)

  /** The email as this copy reads it: the value, `erased`, or `none`. */
  def email: ReadOnlyEffect[String] =
    effects.reply(currentState.email.fold("none")(_.orElse("erased")))

object ReplicatedPlayerCodecs:
  given Serializer[RegisterPlayer] = Codecs.serializer[RegisterPlayer]("register-player")

import ReplicatedPlayerCodecs.given

object ReplicatedPlayers
    extends EventSourcedEntity.Companion[ReplicatedPlayerEntity, PlayerCard, PlayerEvent](
      ComponentId("players"),
      Codecs.serializer[PlayerCard]("player-card"),
      Codecs.serializer[PlayerEvent]("player-event")
    ):
  override def replicated: Boolean = true

  def create(ctx: EventSourcedEntityContext) = new ReplicatedPlayerEntity(ctx)

  val register = command("register")(_.register)
  val email    = query("email")(_.email)

/**
 * `features/erasure/replication.feature`, in process (feature 042, user story 5, R17): two
 * installations of one service in this JVM, each with its own in-memory keyring, and the test kit's
 * keyring relay between them. Only the home's keyring keeps the entity's keys; a follower is given
 * them through the relay and holds each for `foreign-ttl`, here a second.
 *
 * "Asks for an erasure request and it is applied" destroys the key in the home's keyring, which is
 * what an applied request does there; the request's own path is `ErasureFeatures`'.
 */
class ErasureReplicationFeatures
    extends GherkinSuite("../features/erasure/replication.feature")
    with LogCapturing:

  override val munitTimeout = 4.minutes

  // Names no other suite of this JVM starts: an installation is one name per JVM.
  private val Home     = "north"
  private val Follower = "south"

  private var fleet: Option[AnkkaTestKit.Installations] = None
  private val entity                                    = EntityId("8c1f")
  private val written                                   = "ada@example.com"
  private var answers                                   = Vector.empty[String]

  private val settings = ConfigFactory.parseString("ankka.keyring.cache.foreign-ttl = 1s")

  private def group: AnkkaTestKit.Installations = fleet.getOrElse(fail("no installations"))

  private def at(installation: String) = group(installation)

  private def subject = DataSubject("player/8c1f")

  /** The email as `installation` reads it now, or the failure that read was. */
  // What the last read answered, for a wait that ends without what it waited for.
  @volatile private var lastRead: Option[Either[Throwable, String]] = None

  private def read(installation: String): Either[Throwable, String] =
    val r = scala.util
      .Try(
        at(installation).componentClient
          .forEventSourcedEntity(entity)
          .call(ReplicatedPlayers.email)
          .invoke()
      )
      .toEither
    lastRead = Some(r)
    r

  private def eventually[A](what: String, within: FiniteDuration = 60.seconds)(
      probe: => Option[A]
  ): A =
    val deadline = System.nanoTime() + within.toNanos
    var found    = probe
    while found.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(250)
      found = probe
    found.getOrElse(fail(s"$what: not within $within; the last read answered $lastRead"))

  override def beforeEach(context: BeforeEach): Unit =
    TestKeyringRelay.unblockAll()
    answers = Vector.empty

  override def afterEach(context: AfterEach): Unit =
    TestKeyringRelay.unblockAll()
    fleet.foreach(_.stopAll())
    fleet = None

  Given(
    "an entity of {string} whose home is the installation {string} and whose follower is the installation {string}"
  ) { (_: String, home: String, follower: String) =>
    assertEquals((home, follower), (Home, Follower))
    val started = AnkkaTestKit.installations(Home, Follower)
    fleet = Some(started)
    started.start(Home, Seq(ReplicatedPlayers.descriptor), settings = settings): Unit
    started.start(Follower, Seq(ReplicatedPlayers.descriptor), settings = settings): Unit
  }

  Given("its events hold the field {string} as a personal field of the data subject {string}") {
    (field: String, s: String) =>
      assertEquals((field, s), ("email", subject.value))
      at(Home).componentClient
        .forEventSourcedEntity(entity)
        .call(ReplicatedPlayers.register)
        .invoke(RegisterPlayer(written, Vector(Follower))): Unit
      eventually(s"$Follower mirrors the entity")(read(Follower).toOption.filter(_ == written))
  }

  When("the entity is recovered at {string}") { (installation: String) =>
    at(installation).restartService()
  }

  Then("it reads the field {string} as it was written") { (_: String) =>
    assertEquals(read(Follower), Right(written))
  }

  Then("the keyring of {string} keeps no subject key of {string}") {
    (installation: String, s: String) =>
      val kit = at(installation)
      assert(!kit.keyring.holdsKey(kit.project, DataSubject(s)))
      assertEquals(kit.keyring.keysMade(kit.project, DataSubject(s)), 0)
  }

  When("a member asks for an erasure request for {string} at {string} and it is applied") {
    (s: String, installation: String) =>
      val kit = at(installation)
      kit.keyring.erase(kit.project, DataSubject(s)): Unit
  }

  Then(
    "within {int} seconds the entity at {string}, recovered again, reads the field {string} as erased"
  ) { (seconds: Int, installation: String, _: String) =>
    eventually(s"$installation reads the email as erased", seconds.seconds) {
      at(installation).restartService()
      read(installation).toOption.filter(_ == "erased")
    }
  }

  Given("the keyring of {string} does not answer {string}") { (home: String, from: String) =>
    TestKeyringRelay.block(from, home)
  }

  Given("{string} has no subject key of {string}") { (installation: String, s: String) =>
    val kit = at(installation)
    assert(!kit.keyring.holdsKey(kit.project, DataSubject(s)))
  }

  Then("the recovery of the entity fails and is retried") { () =>
    // While the home's keyring does not answer, every read fails: the entity is not recovered.
    (1 to 2).foreach { _ =>
      val r = read(Follower)
      answers ++= r.toOption
      assert(r.isLeft, s"a read while the home's keyring does not answer: $r")
    }
    TestKeyringRelay.unblockAll()
    val recovered = eventually(s"$Follower recovers once the home's keyring answers") {
      val r = read(Follower)
      answers ++= r.toOption
      r.toOption
    }
    assertEquals(recovered, written)
  }

  Then("the field {string} is never read as erased") { (_: String) =>
    assert(!answers.contains("erased"), answers)
    assert(answers.nonEmpty)
  }
