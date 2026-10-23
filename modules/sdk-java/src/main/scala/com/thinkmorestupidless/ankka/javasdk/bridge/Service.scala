package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.{AnkkaService, ServiceBuilder}
import com.thinkmorestupidless.ankka.javasdk.client.{ComponentClient, ViewClient}
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpServer}
import com.thinkmorestupidless.ankka.javasdk.problems.{DeclarationProblem, ServiceProblems}
import com.thinkmorestupidless.ankka.javasdk.timer.Timers
import com.thinkmorestupidless.ankka.runtime
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.core.ComponentDescriptor

import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletionStage
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

/** A Java service definition: the classes it names, read at start. */
final case class JavaServiceBuilder private (
    name: String,
    classes: Vector[(Class[?], Option[String])],
    endpoints: Vector[Class[?]],
    serializers: Declarations.Serializers,
    http: Option[(String, Int)]
) extends ServiceBuilder:

  def this(name: String) = this(name, Vector.empty, Vector.empty, Map.empty, None)

  def register(componentClass: Class[?]): ServiceBuilder =
    copy(classes = classes :+ (componentClass -> None))

  def register(componentClass: Class[?], componentId: String): ServiceBuilder =
    copy(classes = classes :+ (componentClass -> Some(componentId)))

  def withEndpoint(endpoint: Class[? <: HttpEndpoint]): ServiceBuilder =
    copy(endpoints = endpoints :+ endpoint)

  def withHttp(host: String, port: Int): ServiceBuilder = copy(http = Some(host -> port))

  def withSerializer[A](tpe: Class[A], serializer: Serializer[A]): ServiceBuilder =
    copy(serializers = serializers + (tpe -> serializer))

  def start(): AnkkaService =
    val read    = JavaServiceBuilder.read(classes, serializers, endpoints)
    val service = JavaServiceBuilder.scalaBuilder(read, http).start()
    JavaAnkkaService(name, service, read)

