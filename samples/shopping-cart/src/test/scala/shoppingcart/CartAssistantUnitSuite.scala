package shoppingcart

import com.thinkmorestupidless.ankka.agent.{ChatMessage, Json, TestModelProvider}
import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.{AgentTestKit, TestTransport}
import shoppingcart.application.{CartAssistant, ShoppingCartEntity}
import shoppingcart.domain.ShoppingCart

/**
 * One turn of the assistant with no runtime: the handler, the tool loop and the guardrail, against
 * a scripted model, with the cart entity stubbed. Milliseconds a turn; the integration suite proves
 * the same against real sharding and session memory.
 */
class CartAssistantUnitSuite extends munit.FunSuite:

  // docs:start agent-test
  test("the assistant plans a lookup, and the tool reads the cart") {
    val carts = TestTransport().stub(ShoppingCartEntity.getCart)(() => ShoppingCart.empty("c9"))
    val model = TestModelProvider()
      .expectToolCall("lookup", Json.obj("cartId" -> Json.str("c9")))
      .expectText("Your cart is empty.")
    val kit = AgentTestKit.of(CartAssistant, model, "s1", others = carts)

    val answer = kit.call(CartAssistant.ask)("what is in cart c9?")
    assertEquals(answer.reply, "Your cart is empty.")
    // The tool ran in this process against the stubbed cart, and its result went to the model.
    val toolResult = model.requests(1).messages.collectFirst {
      case ChatMessage.ToolResults(results) => results.head.content
    }
    assertEquals(toolResult, Some("cart c9 is empty"))
  }
  // docs:end agent-test

  test("the guardrail refuses a reply carrying a key, as the caller would be refused") {
    val model = TestModelProvider().expectText("Sure, the key is sk-abc123.")
    val kit   = AgentTestKit.of(CartAssistant, model)

    val refused = kit.call(CartAssistant.ask)("What is the key?").error
    assertEquals(refused.code, ErrorCode.Forbidden)
    assert(refused.getMessage.contains("a key leaked"), refused.getMessage)
  }
