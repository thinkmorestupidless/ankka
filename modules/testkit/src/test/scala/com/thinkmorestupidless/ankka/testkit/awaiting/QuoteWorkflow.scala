package com.thinkmorestupidless.ankka.testkit.awaiting

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.*

final case class QuoteRequest(amount: Int)

/**
 * What a quote recorded: the steps that ran, in order, and the instance each ran on (the identity
 * of the component client it was given, which is one per instance), and the quote itself.
 */
final case class Quote(
    id: String,
    amount: Int,
    steps: Vector[String],
    instances: Vector[Int],
    total: Option[Int]
)

/**
 * How one workflow behaves, by its id: how long each step takes, which step fails and how many
 * times, and whether it pauses after `rates`. Set by a test before it starts the workflow.
 */
final case class StepScript(
    durations: Map[String, FiniteDuration] = Map.empty,
    failing: Option[String] = None,
    failures: Int = Int.MaxValue,
    pauseAfterRates: Boolean = false
)

object StepScript:
  private val scripts  = ConcurrentHashMap[String, StepScript]()
  private val failures = ConcurrentHashMap[String, Integer]()
  private val finished = ConcurrentHashMap[String, java.lang.Long]()

  def set(id: String, script: StepScript): Unit = scripts.put(id, script): Unit
  def of(id: String): StepScript                = Option(scripts.get(id)).getOrElse(StepScript())

  /** Runs a step's scripted part: its time, and its failure when it is the failing one. */
  def perform(id: String, step: String): Unit =
    val script = of(id)
    script.durations.get(step).foreach(d => Thread.sleep(d.toMillis))
    if script.failing.contains(step) then
      val so = failures.merge(s"$id/$step", 1, (a, b) => a + b)
      if so <= script.failures then throw RuntimeException(s"no $step for $id")

  /** When a step last finished, in `System.nanoTime()`: what "answered after the step" reads. */
  def finishedAt(id: String, step: String): Option[Long] =
    Option(finished.get(s"$id/$step")).map(_.longValue)

  private[awaiting] def finish(id: String, step: String): Unit =
    finished.put(s"$id/$step", System.nanoTime()): Unit

/**
 * A quote, worked out over three steps: `rates`, `margin`, `offer`. The fixture every scenario of
 * `features/awaiting-workflows` waits for; `StepScript` decides each instance's timing and
 * failures, so one companion covers every ending.
 */
final class QuoteWorkflow(context: WorkflowContext) extends Workflow[Quote]:

  private val id = context.workflowId.toString

  def emptyState: Quote = Quote(id, 0, Vector.empty, Vector.empty, None)

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(60.seconds)
      // "fails after its retries": one retry, then the workflow fails at the step.
      .stepRecovery(QuoteWorkflow.margin, RecoverStrategy.maxRetries(1))
      .stepRecovery(QuoteWorkflow.offer, RecoverStrategy.maxRetries(1))
      .build

  def start(request: QuoteRequest): Effect[Done] =
    if request.amount <= 0 then effects.error("a quote needs an amount greater than zero")
    else if currentState.steps.nonEmpty then
      effects.error("quote already started", ErrorCode.Conflict)
    else
      effects
        .updateState(currentState.copy(amount = request.amount))
        .transitionTo(QuoteWorkflow.rates)
        .thenReply(Done)

  /** Goes on after a pause. */
  def resume: Effect[Done] = effects.transitionTo(QuoteWorkflow.margin).thenReply(Done)

  def cancel: Effect[Done] = effects.delete().thenReply(Done)

  def quote: ReadOnlyEffect[Quote] = effects.reply(currentState)

  private def ran(step: String): Quote =
    StepScript.perform(id, step)
    StepScript.finish(id, step)
    currentState.copy(
      steps = currentState.steps :+ step,
      instances = currentState.instances :+ System.identityHashCode(context.componentClient)
    )

  def ratesStep: StepEffect =
    val next = ran("rates")
    if StepScript.of(id).pauseAfterRates then stepEffects.updateState(next).thenPause()
    else stepEffects.updateState(next).thenTransitionTo(QuoteWorkflow.margin)

  def marginStep: StepEffect =
    stepEffects.updateState(ran("margin")).thenTransitionTo(QuoteWorkflow.offer)

  def offerStep: StepEffect =
    val next = ran("offer")
    stepEffects.updateState(next.copy(total = Some(next.amount + next.amount / 10))).thenEnd

object QuoteWorkflow
    extends Workflow.Companion[QuoteWorkflow, Quote](
      ComponentId("quote"),
      Codecs.serializer[Quote]("quote")
    ):
  given Serializer[QuoteRequest] = Codecs.serializer[QuoteRequest]("quote-request")

  def create(context: WorkflowContext) = new QuoteWorkflow(context)

  val rates  = step("rates")(_.ratesStep)
  val margin = step("margin")(_.marginStep)
  val offer  = step("offer")(_.offerStep)

  val start  = command("start")(_.start)
  val resume = command("resume")(_.resume)
  val cancel = command("cancel")(_.cancel)
  val quote  = query("quote")(_.quote)
