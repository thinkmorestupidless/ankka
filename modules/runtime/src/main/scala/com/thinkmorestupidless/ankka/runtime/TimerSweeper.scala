package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.TimedActionEffect
import com.thinkmorestupidless.ankka.runtime.remote.{
  Conversation,
  RemoteTimedActionDescriptor,
  TimedActionRequest
}
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.slf4j.{Logger, LoggerFactory}

import java.time.{Clock, Instant}
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

  /** Metadata a remote timed action receives in place of a `TimedActionContext`. */
  val TimerNameKey: String = "ankka.timer"
  val AttemptsKey: String  = "ankka.attempts"

  /** The due time the run is for, in milliseconds since the epoch, as `ankka.now` is written. */
  val DueKey: String = "ankka.due"

  def apply(
      database: Database,
      actions: Map[ComponentId, ComponentDescriptor],
      componentClient: ComponentClient,
      secrets: SecretStore,
      services: ServiceClients,
      conversation: Option[Conversation],
      pollInterval: FiniteDuration,
      observer: TimerObserver = TimerObserver.none,
      clock: Clock = Clock.systemUTC()
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
          secrets,
          services,
          conversation,
          ctx.system.executionContext,
          Observability(ctx.system),
          observer,
          clock
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
    actions: Map[ComponentId, ComponentDescriptor],
    componentClient: ComponentClient,
    secrets: SecretStore,
    services: ServiceClients,
    conversation: Option[Conversation],
    ec: ExecutionContext,
    observability: Observability,
    observer: TimerObserver = TimerObserver.none,
    clock: Clock = Clock.systemUTC()
):
  private given ExecutionContext = ec

  private val log: Logger = LoggerFactory.getLogger("ankka.timers")

  private val BatchSize = 100

  /**
   * Fires everything due, returning how many completed.
   *
   * On a table from before recurring timers — a local database whose volume predates them — the due
   * query names columns the table lacks. Such a table can hold only timers that fire once, so they
   * are read and run as the previous release ran them, and the remedy is said once.
   */
  def runBatch(): Future[Int] =
    val now = Instant.now(clock)
    database
      .query(TimerStore.due(now, BatchSize)) { row =>
        DueTimer(
          row.get("timer_name", classOf[String]),
          ComponentId(row.get("component_id", classOf[String])),
          MethodName(row.get("method", classOf[String])),
          row.get("payload", classOf[Array[Byte]]),
          row.get("attempts", classOf[Integer]).intValue,
          Option(row.get("due_at", classOf[Instant])),
          Option(row.get("period_millis", classOf[java.lang.Long])).map(_.longValue),
          Option(row.get("due_for", classOf[Instant]))
        )
      }
      .recoverWith {
        case e: io.r2dbc.spi.R2dbcException if e.getSqlState == "42703" =>
          TimerRuntime.warnOldTable(TimerRuntime.schemaTooOld(e).message)
          database.query(TimerStore.legacyDue(now, BatchSize)) { row =>
            DueTimer(
              row.get("timer_name", classOf[String]),
              ComponentId(row.get("component_id", classOf[String])),
              MethodName(row.get("method", classOf[String])),
              row.get("payload", classOf[Array[Byte]]),
              row.get("attempts", classOf[Integer]).intValue,
              Some(row.get("due_at", classOf[Instant])),
              None,
              None,
              oldTable = true
            )
          }
      }
      .flatMap(due => Future.sequence(due.map(fire)).map(_.count(identity)))

  private def fire(timer: DueTimer): Future[Boolean] =
    actions.get(timer.componentId) match
      case None =>
        // The action was removed while a timer for it was still scheduled — or, for a recurring
        // timer, this instance is older than the one that set it.
        notHere(timer, s"timed action '${timer.componentId}', which is not registered")

      case Some(descriptor: TimedActionDescriptor[?]) =>
        val typed = descriptor.asInstanceOf[TimedActionDescriptor[TimedAction]]
        typed.handler(timer.method) match
          case None =>
            notHere(timer, s"'${timer.componentId}#${timer.method}', which does not exist")
          case Some(handler) => run(typed, handler, timer)

      case Some(descriptor: RemoteTimedActionDescriptor) =>
        (descriptor.handler(timer.method), conversation) match
          case (None, _) =>
            notHere(timer, s"'${timer.componentId}#${timer.method}', which does not exist")
          case (Some(_), None) =>
            // `validate` refused a remote descriptor without a conversation; this is unreachable
            // by construction, and retrying is still right if it ever is reached.
            log.error("timer '{}' targets a remote action but no conversation exists", timer.name)
            failed(timer)
          case (Some(_), Some(conversation)) => runRemote(descriptor, conversation, timer)

      case Some(other) =>
        notHere(timer, s"'${timer.componentId}', which is a ${other.kind}, not a timed action")

  /**
   * A timer whose target this instance does not have.
   *
   * One that fires once is dropped: retrying can never succeed, and it is far more often a leftover
   * than a timer ahead of its deploy. A recurring timer is kept and looked at again: a new version
   * of a service that adds a handler sets its timer at start, while the sweeper may still be on an
   * instance of the old version, whose registry has no such handler — and a drop there would lose a
   * timer set seconds earlier. Nothing failed, so its attempt count is not raised.
   */
  private def notHere(timer: DueTimer, what: String): Future[Boolean] =
    timer.recurring match
      case None =>
        log.error("timer '{}' targets {}; dropping it", timer.name, what)
        val dueAt = timer.dueAt.get
        database
          .execute(TimerStore.deleteRan(timer.name, dueAt))
          .map { _ =>
            observe(timer, FiredTimer.Outcome.Dropped, None)
            false
          }
      case Some((dueFor, period)) =>
        log.warn(
          "recurring timer '{}' targets {} on this instance; keeping it and looking again in {}s. " +
            "If its handler is gone for good, delete the timer",
          timer.name,
          what,
          TimerStore.DeferSeconds
        )
        val at = Instant.now(clock).plusSeconds(TimerStore.DeferSeconds)
        database
          .execute(TimerStore.defer(timer.name, dueFor, period, at))
          .map { changed =>
            observe(timer, FiredTimer.Outcome.Deferred, Option.when(changed > 0)(at))
            false
          }

  /**
   * A remote action runs in the process: the same span, the same retry and attempt counting as a
   * Scala one. The payload is what the process scheduled, opaque to the runtime; the timer's name,
   * attempt count and due time travel as metadata since there is no context object to hand over. A
   * failed call — the process down, the RPC refused — counts as a handler that threw.
   */
  private def runRemote(
      descriptor: RemoteTimedActionDescriptor,
      conversation: Conversation,
      timer: DueTimer
  ): Future[Boolean] =
    val span = observability.recorder.beginRoot(
      componentRef = observability.names.intern(descriptor.componentId.toString),
      handlerRef = observability.names.intern(timer.method.toString)
    )
    val metadata = CallOrigin.into(
      Trace.into(
        Metadata.empty
          .set(TimerSweeper.TimerNameKey, timer.name)
          .set(TimerSweeper.AttemptsKey, timer.attempts.toString)
          .set(TimerSweeper.DueKey, timer.told.toEpochMilli.toString),
        span.context
      ),
      CallOrigin(descriptor.componentId.toString, timer.method.toString)
    )
    conversation
      .invokeTimedAction(
        TimedActionRequest(descriptor.componentId, timer.method, timer.payload, metadata)
      )
      .transform { result =>
        observability.recorder
          .complete(span, if result.isSuccess then SpanOutcome.Ok else SpanOutcome.Failed)
        result
      }
      .transformWith {
        case Success(Right(())) => succeeded(timer)

        case Success(Left(error)) =>
          log.warn(
            "timer '{}' reported failure ({}); rescheduling with backoff after {} attempt(s)",
            timer.name,
            error.message,
            timer.attempts
          )
          failed(timer)

        case Failure(NonFatal(failure)) =>
          log.warn(
            s"timer '${timer.name}' could not reach the process; rescheduling with backoff",
            failure
          )
          failed(timer)

        case Failure(fatal) => Future.failed(fatal)
      }

  private def run(
      descriptor: TimedActionDescriptor[TimedAction],
      handler: (TimedAction, Array[Byte]) => TimedActionEffect,
      timer: DueTimer
  ): Future[Boolean] =
    val context = SimpleTimedActionContext(
      descriptor.componentId,
      componentClient,
      timer.name,
      timer.attempts,
      timer.told,
      secrets,
      services,
      new DatabaseTimerScheduler(database, clock),
      clock
    )

    // Handlers may block on ComponentClient, so they run on a virtual thread.
    val execution = Future {
      val action = descriptor.create(context)
      action._setContext(Some(context))
      // A fired timer is a trace root: it is its own piece of work, not a continuation of
      // whatever scheduled it, possibly days earlier. The recorder holds no actor reference,
      // so calling it from this Future is safe where touching ActorContext would not be.
      val span = observability.recorder.beginRoot(
        componentRef = observability.names.intern(descriptor.componentId.toString),
        handlerRef = observability.names.intern(timer.method.toString)
      )
      var outcome = SpanOutcome.Failed
      try
        val origin = CallOrigin(descriptor.componentId.toString, timer.method.toString)
        val effect = Trace.within(span, origin)(handler(action, timer.payload))
        outcome = SpanOutcome.Ok
        effect
      finally
        observability.recorder.complete(span, outcome)
        action._setContext(None)
    }(using AnkkaExecutors.virtual)

    execution.transformWith {
      case Success(TimedActionEffect.Done) => succeeded(timer)

      case Success(TimedActionEffect.Fail(error)) =>
        log.warn(
          "timer '{}' reported failure ({}); rescheduling with backoff after {} attempt(s)",
          timer.name,
          error.message,
          timer.attempts
        )
        failed(timer)

      case Failure(NonFatal(failure)) =>
        log.warn(s"timer '${timer.name}' threw; rescheduling with backoff", failure)
        failed(timer)

      case Failure(fatal) => Future.failed(fatal)
    }

  // Every statement after a run is matched on what was read, never on the name alone: the handler
  // may have set its own timer again, replaced it or cancelled it, and what it did must stand.

  /**
   * The handler succeeded. A timer that fires once is removed; a recurring one is given its next
   * due, worked out from the due it ran for with the clock read now, after the handler, so a run
   * that outlasted a period skips ahead as an outage does.
   */
  private def succeeded(timer: DueTimer): Future[Boolean] =
    timer.recurring match
      case None =>
        database
          .execute(TimerStore.deleteRan(timer.name, timer.dueAt.get))
          .map { _ =>
            observe(timer, FiredTimer.Outcome.Done, None)
            true
          }
      case Some((dueFor, period)) =>
        val now     = Instant.now(clock)
        val next    = Cadence.next(dueFor, period, now)
        val skipped = Cadence.skipped(dueFor, period, now)
        if skipped > 0 then
          log.info(
            "recurring timer '{}' ran for {}; {} due time(s) since have passed and are skipped, " +
              "the next is {}",
            timer.name,
            dueFor,
            skipped,
            next
          )
        database
          .execute(TimerStore.advance(timer.name, dueFor, period, next))
          .map { changed =>
            observe(timer, FiredTimer.Outcome.Done, Option.when(changed > 0)(next))
            true
          }

  /** The handler failed: run again after the backoff, for the same due. */
  private def failed(timer: DueTimer): Future[Boolean] =
    val at = Instant.now(clock).plusSeconds(TimerStore.backoff(timer.attempts))
    val statement = timer.recurring match
      case None if timer.oldTable =>
        TimerStore.legacyReschedule(timer.name, timer.attempts, timer.dueAt.get, at)
      case None =>
        TimerStore.reschedule(timer.name, timer.attempts, timer.dueAt.get, timer.told, at)
      case Some((dueFor, period)) =>
        TimerStore.rescheduleRecurring(timer.name, timer.attempts, dueFor, period, at)
    database.execute(statement).map { changed =>
      observe(timer, FiredTimer.Outcome.Failed, Option.when(changed > 0)(at))
      false
    }

  private def observe(timer: DueTimer, outcome: FiredTimer.Outcome, next: Option[Instant]): Unit =
    try
      observer.fired(
        FiredTimer(timer.name, timer.told, timer.attempts, outcome, next, timer.recurring.map(_._2))
      )
    catch case NonFatal(e) => log.warn(s"a timer observer threw on '${timer.name}'", e)

