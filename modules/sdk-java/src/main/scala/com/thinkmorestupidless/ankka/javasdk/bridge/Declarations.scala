package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.annotations
import com.thinkmorestupidless.ankka.javasdk.codec.{EncodingException, RecordCodec, Serializer}
import com.thinkmorestupidless.ankka.javasdk.component.{
  EntityContext,
  EventSourcedEntity,
  KeyValueEntity,
  Workflow
}
import com.thinkmorestupidless.ankka.javasdk.effect.{Effect, ReadOnlyEffect, StepEffect}
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.javasdk.problems.DeclarationProblem

import java.lang.reflect.{
  Constructor,
  InvocationTargetException,
  Method,
  Modifier,
  ParameterizedType,
  Type,
  TypeVariable
}
import scala.collection.mutable

/** The kinds of Java component the bridge hosts. */
private[ankka] enum JavaKind:
  case EventSourced, KeyValue, Workflow

/** One handler, read from its method. */
private[ankka] final case class HandlerPlan(
    wireName: String,
    method: Method,
    input: Option[Serializer[Any]],
    output: Serializer[Any],
    readOnly: Boolean
):
  /** What a method reference's `SerializedLambda` names: class, method and JVM descriptor. */
  def javaKey: (String, String, String) =
    (method.getDeclaringClass.getName, method.getName, Declarations.descriptor(method))

  private val call = Invocable.caller(method)

  /** Calls the handler on `instance`; a handler's own exception is rethrown as itself. */
  def invoke(instance: AnyRef, input: Any): Any = call(instance, input)

/** One workflow step, read from its `@Step` method. */
private[ankka] final case class StepPlan(
    name: String,
    method: Method,
    input: Option[Serializer[Any]]
):
  def javaKey: (String, String, String) =
    (method.getDeclaringClass.getName, method.getName, Declarations.descriptor(method))

  private val call = Invocable.caller(method)

  def invoke(instance: AnyRef, input: Any): Any = call(instance, input)

/** A component class, read once: everything the hosts and the client need, or its problems. */
private[ankka] final case class ComponentPlan(
    cls: Class[?],
    componentId: String,
    kind: JavaKind,
    handlers: Vector[HandlerPlan],
    state: Serializer[Any],
    event: Option[Serializer[Any]],
    construct: EntityContext => AnyRef,
    steps: Vector[StepPlan] = Vector.empty,
    settings: sdk.WorkflowSettings = sdk.WorkflowSettings.default,
    snapshotEvery: Option[Int] = Some(100),
    replicated: Boolean = false
)

