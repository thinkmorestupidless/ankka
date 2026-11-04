package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.{Instant, LocalDate}
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The `players` service of `features/erasure/` (feature 042): a player whose registration carries
 * three personal fields of the player and two that are not, its contact card, a view of profiles, a
 * consumer that publishes registrations to the topic `players`, and `engagement`, which reads it.
 */
final case class RegisterPlayer(
    email: String,
    name: String,
    dateOfBirth: LocalDate,
    currency: String
)

given Serializer[RegisterPlayer] = Codecs.serializer[RegisterPlayer]("register-player")

sealed trait PlayerEvent
final case class PlayerRegistered(
    playerId: String,
    email: Personal[String],
    name: Personal[String],
    dateOfBirth: Personal[LocalDate],
    currency: String,
    registeredAt: Instant
) extends PlayerEvent
final case class EmailChanged(email: Personal[String]) extends PlayerEvent

given Serializer[PlayerRegistered] = Codecs.serializer[PlayerRegistered]("player-registered")

/** What the `players` endpoint answers: the player's id and email (`edge.feature`). */
final case class Registration(playerId: String, email: Personal[String])

given Serializer[Registration] = Codecs.serializer[Registration]("registration")

final case class Player(
    email: Option[Personal[String]],
    name: Option[Personal[String]],
    currency: Option[String]
)

object Players:
  def subject(playerId: String): DataSubject = DataSubject(s"player/$playerId")

final class PlayerEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Player, PlayerEvent]:

  private val playerId = context.entityId
  private val subject  = Players.subject(playerId)

  def emptyState: Player = Player(None, None, None)

  def applyEvent(event: PlayerEvent): Player = event match
    case r: PlayerRegistered => Player(Some(r.email), Some(r.name), Some(r.currency))
    case EmailChanged(email) => currentState.copy(email = Some(email))

  def register(command: RegisterPlayer): Effect[Done] =
    effects
      .persist(
        PlayerRegistered(
          playerId,
          Personal(subject, command.email),
          Personal(subject, command.name),
          Personal(subject, command.dateOfBirth),
          command.currency,
          Instant.parse("2026-11-04T09:00:00Z")
        )
      )
      .thenReply(_ => Done)

  /** Records a registration a caller sent whole, as the endpoint of `edge.feature` hands it. */
  def record(registered: PlayerRegistered): Effect[Done] =
    effects.persist(registered).thenReply(_ => Done)

  def registration: ReadOnlyEffect[Registration] =
    currentState.email match
      case Some(email) => effects.reply(Registration(playerId, email))
      case None        => effects.error(s"'$playerId' is not registered", ErrorCode.NotFound)

  def changeEmail(email: String): Effect[Done] =
    effects.persist(EmailChanged(Personal(subject, email))).thenReply(_ => Done)

  /** The email, `erased` once the player is, or `none`. */
  def email: ReadOnlyEffect[String] =
    effects.reply(currentState.email.fold("none")(_.orElse("erased")))

  def currency: ReadOnlyEffect[String] = effects.reply(currentState.currency.getOrElse("none"))

object PlayerEntity
    extends EventSourcedEntity.Companion[PlayerEntity, Player, PlayerEvent](
      componentId = ComponentId("player"),
      stateSerializer = Codecs.serializer[Player]("player"),
      eventSerializer = Codecs.serializer[PlayerEvent]("player-event")
    ):
  def create(context: EventSourcedEntityContext) = new PlayerEntity(context)

  // Every event is followed by a snapshot, so a snapshot of a personal field is always there to read.
  override def snapshotEvery: Option[Int] = Some(1)

  val register     = command("register")(_.register)
  val changeEmail  = command("change-email")(_.changeEmail)
  val record       = command("record")(_.record)
  val registration = query("registration")(_.registration)
  val email        = query("email")(_.email)
  val currency     = query("currency")(_.currency)

/** A player's contact card, as a key value entity. */
final case class PlayerContact(email: Option[Personal[String]])

