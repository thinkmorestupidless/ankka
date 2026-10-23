package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.Agents;
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider;
import com.thinkmorestupidless.ankka.agent.javasdk.TaskSnapshot;
import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;
import com.thinkmorestupidless.ankka.javasdk.cart.CartState;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.testkit.AgentResult;
import com.thinkmorestupidless.ankka.javasdk.testkit.AgentTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.TestClient;
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider;
import java.time.Duration;
import java.util.List;

/** What a Java service with agents writes, as the documentation shows it; AgentKitSuite runs it. */
public final class AgentSamples {

  private AgentSamples() {}

  // docs:start registration
  /** The cart, the agent that helps with it and the one that answers questions about it. */
  public static ServiceBuilder service(ModelProvider model) {
    return Ankka.service("shop")
        .withModule(Agents.module().withModelProvider(model))
        .register(ShoppingCart.class)
        .register(CartHelper.class)
        .register(CartAnswerer.class);
  }
  // docs:end registration

  // docs:start call-agent
  /** One turn of the session {@code session}: the model's answer, its tools run on the way. */
  public static String ask(ComponentClient client, String session, String question) {
    return AgentClients.forAgent(client, session).call(CartHelper::ask).invoke(question);
  }
  // docs:end call-agent

  // docs:start run-task
  /** Starts the work and answers at once, with the task's id. */
  public static String startAnswer(ComponentClient client, String question) {
    return AgentClients.runSingleTask(client, CartAnswerer.class, CartTasks.ANSWER, question);
  }

  /** The task's record once it has ended, its result read as an {@code Answer}. */
  public static TaskSnapshot<CartTasks.Answer> answer(ComponentClient client, String taskId) {
    return AgentClients.forTask(client, taskId).await(CartTasks.ANSWER, Duration.ofMinutes(1));
  }
  // docs:end run-task

  /** One turn with no runtime, its tool's call to the cart answered by the kit's client. */
  public static AgentResult<String> oneTurn() {
    // docs:start unit-test
    var model =
        new TestModelProvider()
            .expectToolCall("cart_items", "{\"cartId\":\"c-1\"}")
            .expectText("Cart c-1 holds 2 lines");
    var cart =
        TestClient.of(ShoppingCart.class)
            .stub(
                ShoppingCart::getCart,
                () ->
                    new CartState(
                        "c-1", List.of(new LineItem("p", "Pen", 2), new LineItem("q", "Pad", 1)), false));
    var kit = AgentTestKit.of(CartHelper.class, model, "s-1", cart);

    var result = kit.call(CartHelper::ask, "How full is c-1?");
    // result.reply() is "Cart c-1 holds 2 lines"; model.lastRequest() holds what the tool answered
    // docs:end unit-test
    return result;
  }

  /** A task worked to its end in the whole service, by a scripted model. */
  public static CartTasks.Answer answerInTheService() {
    // docs:start service-test
    var model =
        new TestModelProvider()
            .expectToolCall("cart_total", "{\"cartId\":\"c-1\"}")
            .expectCompleteTask(
                "{\"answer\":\"Cart c-1 holds 3 items.\",\"sources\":[\"cart_total\"]}");
    try (var kit = AnkkaTestKit.start(service(model))) {
      var client = kit.componentClient();
      client.forEventSourcedEntity("c-1").call(ShoppingCart::addItem).invoke(new LineItem("p", "Pen", 3));

      var taskId = startAnswer(client, "How many items are in cart c-1?");
      return answer(client, taskId).result().orElseThrow();
    }
    // docs:end service-test
  }
}
