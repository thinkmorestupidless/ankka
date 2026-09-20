package com.thinkmorestupidless.ankka.runtime

import com.typesafe.config.Config
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import org.apache.pekko.cluster.typed.Cluster

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}

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
 * at startup rather than at its first request.
 */
final class ServiceBuilder private[ankka] (
    private val descriptors: Vector[ComponentDescriptor],
    private val extensions: Vector[RuntimeExtension] = Vector.empty
):

  def register(descriptor: ComponentDescriptor): ServiceBuilder =
    ServiceBuilder(descriptors :+ descriptor, extensions)

  def registerAll(more: Seq[ComponentDescriptor]): ServiceBuilder =
    ServiceBuilder(descriptors ++ more, extensions)

  /** Adds something that starts once the service is up — see `RuntimeExtension`. */
  def withExtension(extension: RuntimeExtension): ServiceBuilder =
    ServiceBuilder(descriptors, extensions :+ extension)

  /** Validates the definition without starting anything. */
  def validate: Either[Vector[String], ComponentRegistry] =
    ComponentRegistry.from(descriptors)

  /** Creates an actor system and hosts every registered component on it. */
  def start(
      name: String = "ankka",
      config: Config = ClusterConfig.load()
  ): AnkkaService =
    // Idempotent for a config the loader produced; for one a caller assembled itself, this is
    // what supplies the overlay it does not have.
    val system = ActorSystem(Behaviors.empty, name, ClusterConfig.layered(config))
    host(system, ownsSystem = true)

  /** Hosts every registered component on an existing actor system. */
  def startWith(system: ActorSystem[?]): AnkkaService =
    host(system, ownsSystem = false)

  private def host(system: ActorSystem[?], ownsSystem: Boolean): AnkkaService =
    val registry = ComponentRegistry.fromOrThrow(descriptors)
    val sharding = ClusterSharding(system)

    // Before formation: in Kubernetes the readiness check is served by the management endpoint
    // formation starts, and it must be able to see every extension's answer from its first call.
    ExtensionsReadiness(system).register(extensions.flatMap(_.readiness))
    ClusterFormation.form(system)

    val askTimeout =
      FiniteDuration(
        system.settings.config.getDuration("ankka.ask-timeout").toMillis,
        java.util.concurrent.TimeUnit.MILLISECONDS
      )

    // Components receive the client through their context, so it has to exist before any
    // of them is instantiated.
    val componentClient = ShardingTransport.clientFor(sharding, askTimeout)(using system)

    registry.components.foreach {
      case descriptor: EventSourcedEntityDescriptor[?, ?, ?] =>
        initEventSourced(sharding, descriptor, componentClient)
      case descriptor: KeyValueEntityDescriptor[?, ?] =>
        initKeyValue(sharding, descriptor, componentClient)
      case descriptor: WorkflowDescriptor[?, ?] =>
        initWorkflow(sharding, descriptor, componentClient)
      case _ =>
        // Views, consumers, workflows, timers, endpoints and agents are hosted by their
        // own phases; an unrecognised descriptor is simply not sharded.
        ()
    }

    system.log.info(
      "ankka {} service started: {}",
      com.thinkmorestupidless.ankka.core.BuildInfo.version,
      if registry.isEmpty then "no components registered" else registry.toString
    )

    val service = AnkkaService(
      system,
      registry,
      componentClient,
      ViewClient(Database()(using system), askTimeout)(using system),
      ownsSystem,
      extensions
    )

    // Extensions need a cluster member to bind to and a client to call through, so they
    // start only once the node is genuinely up.
    service.awaitReady()
    extensions.foreach { extension =>
      system.log.info("starting ankka extension '{}'", extension.name)
      extension.start(service)
    }

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
    if runningLocally then
      service.attachObservability(ObservabilityEndpoint.start(service, system.name))

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
      componentClient: ComponentClient
  ): Unit =
    type AnyWorkflow = Workflow[Any]
    val typed = descriptor.asInstanceOf[WorkflowDescriptor[AnyWorkflow, Any]]
    val _ = sharding.init(
      Entity(EntityKeys.forComponent(typed.componentId)) { ctx =>
        WorkflowHost.behavior[AnyWorkflow, Any](
          typed,
          EntityId(ctx.entityId),
          componentClient
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
 */
final case class ServedRoute(method: String, path: String, streaming: Boolean)

/** A running ankka service. */
final class AnkkaService private[ankka] (
    val system: ActorSystem[?],
    val registry: ComponentRegistry,
    val componentClient: ComponentClient,
    val viewClient: ViewClient,
    private val ownsSystem: Boolean,
    private val extensions: Vector[RuntimeExtension] = Vector.empty
):

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

  /** Every route this service's extensions serve, for the console's invoke panel. */
  def routes: Vector[ServedRoute] = extensions.flatMap(_.routes)

  def whenTerminated: Future[?] = system.whenTerminated

  /** The local console endpoint, when this service is running outside Kubernetes. */
  @volatile private var observability: Option[ObservabilityEndpoint] = None

  private[runtime] def attachObservability(endpoint: Option[ObservabilityEndpoint]): Unit =
    observability = endpoint

  /** Stops every extension, then terminates the actor system if this service created it. */
  def terminate(): Unit =
    // First, so the console stops listing a service that is on its way out — and so the registry
    // entry is withdrawn even if an extension then fails to stop.
    try observability.foreach(_.stop())
    catch
      case failure: Throwable => system.log.warn("observability endpoint failed to stop", failure)
    observability = None

    extensions.reverse.foreach { extension =>
      try extension.stop()
      catch
        case failure: Throwable =>
          system.log.warn(s"extension '${extension.name}' failed to stop", failure)
    }
    if ownsSystem then system.terminate()
