package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Workflows whose steps do what a scenario's script says, and the view, keyed view and consumer
 * that read them, for `features/workflow-sources/`.
 *
 * A script is a word handed to `start` and kept in the state, so a step reads it from the state it
 * is run with, and two scenarios never share a workflow. What each script makes a step do:
 *
 *   - `end`: every step records a state and moves on; the last records one and ends.
 *   - `pause`: the first step records a state and pauses, with no timeout.
 *   - `hold`: the first step records a state and moves on; the next pauses and records nothing.
 *   - `compensate`: the second step fails; its failover records the failure and fails the workflow.
 *   - `bare`: the second step fails; its failover fails the workflow and records nothing.
 *   - `slow`: the second step takes longer than the whole workflow may.
 */
final case class FlowState(id: String, script: String, note: String, reason: Option[String])

object FlowState:
  val serializer: Serializer[FlowState] = Codecs.serializer[FlowState]("flow-state")

/** The steps a scripted workflow's companion names, so one body serves both workflows. */
trait FlowSteps:
  def first: NoInputStepHandle[?]
  def second: NoInputStepHandle[?]
  def failover: NoInputStepHandle[?]

abstract class ScriptedFlow(context: WorkflowContext, steps: => FlowSteps)
    extends Workflow[FlowState]:

  def emptyState: FlowState = FlowState(context.workflowId.toString, "", "", None)

  def start(script: String): Effect[String] =
    effects
      .updateState(currentState.copy(script = script, note = "started"))
      .transitionTo(steps.first.ref)
      .thenReply("started")

  def remove: Effect[String] = effects.delete().thenReply("deleted")

  def firstStep: StepEffect = currentState.script match
    case "pause" => stepEffects.updateState(currentState.copy(note = firstName)).thenPause()
    case _ =>
      stepEffects
        .updateState(currentState.copy(note = firstName))
        .thenTransitionTo(steps.second.ref)

  def secondStep: StepEffect = currentState.script match
    case "compensate" | "bare" => throw RuntimeException("declined")
    case "hold"                => stepEffects.thenPause()
    case "slow" =>
      Thread.sleep(6000)
      stepEffects.updateState(currentState.copy(note = secondName)).thenEnd
    case _ => stepEffects.updateState(currentState.copy(note = secondName)).thenEnd

  def failoverStep: StepEffect = currentState.script match
    case "bare" => stepEffects.thenFail("declined")
    case _ =>
      stepEffects
        .updateState(currentState.copy(note = "refunded", reason = Some("declined")))
        .thenFail("declined")

  protected def firstName: String
  protected def secondName: String

/** `checkout`: `reserve`, then `charge`, failing over to `refund`. */
final class CheckoutFlow(context: WorkflowContext) extends ScriptedFlow(context, CheckoutFlow):
  protected def firstName  = "reserve"
  protected def secondName = "charge"
  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(15.seconds)
      .stepRecovery(
        CheckoutFlow.charge,
        RecoverStrategy.maxRetries(1).failoverTo(CheckoutFlow.refund)
      )
      .build

object CheckoutFlow
    extends Workflow.Companion[CheckoutFlow, FlowState](
      ComponentId("checkout"),
      FlowState.serializer
    )
    with FlowSteps:
  def create(context: WorkflowContext) = new CheckoutFlow(context)
  val reserve                          = step("reserve")(_.firstStep)
  val charge                           = step("charge")(_.secondStep)
  val refund                           = step("refund")(_.failoverStep)
  val start                            = command("start")(_.start)
  val remove                           = command("remove")(_.remove)
  def first                            = reserve
  def second                           = charge
  def failover                         = refund

/** `transfer`: `withdraw`, then `deposit`, failing over to `refund`, within two seconds in all. */
final class TransferFlow(context: WorkflowContext) extends ScriptedFlow(context, TransferFlow):
  protected def firstName  = "withdraw"
  protected def secondName = "deposit"
  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .timeout(2.seconds)
      .defaultStepTimeout(15.seconds)
      .stepRecovery(
        TransferFlow.deposit,
        RecoverStrategy.maxRetries(1).failoverTo(TransferFlow.refund)
      )
      .build

object TransferFlow
    extends Workflow.Companion[TransferFlow, FlowState](
      ComponentId("transfer"),
      FlowState.serializer
    )
    with FlowSteps:
  def create(context: WorkflowContext) = new TransferFlow(context)
  val withdraw                         = step("withdraw")(_.firstStep)
  val deposit                          = step("deposit")(_.secondStep)
  val refund                           = step("refund")(_.failoverStep)
  val start                            = command("start")(_.start)
  val remove                           = command("remove")(_.remove)
  def first                            = withdraw
  def second                           = deposit
  def failover                         = refund

