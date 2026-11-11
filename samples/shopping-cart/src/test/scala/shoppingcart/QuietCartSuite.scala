package shoppingcart

import com.thinkmorestupidless.ankka.core.Done
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain.LineItem

// docs:start log-capturing
import com.thinkmorestupidless.ankka.testkit.LogCapturing

class QuietCartSuite extends munit.FunSuite with LogCapturing:
  // docs:end log-capturing

  test("a suite with LogCapturing runs its tests as any other suite does") {
    val kit = EventSourcedTestKit.of(ShoppingCartEntity, "cart-quiet")
    assertEquals(kit.call(ShoppingCartEntity.addItem)(LineItem("p1", "Widget", 1)).replyValue, Done)
  }
