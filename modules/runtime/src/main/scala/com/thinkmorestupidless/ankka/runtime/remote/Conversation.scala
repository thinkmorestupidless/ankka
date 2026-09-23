package com.thinkmorestupidless.ankka.runtime.remote

import com.thinkmorestupidless.ankka.core.effect.{Retention, StepOutcome}
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  ComponentKind,
  EntityId,
  Metadata,
  MethodName
}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.Future

/**
 * What crosses the boundary to a developer's process, as plain Scala values.
 *
 * These are *not* the generated protobuf messages: `runtime` does not see the `protocol` project,
 * so the remote hosts speak in these and the sidecar translates. That is the same inversion that
 * keeps `ComponentClient` in `sdk` over a transport `runtime` supplies.
 */
final case class Payload(contentType: String, manifest: String, data: Array[Byte])

object Payload:
  val Json: String   = "application/json"
  val Text: String   = "text/plain"
  val Binary: String = "application/octet-stream"

  /**
   * What the in-process serializers produce for a top-level primitive or record — see ENCODING.md.
   */
  def contentTypeFor(manifest: String): String = manifest match
    case "string" | "int" | "long" | "short" | "byte" | "double" | "float" | "boolean" |
        "duration-millis" =>
      Text
    case "done" | "unit" | "bytes"    => Binary
    case m if m.startsWith("option[") => Binary
    case _                            => Json

/**
 * `EntityProtocol.Invoke` carries bytes and metadata but no manifest, so a payload's manifest and
 * content type travel in the metadata under these keys — set by the callback service on the way in
 * and by the remote hosts on the way out.
 */
object PayloadKeys:
  val Manifest: String    = "ankka.manifest"
  val ContentType: String = "ankka.content-type"

/** A stored snapshot, or the current durable state, handed to the process on `open`. */
final case class Snapshot(sequence: Long, payload: Payload)

/** Opens one instance's conversation. `snapshot` absent means fresh, deleted or expired. */
final case class Init(
    kind: ComponentKind,
    componentId: ComponentId,
    entityId: EntityId,
    snapshot: Option[Snapshot]
)

final case class Command(
    id: Long,
    name: MethodName,
    payload: Payload,
    metadata: Metadata,
    snapshotRequested: Boolean
)

/** The three cases of `core.effect.Outcome`, with the reply already encoded. */
enum RemoteOutcome:
  case Reply(payload: Payload, metadata: Metadata)
  case NoReply
  case Error(error: CommandError)

/**
 * What a process answers a command with. One shape for every stateful kind: an event sourced reply
 * carries `events`, a key value or workflow reply carries `newState`, a workflow command may also
 * `transition`.
 */
final case class Reply(
    commandId: Long,
    events: Vector[Payload],
    newState: Option[Payload],
    transition: Option[StepOutcome.TransitionTo],
    retention: Option[Retention],
    outcome: RemoteOutcome,
    snapshot: Option[Payload]
)

/** What a process answers `runStep` with. */
final case class StepReply(commandId: Long, newState: Option[Payload], next: StepOutcome)

/** A fault in the process — the handler threw, the payload could not be decoded. Not a refusal. */
final case class ProcessFailure(commandId: Long, error: CommandError)

/** The process broke a rule of the protocol. The instance is failed and restarted. */
final class ProtocolViolation(message: String) extends RuntimeException(message)

/** One loaded instance's conversation, opened by the sidecar and closed by it on stop. */
trait InstanceSession:
  /** Replays one journaled event to the process, before the first command. */
  def event(sequence: Long, payload: Payload): Unit

  /** Exactly one in flight per session; the host enforces that, the transport relies on it. */
  def command(cmd: Command): Future[Either[ProcessFailure, Reply]]

  /**
   * `input` is what the process itself put in the transition — the serialized `Payload` message,
   * opaque to the runtime — so the manifest and content type survive the engine's journal.
   */
  def runStep(
      id: Long,
      step: String,
      input: Option[Array[Byte]]
  ): Future[Either[ProcessFailure, StepReply]]

  /** Passivation or a violation. The process releases the instance's state when it sees this. */
  def close(): Unit

/**
 * `event` is absent when the source was deleted: the process decides whether the row goes with it
 * (the default) or stays as a tombstone. The subject and sequence travel in `metadata`.
 */
final case class ViewRequest(
    componentId: ComponentId,
    event: Option[Payload],
    metadata: Metadata,
    row: Option[Payload]
)

enum ViewOutcome:
  case UpdateRow(row: Payload)
  case DeleteRow
  case Ignore

/** `message` is absent when the source was deleted. */
final case class ConsumerRequest(
    componentId: ComponentId,
    message: Option[Payload],
    metadata: Metadata
)

enum ConsumerOutcome:
  case Produce(payload: Payload, metadata: Metadata)
  case Done
  case Ignore

/**
 * `payload` is what the process itself scheduled — the serialized `Payload` message, opaque to the
 * runtime, exactly as a workflow step's input — so the manifest and content type survive the timer
 * table. Empty when the schedule carried none.
 */
final case class TimedActionRequest(
    componentId: ComponentId,
    name: MethodName,
    payload: Array[Byte],
    metadata: Metadata
)

final case class RemotePrincipal(
    subject: String,
    name: Option[String],
    email: Option[String],
    emailVerified: Boolean,
    roles: Set[String]
)

/** An HTTP request the sidecar's router matched to a declared route, forwarded whole. */
final case class HttpForward(
    endpointId: String,
    routeId: String,
    pathArgs: Vector[String],
    query: Vector[(String, String)],
    headers: Vector[(String, String)],
    contentType: String,
    body: Array[Byte],
    principal: Option[RemotePrincipal],
    metadata: Metadata
)

final case class HttpResult(
    status: Int,
    contentType: String,
    body: Array[Byte],
    headers: Vector[(String, String)]
)

/**
 * Everything a remote host needs from the developer's process.
 *
 * Declared here, implemented by the sidecar over gRPC. A remote host never sees a stub or a
 * channel; it sees this. `reachable` is read by readiness at most once a second.
 */
trait Conversation:
  def open(init: Init): InstanceSession

  def handleView(request: ViewRequest): Future[ViewOutcome]
  def handleConsumer(request: ConsumerRequest): Future[ConsumerOutcome]
  def invokeTimedAction(request: TimedActionRequest): Future[Either[CommandError, Unit]]

  def handleHttp(request: HttpForward): Future[Either[ProcessFailure, HttpResult]]
  def handleHttpStream(request: HttpForward): Source[String, NotUsed]

  def reachable(): Boolean
