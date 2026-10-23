package com.thinkmorestupidless.ankka.agent.javasdk.fixtures;

import com.thinkmorestupidless.ankka.agent.javasdk.Blueprint;
import com.thinkmorestupidless.ankka.agent.javasdk.BlueprintContext;
import com.thinkmorestupidless.ankka.agent.javasdk.BlueprintHandler;
import com.thinkmorestupidless.ankka.agent.javasdk.BlueprintRegistry;
import com.thinkmorestupidless.ankka.agent.javasdk.Shape;
import com.thinkmorestupidless.ankka.agent.javasdk.Step;
import com.thinkmorestupidless.ankka.agent.javasdk.Worker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** A blueprint with a worker that lists titles and a call step that keeps each one. */
public final class Keeper {

  private Keeper() {}

  /** What the handler kept, as JSON, with the run it kept it for. */
  public static final List<String> KEPT = new CopyOnWriteArrayList<>();

  public static final Blueprint KEEPER =
      Blueprint.named("keeper")
          .input(Shape.obj(Map.of("topic", Shape.string())))
          .worker(Worker.named("lister").instructions("List titles on the topic.").budget(2))
          .step(Step.named("titles").ask("lister").reads("input").result(Shape.arr(Shape.string())))
          .step(Step.named("keep").call("keep").reads("titles").result(Shape.integer()));

  public static BlueprintRegistry registry(BlueprintContext context) {
    return BlueprintRegistry.empty()
        .handlers(
            BlueprintHandler.of(
                "keep",
                (run, input) -> {
                  KEPT.add(run.blueprint() + "/" + run.step() + ":" + input);
                  return "2";
                }))
        .carrying(KEEPER);
  }
}
