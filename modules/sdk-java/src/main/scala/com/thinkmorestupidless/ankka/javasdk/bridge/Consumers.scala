package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentId}
import com.thinkmorestupidless.ankka.core.effect as se
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import com.thinkmorestupidless.ankka.javasdk.component.{
  ChangeSource as JavaSource,
  Consumer,
  GraphConsumer,
  Publication as JavaPublication
}
import com.thinkmorestupidless.ankka.javasdk.problems.DeclarationProblem
import com.thinkmorestupidless.ankka.sdk

import java.lang.reflect.Method
import java.util.{Optional, OptionalInt}

/** A Java consumer or graph consumer, read once when the service starts. */
private[ankka] final case class ConsumerPlan(
    cls: Class[?],
    componentId: String,
    source: sdk.ChangeSource[Any],
    publication: Option[sdk.Publication],
    output: Option[Serializer[Any]],
    graphTopic: Option[String],
    version: Option[Int],
    parallelism: Int,
    construct: () => AnyRef
)

private[ankka] object Consumers:

  def isConsumer(cls: Class[?]): Boolean =
    classOf[Consumer[?, ?]].isAssignableFrom(cls) || classOf[GraphConsumer[?]].isAssignableFrom(cls)

  def read(
      cls: Class[?],
      idOverride: Option[String],
      serializers: Declarations.Serializers,
      entities: Vector[ComponentPlan]
  ): Either[Vector[DeclarationProblem], ConsumerPlan] =
    val reader = Reader(cls, idOverride, serializers, entities)
    val graph  = classOf[GraphConsumer[?]].isAssignableFrom(cls)
    val hooks  = if graph then Hooks.graph else Hooks.plain

    val source      = reader.declaredSource(hooks.source)
    val version     = reader.version(hooks.version)
    val parallelism = reader.parallelism(hooks.parallelism)

    val (publication, output, topic) =
      if graph then
        val topic = reader.hook("topic")(Hooks.topic).flatMap {
          case null | "" => reader.problem("topic", "names no topic to publish to"); None
          case t         => Some(t)
        }
        (None, None, topic)
      else
        val publishes = reader.hook("publishes")(Hooks.publishes).flatMap {
          case null => reader.problem("publishes", "returned null; return Optional.empty()"); None
          case p    => Option(p.orElse(null))
        }
        val out = reader.arguments(classOf[Consumer[?, ?]]).lift(1)
        val output = publishes.flatMap { p =>
          out match
            case Some(t) if t == classOf[Void] =>
              reader.problem(
                "publishes",
                s"publishes to '$p' but its output type is Void, so it has nothing to publish"
              )
              None
            case Some(t) => reader.serializer(t, "", "output type")
            case None    => None
        }
        val scalaPublication =
          publishes.map(p => sdk.Publication(p.topic, project = Option(p.project.orElse(null))))
        (scalaPublication, output, None)

    if reader.problems.nonEmpty then Left(reader.problems)
    else
      Right(
        ConsumerPlan(
          cls,
          reader.componentId.get,
          source.get,
          publication,
          output,
          topic,
          version,
          parallelism,
          reader.construct.get
        )
      )

  /** The companion the descriptor comes from: a consumer's, or a graph consumer's. */
  def descriptor(
      plan: ConsumerPlan,
      refs: Refs,
      serializers: Declarations.Serializers
  ): ComponentDescriptor =
    plan.graphTopic match
      case Some(topic) =>
        new sdk.graph.GraphConsumer.Companion[JavaGraphHost, Any](
          ComponentId(plan.componentId),
          plan.source,
          topic
        ):
          def create(ctx: sdk.ConsumerContext): JavaGraphHost =
            JavaGraphHost(plan, cell(ctx, refs, serializers))
          override def parallelism: Int     = plan.parallelism
          override def version: Option[Int] = plan.version
        .descriptor
      case None =>
        new sdk.Consumer.Companion[JavaConsumerHost, Any, Any](
          ComponentId(plan.componentId),
          plan.source
        ):
          def create(ctx: sdk.ConsumerContext): JavaConsumerHost =
            JavaConsumerHost(plan, cell(ctx, refs, serializers))
          override def outputSerializer
              : Option[com.thinkmorestupidless.ankka.core.Serializer[Any]] =
            plan.output.map(CodecBridge.toScala)
          override def produces: Option[sdk.Publication] = plan.publication
          override def parallelism: Int                  = plan.parallelism
          override def version: Option[Int]              = plan.version
        .descriptor

  private def cell(
      ctx: sdk.ConsumerContext,
      refs: Refs,
      serializers: Declarations.Serializers
  ): ViewCell =
    ViewCell(Clients.of(ctx.componentClient, refs), Services.of(ctx.services, serializers))

  private[bridge] final class Hooks(base: Class[?]):
    private def method(name: String, params: Class[?]*): Method =
      val m = base.getDeclaredMethod(name, params*)
      m.setAccessible(true)
      m
    private val sourceM      = method("source")
    private val changeM      = method("onChange", classOf[Object])
    private val deleteM      = method("onDelete")
    private val versionM     = method("version")
    private val parallelismM = method("parallelism")

    def source(c: AnyRef): JavaSource[?] = Views.Hooks.call(sourceM, c).asInstanceOf[JavaSource[?]]
    def onChange(c: AnyRef, change: Any): Any =
      Views.Hooks.call(changeM, c, change.asInstanceOf[AnyRef])
    def onDelete(c: AnyRef): Any = Views.Hooks.call(deleteM, c)
    def version(c: AnyRef): OptionalInt =
      Views.Hooks.call(versionM, c).asInstanceOf[OptionalInt]
    def parallelism(c: AnyRef): Int =
      Views.Hooks.call(parallelismM, c).asInstanceOf[java.lang.Integer].intValue

  private[bridge] object Hooks:
    val plain = Hooks(classOf[Consumer[?, ?]])
    val graph = Hooks(classOf[GraphConsumer[?]])

    private val publishesM =
      val m = classOf[Consumer[?, ?]].getDeclaredMethod("publishes"); m.setAccessible(true); m
    private val topicM =
      val m = classOf[GraphConsumer[?]].getDeclaredMethod("topic"); m.setAccessible(true); m

    def publishes(c: AnyRef): Optional[JavaPublication] =
      Views.Hooks.call(publishesM, c).asInstanceOf[Optional[JavaPublication]]
    def topic(c: AnyRef): String = Views.Hooks.call(topicM, c).asInstanceOf[String]

