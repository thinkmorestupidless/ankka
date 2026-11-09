package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agents;
import com.thinkmorestupidless.ankka.agent.javasdk.JudgmentProvider;
import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider;
import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;

/** A service that runs the guide's blueprint. */
public final class BlueprintService {

  private BlueprintService() {}

  // docs:start runtime
  public static ServiceBuilder service(ModelProvider model, JudgmentProvider judge) {
    return Ankka.service("guide")
        .withModule(
            Agents.module()
                .withModelProvider(model)
                .withJudgments(judge)
                .withBlueprints(GuideBlueprints::registry))
        .register(ShoppingCart.class);
  }
  // docs:end runtime
}
