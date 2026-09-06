package nakka.runtime

import nakka.core.*
import nakka.core.effect.*
import nakka.sdk.*
import nakka.sdk.ComponentClient
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.scaladsl.{EventSourcedBehavior, RetentionCriteria}
import org.apache.pekko.persistence.typed.{
  EventAdapter,
  EventSeq,
  PersistenceId,
  RecoveryCompleted,
  SnapshotAdapter
}


/**
 * Hosts one workflow kind: a durable state machine over persisted step transitions.
 *
 * The engine's whole job is the ordering guarantee. A transition is journalled *before*
 * the step runs, so a workflow that dies mid-step comes back knowing exactly which step
 * was pending and with what input, and re-runs it. A step that ran but whose result was
 * never journalled runs again — which is why steps must be idempotent and why retries
 * default to zero.
 */
private[nakka] object WorkflowHost:

  enum Status:
    case NotStarted, Running, Paused, Completed, Failed

  /** The persisted state: the developer's value plus where the process has got to. */
  final case class Run[S](
      value: S,
      status: Status,
      pending: Option[StepRef],
      retries: Map[String, Int],
      startedAtMillis: Long,
      pauseDeadlineMillis: Long,
      pauseOnTimeout: Option[String],
      failure: Option[String]
  ):
    def isTerminal: Boolean = status == Status.Completed || status == Status.Failed

  /** In-memory event type; the adapter maps these to `WorkflowRecord`. */
  enum Event[+S]:
    case StateUpdated(state: S)
    case TransitionedTo(step: StepRef)
    case Paused(onTimeout: Option[String], deadlineMillis: Long)
    case Ended
    case Failed(message: String)
    case RetryRecorded(step: String)
    case Deleted

  def behavior[W <: Workflow[S], S](
      descriptor: WorkflowDescriptor[W, S],
      workflowId: EntityId,
      componentClient: ComponentClient
  ): Behavior[EntityProtocol.Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val context = SimpleWorkflowContextImpl(workflowId, descriptor.componentId, componentClient)

        // One instance for settings and the empty state. Command handling and step
        // execution each get their own, because a step runs concurrently with the actor
        // and must not share the mutable `currentState` slot.
        val prototype = descriptor.create(context)
        val settings  = prototype.settings

        val empty = Run[S](
          prototype.emptyState,
          Status.NotStarted,
          None,
          Map.empty,
          0L,
          0L,
          None,
          None
        )

        val engine = WorkflowEngine(descriptor, workflowId, context, settings, ctx, timers)

        EventSourcedBehavior[EntityProtocol.Command, Event[S], Run[S]](
          persistenceId = PersistenceId(descriptor.componentId, workflowId),
          emptyState = empty,
          commandHandler = (state, command) => engine.onCommand(state, command),
          eventHandler = (state, event) => applyEvent(empty, state, event)
        )
          .eventAdapter(eventAdapter(descriptor))
          .snapshotAdapter(snapshotAdapter(descriptor))
          .withRetention(RetentionCriteria.snapshotEvery(100, 2))
          .receiveSignal { case (state, RecoveryCompleted) =>
            engine.onRecovered(state)
          }
      }
    }

  private def applyEvent[S](empty: Run[S], state: Run[S], event: Event[S]): Run[S] =
    event match
      case Event.StateUpdated(value) =>
        state.copy(value = value)

      case Event.TransitionedTo(step) =>
        state.copy(
          status = Status.Running,
          pending = Some(step),
          pauseDeadlineMillis = 0L,
          pauseOnTimeout = None,
          startedAtMillis =
            if state.startedAtMillis == 0L then System.currentTimeMillis()
            else state.startedAtMillis
        )

      case Event.Paused(onTimeout, deadline) =>
        state.copy(
          status = Status.Paused,
          pending = None,
          pauseDeadlineMillis = deadline,
          pauseOnTimeout = onTimeout
        )

      case Event.Ended =>
        state.copy(status = Status.Completed, pending = None, pauseOnTimeout = None)

      case Event.Failed(message) =>
        state.copy(
          status = Status.Failed,
          pending = None,
          pauseOnTimeout = None,
          failure = Some(message)
        )

      case Event.RetryRecorded(step) =>
        state.copy(retries = state.retries.updated(step, state.retries.getOrElse(step, 0) + 1))

      case Event.Deleted =>
        empty

  // ── Storage adapters ──────────────────────────────────────────────────────

  private def eventAdapter[W <: Workflow[S], S](
      descriptor: WorkflowDescriptor[W, S]
  ): EventAdapter[Event[S], WorkflowRecord] =
    new EventAdapter[Event[S], WorkflowRecord]:

      def toJournal(event: Event[S]): WorkflowRecord = event match
        case Event.StateUpdated(state) =>
          WorkflowRecord.stateUpdated(descriptor.stateSerializer.toBytes(state))
        case Event.TransitionedTo(step) =>
          WorkflowRecord.transitioned(step.name, step.input.getOrElse(Array.emptyByteArray))
        case Event.Paused(onTimeout, deadline) =>
          WorkflowRecord.paused(onTimeout.getOrElse(""), deadline)
        case Event.Ended                  => WorkflowRecord.ended
        case Event.Failed(message)        => WorkflowRecord.failed(message)
        case Event.RetryRecorded(step)    => WorkflowRecord.retryRecorded(step)
        case Event.Deleted                => WorkflowRecord.deleted

      def manifest(event: Event[S]): String = event match
        case Event.StateUpdated(_)   => "state"
        case Event.TransitionedTo(_) => "transition"
        case Event.Paused(_, _)      => "pause"
        case Event.Ended             => "end"
        case Event.Failed(_)         => "fail"
        case Event.RetryRecorded(_)  => "retry"
        case Event.Deleted           => "delete"

      def fromJournal(record: WorkflowRecord, manifest: String): EventSeq[Event[S]] =
        val event = record.kind match
          case WorkflowRecord.KindStateUpdated =>
            Event.StateUpdated(descriptor.stateSerializer.fromBytes(record.state))
          case WorkflowRecord.KindTransitioned =>
            Event.TransitionedTo(
              StepRef(record.step, Option(record.stepInput).filter(_.nonEmpty))
            )
          case WorkflowRecord.KindPaused =>
            Event.Paused(Option(record.step).filter(_.nonEmpty), record.deadlineMillis)
          case WorkflowRecord.KindEnded         => Event.Ended
          case WorkflowRecord.KindFailed        => Event.Failed(record.message)
          case WorkflowRecord.KindRetryRecorded => Event.RetryRecorded(record.step)
          case WorkflowRecord.KindDeleted       => Event.Deleted
          case other =>
            throw IllegalStateException(
              s"unknown workflow record kind $other for '${descriptor.componentId}'; " +
                "the journal was written by a newer runtime"
            )
        EventSeq.single(event)

  private def snapshotAdapter[W <: Workflow[S], S](
      descriptor: WorkflowDescriptor[W, S]
  ): SnapshotAdapter[Run[S]] =
    new SnapshotAdapter[Run[S]]:

      def toJournal(state: Run[S]): Any =
        WorkflowSnapshot(
          descriptor.stateSerializer.toBytes(state.value),
          state.status.ordinal,
          state.pending.map(_.name).getOrElse(""),
          state.pending.flatMap(_.input).getOrElse(Array.emptyByteArray),
          state.retries.toVector.map((k, v) => MetaEntry(k, v.toString)),
          state.startedAtMillis,
          state.pauseDeadlineMillis,
          state.pauseOnTimeout.getOrElse(""),
          state.failure.getOrElse("")
        )

      def fromJournal(from: Any): Run[S] =
        val snapshot = from.asInstanceOf[WorkflowSnapshot]
        Run(
          descriptor.stateSerializer.fromBytes(snapshot.state),
          Status.fromOrdinal(snapshot.status),
          Option(snapshot.pendingStep)
            .filter(_.nonEmpty)
            .map(name => StepRef(name, Option(snapshot.pendingInput).filter(_.nonEmpty))),
          snapshot.retries.map(e => e.key -> e.value.toInt).toMap,
          snapshot.startedAtMillis,
          snapshot.pauseDeadlineMillis,
          Option(snapshot.pauseOnTimeout).filter(_.nonEmpty),
          Option(snapshot.failure).filter(_.nonEmpty)
        )

/** A workflow snapshot on the wire. */
final case class WorkflowSnapshot(
    state: Array[Byte],
    status: Int,
    pendingStep: String,
    pendingInput: Array[Byte],
    retries: Vector[MetaEntry],
    startedAtMillis: Long,
    pauseDeadlineMillis: Long,
    pauseOnTimeout: String,
    failure: String
) extends NakkaSerializable
