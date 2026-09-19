package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.TimedActionEffect
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.{Logger, LoggerFactory}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
 * The cluster singleton that fires due timers.
 *
 * Runs one batch at a time. Overlapping batches would let the same timer be started twice
 * concurrently, turning at-least-once into at-least-once-and-often-simultaneously — much harder for
 * a handler to be idempotent against.
 */
private[ankka] object TimerSweeper:

  private sealed trait Command
  private case object Poll                                 extends Command
  private final case class BatchFinished(fired: Int)       extends Command
  private final case class BatchFailed(failure: Throwable) extends Command

  def apply(
      database: Database,
      actions: Map[ComponentId, TimedActionDescriptor[?]],
      componentClient: ComponentClient,
      pollInterval: FiniteDuration
  ): Behavior[Nothing] =
    Behaviors
      .setup[Command] { ctx =>
        // Everything the async work needs is captured here, while we are safely inside
        // the actor. `ActorContext` — including `ctx.log` and `ctx.system` — must not be
        // touched from a Future callback, and doing so previously made every reschedule
        // throw, which silently turned "retry with backoff" into "retry immediately,
        // forever, with the attempt counter stuck at zero".
        val sweep = Sweep(
          database,
          actions,
          componentClient,
          ctx.system.executionContext,
          Observability(ctx.system)
        )

        Behaviors.withTimers { timers =>
          timers.startTimerWithFixedDelay(Poll, pollInterval)

          def idle: Behavior[Command] = Behaviors.receiveMessage {
            case Poll =>
              ctx.pipeToSelf(sweep.runBatch()) {
                case Success(fired)   => BatchFinished(fired)
                case Failure(failure) => BatchFailed(failure)
              }
              busy
            case _ => Behaviors.same
          }

          def busy: Behavior[Command] = Behaviors.receiveMessage {
            // Ticks arriving mid-batch are dropped rather than queued: the next tick is
            // moments away and there is nothing to catch up on.
            case Poll => Behaviors.same

            case BatchFinished(fired) =>
              if fired > 0 then ctx.log.debug("timer sweep fired {} timer(s)", fired)
              idle

            case BatchFailed(failure) =>
              ctx.log.warn("timer sweep failed; retrying on the next tick", failure)
              idle
          }

          idle
        }
      }
      .narrow

/** The off-actor half of the sweeper: pure async work, no `ActorContext` in sight. */
private[ankka] final class Sweep(
    database: Database,
    actions: Map[ComponentId, TimedActionDescriptor[?]],
    componentClient: ComponentClient,
    ec: ExecutionContext,
    observability: Observability
):
  private given ExecutionContext = ec

  private val log: Logger = LoggerFactory.getLogger("ankka.timers")

  private val BatchSize = 100

  /** Fires everything due, returning how many completed. */
  def runBatch(): Future[Int] =
    database
      .query(TimerStore.due(Instant.now(), BatchSize)) { row =>
        DueTimer(
          row.get("timer_name", classOf[String]),
          ComponentId(row.get("component_id", classOf[String])),
          MethodName(row.get("method", classOf[String])),
          row.get("payload", classOf[Array[Byte]]),
          row.get("attempts", classOf[Integer]).intValue
        )
      }
      .flatMap(due => Future.sequence(due.map(fire)).map(_.count(identity)))

  private def fire(timer: DueTimer): Future[Boolean] =
    actions.get(timer.componentId) match
      case None =>
        // The action was removed while a timer for it was still scheduled. Retrying can
        // never succeed, so drop it rather than spin forever.
        log.error(
          "timer '{}' targets timed action '{}', which is not registered; dropping it",
          timer.name,
          timer.componentId
        )
        drop(timer)

      case Some(descriptor) =>
        val typed = descriptor.asInstanceOf[TimedActionDescriptor[TimedAction]]
        typed.handler(timer.method) match
          case None =>
            log.error(
              "timer '{}' targets '{}#{}', which no longer exists; dropping it",
              timer.name,
              timer.componentId,
              timer.method
            )
            drop(timer)

          case Some(handler) => run(typed, handler, timer)

  private def run(
      descriptor: TimedActionDescriptor[TimedAction],
      handler: (TimedAction, Array[Byte]) => TimedActionEffect,
      timer: DueTimer
  ): Future[Boolean] =
    val context = SimpleTimedActionContext(
      descriptor.componentId,
      componentClient,
      timer.name,
      timer.attempts
    )

    // Handlers may block on ComponentClient, so they run on a virtual thread.
    val execution = Future {
      val action = descriptor.create(context)
      action._setContext(Some(context))
      // A fired timer is a trace root: it is its own piece of work, not a continuation of
      // whatever scheduled it, possibly days earlier. The recorder holds no actor reference,
      // so calling it from this Future is safe where touching ActorContext would not be.
      val span = observability.recorder.begin(
        traceId = Trace.mint(),
        parentSpanId = 0L,
        componentRef = observability.names.intern(descriptor.componentId.toString),
        handlerRef = observability.names.intern(timer.method.toString)
      )
      var outcome = SpanOutcome.Failed
      try
        val effect = Trace.within(span.traceId, span.id)(handler(action, timer.payload))
        outcome = SpanOutcome.Ok
        effect
      finally
        observability.recorder.complete(span, outcome)
        action._setContext(None)
    }(using AnkkaExecutors.virtual)

    execution.transformWith {
      case Success(TimedActionEffect.Done) =>
        database.execute(TimerStore.delete(timer.name)).map(_ => true)

      case Success(TimedActionEffect.Fail(error)) =>
        log.warn(
          "timer '{}' reported failure ({}); rescheduling with backoff after {} attempt(s)",
          timer.name,
          error.message,
          timer.attempts
        )
        reschedule(timer)

      case Failure(NonFatal(failure)) =>
        log.warn(s"timer '${timer.name}' threw; rescheduling with backoff", failure)
        reschedule(timer)

      case Failure(fatal) => Future.failed(fatal)
    }

  private def drop(timer: DueTimer): Future[Boolean] =
    database.execute(TimerStore.delete(timer.name)).map(_ => false)

  private def reschedule(timer: DueTimer): Future[Boolean] =
    database.execute(TimerStore.reschedule(timer.name, timer.attempts)).map(_ => false)

private[ankka] final case class DueTimer(
    name: String,
    componentId: ComponentId,
    method: MethodName,
    payload: Array[Byte],
    attempts: Int
)
