package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;

/** The shopping cart service, started as the deployed image starts it. */
public final class Main {

  // docs:start service
  public static ServiceBuilder service() {
    return Ankka.service("shopping-cart")
        .register(ShoppingCart.class)
        .register(CartRows.class)
        .register(CheckoutNotifier.class)
        .register(CheckoutsSeen.class)
        .withEndpoint(CartEndpoint.class)
        .withEndpoint(CheckoutsSeenEndpoint.class)
        .withEndpoint(CartKeysEndpoint.class)
        .withMcpEndpoint(CartMcpEndpoint.class);
  }
  // docs:end service

  public static void main(String[] args) {
    service().start().whenTerminated().toCompletableFuture().join();
  }

  private Main() {}
}