// ── What the readers saw ──────────────────────────────────────────────────────

/** Everything a reader of a workflow was handed, in the order it was handed it. */
object FlowLog:
  final case class Seen(
      reader: String,
      subject: String,
      sequence: Long,
      note: Option[String],
      standing: Option[WorkflowLifecycle]
  )

  private val seen = ConcurrentLinkedQueue[Seen]()

  def add(entry: Seen): Unit = seen.add(entry): Unit
  def of(reader: String, subject: String): Vector[Seen] =
    seen.asScala.toVector.filter(s => s.reader == reader && s.subject == subject)

  /** A keyed view's begin and end of each change, to show it handled one at a time. */
  private val spans            = ConcurrentLinkedQueue[String]()
  def span(line: String): Unit = spans.add(line): Unit
  def spansOf(prefix: String): Vector[String] =
    spans.asScala.toVector.filter(_.contains(prefix))

/** The version `checkouts` is declared at by the start that reads it. */
object FlowVersions:
  @volatile var checkouts: Int = 1

// ── The plain view ────────────────────────────────────────────────────────────

/**
 * A checkout's row: its state, where it stood, and how often and at which version it was written.
 */
final case class CheckoutRow(
    id: String,
    note: String,
    reason: Option[String],
    standing: String,
    step: Option[String],
    failure: Option[String],
    writes: Int,
    version: Int
)

object CheckoutRow:
  val serializer: Serializer[CheckoutRow] = Codecs.serializer[CheckoutRow]("checkout-row")

final class CheckoutsView(version: Int) extends View[FlowState, CheckoutRow]:
  def onChange(state: FlowState): Effect =
    val standing = updateContext.standing.getOrElse(
      throw IllegalStateException("a change from a workflow carries its standing")
    )
    FlowLog.add(
      FlowLog.Seen(
        "checkouts",
        updateContext.subject,
        updateContext.sequenceNumber,
        Some(state.note),
        Some(standing)
      )
    )
    effects.updateRow(
      CheckoutRow(
        updateContext.subject,
        state.note,
        state.reason,
        standing.status.toLowerCase,
        standing.pendingStep,
        standing.failure,
        rowState.fold(0)(_.writes) + 1,
        version
      )
    )

  override def onDelete: Effect =
    FlowLog.add(
      FlowLog.Seen("checkouts", updateContext.subject, updateContext.sequenceNumber, None, None)
    )
    effects.deleteRow()

object Checkouts
    extends View.Companion[CheckoutsView, FlowState, CheckoutRow](
      ComponentId("checkouts"),
      ChangeSource.stateOf(CheckoutFlow),
      CheckoutRow.serializer
    ):
  def create(ctx: ViewComponentContext) = new CheckoutsView(FlowVersions.checkouts)
  override def version: Option[Int]     = Some(FlowVersions.checkouts)
  val byStanding =
    query("by-standing")(
      s"SELECT payload FROM $table WHERE payload::jsonb->>'standing' = :standing"
    )

// ── The keyed view over an entity and the workflow ────────────────────────────

/** An entity whose one command records an event. */
final case class OrderPlaced(n: Int)

final class OrderEntity extends EventSourcedEntity[Int, OrderPlaced]:
  def emptyState: Int                     = 0
  def applyEvent(event: OrderPlaced): Int = event.n
  def record(n: Int): Effect[Done]        = effects.persist(OrderPlaced(n)).thenReply(_ => Done)

object OrderEntity
    extends EventSourcedEntity.Companion[OrderEntity, Int, OrderPlaced](
      componentId = ComponentId("order"),
      stateSerializer = Codecs.serializer[Int]("order"),
      eventSerializer = Codecs.serializer[OrderPlaced]("order-placed")
    ):
  def create(context: EventSourcedEntityContext) = new OrderEntity
  val record                                     = command("record")(_.record)

final case class FulfilmentRow(key: String, from: String)

final class FulfilmentView extends KeyedView[FulfilmentRow]:
  private def handled(from: String, change: Change): Effect =
    FlowLog.span(s"begin ${change.subject}")
    try
      // Long enough that two changes handled at once would overlap.
      Thread.sleep(300)
      effects.updateRow(change.subject, FulfilmentRow(change.subject, from))
    finally FlowLog.span(s"end ${change.subject}")

  def onOrder(@scala.annotation.unused event: OrderPlaced, change: Change): Effect =
    handled("order", change)
  def onCheckout(@scala.annotation.unused state: FlowState, change: Change): Effect =
    handled("checkout", change)

