package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentId}
import com.thinkmorestupidless.ankka.core.effect as se
import com.thinkmorestupidless.ankka.javasdk.annotations
import com.thinkmorestupidless.ankka.javasdk.client.{
  CallerError,
  ViewClient,
  ViewQueries,
  ViewQuery
}
import com.thinkmorestupidless.ankka.javasdk.codec.{EncodingException, RecordCodec, Serializer}
import com.thinkmorestupidless.ankka.javasdk.component.{
  ChangeSource as JavaSource,
  KeyedChange,
  KeyedView,
  View,
  ViewRows
}
import com.thinkmorestupidless.ankka.javasdk.effect.KeyedViewEffect
import com.thinkmorestupidless.ankka.javasdk.problems.DeclarationProblem
import com.thinkmorestupidless.ankka.runtime
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.sdk.{DeclaredQuery, TopicOptions, ViewComponentContext}

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.JavaFlowSupport

import java.lang.reflect.{InvocationTargetException, Method, Modifier, Type}
import java.util.{Optional, OptionalInt}
import java.util.concurrent.Flow
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** One source of a keyed view: the method for its changes, the one for its deletions, or both. */
private[ankka] final case class KeyedSourcePlan(
    source: sdk.ChangeSource[Any],
    change: Option[Method],
    deleted: Option[Method]
)

/** A Java view or keyed view, read once when the service starts. */
private[ankka] final case class ViewPlan(
    cls: Class[?],
    componentId: String,
    source: Option[sdk.ChangeSource[Any]],
    keyed: Option[Vector[KeyedSourcePlan]],
    row: Serializer[Any],
    queries: Vector[ViewQuery],
    version: Option[Int],
    parallelism: Int,
    construct: () => AnyRef
):
  def declared: Vector[DeclaredQuery] =
    queries.map(q => DeclaredQuery(ComponentId(componentId), q.name, q.statement, q.isWatched))

