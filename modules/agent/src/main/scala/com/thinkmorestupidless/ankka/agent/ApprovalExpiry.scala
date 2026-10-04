package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.agent.autonomous.forAutonomousAgent
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.{DurationLong, FiniteDuration}

/**
 * Decides an approval request as refused when its time limit passes with no decision.
 *
 * A timed action, so the deadline outlives whatever process set it: the timer is a row in the
 * timers table, swept by the cluster's singleton. When it fires the expiry is sent as an ordinary
 * decision, by the platform. A request decided, discarded or no longer held by then answers with a
 * conflict or as not found, and the expiry has nothing left to do.
 */
final class ApprovalExpiry(context: TimedActionContext) extends TimedAction:

  def expire(due: ApprovalExpiry.Due): Effect =
    val decision = Decision.expired(due.approvalId, System.currentTimeMillis())
    try
      due.kind match
        case ApprovalExpiry.RequestAgent =>
          context.componentClient
            .forAgent(SessionId(due.id))
            .decideAsPlatform(ComponentId(due.componentId), decision)
        case _ =>
          context.componentClient
            .forAutonomousAgent(ComponentId(due.componentId), due.id)
            .decide(decision): Unit
      effects.done()
    catch
      case error: CommandError
          if error.code == ErrorCode.Conflict || error.code == ErrorCode.NotFound =>
        effects.done()

object ApprovalExpiry
    extends TimedAction.Companion[ApprovalExpiry](ComponentId("ankka-approval-expiry")):

  val RequestAgent: String    = "agent"
  val AutonomousAgent: String = "autonomous"

  /** Which approval request is due: the kind of agent, its component, its session or instance. */
  final case class Due(kind: String, componentId: String, id: String, approvalId: String)

  given Serializer[Due] = Codecs.serializer[Due]("ankka-approval-due")

  def create(context: TimedActionContext) = new ApprovalExpiry(context)

  val expire = handler("expire")(_.expire)

  /** The platform's own: no service wrote it, and the console marks it so. */
  def platformDescriptor: TimedActionDescriptor[ApprovalExpiry] =
    descriptor.copy(platform = true)

  /** A timer's name is its identity: one per approval request. */
  def timerName(due: Due): String = s"approval:${due.componentId}:${due.id}:${due.approvalId}"

  /**
   * Schedules a request's expiry, before the request is recorded: a timer that finds nothing
   * recorded finds the id not held and is done, while a request recorded with no timer would wait
   * for ever.
   */
  private[ankka] def schedule(timers: TimerScheduler, due: Due, expiresAt: Long): Unit =
    val delay: FiniteDuration = (expiresAt - System.currentTimeMillis()).max(0L).millis
    timers.createSingleTimer(timerName(due), delay, expire.deferred(due))

  /** Forgets a request's expiry once it is decided. Best effort: a late timer finds it decided. */
  private[ankka] def forget(timers: TimerScheduler, due: Due): Unit =
    try timers.delete(timerName(due))
    catch case scala.util.control.NonFatal(_) => ()

  /** Why a time limit cannot be honoured in a service with no timers. */
  private[ankka] def needsTimers(what: String): String =
    s"$what declares a time limit for approval, which needs the service's TimerRuntime: " +
      "register TimerRuntime() with the service"
