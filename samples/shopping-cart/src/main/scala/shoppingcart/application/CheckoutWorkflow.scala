package shoppingcart.application

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.DurationInt

/**
 * The workflow's own state.
 *
 * `status` and `mode` are plain strings rather than a Scala `enum` on purpose. A fieldless enum
 * encodes as `{"type":"Reserving"}` under ankka's shared codec configuration, and this state has to
 * decode in the Python and TypeScript carts too — the three samples are one service written three
 * times. A string is what all three can agree on.
 */
final case class Checkout(
    cartId: String,
    status: String = "new",
    reserved: Int = 0,
    mode: String = "ok"
)

/** Thrown by the charge step so the declared recovery — one retry, then compensate — applies. */
final class PaymentDeclined(message: String) extends RuntimeException(message)

/**
 * A checkout as a durable multi-step process: reserve the stock, charge the customer, and check the
 * cart out — or compensate.
 *
 * Every transition is journalled before the next step begins, so a crash between reserving and
 * charging resumes rather than starting again. A step that *throws* gets the recovery declared in
 * `settings`; a step that ends the workflow deliberately is not a failure.
 */
// docs:start workflow
final class CheckoutWorkflow(context: WorkflowContext) extends Workflow[Checkout]:

  private val client = context.componentClient

  def emptyState: Checkout = Checkout(context.workflowId)

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(10.seconds)
      .stepRecovery(
        CheckoutWorkflow.charge,
        RecoverStrategy.maxRetries(1).failoverTo(CheckoutWorkflow.compensate)
      )
      .build

  /** `mode`: `ok`, `fail` (the charge is declined) or `pause` (a pause before it). */
  def start(mode: String): Effect[Done] =
    if currentState.status != "new" then
      effects.error(s"checkout is already ${currentState.status}", ErrorCode.Conflict)
    else
      effects
        .updateState(currentState.copy(status = "reserving", mode = mode))
        .transitionTo(CheckoutWorkflow.reserve)
        .thenReply(Done)

  def status: ReadOnlyEffect[Checkout] = effects.reply(currentState)

  def reserveStep: StepEffect =
    // A client call from a step: what the cart holds.
    val total = cart.call(ShoppingCartEntity.totalQuantity).invoke()
    val next =
      if currentState.mode == "pause" then CheckoutWorkflow.waitForTimeout
      else CheckoutWorkflow.charge
    stepEffects
      .updateState(currentState.copy(status = "reserved", reserved = total))
      .thenTransitionTo(next.ref)

  def waitStep: StepEffect =
    stepEffects
      .updateState(currentState.copy(status = "waiting"))
      .thenPause(1500.millis, CheckoutWorkflow.charge.ref)

  def chargeStep: StepEffect =
    if currentState.mode == "fail" then throw PaymentDeclined("payment declined")
    // Not idempotent — a retry after the cart was checked out is refused — which is why `charge` is
    // allowed one retry and then fails over, and why compensation exists.
    if currentState.reserved > 0 then cart.call(ShoppingCartEntity.checkout).invoke(): Unit
    stepEffects.updateState(currentState.copy(status = "charged")).thenEnd

  def compensateStep: StepEffect =
    stepEffects.updateState(currentState.copy(status = "compensated", reserved = 0)).thenEnd

  private def cart = client.forEventSourcedEntity(EntityId(currentState.cartId))

object CheckoutWorkflow
    extends Workflow.Companion[CheckoutWorkflow, Checkout](
      componentId = ComponentId("checkout"),
      stateSerializer = Codecs.serializer[Checkout]("checkout")
    ):

  def create(context: WorkflowContext) = new CheckoutWorkflow(context)

  val reserve = step("reserve")(_.reserveStep)
  // The wire name is "wait"; the Scala name cannot be, because `wait` is final on `AnyRef`. That the
  // two are declared separately is exactly what makes this possible.
  val waitForTimeout = step("wait")(_.waitStep)
  val charge         = step("charge")(_.chargeStep)
  val compensate     = step("compensate")(_.compensateStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
// docs:end workflow
