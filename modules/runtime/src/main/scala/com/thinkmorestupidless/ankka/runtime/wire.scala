package com.thinkmorestupidless.ankka.runtime

import com.fasterxml.jackson.annotation.JsonInclude
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, Metadata, WorkflowEnd}
import org.apache.pekko.actor.typed.ActorRef

/**
 * Marker for every type ankka puts on the wire or into storage.
 *
 * One `serialization-bindings` entry against this trait covers the whole runtime, so adding a
 * message type never means remembering to register it.
 */
trait AnkkaSerializable

/**
 * A metadata pair. A `Vector[(String, String)]` would be neater to read but tuples serialise as
 * bare arrays, which makes stored payloads and broker messages far harder to inspect by hand.
 */
final case class MetaEntry(key: String, value: String) extends AnkkaSerializable

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
      with AnkkaSerializable

  /** Run whatever step a workflow's persisted state says is pending. */
  private[ankka] case object RunPendingStep extends Command

  /** A workflow step finished and described what happens next. */
  private[ankka] final case class StepSucceeded(
      step: String,
      stateChange: Option[Any],
      next: com.thinkmorestupidless.ankka.core.effect.StepOutcome
  ) extends Command

  private[ankka] final case class StepFailed(step: String, message: String) extends Command

  private[ankka] final case class StepTimedOut(step: String) extends Command

  private[ankka] case object WorkflowTimedOut extends Command

  private[ankka] case object PauseTimedOut extends Command

  /**
   * A wait for a workflow's end has held its ask as long as it may; the waiter is told "not yet".
   */
  private[ankka] final case class WaiterHeld(key: Long) extends Command

  /**
   * Extension point for ankka modules that host their own sharded component kinds.
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
      with AnkkaSerializable

  /** One element of a streamed reply. */
  sealed trait StreamToken extends AnkkaSerializable

  final case class Token(text: String) extends StreamToken

  case object StreamCompleted extends StreamToken

  /**
   * Ends a stream in place of `StreamCompleted` when what was asked has stopped to wait — an agent
   * turn whose model called a tool that requires approval. `payload` is the module's own encoding
   * of what it waits for; the runtime only carries it.
   */
  final case class StreamAwaiting(payload: Array[Byte]) extends StreamToken

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

  /**
   * A record a replicated entity's follower pulled from `source`, to persist as its next (feature
   * 051). Answered `Persisted` once it holds `sequenceNr`, whether it persisted it now or already
   * held it, and `Refused` when the record is not the next or not written by the entity's home.
   */
  private[ankka] final case class Replicate(
      sequenceNr: Long,
      record: JournalRecord,
      source: String,
      ack: ActorRef[ReplicateAck]
  ) extends ModuleCommand

  private[ankka] sealed trait ReplicateAck
  private[ankka] object ReplicateAck:
    final case class Persisted(sequenceNr: Long) extends ReplicateAck
    final case class Refused(reason: String)     extends ReplicateAck

  /**
   * Another installation advanced a replicated entity: wakes this copy, which starts mirroring if
   * it is a follower. `installation` wrote up to `position`; `joined` when what it wrote put this
   * installation in the replication set.
   */
  private[ankka] final case class Wake(installation: String, position: Long, joined: Boolean)
      extends ModuleCommand
      with AnkkaSerializable

  /** The target of a pending handover holds the entity up to `position`. */
  private[ankka] final case class Reported(installation: String, position: Long)
      extends ModuleCommand
      with AnkkaSerializable

  /** The home's answer to a call this copy forwarded, back on the copy's own thread. */
  private[ankka] final case class Forwarded(
      replyTo: ActorRef[Reply],
      result: scala.util.Try[replication.ForwardedReply],
      home: String
  ) extends ModuleCommand

  /**
   * Where an entity this copy holds no replication record of lives, back on the copy's own thread;
   * and, for a copy that holds records, who wrote its first: itself (`None`), as a journal from
   * before replication was, or the installation it was mirroring before it could tell.
   */
  private[ankka] final case class Located(
      result: scala.util.Try[Option[replication.Located]],
      firstWriter: Option[String]
  ) extends ModuleCommand

  /** Whether another installation's history of this entity is another history altogether. */
  private[ankka] final case class ConflictChecked(other: String, differs: scala.util.Try[Boolean])
      extends ModuleCommand

  /** A held handover waited as long as it may. */
  private[ankka] final case class HandoverTimedOut(target: String) extends ModuleCommand

  sealed trait Reply extends AnkkaSerializable

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
   * A wait for a workflow's end was held as long as one ask allows, and the workflow has not ended:
   * the caller asks again until its own deadline. Also what every waiter is told when the
   * workflow's actor stops, so a workflow that moves or is passivated loses no caller for longer
   * than it takes to ask again. Only an instance that knows waiting sends it, to a caller that
   * asked to wait.
   */
  final case class NotYet(heldMillis: Long) extends Reply

  /**
   * The workflow a caller waited for failed — `step` names the step, when one did — or was deleted.
   * A reply of its own rather than a `Rejected`: the call was answered, and the step and the reason
   * are fields a caller in any language reads.
   */
  final case class WorkflowFailed(step: Option[String], reason: String, deleted: Boolean)
      extends Reply:
    def toCommandError(componentId: String, entityId: String): CommandError =
      val message =
        if deleted then s"workflow $componentId '$entityId' was deleted"
        else s"workflow $componentId '$entityId' failed: $reason"
      CommandError(
        message,
        ErrorCode.WorkflowFailed,
        WorkflowEnd.Failure(step, reason, deleted).details
      )