/**
 * A timer the sweeper read.
 *
 * @param dueAt
 *   for a timer that fires once, `due_at` as the table holds it, so a statement matched on it
 *   matches exactly; `None` for a recurring timer, whose `due_at` is never read
 * @param periodMillis
 *   a recurring timer's period
 * @param dueFor
 *   the due the run is for, as the table holds it; `None` only for a timer written by a runtime
 *   from before recurring timers
 */
private[ankka] final case class DueTimer(
    name: String,
    componentId: ComponentId,
    method: MethodName,
    payload: Array[Byte],
    attempts: Int,
    dueAt: Option[Instant],
    periodMillis: Option[Long],
    dueFor: Option[Instant],
    /** Read from a table from before recurring timers, which has no `due_for` to write. */
    oldTable: Boolean = false
):
  /** The due and the period of a recurring timer. */
  def recurring: Option[(Instant, Long)] =
    for
      period <- periodMillis
      due    <- dueFor
      if dueAt.isEmpty
    yield due -> period

  /**
   * The due time this run is for, which the handler is told.
   *
   * A recurring timer's `due_for`. A timer that fires once is told its `due_at` on its first
   * attempt, and on a retry the `due_for` its backoff kept; a retry of one that failed under a
   * runtime from before recurring timers has none, and is told the `due_at` read, which is when its
   * backoff ended — the best there is.
   */
  def told: Instant =
    recurring.map(_._1).getOrElse {
      val at = dueAt.get
      if attempts == 0 then at else dueFor.getOrElse(at)
    }
