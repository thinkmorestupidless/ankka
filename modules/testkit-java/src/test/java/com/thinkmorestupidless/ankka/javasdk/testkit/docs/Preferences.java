package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

/** A traveller's preferences, one entity per traveller, read by the activity agent. */
@ComponentId("preferences")
public class Preferences extends KeyValueEntity<Preferences.State> {

  @Manifest("preferences")
  public record State(String summary) {}

  @Override
  protected State emptyState() {
    return new State("no preferences recorded");
  }

  @Command("set")
  public Effect<Done> set(String summary) {
    return effects().updateState(new State(summary)).thenReply(Done.DONE);
  }

  @Query("get")
  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }
}
