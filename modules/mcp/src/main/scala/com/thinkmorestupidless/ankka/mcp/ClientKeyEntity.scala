package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.{ClientKeySummary, Expiry}
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/** What a client key's issuer asks the entity to keep: never the key, only its digest. */
final case class IssueClientKey(
    digest: String,
    subject: String,
    attributes: Map[String, String],
    label: Option[String],
    endpoint: String,
    scope: Vector[String],
    issuedAt: Instant,
    /** Optional only so that "none given" reaches the rule that refuses it; see `Expiry`. */
    expiry: Option[Expiry]
)

final case class RecordClientKeyUse(at: Instant)
final case class RevokeClientKey(at: Instant)

/**
 * A client key's journal. Pinned by `ClientKeyEventsSuite`: never change a shape, add beside it.
 */
enum ClientKeyEvent:
  case ClientKeyIssued(
      digest: String,
      subject: String,
      attributes: Map[String, String],
      label: Option[String],
      endpoint: String,
      scope: Vector[String],
      issuedAt: Instant,
      /** `None` is "never". */
      expiresAt: Option[Instant]
  )
  case ClientKeyUsed(at: Instant)
  case ClientKeyRevoked(at: Instant)

final case class ClientKeyState(
    id: String,
    digest: String = "",
    subject: String = "",
    attributes: Map[String, String] = Map.empty,
    label: Option[String] = None,
    endpoint: String = "",
    scope: Vector[String] = Vector.empty,
    issuedAt: Option[Instant] = None,
    expiresAt: Option[Instant] = None,
    lastUsed: Option[Instant] = None,
    revokedAt: Option[Instant] = None
):
  def exists: Boolean = issuedAt.isDefined

  def summary: ClientKeySummary =
    ClientKeySummary(
      id,
      subject,
      attributes,
      label,
      endpoint,
      scope.toSet,
      issuedAt.getOrElse(Instant.EPOCH),
      expiresAt,
      lastUsed,
      revokedAt
    )

/**
 * The rules a key is issued under, said once for the entity and for `ClientKeys.issue` before it.
 */
private[mcp] object ClientKeyRules:

  val MaxAttributes = 16
  val MaxLength     = 256

  /** How much later a use must be than the recorded one to be written down. */
  val UseGranularity: java.time.Duration = java.time.Duration.ofMinutes(1)

  /** The first thing wrong with a key as asked for, in the words the contract gives. */
  def problem(
      subject: String,
      attributes: Map[String, String],
      expiry: Option[Expiry],
      now: Instant
  ): Option[String] =
    if subject.trim.isEmpty then Some("the subject is blank")
    else
      expiry match
        case None         => Some("the expiry is required: After, At or Never")
        case Some(stated) =>
          expiresAt(stated, now) match
            case Some(at) if !at.isAfter(now) => Some(s"the expiry $at is in the past")
            case _                            => attributeProblem(attributes)

  private def attributeProblem(attributes: Map[String, String]): Option[String] =
    def shown(name: String) = if name.length > 24 then s"${name.take(24)}…" else name
    if attributes.sizeIs > MaxAttributes then
      Some(s"${attributes.size} attributes; at most $MaxAttributes")
    else
      attributes.toVector.sortBy(_._1).collectFirst {
        case (name, _) if name.length > MaxLength =>
          s"the attribute '${shown(name)}' is ${name.length} characters; at most $MaxLength"
        case (name, value) if value.length > MaxLength =>
          s"the value of the attribute '${shown(name)}' is ${value.length} characters; at most $MaxLength"
      }

  /** When a key issued `now` with `expiry` stops: `None` is never. */
  def expiresAt(expiry: Expiry, now: Instant): Option[Instant] = expiry match
    case Expiry.After(duration) => Some(now.plusMillis(duration.toMillis))
    case Expiry.At(instant)     => Some(instant)
    case Expiry.Never           => None

/**
 * One client key (feature 054), event sourced under the platform's own component id. Its id is the
 * key's id; its journal is what every instance's `ClientKeyIndex` replays and follows, so a key is
 * known everywhere without anyone asking the database on a request.
 *
 * It keeps a digest, never the key: the key is shown once, by `ClientKeys.issue`, and a journal is
 * readable by anything that can read the database. The clock is the caller's: every command carries
 * the instant it is about, so replay and the rules agree.
 */
