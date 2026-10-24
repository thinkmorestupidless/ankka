package {{package}};

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.testkit.EventSourcedTestKit;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The entity alone: no runtime, no database, milliseconds. */
class ShoppingCartTest {

  // docs:start entity-test
  @Test
  void anItemAddedIsInTheCart() {
    var cart = EventSourcedTestKit.of(ShoppingCart.class, "c1");
    var result = cart.call(ShoppingCart::addItem, new LineItem("p1", "Pen", 2));
    assertEquals(Done.DONE, result.reply());
    assertEquals(List.of(new CartEvent.ItemAdded(new LineItem("p1", "Pen", 2))), result.events());
    assertEquals(2, cart.state().totalQuantity());
  }
  // docs:end entity-test

  @Test
  void aCheckedOutCartTakesNoMoreItems() {
    var cart = EventSourcedTestKit.of(ShoppingCart.class);
    cart.call(ShoppingCart::addItem, new LineItem("p1", "Pen", 1));
    cart.call(ShoppingCart::checkout);
    var refused = cart.call(ShoppingCart::addItem, new LineItem("p2", "Ink", 1));
    assertTrue(refused.isError());
    assertEquals(409, refused.problem().status());
  }
}
