package com.thinkmorestupidless.ankka.testkit.erasure

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.core.personal.Personal
import com.thinkmorestupidless.ankka.sdk.*

import java.time.LocalDate

/** What a caller sends to register a player: plain values; the entity knows whose they are. */
final case class Registration(email: String, name: String, dateOfBirth: LocalDate, currency: String)

/** What a caller reads back: the values, or `<erased>`. */
final case class PlayerRead(
    email: String,
    name: String,
    dateOfBirth: String,
    currency: String,
    deposits: Int
)

enum PlayerEvent:
  case Registered(
      playerId: String,
      email: Personal[String],
      name: Personal[String],
      dateOfBirth: Personal[LocalDate],
      currency: String,
      registeredAt: Long
  )
  case Deposited(amount: Int, cardHolder: Personal[String])

final case class Player(
    email: Option[Personal[String]],
    name: Option[Personal[String]],
    dateOfBirth: Option[Personal[LocalDate]],
    currency: String,
    deposits: Int
)

final class PlayerEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Player, PlayerEvent]:
  private val subject = s"player/${context.entityId}"

  def emptyState: Player = Player(None, None, None, "", 0)

  def applyEvent(event: PlayerEvent): Player = event match
    case PlayerEvent.Registered(_, email, name, dob, currency, _) =>
      currentState.copy(
        email = Some(email),
        name = Some(name),
        dateOfBirth = Some(dob),
        currency = currency
      )
    case PlayerEvent.Deposited(amount, _) =>
      currentState.copy(deposits = currentState.deposits + amount)

  def register(request: Registration): Effect[Done] =
    effects
      .persist(
        PlayerEvent.Registered(
          context.entityId,
          Personal.present(subject, request.email),
          Personal.present(subject, request.name),
          Personal.present(subject, request.dateOfBirth),
          request.currency,
          1_700_000_000_000L
        )
      )
      .thenReply(_ => Done)

  def deposit(amount: Int): Effect[Done] =
    effects
      .persist(PlayerEvent.Deposited(amount, Personal.present(subject, "Card Holder")))
      .thenReply(_ => Done)

  def get: ReadOnlyEffect[PlayerRead] =
    def text[A](p: Option[Personal[A]]) = p.flatMap(_.toOption).fold("<erased>")(_.toString)
    effects.reply(
      PlayerRead(
        text(currentState.email),
        text(currentState.name),
        text(currentState.dateOfBirth),
        currentState.currency,
        currentState.deposits
      )
    )

object PlayerEntity
    extends EventSourcedEntity.Companion[PlayerEntity, Player, PlayerEvent](
      componentId = ComponentId("players"),
      stateSerializer = Codecs.serializer[Player]("player"),
      eventSerializer = Codecs.serializer[PlayerEvent]("player-event")
    ):
  def create(context: EventSourcedEntityContext) = new PlayerEntity(context)

  /** A snapshot every second event, so a scenario can see one kept. */
  override def snapshotEvery: Option[Int] = Some(2)

  given registrationSerializer: Serializer[Registration] =
    Codecs.serializer[Registration]("registration")
  given readSerializer: Serializer[PlayerRead] = Codecs.serializer[PlayerRead]("player-read")

  val register = command("register")(_.register)
  val deposit  = command("deposit")(_.deposit)
  val get      = query("get")(_.get)

/** A row per player, its email marked for lookup. */
final case class ProfileRow(playerId: String, email: Personal[String], currency: String)

final class ProfilesView extends View[PlayerEvent, ProfileRow]:
  def onChange(event: PlayerEvent): Effect = event match
    case PlayerEvent.Registered(id, email, _, _, currency, _) =>
      effects.updateRow(
        ProfileRow(id, email.toOption.fold(email)(Personal.lookup(email.subject, _)), currency)
      )
    case _ => effects.ignore()

object Profiles
    extends View.Companion[ProfilesView, PlayerEvent, ProfileRow](
      componentId = ComponentId("profiles"),
      source = ChangeSource.eventsOf(PlayerEntity),
      rowSerializer = Codecs.serializer[ProfileRow]("profile-row")
    ):
  def create(ctx: ViewComponentContext) = new ProfilesView

  /**
   * A player by email, matched by the email's lookup token: nothing else can match a ciphertext.
   */
  val byEmail = query("by-email")(
    "SELECT payload FROM ankka_view_profiles WHERE payload::jsonb->'email'->>'lookup' = :email"
  )

/** A player's settings: a key value entity's state with a personal field. */
final case class Settings(email: Option[Personal[String]], theme: String)

final class SettingsEntity(context: KeyValueEntityContext) extends KeyValueEntity[Settings]:
  def emptyState: Settings = Settings(None, "light")
  def setEmail(email: String): Effect[Done] =
    effects
      .updateState(
        currentState.copy(email = Some(Personal.present(s"player/${context.entityId}", email)))
      )
      .thenReply(_ => Done)
  def setTheme(theme: String): Effect[Done] =
    effects.updateState(currentState.copy(theme = theme)).thenReply(_ => Done)
  def email: ReadOnlyEffect[String] =
    effects.reply(currentState.email.flatMap(_.toOption).getOrElse("<erased>"))

object SettingsEntity
    extends KeyValueEntity.Companion[SettingsEntity, Settings](
      componentId = ComponentId("player-settings"),
      stateSerializer = Codecs.serializer[Settings]("player-settings")
    ):
  def create(context: KeyValueEntityContext) = new SettingsEntity(context)
  val setEmail                               = command("set-email")(_.setEmail)
  val setTheme                               = command("set-theme")(_.setTheme)
  val email                                  = query("email")(_.email)

/** Publishes every registration to the topic `players`, as written: personal fields encrypted. */
final class PlayersPublisher extends Consumer[PlayerEvent, PlayerEvent]:
  def onMessage(event: PlayerEvent): Effect = event match
    case registered: PlayerEvent.Registered => effects.produce(registered)
    case _                                  => effects.ignore()

object PlayersPublisher
    extends Consumer.Companion[PlayersPublisher, PlayerEvent, PlayerEvent](
      componentId = ComponentId("players-publisher"),
      source = ChangeSource.eventsOf(PlayerEntity)
    ):
  def create(ctx: ConsumerContext) = new PlayersPublisher
  override val outputSerializer: Option[Serializer[PlayerEvent]] = Some(
    PlayerEntity.eventSerializer
  )
  override val produceTo: Option[String] = Some("players")

/** A consumer of the topic `players`, keeping what it was handed. */
final class Engagement extends Consumer[PlayerEvent, Nothing]:
  def onMessage(event: PlayerEvent): Effect =
    event match
      case PlayerEvent.Registered(id, email, _, _, _, _) => Engagement.seen.put(id, email): Unit
      case _                                             => ()
    effects.done()

object Engagement
    extends Consumer.Companion[Engagement, PlayerEvent, Nothing](
      componentId = ComponentId("engagement"),
      source = ChangeSource.fromTopic("players", PlayerEntity.eventSerializer, StartFrom.Earliest)
    ):
  def create(ctx: ConsumerContext) = new Engagement
  val seen = java.util.concurrent.ConcurrentHashMap[String, Personal[String]]()
