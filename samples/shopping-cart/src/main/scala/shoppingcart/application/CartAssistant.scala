package shoppingcart.application

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given

/**
 * An agent that answers questions about a cart.
 *
 * It declares its instructions, one tool and one guardrail; the runtime runs the loop — the model,
 * the memory, compaction — and calls back here to run the tool and check the guardrail. No model
 * key lives in this file.
 */
// docs:start agent
final class CartAssistant extends Agent:

  /**
   * One description, two ways of delivering it: a single reply, or a token stream.
   *
   * The tool is built *here*, inside the handler, and closes over `componentClient` as a value.
   * That is not a style choice. `componentClient` reads the session context, the loop runs a tool
   * only after this handler has returned, and by then the session context is gone — so a tool whose
   * body says `componentClient` throws "sessionContext is only available inside a command handler"
   * when it is finally called. Reading it once, while still inside the handler, is what makes the
   * call work.
   */
  private def describe(question: String) =
    val client = componentClient

    val lookup = FunctionTool
      .named("lookup")
      .describedAs("Looks up what is in a cart by its id.")
      .param[String]("cartId", "The id of the cart to look up.")
      .handle { cartId =>
        val cart = client
          .forEventSourcedEntity(EntityId(cartId))
          .call(ShoppingCartEntity.getCart)
          .invoke()
        if cart.items.isEmpty then s"cart $cartId is empty"
        else cart.items.map(item => s"${item.quantity} x ${item.name}").mkString(", ")
      }

    effects
      .systemMessage(
        "You help shoppers with their carts. Use the lookup tool before answering about a cart."
      )
      .userMessage(question)
      .tools(lookup)
      .guardrails(CartAssistant.noSecrets)

  def ask(question: String): Effect[String] = describe(question).thenReply()

  def chat(question: String): StreamEffect = describe(question).thenStream()

object CartAssistant extends Agent.Companion[CartAssistant](ComponentId("assistant")):

  /**
   * Refuses a reply that looks like it is carrying an API key.
   *
   * Output only: the check runs before memory is written, so a rejected reply leaves no trace in
   * the conversation. Nothing is wrong with a shopper *asking* about a key.
   */
  val noSecrets: Guardrail = new Guardrail:
    val name = "no-secrets"
    override def checkOutput(text: String): Either[String, Unit] =
      if text.contains("sk-") then Left("a key leaked") else Right(())

  def create(context: AgentContext) = new CartAssistant

  val ask  = command("ask")(_.ask)
  val chat = stream("chat")(_.chat)
// docs:end agent
