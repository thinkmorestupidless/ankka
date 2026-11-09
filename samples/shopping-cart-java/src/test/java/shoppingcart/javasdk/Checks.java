package shoppingcart.javasdk;

import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import com.thinkmorestupidless.ankka.javasdk.mcp.Expiry;
import com.thinkmorestupidless.ankka.javasdk.testkit.Published;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** What a test of the running service checks, as a Java test writes it. */
public final class Checks {

  private Checks() {}

  // docs:start published
  /** The keys of the notices the notifier published, once one has: the cart's id. */
  public static List<Optional<String>> noticeKeys() {
    try (var kit = AnkkaTestKit.start(Main.service())) {
      var cart = kit.componentClient().forEventSourcedEntity("c-7");
      cart.call(ShoppingCart::addItem).invoke(new LineItem("p1", "Pen", 1));
      cart.call(ShoppingCart::checkout).invoke();
      // Poll until the notice arrives: the consumer publishes after the entity has replied.
      kit.eventually(
          Duration.ofSeconds(20),
          () -> {
            if (kit.published(CheckoutNotifier.TOPIC).isEmpty()) throw new AssertionError("no notice");
          });
      return kit.published(CheckoutNotifier.TOPIC).stream().map(Published::key).toList();
    }
  }
  // docs:end published

  // docs:start mcp-request
  /** One JSON-RPC request to the cart's MCP endpoint, as any MCP client sends it. */
  static String mcp(AnkkaTestKit kit, String assistantKey, String method, String params)
      throws Exception {
    var message =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}";
    var request =
        HttpRequest.newBuilder(kit.http().resolve("/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", "2025-06-18")
            .header("Authorization", "Bearer " + assistantKey)
            .POST(HttpRequest.BodyPublishers.ofString(message))
            .build();
    var response = kit.httpClient().send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200)
      throw new AssertionError(response.statusCode() + ": " + response.body());
    return response.body();
  }

  static String callTool(AnkkaTestKit kit, String assistantKey, String name, String arguments)
      throws Exception {
    var params = "{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}";
    return mcp(kit, assistantKey, "tools/call", params);
  }
  // docs:end mcp-request

  /** Adds to a cart through its tools, as an assistant would, and reads it back: the answer. */
  public static String cartThroughTools() throws Exception {
    try (var kit = AnkkaTestKit.start(Main.service())) {
      // The service's own code issues a key for one customer's assistant; the test does it here.
      var key = kit.clientKeys().issue("assistant", Expiry.after(Duration.ofHours(1))).clientKey();
      callTool(kit, key, "add_item", "{\"cartId\":\"m-1\",\"productId\":\"p1\",\"quantity\":2}");
      return callTool(kit, key, "get_cart", "{\"cartId\":\"m-1\"}");
    }
  }
}
