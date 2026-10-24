package com.thinkmorestupidless.ankka.javasdk.testkit.kinds.broken;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.annotations.Replicated;
import com.thinkmorestupidless.ankka.javasdk.annotations.SnapshotEvery;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

/** A key value entity declared replicated, which only an event sourced entity can be. */
@ComponentId("note")
@Replicated
@SnapshotEvery(-5)
public class ReplicatedNote extends KeyValueEntity<String> {

  @Query("get")
  public ReadOnlyEffect<String> get() {
    return effects().reply(currentState());
  }
}
