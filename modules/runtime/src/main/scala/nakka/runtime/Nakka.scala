package nakka.runtime

import com.typesafe.config.{Config, ConfigFactory}
import nakka.core.*
import nakka.sdk.*
import nakka.sdk.ComponentClient
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import org.apache.pekko.cluster.typed.{Cluster, Join}

import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * Something that runs alongside the hosted components and needs the service to exist before it can
 * start — an HTTP server, a set of view projections, a consumer group.
 *
 * This is the seam that keeps the module graph acyclic. `nakka-http` cannot be a dependency of
 * `nakka-runtime` (it depends on it), so the runtime knows only that extensions exist, not what
 * they do.
 */
trait RuntimeExtension:
  def name: String

  /** Called once, after components are hosted and the node is a cluster member. */
  def start(service: NakkaService): Unit

  /** Called when the service terminates. */
  def stop(): Unit = ()

/** Entry point for defining and starting a nakka service. */
object Nakka:

  /**
   * Begins a service definition.
   *
   * {{{
   * val service = Nakka.service
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
final class ServiceBuilder private[nakka] (
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
      name: String = "nakka",
      config: Config = ConfigFactory.load()
  ): NakkaService =
    val system = ActorSystem(Behaviors.empty, name, config)
    host(system, ownsSystem = true)

  /** Hosts every registered component on an existing actor system. */
  def startWith(system: ActorSystem[?]): NakkaService =
    host(system, ownsSystem = false)

  private def host(system: ActorSystem[?], ownsSystem: Boolean): NakkaService =
    val registry = ComponentRegistry.fromOrThrow(descriptors)
    val sharding = ClusterSharding(system)

    joinSelfIfUnseeded(system)

    val askTimeout =
      FiniteDuration(
        system.settings.config.getDuration("nakka.ask-timeout").toMillis,
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
      "nakka service started: {}",
      if registry.isEmpty then "no components registered" else registry.toString
    )

    val service = NakkaService(
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
      system.log.info("starting nakka extension '{}'", extension.name)
      extension.start(service)
    }

    service

  /**
   * Forms a single-node cluster when no seeds are configured.
   *
   * A nakka service is always a cluster, including on a laptop — running the same code path in
   * development and production is the point. Joining self removes the configuration step that would
   * otherwise make local runs a special case.
   */
  private def joinSelfIfUnseeded(system: ActorSystem[?]): Unit =
    val config = system.settings.config
    val seeds  = config.getStringList("pekko.cluster.seed-nodes").asScala
    if seeds.isEmpty && config.getBoolean("nakka.join-self-if-no-seed-nodes") then
      val cluster = Cluster(system)
      cluster.manager ! Join(cluster.selfMember.address)

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

/** A running nakka service. */
final class NakkaService private[nakka] (
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

  def whenTerminated: Future[?] = system.whenTerminated

  /** Stops every extension, then terminates the actor system if this service created it. */
  def terminate(): Unit =
    extensions.reverse.foreach { extension =>
      try extension.stop()
      catch
        case failure: Throwable =>
          system.log.warn(s"extension '${extension.name}' failed to stop", failure)
    }
    if ownsSystem then system.terminate()
