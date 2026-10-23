package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousAgent;
import com.thinkmorestupidless.ankka.agent.javasdk.AutonomousDefinition;
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail;
import com.thinkmorestupidless.ankka.agent.javasdk.Tool;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import com.thinkmorestupidless.ankka.javasdk.cart.CartState;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import java.util.List;
import java.util.stream.Collectors;

// docs:start agent
/**
 * Answers questions about carts, on its own: it is handed a task and works it, iteration by
 * iteration, until it completes it with an {@code Answer} that cites its sources.
 *
 * <p>Its tools read the cart entity through the component client. A tool may run more than once
 * for one request of the model — after a crash the last recorded request's tools run again — and
 * these only read, so that costs nothing.
 */
@ComponentId("cart-answerer")
public class CartAnswerer extends AutonomousAgent {

  public static final class CartTools {
    private final ComponentClient client;

    CartTools(ComponentClient client) {
      this.client = client;
    }

    private CartState cart(String cartId) {
      return client.forEventSourcedEntity(cartId).call(ShoppingCart::getCart).invoke();
    }

    @Tool(value = "cart_contents", description = "Lists what is in a cart.")
    public String contents(@Describe("The id of the cart.") String cartId) {
      var items = cart(cartId).items();
      if (items.isEmpty()) return "cart " + cartId + " is empty";
      return items.stream()
          .map(i -> i.quantity() + " x " + i.name())
          .collect(Collectors.joining(", "));
    }

    @Tool(value = "cart_total", description = "Counts the items in a cart.")
    public String total(@Describe("The id of the cart.") String cartId) {
      int total = cart(cartId).items().stream().mapToInt(LineItem::quantity).sum();
      return "cart " + cartId + " holds " + total + " items";
    }
  }

  @Override
  protected AutonomousDefinition definition() {
    return AutonomousDefinition.describedAs("Answers questions about shopping carts")
        .instructions("Look carts up rather than guessing. Be brief.")
        .guardrails(Guardrail.maxInputLength(2000))
        .accepts(CartTasks.ANSWER, 5);
  }

  @Override
  protected List<Object> tools() {
    return List.of(new CartTools(componentClient()));
  }
}
// docs:end agent
