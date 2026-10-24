package com.thinkmorestupidless.ankka.javasdk.testkit.bridge

import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, MethodName}
import com.thinkmorestupidless.ankka.core.graph.GraphDelta as ScalaDelta
import com.thinkmorestupidless.ankka.javasdk.annotations.{
  ComponentId as ComponentIdAnnotation,
  Source
}
import com.thinkmorestupidless.ankka.javasdk.bridge.{
  Clients,
  ComponentPlan,
  Consumers,
  Effects,
  GraphDeltas,
  JavaKind,
  JavaServiceBuilder,
  JavaWorkflowHost,
  JavaViews,
  References,
  Views,
  Workflows
}
import com.thinkmorestupidless.ankka.javasdk.client.{
  CallerError,
  CommandRef,
  ComponentClient,
  NoArgRef,
  ViewQuery,
  WorkflowLifecycle
}
import com.thinkmorestupidless.ankka.javasdk.component.GraphDelta
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.testkit.{ConsumerResult, Published, StepResult}
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.sdk.{CommandHandle, ConsumerDescriptor, NoArgHandle}
import com.thinkmorestupidless.ankka.testkit as stk

import java.time.Duration
import java.util.Optional
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * The Java component kits for workflows, views, keyed views and consumers, each driving the Scala
 * kit of the companion the Java service builds for the class, so a test drives exactly what a
 * service would host. A component's calls go to the stubs it was given.
 */
