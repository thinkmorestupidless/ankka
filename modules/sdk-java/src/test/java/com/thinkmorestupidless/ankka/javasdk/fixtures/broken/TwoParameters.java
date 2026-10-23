package com.thinkmorestupidless.ankka.javasdk.fixtures.broken;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

/** A handler with two parameters. */
@ComponentId("two")
public class TwoParameters extends KeyValueEntity<String> {
  @Command("set")
  public Effect<Done> set(String a, String b) {
    return effects().updateState(a + b).thenDone();
  }
}
