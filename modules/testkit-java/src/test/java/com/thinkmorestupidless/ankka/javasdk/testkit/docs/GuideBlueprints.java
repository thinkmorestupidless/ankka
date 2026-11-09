package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Blueprint;
import com.thinkmorestupidless.ankka.agent.javasdk.BlueprintContext;
import com.thinkmorestupidless.ankka.agent.javasdk.BlueprintHandler;
import com.thinkmorestupidless.ankka.agent.javasdk.BlueprintRegistry;
import com.thinkmorestupidless.ankka.agent.javasdk.FunctionTool;
import com.thinkmorestupidless.ankka.agent.javasdk.Question;
import com.thinkmorestupidless.ankka.agent.javasdk.Shape;
import com.thinkmorestupidless.ankka.agent.javasdk.Step;
import com.thinkmorestupidless.ankka.agent.javasdk.Tool;
import com.thinkmorestupidless.ankka.agent.javasdk.Verdict;
import com.thinkmorestupidless.ankka.agent.javasdk.Worker;
import com.thinkmorestupidless.ankka.agent.javasdk.YesNoQuestion;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** The blueprint the guide walks through, with every pattern that runs once. */
public final class GuideBlueprints {

  private GuideBlueprints() {}

  /** What the note tool was given, read by the suite. */
  public static final List<String> NOTED = new CopyOnWriteArrayList<>();

  // docs:start tools
  /** What the note tool takes: a record's components are the tool's parameters. */
  public record Note(@Describe("What to note.") String text) {}

  /** A tool a worker may name; it calls an entity in a real service. */
  public static final FunctionTool NOTE =
      FunctionTool.of(
          "note",
          "Keeps a note for the reader.",
          Note.class,
          note -> {
            NOTED.add(note.text());
            return "noted";
          });

  /** A handler a call step runs: code with the run's durability, given what the step reads. */
  public static final BlueprintHandler ARCHIVE =
      BlueprintHandler.of(
          "archive",
          (run, input) ->
              "{\"filed\":" + input.contains("\"polish\"") + ",\"run\":\"" + run.runId() + "\"}");

  /** A judgment question a judge step asks, and a verdict a critique step may draft until. */
  public static final YesNoQuestion ON_TOPIC =
      Question.yesNo("on-topic", "Is the draft about the topic asked for?");

  /** A tool built from the service's context, so it can read an entity. */
  public static final class CartTools {
    private final ComponentClient client;

    CartTools(ComponentClient client) {
      this.client = client;
    }

    @Tool(value = "cart_total", description = "Counts the items in a cart.")
    public String total(@Describe("The id of the cart.") String cartId) {
      var items = client.forEventSourcedEntity(cartId).call(ShoppingCart::getCart).invoke().items();
      return "cart " + cartId + " holds " + items.stream().mapToInt(LineItem::quantity).sum();
    }
  }
  // docs:end tools

  // docs:start blueprint
  public static final Shape TEXT = Shape.obj(Map.of("text", Shape.string()));

  public static final Blueprint BRIEF =
      Blueprint.named("brief")
          .input(Shape.obj(Map.of("topic", Shape.string())))
          // Workers are data: instructions, tools by name, a model by name, and a budget of calls.
          .worker(Worker.named("writer").instructions("You write briefly.").tools("note").budget(4))
          .worker(
              Worker.named("researcher")
                  .instructions("You research until you have a draft.")
                  .tools("note")
                  .budget(6))
          .worker(
              Worker.named("critic")
                  .instructions("You pass a draft only when it is on topic.")
                  .budget(2))
          // An ask step: one turn of a worker, its answer checked against the step's shape.
          .step(
              Step.named("outline")
                  .ask("writer")
                  .reads("input")
                  .result(Shape.obj(Map.of("points", Shape.arr(Shape.string())))))
          // A work step: an autonomous task the worker iterates until it completes it.
          .step(Step.named("draft").work("researcher").reads("outline").result(TEXT))
          // A judge step: a judgment's answers to questions about what the step reads.
          .step(Step.named("check").judge("on-topic").reads("draft").result(Shape.any()))
          // A critique step: the writer drafts until the critic's verdict passes, in two rounds.
          .step(
              Step.named("polish")
                  .critique("writer", Verdict.critic("critic"), 2)
                  .reads("draft", "check")
                  .result(TEXT))
          // A call step: a handler the service registered, with what the step reads.
          .step(
              Step.named("file")
                  .call("archive")
                  .reads("polish")
                  .result(Shape.obj(Map.of("filed", Shape.bool(), "run", Shape.string()))))
          .runBudget(20);
  // docs:end blueprint

  // docs:start combinations
  public static final Step DIG =
      Step.named("dig").work("researcher").each("papers.papers", 4, false).result(Shape.arr(TEXT));

  public static final Step VIEWS =
      Step.named("views")
          .ask("reviewer")
          .times(3)
          .reads("draft")
          .result(Shape.arr(Shape.obj(Map.of("n", Shape.integer(), "result", TEXT))));
  // docs:end combinations

  // docs:start registry
  /**
   * What the service's blueprints may name. A tool that needs the service is built from its
   * context.
   */
  public static BlueprintRegistry registry(BlueprintContext context) {
    return BlueprintRegistry.empty()
        .tools(NOTE, new CartTools(context.componentClient()))
        .handlers(ARCHIVE)
        .questions(ON_TOPIC)
        // registered when the service starts; a changed blueprint is a new version
        .carrying(BRIEF);
  }
  // docs:end registry
}