/**
 * One journal record.
 *
 * Flat, with an integer `kind`, rather than a sealed hierarchy: the journal is the longest-lived
 * thing ankka writes, and a shape that needs no polymorphic type resolution to read back is a shape
 * that stays readable.
 *
 *   - `kind = 0` — a domain event, `payload` encoded by the entity's event serializer
 *   - `kind = 1` — the entity was deleted
 *   - `kind = 2` — a time-to-live was set, `expiryMillis` from the epoch
 *
 * A replicated entity's journal (feature 051) also holds the facts every installation holding it
 * must agree on, each written by its home and mirrored with the rest:
 *
 *   - `kind = 3` — the home is the installation `payload` names, from this record on
 *   - `kind = 4` — the replication set is the installations `payload` lists, as a JSON array
 *   - `kind = 5` — a handover to the installation `payload` names is pending
 *   - `kind = 6` — the pending handover to the installation `payload` names did not happen
 *   - `kind = 7` — this copy found another history for its id, written by the installation
 *     `payload` names; written where it was found, and never mirrored
 *
 * `installation` names the installation whose home wrote the record. It is absent on every record
 * written before replication and on every record of an entity that is not replicated, which is then
 * byte for byte what the release before wrote.
 */
final case class JournalRecord(
    kind: Int,
    manifest: String,
    payload: Array[Byte],
    expiryMillis: Long,
    // Left out when absent, so a record that names no installation is the release before's form.
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    installation: Option[String] = None
) extends AnkkaSerializable:

  /** The same record, written at `at`. */
  def at(installation: Option[String]): JournalRecord = copy(installation = installation)

  /** The installation the payload of a kind 3, 5, 6 or 7 record names. */
  def named: String = String(payload, java.nio.charset.StandardCharsets.UTF_8)

  /** The installations the payload of a kind 4 record lists. */
  def names: Set[String] = JournalRecord.readNames(payload)

object JournalRecord:
  val KindDomain            = 0
  val KindDeleted           = 1
  val KindExpiry            = 2
  val KindHome              = 3
  val KindReplicationSet    = 4
  val KindHandover          = 5
  val KindHandoverAbandoned = 6
  val KindConflict          = 7

  /** The kinds a follower mirrors: every kind but a conflict, which is the finder's alone. */
  def mirrored(kind: Int): Boolean = kind != KindConflict

  def domain(manifest: String, payload: Array[Byte]): JournalRecord =
    JournalRecord(KindDomain, manifest, payload, 0L)

  val deleted: JournalRecord = JournalRecord(KindDeleted, "", Array.emptyByteArray, 0L)

  def expiry(atEpochMillis: Long): JournalRecord =
    JournalRecord(KindExpiry, "", Array.emptyByteArray, atEpochMillis)

  private def text(value: String): Array[Byte] =
    value.getBytes(java.nio.charset.StandardCharsets.UTF_8)

  def home(installation: String): JournalRecord =
    JournalRecord(KindHome, "ankka.home", text(installation), 0L)

  def replicationSet(installations: Set[String]): JournalRecord =
    JournalRecord(KindReplicationSet, "ankka.replication-set", writeNames(installations), 0L)

  def handover(target: String): JournalRecord =
    JournalRecord(KindHandover, "ankka.handover", text(target), 0L)

  def handoverAbandoned(target: String): JournalRecord =
    JournalRecord(KindHandoverAbandoned, "ankka.handover-abandoned", text(target), 0L)

  def conflict(other: String): JournalRecord =
    JournalRecord(KindConflict, "ankka.conflict", text(other), 0L)

  /** A JSON array of names, sorted so one set is always written as the same bytes. */
  private def writeNames(names: Set[String]): Array[Byte] =
    text(names.toVector.sorted.map(n => "\"" + n + "\"").mkString("[", ",", "]"))

  /** Reads what `writeNames` wrote. Installation names are DNS labels: no quote or comma. */
  private[runtime] def readNames(payload: Array[Byte]): Set[String] =
    String(payload, java.nio.charset.StandardCharsets.UTF_8).trim
      .stripPrefix("[")
      .stripSuffix("]")
      .split(',')
      .map(_.trim.stripPrefix("\"").stripSuffix("\""))
      .filter(_.nonEmpty)
      .toSet