final class ClientKeyEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[ClientKeyState, ClientKeyEvent]:
  import ClientKeyEvent.*

  def emptyState: ClientKeyState = ClientKeyState(context.entityId)

  def applyEvent(event: ClientKeyEvent): ClientKeyState = event match
    case ClientKeyIssued(
          digest,
          subject,
          attributes,
          label,
          endpoint,
          scope,
          issuedAt,
          expiresAt
        ) =>
      currentState.copy(
        digest = digest,
        subject = subject,
        attributes = attributes,
        label = label,
        endpoint = endpoint,
        scope = scope,
        issuedAt = Some(issuedAt),
        expiresAt = expiresAt
      )
    case ClientKeyUsed(at)    => currentState.copy(lastUsed = Some(at))
    case ClientKeyRevoked(at) => currentState.copy(revokedAt = Some(at))

  def issue(request: IssueClientKey): Effect[Done] =
    if currentState.exists then
      effects.error(s"client key '${context.entityId}' already exists", ErrorCode.Conflict)
    else if request.digest.isEmpty then effects.error("a client key needs a digest")
    else if request.endpoint.isEmpty then effects.error("a client key needs an MCP endpoint")
    else
      ClientKeyRules.problem(
        request.subject,
        request.attributes,
        request.expiry,
        request.issuedAt
      ) match
        case Some(problem) => effects.error(problem, ErrorCode.BadRequest)
        case None          =>
          effects
            .persist(
              ClientKeyIssued(
                request.digest,
                request.subject,
                request.attributes,
                request.label,
                request.endpoint,
                request.scope,
                request.issuedAt,
                request.expiry.flatMap(ClientKeyRules.expiresAt(_, request.issuedAt))
              )
            )
            .thenReply(_ => Done)

  /**
   * Records a use, if it is news: a minute or more after the recorded one. Every instance touches
   * its own copy on every request and sweeps at most once an interval; several reporting the same
   * minute produce one event between them.
   */
  def recordUse(use: RecordClientKeyUse): Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.revokedAt.isDefined then effects.reply(Done)
    else if currentState.lastUsed.exists(last =>
        use.at.isBefore(last.plus(ClientKeyRules.UseGranularity))
      )
    then effects.reply(Done)
    else effects.persist(ClientKeyUsed(use.at)).thenReply(_ => Done)

  def revoke(request: RevokeClientKey): Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.revokedAt.isDefined then effects.reply(Done)
    else effects.persist(ClientKeyRevoked(request.at)).thenReply(_ => Done)

  def get: ReadOnlyEffect[ClientKeySummary] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.summary)

  private def notFoundMessage = s"no such client key '${context.entityId}'"
  private def notFound        = effects.error(notFoundMessage, ErrorCode.NotFound)

object ClientKeyEntity
    extends EventSourcedEntity.Companion[ClientKeyEntity, ClientKeyState, ClientKeyEvent](
      componentId = ComponentId("ankka-client-key"),
      stateSerializer = Codecs.serializer[ClientKeyState]("client-key"),
      eventSerializer = Codecs.serializer[ClientKeyEvent]("client-key-event")
    ):

  /** The platform's own: it keeps what the platform needs, and no service wrote it. */
  override private[ankka] def platform: Boolean = true

  given Serializer[IssueClientKey]     = Codecs.serializer[IssueClientKey]("issue-client-key")
  given Serializer[RecordClientKeyUse] =
    Codecs.serializer[RecordClientKeyUse]("record-client-key-use")
  given Serializer[RevokeClientKey]  = Codecs.serializer[RevokeClientKey]("revoke-client-key")
  given Serializer[ClientKeySummary] = Codecs.serializer[ClientKeySummary]("client-key-summary")

  def create(context: EventSourcedEntityContext) = new ClientKeyEntity(context)

  val issue     = command("issue")(_.issue)
  val recordUse = command("record-use")(_.recordUse)
  val revoke    = command("revoke")(_.revoke)
  val get       = query("get")(_.get)
