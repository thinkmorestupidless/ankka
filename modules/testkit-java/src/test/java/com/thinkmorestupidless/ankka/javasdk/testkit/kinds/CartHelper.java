package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentCommandRef;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail;
import com.thinkmorestupidless.ankka.agent.javasdk.Tool;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An agent whose tool reads a cart, one that refunds only once approved, and a guarded handler. */
@ComponentId("cart-helper")
public class CartHelper extends Agent {

  /** What the refund tool refunded. */
  public static final List<String> REFUNDED = new CopyOnWriteArrayList<>();

  public record CartQuery(@Describe("the cart to read") String cartId) {}

  public record Summary(String cartId, int items) {}

  public static final class Tools {
    private final ComponentClient client;

    Tools(ComponentClient client) {
      this.client = client;
    }

    @Tool(value = "cart_items", description = "How many items a cart holds")
    public int items(CartQuery query) {
      return client
          .forEventSourcedEntity(query.cartId())
          .call(ShoppingCart::getCart)
          .invoke()
          .items()
          .size();
    }

    @Tool(value = "refund", description = "Refunds a cart", requiresApproval = true)
    public String refund(CartQuery query) {
      REFUNDED.add(query.cartId());
      return "refunded " + query.cartId();
    }
  }

  @Command("ask")
  public AgentEffect<String> ask(String question) {
    return effects()
        .systemMessage("You help with carts.")
        .userMessage(question)
        .tools(new Tools(componentClient()))
        .thenReply();
  }

  @Command("summarise")
  public AgentEffect<Summary> summarise(String cartId) {
    return effects().userMessage("Summarise cart " + cartId).thenReply(Summary.class);
  }

  @Command("short")
  public AgentEffect<String> shortQuestion(String question) {
    return effects()
        .userMessage(question)
        .guardrails(Guardrail.maxInputLength(10))
        .thenReply();
  }

  public static final AgentCommandRef<CartHelper, String, String> ASK = CartHelper::ask;
  public static final AgentCommandRef<CartHelper, String, Summary> SUMMARISE =
      CartHelper::summarise;
  public static final AgentCommandRef<CartHelper, String, String> SHORT = CartHelper::shortQuestion;
}
