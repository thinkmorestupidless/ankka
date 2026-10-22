package com.thinkmorestupidless.ankka.runtime.remote

import com.thinkmorestupidless.ankka.sdk.WorkflowLifecycle
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
import scala.concurrent.duration.FiniteDuration

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
    snapshot: Option[Snapshot],
    /** This installation, for a replicated entity (feature 051). */
    installation: Option[String] = None
)

final case class Command(
    id: Long,
    name: MethodName,
    payload: Payload,
    metadata: Metadata,
    snapshotRequested: Boolean,
    /** The installation handling the command, for a replicated entity (feature 051). */
    installation: Option[String] = None,
    /** Whether that installation is the entity's home; true for an entity not replicated. */
    atHome: Boolean = true,
    /** Who acted, and the installation the command was forwarded from (spec 057; protocol 1.22). */
    attribution: Option[String] = None,
    forwardedFrom: Option[String] = None
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
    snapshot: Option[Payload],
    /** A replicated entity's handover or change to its replication set (feature 051). */
    replication: Option[com.thinkmorestupidless.ankka.core.effect.ReplicationChange] = None
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

  /**
   * Replays one journaled event, naming the installation that wrote it (feature 051). A
   * conversation that carries no installation sends the event alone.
   */
  def event(sequence: Long, payload: Payload, installation: Option[String]): Unit =
    val _ = installation
    event(sequence, payload)

  /**
   * Replays one journaled event with who acted on the command that wrote it and where that command
   * was forwarded from (spec 057; protocol 1.22). A conversation that carries neither sends the
   * event as above.
   */
  def event(
      sequence: Long,
      payload: Payload,
      installation: Option[String],
      attribution: Option[String],
      forwardedFrom: Option[String]
  ): Unit =
    val _ = (attribution, forwardedFrom)
    if installation.isEmpty then event(sequence, payload)
    else event(sequence, payload, installation)

  /** Exactly one in flight per session; the host enforces that, the transport relies on it. */
  def command(cmd: Command): Future[Either[ProcessFailure, Reply]]

  /**
   * `input` is what the process itself put in the transition — the serialized `Payload` message,
   * opaque to the runtime — so the manifest and content type survive the engine's journal.
   */
  def runStep(
      id: Long,
      step: String,
      input: Option[Array[Byte]],
      metadata: Metadata
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
    row: Option[Payload],
    /** A keyed view's change: the component it came from. A keyed view is sent no row. */
    sourceId: Option[ComponentId] = None,
    /** A change from a workflow: where it stood once the effect was applied (protocol 1.16). */
    standing: Option[WorkflowLifecycle] = None
)

enum ViewOutcome:
  case UpdateRow(row: Payload)
  case DeleteRow
  case Ignore

  /** A keyed view's answer: rows written (`Some`) and deleted (`None`) by key, in order. */
  case Rows(changes: Vector[(String, Option[Payload])])

/** `message` is absent when the source was deleted. */
final case class ConsumerRequest(
    componentId: ComponentId,
    message: Option[Payload],
    metadata: Metadata,
    /** A change from a workflow: where it stood once the effect was applied (protocol 1.16). */
    standing: Option[WorkflowLifecycle] = None
)

/** One message of several a consumer produced. `key` absent means the message's subject. */
final case class ProducedMessage(payload: Payload, metadata: Metadata, key: Option[String])

enum ConsumerOutcome:
  case Produce(payload: Payload, metadata: Metadata)

  /** Protocol 1.3: published in order, and the change is handled when all are accepted. */
  case ProduceAll(messages: Vector[ProducedMessage])
  case Done
  case Ignore

/**
 * The version of the protocol this runtime speaks, and where it says so to a consumer.
 *
 * Written here for the runtime and the sidecar, and once more in `controlplane-api`
 * (`Protocol.version`), which cannot see this module. A consumer's request carries it as metadata
 * because a runtime that does not know a reply drops it: an SDK reads the entry before it answers
 * with anything an earlier runtime would not understand.
 */
/** One part of a stream route's answer. */
enum StreamPart:
  case Text(text: String)

  /** A server-sent event under a name of its own; `data` is JSON text on one line. */
  case Event(name: String, data: String)

object WireProtocol:
  val Version: String     = "1.22"
  val MetadataKey: String = "ankka.protocol"

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

/** One request to plan: which handler, on which session, with what. */
final case class PlanRequest(
    componentId: ComponentId,
    sessionId: String,
    name: MethodName,
    payload: Payload,
    metadata: Metadata
)

/**
 * An `AgentEffect` as the process described it: names, not values. Tools and guardrails are
 * resolved against what discovery declared; the model against what the sidecar has configured.
 */
final case class RemotePlan(
    model: Option[String],
    system: Option[String],
    user: Option[String],
    context: Vector[String],
    sessionMemory: Boolean,
    tools: Vector[String],
    jsonShape: Option[String],
    guardrails: Vector[String],
    failure: Option[CommandError]
)

enum GuardrailStage:
  case Input, Output

  /** What an MCP server's tool answered (protocol 1.11), and which tool, when it is known. */
  case Result(tool: Option[String])

final case class RemotePrincipal(
    subject: String,
    name: Option[String],
    email: Option[String],
    emailVerified: Boolean,
    roles: Set[String],
    claims: Map[String, String] = Map.empty,
    issuer: Option[String] = None
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
    metadata: Metadata,
    caller: RemoteCaller = RemoteCaller.Local
)

/**
 * Which workload sent a forwarded request; `http.Caller` in plain values, so `runtime` needs no
 * `http`.
 */
enum RemoteCaller:
  case Gateway
  case Service(project: String, name: String)

  /** A registered machine, proven by a token the control plane issued (protocol 1.15). */
  case Machine(organization: String, name: String)
  case Local

/**
 * A tool of an MCP endpoint the process declared (protocol 1.20), to run: the sidecar has answered
 * the protocol, applied the ACL and checked `argumentsJson` against the tool's schema. The
 * principal is the credential's, never the credential.
 */
final case class RemoteMcpCall(
    endpointId: String,
    tool: String,
    argumentsJson: String,
    principal: Option[RemotePrincipal],
    caller: RemoteCaller,
    metadata: Metadata
)

/** What a tool answered: its text, and its structured content when the result is a record. */
final case class RemoteMcpAnswer(text: String, structuredJson: Option[String])

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
object Conversation:
  /** A service whose components run in the runtime's own JVM: no conversation at all. */
  val Embedded: String = "embedded"
  val Process: String  = "process"
  val Module: String   = "module"

trait Conversation:

  /**
   * How the developer's program is hosted, as the record of a secret's read names it: `process` for
   * a process beside the sidecar, `module` for a WebAssembly module the runtime loaded.
   */
  def hosting: String = Conversation.Process

  def open(init: Init): InstanceSession

  def handleView(request: ViewRequest): Future[ViewOutcome]
  def handleConsumer(request: ConsumerRequest): Future[ConsumerOutcome]
  def invokeTimedAction(request: TimedActionRequest): Future[Either[CommandError, Unit]]

  def plan(request: PlanRequest): Future[Either[ProcessFailure, RemotePlan]]

  /** Runs a tool in the process; `Left` is a message for the model, as `FunctionTool.invoke`. */
  def invokeTool(
      componentId: ComponentId,
      sessionId: String,
      tool: String,
      argumentsJson: String,
      metadata: Metadata
  ): Future[Either[String, String]]

  /**
   * Checks one guardrail in the process. `metadata` is what the process is told about the check:
   * the trace, and the handler whose call it is, so a call the guardrail makes is that handler's.
   */
  def checkGuardrail(
      componentId: ComponentId,
      sessionId: String,
      guardrail: String,
      stage: GuardrailStage,
      text: String,
      metadata: Metadata
  ): Future[Either[String, Unit]]

  /**
   * Asks the process to check an autonomous agent's result: decode it as the task type's result and
   * run the type's rules. A rule that threw fails the future, which the agent treats as a failed
   * iteration rather than as a verdict — the process did not decide anything. `metadata` is what
   * the process is told about the check, as for a guardrail: a call a rule makes is the
   * iteration's.
   */
  def checkTaskResult(
      componentId: ComponentId,
      taskId: String,
      taskType: String,
      resultJson: String,
      metadata: Metadata
  ): Future[TaskResultVerdict]

  def handleHttp(request: HttpForward): Future[Either[ProcessFailure, HttpResult]]
  def handleHttpStream(request: HttpForward): Source[String, NotUsed]

  /**
   * A stream route's parts as the process sends them: text, or (protocol 1.11) a named event whose
   * data is JSON. A conversation whose process sends only text — a module's — need not override it.
   */
  def handleHttpEvents(request: HttpForward): Source[StreamPart, NotUsed] =
    handleHttpStream(request).map(StreamPart.Text(_))

  /**
   * Opens a socket route's socket in the process: `request` is the request that opened it, with no
   * body. A conversation that cannot hold a socket — a module's — refuses, and discovery has
   * already refused the route; this is the backstop.
   */
  def openSocket(request: HttpForward): SocketLink =
    throw UnsupportedOperationException("this conversation cannot hold a socket")

  /**
   * Runs a tool of an MCP endpoint in the process (protocol 1.20). `Right(Left(message))` is a tool
   * error the model reads; `Left` is a process that could not run it. A conversation whose program
   * declares no MCP endpoint need not override it: discovery has refused one, and this is the
   * backstop.
   */
  def invokeMcpTool(
      call: RemoteMcpCall
  ): Future[Either[ProcessFailure, Either[String, RemoteMcpAnswer]]] =
    throw UnsupportedOperationException("this conversation cannot run an MCP tool")

  def reachable(): Boolean

/** What the process sends on an open socket. */
enum SocketOutput:
  /** A frame for the client. */
  case Frame(text: String)

  /** The process's handler returned: the socket is closed "finished". */
  case Completed

  /** The process's handler threw, or the process could not be reached: closed "failed". */
  case Failed(message: String)

/**
 * One open socket relayed to the process, in plain values: frames each way, then an end. The
 * sidecar holds the socket itself — its ACL, its limits, its close codes — so this is only ever the
 * frames and the reason it ended.
 */
trait SocketLink:

  /**
   * Relays a frame the client sent, waiting while the process cannot take one: a process that does
   * not read leaves frames in the socket's own bounded queue rather than in this link. `false` once
   * the link has ended, when the frame is not sent.
   */
  def send(text: String): Boolean

  /** Tells the process the socket is closed, and why, then ends this side. Idempotent. */
  def close(reason: String): Unit

  /**
   * The process's next message, waiting up to `within`. After `Completed` or `Failed` it goes on
   * answering the same.
   */
  def next(within: FiniteDuration): Option[SocketOutput]

/** What a process said about an autonomous agent's result. */
enum TaskResultVerdict:
  case Accept

  /** It does not decode as the task type's result; the model is told why. */
  case Malformed(problem: String)

  /** `rule` refused it, for `reason`. */
  case Reject(rule: String, reason: String)
