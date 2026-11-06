package shoppingcart.players

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Players with personal fields, which the cart image runs instead of the cart when the descriptor
 * sets `CART_PLAYERS` (feature 042): how the platform's erasure suites deploy a service that keeps
 * a data subject's personal fields, in three roles.
 *
 *   - `players`: a player's registration, its email and name personal fields of `player/<id>`, a
 *     view of profiles that can be looked up by email, and, with a broker, every registration
 *     published to the topic `players`.
 *   - `engagement`: a view of the registrations it reads from its own project's topic `players`.
 *   - `cardholders`: a view of the registrations it reads from the topic `players` of another
 *     project, `CART_PLAYERS_FROM` (`brand` unless it says otherwise).
 *
 * Every read answers a personal field as its value, or `erased`.
 */
final case class RegisterPlayer(email: String, name: String, currency: String)

sealed trait PlayerEvent
final case class PlayerRegistered(
    playerId: String,
    email: Personal[String],
    name: Personal[String],
    currency: String
) extends PlayerEvent

final case class Player(
    email: Option[Personal[String]],
    name: Option[Personal[String]],
    currency: Option[String]
)

/** What every read answers: a personal field as its value, `erased`, or `none`. */
final case class PlayerRead(playerId: String, email: String, name: String, currency: String)

object RegisterPlayer:
  given JsonValueCodec[RegisterPlayer] = Codecs.make[RegisterPlayer]
  given Serializer[RegisterPlayer]     = Codecs.serializer[RegisterPlayer]("register-player")

object PlayerRead:
  given JsonValueCodec[PlayerRead]                = Codecs.make[PlayerRead]
  given Serializer[PlayerRead]                    = Codecs.serializer[PlayerRead]("player-read")
  def of(field: Option[Personal[String]]): String = field.fold("none")(_.orElse("erased"))

final class PlayerEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Player, PlayerEvent]:

  private val playerId = context.entityId
  private val subject  = DataSubject(s"player/$playerId")

  def emptyState: Player = Player(None, None, None)

  def applyEvent(event: PlayerEvent): Player = event match
    case r: PlayerRegistered => Player(Some(r.email), Some(r.name), Some(r.currency))

  def register(command: RegisterPlayer): Effect[Done] =
    effects
      .persist(
        PlayerRegistered(
          playerId,
          Personal(subject, command.email),
          Personal(subject, command.name),
          command.currency
        )
      )
      .thenReply(_ => Done)

  def read: ReadOnlyEffect[PlayerRead] =
    effects.reply(
      PlayerRead(
        playerId,
        PlayerRead.of(currentState.email),
        PlayerRead.of(currentState.name),
        currentState.currency.getOrElse("none")
      )
    )

object PlayerEntity
    extends EventSourcedEntity.Companion[PlayerEntity, Player, PlayerEvent](
      componentId = ComponentId("player"),
      stateSerializer = Codecs.serializer[Player]("player"),
      eventSerializer = Codecs.serializer[PlayerEvent]("player-event")
    ):
  def create(context: EventSourcedEntityContext) = new PlayerEntity(context)

  // Every event is followed by a snapshot, so a restored database holds a sealed snapshot too.
  override def snapshotEvery: Option[Int] = Some(1)

  val register = command("register")(_.register)
  val read     = query("read")(_.read)

/** One row per player, its email marked for lookup. */
final case class ProfileRow(playerId: String, email: Personal[String], name: Personal[String])

final class ProfilesView extends View[PlayerEvent, ProfileRow]:
  def onChange(event: PlayerEvent): Effect = event match
    case r: PlayerRegistered => effects.updateRow(ProfileRow(r.playerId, r.email, r.name))

