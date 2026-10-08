package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.agent.{Agent, AgentContext, AgentDescriptor}
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.given

/**
 * One turn of a worker: everything the ask agent needs to build the effect from the payload alone,
 * so that a turn suspended on an approval is rebuilt exactly when the decision comes.
 */
final case class WorkerTurn(worker: Worker, message: String, result: Shape, run: RunRef)

object WorkerTurn:
  given Serializer[WorkerTurn] = Codecs.serializer[WorkerTurn]("blueprint-worker-turn")

/**
 * The one request agent every worker's ask turn goes to. Its handler chooses the model, the
 * instructions, the tools and the guardrails from the turn's worker, by name from the registry, and
 * bounds the turn by the worker's budget; its own companion fixes nothing a worker varies.
 */
final class AskAgent(context: AgentContext, registry: BlueprintRegistry) extends Agent:

  def turn(turn: WorkerTurn): Effect[String] =
    val worker = turn.worker
    val tools  = worker.tools.flatMap(registry.tool).map(RunContext.wrap(_, turn.run))
    val guards = worker.guardrails.flatMap(registry.guardrail)
    val chosen = registry.modelsByName.get(worker.model).orElse(context.defaultModel)
    val base = chosen.fold(effects.systemMessage(AskAgent.systemMessage(worker, turn.result)))(m =>
      effects.model(m).systemMessage(AskAgent.systemMessage(worker, turn.result))
    )
    base
      .userMessage(turn.message)
      .tools(tools*)
      .guardrails(guards*)
      .maxToolCallSteps(worker.budget)
      .thenReply()

object AskAgent extends Agent.Companion[AskAgent](ComponentId("ankka-blueprint-ask")):

  /** The instructions, and the shape the answer must have, as JSON and nothing else. */
  def systemMessage(worker: Worker, result: Shape): String =
    s"""${worker.instructions}
       |
       |Answer with JSON only, no prose around it, with this shape:
       |${result.schema.render}""".stripMargin

  /** Before the service starts there is no registry; the runtime hosts the agent with its own. */
  def create(context: AgentContext) = new AskAgent(context, BlueprintRegistry.empty)

  val turn = command("turn")(_.turn)

  /** The platform's own, with the service's registry. */
  private[ankka] def hosted(registry: BlueprintRegistry): AgentDescriptor[AskAgent] =
    descriptor.copy(create = context => new AskAgent(context, registry), platform = true)

  val platformDescriptor: AgentDescriptor[AskAgent] = descriptor.copy(platform = true)
