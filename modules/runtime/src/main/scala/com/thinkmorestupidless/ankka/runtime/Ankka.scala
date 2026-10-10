package com.thinkmorestupidless.ankka.runtime

import com.typesafe.config.Config
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import org.apache.pekko.cluster.typed.Cluster

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}

/**
 * Something that runs alongside the hosted components and needs the service to exist before it can
 * start — an HTTP server, a set of view projections, a consumer group.
 *
 * This is the seam that keeps the module graph acyclic. `ankka-http` cannot be a dependency of
 * `ankka-runtime` (it depends on it), so the runtime knows only that extensions exist, not what
 * they do.
 */
trait RuntimeExtension:
  def name: String

  /** Called once, after components are hosted and the node is a cluster member. */
  def start(service: AnkkaService): Unit

  /** Called when the service terminates. */
  def stop(): Unit = ()

  /**
   * Whether this extension is ready to serve, for the node's readiness check.
   *
   * `None` means "not applicable" and never holds readiness back. An extension that is not ready
   * until it has done something — bound a port, say — returns `Some` of a supplier saying whether
   * it has. `start` runs only after the node is a cluster member, so without this there is a moment
   * where a pod is a member, reports ready, and cannot yet answer a request.
   */
  def readiness: Option[() => Boolean] = None

  /**
   * Where this extension accepts requests, once it has bound — `http://127.0.0.1:9000` and such.
   *
   * `None` means "serves nothing addressable", which is the honest answer for a projection or a
   * timer runtime. It exists so the local console can offer to invoke a service's own routes
   * without `runtime` learning anything about `http`: the extension knows its address, the runtime
   * only knows that an extension may have one. Read when asked rather than at start, because
   * binding completes on its own schedule.
   */
  def boundAddress: Option[String] = None

  /**
   * What this extension will answer, as `(method, path template)` — `("POST", "/carts/{cartId}")`.
   *
   * Empty for an extension that answers nothing addressable. It exists so the console can turn a
   * service's own routes into a form, and it is a *description* rather than a way in: invoking one
   * means making an ordinary HTTP request to the address `boundAddress` reports, subject to the
   * same ACL as any other caller. There is deliberately no privileged path from here to a handler.
   */
  def routes: Vector[ServedRoute] = Vector.empty

  /**
   * Where this extension serves gRPC, once it has bound — `127.0.0.1:9090` — for a service on this
   * machine that calls it. Separate from `boundAddress`, which every reader takes for an HTTP
   * address: a gRPC address offered as one would be an invoke panel that cannot work.
   */
  def grpcAddress: Option[String] = None

/** Entry point for defining and starting an ankka service. */
object Ankka:

  /**
   * Begins a service definition.
   *
   * {{{
   * val service = Ankka.service
   *   .register(ShoppingCartEntity.descriptor)
   *   .register(ProfileEntity.descriptor)
   *   .start()
   * }}}
   */
  def service: ServiceBuilder = ServiceBuilder(Vector.empty, Vector.empty)

/**
 * An immutable, growing service definition.
 *
 * Registration is explicit — there is no classpath scanning — so the set of components a service
 * hosts is a value you can inspect, test and diff, and a component that was never registered fails
 * at startup rather than at its first request. The one thing added that the service did not hand
 * over is a platform extension a module on its classpath declares (`RuntimeExtensionProvider`),
 * which joins the service's own extensions after them when it starts.
 */
