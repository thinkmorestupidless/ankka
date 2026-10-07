package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.{TimedActionEffect, TimedActionEffects}

import scala.collection.mutable

/**
 * A call the runtime makes later, on your behalf.
 *
 * Stateless: a timed action coordinates other components rather than holding anything itself.
 * Delivery is at-least-once and a timer is only forgotten once its handler reports success, so an
 * action for work that has since become irrelevant must return `effects.done()` — returning an
 * error would reschedule it forever. That is the sharpest edge in the whole timer API.
 */
abstract class TimedAction:

  private var contextOpt: Option[TimedActionContext] = None

  final type Effect = TimedActionEffect

  protected final val effects: TimedActionEffects = new TimedActionEffects()

  protected final def timerContext: TimedActionContext =
    contextOpt.getOrElse(
      throw IllegalStateException("timerContext is only available while a timer is firing")
    )

  private[ankka] def _setContext(ctx: Option[TimedActionContext]): Unit = contextOpt = ctx

/** Available while a scheduled call is running. */
trait TimedActionContext extends ComponentContext:
  /** The timer's name, so a handler can tell which schedule fired. */
  def timerName: String

  /** How many times this timer has already been attempted and failed. */
  def previousAttempts: Int

  /**
   * The due time this run is for. A retry is told the due time of the attempt that failed, so it is
   * the same on every run for one due, and a key to make a repeated run safe with. For a recurring
   * timer, successive values are its first due plus a whole number of periods. It is not when the
   * handler was called: the sweeper polls, so a run starts up to a poll interval later.
   */
  def dueTime: java.time.Instant

  /** The service's secret store. */
  def secrets: SecretStore

  /** Other services, called as this one. */
  def services: ServiceClients

private[ankka] final case class SimpleTimedActionContext(
    componentId: ComponentId,
    componentClient: ComponentClient,
    timerName: String,
    previousAttempts: Int,
    dueTime: java.time.Instant,
    secrets: SecretStore,
    services: ServiceClients
) extends TimedActionContext

/**
 * A call captured for later execution.
 *
 * Holds the target and its already-encoded argument, so the schedule survives a restart without
 * needing the scheduling code's types to still be around.
 */
final case class DeferredCall(
    componentId: ComponentId,
    method: MethodName,
    payload: Array[Byte]
):
  override def toString: String = s"$componentId#$method"

/** A typed reference to a timed action handler taking one argument. */
final class TimedActionHandle[A, I] private[ankka] (
    val componentId: ComponentId,
    val name: MethodName,
    private[ankka] val inputSerializer: Serializer[I],
    private[ankka] val run: (A, I) => TimedActionEffect
):
  /** Captures this call with `input`, ready to schedule. */
  def deferred(input: I): DeferredCall =
    DeferredCall(componentId, name, inputSerializer.toBytes(input))

  private[ankka] def invoke(action: A, payload: Array[Byte]): TimedActionEffect =
    run(action, inputSerializer.fromBytes(payload))

/** A typed reference to a timed action handler taking no argument. */
final class NoArgTimedActionHandle[A] private[ankka] (
    val componentId: ComponentId,
    val name: MethodName,
    private[ankka] val run: A => TimedActionEffect
):
  def deferred: DeferredCall = DeferredCall(componentId, name, Array.emptyByteArray)

  // The payload is accepted and discarded so this matches `TimedActionHandle.invoke`,
  // letting the sweeper drive either kind of handler through one function type.
  private[ankka] def invoke(action: A, unusedPayload: Array[Byte]): TimedActionEffect =
    val _ = unusedPayload
    run(action)

/** Schedules and cancels deferred calls. */
trait TimerScheduler:

  /**
   * Schedules `call` to run once, after `delay`.
   *
   * Names are the identity: scheduling twice under one name replaces the earlier schedule, which is
   * what makes "extend the deadline" a single call rather than a cancel-then-create race.
   */
  def createSingleTimer(
      name: String,
      delay: scala.concurrent.duration.FiniteDuration,
      call: DeferredCall
  ): Unit

  /**
   * Schedules `call` to run first after `delay` and then once every `period`, until it is deleted
   * or replaced. Each next due is the previous due plus the period, never the time the handler
   * finished; when that has already passed — the service was down, the handler kept failing, or a
   * run outlasted a period — the next due is the first cadence point still to come, and the periods
   * between are not fired. A delay of zero or less is due at once.
   *
   * Scheduling again under a name that already holds a recurring timer for the same handler with
   * the same period keeps its next due and takes the new payload, so this is safe to call every
   * time a service starts. Anything else under an existing name replaces it.
   *
   * A period is from one millisecond to 36,500 days; anything else is refused before anything is
   * stored.
   */
  def createRecurringTimer(
      name: String,
      delay: scala.concurrent.duration.FiniteDuration,
      period: scala.concurrent.duration.FiniteDuration,
      call: DeferredCall
  ): Unit

  /** Cancels a timer, of either kind. Cancelling one that does not exist is not an error. */
  def delete(name: String): Unit

  /** Whether a timer with this name is still scheduled, of either kind. */
  def exists(name: String): Boolean

object TimedAction:

  /**
   * Declares a timed action to the runtime.
   *
   * {{{
   * object OrderTimers extends TimedAction.Companion[OrderTimers](ComponentId("order-timers")):
   *   def create(ctx: TimedActionContext) = new OrderTimers(ctx)
   *   val expire = handler("expire")(_.expireOrder)
   * }}}
   */
  abstract class Companion[A <: TimedAction](val componentId: ComponentId):

    private val handlers =
      mutable.ListBuffer.empty[(MethodName, (A, Array[Byte]) => TimedActionEffect)]

    def create(ctx: TimedActionContext): A

    protected final def handler[I](name: String)(
        f: A => I => TimedActionEffect
    )(using in: Serializer[I]): TimedActionHandle[A, I] =
      val handle =
        new TimedActionHandle[A, I](componentId, MethodName(name), in, (a, i) => f(a)(i))
      handlers += (handle.name -> handle.invoke)
      handle

    protected final def handler(name: String)(
        f: A => TimedActionEffect
    ): NoArgTimedActionHandle[A] =
      val handle = new NoArgTimedActionHandle[A](componentId, MethodName(name), f)
      handlers += (handle.name -> handle.invoke)
      handle

    /** A `def`, not a `val` — see the note on `EventSourcedEntity.Companion`. */
    final def descriptor: TimedActionDescriptor[A] =
      val clashes = handlers.groupBy(_._1).collect {
        case (name, hs) if hs.sizeIs > 1 => s"handler '$name' registered ${hs.size} times"
      }
      if clashes.nonEmpty then
        throw IllegalArgumentException(
          clashes.mkString(s"invalid timed action '$componentId':\n  - ", "\n  - ", "")
        )
      TimedActionDescriptor(componentId, create, handlers.toMap)

/** The registered form of a timed action. */
final case class TimedActionDescriptor[A <: TimedAction](
    componentId: ComponentId,
    create: TimedActionContext => A,
    handlers: Map[MethodName, (A, Array[Byte]) => TimedActionEffect],
    override val platform: Boolean = false
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.TimedAction

  override def declaredHandlers: Vector[DeclaredHandler] =
    DeclaredHandler.sorted(handlers.keys.map(m => DeclaredHandler(m.toString, HandlerKind.Action)))

  private[ankka] def handler(name: MethodName): Option[(A, Array[Byte]) => TimedActionEffect] =
    handlers.get(name)