/** Reads a component class once, at start, collecting every problem rather than the first. */
private[ankka] object Declarations:

  /** Hand-written serializers, by the type they write. */
  type Serializers = Map[Class[?], Serializer[?]]

  def read(
      cls: Class[?],
      idOverride: Option[String],
      serializers: Serializers
  ): Either[Vector[DeclarationProblem], ComponentPlan] =
    val name     = cls.getSimpleName
    val problems = mutable.ArrayBuffer.empty[DeclarationProblem]
    def problem(member: String, message: String): Unit =
      problems += DeclarationProblem(name, member, message)

    val kind =
      if classOf[EventSourcedEntity[?, ?]].isAssignableFrom(cls) then Some(JavaKind.EventSourced)
      else if classOf[KeyValueEntity[?]].isAssignableFrom(cls) then Some(JavaKind.KeyValue)
      else if classOf[Workflow[?]].isAssignableFrom(cls) then Some(JavaKind.Workflow)
      else None
    if kind.isEmpty then
      problem("", "is not a component: extend EventSourcedEntity, KeyValueEntity or Workflow")

    val componentId = idOverride
      .orElse(Option(cls.getAnnotation(classOf[annotations.ComponentId])).map(_.value))
      .filter(_.nonEmpty)
    if componentId.isEmpty then
      problem("", "has no component id: annotate it @ComponentId(\"...\") or register(cls, id)")

    if Modifier.isAbstract(cls.getModifiers) then
      problem("", "is abstract; register a concrete class")

    val replicated    = cls.isAnnotationPresent(classOf[annotations.Replicated])
    val snapshotEvery = Option(cls.getAnnotation(classOf[annotations.SnapshotEvery])).map(_.value)
    if (replicated || snapshotEvery.isDefined) && kind.exists(_ != JavaKind.EventSourced) then
      problem("", "is @Replicated or @SnapshotEvery, which only an event sourced entity can be")
    snapshotEvery.filter(_ < 0).foreach { n =>
      problem("", s"snapshots every $n events; name a positive number, or 0 for never")
    }

    val construct = constructor(cls) match
      case Right(c)  => Some(c)
      case Left(why) => problem("", why); None

    val bindings = typeBindings(cls)
    def serializer(tpe: Type, member: String, what: String): Option[Serializer[Any]] =
      val resolved = resolve(tpe, bindings)
      Planner.rawClass(resolved).flatMap(serializers.get) match
        case Some(explicit) => Some(explicit.asInstanceOf[Serializer[Any]])
        case None =>
          try Some(RecordCodec.forType(resolved).asInstanceOf[Serializer[Any]])
          catch
            case e: EncodingException =>
              problem(member, s"its $what has no serializer: ${e.getMessage}")
              None

    val (state, event) = kind match
      case Some(JavaKind.EventSourced) =>
        val args = superArguments(classOf[EventSourcedEntity[?, ?]], bindings)
        (
          args.lift(0).flatMap(serializer(_, "", "state type")),
          args.lift(1).flatMap(serializer(_, "", "event type"))
        )
      case Some(JavaKind.KeyValue) =>
        val args = superArguments(classOf[KeyValueEntity[?]], bindings)
        (args.lift(0).flatMap(serializer(_, "", "state type")), None)
      case Some(JavaKind.Workflow) =>
        val args = superArguments(classOf[Workflow[?]], bindings)
        (args.lift(0).flatMap(serializer(_, "", "state type")), None)
      case None => (None, None)

    val handlers = mutable.ArrayBuffer.empty[HandlerPlan]
    val seen     = mutable.Map.empty[String, String]
    cls.getDeclaredMethods.sortBy(m => (m.getName, descriptor(m))).foreach { m =>
      val command = Option(m.getAnnotation(classOf[annotations.Command])).map(_.value)
      val query   = Option(m.getAnnotation(classOf[annotations.Query])).map(_.value)
      val member  = m.getName
      (command, query) match
        case (Some(_), Some(_)) =>
          problem(member, "is both @Command and @Query; it is one or the other")
        case (None, None) =>
          if declaredByHand(m) && classOf[Effect[?]].isAssignableFrom(m.getReturnType)
          then problem(member, "returns an effect but has no @Command or @Query")
        case _ =>
          val readOnly = query.isDefined
          val wire     = command.orElse(query).get
          if wire.isEmpty then problem(member, "has an empty wire name")
          else if wire.startsWith("ankka:") then
            problem(member, s"'$wire' uses the platform's reserved 'ankka:' prefix")
          else
            seen.get(wire) match
              case Some(other) => problem(member, s"has the wire name '$wire', as $other does")
              case None        => seen(wire) = member
          if Modifier.isStatic(m.getModifiers) then
            problem(member, "is static; a handler is an instance method")
          Invocable.problem(m).foreach(problem(member, _))
          val params = Invocable.parameterCount(m)
          if !classOf[Effect[?]].isAssignableFrom(Invocable.returnType(m)) then
            problem(member, "must return an Effect built by effects()")
          if readOnly && !classOf[ReadOnlyEffect[?]].isAssignableFrom(Invocable.returnType(m)) then
            problem(member, "is a @Query, so it must return a ReadOnlyEffect, which cannot persist")
          if params > 1 then
            problem(
              member,
              s"takes $params parameters; a handler takes one or none (use a record)"
            )
          val input =
            if params == 1 then serializer(Invocable.parameterTypes(m)(0), member, "parameter")
            else None
          val reply = replyType(Invocable.genericReturnType(m)) match
            case Some(t) => serializer(t, member, "reply")
            case None =>
              problem(member, "must name its reply type, such as Effect<Done>")
              None
          m.setAccessible(true)
          for
            out <- reply
            if params <= 1 && (params == 0 || input.isDefined)
          do handlers += HandlerPlan(wire, m, input, out, readOnly)
    }

    val steps = mutable.ArrayBuffer.empty[StepPlan]
    cls.getDeclaredMethods.sortBy(m => (m.getName, descriptor(m))).foreach { m =>
      val member = m.getName
      Option(m.getAnnotation(classOf[annotations.Step])).map(_.value) match
        case None =>
          if declaredByHand(m) && classOf[StepEffect].isAssignableFrom(m.getReturnType)
          then problem(member, "returns a StepEffect but has no @Step")
        case Some(_) if !kind.contains(JavaKind.Workflow) =>
          problem(member, "is a @Step, which only a workflow has")
        case Some(name) =>
          if name.isEmpty then problem(member, "has an empty step name")
          else if name.startsWith(sdk.WorkflowLifecycle.MethodPrefix) then
            problem(
              member,
              s"'$name' uses the platform's reserved '${sdk.WorkflowLifecycle.MethodPrefix}' prefix"
            )
          else
            steps.find(_.name == name).foreach { other =>
              problem(member, s"has the step name '$name', as ${other.method.getName} does")
            }
          if Modifier.isStatic(m.getModifiers) then
            problem(member, "is static; a step is an instance method")
          Invocable.problem(m).foreach(problem(member, _))
          val params = Invocable.parameterCount(m)
          if Invocable.returnType(m) != classOf[StepEffect] then
            problem(member, "must return a StepEffect built by stepEffects()")
          if params > 1 then
            problem(member, s"takes $params parameters; a step takes one or none (use a record)")
          val input =
            if params == 1 then serializer(Invocable.parameterTypes(m)(0), member, "input")
            else None
          m.setAccessible(true)
          if params == 0 || input.isDefined then steps += StepPlan(name, m, input)
    }

    val settings =
      if !kind.contains(JavaKind.Workflow) || problems.nonEmpty then sdk.WorkflowSettings.default
      else
        WorkflowSettingsReader
          .read(cls, componentId.get, construct.get, steps.toVector)
          .fold(
            found => {
              found.foreach((member, message) => problem(member, message));
              sdk.WorkflowSettings.default
            },
            identity
          )

    if problems.nonEmpty then Left(problems.toVector)
    else
      Right(
        ComponentPlan(
          cls,
          componentId.get,
          kind.get,
          handlers.toVector,
          state.get,
          event,
          construct.get,
          steps.toVector,
          settings,
          snapshotEvery = snapshotEvery.fold(Some(100))(n => Option.when(n > 0)(n)),
          replicated = replicated
        )
      )

  /**
   * Whether the developer wrote `m`, rather than a compiler: not synthetic, not a bridge, and with
   * no `$` in its name, which Kotlin gives the methods it makes of lambdas without marking them so.
   */
  private[ankka] def declaredByHand(m: Method): Boolean =
    !m.isSynthetic && !m.isBridge && !m.getName.contains('$')

  /** A no-argument constructor or one taking the context, of any visibility. */
  private[bridge] def constructor(cls: Class[?]): Either[String, EntityContext => AnyRef] =
    val constructors                                  = cls.getDeclaredConstructors.toVector
    def accessible(c: Constructor[?]): Constructor[?] = { c.setAccessible(true); c }
    def build(c: Constructor[?], args: EntityContext => Array[AnyRef]): EntityContext => AnyRef =
      ctx =>
        try c.newInstance(args(ctx)*).asInstanceOf[AnyRef]
        catch case e: InvocationTargetException => throw e.getCause
    constructors.find(_.getParameterTypes.toSeq == Seq(classOf[EntityContext])) match
      case Some(c) => Right(build(accessible(c), ctx => Array(ctx)))
      case None =>
        constructors.find(_.getParameterCount == 0) match
          case Some(c) => Right(build(accessible(c), _ => Array.empty))
          case None    => Left("has no constructor taking nothing or an EntityContext")

  /** `R` of a handler's `Effect<R>` or `ReadOnlyEffect<R>`. */
  private def replyType(t: Type): Option[Type] = t match
    case p: ParameterizedType => p.getActualTypeArguments.headOption
    case _                    => None

  /** The JVM method descriptor, as a `SerializedLambda` gives it. */
  def descriptor(m: Method): String =
    m.getParameterTypes.map(jvmName).mkString("(", "", ")") + jvmName(m.getReturnType)

  private def jvmName(c: Class[?]): String =
    if c.isArray then c.getName.replace('.', '/')
    else if c == java.lang.Void.TYPE then "V"
    else if c.isPrimitive then
      c match
        case java.lang.Integer.TYPE   => "I"
        case java.lang.Long.TYPE      => "J"
        case java.lang.Boolean.TYPE   => "Z"
        case java.lang.Double.TYPE    => "D"
        case java.lang.Float.TYPE     => "F"
        case java.lang.Short.TYPE     => "S"
        case java.lang.Byte.TYPE      => "B"
        case java.lang.Character.TYPE => "C"
        case _                        => "V"
    else "L" + c.getName.replace('.', '/') + ";"

  // ── Type arguments, resolved through the class hierarchy ──────────────────

  /** Every type variable in `cls`'s superclasses, bound to what `cls` gives it. */
  private[ankka] def typeBindings(cls: Class[?]): Map[TypeVariable[?], Type] =
    val out           = mutable.Map.empty[TypeVariable[?], Type]
    var current: Type = cls.getGenericSuperclass
    while current != null do
      current match
        case p: ParameterizedType =>
          val raw = p.getRawType.asInstanceOf[Class[?]]
          raw.getTypeParameters.zip(p.getActualTypeArguments).foreach { (v, a) =>
            out(v) = resolve(a, out.toMap)
          }
          current = raw.getGenericSuperclass
        case c: Class[?] => current = c.getGenericSuperclass
        case _           => current = null
    out.toMap

  private[bridge] def superArguments(
      base: Class[?],
      bindings: Map[TypeVariable[?], Type]
  ): Vector[Type] =
    base.getTypeParameters.toVector.flatMap(v => bindings.get(v))

  private[ankka] def resolve(t: Type, bindings: Map[TypeVariable[?], Type]): Type = t match
    case v: TypeVariable[?] => bindings.getOrElse(v, v)
    case _                  => t

/** Reads a raw class from a type for the serializer registry. */
private[ankka] object Planner:
  def rawClass(t: Type): Option[Class[?]] = t match
    case c: Class[?]          => Some(c)
    case p: ParameterizedType => rawClass(p.getRawType)
    case _                    => None
