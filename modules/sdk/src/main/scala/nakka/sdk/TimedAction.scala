package nakka.sdk

import nakka.core.*
import nakka.core.effect.{TimedActionEffect, TimedActionEffects}

import scala.collection.mutable

/**
 * A call the runtime makes later, on your behalf.
 *
 * Stateless: a timed action coordinates other components rather than holding anything
 * itself. Delivery is at-least-once and a timer is only forgotten once its handler
 * reports success, so an action for work that has since become irrelevant must return
 * `effects.done()` — returning an error would reschedule it forever. That is the sharpest
 * edge in the whole timer API.
 */
abstract class TimedAction:

  private var contextOpt: Option[TimedActionContext] = None

  final type Effect = TimedActionEffect

  protected final val effects: TimedActionEffects = new TimedActionEffects()

  protected final def timerContext: TimedActionContext =
    contextOpt.getOrElse(
      throw IllegalStateException("timerContext is only available while a timer is firing")
    )

  private[nakka] def _setContext(ctx: Option[TimedActionContext]): Unit = contextOpt = ctx

/** Available while a scheduled call is running. */
trait TimedActionContext extends ComponentContext:
  /** The timer's name, so a handler can tell which schedule fired. */
  def timerName: String

  /** How many times this timer has already been attempted and failed. */
  def previousAttempts: Int

private[nakka] final case class SimpleTimedActionContext(
    componentId: ComponentId,
    componentClient: ComponentClient,
    timerName: String,
    previousAttempts: Int
) extends TimedActionContext

/**
 * A call captured for later execution.
 *
 * Holds the target and its already-encoded argument, so the schedule survives a restart
 * without needing the scheduling code's types to still be around.
 */
final case class DeferredCall(
    componentId: ComponentId,
    method: MethodName,
    payload: Array[Byte]
):
  override def toString: String = s"$componentId#$method"

/** A typed reference to a timed action handler taking one argument. */
final class TimedActionHandle[A, I] private[nakka] (
    val componentId: ComponentId,
    val name: MethodName,
    private[nakka] val inputSerializer: Serializer[I],
    private[nakka] val run: (A, I) => TimedActionEffect
):
  /** Captures this call with `input`, ready to schedule. */
  def deferred(input: I): DeferredCall =
    DeferredCall(componentId, name, inputSerializer.toBytes(input))

  private[nakka] def invoke(action: A, payload: Array[Byte]): TimedActionEffect =
    run(action, inputSerializer.fromBytes(payload))

/** A typed reference to a timed action handler taking no argument. */
final class NoArgTimedActionHandle[A] private[nakka] (
    val componentId: ComponentId,
    val name: MethodName,
    private[nakka] val run: A => TimedActionEffect
):
  def deferred: DeferredCall = DeferredCall(componentId, name, Array.emptyByteArray)

  // The payload is accepted and discarded so this matches `TimedActionHandle.invoke`,
  // letting the sweeper drive either kind of handler through one function type.
  private[nakka] def invoke(action: A, unusedPayload: Array[Byte]): TimedActionEffect =
    val _ = unusedPayload
    run(action)

/** Schedules and cancels deferred calls. */
trait TimerScheduler:

  /**
   * Schedules `call` to run once, after `delay`.
   *
   * Names are the identity: scheduling twice under one name replaces the earlier
   * schedule, which is what makes "extend the deadline" a single call rather than a
   * cancel-then-create race.
   */
  def createSingleTimer(name: String, delay: scala.concurrent.duration.FiniteDuration, call: DeferredCall): Unit

  /** Cancels a timer. Cancelling one that does not exist is not an error. */
  def delete(name: String): Unit

  /** Whether a timer with this name is still scheduled. */
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

    private val handlers = mutable.ListBuffer.empty[(MethodName, (A, Array[Byte]) => TimedActionEffect)]

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
    handlers: Map[MethodName, (A, Array[Byte]) => TimedActionEffect]
) extends ComponentDescriptor:
  val kind: ComponentKind = ComponentKind.TimedAction

  private[nakka] def handler(name: MethodName): Option[(A, Array[Byte]) => TimedActionEffect] =
    handlers.get(name)
