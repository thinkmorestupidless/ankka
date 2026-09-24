package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.{AgentDetail, Component}
import com.thinkmorestupidless.ankka.agent.{
  Agent,
  AgentDescriptor,
  AgentEffect,
  FunctionTool,
  Guardrail,
  Json,
  MemoryProvider,
  StreamHandle,
  ToolSpec
}
import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Metadata, MethodName, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.Trace
import com.thinkmorestupidless.ankka.runtime.remote.{
  Conversation,
  GuardrailStage,
  Payload,
  PlanRequest,
  ProcessFailure
}
import com.thinkmorestupidless.ankka.sdk.{CommandHandle, HandlerBinding}

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * An agent whose handlers, tools and guardrails live in the process, hosted by the sidecar's own
 * loop: the process is asked to *plan* — to say which model, what instructions, which of its tools
 * — and to run a tool or a guardrail when the loop needs one. The model call, the tool dispatch,
 * the session memory, compaction and the token accounting are the sidecar's, so the process never
 * holds a model key and never sees a model's API.
 *
 * Every handler is one `HandlerBinding` whose body is a `Plan` round-trip; the effect it returns is
 * an ordinary `AgentEffect` that the unchanged `AgentLoop` interprets. A tool is a `FunctionTool`
 * whose invoker is an `InvokeTool` round-trip, a guardrail a `Guardrail` whose checks are
 * `CheckGuardrail` round-trips — all on the loop's virtual thread, so blocking is free.
 */
final class RemoteAgent(
    spec: RemoteAgent.Spec,
    conversation: Conversation,
    models: Models,
    planTimeout: FiniteDuration,
    toolTimeout: FiniteDuration
) extends Agent:

  private[sidecar] def plan(name: MethodName, bytes: Array[Byte]): AgentEffect[String] =
    val metadata = Trace.currentTrace
      .map((traceId, spanId) => Trace.into(Metadata.empty, traceId, spanId))
      .getOrElse(Metadata.empty)
    val request =
      PlanRequest(spec.componentId, sessionId, name, Payload(Payload.Json, "", bytes), metadata)
    Await.result(conversation.plan(request), planTimeout) match
      case Left(ProcessFailure(_, error)) =>
        // A fault in the process is a fault here: the host answers Internal, not a refusal.
        throw error
      case Right(plan) =>
        plan.failure match
          case Some(refusal) => effects.error(refusal.message, refusal.code)
          case None =>
            val provider = plan.model match
              case Some(named) => models.named(named)
              case None        => models.default
            (provider, plan.model) match
              case (None, Some(named)) =>
                effects.error(
                  s"the plan names model '$named', which the sidecar has not configured",
                  ErrorCode.Unavailable
                )
              case (None, None) =>
                effects.error(
                  "the sidecar has no model: set ANTHROPIC_API_KEY, or ANKKA_MODEL_SCRIPT for a test",
                  ErrorCode.Unavailable
                )
              case (Some(model), _) =>
                val unknownTools  = plan.tools.filterNot(spec.tools.contains)
                val unknownGuards = plan.guardrails.filterNot(spec.guardrails.contains)
                if unknownTools.nonEmpty || unknownGuards.nonEmpty then
                  effects.error(
                    s"the plan names undeclared tool(s) ${unknownTools.mkString(", ")} " +
                      s"guardrail(s) ${unknownGuards.mkString(", ")}",
                    ErrorCode.Internal
                  )
                else
                  var effect = effects.model(model)
                  plan.system.foreach(text => effect = effect.systemMessage(text))
                  plan.user.foreach(text => effect = effect.userMessage(text))
                  plan.context.foreach(text => effect = effect.withContext(text))
                  effect = effect.memory(
                    if plan.sessionMemory then MemoryProvider.limitedWindow else MemoryProvider.none
                  )
                  effect = effect.tools(plan.tools.map(tool)*)
                  effect = effect.guardrails(plan.guardrails.map(guardrail)*)
                  // A JSON shape is the model's JSON passed through as text: the process decodes
                  // the reply with its own codec, which is where a mismatch is best reported.
                  effect.thenReply()

  // The loop runs tools and guardrails after the handler has returned and the session context is
  // gone, so both capture the session here rather than reading it when called.
  private def tool(name: String): FunctionTool =
    val declared  = spec.tools(name)
    val session   = sessionId
    val component = spec.componentId
    FunctionTool.raw(ToolSpec(name, declared.description, declared.inputSchema)) { arguments =>
      Await.result(conversation.invokeTool(component, session, name, arguments.render), toolTimeout)
    }

  private def guardrail(guardName: String): Guardrail =
    val session   = sessionId
    val component = spec.componentId
    new Guardrail:
      val name: String = guardName
      override def checkInput(text: String): Either[String, Unit] =
        Await.result(
          conversation.checkGuardrail(component, session, guardName, GuardrailStage.Input, text),
          planTimeout
        )
      override def checkOutput(text: String): Either[String, Unit] =
        Await.result(
          conversation.checkGuardrail(component, session, guardName, GuardrailStage.Output, text),
          planTimeout
        )

object RemoteAgent:

  final case class Tool(description: String, inputSchema: Json)

  /** What discovery said about one agent. */
  final case class Spec(
      componentId: ComponentId,
      role: String,
      maxToolCallSteps: Int,
      tools: Map[String, Tool],
      guardrails: Set[String],
      handlers: Vector[MethodName],
      streams: Vector[MethodName]
  )

  def spec(component: Component): Either[String, Spec] =
    ComponentId.parse(component.id).map { id =>
      val detail = component.detail.agent.getOrElse(AgentDetail())
      Spec(
        id,
        if detail.role.isEmpty then id else detail.role,
        if detail.maxToolCallSteps > 0 then detail.maxToolCallSteps else 100,
        detail.tools.map { t =>
          t.name -> Tool(t.description, Json.parse(t.inputSchemaJson).getOrElse(Json.obj()))
        }.toMap,
        detail.guardrails.toSet,
        component.handlers.filterNot(_.streaming).map(h => MethodName(h.name)).toVector,
        component.handlers.filter(_.streaming).map(h => MethodName(h.name)).toVector
      )
    }

  /** The descriptor the `AgentRuntime` hosts, every handler a plan round-trip. */
  def descriptor(
      spec: Spec,
      conversation: Conversation,
      models: Models,
      planTimeout: FiniteDuration,
      toolTimeout: FiniteDuration = 2.minutes
  ): AgentDescriptor[RemoteAgent] =
    val handlers: Map[MethodName, HandlerBinding[RemoteAgent]] =
      spec.handlers.map { name =>
        name -> new CommandHandle[RemoteAgent, Array[Byte], String](
          spec.componentId,
          name,
          readOnly = false,
          Serializer.bytes,
          summon[Serializer[String]],
          (agent, bytes) => agent.plan(name, bytes)
        )
      }.toMap
    val streams: Map[MethodName, StreamHandle[RemoteAgent, ?]] =
      spec.streams.map { name =>
        name -> StreamHandle.raw[RemoteAgent](
          spec.componentId,
          name,
          (agent, bytes) => agent.plan(name, bytes).thenStream()
        )
      }.toMap
    AgentDescriptor[RemoteAgent](
      spec.componentId,
      spec.role,
      spec.maxToolCallSteps,
      _ => new RemoteAgent(spec, conversation, models, planTimeout, toolTimeout),
      handlers,
      streams
    )
