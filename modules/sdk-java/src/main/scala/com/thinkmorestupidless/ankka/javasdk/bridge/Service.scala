package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.{AnkkaService, ServiceBuilder, ServiceIdentity}
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage
import com.thinkmorestupidless.ankka.javasdk.client.{ComponentClient, ViewClient}
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.http.{
  ClientKeysProvider,
  EndpointClients,
  HttpServer,
  ClientKeys as ScalaClientKeys
}
import com.thinkmorestupidless.ankka.javasdk.mcp.{ClientKeys, McpEndpoint}
import com.thinkmorestupidless.ankka.mcp as smcp
import com.thinkmorestupidless.ankka.javasdk.problems.{DeclarationProblem, ServiceProblems}
import com.thinkmorestupidless.ankka.javasdk.timer.Timers
import com.thinkmorestupidless.ankka.grpc as sgrpc
import com.thinkmorestupidless.ankka.javasdk.grpc.{GrpcClients, GrpcEndpoint}
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
import scala.util.chaining.*

/** A Java service definition: the classes it names, read at start. */
final case class JavaServiceBuilder private (
    name: String,
    classes: Vector[(Class[?], Option[String])],
    endpoints: Vector[Class[?]],
    serializers: Declarations.Serializers,
    http: Option[(String, Int)],
    grpcEndpoints: Vector[Class[?]] = Vector.empty,
    grpc: Option[(String, Int)] = None,
    grpcClients: Boolean = false,
    mcpEndpoints: Vector[Class[?]] = Vector.empty
) extends ServiceBuilder:

  def this(name: String) = this(name, Vector.empty, Vector.empty, Map.empty, None)

  def register(componentClass: Class[?]): ServiceBuilder =
    copy(classes = classes :+ (componentClass -> None))

  def register(componentClass: Class[?], componentId: String): ServiceBuilder =
    copy(classes = classes :+ (componentClass -> Some(componentId)))

  def withEndpoint(endpoint: Class[? <: HttpEndpoint]): ServiceBuilder =
    copy(endpoints = endpoints :+ endpoint)

  def withHttp(host: String, port: Int): ServiceBuilder = copy(http = Some(host -> port))

  def withGrpcEndpoint(endpoint: Class[? <: GrpcEndpoint]): ServiceBuilder =
    copy(grpcEndpoints = grpcEndpoints :+ endpoint)

  def withGrpc(host: String, port: Int): ServiceBuilder = copy(grpc = Some(host -> port))

  def withGrpcClients(): ServiceBuilder = copy(grpcClients = true)

  def withMcpEndpoint(endpoint: Class[? <: McpEndpoint]): ServiceBuilder =
    copy(mcpEndpoints = mcpEndpoints :+ endpoint)

  def withSerializer[A](tpe: Class[A], serializer: Serializer[A]): ServiceBuilder =
    copy(serializers = serializers + (tpe -> serializer))

  def start(): AnkkaService =
    val read =
      JavaServiceBuilder.read(
        classes,
        serializers,
        endpoints,
        grpcEndpoints = grpcEndpoints,
        mcpEndpoints = mcpEndpoints
      )
    val service = JavaServiceBuilder.problemsAtStart {
      JavaServiceBuilder
        .scalaBuilder(read, http)
        .pipe(b => read.grpcExtensions(grpc, grpcClients).foldLeft(b)(_.withExtension(_)))
        .start()
    }
    JavaAnkkaService(name, service, read)

