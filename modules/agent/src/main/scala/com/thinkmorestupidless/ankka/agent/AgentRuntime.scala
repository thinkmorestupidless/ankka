package com.thinkmorestupidless.ankka.agent

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromArray, writeToArray}
import com.thinkmorestupidless.ankka.agent.judgment.{JudgmentProvider, Judgments}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{
  EntityProtocol,
  AnkkaExecutors,
  AnkkaService,
  MetaEntry,
  Observability,
  RuntimeExtension,
  SpanOutcome,
  Trace
}
import org.apache.pekko.NotUsed
import com.thinkmorestupidless.ankka.sdk.{
  CommandHandle,
  ComponentClient,
  HandlerBinding,
  NoArgHandle,
  SecretStore,
  ServiceClients
}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.typed.scaladsl.ActorSource
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.cluster.sharding.typed.ClusterShardingSettings
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Hosts agents and their session memory.
 *
 * An extension rather than part of the core runtime, for the same reason the HTTP server is:
 * `ankka-runtime` must not depend on `ankka-agent`. Registering this is what turns a service into
 * one that can run agents.
 */
final class AgentRuntime private (
    defaultModel: Option[ModelProvider],
    modelTimeout: FiniteDuration,
    compaction: Option[(CompactionSettings, Summariser)],
    judgments: Judgments,
    variables: String => Option[String] = sys.env.get,
    blueprintsBuilder: Option[blueprint.BlueprintContext => blueprint.BlueprintRegistry] = None
) extends RuntimeExtension:

  @volatile private var blueprintCalls: Option[blueprint.BlueprintCalls] = None
  @volatile private var runCalls: Option[blueprint.RunCalls]             = None

  def name: String = "agents"

  /**
   * What this service's blueprints may name, built once from a context when the service starts: a
   * tool that writes an entity needs the component client when it is built. Registers the
   * blueprints the registry carries, and refuses to start when one has problems.
   */
  def withBlueprints(
      build: blueprint.BlueprintContext => blueprint.BlueprintRegistry
  ): AgentRuntime =
    new AgentRuntime(defaultModel, modelTimeout, compaction, judgments, variables, Some(build))

  /** Calls about blueprints. Available once the service has started. */
  def blueprints: blueprint.BlueprintCalls =
    blueprintCalls.getOrElse(
      throw IllegalStateException(
        "blueprints are not available: the service has not started, or withBlueprints(...) was not called"
      )
    )

  /** Calls about runs. Available once the service has started. */
  def runs: blueprint.RunCalls =
    runCalls.getOrElse(
      throw IllegalStateException(
        "runs are not available: the service has not started, or withBlueprints(...) was not called"
      )
    )

  /**
   * Where the runtime reads the variables an agent's MCP servers name — their addresses and their
   * credentials. The process's environment unless a test gives its own, so a test sets them without
   * touching the environment of the JVM it runs in.
   */
  def withVariables(read: String => Option[String]): AgentRuntime =
    new AgentRuntime(defaultModel, modelTimeout, compaction, judgments, read, blueprintsBuilder)

  /**
   * Enables compaction: long sessions get their oldest messages replaced by a summary.
   *
   * The summariser defaults to the runtime's own model. Compaction runs as a consumer over session
   * memory, so a `ProjectionRuntime` must also be registered — without one the compactor is simply
   * never started.
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
        new AgentRuntime(
          defaultModel,
          modelTimeout,
          Some(settings -> summary),
          judgments,
          variables,
          blueprintsBuilder
        )

  /**
   * Supplies a judgment provider, so an agent can ask typed questions of a state and a judged
   * guardrail has someone to ask.
   *
   * A judgment is meant to be quick — a System One model answers in well under a second — so the
   * timeout is short, and it bounds everything: a provider's retries included.
   */
  def withJudgments(
      provider: JudgmentProvider,
      timeout: FiniteDuration = Judgments.DefaultTimeout
  ): AgentRuntime =
    if timeout.length <= 0 then
      throw IllegalArgumentException("withJudgments needs a positive timeout")
    new AgentRuntime(
      defaultModel,
      modelTimeout,
      compaction,
      Judgments(Some(provider), timeout),
      variables,
      blueprintsBuilder
    )

  /**
   * Everything this runtime needs registered.
   *
   * An instance method rather than a static one because the set depends on how the runtime is
   * configured — enabling compaction adds a component.
   */
  def descriptors: Seq[ComponentDescriptor] =
    AgentRuntime.descriptors ++
      compaction.map((settings, summariser) => SessionCompactor.descriptor(settings, summariser))

  def start(service: AnkkaService): Unit =
    given system: org.apache.pekko.actor.typed.ActorSystem[?] = service.system

    judgments.default.foreach { provider =>
      system.log.info(
        "judgments answered by '{}' ({}), within {}",
        provider.name,
        provider.modelName,
        judgments.timeout
      )
    }

    // Approval time limits and schedules are kept as timers, which need the service's TimerRuntime
    // to fire; its clock is the one they are set by, so a test that moves it moves them (R14).
    val clock: java.time.Clock =
      service
        .extension[com.thinkmorestupidless.ankka.runtime.TimerRuntime]
        .map(_.clock)
        .getOrElse(java.time.Clock.systemUTC())
    val timers: Option[com.thinkmorestupidless.ankka.sdk.TimerScheduler] =
      Option.when(service.extensionNames.contains("timers"))(
        com.thinkmorestupidless.ankka.runtime.DatabaseTimerScheduler(
          com.thinkmorestupidless.ankka.runtime.Database(),
          clock
        )
      )

    // Blueprints first: the autonomous hosts resolve a work step's task against the registry.
    startBlueprints(service, timers, clock)
    startAutonomous(service, timers)

    val agents = service.registry.components.collect { case a: AgentDescriptor[?] => a }
    if agents.isEmpty then system.log.debug("no agents registered")
    else
      val sharding = ClusterSharding(system)
      val client   = service.componentClient

      agents.foreach { descriptor =>
        val typed = descriptor.asInstanceOf[AgentDescriptor[Agent]] match
          // The one agent every worker's turn goes to builds each turn from the service's registry.
          case ask if ask.componentId == blueprint.AskAgent.componentId =>
            blueprintCalls.fold(ask)(calls =>
              blueprint.AskAgent.hosted(calls.registry).asInstanceOf[AgentDescriptor[Agent]]
            )
          case other => other
        val mcpTools = connectMcp(typed.componentId, typed.mcpServers, service, timers)
        val _ = sharding.init(
          Entity(EntityTypeKey[EntityProtocol.Command](typed.componentId)) { ctx =>
            AgentHost.behavior(
              typed,
              SessionId(ctx.entityId),
              client,
              defaultModel,
              modelTimeout,
              judgments,
              service.secrets,
              service.services,
              timers,
              mcpTools
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

  /**
   * Builds the registry of what blueprints may name, connects its MCP servers, and registers the
   * blueprints the service carries. A carried blueprint with problems fails the start, naming them,
   * by the same rule an unregistered component does.
   */
  private def startBlueprints(
      service: AnkkaService,
      timers: Option[com.thinkmorestupidless.ankka.sdk.TimerScheduler],
      clock: java.time.Clock
  )(using system: ActorSystem[?]): Unit =
    blueprintsBuilder.foreach { build =>
      val context = new blueprint.BlueprintContext:
        def componentClient = service.componentClient
        def viewClient      = service.viewClient
        def services        = service.services
        def secrets         = service.secrets
        def hasTimers       = timers.isDefined
      val declared = build(context).withTimers(timers.isDefined)
      val mcpTools = connectMcp(ComponentId("ankka-blueprints"), declared.servers, service, timers)
      val registry = declared.withMcpTools(mcpTools)
      val calls    = blueprint.BlueprintCalls(service.componentClient, registry, timers, clock)
      declared.carried.foreach { carried =>
        val registered =
          try calls.register(carried)
          catch
            case e: CommandError =>
              throw IllegalArgumentException(
                s"the blueprint '${carried.name}' this service carries has problems:\n" +
                  blueprint.BlueprintRefusal.describe(e)
              )
        system.log.info(
          "blueprint '{}' is at version {}{}",
          registered.name,
          registered.version,
          if registered.isNew then " (registered now)" else ""
        )
      }
      blueprintCalls = Some(calls)
      val views = Option.when(service.extensionNames.contains("projections"))(service.viewClient)
      runCalls = Some(blueprint.RunCalls(service.componentClient, calls, views))

      // One host per run, remembered: a run working when its node stopped is started again by
      // sharding itself, from its record, with nothing sent to it.
      val _ = ClusterSharding(system).init(
        Entity(EntityTypeKey[EntityProtocol.Command](blueprint.RunHost.ComponentId)) { ctx =>
          blueprint.RunHost.behavior(
            ctx.entityId,
            ctx.shard,
            service.componentClient,
            registry,
            judgments
          )
        }.withStopMessage(blueprint.RunHost.Stop)
          .withSettings(
            ClusterShardingSettings(system)
              .withRememberEntities(true)
              .withRememberEntitiesStoreMode(
                ClusterShardingSettings.RememberEntitiesStoreModeEventSourced
              )
          )
      )
      if !service.registry.components.exists(_.componentId == blueprint.BlueprintEntity.componentId)
      then
        system.log.warn(
          "blueprints are configured but '{}' is not registered; use registerAll(AgentRuntime.descriptors)",
          blueprint.BlueprintEntity.componentId
        )
    }

  /**
   * An agent's MCP servers, connected, as tools. Done once per agent when the service starts; a
   * server that cannot be used fails the start, naming it and the agent.
   */
  private def connectMcp(
      agentId: ComponentId,
      servers: Vector[mcp.McpServer],
      service: AnkkaService,
      timers: Option[com.thinkmorestupidless.ankka.sdk.TimerScheduler]
  )(using system: ActorSystem[?]): Vector[FunctionTool] =
    if servers.isEmpty then Vector.empty
    else
      val config = system.settings.config
      def duration(path: String) =
        scala.concurrent.duration.FiniteDuration(config.getDuration(path).toMillis, "ms")
      val tools = mcp.McpTools.connect(
        agentId.toString,
        servers,
        variables,
        service.services,
        timers.isDefined,
        mcp.McpTools.Settings(
          duration("ankka.agent.mcp.connect-timeout"),
          duration("ankka.agent.mcp.call-timeout"),
          com.thinkmorestupidless.ankka.core.BuildInfo.version
        )
      )
      system.log.info(
        "agent '{}' offers {} tool(s) from MCP server(s) {}",
        agentId,
        tools.size,
        servers.map(_.name).mkString(", ")
      )
      tools

  /**
   * Hosts every autonomous agent, one sharded instance per instance id.
   *
   * The entity type remembers its entities: an instance working a task has no caller to wake it
   * after a crash or a move between nodes, and a remembered entity is started again by sharding
   * itself, from the journal. Remembering also turns automatic passivation off for the type, so an
   * instance leaves memory only when it passivates itself, which it does when it has nothing to do
   * and nobody is watching.
   */
  private def startAutonomous(
      service: AnkkaService,
      timers: Option[com.thinkmorestupidless.ankka.sdk.TimerScheduler]
  )(using system: ActorSystem[?]): Unit =
    val autonomousAgents = service.registry.components.collect {
      case a: autonomous.AutonomousAgentDescriptor[?] => a
    }
    if autonomousAgents.nonEmpty then
      val sharding = ClusterSharding(system)
      val client   = service.componentClient
      // A task that carries a definition of its own is a blueprint's work step: resolved against
      // what the service registered for blueprints, or refused when it registered nothing.
      val perTask: autonomous.TaskDefinitionResolver =
        blueprintCalls.fold(autonomous.TaskDefinitionResolver.none)(calls =>
          blueprint.BlueprintTasks.resolver(calls.registry, defaultModel)
        )
      autonomousAgents.foreach { d =>
        val descriptor =
          d.asInstanceOf[autonomous.AutonomousAgentDescriptor[autonomous.AutonomousAgent]]
        // The tools are declared on the instance, so they are checked on one, here, rather than
        // failing the first task an instance is given.
        val probe = descriptor.create(
          autonomous.SimpleAutonomousAgentContext(
            descriptor.componentId,
            "(startup)",
            client,
            defaultModel,
            service.secrets,
            service.services
          )
        )
        val mcpTools =
          connectMcp(descriptor.componentId, descriptor.definition.mcpServers, service, timers)
        val limited = probe.tools.find(_.approval.exists(_.within.isDefined))
        val problems = autonomous.AutonomousAgentDefinition.toolProblems(probe.tools) ++
          limited
            .filter(_ => timers.isEmpty)
            .map(t => ApprovalExpiry.needsTimers(s"tool '${t.name}'"))
        if problems.nonEmpty then
          throw IllegalArgumentException(
            problems.mkString(
              s"invalid autonomous agent '${descriptor.componentId}':\n  - ",
              "\n  - ",
              ""
            )
          )
        AgentRuntime.unanswerableGuardrail(descriptor.definition.guardrails, judgments).foreach {
          guardrail =>
            system.log.warn(
              "autonomous agent '{}' has the judged guardrail '{}' and no judgment provider: its " +
                "tasks will fail until one is given with provider(...) or configured with " +
                "withJudgments(...) on the AgentRuntime",
              descriptor.componentId,
              guardrail
            )
        }
        // As for a request agent, a missing model refuses the work that needs one, not the service:
        // a task given to this agent fails at once, saying why.
        if descriptor.definition.model.orElse(defaultModel).isEmpty then
          system.log.warn(
            "autonomous agent '{}' has no model: its tasks will fail until one is set with model(...) " +
              "on its definition, or a default provider is configured on the AgentRuntime",
            descriptor.componentId
          )

        val _ = sharding.init(
          Entity(EntityTypeKey[EntityProtocol.Command](descriptor.componentId)) { ctx =>
            autonomous.AutonomousAgentHost.behavior(
              descriptor,
              ctx.entityId,
              ctx.shard,
              client,
              defaultModel,
              modelTimeout,
              judgments,
              service.secrets,
              service.services,
              timers,
              mcpTools,
              perTask
            )
          }.withStopMessage(autonomous.AutonomousAgentHost.Stop)
            .withSettings(
              ClusterShardingSettings(system)
                .withRememberEntities(true)
                .withRememberEntitiesStoreMode(
                  ClusterShardingSettings.RememberEntitiesStoreModeEventSourced
                )
            )
        )
        system.log.info(
          "autonomous agent '{}' hosted, accepting {}",
          descriptor.componentId,
          descriptor.definition.acceptances
            .map(a => s"'${a.taskType.name}' (up to ${a.budget} iterations)")
            .mkString(", ")
        )
      }

      val registered = service.registry.components.map(_.componentId).toSet
      val missing    = AgentRuntime.descriptors.map(_.componentId).filterNot(registered)
      if missing.nonEmpty then
        system.log.warn(
          "autonomous agents are registered but {} are not; use registerAll(AgentRuntime.descriptors)",
          missing.mkString(", ")
        )
      // A dependency that fails cancels its dependents through a consumer, and a consumer runs
      // only under a projection runtime. Without one, the cascade silently never happens.
      if !service.extensionNames.contains("projections") then
        system.log.warn(
          "autonomous agents are registered without a ProjectionRuntime; a task whose dependency " +
            "fails will wait forever instead of being cancelled. Register ProjectionRuntime()."
        )

object AgentRuntime:

  /** The first judged guardrail nobody can answer: no provider of its own, and none configured. */
  private[agent] def unanswerableGuardrail(
      guardrails: Vector[Guardrail],
      judgments: Judgments
  ): Option[String] =
    if judgments.default.isDefined then None
    else
      guardrails.collectFirst {
        case g: judgment.JudgedGuardrail if !g.hasOwnProvider => g.name
      }

  private[agent] def noJudgmentProvider(componentId: ComponentId, guardrail: String): String =
    s"'$componentId' has the judged guardrail '$guardrail' and no judgment provider: give it one " +
      "with provider(...), or configure one with withJudgments(...) on the AgentRuntime"

  /** Agents must each name a model via `effects.model(...)`. */
  def apply(): AgentRuntime = new AgentRuntime(None, 2.minutes, None, Judgments.none)

  /**
   * Supplies a default model, so handlers need only describe the interaction.
   *
   * A generous timeout by default: a reasoning model working through a multi-step task with tools
   * can legitimately take minutes, and a 30-second default would turn normal behaviour into a
   * failure.
   */
  def withDefaultModel(
      provider: ModelProvider,
      modelTimeout: FiniteDuration = 2.minutes
  ): AgentRuntime =
    new AgentRuntime(Some(provider), modelTimeout, None, Judgments.none)

  /**
   * What an agent-capable service must register when compaction is not in use.
   *
   * With compaction, use the runtime's own `descriptors` instead — the set depends on its
   * configuration.
   */
  def descriptors: Seq[ComponentDescriptor] =
    Seq(
      SessionMemoryEntity.descriptor,
      autonomous.TaskEntity.descriptor,
      autonomous.InstanceEntity.descriptor,
      autonomous.TaskCascade.descriptor,
      ApprovalExpiry.platformDescriptor,
      blueprint.BlueprintEntity.descriptor,
      blueprint.RunEntity.descriptor,
      blueprint.AskAgent.platformDescriptor,
      blueprint.WorkerAgent.platformDescriptor,
      blueprint.RunsView.platformDescriptor,
      blueprint.ScheduleTimer.platformDescriptor
    )

/**
 * One agent instance, sharded by session id.
 *
 * Requests for the same session are handled strictly one at a time — the second is stashed until
 * the first finishes. Without that, two overlapping requests would read the same history, both
 * append to it, and produce a conversation where neither turn acknowledges the other.
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
      modelTimeout: FiniteDuration,
      judgments: Judgments,
      secrets: SecretStore,
      services: ServiceClients,
      timers: Option[com.thinkmorestupidless.ankka.sdk.TimerScheduler] = None,
      mcpTools: Vector[FunctionTool] = Vector.empty
  ): Behavior[EntityProtocol.Command] =
    Behaviors.setup { ctx =>
      Behaviors.withStash(StashCapacity) { stash =>
        val context = SimpleAgentContext(
          sessionId,
          descriptor.componentId,
          componentClient,
          defaultModel,
          secrets,
          services
        )

        val loop = new AgentLoop(
          descriptor.asInstanceOf[AgentDescriptor[Agent]],
          sessionId,
          componentClient,
          modelTimeout,
          judgments,
          timers,
          ToolSpans(Some(Observability(ctx.system)), descriptor.componentId.toString),
          mcpTools
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

          case invoke: EntityProtocol.Invoke if invoke.method == Approvals.DecideMethod =>
            // A decision holds the session as a request does: it may run tools and the model.
            startDecide(ctx, descriptor, context, loop, componentClient, sessionId, timers, invoke)
            busy

          case invoke: EntityProtocol.Invoke =>
            descriptor.handler(MethodName(invoke.method)) match
              case None =>
                // Answered by the host, and no handler ran: a call that was not delivered.
                Observability(ctx.system).undelivered(
                  MetaEntry.toMetadata(invoke.metadata),
                  descriptor.componentId.toString,
                  None
                )
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

    // Taken here, on the actor's thread; the interaction runs on one of its own.
    val observability = Observability(ctx.system)
    val incoming      = MetaEntry.toMetadata(invoke.metadata)
    val component     = descriptor.componentId.toString

    val execution = Future {
      // The whole interaction is the agent's handler at work: the handler itself, and the loop of
      // model and tool calls its effect describes. So it is one span, and a call a tool makes, or
      // the loop makes to session memory, is the agent's call. Set here, on the thread that does
      // the work, and not around this Future.
      observability.invocation[EntityProtocol.Reply](component, invoke.method, incoming)(
        Observability.outcomeOf
      ) {
        val effect =
          try binding.decodeAndInvoke(agent, invoke.payload).asInstanceOf[AgentEffect[Any]]
          finally agent._setContext(None)

        reply(
          loop.run(effect, TurnOrigin(invoke.method, streaming = false, invoke.payload)),
          binding.encodeReply
        )
      }
    }(using AnkkaExecutors.virtual)

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
   * A turn's outcome as a reply: an answer encoded by the handler's own serializer, or approval
   * requests marked as such in the reply's metadata. The envelope is the one every node reads, so
   * no serializer changes for it.
   */
  private def reply[R](
      outcome: Either[CommandError, AgentOutcome[R]],
      encode: Any => Array[Byte]
  ): EntityProtocol.Reply =
    outcome match
      case Right(AgentOutcome.Answered(value)) =>
        EntityProtocol.Succeeded(encode(value), Vector.empty)
      case Right(AgentOutcome.AwaitingApproval(requests)) =>
        EntityProtocol.Succeeded(
          writeToArray(Approvals.Awaiting(requests)),
          Vector(MetaEntry(Approvals.OutcomeKey, Approvals.OutcomeValue))
        )
      case Left(rejection) => EntityProtocol.Rejected(rejection)

  /**
   * Records a decision and, when it was the turn's last awaited one, goes on with the turn.
   *
   * The decision is recorded before anything acts on it. The turn's effect is rebuilt by running
   * the handler that began it on the request it was given — safe, because building an effect does
   * no I/O — and the loop resumes from what the turn recorded. The caller is answered as the
   * handler's own caller would have been: the model's answer, or requests still or newly awaiting.
   */
  private def startDecide[A <: Agent](
      ctx: ActorContext[EntityProtocol.Command],
      descriptor: AgentDescriptor[A],
      context: AgentContext,
      loop: AgentLoop,
      componentClient: ComponentClient,
      sessionId: SessionId,
      timers: Option[com.thinkmorestupidless.ankka.sdk.TimerScheduler],
      invoke: EntityProtocol.Invoke
  ): Unit =
    val observability = Observability(ctx.system)
    val incoming      = MetaEntry.toMetadata(invoke.metadata)
    val component     = descriptor.componentId.toString
    val memory        = componentClient.forEventSourcedEntity(EntityId(sessionId))

    def awaiting(requests: Vector[ApprovalRequest]) =
      reply(Right(AgentOutcome.AwaitingApproval(requests)), _ => Array.emptyByteArray)

    def run(): EntityProtocol.Reply =
      val request = readFromArray[Approvals.DecideRequest](invoke.payload)
      val before  = memory.call(SessionMemoryEntity.history).invoke()
      before.suspended match
        case Some(turn) if request.handler.nonEmpty && request.handler != turn.handler =>
          EntityProtocol.Rejected(
            CommandError(
              s"the turn awaiting a decision in session '$sessionId' was begun by handler " +
                s"'${turn.handler}', not '${request.handler}'",
              ErrorCode.BadRequest
            )
          )
        case _ =>
          // A suspended turn with nothing awaiting was cut off after its last decision; it ends
          // here, and its decisions, never written to a result, are not found.
          if before.suspended.exists(_.awaiting.isEmpty) then
            memory.call(SessionMemoryEntity.endTurn).invoke(): Unit
          val turn = memory.call(SessionMemoryEntity.decideApproval).invoke(request.decision)
          timers.foreach(
            ApprovalExpiry.forget(
              _,
              ApprovalExpiry.Due(
                ApprovalExpiry.RequestAgent,
                descriptor.componentId,
                sessionId,
                request.decision.approvalId
              )
            )
          )
          if turn.awaiting.nonEmpty then awaiting(turn.awaiting)
          else resume(turn)

    def resume(turn: SuspendedTurn): EntityProtocol.Reply =
      val method = MethodName(turn.handler)
      val agent  = descriptor.create(context)
      agent._setContext(Some(context))
      val rebuilt =
        try
          descriptor.handler(method) match
            case Some(binding) =>
              Some(
                binding.decodeAndInvoke(agent, turn.payloadBytes).asInstanceOf[AgentEffect[Any]] ->
                  binding.encodeReply
              )
            case None =>
              descriptor.streamHandler(method).map { handle =>
                val effect = handle
                  .asInstanceOf[StreamHandle[A, Any]]
                  .decodeAndInvoke(agent, turn.payloadBytes)
                  .effect
                effect.asInstanceOf[AgentEffect[Any]] ->
                  ((value: Any) => value.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8))
              }
        finally agent._setContext(None)
      rebuilt match
        case None =>
          memory.call(SessionMemoryEntity.endTurn).invoke(): Unit
          EntityProtocol.Rejected(
            CommandError(
              s"agent '${descriptor.componentId}' has no handler '${turn.handler}' to go on with " +
                "the turn that awaited a decision; the turn is ended",
              ErrorCode.Internal
            )
          )
        case Some((effect, encode)) => reply(loop.resume(effect, turn), encode)

    // A decision is the work of the handler whose turn it decides: a tool it runs is that
    // handler's call, as it would have been had the turn not waited. The name is taken from the
    // request only when the agent declares it, so nothing a caller sends grows the names table.
    val handlerName =
      scala.util
        .Try(readFromArray[Approvals.DecideRequest](invoke.payload).handler)
        .toOption
        .filter(h =>
          h.nonEmpty && (descriptor.handler(MethodName(h)).isDefined ||
            descriptor.streamHandler(MethodName(h)).isDefined)
        )
        .getOrElse(invoke.method)

    val execution = Future {
      observability.invocation[EntityProtocol.Reply](component, handlerName, incoming)(
        Observability.outcomeOf
      ) {
        try run()
        catch
          case error: CommandError                  => EntityProtocol.Rejected(error)
          case scala.util.control.NonFatal(failure) =>
            // Whatever stopped the turn after its decision was recorded, it is over: the
            // approved tools are run at most once.
            try memory.call(SessionMemoryEntity.endTurn).invoke(): Unit
            catch case scala.util.control.NonFatal(_) => ()
            EntityProtocol.Rejected(
              CommandError(
                Option(failure.getMessage).getOrElse(failure.toString),
                ErrorCode.Internal
              )
            )
      }
    }(using AnkkaExecutors.virtual)

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
   * The session stays held until the stream finishes — `Finished` is only sent at the end — so a
   * conversation cannot be interleaved mid-stream.
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

    val observability = Observability(ctx.system)
    val incoming      = MetaEntry.toMetadata(request.metadata)
    val component     = descriptor.componentId.toString

    val execution = Future {
      // One call, however many tokens it answers with: counted when the stream ends, with how it
      // ended and how long it ran.
      observability.invocation[Either[CommandError, Option[Vector[ApprovalRequest]]]](
        component,
        request.method,
        incoming,
        streaming = true
      )(_.fold(Observability.outcomeOf, _ => SpanOutcome.Ok)) {
        val effect =
          try
            handle
              .asInstanceOf[StreamHandle[A, Any]]
              .decodeAndInvoke(agent, request.payload)
          finally agent._setContext(None)

        val ended = loop.runStreaming(
          effect,
          text => request.tokens ! EntityProtocol.Token(text),
          TurnOrigin(request.method, streaming = true, request.payload)
        )
        ended match
          case Right(None)           => request.tokens ! EntityProtocol.StreamCompleted
          case Right(Some(requests)) =>
            // The text the model wrote before its tool call has been sent; the stream ends
            // with what it waits for, never by hanging.
            request.tokens ! EntityProtocol.StreamAwaiting(
              writeToArray(Approvals.Awaiting(requests))
            )
          case Left(rejection) => request.tokens ! EntityProtocol.StreamFailed(rejection)
        ended
      }
    }(using AnkkaExecutors.virtual)

    ctx.pipeToSelf(execution) {
      case Success(_)       => StreamFinished
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

/**
 * A resolved, not-yet-issued call to an agent's handler, answered with `R`.
 *
 * `call` answers with the handler's value and throws `ApprovalAwaited` when the turn waits; `ask`
 * answers with an `AgentOutcome`, which says which of the two it was.
 */
final class AgentInvocation[I, R] private[agent] (
    send: (I, Metadata) => Future[R],
    timeout: FiniteDuration,
    metadata: Metadata = Metadata.empty
):
  def withMetadata(metadata: Metadata): AgentInvocation[I, R] =
    AgentInvocation(send, timeout, metadata)

  /** Issues the call and waits. Throws `CommandError` if the handler rejected it. */
  def invoke(input: I): R = ComponentClient.await(invokeAsync(input), timeout)

  def invokeAsync(input: I): Future[R] = send(input, metadata)

/** As `AgentInvocation`, for a handler that takes no argument. */
final class AgentNoArgInvocation[R] private[agent] (
    send: Metadata => Future[R],
    timeout: FiniteDuration,
    metadata: Metadata = Metadata.empty
):
  def withMetadata(metadata: Metadata): AgentNoArgInvocation[R] =
    AgentNoArgInvocation(send, timeout, metadata)

  def invoke(): R = ComponentClient.await(invokeAsync(), timeout)

  def invokeAsync(): Future[R] = send(metadata)

/** Calls to agents, addressed by session. */
final class AgentCalls private[agent] (
    transport: com.thinkmorestupidless.ankka.sdk.CallTransport,
    sessionId: SessionId
):
  private given ExecutionContext = ExecutionContext.parasitic
  private val entity             = EntityId(sessionId)

  /**
   * Tags the session with the data subject the conversation is about (feature 042), before the
   * call: every turn from then on is kept under the subject's key, and after an erasure of the
   * subject the session reads as nothing. Refused for a subject already erased, or a session about
   * another.
   */
  def withSubject(subject: String): AgentCalls =
    ComponentClient.await(
      transport
        .ask(
          SessionMemoryEntity.componentId,
          entity,
          MethodName("assign-subject"),
          SessionMemoryEntity.assignSubject.inputSerializer.toBytes(subject),
          Metadata.empty
        )
        .map(_ => ()),
      transport.askTimeout
    )
    this

  /** Calls a handler for its value; throws `ApprovalAwaited` when the turn waits for approval. */
  def call[A <: Agent, I, O](handle: CommandHandle[A, I, O]): AgentInvocation[I, O] =
    AgentInvocation(
      (input, md) => send(handle, handle.inputSerializer.toBytes(input), md).map(valueOf),
      transport.askTimeout
    )

  def call[A <: Agent, O](handle: NoArgHandle[A, O]): AgentNoArgInvocation[O] =
    AgentNoArgInvocation(md => sendNoArg(handle, md).map(valueOf), transport.askTimeout)

  /** Calls a handler for its outcome: the answer, or the approval requests the turn awaits. */
  def ask[A <: Agent, I, O](handle: CommandHandle[A, I, O]): AgentInvocation[I, AgentOutcome[O]] =
    AgentInvocation(
      (input, md) => send(handle, handle.inputSerializer.toBytes(input), md),
      transport.askTimeout
    )

  def ask[A <: Agent, O](handle: NoArgHandle[A, O]): AgentNoArgInvocation[AgentOutcome[O]] =
    AgentNoArgInvocation(md => sendNoArg(handle, md), transport.askTimeout)

  /**
   * Sends a decision on one of the session's approval requests.
   *
   * `handle` is the handler that was called — what gives the answer its type. When this was the
   * turn's last awaited decision the turn goes on, and this answers as the handler's own caller
   * would have been answered: with the model's answer, or with approval requests still or newly
   * awaiting a decision.
   */
  def decide[A <: Agent, I, O](handle: CommandHandle[A, I, O])(
      decision: Decision
  ): AgentOutcome[O] =
    ComponentClient.await(decideAsync(handle)(decision), transport.askTimeout)

  def decideAsync[A <: Agent, I, O](handle: CommandHandle[A, I, O])(
      decision: Decision
  ): Future[AgentOutcome[O]] =
    sendDecision(
      handle.componentId,
      handle.name.toString,
      decision,
      handle.outputSerializer.fromBytes
    )

  def decide[A <: Agent, O](handle: NoArgHandle[A, O])(decision: Decision): AgentOutcome[O] =
    ComponentClient.await(
      sendDecision(
        handle.componentId,
        handle.name.toString,
        decision,
        handle.outputSerializer.fromBytes
      ),
      transport.askTimeout
    )

  /** A turn that began as a stream is answered whole once decided: the text, or more requests. */
  def decide[A <: Agent, I](handle: StreamHandle[A, I])(decision: Decision): AgentOutcome[String] =
    ComponentClient.await(
      sendDecision(
        handle.componentId,
        handle.name.toString,
        decision,
        String(_, java.nio.charset.StandardCharsets.UTF_8)
      ),
      transport.askTimeout
    )

  /**
   * The platform's own decision — an expiry — which names no handler: it goes to whichever turn
   * holds the request, and nobody is waiting for what the model says next.
   */
  private[ankka] def decideAsPlatform(componentId: ComponentId, decision: Decision): Unit =
    ComponentClient.await(
      sendDecision(componentId, "", decision, _ => ()),
      transport.askTimeout
    ): Unit

  /** The session's approval requests that are awaiting a decision. */
  def approvals(): Vector[ApprovalRequest] =
    ComponentClient(transport)
      .forEventSourcedEntity(entity)
      .call(SessionMemoryEntity.history)
      .invoke()
      .awaiting

  /**
   * Streams a handler's reply.
   *
   * The `Source` is materialised by the caller and its actor ref sent to the agent, so tokens flow
   * directly from wherever the session is sharded to wherever this was called. Nothing buffers the
   * whole reply. When the turn waits for approval the source fails with `ApprovalAwaited` once the
   * text before it has arrived; `streamParts` delivers the requests as its last element instead.
   */
  def stream[A <: Agent, I](handle: StreamHandle[A, I])(input: I): Source[String, NotUsed] =
    tokens(handle, input).map {
      case AgentPart.Text(text)                 => text
      case AgentPart.AwaitingApproval(requests) => throw ApprovalAwaited(requests)
    }

  /** Streams a handler's reply as parts: text, then, if the turn waits, its approval requests. */
  def streamParts[A <: Agent, I](handle: StreamHandle[A, I])(input: I): Source[AgentPart, NotUsed] =
    tokens(handle, input)

  private def tokens[A <: Agent, I](
      handle: StreamHandle[A, I],
      input: I
  ): Source[AgentPart, NotUsed] =
    // The call is made when the source is run, which is often on another thread: an endpoint
    // hands the source back and the server runs it. It is still the call of whoever asked for the
    // stream, so who that is is taken here, where they asked.
    val asked = Trace.capture()
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
        Trace.resume(asked) {
          transport.tell(
            handle.componentId,
            entity,
            EntityProtocol.InvokeStream(
              handle.name,
              handle.inputSerializer.toBytes(input),
              Vector.empty,
              tokens
            )
          )
        }
        NotUsed
      }
      .collect {
        case EntityProtocol.Token(text) => AgentPart.Text(text)
        case EntityProtocol.StreamAwaiting(payload) =>
          AgentPart.AwaitingApproval(readFromArray[Approvals.Awaiting](payload).requests)
      }
      // The requests are a stream's last part: the host sends nothing after them.
      .takeWhile(!_.isInstanceOf[AgentPart.AwaitingApproval], inclusive = true)

  private def send[O](
      handle: CommandHandle[?, ?, O],
      payload: Array[Byte],
      metadata: Metadata
  ): Future[AgentOutcome[O]] =
    transport
      .askWithMetadata(handle.componentId, entity, handle.name, payload, metadata)
      .map(outcomeOf(handle.outputSerializer.fromBytes))

  private def sendNoArg[O](handle: NoArgHandle[?, O], metadata: Metadata): Future[AgentOutcome[O]] =
    transport
      .askWithMetadata(handle.componentId, entity, handle.name, Array.emptyByteArray, metadata)
      .map(outcomeOf(handle.outputSerializer.fromBytes))

  private def sendDecision[O](
      componentId: ComponentId,
      handler: String,
      decision: Decision,
      decode: Array[Byte] => O
  ): Future[AgentOutcome[O]] =
    transport
      .askWithMetadata(
        componentId,
        entity,
        MethodName(Approvals.DecideMethod),
        writeToArray(Approvals.DecideRequest(handler, decision)),
        Metadata.empty
      )
      .map(outcomeOf(decode))

  private def outcomeOf[O](decode: Array[Byte] => O)(
      reply: (Array[Byte], Metadata)
  ): AgentOutcome[O] =
    val (bytes, metadata) = reply
    if metadata.get(Approvals.OutcomeKey).contains(Approvals.OutcomeValue) then
      AgentOutcome.AwaitingApproval(readFromArray[Approvals.Awaiting](bytes).requests)
    else AgentOutcome.Answered(decode(bytes))

  private def valueOf[O](outcome: AgentOutcome[O]): O = outcome match
    case AgentOutcome.Answered(value)            => value
    case AgentOutcome.AwaitingApproval(requests) => throw ApprovalAwaited(requests)

/**
 * Adds `forAgent` to `ComponentClient`.
 *
 * An extension method rather than a member, because `ComponentClient` lives in `ankka-sdk` and
 * cannot see `Agent`. The call site reads the same either way.
 */
extension (client: ComponentClient)
  def forAgent(sessionId: SessionId): AgentCalls =
    AgentCalls(client.transportRef, sessionId)

  def forSessionMemory(sessionId: SessionId) =
    client.forEventSourcedEntity(EntityId(sessionId))
