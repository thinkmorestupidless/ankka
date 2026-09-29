package shoppingcart.application

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.{FunctionTool, Guardrail}
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.*

// docs:start task-type
/** An answer, and what the agent looked at to reach it. */
final case class Answer(answer: String, sources: List[String])

object Answer:
  given JsonValueCodec[Answer] = Codecs.make        // how the result is read and stored
  given JsonSchema[Answer]     = JsonSchema.derived // how it is described to the model

object CartTasks:
  /**
   * `"answer"` is the wire name, written into every task of this type: renaming the Scala value
   * changes nothing stored.
   */
  val answer: TaskType[Answer] = Task
    .named("answer")
    .describedAs("Answer a question about a shopping cart, citing what you looked at")
    .resultConformsTo[Answer]
    .rule("cites-sources")(answer =>
      if answer.sources.isEmpty then TaskRule.Rejected("say which tools you used in sources")
      else TaskRule.Accepted
    )
// docs:end task-type

// docs:start agent
/**
 * Answers questions about carts, on its own: it is handed a task and works it, iteration by
 * iteration, until it completes it with an `Answer` that cites its sources.
 *
 * Its tools read the cart entity through the component client, which the instance's context holds.
 * A tool may run more than once for one request of the model — after a crash the last recorded
 * request's tools run again — and these only read, so that costs nothing.
 */
final class CartAnswerer(context: AutonomousAgentContext) extends AutonomousAgent(context):

  private def cart(cartId: String) =
    context.componentClient
      .forEventSourcedEntity(EntityId(cartId))
      .call(ShoppingCartEntity.getCart)
      .invoke()

  override def tools: Seq[FunctionTool] = Seq(
    FunctionTool
      .named("cart_contents")
      .describedAs("Lists what is in a cart.")
      .param[String]("cartId", "The id of the cart.")
      .handle { cartId =>
        val items = cart(cartId).items
        if items.isEmpty then s"cart $cartId is empty"
        else items.map(i => s"${i.quantity} x ${i.name}").mkString(", ")
      },
    FunctionTool
      .named("cart_total")
      .describedAs("Counts the items in a cart.")
      .param[String]("cartId", "The id of the cart.")
      .handle(cartId => s"cart $cartId holds ${cart(cartId).totalQuantity} items")
  )

object CartAnswerer extends AutonomousAgent.Companion[CartAnswerer](ComponentId("cart-answerer")):
  def create(context: AutonomousAgentContext) = new CartAnswerer(context)

  def definition: AutonomousAgentDefinition =
    define
      .describedAs("Answers questions about shopping carts")
      .instructions("Look carts up rather than guessing. Be brief.")
      .guardrails(Guardrail.maxInputLength(2000))
      .capability(TaskAcceptance.of(CartTasks.answer).maxIterationsPerTask(5))
// docs:end agent
