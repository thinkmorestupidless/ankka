package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.Blueprint;
import com.thinkmorestupidless.ankka.agent.javasdk.BlueprintsRef;
import com.thinkmorestupidless.ankka.agent.javasdk.RunsRef;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** The calls the blueprints page shows, written as an endpoint or a workflow would write them. */
public final class BlueprintCalls {

  private BlueprintCalls() {}

  // docs:start registering
  public static BlueprintsRef.Registered register(ComponentClient client, String jsonText) {
    var blueprints = AgentClients.blueprints(client);
    // Registered(name, version, isNew, notes)
    var registered = blueprints.register(GuideBlueprints.BRIEF);
    // the same, from a blueprint written as JSON
    blueprints.register(jsonText);
    // every version, oldest first
    List<BlueprintsRef.Version> versions = blueprints.versions("brief");
    // the version a run started now would use
    Optional<Blueprint> current = blueprints.current("brief");
    return registered;
  }
  // docs:end registering

  // docs:start run
  public static RunsRef.RunSnapshot brief(ComponentClient client, String topic) {
    var runs = AgentClients.runs(client);
    var started = runs.start("brief", "{\"topic\":\"" + topic + "\"}", "brief-7");
    var run = runs.await("brief-7", Duration.ofSeconds(60));
    if (!run.status().equals("completed")) {
      throw new IllegalStateException("the brief " + started.runId() + " " + run.status());
    }
    return run;
  }
  // docs:end run
}
