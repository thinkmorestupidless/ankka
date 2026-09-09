package nakka.runtime

import nakka.core.{CommandError, ErrorCode, Metadata}
import org.apache.pekko.actor.typed.ActorRef

/**
 * Marker for every type nakka puts on the wire or into storage.
 *
 * One `serialization-bindings` entry against this trait covers the whole runtime, so adding a
 * message type never means remembering to register it.
 */
trait NakkaSerializable

/**
 * A metadata pair. A `Vector[(String, String)]` would be neater to read but tuples serialise as
 * bare arrays, which makes stored payloads and broker messages far harder to inspect by hand.
 */
final case class MetaEntry(key: String, value: String) extends NakkaSerializable

object MetaEntry:
  def from(metadata: Metadata): Vector[MetaEntry] =
    metadata.toSeq.toVector.map((k, v) => MetaEntry(k, v))

  def toMetadata(entries: Vector[MetaEntry]): Metadata =
    Metadata(entries.map(e => e.key -> e.value))

/**
 * What a component instance receives and returns across a shard boundary.
 *
 * The payload is already-encoded bytes: a handler's input and output are serialised by that
 * handler's own `Serializer`, so this envelope never needs to know their types.
 */
object EntityProtocol:

  /**
   * One command protocol for every sharded component kind.
   *
   * Entities and workflows share it so that `ShardingTransport` needs a single `EntityTypeKey` type
   * and a single message type — routing a call should not have to know what kind of component is on
   * the other end. Only `Invoke` ever crosses a node boundary; the rest are a workflow engine
   * talking to itself.
   */
  sealed trait Command

  final case class Invoke(
      method: String,
      payload: Array[Byte],
      metadata: Vector[MetaEntry],
      replyTo: ActorRef[Reply]
  ) extends Command
      with NakkaSerializable

  /** Run whatever step a workflow's persisted state says is pending. */
  private[nakka] case object RunPendingStep extends Command

  /** A workflow step finished and described what happens next. */
  private[nakka] final case class StepSucceeded(
      step: String,
      stateChange: Option[Any],
      next: nakka.core.effect.StepOutcome
  ) extends Command

  private[nakka] final case class StepFailed(step: String, message: String) extends Command

  private[nakka] final case class StepTimedOut(step: String) extends Command

  private[nakka] case object WorkflowTimedOut extends Command

  private[nakka] case object PauseTimedOut extends Command

  /**
   * Extension point for nakka modules that host their own sharded component kinds.
   *
   * Note the cost: because this sub-trait is not sealed, the compiler treats `Command` as open and
   * stops reporting non-exhaustive matches over it. Every host must therefore handle unexpected
   * commands explicitly — and for a streaming request that means *replying*, since a caller waiting
   * on a token stream would otherwise hang forever.
   */
  /**
   * Asks a component to stream its reply.
   *
   * Tokens are pushed to `tokens` rather than returned, because a reply that arrives over time
   * cannot be a return value. An `ActorRef` is used rather than a stream `SourceRef` for a concrete
   * reason: Pekko binds its stream-ref serializer to the ref classes themselves, so a `SourceRef`
   * nested inside a message would not serialise — while `ActorRef` has first-class support and
   * therefore works across nodes.
   */
  final case class InvokeStream(
      method: String,
      payload: Array[Byte],
      metadata: Vector[MetaEntry],
      tokens: ActorRef[StreamToken]
  ) extends Command
      with NakkaSerializable

  /** One element of a streamed reply. */
  sealed trait StreamToken extends NakkaSerializable

  final case class Token(text: String) extends StreamToken

  case object StreamCompleted extends StreamToken

  final case class StreamFailed(message: String, code: String) extends StreamToken:
    def toCommandError: CommandError =
      CommandError(
        message,
        ErrorCode.values.find(_.toString == code).getOrElse(ErrorCode.Internal)
      )

  object StreamFailed:
    def apply(error: CommandError): StreamFailed =
      StreamFailed(error.message, error.code.toString)

  trait ModuleCommand extends Command

  sealed trait Reply extends NakkaSerializable

  final case class Succeeded(payload: Array[Byte], metadata: Vector[MetaEntry]) extends Reply

  /**
   * A modelled rejection. `code` travels as the enum's name rather than its ordinal so that adding
   * a case never reinterprets replies already in flight.
   */
  final case class Rejected(message: String, code: String) extends Reply:
    def toCommandError: CommandError =
      CommandError(message, ErrorCode.values.find(_.toString == code).getOrElse(ErrorCode.Internal))

  object Rejected:
    def apply(error: CommandError): Rejected = Rejected(error.message, error.code.toString)

