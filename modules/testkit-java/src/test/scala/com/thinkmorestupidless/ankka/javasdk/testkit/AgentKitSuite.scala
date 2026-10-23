package com.thinkmorestupidless.ankka.javasdk.testkit

import com.thinkmorestupidless.ankka.javasdk.cart.{CartState, LineItem, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.{CartHelper, KindRefs}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

/**
 * The Java agent test kit: a turn of a Java agent with no runtime, its tool's call to an entity
 * answered by the kit's client, a structured reply, a guardrail's refusal, a turn stopped for
 * approval, the session read again by the next turn, and a script that runs out.
 */
class AgentKitSuite extends munit.FunSuite with LogCapturing:

  override def beforeEach(context: BeforeEach): Unit = CartHelper.REFUNDED.clear()

  private val cart = TestClient
    .of(classOf[ShoppingCart])
    .stub(
      KindRefs.getCart,
      () =>
        CartState("c-1", java.util.List.of(LineItem("p", "Pen", 2), LineItem("q", "Pad", 1)), false)
    )

  test("a turn runs the tool the model asks for, its call answered by the kit's client") {
    val model = TestModelProvider()
      .expectToolCall("cart_items", """{"cartId":"c-1"}""")
      .expectText("Cart c-1 holds 2 lines")
    val kit    = AgentTestKit.of(classOf[CartHelper], model, "s-1", cart)
    val result = kit.call(CartHelper.ASK, "How full is c-1?")
    assertEquals(result.reply, "Cart c-1 holds 2 lines")
    assert(model.lastRequest.contains("2"), model.lastRequest)
    assertEquals(model.callCount, 2)
    assert(result.messages > 0)
  }

  test("a structured reply is read as the handler's record") {
    val model = TestModelProvider().expectText("""{"cartId":"c-1","items":2}""")
    val kit   = AgentTestKit.of(classOf[CartHelper], model)
    assertEquals(kit.call(CartHelper.SUMMARISE, "c-1").reply, CartHelper.Summary("c-1", 2))
  }

  test("a guardrail refuses before the model is asked, as the caller would be refused") {
    val model = TestModelProvider()
    val result =
      AgentTestKit.of(classOf[CartHelper], model).call(CartHelper.SHORT, "far too long a question")
    assert(result.isError)
    assertEquals(result.problem.status, 403)
    assertEquals(model.callCount, 0)
  }

  test("a tool that needs approval stops the turn before it runs") {
    val model  = TestModelProvider().expectToolCall("refund", """{"cartId":"c-1"}""")
    val result = AgentTestKit.of(classOf[CartHelper], model).call(CartHelper.ASK, "Refund c-1")
    assert(result.isAwaiting)
    assertEquals(result.awaiting.get(0).tool, "refund")
    assert(CartHelper.REFUNDED.isEmpty)
  }

  test("the next turn reads what the last one recorded") {
    val model = TestModelProvider().expectText("noted").expectText("heron")
    val kit   = AgentTestKit.of(classOf[CartHelper], model)
    kit.call(CartHelper.ASK, "The word is heron.").reply: Unit
    kit.call(CartHelper.ASK, "What was the word?").reply: Unit
    assert(model.lastRequest.contains("The word is heron."), model.lastRequest)
  }

  test("a script that runs out fails the turn, naming the script") {
    val kit    = AgentTestKit.of(classOf[CartHelper], TestModelProvider())
    val thrown = intercept[Throwable](kit.call(CartHelper.ASK, "Anyone?").reply)
    assert(thrown.getMessage.toLowerCase.contains("script"), thrown.getMessage)
  }

  test("a class that is not an agent is refused, naming it") {
    val refused = intercept[RuntimeException](
      AgentTestKit.of(classOf[ShoppingCart].asInstanceOf[Class[CartHelper]], TestModelProvider())
    )
    assert(refused.getMessage.contains("ShoppingCart"), refused.getMessage)
  }