final class PlayerContactEntity(context: KeyValueEntityContext)
    extends KeyValueEntity[PlayerContact]:

  private val subject = Players.subject(context.entityId)

  def emptyState: PlayerContact = PlayerContact(None)

  def setEmail(email: String): Effect[Done] =
    effects.updateState(PlayerContact(Some(Personal(subject, email)))).thenReply(_ => Done)

  def email: ReadOnlyEffect[String] =
    effects.reply(currentState.email.fold("none")(_.orElse("erased")))

object PlayerContactEntity
    extends KeyValueEntity.Companion[PlayerContactEntity, PlayerContact](
      componentId = ComponentId("player-contact"),
      stateSerializer = Codecs.serializer[PlayerContact]("player-contact")
    ):
  def create(context: KeyValueEntityContext) = new PlayerContactEntity(context)

  val setEmail = command("set-email")(_.setEmail)
  val email    = query("email")(_.email)

/** One row per player, with the email as a personal field. */
final case class ProfileRow(playerId: String, email: Personal[String])

final class ProfilesView extends View[PlayerEvent, ProfileRow]:
  def onChange(event: PlayerEvent): Effect = event match
    case r: PlayerRegistered => effects.updateRow(ProfileRow(r.playerId, r.email))
    case _: EmailChanged     => effects.ignore()

object Profiles
    extends View.Companion[ProfilesView, PlayerEvent, ProfileRow](
      componentId = ComponentId("profiles"),
      source = ChangeSource.eventsOf(PlayerEntity),
      rowSerializer = Codecs.serializer[ProfileRow]("profile-row")
    ):
  def create(ctx: ViewComponentContext) = new ProfilesView

  val byPlayer = query("by-player")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player_id"
  )

/** Publishes every registration to the topic `players`. */
final class RegistrationPublisher extends Consumer[PlayerEvent, PlayerEvent]:
  def onMessage(event: PlayerEvent): Effect = event match
    case r: PlayerRegistered => effects.produce(r)
    case _: EmailChanged     => effects.ignore()

object RegistrationPublisher
    extends Consumer.Companion[RegistrationPublisher, PlayerEvent, PlayerEvent](
      componentId = ComponentId("registration-publisher"),
      source = ChangeSource.eventsOf(PlayerEntity)
    ):
  def create(ctx: ConsumerContext)                               = new RegistrationPublisher
  override val outputSerializer: Option[Serializer[PlayerEvent]] =
    Some(Codecs.serializer[PlayerEvent]("player-event"))
  override def produces: Option[Publication] = Some(Publication("players"))

/** `engagement`: reads the topic `players` and keeps the email each registration was handed. */
final class Engagement extends Consumer[PlayerEvent, Nothing]:
  def onMessage(event: PlayerEvent): Effect =
    event match
      case r: PlayerRegistered => Engagement.handed.add(r.playerId -> r.email): Unit
      case _                   => ()
    effects.done()

object Engagement
    extends Consumer.Companion[Engagement, PlayerEvent, Nothing](
      componentId = ComponentId("engagement"),
      source = ChangeSource.fromTopic(
        "players",
        Codecs.serializer[PlayerEvent]("player-event"),
        StartFrom.Earliest
      )
    ):
  val handed: ConcurrentLinkedQueue[(String, Personal[String])] = ConcurrentLinkedQueue()
  def create(ctx: ConsumerContext)                              = new Engagement

/**
 * The endpoint of `edge.feature`: a player's registration answered, and a registration a caller
 * sent recorded, each with its personal field in the edge form, since no serializer is encoding.
 */
final class PlayersEndpoint(clients: com.thinkmorestupidless.ankka.http.EndpointClients)
    extends com.thinkmorestupidless.ankka.http.HttpEndpoint("/players"):

  private given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[Registration] =
    Codecs.make[Registration]
  private given com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[PlayerRegistered] =
    Codecs.make[PlayerRegistered]

  val acl: com.thinkmorestupidless.ankka.http.Acl = com.thinkmorestupidless.ankka.http.Acl.AllowAll

  private def player(id: String) = clients.componentClient.forEventSourcedEntity(EntityId(id))

  get("/{id}") { (id: String) =>
    player(id).call(PlayerEntity.registration).invoke()
  }

  postBody("/{id}") { (id: String, registered: PlayerRegistered) =>
    player(id).call(PlayerEntity.record).invoke(registered): Unit
    "recorded"
  }