object Fulfilment
    extends KeyedView.Companion[FulfilmentView, FulfilmentRow](
      ComponentId("fulfilment"),
      Codecs.serializer[FulfilmentRow]("fulfilment-row")
    ):
  val orders                            = source(ChangeSource.eventsOf(OrderEntity))(_.onOrder)
  val checkouts                         = source(ChangeSource.stateOf(CheckoutFlow))(_.onCheckout)
  def create(ctx: ViewComponentContext) = new FulfilmentView

/** A keyed view that reads a topic beside the workflow, which the service refuses to start. */
def ordersOfTopicAndCheckout: KeyedViewDescriptor[FulfilmentView, FulfilmentRow] =
  new KeyedView.Companion[FulfilmentView, FulfilmentRow](
    ComponentId("orders"),
    Codecs.serializer[FulfilmentRow]("fulfilment-row")
  ):
    @annotation.nowarn("msg=unused")
    val orders =
      source(ChangeSource.fromTopic("orders", Codecs.serializer[OrderPlaced]("order-placed")))(
        _.onOrder
      )
    @annotation.nowarn("msg=unused")
    val checkouts                         = source(ChangeSource.stateOf(CheckoutFlow))(_.onCheckout)
    def create(ctx: ViewComponentContext) = new FulfilmentView
  .descriptor

// ── The consumer over `transfer` ──────────────────────────────────────────────

/** The ledger a failed transfer is reported to: how many reports it has had. */
final case class Reported(why: String)

final class LedgerEntity extends EventSourcedEntity[Int, Reported]:
  def emptyState: Int                   = 0
  def applyEvent(event: Reported): Int  = currentState + 1
  def report(why: String): Effect[Done] = effects.persist(Reported(why)).thenReply(_ => Done)
  def reports: ReadOnlyEffect[Int]      = effects.reply(currentState)

object LedgerEntity
    extends EventSourcedEntity.Companion[LedgerEntity, Int, Reported](
      componentId = ComponentId("ledger"),
      stateSerializer = Codecs.serializer[Int]("ledger"),
      eventSerializer = Codecs.serializer[Reported]("ledger-reported")
    ):
  def create(context: EventSourcedEntityContext) = new LedgerEntity
  val report                                     = command("report")(_.report)
  val reports                                    = query("reports")(_.reports)

/** What `settlement` publishes for a transfer that completed. */
final case class Settled(id: String)

/**
 * `settlement`: notes every change it is handed, publishes to `transfers-settled` on a completed
 * standing, and reports to the ledger on a failed one.
 */
final class SettlementConsumer(client: ComponentClient) extends Consumer[FlowState, Settled]:
  def onMessage(state: FlowState): Effect =
    val standing = messageContext.standing
    FlowLog.add(
      FlowLog.Seen(
        "settlement",
        messageContext.subject,
        messageContext.sequenceNumber,
        Some(state.note),
        standing
      )
    )
    standing.map(_.status) match
      case Some("Completed") => effects.produce(Settled(messageContext.subject))
      case Some("Failed") =>
        client
          .forEventSourcedEntity(EntityId(messageContext.subject))
          .call(LedgerEntity.report)
          .invoke(standing.flatMap(_.failure).getOrElse("")): Unit
        effects.done()
      case _ => effects.ignore()

  override def onDelete: Effect =
    FlowLog.add(
      FlowLog.Seen("settlement", messageContext.subject, messageContext.sequenceNumber, None, None)
    )
    effects.ignore()

object Settlement
    extends Consumer.Companion[SettlementConsumer, FlowState, Settled](
      ComponentId("settlement"),
      ChangeSource.stateOf(TransferFlow)
    ):
  def create(ctx: ConsumerContext) = new SettlementConsumer(ctx.componentClient)
  override def outputSerializer: Option[Serializer[Settled]] =
    Some(Codecs.serializer[Settled]("settled"))
  override def produceTo: Option[String] = Some("transfers-settled")

/** Everything `features/workflow-sources/` registers. */
def workflowSourceComponents: Seq[ComponentDescriptor] = Seq(
  CheckoutFlow.descriptor,
  TransferFlow.descriptor,
  OrderEntity.descriptor,
  LedgerEntity.descriptor,
  Checkouts.descriptor,
  Fulfilment.descriptor,
  Settlement.descriptor
)
