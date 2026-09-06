package nakka.agent

import nakka.core.*
import nakka.runtime.{EntityProtocol, NakkaExecutors, NakkaService, RuntimeExtension}
import nakka.sdk.{ComponentClient, HandlerBinding}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * Hosts agents and their session memory.
 *
 * An extension rather than part of the core runtime, for the same reason the HTTP server
 * is: `nakka-runtime` must not depend on `nakka-agent`. Registering this is what turns a
 * service into one that can run agents.
 */
final class AgentRuntime private (
    defaultModel: Option[ModelProvider],
    modelTimeout: FiniteDuration
) extends RuntimeExtension:

  def name: String = "agents"

  def start(service: NakkaService): Unit =
    given system: org.apache.pekko.actor.typed.ActorSystem[?] = service.system

    val agents = service.registry.components.collect { case a: AgentDescriptor[?] => a }
    if agents.isEmpty then system.log.debug("no agents registered")
    else
      val sharding = ClusterSharding(system)
      val client   = service.componentClient

      agents.foreach { descriptor =>
        val typed = descriptor.asInstanceOf[AgentDescriptor[Agent]]
        val _ = sharding.init(
          Entity(EntityTypeKey[EntityProtocol.Command](typed.componentId)) { ctx =>
            AgentHost.behavior(
              typed,
              SessionId(ctx.entityId),
              client,
              defaultModel,
              modelTimeout
            )
          }
        )
        system.log.info(
          "agent '{}' hosted (role '{}', up to {} tool-call steps)",
          typed.componentId,
          typed.role,
          typed.maxToolCallSteps
        )
      }

      if !service.registry.components.exists(_.componentId == SessionMemoryEntity.componentId) then
        system.log.warn(
          "agents are registered but '{}' is not; register " +
            "SessionMemoryEntity.descriptor or session memory calls will fail",
          SessionMemoryEntity.componentId
        )

object AgentRuntime:

  /** Agents must each name a model via `effects.model(...)`. */
  def apply(): AgentRuntime = new AgentRuntime(None, 2.minutes)

  /**
   * Supplies a default model, so handlers need only describe the interaction.
   *
   * A generous timeout by default: a reasoning model working through a multi-step task
   * with tools can legitimately take minutes, and a 30-second default would turn normal
   * behaviour into a failure.
   */
  def withDefaultModel(
      provider: ModelProvider,
      modelTimeout: FiniteDuration = 2.minutes
  ): AgentRuntime =
    new AgentRuntime(Some(provider), modelTimeout)

  /** Everything an agent-capable service must register. */
  def descriptors: Seq[ComponentDescriptor] = Seq(SessionMemoryEntity.descriptor)

/**
 * One agent instance, sharded by session id.
 *
 * Requests for the same session are handled strictly one at a time — the second is
 * stashed until the first finishes. Without that, two overlapping requests would read
 * the same history, both append to it, and produce a conversation where neither turn
 * acknowledges the other.
 */
private[agent] object AgentHost:

  private final case class Finished(
      replyTo: ActorRef[EntityProtocol.Reply],
      reply: EntityProtocol.Reply
  ) extends EntityProtocol.ModuleCommand

  private val StashCapacity = 32

  def behavior[A <: Agent](
      descriptor: AgentDescriptor[A],
      sessionId: SessionId,
      componentClient: ComponentClient,
      defaultModel: Option[ModelProvider],
      modelTimeout: FiniteDuration
  ): Behavior[EntityProtocol.Command] =
    Behaviors.setup { ctx =>
      Behaviors.withStash(StashCapacity) { stash =>
        val context = SimpleAgentContext(
          sessionId,
          descriptor.componentId,
          componentClient,
          defaultModel
        )

        val loop = new AgentLoop(
          descriptor.asInstanceOf[AgentDescriptor[Agent]],
          sessionId,
          componentClient,
          modelTimeout
        )

        def idle: Behavior[EntityProtocol.Command] = Behaviors.receiveMessage {
          case invoke: EntityProtocol.Invoke =>
            descriptor.handler(MethodName(invoke.method)) match
              case None =>
                invoke.replyTo ! EntityProtocol.Rejected(
                  CommandError(
                    s"no handler '${invoke.method}' on agent '${descriptor.componentId}'",
                    ErrorCode.NotFound
                  )
                )
                Behaviors.same

              case Some(binding) =>
                start(ctx, descriptor, context, loop, binding, invoke)
                busy

          case _ => Behaviors.same
        }

        def busy: Behavior[EntityProtocol.Command] = Behaviors.receiveMessage {
          case invoke: EntityProtocol.Invoke =>
            if stash.isFull then
              invoke.replyTo ! EntityProtocol.Rejected(
                CommandError(
                  s"session '$sessionId' has $StashCapacity requests queued; " +
                    "use a different session id to run interactions concurrently",
                  ErrorCode.Unavailable
                )
              )
              Behaviors.same
            else
              stash.stash(invoke)
              Behaviors.same

          case Finished(replyTo, reply) =>
            replyTo ! reply
            stash.unstashAll(idle)

          case _ => Behaviors.same
        }

        idle
      }
    }

  private def start[A <: Agent](
      ctx: ActorContext[EntityProtocol.Command],
      descriptor: AgentDescriptor[A],
      context: AgentContext,
      loop: AgentLoop,
      binding: HandlerBinding[A],
      invoke: EntityProtocol.Invoke
  ): Unit =
    // A fresh instance per request: the interaction runs on a virtual thread while the
    // actor keeps receiving, so sharing the agent's context slot would be a data race.
    val agent = descriptor.create(context)
    agent._setContext(Some(context))

    val execution = Future {
      val effect =
        try binding.decodeAndInvoke(agent, invoke.payload).asInstanceOf[AgentEffect[Any]]
        finally agent._setContext(None)

      loop.run(effect) match
        case Right(value) =>
          EntityProtocol.Succeeded(binding.encodeReply(value), Vector.empty)
        case Left(rejection) =>
          EntityProtocol.Rejected(rejection)
    }(using NakkaExecutors.virtual)

    ctx.pipeToSelf(execution) {
      case Success(reply) => Finished(invoke.replyTo, reply)
      case Failure(failure) =>
        Finished(
          invoke.replyTo,
          EntityProtocol.Rejected(
            CommandError(
              Option(failure.getMessage).getOrElse(failure.toString),
              ErrorCode.Internal
            )
          )
        )
    }

/** Calls to agents, addressed by session. */
final class AgentCalls private[agent] (
    transport: nakka.sdk.CallTransport,
    sessionId: SessionId
):
  def call[A <: Agent, I, O](
      handle: nakka.sdk.CommandHandle[A, I, O]
  ): nakka.sdk.Invocation[I, O] =
    nakka.sdk.Invocation(transport, EntityId(sessionId), handle)

  def call[A <: Agent, O](
      handle: nakka.sdk.NoArgHandle[A, O]
  ): nakka.sdk.NoArgInvocation[O] =
    nakka.sdk.NoArgInvocation(transport, EntityId(sessionId), handle)

/**
 * Adds `forAgent` to `ComponentClient`.
 *
 * An extension method rather than a member, because `ComponentClient` lives in
 * `nakka-sdk` and cannot see `Agent`. The call site reads the same either way.
 */
extension (client: ComponentClient)
  def forAgent(sessionId: SessionId): AgentCalls =
    AgentCalls(client.transportRef, sessionId)

  def forSessionMemory(sessionId: SessionId) =
    client.forEventSourcedEntity(EntityId(sessionId))
