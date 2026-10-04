package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.{Agent, AgentContext, FunctionTool}
import com.thinkmorestupidless.ankka.agent.autonomous.{
  AutonomousAgent,
  AutonomousAgentContext,
  AutonomousAgentDefinition,
  Task,
  TaskAcceptance,
  TaskType
}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.graph.GraphConsumer

/** What a payout workflow knows: what the PSP gateway answered, or how the call failed. */
final case class PayoutState(answer: String, failure: String)

// docs:start step-calls-service
/**
 * Asks the PSP gateway service to start a payout, from a step: a step may call another service, as
 * this service, and goes on with the answer.
 */
final class PayoutWorkflow(context: WorkflowContext) extends Workflow[PayoutState]:

  def emptyState: PayoutState = PayoutState("", "")

  def start(amount: Int): Effect[Done] =
    effects
      .updateState(emptyState)
      .transitionTo(PayoutWorkflow.initiatePayout.withInput(amount))
      .thenReply(Done)

  def initiatePayout(amount: Int): StepEffect =
    val answer = context.services("psp-gateway").getText(s"/payouts?amount=$amount")
    stepEffects.updateState(currentState.copy(answer = answer)).thenEnd
  // docs:end step-calls-service

  /** The same call, but every failure is kept in the state rather than failing the step. */
  def tryPayout(amount: Int): StepEffect =
    val failure =
      try
        context.services("psp-gateway").getText(s"/payouts?amount=$amount"): Unit
        ""
      catch
        case e: (ServiceUnresolvable | ServiceUnanswered | ServiceCallFailed) =>
          e.getClass.getSimpleName + ": " + e.getMessage
    stepEffects.updateState(currentState.copy(failure = failure)).thenEnd

  def startTrying(amount: Int): Effect[Done] =
    effects
      .updateState(emptyState)
      .transitionTo(PayoutWorkflow.tryPayoutStep.withInput(amount))
      .thenReply(Done)

  /** A command that calls another service, which a workflow may not do outside a step. */
  def callFromCommand(amount: Int): Effect[Done] =
    context.services("psp-gateway").getText(s"/payouts?amount=$amount"): Unit
    effects.reply(Done)

  def status: ReadOnlyEffect[PayoutState] = effects.reply(currentState)

object PayoutWorkflow
    extends Workflow.Companion[PayoutWorkflow, PayoutState](
      componentId = ComponentId("payout"),
      stateSerializer = Codecs.serializer[PayoutState]("payout-state")
    ):

  def create(context: WorkflowContext) = new PayoutWorkflow(context)

  val initiatePayout = step("initiate-payout")(_.initiatePayout)
  val tryPayoutStep  = step("try-payout")(_.tryPayout)

  val start           = command("start")(_.start)
  val startTrying     = command("start-trying")(_.startTrying)
  val callFromCommand = command("call-from-command")(_.callFromCommand)
  val status          = query("status")(_.status)

/** Tells the PSP gateway service of every amount added to a ledger, before it is done with it. */
final class LedgerForwarder(context: ConsumerContext) extends Consumer[LedgerEvent, Nothing]:
  def onMessage(event: LedgerEvent): Effect =
    event match
      case LedgerEvent.Added(amount) =>
        context.services("psp-gateway").getText(s"/ledgers/${messageContext.subject}/$amount"): Unit
      case LedgerEvent.Closed => ()
    effects.ignore()

object LedgerForwarder
    extends Consumer.Companion[LedgerForwarder, LedgerEvent, Nothing](
      componentId = ComponentId("ledger-forwarder"),
      source = ChangeSource.eventsOf(LedgerEntity)
    ):
  def create(context: ConsumerContext) = new LedgerForwarder(context)

/** Polls the PSP gateway service when its timer fires. */
final class SettlementPoller(context: TimedActionContext) extends TimedAction:
  def poll(batch: String): Effect =
    context.services("psp-gateway").getText(s"/settlements/$batch"): Unit
    effects.done()

object SettlementPoller extends TimedAction.Companion[SettlementPoller](ComponentId("settlement")):
  def create(context: TimedActionContext) = new SettlementPoller(context)
  val poll                                = handler("poll")(_.poll)

/** An agent with a tool that reads a payout's status from the PSP gateway service. */
final class PayoutAgent(context: AgentContext) extends Agent:

  private val payoutStatus = FunctionTool
    .named("payout_status")
    .describedAs("The status of a payout, from the PSP gateway.")
    .param[String]("payout", "The payout's id.")
    .handle((payout: String) => context.services("psp-gateway").getText(s"/payouts/$payout"))

  def ask(question: String): Effect[String] =
    effects
      .systemMessage("Answer from the PSP gateway.")
      .userMessage(question)
      .tools(payoutStatus)
      .thenReply()

object PayoutAgent extends Agent.Companion[PayoutAgent](ComponentId("payout-agent")):
  def create(context: AgentContext) = new PayoutAgent(context)
  val ask                           = command("ask")(_.ask)

/** An autonomous agent whose tool reads a payout's status from the PSP gateway service. */
final class PayoutReporter(context: AutonomousAgentContext) extends AutonomousAgent(context):
  override def tools: Seq[FunctionTool] = Seq(
    FunctionTool
      .named("payout_status")
      .describedAs("The status of a payout, from the PSP gateway.")
      .param[String]("payout", "The payout's id.")
      .handle((payout: String) => context.services("psp-gateway").getText(s"/payouts/$payout"))
  )

object PayoutReporter
    extends AutonomousAgent.Companion[PayoutReporter](ComponentId("payout-reporter")):
  val report: TaskType[String] = Task.named("report").describedAs("Report on a payout")

  def create(context: AutonomousAgentContext) = new PayoutReporter(context)

  def definition: AutonomousAgentDefinition =
    define
      .describedAs("Reports on payouts")
      .instructions("Be brief.")
      .capability(TaskAcceptance.of(report).maxIterationsPerTask(3))

/** A graph consumer that asks the PSP gateway service about each amount before it publishes it. */
final class LedgerCheckGraph(context: ConsumerContext) extends GraphConsumer[LedgerEvent]:
  def onMessage(event: LedgerEvent): Effect = event match
    case LedgerEvent.Added(amount) =>
      val checked = context.services("psp-gateway").getText(s"/checks/$amount")
      effects.publish(graph.node(s"check:$amount", Seq("Check"), Map("checked" -> checked)))
    case LedgerEvent.Closed => effects.ignore()

object LedgerCheckGraph
    extends GraphConsumer.Companion[LedgerCheckGraph, LedgerEvent](
      componentId = ComponentId("ledger-check-graph"),
      source = ChangeSource.eventsOf(LedgerEntity),
      topic = "ledger-checks"
    ):
  def create(context: ConsumerContext) = new LedgerCheckGraph(context)
