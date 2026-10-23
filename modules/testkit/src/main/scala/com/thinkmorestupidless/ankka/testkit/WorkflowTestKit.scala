package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.*
import com.thinkmorestupidless.ankka.runtime.{StepScope, WorkflowHost}
import com.thinkmorestupidless.ankka.runtime.WorkflowHost.{Event, Run, Status}
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.FiniteDuration

/**
 * What one command, or one pause timing out, came to: the reply the caller would have seen, each
 * step the workflow moved to on the way, and where it stands now.
 */
final case class StepResult[S, O](
    reply: Either[CommandError, Option[O]],
    /** The steps run after the command, in order, a retried step once per attempt. */
    steps: Vector[String],
    state: S,
    lifecycle: WorkflowLifecycle,
    /** The pause's timeout, when the workflow paused with one: how long, and the step it runs. */
    pauseTimeout: Option[(FiniteDuration, String)] = None
):

  /** The reply value, or a test failure if the handler refused or replied with nothing. */
  def replyValue: O = reply match
    case Right(Some(value)) => value
    case Right(None)        => throw AssertionError("handler replied with no value")
    case Left(error) => throw AssertionError(s"handler rejected the command: ${error.message}")

  def isError: Boolean = reply.isLeft

  /** The refusal, or a test failure if the command succeeded. */
  def error: CommandError = reply.left.getOrElse(
    throw AssertionError(s"expected a rejection but the command succeeded with $reply")
  )

/**
 * Drives one workflow in memory, with no actor system, no database and no timer.
 *
 * A command runs as the engine runs it and its effect is folded by the engine's own fold
 * (`WorkflowHost.applyEvent`); then each step it transitions to runs at once, on the caller's
 * thread, until the workflow pauses, ends or fails. A step that throws is retried or failed over by
 * the workflow's own recovery settings, as the engine does. Nothing waits: a pause with a timeout
 * is reported in the result and fires only when the test says so (`timeOut`), and step and workflow
 * timeouts never fire, since a step here cannot be slow.
 *
 * Every input, reply and state round-trips through the workflow's serializers, and every step's
 * input through the bytes the transition would persist.
 */
