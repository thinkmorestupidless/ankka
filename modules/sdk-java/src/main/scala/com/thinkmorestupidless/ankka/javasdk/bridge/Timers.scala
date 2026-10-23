package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentId, MethodName}
import com.thinkmorestupidless.ankka.core.effect as se
import com.thinkmorestupidless.ankka.javasdk.annotations
import com.thinkmorestupidless.ankka.javasdk.client.{
  ActionRef,
  CallerError,
  ComponentClient,
  NoArgActionRef,
  ServiceClients
}
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import com.thinkmorestupidless.ankka.javasdk.component.{TimedAction, TimerContext}
import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffect
import com.thinkmorestupidless.ankka.javasdk.problems.DeclarationProblem
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage
import com.thinkmorestupidless.ankka.javasdk.timer.Timers
import com.thinkmorestupidless.ankka.sdk

import java.lang.reflect.{Method, Modifier}
import java.time.{Clock, Duration, Instant}

/** One `@Action` method of a timed action. */
private[ankka] final case class ActionPlan(
    name: String,
    method: Method,
    input: Option[Serializer[Any]]
):
  def javaKey: (String, String, String) =
    (method.getDeclaringClass.getName, method.getName, Declarations.descriptor(method))

  def invoke(instance: AnyRef, input: Any): Any =
    if Invocable.parameterCount(method) == 0 then Invocable.invoke(method, instance)
    else Invocable.invoke(method, instance, input.asInstanceOf[AnyRef])

/** A timed action class, read once. */
private[ankka] final case class TimedActionPlan(
    cls: Class[?],
    componentId: String,
    actions: Vector[ActionPlan],
    construct: () => AnyRef
)

/** Method references to `@Action` methods, resolved to the call a timer stores. */
private[ankka] final class ActionIndex(plans: Vector[TimedActionPlan]):
  private val byKey = plans.flatMap(p => p.actions.map(a => a.javaKey -> (p, a))).toMap

  def isEmpty: Boolean = plans.isEmpty

  private def resolve(ref: AnyRef): (TimedActionPlan, ActionPlan) =
    val lambda = Refs.serialized(ref)
    val key = (
      lambda.getImplClass.replace('/', '.'),
      lambda.getImplMethodName,
      lambda.getImplMethodSignature
    )
    // A lambda is refused before the adapter is read through: a Java lambda that calls one action
    // reads exactly like Kotlin's adapter, and would be stored as that action.
    if key._2.startsWith("lambda$") then
      throw CallerError(
        "a timer names its action with a method reference, such as CartTimers::expire, not a lambda"
      )
    // Kotlin hands `Reminder::remind` over as an adapter of its own (`remind$remind`), which the
    // registry reads through to the action it calls, as it does a handler's.
    byKey
      .get(key)
      .orElse(Refs.throughAdapter(lambda, ref.getClass.getClassLoader).flatMap(byKey.get))
      .getOrElse(
        throw CallerError(
          s"${key._1}::${key._2} is not a registered @Action: it has no @Action, or " +
            s"${key._1.split('.').last} is not registered with this service"
        )
      )

  def call(ref: ActionRef[?, ?], input: Any): sdk.DeferredCall =
    val (plan, action) = resolve(ref)
    val serializer = action.input.getOrElse(
      throw CallerError(s"the action '${action.name}' takes no input; name it as a NoArgActionRef")
    )
    sdk.DeferredCall(
      ComponentId(plan.componentId),
      MethodName(action.name),
      serializer.toBytes(input)
    )

  def call(ref: NoArgActionRef[?]): sdk.DeferredCall =
    val (plan, action) = resolve(ref)
    if action.input.isDefined then
      throw CallerError(s"the action '${action.name}' takes an input; schedule it with one")
    sdk.DeferredCall(ComponentId(plan.componentId), MethodName(action.name), Array.emptyByteArray)

private[ankka] object ActionIndex:
  val empty: ActionIndex = ActionIndex(Vector.empty)

/** The Java timers over the Scala scheduler. */
private[ankka] final class JavaTimers(scala: sdk.TimerScheduler, index: ActionIndex) extends Timers:
  def schedule[A, I](name: String, delay: Duration, action: ActionRef[A, I], input: I): Unit =
    scala.createSingleTimer(name, Effects.finite(delay), index.call(action, input))
  def schedule[A](name: String, delay: Duration, action: NoArgActionRef[A]): Unit =
    scala.createSingleTimer(name, Effects.finite(delay), index.call(action))
  def scheduleRecurring[A, I](
      name: String,
      delay: Duration,
      period: Duration,
      action: ActionRef[A, I],
      input: I
  ): Unit =
    scala.createRecurringTimer(
      name,
      Effects.finite(delay),
      Effects.finite(period),
      index.call(action, input)
    )
  def scheduleRecurring[A](
      name: String,
      delay: Duration,
      period: Duration,
      action: NoArgActionRef[A]
  ): Unit =
    scala.createRecurringTimer(
      name,
      Effects.finite(delay),
      Effects.finite(period),
      index.call(action)
    )
  def cancel(name: String): Unit    = scala.delete(name)
  def exists(name: String): Boolean = scala.exists(name)

private[ankka] object JavaTimers:
  val unavailable: Timers = new Timers:
    private def refuse = throw IllegalStateException(
      "this service registers no timed action, so it has no timers to schedule"
    )
    def schedule[A, I](name: String, delay: Duration, action: ActionRef[A, I], input: I): Unit =
      refuse
    def schedule[A](name: String, delay: Duration, action: NoArgActionRef[A]): Unit = refuse
    def scheduleRecurring[A, I](
        name: String,
        delay: Duration,
        period: Duration,
        action: ActionRef[A, I],
        input: I
    ): Unit = refuse
    def scheduleRecurring[A](
        name: String,
        delay: Duration,
        period: Duration,
        action: NoArgActionRef[A]
    ): Unit = refuse
    def cancel(name: String): Unit    = refuse
    def exists(name: String): Boolean = refuse

