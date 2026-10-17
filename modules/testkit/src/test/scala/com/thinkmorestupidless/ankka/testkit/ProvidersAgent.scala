package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given

/**
 * An agent whose handlers are answered by different model providers in one service: `triage` names
 * a second provider, `drafting` names none and is answered by the runtime's default.
 */
final class ProvidersAgent extends Agent:

  def triage(question: String): Effect[String] =
    effects
      .model(ProvidersAgent.second)
      .systemMessage("Triage the request.")
      .userMessage(question)
      .tools(ProvidersAgent.lookup, ProvidersAgent.refund)
      .thenReply()

  def drafting(question: String): Effect[String] =
    effects
      .systemMessage("Draft a reply.")
      .userMessage(question)
      .tools(ProvidersAgent.lookup)
      .thenReply()

object ProvidersAgent extends Agent.Companion[ProvidersAgent](ComponentId("providers-agent")):

  /** The provider `triage` names; a test puts another here and restores it. */
  @volatile var second: ModelProvider = TestModelProvider(name = "openai")

  val lookups: java.util.concurrent.ConcurrentLinkedQueue[String] =
    java.util.concurrent.ConcurrentLinkedQueue[String]()

  val lookup: FunctionTool = FunctionTool
    .named("lookup")
    .describedAs("Looks up the weather in a city.")
    .param[String]("city", "The city.")
    .handle { city =>
      lookups.add(city): Unit
      s"$city: sunny"
    }

  val refund: FunctionTool = FunctionTool
    .named("issue_refund")
    .describedAs("Refunds an order; a person approves it first.")
    .param[String]("order", "The order.")
    .handle(order => s"refunded $order")
    .requiresApproval

  def create(context: AgentContext) = new ProvidersAgent

  val triage   = command("triage")(_.triage)
  val drafting = command("drafting")(_.drafting)
