package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.cart.CartState;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.CommandException;
import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Calls through the component client, as the component client page shows them. */
public final class CartCalls {

  private CartCalls() {}

  // docs:start addressing
  public static CartState addAndRead(ComponentClient client) {
    var cart = client.forEventSourcedEntity("cart-1");

    cart.call(ShoppingCart::addItem).invoke(new LineItem("p1", "Widget", 2)); // Done
    return cart.call(ShoppingCart::getCart).invoke(); // CartState
  }
  // docs:end addressing

  // docs:start fan-out
  public static List<Integer> totals(ComponentClient client, List<String> cartIds) {
    var pending =
        cartIds.stream()
            .map(id -> client.forEventSourcedEntity(id).call(ShoppingCart::getCart).invokeAsync())
            .toList();
    return pending.stream()
        .map(answer -> answer.toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join())
        .map(cart -> cart.items().stream().mapToInt(LineItem::quantity).sum())
        .toList();
  }
  // docs:end fan-out

  // docs:start refusal
  public static int addNothing(ComponentClient client) {
    try {
      client
          .forEventSourcedEntity("cart-1")
          .call(ShoppingCart::addItem)
          .invoke(new LineItem("p1", "Widget", 0));
      return 200;
    } catch (CommandException e) {
      // 400: the entity refused the command, and nothing was persisted.
      return e.problem().status();
    }
  }
  // docs:end refusal

  // docs:start key-value-call
  /** Registered like any other component, and called through the component client. */
  public static ServiceBuilder checkoutLogService() {
    return Ankka.service("carts").register(CheckoutLog.class);
  }

  public static CheckoutLog.CheckoutRecord checkoutRecord(ComponentClient client, String cartId) {
    return client.forKeyValueEntity(cartId).call(CheckoutLog::get).invoke();
  }
  // docs:end key-value-call
}