/**
 * One journal record.
 *
 * Flat, with an integer `kind`, rather than a sealed hierarchy: the journal is the longest-lived
 * thing nakka writes, and a shape that needs no polymorphic type resolution to read back is a shape
 * that stays readable.
 *
 *   - `kind = 0` — a domain event, `payload` encoded by the entity's event serializer
 *   - `kind = 1` — the entity was deleted
 *   - `kind = 2` — a time-to-live was set, `expiryMillis` from the epoch
 */
final case class JournalRecord(
    kind: Int,
    manifest: String,
    payload: Array[Byte],
    expiryMillis: Long
) extends NakkaSerializable

object JournalRecord:
  val KindDomain  = 0
  val KindDeleted = 1
  val KindExpiry  = 2

  def domain(manifest: String, payload: Array[Byte]): JournalRecord =
    JournalRecord(KindDomain, manifest, payload, 0L)

  val deleted: JournalRecord = JournalRecord(KindDeleted, "", Array.emptyByteArray, 0L)

  def expiry(atEpochMillis: Long): JournalRecord =
    JournalRecord(KindExpiry, "", Array.emptyByteArray, atEpochMillis)

/** A snapshot, or the whole stored value of a key value entity. */
final case class StateRecord(
    manifest: String,
    payload: Array[Byte],
    deleted: Boolean,
    expiryMillis: Long
) extends NakkaSerializable

/**
 * One record in a workflow's journal.
 *
 * Flat, like `JournalRecord`, and for the same reason: this is the durable record of a business
 * process, and it should stay readable without nakka to interpret it.
 *
 *   - `kind = 0` — the workflow's state changed
 *   - `kind = 1` — a step was scheduled; `step` and `stepInput` say which and with what
 *   - `kind = 2` — the workflow paused; `deadlineMillis`/`step` hold the timeout, if any
 *   - `kind = 3` — completed
 *   - `kind = 4` — failed; `message` says why
 *   - `kind = 5` — a step retry was recorded
 *   - `kind = 6` — the workflow's state was deleted
 */
final case class WorkflowRecord(
    kind: Int,
    state: Array[Byte],
    step: String,
    stepInput: Array[Byte],
    message: String,
    deadlineMillis: Long
) extends NakkaSerializable

object WorkflowRecord:
  val KindStateUpdated  = 0
  val KindTransitioned  = 1
  val KindPaused        = 2
  val KindEnded         = 3
  val KindFailed        = 4
  val KindRetryRecorded = 5
  val KindDeleted       = 6

  private val NoBytes = Array.emptyByteArray

  def stateUpdated(state: Array[Byte]): WorkflowRecord =
    WorkflowRecord(KindStateUpdated, state, "", NoBytes, "", 0L)

  def transitioned(step: String, input: Array[Byte]): WorkflowRecord =
    WorkflowRecord(KindTransitioned, NoBytes, step, input, "", 0L)

  def paused(onTimeoutStep: String, deadlineMillis: Long): WorkflowRecord =
    WorkflowRecord(KindPaused, NoBytes, onTimeoutStep, NoBytes, "", deadlineMillis)

  val ended: WorkflowRecord = WorkflowRecord(KindEnded, NoBytes, "", NoBytes, "", 0L)

  def failed(message: String): WorkflowRecord =
    WorkflowRecord(KindFailed, NoBytes, "", NoBytes, message, 0L)

  def retryRecorded(step: String): WorkflowRecord =
    WorkflowRecord(KindRetryRecorded, NoBytes, step, NoBytes, "", 0L)

  val deleted: WorkflowRecord = WorkflowRecord(KindDeleted, NoBytes, "", NoBytes, "", 0L)