/**
 * A snapshot, or the whole stored value of a key value entity.
 *
 * The four replication fields (feature 051) are a replicated entity's mode at the snapshot's
 * sequence number, each left out when absent so every other snapshot and every key value row is the
 * release before's form.
 */
final case class StateRecord(
    manifest: String,
    payload: Array[Byte],
    deleted: Boolean,
    expiryMillis: Long,
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    home: Option[String] = None,
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    replicationSet: Option[Vector[String]] = None,
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    pendingHandover: Option[String] = None,
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    conflict: Option[String] = None
) extends AnkkaSerializable

/**
 * A remote event sourced host's snapshot (feature 009): a `StateRecord` plus where in the journal
 * the process produced this state and where the last deletion marker sits. Positions are stored
 * because Pekko decides *when* it saves a snapshot and a sidecar cannot fold events to find out;
 * they are absolute sequence numbers, so the save's timing does not matter.
 *
 * The in-process host reads this record too (taking only what a `StateRecord` has), so a journal
 * written by a service in another language recovers in Scala, and the reverse.
 */
final case class RemoteStateRecord(
    manifest: String,
    payload: Array[Byte],
    deleted: Boolean,
    expiryMillis: Long,
    snapshotSeq: Long,
    deletionSeq: Long,
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    home: Option[String] = None,
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    replicationSet: Option[Vector[String]] = None,
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    pendingHandover: Option[String] = None,
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    conflict: Option[String] = None
) extends AnkkaSerializable

/**
 * One record in a workflow's journal.
 *
 * Flat, like `JournalRecord`, and for the same reason: this is the durable record of a business
 * process, and it should stay readable without ankka to interpret it.
 *
 *   - `kind = 0` — the workflow's state changed; from spec 046 `standing` holds where the workflow
 *     stood once the whole effect that recorded the state was applied, and a record written before
 *     holds none
 *   - `kind = 1` — a step was scheduled; `step` and `stepInput` say which and with what
 *   - `kind = 2` — the workflow paused; `deadlineMillis`/`step` hold the timeout, if any
 *   - `kind = 3` — completed
 *   - `kind = 4` — failed; `message` says why, and `step` names the step that failed, when one did
 *   - `kind = 5` — a step retry was recorded
 *   - `kind = 6` — the workflow's state was deleted
 */
final case class WorkflowRecord(
    kind: Int,
    state: Array[Byte],
    step: String,
    stepInput: Array[Byte],
    message: String,
    deadlineMillis: Long,
    // Left out when absent, so a record that carries no standing is written byte for byte as the
    // release before wrote it, and the pinned journal stays the journal.
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    standing: Option[StandingRecord] = None
) extends AnkkaSerializable

/**
 * Where a workflow stood once the effect that recorded a state was applied: the engine's status by
 * name, the step it is on or waits after (empty for none), its retries, and why it failed (empty
 * when it did not). Flat and string-keyed like the record it sits on.
 */
final case class StandingRecord(
    status: String,
    step: String,
    retries: Map[String, Int],
    failure: String
)

object WorkflowRecord:
  val KindStateUpdated  = 0
  val KindTransitioned  = 1
  val KindPaused        = 2
  val KindEnded         = 3
  val KindFailed        = 4
  val KindRetryRecorded = 5
  val KindDeleted       = 6

  private val NoBytes = Array.emptyByteArray

  def stateUpdated(state: Array[Byte], standing: Option[StandingRecord] = None): WorkflowRecord =
    WorkflowRecord(KindStateUpdated, state, "", NoBytes, "", 0L, standing)

  def transitioned(step: String, input: Array[Byte]): WorkflowRecord =
    WorkflowRecord(KindTransitioned, NoBytes, step, input, "", 0L)

  def paused(onTimeoutStep: String, deadlineMillis: Long): WorkflowRecord =
    WorkflowRecord(KindPaused, NoBytes, onTimeoutStep, NoBytes, "", deadlineMillis)

  val ended: WorkflowRecord = WorkflowRecord(KindEnded, NoBytes, "", NoBytes, "", 0L)

  /** `step` is the step that failed, or empty: a record from before it was written has none. */
  def failed(message: String, step: String = ""): WorkflowRecord =
    WorkflowRecord(KindFailed, NoBytes, step, NoBytes, message, 0L)

  def retryRecorded(step: String): WorkflowRecord =
    WorkflowRecord(KindRetryRecorded, NoBytes, step, NoBytes, "", 0L)

  val deleted: WorkflowRecord = WorkflowRecord(KindDeleted, NoBytes, "", NoBytes, "", 0L)
