package com.thinkmorestupidless.ankka.http

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.thinkmorestupidless.ankka.core.{Codecs, CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.RuntimeExtension

import java.time.Instant
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

/**
 * A service's client keys (feature 054): the credentials it issues to the callers of its MCP
 * endpoint, one per caller, each with a subject its handlers read as the principal, a scope of
 * tools, a label and an expiry. Reached from a handler as `clients.clientKeys`.
 *
 * The service issues them because only the service knows who its callers are; the platform keeps
 * them, so that no service writes its own. A key is shown once, in what `issue` answers, and never
 * again: what is kept is a digest. Registering `McpRuntime()` (in `ankka-mcp`) is what makes these
 * work; without it every call is refused saying so.
 */
trait ClientKeys:

  /**
   * Issues a client key for `subject`, for the MCP endpoint at `endpoint` (implied when the service
   * has one), able to call the tools in `scope` (every tool when empty). Its `attributes` are what
   * a handler reads as the principal's claims: at most 16, each name and value at most 256
   * characters. Refused, naming what is wrong, with a `CommandError` of `BadRequest`.
   */
  final def issue(
      subject: String,
      expiry: Expiry,
      endpoint: Option[String] = None,
      scope: Set[String] = Set.empty,
      attributes: Map[String, String] = Map.empty,
      label: Option[String] = None
  ): IssuedClientKey =
    issueRequest(ClientKeyRequest(subject, Some(expiry), endpoint, scope, attributes, label))

  /** Every client key of the service, revoked ones included, never the key itself. */
  def list(): Vector[ClientKeySummary]

  /**
   * Revokes a key: refused on its next request on every instance. Idempotent; an unknown id is
   * `NotFound`.
   */
  def revoke(id: String): Unit

  /**
   * `issue`, as a request a process or a route decoded, in which the expiry may be missing: "none
   * given" is refused, and is not "never".
   */
  private[ankka] def issueRequest(request: ClientKeyRequest): IssuedClientKey

object ClientKeys:

  /** What `EndpointClients.clientKeys` is when the service registers no `McpRuntime`. */
  private[ankka] val unavailable: ClientKeys = new ClientKeys:
    private def refuse = throw CommandError(
      "client keys need the MCP runtime; add McpRuntime() and McpRuntime.descriptors to the service",
      ErrorCode.Unavailable
    )
    def list(): Vector[ClientKeySummary]                                        = refuse
    def revoke(id: String): Unit                                                = refuse
    private[ankka] def issueRequest(request: ClientKeyRequest): IssuedClientKey = refuse

/** When a client key stops being admitted. There is no default: a key's issuer says, every time. */
enum Expiry:
  case After(duration: FiniteDuration)
  case At(instant: Instant)
  case Never

object Expiry:

  /**
   * `{"after": <millis>}`, `{"at": "<instant>"}` or `{"never": true}`; `null` is no expiry given.
   */
  private final case class Wire(
      after: Option[Long] = None,
      at: Option[Instant] = None,
      never: Option[Boolean] = None
  )

  private val wire: JsonValueCodec[Wire] = Codecs.make[Wire]

  given JsonValueCodec[Expiry] = new JsonValueCodec[Expiry]:
    def nullValue: Expiry = null.asInstanceOf[Expiry]
    def encodeValue(x: Expiry, out: JsonWriter): Unit =
      wire.encodeValue(
        x match
          case After(duration) => Wire(after = Some(duration.toMillis))
          case At(instant)     => Wire(at = Some(instant))
          case Never           => Wire(never = Some(true))
        ,
        out
      )
    def decodeValue(in: JsonReader, default: Expiry): Expiry =
      wire.decodeValue(in, null) match
        case null                          => default
        case Wire(Some(after), None, None) => After(FiniteDuration(after, MILLISECONDS))
        case Wire(None, Some(at), None)    => At(at)
        case Wire(None, None, Some(true))  => Never
        case _ =>
          in.decodeError("an expiry is one of after (milliseconds), at (an instant) or never")

/** What `issue` answers: the key's id, and the key itself, shown this once. */
final case class IssuedClientKey(id: String, clientKey: String):
  override def toString: String = s"IssuedClientKey($id, <the key>)"

/** One client key as a listing shows it: never the key, never its digest. */
final case class ClientKeySummary(
    id: String,
    subject: String,
    attributes: Map[String, String],
    label: Option[String],
    endpoint: String,
    scope: Set[String],
    issuedAt: Instant,
    expiresAt: Option[Instant],
    lastUsed: Option[Instant],
    revokedAt: Option[Instant]
)

/** `issue`'s arguments, with the expiry optional so that "none given" can be refused. */
private[ankka] final case class ClientKeyRequest(
    subject: String,
    expiry: Option[Expiry],
    endpoint: Option[String] = None,
    scope: Set[String] = Set.empty,
    attributes: Map[String, String] = Map.empty,
    label: Option[String] = None
)

/** The extension that keeps a service's client keys: `McpRuntime`, in `ankka-mcp`. */
private[ankka] trait ClientKeysProvider extends RuntimeExtension:
  def clientKeys: ClientKeys
