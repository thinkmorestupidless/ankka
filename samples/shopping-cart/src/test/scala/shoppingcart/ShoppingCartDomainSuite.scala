package shoppingcart

import shoppingcart.domain.*

/** Domain rules, with nothing running. */
class ShoppingCartDomainSuite extends munit.FunSuite:

  private val cart = ShoppingCart.empty("cart-1")

  test("adding the same product twice folds the quantities") {
    val updated = cart
      .addItem(LineItem("p1", "Widget", 2))
      .addItem(LineItem("p1", "Widget", 3))
    assertEquals(updated.items, List(LineItem("p1", "Widget", 5)))
    assertEquals(updated.totalQuantity, 5)
  }

  test("items are ordered by product id regardless of insertion order") {
    val forwards = cart.addItem(LineItem("a", "A", 1)).addItem(LineItem("b", "B", 1))
    val reverse  = cart.addItem(LineItem("b", "B", 1)).addItem(LineItem("a", "A", 1))
    assertEquals(forwards, reverse)
  }

  test("removing a product drops its whole line") {
    val updated = cart
      .addItem(LineItem("p1", "Widget", 2))
      .addItem(LineItem("p2", "Gadget", 1))
      .removeItem("p1")
    assertEquals(updated.items.map(_.productId), List("p2"))
    assert(!updated.contains("p1"))
  }

  test("checkout is a state flag, not a deletion") {
    val checked = cart.addItem(LineItem("p1", "Widget", 1)).onCheckedOut
    assert(checked.checkedOut)
    assertEquals(checked.totalQuantity, 1)
  }
