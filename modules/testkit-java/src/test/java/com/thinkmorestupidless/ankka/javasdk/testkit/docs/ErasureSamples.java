package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;
import com.thinkmorestupidless.ankka.javasdk.erasure.ErasureReport;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Players;

/** The personal data page's erasure handler, in Java. */
public final class ErasureSamples {

  private ErasureSamples() {}

  // docs:start erasure-handler
  static ServiceBuilder players() {
    return Ankka.service("players")
        .register(Players.class)
        .withErasureHandler(
            (ctx, subject) -> ErasureReport.of(ctx.storage().defaultBucket().erase(subject)));
  }
  // docs:end erasure-handler
}
