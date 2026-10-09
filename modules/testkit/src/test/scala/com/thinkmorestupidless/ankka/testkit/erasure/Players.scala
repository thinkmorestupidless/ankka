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
