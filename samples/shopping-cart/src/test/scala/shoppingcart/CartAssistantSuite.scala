package shoppingcart

// A wildcard because `forAgent` is an extension method on `ComponentClient` and `Json` is the agent
// module's own, not the core's.
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode, SessionId}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import shoppingcart.application.{CartAssistant, ShoppingCartEntity}
import shoppingcart.domain.LineItem

import scala.concurrent.duration.DurationInt

/**
 * The assistant end to end: sharded per session, real session memory, real tool loop — with a
 * scripted model, so the assertions are about the cart's behaviour rather than a model's mood.
 *
 * The interesting part is the tool. It calls the cart entity through the component client from
 * inside the agent loop, which runs *after* the handler returned, so this is what proves the client
 * the tool captured is still usable there.
 */
class CartAssistantSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null
  private val model                 = TestModelProvider()

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(ShoppingCartEntity.descriptor, CartAssistant.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit = model.reset()

  private def assistant(session: String) = testKit.componentClient.forAgent(SessionId(session))
  private def cart(id: String) = testKit.componentClient.forEventSourcedEntity(EntityId(id))

  private def fill(id: String): Unit =
    val _ = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 2))
    val _ = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p2", "Gadget", 1))

  /** The text of the tool result in the model's `n`th request, if it carried one. */
  private def toolResultIn(n: Int): Option[String] =
    model.requests(n).messages.collectFirst {
      case ChatMessage.ToolResults(results) if results.nonEmpty => results.head.content
    }

  test("a plain question reaches the model and its answer comes back") {
    model.expectText("I can help with your cart.")

    val reply = assistant("s-plain").call(CartAssistant.ask).invoke("Can you help?")

    assertEquals(reply, "I can help with your cart.")
  }

  test("the lookup tool reads the real cart and feeds its contents back to the model") {
    fill("cart-a")
    model
      .expectToolCall("lookup", Json.obj("cartId" -> Json.str("cart-a")))
      .expectText("You have a Widget and a Gadget.")

    val reply = assistant("s-tool").call(CartAssistant.ask).invoke("What is in cart-a?")

    assertEquals(reply, "You have a Widget and a Gadget.")
    assertEquals(model.callCount, 2, "the tool result should have earned a second model turn")
    // Items are held sorted by product id, so this order is stable.
    assertEquals(toolResultIn(1), Some("2 x Widget, 1 x Gadget"))
  }

  test("the lookup tool says so when the cart is empty rather than answering with nothing") {
    model
      .expectToolCall("lookup", Json.obj("cartId" -> Json.str("cart-empty")))
      .expectText("That cart is empty.")

    val _ = assistant("s-empty").call(CartAssistant.ask).invoke("What is in cart-empty?")

    assertEquals(toolResultIn(1), Some("cart cart-empty is empty"))
  }

  test("the guardrail refuses a reply that carries something shaped like an API key") {
    model.expectText("Sure, the key is sk-abc123.")

    val failure = intercept[CommandError] {
      assistant("s-secret").call(CartAssistant.ask).invoke("What is the key?")
    }

    assertEquals(failure.code, ErrorCode.Forbidden)
    assert(failure.getMessage.contains("a key leaked"), failure.getMessage)
  }

  test("an ordinary reply is not refused by the output guardrail") {
    model.expectText("Your cart looks fine.")

    val reply = assistant("s-clean").call(CartAssistant.ask).invoke("How does it look?")

    assertEquals(reply, "Your cart looks fine.")
  }
