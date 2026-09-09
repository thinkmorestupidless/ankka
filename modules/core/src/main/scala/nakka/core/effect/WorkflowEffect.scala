package nakka.core.effect

import nakka.core.{CommandError, ErrorCode, Metadata}

import scala.concurrent.duration.FiniteDuration

/**
 * A reference to the next step, with its input already encoded.
 *
 * Encoding at transition time rather than at execution time is what makes a workflow durable: the
 * pending step and its argument are persisted together, so a workflow that crashes between two
 * steps resumes with exactly the input it was about to use.
 */
final case class StepRef(name: String, input: Option[Array[Byte]]):
  override def toString: String = if input.isDefined then s"$name(…)" else name

/** Where a step goes when it finishes. */
sealed trait StepOutcome

object StepOutcome:
  /** Run another step next. */
  final case class TransitionTo(step: StepRef) extends StepOutcome

  /**
   * Stop and wait for an external command.
   *
   * A paused workflow holds no resources — it is a row in Postgres, not a parked thread — which is
   * why pausing for eight hours awaiting a human decision is unremarkable.
   */
  final case class Pause(after: Option[FiniteDuration], onTimeout: Option[StepRef])
      extends StepOutcome

  /** Finish successfully. */
  case object End extends StepOutcome

  /** Finish unsuccessfully. Recovery strategies do not apply — this is deliberate. */
  final case class Fail(error: CommandError) extends StepOutcome

/**
 * What a command handler on a workflow does.
 *
 * Separate from `WorkflowStepEffect` because the two answer different questions: a command decides
 * how to *reply to a caller*, a step decides *what runs next*. Akka splits them the same way, and
 * conflating them makes it easy to write a step that silently never replies.
 */
sealed trait WorkflowEffect[S, +R]:
  private[nakka] def stateChange: Option[S]
  private[nakka] def transition: Option[StepRef]
  private[nakka] def deleting: Boolean
  private[nakka] def outcome: Outcome[S, R]

/** A command effect that provably changes nothing. */
sealed trait WorkflowReadOnlyEffect[S, +R] extends WorkflowEffect[S, R]:
  private[nakka] final def stateChange: Option[S]      = None
  private[nakka] final def transition: Option[StepRef] = None
  private[nakka] final def deleting: Boolean           = false

object WorkflowEffect:

  private[nakka] final case class Changing[S, R](
      stateChange: Option[S],
      transition: Option[StepRef],
      deleting: Boolean,
      outcome: Outcome[S, R]
  ) extends WorkflowEffect[S, R]

  private[nakka] final case class ReadOnly[S, R](outcome: Outcome[S, R])
      extends WorkflowReadOnlyEffect[S, R]

/** What a step does when it finishes. */
sealed trait WorkflowStepEffect[S]:
  private[nakka] def stateChange: Option[S]
  private[nakka] def next: StepOutcome

object WorkflowStepEffect:
  private[nakka] final case class Impl[S](stateChange: Option[S], next: StepOutcome)
      extends WorkflowStepEffect[S]

/** The `effects` surface in a workflow's command handlers. */
final class WorkflowEffects[S] private[nakka] ():
  import WorkflowEffect.*

  /** Sets the initial state and starts the workflow at a step. */
  def updateState(state: S): WorkflowCommandBuilder[S] =
    new WorkflowCommandBuilder(Some(state), None, deleting = false)

  /** Starts or redirects the workflow without changing state. */
  def transitionTo(step: StepRef): WorkflowCommandBuilder[S] =
    new WorkflowCommandBuilder(None, Some(step), deleting = false)

  def reply[R](value: R): WorkflowReadOnlyEffect[S, R] =
    ReadOnly(Outcome.Reply(_ => value, Metadata.empty))

  def error[R](message: String): WorkflowReadOnlyEffect[S, R] =
    ReadOnly(Outcome.Fail(CommandError(message)))

  def error[R](message: String, code: ErrorCode): WorkflowReadOnlyEffect[S, R] =
    ReadOnly(Outcome.Fail(CommandError(message, code)))

  /** Discards the workflow's state entirely. */
  def delete(): WorkflowCommandBuilder[S] =
    new WorkflowCommandBuilder(None, None, deleting = true)

final class WorkflowCommandBuilder[S] private[nakka] (
    private val stateChange: Option[S],
    private val transition: Option[StepRef],
    private val deleting: Boolean
):
  import WorkflowEffect.*

  def transitionTo(step: StepRef): WorkflowCommandBuilder[S] =
    new WorkflowCommandBuilder(stateChange, Some(step), deleting)

  def thenReply[R](value: R): WorkflowEffect[S, R] =
    Changing(stateChange, transition, deleting, Outcome.Reply(_ => value, Metadata.empty))

  def thenReplyState: WorkflowEffect[S, S] =
    Changing(stateChange, transition, deleting, Outcome.Reply(identity, Metadata.empty))

  def thenNoReply[R]: WorkflowEffect[S, R] =
    Changing(stateChange, transition, deleting, Outcome.NoReply)

/** The `stepEffects` surface inside a workflow step. */
final class WorkflowStepEffects[S] private[nakka] ():
  import WorkflowStepEffect.*

  def updateState(state: S): WorkflowStepBuilder[S] = new WorkflowStepBuilder(Some(state))

  def thenTransitionTo(step: StepRef): WorkflowStepEffect[S] =
    Impl(None, StepOutcome.TransitionTo(step))

  def thenPause(): WorkflowStepEffect[S] = Impl(None, StepOutcome.Pause(None, None))

  def thenPause(after: FiniteDuration, onTimeout: StepRef): WorkflowStepEffect[S] =
    Impl(None, StepOutcome.Pause(Some(after), Some(onTimeout)))

  def thenEnd: WorkflowStepEffect[S] = Impl(None, StepOutcome.End)

  def thenFail(message: String): WorkflowStepEffect[S] =
    Impl(None, StepOutcome.Fail(CommandError(message)))

final class WorkflowStepBuilder[S] private[nakka] (private val stateChange: Option[S]):
  import WorkflowStepEffect.*

  def thenTransitionTo(step: StepRef): WorkflowStepEffect[S] =
    Impl(stateChange, StepOutcome.TransitionTo(step))

  def thenPause(): WorkflowStepEffect[S] = Impl(stateChange, StepOutcome.Pause(None, None))

  def thenPause(after: FiniteDuration, onTimeout: StepRef): WorkflowStepEffect[S] =
    Impl(stateChange, StepOutcome.Pause(Some(after), Some(onTimeout)))

  def thenEnd: WorkflowStepEffect[S] = Impl(stateChange, StepOutcome.End)

  def thenFail(message: String): WorkflowStepEffect[S] =
    Impl(stateChange, StepOutcome.Fail(CommandError(message)))
