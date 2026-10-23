package com.thinkmorestupidless.ankka.javasdk.fixtures.broken;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

/** Two handlers with one wire name. */
@ComponentId("duplicate")
public class DuplicateWireName extends KeyValueEntity<String> {
  @Command("add-item")
  public Effect<Done> addItem(String s) {
    return effects().updateState(s).thenDone();
  }

  @Command("add-item")
  public Effect<Done> addAnother(String s) {
    return effects().updateState(s).thenDone();
  }
}