final class ServiceBuilder private[ankka] (
    private val descriptors: Vector[ComponentDescriptor],
    private val extensions: Vector[RuntimeExtension] = Vector.empty,
    private val conversation: Option[remote.Conversation] = None,
    private val identityOverride: Option[Either[String, ServiceIdentity]] = None,
    private val wrapServices: ServiceClients => ServiceClients = scala.Predef.identity
):

  def register(descriptor: ComponentDescriptor): ServiceBuilder =
    ServiceBuilder(
      descriptors :+ descriptor,
      extensions,
      conversation,
      identityOverride,
      wrapServices
    )

  def registerAll(more: Seq[ComponentDescriptor]): ServiceBuilder =
    ServiceBuilder(descriptors ++ more, extensions, conversation, identityOverride, wrapServices)

  /** Adds something that starts once the service is up — see `RuntimeExtension`. */
  def withExtension(extension: RuntimeExtension): ServiceBuilder =
    ServiceBuilder(
      descriptors,
      extensions :+ extension,
      conversation,
      identityOverride,
      wrapServices
    )

  /**
   * Who the service is, stated outright rather than read from where it runs. For tests: a test kit
   * plays a deployed service, or a local one with a name, without a certificate or a variable.
   */
  private[ankka] def withIdentity(identity: Either[String, ServiceIdentity]): ServiceBuilder =
    ServiceBuilder(descriptors, extensions, conversation, Some(identity), wrapServices)

  /**
   * How remote descriptors (feature 009) reach the developer's process. Supplied by the sidecar; an
   * in-process service never needs one. A remote descriptor registered without a conversation is a
   * validation error, not a hang at first command.
   */
  def withConversation(conversation: remote.Conversation): ServiceBuilder =
    ServiceBuilder(descriptors, extensions, Some(conversation), identityOverride, wrapServices)

  /**
   * Wraps the service's client for other services before anything receives it. For a test that
   * needs an outcome a machine without certificates cannot produce — an identity mismatch — and
   * nothing else.
   */
  private[ankka] def withServices(wrap: ServiceClients => ServiceClients): ServiceBuilder =
    ServiceBuilder(
      descriptors,
      extensions,
      conversation,
      identityOverride,
      wrapServices.andThen(wrap)
    )

  /** Validates the definition without starting anything. */
  def validate: Either[Vector[String], ComponentRegistry] =
    val remoteWithoutConversation =
      if conversation.isEmpty then
        descriptors.collect { case d: remote.RemoteDescriptor =>
          s"remote ${d.kind} '${d.componentId}' is registered but no conversation was supplied"
        }
      else Vector.empty
    val more = remoteWithoutConversation ++ TopicSourceRules.problems(descriptors) ++
      KeyedViewRules.problems(descriptors) ++ QueryCheck.problems(descriptors)
    ComponentRegistry.from(descriptors) match
      case Left(problems)  => Left(problems ++ more)
      case Right(registry) => if more.isEmpty then Right(registry) else Left(more)

  /** Creates an actor system and hosts every registered component on it. */
  def start(
      name: String = "ankka",
      config: Config = ClusterConfig.load()
  ): AnkkaService =
    // Before the actor system exists, so a service that cannot serve what it was deployed to
    // serve exits rather than idling with non-daemon threads holding the process open.
    DeclaredGrpc.check(sys.env.get, extensions.map(_.name))
    // Idempotent for a config the loader produced; for one a caller assembled itself, this is
    // what supplies the overlay it does not have.
    val system = ActorSystem(Behaviors.empty, name, ClusterConfig.layered(config))
    // A service that cannot start — an invalid registry, a malformed secret key — must not leave
    // the actor system it was given running behind the exception.
    try host(system, ownsSystem = true)
    catch
      case failure: Throwable =>
        system.terminate()
        throw failure

  /** Hosts every registered component on an existing actor system. */
  def startWith(system: ActorSystem[?]): AnkkaService =
    DeclaredGrpc.check(sys.env.get, extensions.map(_.name))
    host(system, ownsSystem = false)

  private def host(system: ActorSystem[?], ownsSystem: Boolean): AnkkaService =
    // Any a module on its classpath provides, then the service's own: started first and stopped
    // last. Stopped first, the telemetry exporter's final flush held every server open behind it
    // while the pod's other containers were already stopping, and a rolling restart refused requests.
    val extensions =
      RuntimeExtensionProvider.provided(system.settings.config) ++ this.extensions
    // Before anything runs a handler: every line a handler writes names its trace from here on.
    TraceLogging.install()
    val registry = validate.fold(
      problems =>
        StartRefusal.refuse(
          problems.mkString("invalid ankka service:\n  - ", "\n  - ", ""),
          IllegalArgumentException(_)
        ),
      identity
    )
    // Feature 037: a component that needs the database this service says it has not.
    if NoDatabase.declared(system.settings.config) then
      val needing = NoDatabase.problems(registry.components)
      if needing.nonEmpty then
        StartRefusal.refuse(
          needing.mkString("this service declares no database:\n  - ", "\n  - ", ""),
          IllegalArgumentException(_)
        )
    val sharding = ClusterSharding(system)

    // What the service declared, before anything can call anything: a name in a call's metadata is
    // believed only when it is one of these. The routes are added once the endpoints have started.
    Observability(system).declare(DeclaredNames.of(registry, Vector.empty))

    // Before formation: in Kubernetes the readiness check is served by the management endpoint
    // formation starts, and it must be able to see every extension's answer from its first call.
    ExtensionsReadiness(system).register(extensions.flatMap(_.readiness))
    ClusterFormation.form(system)

    val askTimeout =
      FiniteDuration(
        system.settings.config.getDuration("ankka.ask-timeout").toMillis,
        java.util.concurrent.TimeUnit.MILLISECONDS
      )

    val queryResendAfter =
      FiniteDuration(
        system.settings.config.getDuration("ankka.query-resend-after").toMillis,
        java.util.concurrent.TimeUnit.MILLISECONDS
      )

    // Components receive the client through their context, so it has to exist before any
    // of them is instantiated.
    val componentClient =
      ShardingTransport.clientFor(sharding, askTimeout, queryResendAfter)(using system)

    // The secret store too, for the same reason. A key that is set and malformed stops the start
    // here, naming the variable; no key at all is a service that runs and refuses to keep secrets.
    val secretKey = system.settings.config.getString("ankka.secrets.key").trim match
      case "" => None
      case text =>
        Some(
          SecretKey.parse(text).fold(problem => throw IllegalArgumentException(problem), identity)
        )
    // A service with no database (feature 037) keeps no secrets and opens no connection for them.
    val noDatabase = NoDatabase.declared(system.settings.config)
    val secrets: SecretStore =
      if noDatabase then SecretStore.unavailable
      else DatabaseSecretStore(Database()(using system), secretKey)

    // And the one client for other services that every component which may call one is given.
    val services: ServiceClients = wrapServices(ServiceBuilder.LazyServices(system))

    registry.components.foreach {
      case descriptor: EventSourcedEntityDescriptor[?, ?, ?] =>
        initEventSourced(sharding, descriptor, componentClient)
      case descriptor: KeyValueEntityDescriptor[?, ?] =>
        initKeyValue(sharding, descriptor, componentClient)
      case descriptor: WorkflowDescriptor[?, ?] =>
        initWorkflow(sharding, descriptor, componentClient, secrets, services)
      case descriptor: remote.RemoteKeyValueDescriptor =>
        val _ = sharding.init(
          Entity(EntityKeys.forComponent(descriptor.componentId)) { ctx =>
            remote.RemoteKeyValueHost.behavior(descriptor, EntityId(ctx.entityId), conversation.get)
          }
        )
      case descriptor: remote.RemoteWorkflowDescriptor =>
        // The in-process engine over a proxy whose handlers and steps cross the conversation.
        initWorkflow(
          sharding,
          remote.RemoteWorkflowHost.descriptor(descriptor, conversation.get, askTimeout),
          componentClient,
          secrets,
          services
        )
      case descriptor: remote.RemoteEventSourcedDescriptor =>
        // `conversation.get` is safe: `validate` refused the registry without one.
        val _ = sharding.init(
          Entity(EntityKeys.forComponent(descriptor.componentId)) { ctx =>
            remote.RemoteEventSourcedHost.behavior(
              descriptor,
              EntityId(ctx.entityId),
              conversation.get
            )
          }
        )
      case _ =>
        // Views, consumers, timers and endpoints are hosted by their own phases, and both kinds
        // of agent by AgentRuntime, an extension, because runtime cannot depend on the agent
        // module. An unrecognised descriptor is simply not sharded here.
        ()
    }

    system.log.info(
      "ankka {} service started: {}",
      com.thinkmorestupidless.ankka.core.BuildInfo.version,
      if registry.isEmpty then "no components registered" else registry.toString
    )

    // Resolved here and not refused: only a topic source needs it, and a service with none must
    // start as it always has. ProjectionRuntime refuses a topic source when this is a Left.
    val serviceIdentity =
      identityOverride.getOrElse(ServiceIdentity.resolve(system.settings.config))

    val service = AnkkaService(
      system,
      registry,
      componentClient,
      ViewClient(Database()(using system), askTimeout)(using system),
      ownsSystem,
      extensions,
      conversation,
      secrets,
      serviceIdentity,
      services
    )

    // Extensions need a cluster member to bind to and a client to call through, so they
    // start only once the node is genuinely up.
    service.awaitReady()
    service.registerShutdown()
    extensions.foreach { extension =>
      system.log.info("starting ankka extension '{}'", extension.name)
      extension.start(service)
    }
    Observability(system).declare(DeclaredNames.of(registry, service.routes))

    // After the extensions, so the endpoint can report the address they bound. Local mode only:
    // in Kubernetes the pod is the registry and management is the exposure, and management is
    // started there and only there because it binds a fixed port two local services would fight
    // over — which is the whole reason this endpoint exists separately.
    // "bootstrap" is the Kubernetes overlay's formation; anything else is a locally-run service.
    // Keyed off formation rather than the mode variable because that is what the overlay actually
    // sets, and a service given a config by hand still gets the right answer.
    val runningLocally =
      !system.settings.config.getString(ClusterFormation.FormationKey).equals("bootstrap")
    // Handed to the service rather than held here: the service is what gets terminated, and this
    // builder is a singleton that would keep only the most recently started endpoint. It was a
    // `var` on this object that nothing ever read, so nothing ever withdrew the registration and
    // every locally-run service leaked its entry into `~/.ankka/running` permanently.
    val documents = ObservabilityDocuments(service, system.name)
    if runningLocally then
      service.attachObservability(ObservabilityEndpoint.start(service, system.name, documents))
    // In a cluster the reader is the installation's control plane, over a port only it may open.
    else service.attachObserve(ObserveServer.startIfEnabled(system, documents))

    service

  private def initEventSourced(
      sharding: ClusterSharding,
      descriptor: EventSourcedEntityDescriptor[?, ?, ?],
      componentClient: ComponentClient
  ): Unit =
    // Generics are erased by the time a behaviour runs, and every value flowing through
    // it came from this same descriptor, so widening to `Any` here loses no safety that
    // the Companion's `command`/`query` signatures have not already established.
    type AnyEntity = EventSourcedEntity[Any, Any]
    val typed = descriptor.asInstanceOf[EventSourcedEntityDescriptor[AnyEntity, Any, Any]]
    val _ = sharding.init(
      Entity(EntityKeys.forComponent(typed.componentId)) { ctx =>
        EventSourcedEntityHost.behavior[AnyEntity, Any, Any](
          typed,
          EntityId(ctx.entityId),
          componentClient
        )
      }
    )

  private def initWorkflow(
      sharding: ClusterSharding,
      descriptor: WorkflowDescriptor[?, ?],
      componentClient: ComponentClient,
      secrets: SecretStore,
      services: ServiceClients
  ): Unit =
    type AnyWorkflow = Workflow[Any]
    val typed = descriptor.asInstanceOf[WorkflowDescriptor[AnyWorkflow, Any]]
    val _ = sharding.init(
      Entity(EntityKeys.forComponent(typed.componentId)) { ctx =>
        WorkflowHost.behavior[AnyWorkflow, Any](
          typed,
          EntityId(ctx.entityId),
          componentClient,
          secrets,
          services
        )
      }
    )

  private def initKeyValue(
      sharding: ClusterSharding,
      descriptor: KeyValueEntityDescriptor[?, ?],
      componentClient: ComponentClient
  ): Unit =
    type AnyEntity = KeyValueEntity[Any]
    val typed = descriptor.asInstanceOf[KeyValueEntityDescriptor[AnyEntity, Any]]
    val _ = sharding.init(
      Entity(EntityKeys.forComponent(typed.componentId)) { ctx =>
        KeyValueEntityHost.behavior[AnyEntity, Any](
          typed,
          EntityId(ctx.entityId),
          componentClient
        )
      }
    )