private[ankka] object JavaServiceBuilder:

  /** Every class read, its descriptor built, and the refs that resolve calls to its handlers. */
  final case class Read(
      plans: Vector[ComponentPlan],
      descriptors: Vector[ComponentDescriptor],
      refs: Refs,
      endpoints: Vector[EndpointPlan] = Vector.empty,
      serializers: Declarations.Serializers = Map.empty,
      views: Map[Class[?], (String, Views.Companion)] = Map.empty,
      consumers: Vector[ConsumerPlan] = Vector.empty,
      actions: ActionIndex = ActionIndex.empty
  ):
    /**
     * The platform's extensions the read components need, which a Java service never names: the
     * projections behind its views and consumers, and the timers its timed actions are run by.
     */
    def platformExtensions: Vector[runtime.RuntimeExtension] =
      (if views.nonEmpty || consumers.nonEmpty then Vector(runtime.ProjectionRuntime())
       else Vector.empty) ++
        (if actions.isEmpty then Vector.empty else Vector(runtime.TimerRuntime()))

    /** The Java timers over a running service's scheduler. */
    def timers(scala: sdk.TimerScheduler): Timers =
      if actions.isEmpty then JavaTimers.unavailable else JavaTimers(scala, actions)

    /** The Java view client over a running service's. */
    def viewClient(scala: runtime.ViewClient): ViewClient = JavaViews.client(scala, views)

    /** The HTTP server of the read endpoints: on `at`, or where the configuration says. */
    def httpServer(at: Option[(String, Int)]): HttpServer =
      val factory = (clients: EndpointClients) =>
        val java  = Clients.of(clients.componentClient, refs)
        val viewz = viewClient(clients.viewClient)
        JavaHttpEndpoint(endpoints.flatMap { plan =>
          val instance = EndpointCell.constructing(
            EndpointCell(java, Services.of(clients.services, serializers), viewz)
          )(plan.construct())
          Routes.scalaRoutes(plan, instance.asInstanceOf[HttpEndpoint], serializers)
        })
      at match
        case Some((host, port)) => HttpServer.at(host, port)(factory)
        case None               => HttpServer.of(factory)

  /** Reads every class, or throws every problem at once. */
  def read(
      classes: Vector[(Class[?], Option[String])],
      serializers: Declarations.Serializers,
      endpoints: Vector[Class[?]] = Vector.empty,
      /** Off for a component test kit, which hosts one component whatever it calls. */
      checkReferences: Boolean = true
  ): Read =
    val (viewClasses, others) = classes.partition((cls, _) => Views.isView(cls))
    val (consumerClasses, rest) =
      others.partition((cls, _) => Consumers.isConsumer(cls))
    val (actionClasses, entityClasses) =
      rest.partition((cls, _) => TimedActions.isTimedAction(cls))
    val actionResults = actionClasses.map((cls, id) => TimedActions.read(cls, id, serializers))
    val results       = entityClasses.map((cls, id) => Declarations.read(cls, id, serializers))
    val entities      = results.collect { case Right(plan) => plan }
    // A view is read after the entities, since its source names one of them.
    val viewResults = viewClasses.map((cls, id) => Views.read(cls, id, serializers, entities))
    val consumerResults =
      consumerClasses.map((cls, id) => Consumers.read(cls, id, serializers, entities))
    val routes = endpoints.map(Routes.read(_, serializers))
    val problems = results.collect { case Left(ps) => ps }.flatten ++
      viewResults.collect { case Left(ps) => ps }.flatten ++
      consumerResults.collect { case Left(ps) => ps }.flatten ++
      actionResults.collect { case Left(ps) => ps }.flatten ++
      routes.collect { case Left(ps) => ps }.flatten
    val registered = results.collect { case Right(plan) => plan.cls }.toSet ++
      viewResults.collect { case Right(plan) => plan.cls } ++
      consumerResults.collect { case Right(plan) => plan.cls } ++
      actionResults.collect { case Right(plan) => plan.cls }
    val checked =
      if checkReferences then (classes.map(_._1) ++ endpoints).distinct else Vector.empty
    val unregistered = checked.flatMap { cls =>
      References.components(cls).filterNot(registered).map { component =>
        val id = component.getAnnotation(classOf[ComponentId]).value
        DeclarationProblem(
          cls.getSimpleName,
          "",
          s"calls the component '$id' (${component.getSimpleName}), which the service does not register"
        )
      }
    }
    val all = problems ++ unregistered
    if all.nonEmpty then throw ServiceProblems(all.asJava)
    val plans = results.collect { case Right(plan) => plan }
    val refs  = Refs(plans)
    val views = viewResults.collect { case Right(plan) =>
      plan.cls -> (plan.componentId, Views.companion(plan))
    }
    val consumers   = consumerResults.collect { case Right(plan) => plan }
    val actionPlans = actionResults.collect { case Right(plan) => plan }
    val actions     = ActionIndex(actionPlans)
    val descriptors = plans.map(Adapters.descriptor(_, refs)) ++
      views.map(_._2._2.descriptor) ++
      consumers.map(Consumers.descriptor(_, refs, serializers)) ++
      actionPlans.map(TimedActions.descriptor(_, refs, actions, serializers))
    Read(
      plans,
      descriptors,
      refs,
      routes.collect { case Right(plan) => plan },
      serializers,
      views.toMap,
      consumers,
      actions
    )

  /** The Scala service of the read classes, its own checks reported as declaration problems. */
  def scalaBuilder(read: Read, http: Option[(String, Int)] = None): runtime.ServiceBuilder =
    val components = read.platformExtensions.foldLeft(
      runtime.Ankka.service.registerAll(read.descriptors)
    )(_.withExtension(_))
    val builder =
      if read.endpoints.isEmpty then components
      else components.withExtension(read.httpServer(http))
    builder.validate match
      case Left(problems) =>
        throw ServiceProblems(problems.map(p => DeclarationProblem("service", "", p)).asJava)
      case Right(_) => builder

/** A running Java service. */
private[ankka] final class JavaAnkkaService(
    name: String,
    val scala: runtime.AnkkaService,
    read: JavaServiceBuilder.Read
) extends AnkkaService:
  private val client = Clients.of(scala.componentClient, read.refs)
  private val views  = read.viewClient(scala.viewClient)

  def componentClient(): ComponentClient = client
  def viewClient(): ViewClient           = views
  def timers(): Timers =
    scala
      .extension[runtime.TimerRuntime]
      .fold(JavaTimers.unavailable)(t => read.timers(t.timerScheduler))
  def httpAddress(): URI =
    URI.create(
      scala.boundAddresses.headOption.getOrElse(
        throw IllegalStateException(s"$name serves no endpoint, or is not ready")
      )
    )
  def awaitReady(): Unit                  = scala.awaitReady()
  def awaitReady(timeout: Duration): Unit = scala.awaitReady(Effects.finite(timeout))
  def whenTerminated(): CompletionStage[Void] =
    scala.whenTerminated.map(_ => null.asInstanceOf[Void])(using ExecutionContext.parasitic).asJava
  def terminate(): Unit         = scala.terminate()
  override def toString: String = s"AnkkaService($name)"
  def close(): Unit =
    scala.terminate()
    Await.ready(scala.whenTerminated, 30.seconds): Unit
