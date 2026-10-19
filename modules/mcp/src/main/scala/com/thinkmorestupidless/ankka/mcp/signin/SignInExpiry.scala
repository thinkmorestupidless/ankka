package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * Deletes a sign-in record whose time is up (feature 054). The record is already forgotten by then
 * (its state's own expiry, which every read sees); this removes it, so what was kept of a person
 * does not outlive it. A record written again since is still live, reads as such, and is left.
 */
final class SignInExpiry(context: TimedActionContext) extends TimedAction:

  def expire(due: SignInExpiry.Due): Effect =
    val record = context.componentClient.forKeyValueEntity(EntityId(due.recordId))
    if record.call(SignInRecordEntity.get).invoke() == SignInRecord.Absent then
      record.call(SignInRecordEntity.delete).invoke(): Unit
    effects.done()

object SignInExpiry
    extends TimedAction.Companion[SignInExpiry](ComponentId("ankka-sign-in-expiry")):

  /** Which record is due. */
  final case class Due(recordId: String)

  given Serializer[Due] = Codecs.serializer[Due]("ankka-sign-in-due")

  def create(context: TimedActionContext) = new SignInExpiry(context)

  val expire = handler("expire")(_.expire)

  /** The platform's own: no service wrote it, and the console marks it so. */
  def platformDescriptor: TimedActionDescriptor[SignInExpiry] = descriptor.copy(platform = true)

  /** One timer per record, so a record written again moves its expiry rather than adding one. */
  def timerName(recordId: String): String = s"sign-in:$recordId"

  /** Schedules a record's deletion at `at`, a minute late, so the record has expired by then. */
  private[signin] def schedule(
      timers: TimerScheduler,
      recordId: String,
      at: Instant,
      now: Instant
  ): Unit =
    val delay: FiniteDuration = (at.toEpochMilli - now.toEpochMilli).max(0L).millis + 1.minute
    try timers.createSingleTimer(timerName(recordId), delay, expire.deferred(Due(recordId)))
    catch case NonFatal(_) => () // best effort: the record has expired whether or not it is deleted

  /** Forgets a record's deletion once the record is taken. Best effort. */
  private[signin] def forget(timers: TimerScheduler, recordId: String): Unit =
    try timers.delete(timerName(recordId))
    catch case NonFatal(_) => ()
