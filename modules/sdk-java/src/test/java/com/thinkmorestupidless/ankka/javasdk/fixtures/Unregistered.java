package com.thinkmorestupidless.ankka.javasdk.fixtures;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

/** A component no service registers. */
@ComponentId("unregistered")
public class Unregistered extends KeyValueEntity<String> {
  @Query("name")
  public ReadOnlyEffect<String> name() {
    return effects().reply("");
  }
}
