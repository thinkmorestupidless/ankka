package com.thinkmorestupidless.ankka.javasdk.testkit.bridge

import com.thinkmorestupidless.ankka.core.{CommandError, MethodName}
import com.thinkmorestupidless.ankka.javasdk.bridge.{
  Adapters,
  ComponentPlan,
  Effects,
  JavaEventSourcedHost,
  JavaKeyValueHost,
  JavaServiceBuilder,
  Refs
}
import com.thinkmorestupidless.ankka.javasdk.client.{CallerError, CommandRef, NoArgRef}
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.testkit.Result
import com.thinkmorestupidless.ankka.sdk.{CommandHandle, HandlerBinding, NoArgHandle}
import com.thinkmorestupidless.ankka.testkit as stk

import java.util.List as JList
import scala.jdk.CollectionConverters.*

/** The Java component kits, each driving the Scala kit of the component's own companion. */
object EntityKits:

  def eventSourced(cls: Class[?], entityId: String, events: JList[?]): EventSourced =
    EventSourced(cls, entityId, events.asScala.toVector)

  def keyValue(cls: Class[?], entityId: String): KeyValue = KeyValue(cls, entityId)

  /** One component read as the service reads it, and its handlers by wire name. */
  private final class Hosted(cls: Class[?]):
    private val read =
      JavaServiceBuilder.read(Vector(cls -> None), Map.empty, checkReferences = false)
    val plan: ComponentPlan = read.plans.head
    val refs: Refs          = read.refs

    def handler(ref: AnyRef): String =
      val (component, handler) =
        try refs.resolve(ref)
        catch
          case e: CallerError =>
            throw CallerError(s"${e.getMessage}; this kit drives ${plan.cls.getSimpleName}")
      if component.cls != plan.cls then
        throw CallerError(
          s"${component.cls.getSimpleName}::${handler.method.getName} is not a handler of " +
            s"${plan.cls.getSimpleName}, the entity this kit drives"
        )
      handler.wireName

  private def binding[H](bindings: MethodName => Option[HandlerBinding[H]], name: String) =
    bindings(MethodName(name)).getOrElse(throw IllegalStateException(s"no handler '$name'"))

  final class EventSourced private[bridge] (cls: Class[?], entityId: String, recorded: Vector[Any]):
    private val hosted    = Hosted(cls)
    private val companion = Adapters.eventSourcedCompanion(hosted.plan, hosted.refs)
    private val handlers  = companion.descriptor
    private var history   = recorded
    private var kit       = recovered()

    private def recovered() =
      val k = stk.EventSourcedTestKit.of(companion, entityId)
      k.replay(history*)
      k

    def call[C, I, O](ref: CommandRef[C, I, O], input: I): Result[O] =
      run(binding(handlers.handler, hosted.handler(ref)), Some(input))

    def call[C, O](ref: NoArgRef[C, O]): Result[O] =
      run(binding(handlers.handler, hosted.handler(ref)), None)

    private def run[O](b: HandlerBinding[JavaEventSourcedHost], input: Option[Any]): Result[O] =
      val result = (b, input) match
        case (h: CommandHandle[JavaEventSourcedHost, Any, Any] @unchecked, Some(in)) =>
          kit.call(h)(in)
        case (h: NoArgHandle[JavaEventSourcedHost, Any] @unchecked, None) => kit.call(h)
        case _ =>
          throw CallerError(
            s"'${b.name}' ${if input.isEmpty then "takes an input" else "takes none"}"
          )
      history = history ++ result.events
      KitResult[O](result.reply, result.events, result.state)

    def state(): Any         = kit.currentState
    def events(): JList[Any] = history.asJava
    def deleted(): Boolean   = kit.isDeleted
    def restart(): Unit      = kit = recovered()

  final class KeyValue private[bridge] (cls: Class[?], entityId: String):
    private val hosted    = Hosted(cls)
    private val companion = Adapters.keyValueCompanion(hosted.plan, hosted.refs)
    private val handlers  = companion.descriptor
    private val kit       = stk.KeyValueEntityTestKit.of(companion, entityId)

    def call[C, I, O](ref: CommandRef[C, I, O], input: I): Result[O] =
      run(binding(handlers.handler, hosted.handler(ref)), Some(input))

    def call[C, O](ref: NoArgRef[C, O]): Result[O] =
      run(binding(handlers.handler, hosted.handler(ref)), None)

    private def run[O](b: HandlerBinding[JavaKeyValueHost], input: Option[Any]): Result[O] =
      val result = (b, input) match
        case (h: CommandHandle[JavaKeyValueHost, Any, Any] @unchecked, Some(in)) => kit.call(h)(in)
        case (h: NoArgHandle[JavaKeyValueHost, Any] @unchecked, None)            => kit.call(h)
        case _ =>
          throw CallerError(
            s"'${b.name}' ${if input.isEmpty then "takes an input" else "takes none"}"
          )
      KitResult[O](result.reply, Vector.empty, result.state)

    def state(): Any       = kit.currentState
    def deleted(): Boolean = kit.isDeleted

private final case class KitResult[O](
    outcome: Either[CommandError, Option[Any]],
    persisted: Vector[Any],
    after: Any
) extends Result[O]:
  def reply(): O = outcome match
    case Right(Some(value)) => value.asInstanceOf[O]
    case Right(None)        => throw AssertionError("the handler replied with no value")
    case Left(error) => throw AssertionError(s"the handler refused the command: ${error.message}")
  def isError(): Boolean = outcome.isLeft
  private def refusal: CommandError = outcome.left.getOrElse(
    throw AssertionError(s"expected a refusal, but the command succeeded with $outcome")
  )
  def error(): String    = refusal.message
  def problem(): Problem = Effects.toProblem(refusal)
  def events(): JList[?] = persisted.asJava
  def state(): Any       = after
