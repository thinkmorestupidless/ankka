package {{package}};

import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;

/** The service: every component and endpoint registered by name, nothing found by scanning. */
public final class Main {

  // docs:start service
  public static ServiceBuilder service() {
    return Ankka.service("{{name}}").register(ShoppingCart.class).withEndpoint(CartEndpoint.class);
  }
  // docs:end service

  public static void main(String[] args) {
    service().start().whenTerminated().toCompletableFuture().join();
  }

  private Main() {}
}
