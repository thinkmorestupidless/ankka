package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit;
import com.thinkmorestupidless.ankka.javasdk.testkit.TestModelProvider;

/** A scripted model, and the service started on it. */
public final class ModelChecks {

  private ModelChecks() {}

  /** The weather agent answers from the script: one tool call, then the text. */
  public static String weatherWithScriptedModel() {
    // docs:start model-script
    var model =
        new TestModelProvider()
            .expectToolCall("get_weather", "{\"location\":\"Lisbon\"}")
            .expectText("Lisbon is 18C and sunny.");
    // docs:end model-script
    // docs:start start-with-model
    try (var kit = AnkkaTestKit.start(PlannerCalls.service(model))) {
      // docs:end start-with-model
      return AgentClients.forAgent(kit.componentClient(), "w-9")
          .call(WeatherAgent::ask)
          .invoke("What is the weather in Lisbon?");
    }
  }
}