/** A Java consumer, hosted by the Scala projection runtime. */
private[ankka] final class JavaConsumerHost(plan: ConsumerPlan, cell: ViewCell)
    extends sdk.Consumer[Any, Any]:
  private val java  = ViewCell.constructing(cell)(plan.construct())
  private val where = plan.cls.getSimpleName

  private def run(what: String)(call: => Any): Effect =
    cell.set(None, scala.util.Try(messageContext).toOption)
    try Effects.unwrap(call, s"$where.$what").asInstanceOf[se.ConsumerEffect[Any]]
    finally cell.set(None, None)

  def onMessage(message: Any): Effect =
    run("onChange")(Consumers.Hooks.plain.onChange(java, message))

  override def onDelete: Effect = run("onDelete")(Consumers.Hooks.plain.onDelete(java))

/** A Java graph consumer, hosted as the Scala graph consumer is. */
private[ankka] final class JavaGraphHost(plan: ConsumerPlan, cell: ViewCell)
    extends sdk.graph.GraphConsumer[Any]:
  private val java  = ViewCell.constructing(cell)(plan.construct())
  private val where = plan.cls.getSimpleName

  private def run(what: String)(call: => Any): Effect =
    cell.set(None, scala.util.Try(messageContext).toOption)
    try Effects.unwrap(call, s"$where.$what").asInstanceOf[sdk.graph.GraphEffect]
    finally cell.set(None, None)

  def onMessage(message: Any): Effect =
    run("onChange")(Consumers.Hooks.graph.onChange(java, message))

  override def onDelete: Effect = run("onDelete")(Consumers.Hooks.graph.onDelete(java))