object KindKits:

  // ── Stubbed calls ──────────────────────────────────────────────────────────

  def stubs(components: Array[Class[?]]): Stubs = Stubs(components.toVector, stk.TestTransport())

  /** The classes a kit's component may call, and what the test stubbed of them. */
  final class Stubs private[bridge] (
      private[bridge] val classes: Vector[Class[?]],
      transport: stk.TestTransport
  ):
    private lazy val read =
      JavaServiceBuilder.read(classes.map(_ -> None), Map.empty, checkReferences = false)

    def answer(ref: AnyRef, respond: java.util.function.Function[Any, Any]): Stubs =
      val (component, handler) = resolve(ref)
      Stubs(
        classes,
        transport.stubBytes(ComponentId(component.componentId), MethodName(handler.wireName)) {
          bytes =>
            val input = handler.input.map(_.fromBytes(bytes)).orNull
            handler.output.toBytes(respond.apply(input))
        }
      )

    def refuse(ref: AnyRef, problem: Problem): Stubs =
      val (component, handler) = resolve(ref)
      Stubs(
        classes,
        transport.stubBytes(ComponentId(component.componentId), MethodName(handler.wireName))(_ =>
          throw Effects.toCommandError(problem)
        )
      )

    def client(): ComponentClient = Clients.of(scalaClient, read.refs)

    private[bridge] def scalaClient: sdk.ComponentClient = transport.client

    private def resolve(ref: AnyRef) =
      try read.refs.resolve(ref)
      catch
        case e: CallerError =>
          throw CallerError(
            s"${e.getMessage}; this client stubs only " +
              classes.map(_.getSimpleName).mkString("[", ", ", "]")
          )

  /**
   * `cls` read as a service reads it, beside the components it names (the entity a view reads, the
   * one a consumer calls), so their serializers are known, and the classes its calls are stubbed
   * for. A call to a component it names that nobody stubbed is refused by the stubs' transport.
   */
  private def readWith(cls: Class[?], stubs: Stubs) =
    JavaServiceBuilder.read(
      (cls +: (References.components(cls) ++ sourcesOf(cls) ++ stubs.classes)).distinct
        .map(_ -> None),
      Map.empty,
      checkReferences = false
    )

  /**
   * The components a keyed view's `@Source` methods name, which are annotation values and so not
   * among the class constants `References` reads.
   */
  private def sourcesOf(cls: Class[?]): Vector[Class[?]] =
    cls.getDeclaredMethods.toVector
      .flatMap(m => Option(m.getAnnotation(classOf[Source])))
      .map(_.value)
      .filter(_ != classOf[Void])

  /** The `@ComponentId` of a class a keyed view reads. */
  def sourceName(cls: Class[?]): String =
    Option(cls.getAnnotation(classOf[ComponentIdAnnotation]))
      .map(_.value)
      .getOrElse(throw IllegalArgumentException(s"${cls.getName} has no @ComponentId"))

  private def refusedKind(cls: Class[?], kind: String) =
    IllegalArgumentException(s"${cls.getName} is not a $kind")

  // ── Workflows ──────────────────────────────────────────────────────────────

  def workflow(cls: Class[?], workflowId: String, stubs: Stubs): WorkflowKit =
    WorkflowKit(cls, workflowId, stubs)

  final class WorkflowKit private[bridge] (cls: Class[?], workflowId: String, stubs: Stubs):
    private val read                = readWith(cls, stubs)
    private val plan: ComponentPlan =
      read.plans
        .find(p => p.cls == cls && p.kind == JavaKind.Workflow)
        .getOrElse(throw refusedKind(cls, "workflow"))
    private val companion = Workflows.companion(plan, read.refs, read.serializers)
    private val handlers  = companion.descriptor
    private val kit       = stk.WorkflowTestKit.of(companion, workflowId, stubs.scalaClient)

    def call[S, C, I, O](ref: CommandRef[C, I, O], input: I): StepResult[S, O] =
      run(ref, Some(input))
    def call[S, C, O](ref: NoArgRef[C, O]): StepResult[S, O] = run(ref, None)

    private def run[S, O](ref: AnyRef, input: Option[Any]): StepResult[S, O] =
      val (component, handler) = read.refs.resolve(ref)
      if component.cls != cls then
        throw CallerError(
          s"${component.cls.getSimpleName}::${handler.method.getName} is not a handler of " +
            s"${cls.getSimpleName}, the workflow this kit drives"
        )
      val binding = handlers
        .handler(MethodName(handler.wireName))
        .getOrElse(throw IllegalStateException(s"no handler '${handler.wireName}'"))
      val result = (binding, input) match
        case (h: CommandHandle[JavaWorkflowHost, Any, Any] @unchecked, Some(in)) => kit.call(h)(in)
        case (h: NoArgHandle[JavaWorkflowHost, Any] @unchecked, None)            => kit.call(h)
        case _                                                                   =>
          throw CallerError(
            s"'${binding.name}' ${if input.isEmpty then "takes an input" else "takes none"}"
          )
      JavaStepResult[S, O](result)

    def timeOut[S](): StepResult[S, Void] = JavaStepResult[S, Void](kit.timeOut())
    def state(): Any                      = kit.state
    def lifecycle(): WorkflowLifecycle    = KindKits.lifecycle(kit.lifecycle)
    def deleted(): Boolean                = kit.isDeleted
    def failedStep(): Optional[String]    = kit.failedStep.toJava

  private def lifecycle(l: sdk.WorkflowLifecycle): WorkflowLifecycle = Clients.javaLifecycle(l)

  private final case class JavaStepResult[S, O](scala: stk.StepResult[Any, ?])
      extends StepResult[S, O]:
    def reply(): O = scala.reply match
      case Right(Some(value)) => value.asInstanceOf[O]
      case Right(None)        => throw AssertionError("the handler replied with no value")
      case Left(error) => throw AssertionError(s"the handler refused the command: ${error.message}")
    def isError(): Boolean            = scala.isError
    private def refusal: CommandError = scala.reply.left.getOrElse(
      throw AssertionError(s"expected a refusal, but the command succeeded with ${scala.reply}")
    )
    def error(): String                 = refusal.message
    def problem(): Problem              = Effects.toProblem(refusal)
    def steps(): java.util.List[String] = scala.steps.asJava
    def state(): S                      = scala.state.asInstanceOf[S]
    def lifecycle(): WorkflowLifecycle  = KindKits.lifecycle(scala.lifecycle)
    def pausedFor(): Optional[Duration] =
      scala.pauseTimeout.map(p => Duration.ofMillis(p._1.toMillis)).toJava
    def onTimeout(): Optional[String] = scala.pauseTimeout.map(_._2).toJava

  // ── Views ──────────────────────────────────────────────────────────────────

  def view(cls: Class[?]): ViewKit = ViewKit(cls)

  private def viewCompanion(cls: Class[?]) =
    readWith(cls, stubs(Array.empty)).views
      .get(cls)
      .map(_._2)

  final class ViewKit private[bridge] (cls: Class[?]):
    private val kit = viewCompanion(cls) match
      case Some(Views.Companion.Plain(companion)) => stk.ViewTestKit(companion)
      case _                                      => throw refusedKind(cls, "view")

    def change(subject: String, value: Any, sequenceNumber: Long): Unit =
      kit.change(subject, value, sequenceNumber): Unit
    def deleted(subject: String): Unit      = kit.deleted(subject): Unit
    def row(subject: String): Optional[Any] = kit.row(subject).toJava
    def rows(): java.util.Map[String, Any]  = kit.rows.asJava

  def keyedView(cls: Class[?]): KeyedViewKit = KeyedViewKit(cls)

  final class KeyedViewKit private[bridge] (cls: Class[?]):
    private val companion = viewCompanion(cls) match
      case Some(Views.Companion.Keyed(companion)) => companion
      case _                                      => throw refusedKind(cls, "keyed view")
    private val sources = companion.descriptor.sources
    private val kit     = stk.KeyedViewTestKit(companion)

    def answering(
        query: ViewQuery,
        answer: java.util.function.Function[java.util.Map[String, String], ? <: java.util.List[?]]
    ): Unit =
      kit.answering(JavaViews.declared(sourceName(cls), query))(values =>
        (answer.apply(values.asJava): java.util.List[?]).asScala.toVector.map(_.asInstanceOf[Any])
      ): Unit

    private def source(name: String) =
      sources
        .find(_.componentId.toString == name)
        .getOrElse(
          throw IllegalArgumentException(
            s"$name is not a source of ${cls.getSimpleName}; its sources are " +
              sources.map(_.componentId).mkString("[", ", ", "]")
          )
        )

    def change(name: String, subject: String, value: Any): Unit =
      kit.change(source(name), subject, value): Unit
    def deleted(name: String, subject: String): Unit = kit.deleted(source(name), subject): Unit
    def row(key: String): Optional[Any]              = kit.row(key).toJava
    def rows(): java.util.Map[String, Any]           = kit.rows.asJava

  // ── Consumers ──────────────────────────────────────────────────────────────

  private def consumerDescriptor(cls: Class[?], stubs: Stubs, graph: Boolean) =
    val read = readWith(cls, stubs)
    read.consumers
      .find(p => p.cls == cls && p.graphTopic.isDefined == graph)
      .map(plan =>
        Consumers
          .descriptor(plan, read.refs, read.serializers)
          .asInstanceOf[ConsumerDescriptor[sdk.Consumer[Any, Any], Any, Any]]
      )
      .getOrElse(throw refusedKind(cls, if graph then "graph consumer" else "consumer"))

  def consumer(cls: Class[?], stubs: Stubs): ConsumerKit = ConsumerKit(cls, stubs)

  final class ConsumerKit private[bridge] (cls: Class[?], stubs: Stubs):
    private val kit = stk.ConsumerTestKit.ofDescriptor(
      consumerDescriptor(cls, stubs, graph = false),
      stubs.scalaClient
    )

    def onMessage[O](message: Any, subject: String, sequenceNumber: Long): ConsumerResult[O] =
      JavaConsumerResult(kit.onMessage(message, subject, sequenceNumber))
    def onDelete[O](subject: String): ConsumerResult[O] = JavaConsumerResult(kit.onDelete(subject))

  private final case class JavaConsumerResult[O](scala: stk.ConsumerTestKit.Result[Any])
      extends ConsumerResult[O]:
    def payloads(): java.util.List[O]         = scala.payloads.map(_.asInstanceOf[O]).asJava
    def messages(): java.util.List[Published] =
      scala.messages.map { m =>
        val headers = java.util.LinkedHashMap[String, String]()
        m.metadata.toSeq.foreach((k, v) => headers.putIfAbsent(k, v): Unit)
        Published(m.key.toJava, m.text, java.util.Collections.unmodifiableMap(headers))
      }.asJava
    def recordKeys(): java.util.List[Optional[String]] = scala.recordKeys.map(_.toJava).asJava

  def graph(cls: Class[?], stubs: Stubs): GraphKit = GraphKit(cls, stubs)

  final class GraphKit private[bridge] (cls: Class[?], stubs: Stubs):
    private val kit = stk.ConsumerTestKit.graphOfDescriptor(
      consumerDescriptor(cls, stubs, graph = true)
        .asInstanceOf[ConsumerDescriptor[sdk.Consumer[Any, ScalaDelta], Any, ScalaDelta]],
      stubs.scalaClient
    )

    def onMessage(message: Any, subject: String, sequenceNumber: Long): java.util.List[GraphDelta] =
      kit.onMessage(message, subject, sequenceNumber).map(GraphDeltas.toJava).asJava
    def onDelete(subject: String): java.util.List[GraphDelta] =
      kit.onDelete(subject).map(GraphDeltas.toJava).asJava