/**
 * A route an extension answers, as the console needs to render it.
 *
 * `streaming` is not decoration: a streaming response has no end the panel can wait for, so it has
 * to be read as it arrives. An agent's stream may run for a minute, and a panel that buffers shows
 * nothing for the whole of the interesting part.
 *
 * `endpoint` names the endpoint that serves the route, as a topology names it (`endpoint:/carts`).
 * A request's span says only that HTTP served it and by which route; this is what puts that route,
 * and every call made from it, on the endpoint a developer wrote.
 */
final case class ServedRoute(method: String, path: String, streaming: Boolean, endpoint: String)

object ServedRoute:
  /**
   * An endpoint's id in a topology. Its prefix is its name, in every language: a Scala endpoint has
   * no id of its own, and one in another language is served by the same HTTP server under the same
   * prefix.
   */
  def endpointId(name: String): String = s"endpoint:$name"

/** A running ankka service. */
final class AnkkaService private[ankka] (
    val system: ActorSystem[?],
    val registry: ComponentRegistry,
    val componentClient: ComponentClient,
    val viewClient: ViewClient,
    private val ownsSystem: Boolean,
    private val extensions: Vector[RuntimeExtension] = Vector.empty,
    /**
     * Present when remote components are registered: how the extensions hosting them reach the
     * process.
     */
    val conversation: Option[remote.Conversation] = None,
    /**
     * The service's secret store: one table in its own database, encrypted with its secret key.
     * Given to endpoints, workflow steps, consumers, timed actions and agents, never to an entity
     * or a view.
     */
    val secrets: SecretStore = SecretStore.unavailable,
    /**
     * Who this service is — what its topic sources' consumer groups are named for — or the sentence
     * saying why that could not be read. See `ServiceIdentity`.
     */
    val identity: Either[String, ServiceIdentity] = Right(ServiceIdentity.unnamed),
    /**
     * Other services, called as this one. Every component that may call one — an endpoint, a
     * workflow's step, a consumer, a timed action, an agent — and the sidecar on behalf of a
     * process is given this same one. Nothing behind it is built until the first call, since only a
     * service that calls another needs it, and in a cluster it reads the service's certificate.
     */
    val services: ServiceClients = ServiceBuilder.noServices
):

  /**
   * The names of the extensions this service runs — so one extension can say when another it relies
   * on is missing, rather than failing quietly.
   */
  def extensionNames: Vector[String] = extensions.map(_.name)

  /**
   * The extension of a type this service runs, when it runs one — `extension[TimerRuntime]` for its
   * clock, say. By type, so an extension that relies on another reaches it without the runtime
   * knowing what either is.
   */
  def extension[E <: RuntimeExtension](using tag: scala.reflect.ClassTag[E]): Option[E] =
    extensions.collectFirst { case e: E => e }

  /**
   * Blocks until this node is a cluster member.
   *
   * Sharding buffers messages sent before the node is up, so this is not required for correctness —
   * but without it a test's first assertion competes with cluster formation, and the resulting
   * flake is tedious to diagnose.
   */
  def awaitReady(timeout: FiniteDuration = 20.seconds): Unit =
    val cluster  = Cluster(system)
    val deadline = System.nanoTime() + timeout.toNanos
    while cluster.selfMember.status != MemberStatus.Up && System.nanoTime() < deadline do
      Thread.sleep(50)
    if cluster.selfMember.status != MemberStatus.Up then
      throw IllegalStateException(
        s"node did not reach Up within $timeout (status ${cluster.selfMember.status})"
      )

  /**
   * The addresses this service's extensions are serving on, asked at the moment of asking.
   *
   * Used by the observability endpoint so the console can send a request to the service's *own*
   * port, as an ordinary client — which is what makes "the console cannot bypass an ACL" a
   * structural fact rather than a rule someone has to remember.
   */
  def boundAddresses: Vector[String] = extensions.flatMap(_.boundAddress)

  /** Where this service serves gRPC, for a service on this machine that calls it. */
  def grpcAddresses: Vector[String] = extensions.flatMap(_.grpcAddress)

  /** Every route this service's extensions serve, for the console's invoke panel. */
  def routes: Vector[ServedRoute] = extensions.flatMap(_.routes)

  def whenTerminated: Future[?] = system.whenTerminated

  /** The local console endpoint, when this service is running outside Kubernetes. */
  @volatile private var observability: Option[ObservabilityEndpoint] = None

  private[runtime] def attachObservability(endpoint: Option[ObservabilityEndpoint]): Unit =
    observability = endpoint

  /** The observe listener, when this service is running in a cluster that enables one. */
  @volatile private var observe: Option[ObserveServer] = None

  private[runtime] def attachObserve(server: Option[ObserveServer]): Unit = observe = server

  /** The observe listener's port, for a suite that reads what the control plane would read. */
  private[ankka] def observePort: Option[Int] = observe.map(_.port)

  /**
   * Where the local console endpoint answers, when there is one. For a suite that reads what the
   * console would read, without going by way of the registry directory to find the address.
   */
  private[ankka] def observabilityAddress: Option[String] = observability.map(_.address)

  /**
   * Stops every extension, once, however many paths ask. A lazy val, so the first to ask starts the
   * stop and every other waits on the same one.
   *
   * Two paths do ask. `terminate` does, and so does Pekko's coordinated shutdown
   * (`registerShutdown`): on SIGTERM the JVM runs every shutdown hook at once, so a service's own
   * hook calling `terminate` races Pekko's, which ends with the actor system — and its stream
   * materializer — terminated. A gRPC stream still inside the server's shutdown grace was then
   * aborted and its caller told `INTERNAL`, instead of being given the grace and then told
   * `UNAVAILABLE`. Coordinated shutdown waiting on this stop before it terminates the actor system
   * is what makes "extensions first, then the actor system" hold whichever hook runs first.
   */
  private lazy val extensionsStopped: Future[Done] =
    Future {
      // First, so the console stops listing a service that is on its way out — and so the registry
      // entry is withdrawn even if an extension then fails to stop.
      try observability.foreach(_.stop())
      catch
        case failure: Throwable => system.log.warn("observability endpoint failed to stop", failure)
      try observe.foreach(_.stop())
      catch case failure: Throwable => system.log.warn("observe listener failed to stop", failure)
      observability = None

      // Before the extensions: a watch served as server-sent events is told why it ended while
      // the server can still send it.
      try viewClient.stopWatches()
      catch case failure: Throwable => system.log.warn("view watches failed to stop", failure)

      extensions.reverse.foreach { extension =>
        try extension.stop()
        catch
          case failure: Throwable =>
            system.log.warn(s"extension '${extension.name}' failed to stop", failure)
      }
      Done
    }(using AnkkaExecutors.virtual)

  /**
   * Has coordinated shutdown start stopping the extensions in its first phase, and wait for them in
   * its last, so the actor system is not terminated under them. Called once, when the service is
   * started.
   *
   * Started, not awaited, in the first phase: the phases between are the node leaving the cluster
   * and handing its shards off, and those must not wait for the extensions. Holding them back until
   * every server and client had stopped delayed the handoff, and requests for the departing node's
   * entities waited past a caller's timeout during a rolling restart — `ExposureClusterSuite`'s
   * rolling restart under load failed that way, every run, and passed without it.
   */
  private[runtime] def registerShutdown(): Unit =
    val shutdown = CoordinatedShutdown(system)
    shutdown.addTask(
      CoordinatedShutdown.PhaseBeforeServiceUnbind,
      "ankka-start-stopping-extensions"
    ) { () =>
      extensionsStopped: Unit
      Future.successful(Done)
    }
    shutdown.addTask(
      CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
      "ankka-await-extensions-stopped"
    )(() => extensionsStopped)

  /** Stops every extension, then terminates the actor system if this service created it. */
  def terminate(): Unit =
    Await.ready(extensionsStopped, Duration.Inf): Unit
    if ownsSystem then system.terminate()

object ServiceBuilder:

  /**
   * The service's client for other services, built on its first call: `HttpServiceClients` reads
   * the service's certificate in a cluster, and a service that calls nobody should not.
   */
  private[runtime] final class LazyServices(system: ActorSystem[?]) extends ServiceClients:
    private lazy val clients: ServiceClients =
      HttpServiceClients(system.settings.config, None, observability = Some(Observability(system)))
    def apply(name: String): ServiceClient                  = clients(name)
    def apply(project: String, name: String): ServiceClient = clients(project, name)

  /** For a service built without one: every call says so. */
  val noServices: ServiceClients = new ServiceClients:
    def apply(name: String): ServiceClient = apply("", name)
    def apply(project: String, name: String): ServiceClient =
      throw IllegalStateException(s"no service client is configured; cannot call '$name'")
