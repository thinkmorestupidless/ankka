package shoppingcart

import com.thinkmorestupidless.ankka.core.effect.ViewEffect
import com.thinkmorestupidless.ankka.testkit.ViewTestKit
import shoppingcart.application.{CartRow, CartRows}
import shoppingcart.domain.LineItem
import shoppingcart.domain.ShoppingCartEvent.*

/**
 * The cart rows view with no projection: each change goes to the handler with the row the view
 * holds for that cart, and the row it writes is read back through the view's own serializer.
 */
class CartRowsUnitSuite extends munit.FunSuite:

  // docs:start view-test
  test("an added item writes the cart's row, and a deletion removes it") {
    val kit = ViewTestKit(CartRows)
    kit.change("c1", ItemAdded(LineItem("p1", "Pen", 2))): Unit
    assertEquals(
      kit.row("c1"),
      Some(CartRow("c1", Map("p1" -> 2), checkedOut = false, names = Map("p1" -> "Pen")))
    )
    assertEquals(kit.deleted("c1"), ViewEffect.DeleteRow)
    assertEquals(kit.row("c1"), None)
  }
  // docs:end view-test

  test("a repeated add is folded into the row, as the entity folds it") {
    val kit = ViewTestKit(CartRows)
    kit.change("c1", ItemAdded(LineItem("p1", "Pen", 2))): Unit
    kit.change("c1", ItemAdded(LineItem("p1", "Pen", 3))): Unit
    kit.change("c1", ItemRemoved("p9")): Unit
    assertEquals(kit.row("c1").map(_.quantities), Some(Map("p1" -> 5)))
    assertEquals(kit.row("c1").map(_.totalQuantity), Some(5))
  }
