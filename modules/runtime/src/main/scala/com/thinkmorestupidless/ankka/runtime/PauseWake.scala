package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.{DeferredCall, TimerScheduler}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * The durable wake of a paused workflow: a row in the timer table, due at the pause's deadline,
 * that the timer sweeper turns into a call of the reserved `ankka:pause-wake` on the workflow.
 *
 * A pause's deadline is journalled, and the engine arms an actor timer for it, but an actor timer
 * lives only as long as the actor. Sharding passivates an idle entity, and a timer's message to
 * itself is not traffic, so a workflow paused for longer than the idle timeout stops with its timer
 * and nothing starts it again. The row is what does. The actor timer stays, for precision while the
 * workflow is awake; whichever arrives first ends the pause, and the engine ignores the other.
 *
 * The call names the deadline it is for. A workflow no longer paused on that deadline — it was
 * resumed by a command, timed out by its actor timer, or paused again since — answers it and does
 * nothing.
 */
private[ankka] object PauseWake:

  /**
   * The reserved method the sweeper calls. Internal: `Workflow.Companion` refuses handler names
   * with the `ankka:` prefix, so no service's handler can collide with it.
   */
  val Method: MethodName = MethodName("ankka:pause-wake")

  /** The one row a workflow instance's pause is held in. */
  def timerName(componentId: ComponentId, workflowId: EntityId): String =
    s"ankka-pause-$componentId-$workflowId"

  /** The row's payload: the deadline, then the workflow's id, which the row has no column for. */
  def payload(workflowId: EntityId, deadlineMillis: Long): Array[Byte] =
    s"$deadlineMillis:$workflowId".getBytes(UTF_8)

  /** The workflow and the deadline a row's payload names. */
  def read(payload: Array[Byte]): Option[(EntityId, Long)] =
    val text  = String(payload, UTF_8)
    val colon = text.indexOf(':')
    if colon <= 0 then None
    else
      for
        deadline <- text.take(colon).toLongOption
        id       <- EntityId.parse(text.drop(colon + 1)).toOption
      yield id -> deadline

  /** What the sweeper sends the workflow: the deadline the wake is for. */
  def callPayload(deadlineMillis: Long): Array[Byte] = deadlineMillis.toString.getBytes(UTF_8)

  /** The deadline a wake names, read by the engine. */
  def deadlineOf(payload: Array[Byte]): Option[Long] =
    String(payload, UTF_8).trim.toLongOption

/**
 * Keeps one workflow instance's wake row in step with its pause.
 *
 * Called on the actor's thread only, after a persist; the writes run one after another off it, on a
 * virtual thread, so a delete and a later set cannot overtake each other and the actor never waits
 * on the database. A failed write is logged and not retried: the actor timer still runs while the
 * workflow is awake, and the next pause, or the next recovery while paused, writes the row again.
 */
private[ankka] final class PauseWakes(
    componentId: ComponentId,
    workflowId: EntityId,
    scheduler: () => TimerScheduler
):
  private val log: Logger = LoggerFactory.getLogger("ankka.workflows")
  private val name        = PauseWake.timerName(componentId, workflowId)

  /** The deadline a row was last written for by this instance, if one may exist. */
  private var armed: Option[Long]  = None
  private var writes: Future[Unit] = Future.unit

  /**
   * Makes the row say `deadline`: set for a pause with one, removed when the workflow is not paused
   * on a deadline any more. Nothing is written when the row already says so.
   */
  def sync(deadline: Option[Long]): Unit =
    if deadline != armed then
      armed = deadline
      deadline match
        case Some(at) => write(s"set the wake of '$name'")(set(at))
        case None     => write(s"remove the wake of '$name'")(scheduler().delete(name))

  /**
   * After recovery while paused: the row is written again, since a process that stopped between the
   * pause's persist and its row's write left none.
   */
  def recovered(deadline: Long): Unit =
    armed = Some(deadline)
    write(s"set the wake of '$name'")(set(deadline))

  private def set(deadline: Long): Unit =
    val delay = (deadline - System.currentTimeMillis()).max(0L)
    scheduler().createSingleTimer(
      name,
      delay.millis,
      DeferredCall(componentId, PauseWake.Method, PauseWake.payload(workflowId, deadline))
    )

  private def write(what: String)(op: => Unit): Unit =
    given ExecutionContext = AnkkaExecutors.virtual
    writes = writes.flatMap { _ =>
      Future(op).recover { case NonFatal(e) =>
        log.warn(
          s"could not $what; the pause ends on time only while the workflow stays awake",
          e
        )
      }
    }