private[ankka] object JavaServiceBuilder:

  private val HttpProblems = "invalid ankka http configuration:"

  /**
   * The HTTP server's own checks, which need the running service (an MCP endpoint's tools and the
   * handlers they name), as declaration problems rather than the server's exception.
   */
  def problemsAtStart[A](start: => A): A =
    try start
    catch
      case e: IllegalArgumentException if Option(e.getMessage).exists(_.startsWith(HttpProblems)) =>
        val problems = e.getMessage.linesIterator
          .map(_.trim)
          .collect { case line if line.startsWith("- ") => line.drop(2) }
          .map(DeclarationProblem("service", "", _))
          .toVector
        throw ServiceProblems(problems.asJava)

  /** Every class read, its descriptor built, and the refs that resolve calls to its handlers. */
  final case class Read(
      plans: Vector[ComponentPlan],
      descriptors: Vector[ComponentDescriptor],
      refs: Refs,
      endpoints: Vector[EndpointPlan] = Vector.empty,
      serializers: Declarations.Serializers = Map.empty,
      views: Map[Class[?], (String, Views.Companion)] = Map.empty,
      consumers: Vector[ConsumerPlan] = Vector.empty,
      actions: ActionIndex = ActionIndex.empty,
      grpcs: Vector[GrpcPlan] = Vector.empty,
      mcps: Vector[McpPlan] = Vector.empty
  ):
    /**
     * The platform's extensions the read components need, which a Java service never names: the
     * projections behind its views and consumers, and the timers its timed actions are run by.
     */
    def platformExtensions: Vector[runtime.RuntimeExtension] =
      platformExtensionsWith(runtime.ProjectionRuntime.fromEnv(), timerRuntime)

    /** The timers the platform's extensions run, which the endpoints schedule through. */
    lazy val timerRuntime: runtime.TimerRuntime = runtime.TimerRuntime()

    /** The Java timers over `scheduler`'s, refused when the service has no timed action. */
    def endpointTimers(scheduler: () => runtime.TimerRuntime): () => Timers =
      () => if actions.isEmpty then JavaTimers.unavailable else timers(scheduler().timerScheduler)

    /**
     * As [[platformExtensions]], with the projections and timers given: a deployed service is told
     * of its broker by its environment, a test kit gives one in memory and watches its timers.
     */
    def platformExtensionsWith(
        projections: => runtime.ProjectionRuntime,
        timers: => runtime.TimerRuntime
    ): Vector[runtime.RuntimeExtension] =
      (if views.nonEmpty || consumers.nonEmpty then Vector(projections) else Vector.empty) ++
        (if actions.isEmpty && mcps.isEmpty then Vector.empty else Vector(timers)) ++
        // Client keys, and the brokered sign-in's records, which its timers forget.
        (if mcps.isEmpty then Vector.empty else Vector(smcp.McpRuntime()))

    /** Whether anything is served over HTTP: routes, or an MCP endpoint's tools. */
    def servesHttp: Boolean = endpoints.nonEmpty || mcps.nonEmpty

    /** The gRPC server of the read gRPC endpoints, and the clients, when asked for. */
    def grpcExtensions(
        at: Option[(String, Int)],
        clients: Boolean
    ): Vector[runtime.RuntimeExtension] =
      val factories = grpcs.map { plan => (scala: EndpointClients) =>
        Grpc.scalaEndpoint(
          plan,
          GrpcCell(
            Clients.of(scala.componentClient, refs),
            Services.of(scala.services, serializers),
            viewClient(scala.viewClient),
            Stores.secrets(scala.secrets),
            Stores.storage(scala.storage)
          )
        )
      }
      val server =
        if factories.isEmpty then Vector.empty
        else
          Vector(at match
            case Some((host, port)) => sgrpc.GrpcServer.at(host, port)(factories*)
            case None               => sgrpc.GrpcServer.of(factories*))
      server ++ (if clients then Vector(sgrpc.GrpcClients()) else Vector.empty)

    /** The Java timers over a running service's scheduler. */
    def timers(scala: sdk.TimerScheduler): Timers =
      if actions.isEmpty then JavaTimers.unavailable else JavaTimers(scala, actions)

    /** The Java view client over a running service's. */
    def viewClient(scala: runtime.ViewClient): ViewClient = JavaViews.client(scala, views)

    /** The HTTP server of the read endpoints: on `at`, or where the configuration says. */
    def httpServer(
        at: Option[(String, Int)],
        timers: () => Timers = endpointTimers(() => timerRuntime)
    ): HttpServer =
      val factories = endpointFactories(timers)
      at match
        case Some((host, port)) => HttpServer.at(host, port)(factories*)
        case None               => HttpServer.of(factories*)

    /**
     * The Scala endpoints the read endpoints are served as: one for every route, then one for each
     * MCP endpoint. A host that serves other endpoints beside them passes these to its own server.
     */
    def endpointFactories(
        timers: () => Timers
    ): Vector[EndpointClients => com.thinkmorestupidless.ankka.http.HttpEndpoint] =
      def keys(clients: EndpointClients) = Mcp.clientKeys(clients.clientKeys)
      val mcpFactories = mcps.map { plan => (clients: EndpointClients) =>
        Mcp.scalaEndpoint(
          plan,
          McpCell(
            Some(refs),
            Clients.of(clients.componentClient, refs),
            Services.of(clients.services, serializers),
            Stores.secrets(clients.secrets),
            Stores.storage(clients.storage),
            keys(clients)
          )
        )
      }
      val factory = (clients: EndpointClients) =>
        val java  = Clients.of(clients.componentClient, refs)
        val viewz = viewClient(clients.viewClient)
        JavaHttpEndpoint(endpoints.foldLeft(Served()) { (served, plan) =>
          val instance = EndpointCell.constructing(
            EndpointCell(
              java,
              Services.of(clients.services, serializers),
              viewz,
              Stores.secrets(clients.secrets),
              Stores.storage(clients.storage),
              keys(clients),
              timers
            )
          )(plan.construct())
          served ++ Routes.scalaRoutes(plan, instance.asInstanceOf[HttpEndpoint], serializers)
        })
      (if endpoints.isEmpty then Vector.empty else Vector(factory)) ++ mcpFactories

  /** Reads every class, or throws every problem at once. */
  def read(
      classes: Vector[(Class[?], Option[String])],
      serializers: Declarations.Serializers,
      endpoints: Vector[Class[?]] = Vector.empty,
      /** Off for a component test kit, which hosts one component whatever it calls. */
      checkReferences: Boolean = true,
      grpcEndpoints: Vector[Class[?]] = Vector.empty,
      mcpEndpoints: Vector[Class[?]] = Vector.empty
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
    val grpcs  = grpcEndpoints.map(Grpc.read)
    val mcps   = mcpEndpoints.map(Mcp.read)
    val problems = results.collect { case Left(ps) => ps }.flatten ++
      viewResults.collect { case Left(ps) => ps }.flatten ++
      consumerResults.collect { case Left(ps) => ps }.flatten ++
      actionResults.collect { case Left(ps) => ps }.flatten ++
      routes.collect { case Left(ps) => ps }.flatten ++
      grpcs.collect { case Left(ps) => ps }.flatten ++
      mcps.collect { case Left(ps) => ps }.flatten
    val registered = results.collect { case Right(plan) => plan.cls }.toSet ++
      viewResults.collect { case Right(plan) => plan.cls } ++
      consumerResults.collect { case Right(plan) => plan.cls } ++
      actionResults.collect { case Right(plan) => plan.cls }
    val checked =
      if checkReferences then
        (classes.map(_._1) ++ endpoints ++ grpcEndpoints ++ mcpEndpoints).distinct
      else Vector.empty
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
    val descriptors = plans.map(Adapters.descriptor(_, refs, serializers)) ++
      views.map(_._2._2.descriptor) ++
      consumers.map(Consumers.descriptor(_, refs, serializers)) ++
      actionPlans.map(TimedActions.descriptor(_, refs, actions, serializers)) ++
      (if mcpEndpoints.isEmpty then Vector.empty else smcp.McpRuntime.descriptors.toVector)
    Read(
      plans,
      descriptors,
      refs,
      routes.collect { case Right(plan) => plan },
      serializers,
      views.toMap,
      consumers,
      actions,
      grpcs.collect { case Right(plan) => plan },
      mcps.collect { case Right(plan) => plan }
    )

  /** The Scala service of the read classes, its own checks reported as declaration problems. */
  def scalaBuilder(read: Read, http: Option[(String, Int)] = None): runtime.ServiceBuilder =
    val components = read.platformExtensions.foldLeft(
      runtime.Ankka.service.registerAll(read.descriptors)
    )(_.withExtension(_))
    val builder =
      if !read.servesHttp then components
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
  def secrets(): SecretStore             = Stores.secrets(scala.secrets)
  def clientKeys(): ClientKeys =
    Mcp.clientKeys(
      scala.extension[ClientKeysProvider].fold(ScalaClientKeys.unavailable)(_.clientKeys)
    )
  def storage(): ObjectStorage    = Stores.storage(scala.storage)
  def identity(): ServiceIdentity = Stores.identity(scala.identity)
  def grpcClients(): GrpcClients =
    scala.extension[sgrpc.GrpcClients].fold(Grpc.noClients)(Grpc.clients)
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