private[ankka] object Views:

  def isView(cls: Class[?]): Boolean =
    classOf[View[?, ?]].isAssignableFrom(cls) || classOf[KeyedView[?]].isAssignableFrom(cls)

  /** Reads a view, resolving a component source against the entities the service registers. */
  def read(
      cls: Class[?],
      idOverride: Option[String],
      serializers: Declarations.Serializers,
      entities: Vector[ComponentPlan]
  ): Either[Vector[DeclarationProblem], ViewPlan] =
    val name     = cls.getSimpleName
    val problems = mutable.ArrayBuffer.empty[DeclarationProblem]
    def problem(member: String, message: String): Unit =
      problems += DeclarationProblem(name, member, message)

    val keyed       = classOf[KeyedView[?]].isAssignableFrom(cls)
    val annotated   = Option(cls.getAnnotation(classOf[annotations.ComponentId])).map(_.value)
    val componentId = idOverride.orElse(annotated).filter(_.nonEmpty)
    if componentId.isEmpty then
      problem("", "has no component id: annotate it @ComponentId(\"...\") or register(cls, id)")
    if Modifier.isAbstract(cls.getModifiers) then
      problem("", "is abstract; register a concrete class")

    val construct = cls.getDeclaredConstructors.find(_.getParameterCount == 0) match
      case Some(c) =>
        c.setAccessible(true)
        Some(() =>
          try c.newInstance().asInstanceOf[AnyRef]
          catch case e: InvocationTargetException => throw e.getCause
        )
      case None =>
        problem("", "has no constructor taking nothing")
        None

    val bindings = Declarations.typeBindings(cls)
    def serializer(tpe: Type, member: String, what: String): Option[Serializer[Any]] =
      val resolved = Declarations.resolve(tpe, bindings)
      Planner.rawClass(resolved).flatMap(serializers.get) match
        case Some(explicit) => Some(explicit.asInstanceOf[Serializer[Any]])
        case None =>
          try Some(RecordCodec.forType(resolved).asInstanceOf[Serializer[Any]])
          catch
            case e: EncodingException =>
              problem(member, s"its $what has no serializer: ${e.getMessage}")
              None

    val row =
      if keyed then
        Declarations
          .superArguments(classOf[KeyedView[?]], bindings)
          .headOption
          .flatMap(serializer(_, "", "row type"))
      else
        Declarations
          .superArguments(classOf[View[?, ?]], bindings)
          .lift(1)
          .flatMap(serializer(_, "", "row type"))

    def resolveSource(java: JavaSource[?], member: String): Option[sdk.ChangeSource[Any]] =
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
            case Some(plan) =>
              Some(
                sdk.ChangeSource.KeyValue(
                  ComponentId(plan.componentId),
                  CodecBridge.state(plan.state)
                )
              )
            case None =>
              problem(
                member,
                s"reads ${java.component.getSimpleName}, which the service does not register " +
                  "as an entity"
              )
              None
        case JavaSource.Kind.WORKFLOW =>
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
              None,
              TopicOptions(project = Option(java.project))
            )
          }

    // The view's own hooks are read from one instance, made here as the host makes them.
    val probe = construct.flatMap { make =>
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

    val version = hook("version")(i => Hooks.version(i, keyed)).flatMap { v =>
      if v.isPresent && v.getAsInt < 1 then
        problem("version", s"is ${v.getAsInt}; a version is 1 or more")
      if v.isPresent then Some(v.getAsInt) else None
    }

    val source =
      if keyed then None
      else
        hook("source")(Hooks.source).flatMap {
          case null => problem("source", "returned null"); None
          case s    => resolveSource(s, "source")
        }

    val keyedSources = if !keyed then None else Some(keyedPlans(cls, problem, resolveSource))

    val queries = cls.getDeclaredFields.toVector
      .filter(f => f.getType == classOf[ViewQuery] && Modifier.isStatic(f.getModifiers))
      .sortBy(_.getName)
      .flatMap { f =>
        f.setAccessible(true)
        f.get(null) match
          case null =>
            problem(f.getName, "is a ViewQuery field holding null"); None
          case q: ViewQuery => Some(q)
      }
    queries.groupBy(_.name).foreach { (query, same) =>
      if same.size > 1 then problem("", s"declares the query '$query' ${same.size} times")
    }
    for id <- idOverride; declared <- annotated if id != declared && queries.nonEmpty do
      problem(
        "",
        s"is registered as '$id' but its queries name the table of '$declared'; register it " +
          "under its @ComponentId"
      )

    val parallelism = if keyed then 1 else hook("parallelism")(Hooks.parallelism).getOrElse(1)
    if parallelism < 1 then problem("parallelism", s"is $parallelism; it is 1 or more")

    if problems.nonEmpty then Left(problems.toVector)
    else
      Right(
        ViewPlan(
          cls,
          componentId.get,
          source,
          keyedSources,
          row.get,
          queries,
          version,
          parallelism,
          construct.get
        )
      )

  private def keyedPlans(
      cls: Class[?],
      problem: (String, String) => Unit,
      resolveSource: (JavaSource[?], String) => Option[sdk.ChangeSource[Any]]
  ): Vector[KeyedSourcePlan] =
    val methods = cls.getDeclaredMethods.toVector
      .filter(_.isAnnotationPresent(classOf[annotations.Source]))
      .sortBy(_.getName)
    if methods.isEmpty then problem("", "is a keyed view with no @Source method")
    val bySource = mutable.LinkedHashMap.empty[(Any, String, String), KeyedSourcePlan]
    methods.foreach { m =>
      val member = m.getName
      val ann    = m.getAnnotation(classOf[annotations.Source])
      val params = m.getParameterTypes.toVector
      m.setAccessible(true)
      if !classOf[KeyedViewEffect[?]].isAssignableFrom(m.getReturnType) then
        problem(member, "must return a KeyedViewEffect built by effects()")
      if Modifier.isStatic(m.getModifiers) then
        problem(member, "is static; it is an instance method")
      val component = ann.value != classOf[Void]
      val topic     = ann.topic.nonEmpty
      if component == topic then
        problem(member, "names its source by a class or by a topic: exactly one of the two")
      else if ann.deleted && topic then
        problem(member, "handles deletions, which only a component source has")
      else
        val shapeOk =
          if ann.deleted then params.isEmpty || params == Vector(classOf[KeyedChange[?]])
          else params.size == 1 || (params.size == 2 && params(1) == classOf[KeyedChange[?]])
        if !shapeOk then
          problem(
            member,
            if ann.deleted then "handles deletions, so it takes nothing or a KeyedChange"
            else "takes the change, or the change and a KeyedChange"
          )
        else
          val java =
            if topic then
              if ann.project.isEmpty then JavaSource.ofTopic(ann.topic, params(0))
              else JavaSource.ofTopic(ann.project, ann.topic, params(0))
            else JavaSource.of[Any](ann.value)
          resolveSource(java, member).foreach { source =>
            val key = (Option(java.component).getOrElse(""), ann.topic, ann.project)
            val was = bySource.getOrElse(key, KeyedSourcePlan(source, None, None))
            if ann.deleted && was.deleted.isDefined then
              problem(member, s"handles ${java}'s deletions, as ${was.deleted.get.getName} does")
            else if !ann.deleted && was.change.isDefined then
              problem(member, s"handles ${java}'s changes, as ${was.change.get.getName} does")
            else
              bySource(key) =
                if ann.deleted then was.copy(deleted = Some(m)) else was.copy(change = Some(m))
          }
    }
    bySource.values.toVector

  // ── Hosting ────────────────────────────────────────────────────────────────

  /** What the client needs of a registered view: its companion, view or keyed. */
  private[ankka] enum Companion:
    case Plain(companion: sdk.View.Companion[JavaViewHost, Any, Any])
    case Keyed(companion: sdk.KeyedView.Companion[JavaKeyedViewHost, Any])

    def descriptor: ComponentDescriptor = this match
      case Plain(c) => c.descriptor
      case Keyed(c) => c.descriptor

  def companion(plan: ViewPlan): Companion = plan.keyed match
    case None        => Companion.Plain(viewCompanion(plan))
    case Some(plans) => Companion.Keyed(keyedCompanion(plan, plans))

  private def viewCompanion(plan: ViewPlan): sdk.View.Companion[JavaViewHost, Any, Any] =
    new sdk.View.Companion[JavaViewHost, Any, Any](
      ComponentId(plan.componentId),
      plan.source.get,
      CodecBridge.toScala(plan.row)
    ):
      plan.queries.foreach { q =>
        val declared = query(q.name)(q.statement)
        if q.isWatched then { val _ = declared.watched }
      }
      override def parallelism: Int         = plan.parallelism
      override def version: Option[Int]     = plan.version
      def create(ctx: ViewComponentContext) = JavaViewHost(plan)

  private def keyedCompanion(
      plan: ViewPlan,
      sources: Vector[KeyedSourcePlan]
  ): sdk.KeyedView.Companion[JavaKeyedViewHost, Any] =
    new sdk.KeyedView.Companion[JavaKeyedViewHost, Any](
      ComponentId(plan.componentId),
      CodecBridge.toScala(plan.row)
    ):
      sources.foreach { s =>
        source[Any](
          s.source,
          host => change => s.deleted.fold(se.KeyedViewEffect.Nothing)(host.call(_, None, change))
        )(host =>
          (src, change) =>
            s.change.fold(se.KeyedViewEffect.Nothing)(host.call(_, Some(src), change))
        ): Unit
      }
      plan.queries.foreach { q =>
        val declared = query(q.name)(q.statement)
        if q.isWatched then { val _ = declared.watched }
      }
      override def version: Option[Int]     = plan.version
      def create(ctx: ViewComponentContext) = JavaKeyedViewHost(plan)

  private[bridge] object Hooks:
    private def method(cls: Class[?], name: String, params: Class[?]*): Method =
      val m = cls.getDeclaredMethod(name, params*)
      m.setAccessible(true)
      m

    private val viewSource      = method(classOf[View[?, ?]], "source")
    private val viewChange      = method(classOf[View[?, ?]], "onChange", classOf[Object])
    private val viewDelete      = method(classOf[View[?, ?]], "onDelete")
    private val viewVersion     = method(classOf[View[?, ?]], "version")
    private val viewParallelism = method(classOf[View[?, ?]], "parallelism")
    private val keyedVersion    = method(classOf[KeyedView[?]], "version")

    def call(m: Method, target: AnyRef, args: AnyRef*): Any =
      try m.invoke(target, args*)
      catch case e: InvocationTargetException => throw e.getCause

    def source(view: AnyRef): JavaSource[?] = call(viewSource, view).asInstanceOf[JavaSource[?]]
    def onChange(view: AnyRef, change: Any): Any =
      call(viewChange, view, change.asInstanceOf[AnyRef])
    def onDelete(view: AnyRef): Any = call(viewDelete, view)
    def version(view: AnyRef, keyed: Boolean): OptionalInt =
      call(if keyed then keyedVersion else viewVersion, view).asInstanceOf[OptionalInt]
    def parallelism(view: AnyRef): Int =
      call(viewParallelism, view).asInstanceOf[java.lang.Integer].intValue

/** A Java view, hosted by the Scala view runtime. */
private[ankka] final class JavaViewHost(plan: ViewPlan) extends sdk.View[Any, Any]:
  private val cell  = ViewCell()
  private val java  = ViewCell.constructing(cell)(plan.construct())
  private val where = plan.cls.getSimpleName

  def onChange(change: Any): Effect =
    cell.set(rowState, Some(updateContext))
    try
      Effects
        .unwrap(Views.Hooks.onChange(java, change), s"$where.onChange")
        .asInstanceOf[Effect]
    finally cell.set(None, None)

  override def onDelete: Effect =
    cell.set(rowState, scala.util.Try(updateContext).toOption)
    try Effects.unwrap(Views.Hooks.onDelete(java), s"$where.onDelete").asInstanceOf[Effect]
    finally cell.set(None, None)

/** A Java keyed view, hosted by the Scala view runtime. */
private[ankka] final class JavaKeyedViewHost(plan: ViewPlan) extends sdk.KeyedView[Any]:
  private val java = plan.construct()

  private[bridge] def call(
      m: Method,
      change: Option[Any],
      context: sdk.KeyedChange[Any]
  ): se.KeyedViewEffect[Any] =
    val keyed = JavaKeyedChange(context, plan)
    val args: Seq[AnyRef] = change.map(_.asInstanceOf[AnyRef]).toSeq ++
      (if m.getParameterCount > change.size then Seq(keyed) else Seq.empty)
    Effects
      .unwrap(Views.Hooks.call(m, java, args*), s"${plan.cls.getSimpleName}.${m.getName}")
      .asInstanceOf[se.KeyedViewEffect[Any]]

private final class JavaKeyedChange(change: sdk.KeyedChange[Any], plan: ViewPlan)
    extends KeyedChange[Any]:
  def subject(): String      = change.subject
  def sequenceNumber(): Long = change.sequenceNumber
  def rows(): ViewRows[Any] = new ViewRows[Any]:
    def get(key: String): Optional[Any] =
      change.rows.get(key).fold(Optional.empty[Any]())(Optional.of(_))
    def ask(query: ViewQuery, values: java.util.Map[String, String]): java.util.List[Any] =
      change.rows.ask(JavaViews.declared(plan.componentId, query), values.asScala.toSeq*).asJava

// ── The client ─────────────────────────────────────────────────────────────

private[ankka] object JavaViews:

  def declared(componentId: String, query: ViewQuery): DeclaredQuery =
    DeclaredQuery(ComponentId(componentId), query.name, query.statement, query.isWatched)

  val unavailable: ViewClient = new ViewClient:
    def forView[Row](view: Class[? <: View[?, Row]]): ViewQueries[Row]        = refuse
    def forKeyedView[Row](view: Class[? <: KeyedView[Row]]): ViewQueries[Row] = refuse
    private def refuse =
      throw IllegalStateException("views are queried from an endpoint, a service or a test kit")

  def client(
      scala: runtime.ViewClient,
      views: Map[Class[?], (String, Views.Companion)]
  ): ViewClient = new ViewClient:
    def forView[Row](view: Class[? <: View[?, Row]]): ViewQueries[Row]        = queries(view)
    def forKeyedView[Row](view: Class[? <: KeyedView[Row]]): ViewQueries[Row] = queries(view)

    private def queries[Row](view: Class[?]): ViewQueries[Row] =
      views.get(view) match
        case Some((id, Views.Companion.Plain(c))) =>
          JavaViewQueries(id, scala.forView(c), scala.actorSystem)
        case Some((id, Views.Companion.Keyed(c))) =>
          JavaViewQueries(id, scala.forView(c), scala.actorSystem)
        case None =>
          throw CallerError(s"${view.getName} is not a view this service registers")

private final class JavaViewQueries[Row](
    componentId: String,
    scala: runtime.ViewQueries[Any],
    system: org.apache.pekko.actor.typed.ActorSystem[?]
) extends ViewQueries[Row]:

  private def guarded[A](run: => A): A =
    try run
    catch case t: Throwable => throw Clients.javaFailure(t)

  private def rows(found: Vector[Any]): java.util.List[Row] =
    found.asInstanceOf[Vector[Row]].asJava

  def get(key: String): Optional[Row] =
    guarded(scala.get(key)).fold(Optional.empty[Row]())(r => Optional.of(r.asInstanceOf[Row]))
  def all(): java.util.List[Row]           = guarded(rows(scala.all()))
  def all(limit: Int): java.util.List[Row] = guarded(rows(scala.all(limit)))
  def count(): Long                        = guarded(scala.count())

  def ask(query: ViewQuery, values: java.util.Map[String, String]): java.util.List[Row] =
    guarded(rows(scala.ask(JavaViews.declared(componentId, query), values.asScala.toSeq*)))

  def ask(
      query: ViewQuery,
      limit: Int,
      values: java.util.Map[String, String]
  ): java.util.List[Row] =
    guarded(rows(scala.ask(JavaViews.declared(componentId, query), limit, values.asScala.toSeq*)))

  def askStream(query: ViewQuery, values: java.util.Map[String, String]): Flow.Publisher[Row] =
    scala
      .askStream(JavaViews.declared(componentId, query), values.asScala.toSeq*)
      .asInstanceOf[org.apache.pekko.stream.scaladsl.Source[Row, ?]]
      .runWith(JavaFlowSupport.Sink.asPublisher[Row](fanout = false))(using Materializer(system))
