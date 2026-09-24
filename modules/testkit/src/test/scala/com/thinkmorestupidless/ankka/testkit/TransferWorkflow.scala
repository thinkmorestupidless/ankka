package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.DurationInt

final case class Transfer(from: String, to: String, amount: Int)

final case class TransferState(transfer: Transfer, status: String)

/**
 * Move money between two wallets, with compensation if the deposit fails.
 *
 * The interesting property is not the happy path — it is that a crash after the withdrawal but
 * before the deposit does not lose the money. The withdrawal is journalled as a completed step, so
 * recovery resumes at the deposit rather than starting over or giving up.
 */
final class TransferWorkflow(context: WorkflowContext) extends Workflow[TransferState]:

  private val client = context.componentClient

  def emptyState: TransferState = TransferState(Transfer("", "", 0), "not-started")

  // docs:start settings
  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .timeout(60.seconds)
      .defaultStepTimeout(10.seconds)
      // One retry, then compensate. The deposit is the step that can fail for reasons
      // outside this workflow's control, so it is the one worth a second attempt.
      .stepRecovery(
        TransferWorkflow.deposit,
        RecoverStrategy.maxRetries(1).failoverTo(TransferWorkflow.compensate)
      )
      .build
  // docs:end settings

  // docs:start start
  def start(transfer: Transfer): Effect[Done] =
    if transfer.amount <= 0 then effects.error("transfer amount must be greater than zero")
    else if currentState.status != "not-started" then
      effects.error("transfer already started", ErrorCode.Conflict)
    else
      effects
        .updateState(TransferState(transfer, "started"))
        .transitionTo(TransferWorkflow.withdraw.withInput(transfer))
        .thenReply(Done)
  // docs:end start

  // docs:start steps
  def withdrawStep(transfer: Transfer): StepEffect =
    wallet(transfer.from).call(WalletEntity.withdraw).invoke(transfer.amount)
    stepEffects
      .updateState(currentState.copy(status = "withdrawn"))
      .thenTransitionTo(TransferWorkflow.deposit.withInput(transfer))

  def depositStep(transfer: Transfer): StepEffect =
    wallet(transfer.to).call(WalletEntity.deposit).invoke(transfer.amount)
    stepEffects.updateState(currentState.copy(status = "completed")).thenEnd

  /**
   * Puts the money back.
   *
   * Takes no input and reads the transfer from `currentState`, which is why failover steps are
   * input-free: what needs compensating is whatever the workflow has recorded, not whatever was
   * known when the settings were written.
   */
  def compensateStep: StepEffect =
    val transfer = currentState.transfer
    wallet(transfer.from).call(WalletEntity.deposit).invoke(transfer.amount)
    stepEffects.updateState(currentState.copy(status = "compensated")).thenEnd
  // docs:end steps

  def status: ReadOnlyEffect[TransferState] = effects.reply(currentState)

  private def wallet(id: String) = client.forKeyValueEntity(EntityId(id))

// docs:start companion
object TransferWorkflow
    extends Workflow.Companion[TransferWorkflow, TransferState](
      componentId = ComponentId("transfer"),
      stateSerializer = Codecs.serializer[TransferState]("transfer-state")
    ):

  given Serializer[Transfer] = Codecs.serializer[Transfer]("transfer")

  def create(context: WorkflowContext) = new TransferWorkflow(context)

  val withdraw   = step("withdraw")(_.withdrawStep)
  val deposit    = step("deposit")(_.depositStep)
  val compensate = step("compensate")(_.compensateStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
// docs:end companion