final class WorkflowTestKit[W <: Workflow[S], S] private (
    descriptor: WorkflowDescriptor[W, S],
    workflowId: String,
    client: ComponentClient,
    /** The steps' secret store, in memory; a command handler is refused it, as in a service. */
    val secrets: InMemorySecretStore,
    /** Other services, as the test scripted them; steps only, as in a service. */
    val services: ScriptedServices,
    /** The steps' storage client, a store in memory unless the test gave one. */
    val storage: com.thinkmorestupidless.ankka.sdk.ObjectStorage
):

  private val context = SimpleWorkflowContextImpl(
    EntityId(workflowId),
    descriptor.componentId,
    client,
    StepScope.stepsOnly(secrets),
    StepScope.stepsOnly(services),
    StepScope.stepsOnly(storage)
  )

  private val prototype = descriptor.create(context)
  private val settings  = prototype.settings
  private val empty =
    Run[S](prototype.emptyState, Status.NotStarted, None, Map.empty, 0L, 0L, None, None)

  private var run: Run[S]                                    = empty
  private var pauseTimeout: Option[(FiniteDuration, String)] = None

  /** The workflow's state now, as its serializer reads it back. */
  def state: S = roundTrip(run.value)

  /** Where the workflow stands: its status, the step pending, its retries and why it failed. */
  def lifecycle: WorkflowLifecycle =
    WorkflowLifecycle(run.status.toString, run.pending.map(_.name), run.retries, run.failure)

  /** Whether the workflow's state was deleted and nothing has been recorded since. */
  def isDeleted: Boolean = run.deleted

  /** The step that failed, when the workflow failed at one. */
  def failedStep: Option[String] = run.failedStep

  def call[I, O](handle: CommandHandle[W, I, O])(input: I): StepResult[S, O] =
    command(handle, handle.inputSerializer.toBytes(input), handle.outputSerializer)

  def call[O](handle: NoArgHandle[W, O]): StepResult[S, O] =
    command(handle, Array.emptyByteArray, handle.outputSerializer)

  /**
   * The pause's timeout fires: the workflow runs the step its pause named, and on. A workflow that
   * is not paused with a timeout is a test failure, not a quiet nothing.
   */
  def timeOut(): StepResult[S, Nothing] =
    if run.status != Status.Paused || run.pauseOnTimeout.isEmpty then
      throw AssertionError(
        s"workflow '${descriptor.componentId}' is not paused with a timeout; it is ${lifecycle.status}"
      )
    val step = run.pauseOnTimeout.get
    pauseTimeout = None
    apply(Event.TransitionedTo(StepRef(step, None)))
    result(Right(None), runSteps())

  private def command[O](
      binding: HandlerBinding[W],
      payload: Array[Byte],
      output: Serializer[O]
  ): StepResult[S, O] =
    val workflow = descriptor.create(context)
    workflow._setState(roundTrip(run.value))
    workflow._setContext(
      Some(SimpleCommandContext(EntityId(workflowId), descriptor.componentId, Metadata.empty, 0L))
    )
    val attempt: Either[CommandError, WorkflowEffect[S, Any]] =
      try Right(binding.decodeAndInvoke(workflow, payload).asInstanceOf[WorkflowEffect[S, Any]])
      catch case refused: CommandError => Left(refused)
      finally workflow._setContext(None)

    attempt.flatMap { effect =>
      effect.outcome match
        case Outcome.Fail(error) => Left(error)
        case outcome =>
          if effect.deleting then apply(Event.Deleted)
          effect.stateChange.foreach(value => apply(Event.StateUpdated(roundTrip(value))))
          effect.transition.foreach { step =>
            pauseTimeout = None
            apply(Event.TransitionedTo(step))
          }
          Right(outcome)
    } match
      case Left(error)    => result(Left(error), Vector.empty)
      case Right(outcome) =>
        // The reply is computed from the state the command left, before any step runs, as the
        // engine replies once the command's events are journalled.
        val reply = outcome match
          case Outcome.Reply(compute, _) =>
            Some(output.fromBytes(binding.encodeReply(compute(run.value))))
          case _ => None
        result(Right(reply), runSteps())

  private def runSteps(): Vector[String] =
    val ran   = Vector.newBuilder[String]
    var count = 0
    while run.status == Status.Running && run.pending.isDefined do
      count += 1
      if count > WorkflowTestKit.StepLimit then
        throw AssertionError(
          s"workflow '${descriptor.componentId}' ran ${WorkflowTestKit.StepLimit} steps without " +
            "pausing or ending; a step that transitions to itself for ever never stops"
        )
      val ref = run.pending.get
      ran += ref.name
      descriptor.stepNamed(ref.name) match
        case None =>
          apply(
            Event.Failed(
              s"workflow '${descriptor.componentId}' has no step '${ref.name}'; " +
                "it was renamed or removed while an instance was mid-flight",
              Some(ref.name)
            )
          )
        case Some(handle) =>
          val workflow = descriptor.create(context)
          workflow._setState(roundTrip(run.value))
          val attempt =
            try
              Right(
                StepScope.within(
                  handle.invoke(workflow, ref.input).asInstanceOf[WorkflowStepEffect[S]]
                )
              )
            catch case scala.util.control.NonFatal(e) => Left(e)
          attempt match
            case Right(effect) => stepped(ref.name, effect)
            case Left(failure) =>
              failed(ref.name, Option(failure.getMessage).getOrElse(failure.toString))
    ran.result()

  private def stepped(step: String, effect: WorkflowStepEffect[S]): Unit =
    effect.stateChange.foreach(value => apply(Event.StateUpdated(roundTrip(value))))
    effect.next match
      case StepOutcome.TransitionTo(next) => apply(Event.TransitionedTo(next))
      case StepOutcome.Pause(after, onTimeout) =>
        pauseTimeout = after.zip(onTimeout.map(_.name))
        apply(Event.Paused(onTimeout.map(_.name), after.fold(0L)(_.toMillis)))
      case StepOutcome.End         => apply(Event.Ended)
      case StepOutcome.Fail(error) => apply(Event.Failed(error.message, Some(step)))

  private def failed(step: String, message: String): Unit =
    val strategy  = settings.recoveryFor(step)
    val attempted = run.retries.getOrElse(step, 0)
    if attempted < strategy.maxRetries then apply(Event.RetryRecorded(step))
    else
      strategy.failoverTo match
        case Some(failover) => apply(Event.TransitionedTo(StepRef(failover, None)))
        case None           => apply(Event.Failed(s"step '$step' failed: $message", Some(step)))

  private def apply(event: Event[S]): Unit = run = WorkflowHost.applyEvent(empty, run, event)

  private def roundTrip(value: S): S =
    descriptor.stateSerializer.fromBytes(descriptor.stateSerializer.toBytes(value))

  private def result[O](reply: Either[CommandError, Option[O]], steps: Vector[String]) =
    StepResult(reply, steps, state, lifecycle, pauseTimeout)

object WorkflowTestKit:

  /** More steps than this after one command is a workflow that never stops. */
  val StepLimit = 1000

  /** A kit for one instance of a workflow. Calls through `client` are refused by default. */
  def of[W <: Workflow[S], S](
      companion: Workflow.Companion[W, S],
      workflowId: String = "test",
      client: ComponentClient = TestTransport.unroutedClient,
      secrets: InMemorySecretStore = InMemorySecretStore(),
      services: ScriptedServices = ScriptedServices(),
      storage: com.thinkmorestupidless.ankka.sdk.ObjectStorage = InMemoryObjectStorage()
  ): WorkflowTestKit[W, S] =
    new WorkflowTestKit(companion.descriptor, workflowId, client, secrets, services, storage)
