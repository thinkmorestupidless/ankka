package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations
import com.thinkmorestupidless.ankka.javasdk.codec.{EncodingException, RecordCodec, Serializer}
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource as JavaSource
import com.thinkmorestupidless.ankka.javasdk.problems.DeclarationProblem
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.sdk.TopicOptions

import java.lang.reflect.{InvocationTargetException, Modifier, Type}
import java.util.OptionalInt
import scala.collection.mutable

/**
 * What every component read from a class shares, beyond the entities': the id, a constructor taking
 * nothing, serializers for its type arguments, its source resolved against the service's entities
 * and workflows, and the hooks read from one instance made as the host makes it. Problems collect
 * rather than stop, so a start reports every one at once.
 */
private[bridge] final class Reader(
    val cls: Class[?],
    idOverride: Option[String],
    serializers: Declarations.Serializers,
    entities: Vector[ComponentPlan]
):
  private val found = mutable.ArrayBuffer.empty[DeclarationProblem]

  def problem(member: String, message: String): Unit =
    found += DeclarationProblem(cls.getSimpleName, member, message)

  def problems: Vector[DeclarationProblem] = found.toVector

  val annotated: Option[String] =
    Option(cls.getAnnotation(classOf[annotations.ComponentId])).map(_.value)

  val componentId: Option[String] = idOverride.orElse(annotated).filter(_.nonEmpty)
  if componentId.isEmpty then
    problem("", "has no component id: annotate it @ComponentId(\"...\") or register(cls, id)")
  if Modifier.isAbstract(cls.getModifiers) then
    problem("", "is abstract; register a concrete class")

  val construct: Option[() => AnyRef] =
    cls.getDeclaredConstructors.find(_.getParameterCount == 0) match
      case Some(c) =>
        c.setAccessible(true)
        Some(() =>
          try c.newInstance().asInstanceOf[AnyRef]
          catch case e: InvocationTargetException => throw e.getCause
        )
      case None =>
        problem("", "has no constructor taking nothing")
        None

  private val bindings = Declarations.typeBindings(cls)

  /** The type arguments `cls` gives `base`, in order. */
  def arguments(base: Class[?]): Vector[Type] = Declarations.superArguments(base, bindings)

  def serializer(tpe: Type, member: String, what: String): Option[Serializer[Any]] =
    val resolved = Declarations.resolve(tpe, bindings)
    Planner.rawClass(resolved).flatMap(serializers.get) match
      case Some(explicit) => Some(explicit.asInstanceOf[Serializer[Any]])
      case None           =>
        try Some(RecordCodec.forType(resolved).asInstanceOf[Serializer[Any]])
        catch
          case e: EncodingException =>
            problem(member, s"its $what has no serializer: ${e.getMessage}")
            None

  def source(java: JavaSource[?], member: String): Option[sdk.ChangeSource[Any]] =
    java.kind match
      case JavaSource.Kind.ENTITY =>
        entities.find(_.cls == java.component) match
          case Some(plan) if plan.kind == JavaKind.EventSourced =>
            Some(
              sdk.ChangeSource.EventSourced(
                ComponentId(plan.componentId),
                CodecBridge.toScala(plan.event.get)
              )
            )
          case Some(plan) if plan.kind == JavaKind.KeyValue =>
            Some(
              sdk.ChangeSource
                .KeyValue(ComponentId(plan.componentId), CodecBridge.state(plan.state))
            )
          case Some(_) =>
            problem(
              member,
              s"reads ${java.component.getSimpleName} as an entity, but it is a workflow; " +
                "read it with ChangeSource.ofWorkflow"
            )
            None
          case None =>
            problem(
              member,
              s"reads ${java.component.getSimpleName}, which the service does not register as an " +
                "entity"
            )
            None
      case JavaSource.Kind.WORKFLOW =>
        entities.find(p => p.cls == java.component && p.kind == JavaKind.Workflow) match
          case Some(plan) =>
            Some(
              sdk.ChangeSource
                .Workflow(ComponentId(plan.componentId), CodecBridge.state(plan.state))
            )
          case None =>
            problem(
              member,
              s"reads the workflow ${java.component.getSimpleName}, which the service does not " +
                "register"
            )
            None
      case JavaSource.Kind.TOPIC =>
        serializer(java.`type`, member, "topic's message type").map { decoder =>
          sdk.ChangeSource.Topic(
            java.topic,
            CodecBridge.toScala(decoder),
            Option(java.start).map {
              case JavaSource.Start.EARLIEST => sdk.StartFrom.Earliest
              case JavaSource.Start.LATEST   => sdk.StartFrom.Latest
              case JavaSource.Start.AT       => sdk.StartFrom.At(java.startAt)
            },
            TopicOptions(
              contract = Option(java.contract).map(Topics.scala),
              broker = Option(java.broker),
              parallel = java.isParallel,
              project = Option(java.project)
            )
          )
        }

  // The component's own hooks are read from one instance, made here as its host makes them.
  private lazy val probe: Option[AnyRef] = construct.flatMap { make =>
    try Some(ViewCell.constructing(ViewCell())(make()))
    catch
      case e: Exception =>
        problem("", s"could not be constructed: $e")
        None
  }

  def hook[A](what: String)(read: AnyRef => A): Option[A] =
    probe.flatMap { instance =>
      try Some(read(instance))
      catch
        case e: Exception =>
          problem(what, s"threw $e")
          None
    }

  def version(read: AnyRef => OptionalInt): Option[Int] =
    hook("version")(read).flatMap { v =>
      if v.isPresent && v.getAsInt < 1 then
        problem("version", s"is ${v.getAsInt}; a version is 1 or more")
      if v.isPresent then Some(v.getAsInt) else None
    }

  def parallelism(read: AnyRef => Int): Int =
    val p = hook("parallelism")(read).getOrElse(1)
    if p < 1 then problem("parallelism", s"is $p; it is 1 or more")
    p

  def declaredSource(read: AnyRef => JavaSource[?]): Option[sdk.ChangeSource[Any]] =
    hook("source")(read).flatMap {
      case null => problem("source", "returned null"); None
      case s    => source(s, "source")
    }