object Profiles
    extends View.Companion[ProfilesView, PlayerEvent, ProfileRow](
      componentId = ComponentId("profiles"),
      source = ChangeSource.eventsOf(PlayerEntity),
      rowSerializer = Codecs.serializer[ProfileRow]("profile-row")
    ):
  def create(ctx: ViewComponentContext) = new ProfilesView
  lookup(_.email)
  val byPlayer =
    query("by-player")(s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player_id")
  val byEmail =
    query("by-email")(s"SELECT payload FROM $table WHERE payload::jsonb->>'email' = :email")

/** Publishes every registration to the topic `players`. */
final class RegistrationPublisher extends Consumer[PlayerEvent, PlayerEvent]:
  def onMessage(event: PlayerEvent): Effect = effects.produce(event)

object RegistrationPublisher
    extends Consumer.Companion[RegistrationPublisher, PlayerEvent, PlayerEvent](
      componentId = ComponentId("registration-publisher"),
      source = ChangeSource.eventsOf(PlayerEntity)
    ):
  def create(ctx: ConsumerContext)                               = new RegistrationPublisher
  override val outputSerializer: Option[Serializer[PlayerEvent]] =
    Some(Codecs.serializer[PlayerEvent]("player-event"))
  override def produces: Option[Publication] = Some(Publication("players"))

/** A player as a reader of the topic keeps one: its name, sealed as it was published. */
final case class NameRow(playerId: String, name: Personal[String])

final class NamesView extends View[PlayerEvent, NameRow]:
  def onChange(event: PlayerEvent): Effect = event match
    case r: PlayerRegistered => effects.updateRow(NameRow(r.playerId, r.name))

/** `engagement`: the registrations of its own project's topic. */
object Engaged
    extends View.Companion[NamesView, PlayerEvent, NameRow](
      componentId = ComponentId("engaged"),
      source = ChangeSource.fromTopic(
        "players",
        Codecs.serializer[PlayerEvent]("player-event"),
        StartFrom.Earliest
      ),
      rowSerializer = Codecs.serializer[NameRow]("name-row")
    ):
  def create(ctx: ViewComponentContext) = new NamesView
  val byPlayer                          =
    query("by-player")(s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player_id")

/** `cardholders`: the registrations of another project's topic, read under its grant. */
object Cardholdings
    extends View.Companion[NamesView, PlayerEvent, NameRow](
      componentId = ComponentId("cardholdings"),
      source = ChangeSource.fromTopic(
        sys.env.get("CART_PLAYERS_FROM").filter(_.nonEmpty).getOrElse("brand"),
        "players",
        Codecs.serializer[PlayerEvent]("player-event"),
        StartFrom.Earliest
      ),
      rowSerializer = Codecs.serializer[NameRow]("name-row")
    ):
  def create(ctx: ViewComponentContext) = new NamesView
  val byPlayer                          =
    query("by-player")(s"SELECT payload FROM $table WHERE payload::jsonb->>'playerId' = :player_id")

/** Each role's reads and, for `players`, the registration. */
final class PlayersEndpoint(role: String, clients: EndpointClients)
    extends HttpEndpoint("/players"):

  val acl: Acl = Acl.AllowAll

  private def notFound(what: String) = CommandError(what, ErrorCode.NotFound)

  private def names(rows: Vector[NameRow], id: String): PlayerRead =
    rows.headOption
      .map(r => PlayerRead(id, "none", r.name.orElse("erased"), "none"))
      .getOrElse(throw notFound(s"no registration of '$id' has been read"))

  postBody("/{id}") { (id: String, command: RegisterPlayer) =>
    clients.componentClient
      .forEventSourcedEntity(EntityId(id))
      .call(PlayerEntity.register)
      .invoke(command): Unit
    "registered"
  }

  get("/{id}") { (id: String) =>
    val read: PlayerRead = role match
      case "players" =>
        clients.componentClient.forEventSourcedEntity(EntityId(id)).call(PlayerEntity.read).invoke()
      case "engagement" =>
        names(clients.viewClient.forView(Engaged).ask(Engaged.byPlayer, "player_id" -> id), id)
      case _ =>
        names(
          clients.viewClient.forView(Cardholdings).ask(Cardholdings.byPlayer, "player_id" -> id),
          id
        )
    read
  }

  get("/{id}/profile") { (id: String) =>
    clients.viewClient
      .forView(Profiles)
      .ask(Profiles.byPlayer, "player_id" -> id)
      .headOption
      .map(r => PlayerRead(id, r.email.orElse("erased"), r.name.orElse("erased"), "none"))
      .getOrElse(throw notFound(s"no profile of '$id'"))
  }

  /** The players whose email is the one asked, found through the email's lookup token. */
  get("/by-email/{email}") { (email: String) =>
    clients.viewClient
      .forView(Profiles)
      .ask(Profiles.byEmail, "email" -> email)
      .map(_.playerId)
      .mkString(",")
  }

object PlayersService:

  /** Runs the role `CART_PLAYERS` names until the process is stopped. */
  def run(role: String): Unit =
    val base    = Ankka.service
    val builder = role match
      case "players" =>
        val players = base.register(PlayerEntity.descriptor).register(Profiles.descriptor)
        // A consumer that publishes is refused at start without a broker to publish to.
        if sys.env.get(ProjectionRuntime.KafkaEnvVar).exists(_.nonEmpty) then
          players.register(RegistrationPublisher.descriptor)
        else players
      case "engagement"  => base.register(Engaged.descriptor)
      case "cardholders" => base.register(Cardholdings.descriptor)
      case other         =>
        throw IllegalArgumentException(
          s"CART_PLAYERS is '$other'; it must be players, engagement or cardholders"
        )
    val service = builder
      .withExtension(ProjectionRuntime.fromEnv())
      .withExtension(HttpServer.of(clients => PlayersEndpoint(role, clients)))
      .start()
    sys.addShutdownHook(service.terminate())
    scala.concurrent.Await
      .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