/**
 * What a Java timed action reads while a timer runs it, set by its host before each run. Handed to
 * the constructor through a thread-local, as an entity's cell is.
 */
final class TimerCell private[bridge] ():
  @volatile private var current: Option[TimerContext] = None

  private[bridge] def set(context: Option[TimerContext]): Unit = current = context

  def context(): TimerContext = current.getOrElse(
    throw IllegalStateException(
      "the timer's context is only available while a timer runs the action"
    )
  )

object TimerCell:
  private val constructing = ThreadLocal[TimerCell]()

  def take(): TimerCell =
    val cell = constructing.get()
    if cell == null then TimerCell()
    else
      constructing.remove()
      cell

  private[bridge] def constructing[A](cell: TimerCell)(build: => A): A =
    constructing.set(cell)
    try build
    finally constructing.remove()

private final class JavaTimerContext(
    ctx: sdk.TimedActionContext,
    client: ComponentClient,
    serviceClients: ServiceClients,
    javaTimers: Timers
) extends TimerContext:
  def secrets(): SecretStore             = Stores.secrets(ctx.secrets)
  def storage(): ObjectStorage           = Stores.storage(ctx.storage)
  def timerName(): String                = ctx.timerName
  def previousAttempts(): Int            = ctx.previousAttempts
  def dueTime(): Instant                 = ctx.dueTime
  def clock(): Clock                     = ctx.clock
  def timers(): Timers                   = javaTimers
  def componentClient(): ComponentClient = client
  def services(): ServiceClients         = serviceClients

private[ankka] object TimedActions:

  def isTimedAction(cls: Class[?]): Boolean = classOf[TimedAction].isAssignableFrom(cls)

  def read(
      cls: Class[?],
      idOverride: Option[String],
      serializers: Declarations.Serializers
  ): Either[Vector[DeclarationProblem], TimedActionPlan] =
    val reader  = Reader(cls, idOverride, serializers, Vector.empty)
    val actions = Vector.newBuilder[ActionPlan]
    val seen    = scala.collection.mutable.Map.empty[String, String]
    cls.getDeclaredMethods.sortBy(m => (m.getName, Declarations.descriptor(m))).foreach { m =>
      val member = m.getName
      Option(m.getAnnotation(classOf[annotations.Action])).map(_.value) match
        case None =>
          if Declarations.declaredByHand(m) && m.getReturnType == classOf[TimedActionEffect] then
            reader.problem(member, "returns a TimedActionEffect but has no @Action")
        case Some(name) =>
          if name.isEmpty then reader.problem(member, "has an empty action name")
          else
            seen.get(name) match
              case Some(other) =>
                reader.problem(member, s"has the action name '$name', as $other does")
              case None => seen(name) = member
          if Modifier.isStatic(m.getModifiers) then
            reader.problem(member, "is static; an action is an instance method")
          Invocable.problem(m).foreach(reader.problem(member, _))
          val params = Invocable.parameterCount(m)
          if Invocable.returnType(m) != classOf[TimedActionEffect] then
            reader.problem(member, "must return a TimedActionEffect built by effects()")
          if params > 1 then
            reader.problem(
              member,
              s"takes $params parameters; an action takes one or none (use a record)"
            )
          val input =
            if params == 1 then reader.serializer(Invocable.parameterTypes(m)(0), member, "input")
            else None
          m.setAccessible(true)
          if params == 0 || input.isDefined then actions += ActionPlan(name, m, input)
    }
    val found = actions.result()
    if found.isEmpty then reader.problem("", "is a timed action with no @Action method")
    if reader.problems.nonEmpty then Left(reader.problems)
    else Right(TimedActionPlan(cls, reader.componentId.get, found, reader.construct.get))

  def descriptor(
      plan: TimedActionPlan,
      refs: Refs,
      index: ActionIndex,
      serializers: Declarations.Serializers
  ): ComponentDescriptor =
    new sdk.TimedAction.Companion[JavaTimedActionHost](ComponentId(plan.componentId)):
      def create(ctx: sdk.TimedActionContext): JavaTimedActionHost =
        JavaTimedActionHost(plan, refs, index, serializers)

      plan.actions.foreach { a =>
        val where = s"${plan.cls.getSimpleName}.${a.method.getName}"
        def run(host: JavaTimedActionHost, input: Any): se.TimedActionEffect =
          Effects.unwrap(a.invoke(host.java, input), where).asInstanceOf[se.TimedActionEffect]
        a.input match
          case Some(in) =>
            handler[Any](a.name)(host => i => run(host, i))(using CodecBridge.toScala(in)): Unit
          case None => handler(a.name)(host => run(host, null)): Unit
      }
    .descriptor

/** A Java timed action, hosted. */
private[ankka] final class JavaTimedActionHost(
    plan: TimedActionPlan,
    refs: Refs,
    index: ActionIndex,
    serializers: Declarations.Serializers
) extends sdk.TimedAction:
  private val cell         = TimerCell()
  private[bridge] val java = TimerCell.constructing(cell)(plan.construct())

  override private[ankka] def _setContext(ctx: Option[sdk.TimedActionContext]): Unit =
    super._setContext(ctx)
    cell.set(ctx.map { c =>
      JavaTimerContext(
        c,
        Clients.of(c.componentClient, refs),
        Services.of(c.services, serializers),
        JavaTimers(c.timers, index)
      )
    })
