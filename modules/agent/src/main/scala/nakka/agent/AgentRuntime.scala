package nakka.agent

import nakka.core.*
import nakka.runtime.{EntityProtocol, NakkaExecutors, NakkaService, RuntimeExtension}
import org.apache.pekko.NotUsed
import nakka.sdk.{ComponentClient, HandlerBinding}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.typed.scaladsl.ActorSource
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
    modelTimeout: FiniteDuration,
    compaction: Option[(CompactionSettings, Summariser)]
) extends RuntimeExtension:

  def name: String = "agents"

  /**
   * Enables compaction: long sessions get their oldest messages replaced by a summary.
   *
   * The summariser defaults to the runtime's own model. Compaction runs as a consumer
   * over session memory, so a `ProjectionRuntime` must also be registered — without one
   * the compactor is simply never started.
   */
  def withCompaction(
      settings: CompactionSettings = CompactionSettings(),
      summariser: Option[Summariser] = None
  ): AgentRuntime =
    val resolved = summariser.orElse(defaultModel.map(ModelSummariser(_, modelTimeout)))
    resolved match
      case None =>
        throw IllegalArgumentException(
          "compaction needs a summariser: either configure a default model with " +
            "AgentRuntime.withDefaultModel, or pass one explicitly"
        )
      case Some(summary) =>
        new AgentRuntime(defaultModel, modelTimeout, Some(settings -> summary))

  /**
   * Everything this runtime needs registered.
   *
   * An instance method rather than a static one because the set depends on how the
   * runtime is configured — enabling compaction adds a component.
   */
  def descriptors: Seq[ComponentDescriptor] =
    Seq(SessionMemoryEntity.descriptor) ++
      compaction.map((settings, summariser) => SessionCompactor.descriptor(settings, summariser))

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

      // Enabling compaction and then not registering it is the likely mistake, and it
      // fails silently — sessions just grow.
      compaction.foreach { (settings, _) =>
        if !service.registry.components.exists(_.componentId == SessionCompactor.ComponentId) then
          system.log.warn(
            "compaction is enabled but '{}' is not registered; use " +
              "registerAll(agentRuntime.descriptors) and register a ProjectionRuntime",
            SessionCompactor.ComponentId
          )
        else
          system.log.info(
            "session compaction enabled above {} characters, keeping {} recent messages",
            settings.maxHistoryBytes,
            settings.keepRecentMessages
          )
      }

object AgentRuntime:

  /** Agents must each name a model via `effects.model(...)`. */
  def apply(): AgentRuntime = new AgentRuntime(None, 2.minutes, None)

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
    new AgentRuntime(Some(provider), modelTimeout, None)

  /**
   * What an agent-capable service must register when compaction is not in use.
   *
   * With compaction, use the runtime's own `descriptors` instead — the set depends on
   * its configuration.
   */
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

  /** Signals that a stream finished, so the session can accept the next request. */
  private case object StreamFinished extends EntityProtocol.ModuleCommand

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
          case request: EntityProtocol.InvokeStream =>
            descriptor.streamHandler(MethodName(request.method)) match
              case None =>
                request.tokens ! EntityProtocol.StreamFailed(
                  CommandError(
                    s"no streaming handler '${request.method}' on agent " +
                      s"'${descriptor.componentId}'",
                    ErrorCode.NotFound
                  )
                )
                Behaviors.same

              case Some(handle) =>
                startStream(ctx, descriptor, context, loop, handle, request)
                busy

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
          case request: EntityProtocol.InvokeStream =>
            // Streams hold the session for their duration, which is the point: two
            // overlapping streams on one conversation would interleave their turns.
            request.tokens ! EntityProtocol.StreamFailed(
              CommandError(
                s"session '$sessionId' is busy; use a different session id to stream " +
                  "concurrently",
                ErrorCode.Unavailable
              )
            )
            Behaviors.same

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

          case StreamFinished =>
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

  /**
   * Runs a streaming handler, pushing tokens to the caller's ref.
   *
   * The session stays held until the stream finishes — `Finished` is only sent at the
   * end — so a conversation cannot be interleaved mid-stream.
   */
  private def startStream[A <: Agent](
      ctx: ActorContext[EntityProtocol.Command],
      descriptor: AgentDescriptor[A],
      context: AgentContext,
      loop: AgentLoop,
      handle: StreamHandle[A, ?],
      request: EntityProtocol.InvokeStream
  ): Unit =
    given ActorSystem[?] = ctx.system

    val agent = descriptor.create(context)
    agent._setContext(Some(context))

    val execution = Future {
      val effect =
        try
          handle
            .asInstanceOf[StreamHandle[A, Any]]
            .decodeAndInvoke(agent, request.payload)
        finally agent._setContext(None)

      loop.runStreaming(effect, text => request.tokens ! EntityProtocol.Token(text)) match
        case Right(())       => request.tokens ! EntityProtocol.StreamCompleted
        case Left(rejection) => request.tokens ! EntityProtocol.StreamFailed(rejection)
    }(using NakkaExecutors.virtual)

    ctx.pipeToSelf(execution) {
      case Success(_) => StreamFinished
      case Failure(failure) =>
        // The loop itself threw rather than returning a rejection; the caller is waiting
        // on the token stream, so it has to be told.
        request.tokens ! EntityProtocol.StreamFailed(
          CommandError(
            Option(failure.getMessage).getOrElse(failure.toString),
            ErrorCode.Internal
          )
        )
        StreamFinished
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
   * Streams a handler's reply.
   *
   * The `Source` is materialised by the caller and its actor ref sent to the agent, so
   * tokens flow directly from wherever the session is sharded to wherever this was
   * called. Nothing buffers the whole reply.
   */
  def stream[A <: Agent, I](handle: StreamHandle[A, I])(input: I): Source[String, NotUsed] =
    ActorSource
      .actorRef[EntityProtocol.StreamToken](
        completionMatcher = { case EntityProtocol.StreamCompleted => () },
        failureMatcher = { case failed: EntityProtocol.StreamFailed => failed.toCommandError },
        // Tokens are small and the consumer is usually a socket. Failing on overflow
        // rather than dropping: a silently truncated answer is worse than an error.
        bufferSize = 1024,
        overflowStrategy = OverflowStrategy.fail
      )
      .mapMaterializedValue { tokens =>
        transport.tell(
          handle.componentId,
          EntityId(sessionId),
          EntityProtocol.InvokeStream(
            handle.name,
            handle.inputSerializer.toBytes(input),
            Vector.empty,
            tokens
          )
        )
        NotUsed
      }
      .collect { case EntityProtocol.Token(text) => text }

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
