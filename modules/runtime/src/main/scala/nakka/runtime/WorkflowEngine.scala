package nakka.runtime

import nakka.core.*
import nakka.core.effect.*
import nakka.sdk.*
import nakka.runtime.WorkflowHost.{Event, Run, Status}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, TimerScheduler}
import org.apache.pekko.persistence.typed.scaladsl.{Effect as PekkoEffect, EffectBuilder}

import scala.concurrent.Future
import scala.concurrent.duration.DurationLong
import scala.util.{Failure, Success}

/**
 * The durable step engine for one workflow instance.
 *
 * Separated from `WorkflowHost` because the host is about *storage* — journal records,
 * adapters, state folding — and this is about *execution order*, which is where all the
 * subtlety lives.
 */
private[nakka] final class WorkflowEngine[W <: Workflow[S], S](
    descriptor: WorkflowDescriptor[W, S],
    workflowId: EntityId,
    context: WorkflowContext,
    settings: WorkflowSettings,
    ctx: ActorContext[EntityProtocol.Command],
    timers: TimerScheduler[EntityProtocol.Command]
):
  import EntityProtocol.*

  private val StepTimerKey     = "nakka-step-timeout"
  private val WorkflowTimerKey = "nakka-workflow-timeout"
  private val PauseTimerKey    = "nakka-pause-timeout"

  def onCommand(state: Run[S], command: Command): PekkoEffect[Event[S], Run[S]] =
    command match
      case invoke: Invoke        => onInvoke(state, invoke)
      case RunPendingStep        => onRunPendingStep(state)
      case succeeded: StepSucceeded => onStepSucceeded(state, succeeded)
      case failed: StepFailed    => onStepFailure(state, failed.step, failed.message)
      case timedOut: StepTimedOut =>
        onStepFailure(
          state,
          timedOut.step,
          s"step '${timedOut.step}' timed out after ${settings.stepTimeout(timedOut.step)}"
        )
      case WorkflowTimedOut => onWorkflowTimedOut(state)
      case PauseTimedOut    => onPauseTimedOut(state)

      // Another module's host command, delivered here by mistake. Ignore rather than
      // crash the workflow: it cannot mean anything to this engine.
      case _: ModuleCommand => PekkoEffect.none

  /**
   * Re-arms the engine after recovery.
   *
   * This is what makes a workflow durable rather than merely persistent. The journal says
   * a step was pending; nothing is running it, because the process that was running it is
   * gone. So run it again.
   */
  def onRecovered(state: Run[S]): Unit =
    if state.isTerminal then ()
    else
      armWorkflowTimeout(state)
      state.status match
        case Status.Running if state.pending.isDefined =>
          ctx.log.info(
            "workflow '{}' resuming pending step '{}' after recovery",
            workflowId,
            state.pending.get.name
          )
          ctx.self ! RunPendingStep
        case Status.Paused if state.pauseDeadlineMillis > 0L =>
          armPauseTimeout(state)
        case _ => ()

  // ── External commands ─────────────────────────────────────────────────────

  private def onInvoke(state: Run[S], invoke: Invoke): PekkoEffect[Event[S], Run[S]] =
    if invoke.method == WorkflowLifecycle.Method then onLifecycleQuery(state, invoke)
    else
      descriptor.handler(MethodName(invoke.method)) match
        case None =>
          PekkoEffect.reply(invoke.replyTo)(
            EntityProtocol.Rejected(
              CommandError(
                s"no handler '${invoke.method}' on workflow '${descriptor.componentId}'",
                ErrorCode.NotFound
              )
            )
          )

        case Some(binding) =>
          val workflow = descriptor.create(context)
          workflow._setState(state.value)
          workflow._setContext(
            Some(
              SimpleCommandContext(
                workflowId,
                descriptor.componentId,
                MetaEntry.toMetadata(invoke.metadata),
                0L
              )
            )
          )

          val effect =
            try
              binding
                .decodeAndInvoke(workflow, invoke.payload)
                .asInstanceOf[WorkflowEffect[S, Any]]
            finally workflow._setContext(None)

          effect.outcome match
            case Outcome.Fail(error) =>
              PekkoEffect.reply(invoke.replyTo)(EntityProtocol.Rejected(error))

            case outcome =>
              val events = Vector.newBuilder[Event[S]]
              if effect.deleting then events += Event.Deleted
              effect.stateChange.foreach(value => events += Event.StateUpdated(value))
              effect.transition.foreach(step => events += Event.TransitionedTo(step))
              val toPersist = events.result()

              val nextValue = effect.stateChange.getOrElse(state.value)

              val builder: EffectBuilder[Event[S], Run[S]] =
                if toPersist.isEmpty then PekkoEffect.none
                else PekkoEffect.persist(toPersist.toList)

              val withStep =
                if effect.transition.isDefined then
                  // The transition is journalled first; only then is the step started.
                  builder.thenRun { updated =>
                    armWorkflowTimeout(updated)
                    ctx.self ! RunPendingStep
                  }
                else builder

              outcome match
                case Outcome.Reply(compute, metadata) =>
                  withStep.thenReply(invoke.replyTo) { _ =>
                    EntityProtocol.Succeeded(
                      binding.encodeReply(compute(nextValue)),
                      MetaEntry.from(metadata)
                    )
                  }
                case _ => withStep.thenNoReply()

  /**
   * Answers the engine's own lifecycle query.
   *
   * Deliberately not routed through a developer-written handler: the point is that this
   * works even when — especially when — the workflow's own handlers cannot tell you
   * anything useful.
   */
  private def onLifecycleQuery(state: Run[S], invoke: Invoke): PekkoEffect[Event[S], Run[S]] =
    val lifecycle = WorkflowLifecycle(
      state.status.toString,
      state.pending.map(_.name),
      state.retries,
      state.failure
    )
    PekkoEffect.reply(invoke.replyTo)(
      EntityProtocol.Succeeded(
        WorkflowLifecycle.serializer.toBytes(lifecycle),
        Vector.empty
      )
    )

  // ── Step execution ────────────────────────────────────────────────────────

  private def onRunPendingStep(state: Run[S]): PekkoEffect[Event[S], Run[S]] =
    state.pending match
      case None => PekkoEffect.none
      case Some(ref) =>
        descriptor.stepNamed(ref.name) match
          case None =>
            // A step that no longer exists cannot be retried into existence. Fail loudly
            // rather than retry forever — this is the "renamed a step" mistake.
            PekkoEffect.persist(
              Event.Failed(
                s"workflow '${descriptor.componentId}' has no step '${ref.name}'; " +
                  "it was renamed or removed while an instance was mid-flight"
              )
            )

          case Some(handle) =>
            timers.startSingleTimer(
              StepTimerKey,
              StepTimedOut(ref.name),
              settings.stepTimeout(ref.name)
            )
            startStep(handle, ref, state.value)
            PekkoEffect.none

  /**
   * Runs the step off the actor.
   *
   * A fresh workflow instance per execution: the step runs on a virtual thread while the
   * actor keeps handling commands, so sharing the instance's `currentState` slot would
   * be a data race. Allocating an object is cheaper than that class of bug.
   */
  private def startStep(handle: StepHandleLike[W], ref: StepRef, value: S): Unit =
    val workflow = descriptor.create(context)
    workflow._setState(value)

    val execution = Future {
      handle.invoke(workflow, ref.input).asInstanceOf[WorkflowStepEffect[S]]
    }(using NakkaExecutors.virtual)

    ctx.pipeToSelf(execution) {
      case Success(effect) =>
        StepSucceeded(ref.name, effect.stateChange, effect.next)
      case Failure(failure) =>
        StepFailed(ref.name, Option(failure.getMessage).getOrElse(failure.toString))
    }

  private def onStepSucceeded(
      state: Run[S],
      succeeded: StepSucceeded
  ): PekkoEffect[Event[S], Run[S]] =
    // A result for a step that is no longer pending is a straggler from before a
    // failover or a restart; applying it would rewind the workflow.
    if !state.pending.exists(_.name == succeeded.step) then
      ctx.log.debug("ignoring stale result for step '{}'", succeeded.step)
      PekkoEffect.none
    else
      timers.cancel(StepTimerKey)

      val stateEvents =
        succeeded.stateChange.map(value => Event.StateUpdated(value.asInstanceOf[S])).toVector

      succeeded.next match
        case StepOutcome.TransitionTo(next) =>
          PekkoEffect
            .persist((stateEvents :+ Event.TransitionedTo(next)).toList)
            .thenRun(_ => ctx.self ! RunPendingStep)

        case StepOutcome.Pause(after, onTimeout) =>
          val deadline = after.map(d => System.currentTimeMillis() + d.toMillis).getOrElse(0L)
          PekkoEffect
            .persist((stateEvents :+ Event.Paused(onTimeout.map(_.name), deadline)).toList)
            // The timeout target is read back from the persisted state, not held in a
            // field, so it survives a restart while the workflow is paused.
            .thenRun(updated => armPauseTimeout(updated))

        case StepOutcome.End =>
          PekkoEffect
            .persist((stateEvents :+ Event.Ended).toList)
            .thenRun(_ => cancelLifecycleTimers())

        case StepOutcome.Fail(error) =>
          PekkoEffect
            .persist((stateEvents :+ Event.Failed(error.message)).toList)
            .thenRun(_ => cancelLifecycleTimers())

  /**
   * Applies the recovery strategy for a failed or timed-out step.
   *
   * Retries re-run the same step with the same input; failover transitions to a step that
   * takes no input, so compensation reads whatever the workflow accumulated in its state.
   */
  private def onStepFailure(
      state: Run[S],
      step: String,
      message: String
  ): PekkoEffect[Event[S], Run[S]] =
    if !state.pending.exists(_.name == step) then PekkoEffect.none
    else
      timers.cancel(StepTimerKey)
      val strategy  = settings.recoveryFor(step)
      val attempted = state.retries.getOrElse(step, 0)

      if attempted < strategy.maxRetries then
        ctx.log.warn(
          "step '{}' of workflow '{}' failed ({}); retrying, attempt {} of {}",
          step,
          workflowId,
          message,
          attempted + 1,
          strategy.maxRetries
        )
        PekkoEffect
          .persist(Event.RetryRecorded(step))
          .thenRun(_ => ctx.self ! RunPendingStep)
      else
        strategy.failoverTo match
          case Some(failover) =>
            ctx.log.warn(
              "step '{}' of workflow '{}' failed ({}); failing over to '{}'",
              step,
              workflowId,
              message,
              failover
            )
            PekkoEffect
              .persist(Event.TransitionedTo(StepRef(failover, None)))
              .thenRun(_ => ctx.self ! RunPendingStep)

          case None =>
            ctx.log.error("step '{}' of workflow '{}' failed: {}", step, workflowId, message)
            PekkoEffect
              .persist(Event.Failed(s"step '$step' failed: $message"))
              .thenRun(_ => cancelLifecycleTimers())

  // ── Timeouts ──────────────────────────────────────────────────────────────

  private def onWorkflowTimedOut(state: Run[S]): PekkoEffect[Event[S], Run[S]] =
    if state.isTerminal then PekkoEffect.none
    else
      timers.cancel(StepTimerKey)
      PekkoEffect
        .persist(
          Event.Failed(
            s"workflow timed out after ${settings.timeout.fold("its limit")(_.toString)}"
          )
        )
        .thenRun(_ => cancelLifecycleTimers())

  private def onPauseTimedOut(state: Run[S]): PekkoEffect[Event[S], Run[S]] =
    if state.status != Status.Paused then PekkoEffect.none
    else
      state.pauseOnTimeout match
        case Some(step) =>
          PekkoEffect
            .persist(Event.TransitionedTo(StepRef(step, None)))
            .thenRun(_ => ctx.self ! RunPendingStep)
        case None =>
          PekkoEffect.persist(Event.Failed("paused workflow timed out with no timeout handler"))

  /**
   * Arms the global timeout against the *original* start, not against now — so a
   * workflow that restarts twice still gets one total budget rather than a fresh one
   * each time.
   *
   * The `startedAtMillis > 0` guard is essential rather than defensive: a workflow that
   * has not started yet has no start time, and treating 0 as one makes `elapsed` the
   * whole Unix epoch, which fires the timeout immediately and fails the workflow before
   * its first step ever runs.
   */
  private def armWorkflowTimeout(state: Run[S]): Unit =
    if state.startedAtMillis > 0L then
      settings.timeout.foreach { limit =>
        val elapsed   = (System.currentTimeMillis() - state.startedAtMillis).max(0L)
        val remaining = limit.toMillis - elapsed
        if remaining <= 0L then ctx.self ! WorkflowTimedOut
        else timers.startSingleTimer(WorkflowTimerKey, WorkflowTimedOut, remaining.millis)
      }

  private def armPauseTimeout(state: Run[S]): Unit =
    if state.pauseDeadlineMillis > 0L then
      val remaining = state.pauseDeadlineMillis - System.currentTimeMillis()
      if remaining <= 0L then ctx.self ! PauseTimedOut
      else timers.startSingleTimer(PauseTimerKey, PauseTimedOut, remaining.millis)

  private def cancelLifecycleTimers(): Unit =
    timers.cancel(StepTimerKey)
    timers.cancel(WorkflowTimerKey)
    timers.cancel(PauseTimerKey)
