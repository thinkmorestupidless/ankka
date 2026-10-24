package com.thinkmorestupidless.ankka.javasdk.fixtures;

import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

/** A key value entity holding a number. */
@ComponentId("counter")
public class Counter extends KeyValueEntity<Long> {

  @Override
  protected Long emptyState() {
    return 0L;
  }

  @Command("increase")
  public Effect<Long> increase(int by) {
    return effects().updateState(currentState() + by).thenReplyState();
  }

  @Query("get")
  public ReadOnlyEffect<Long> get() {
    return effects().reply(currentState());
  }
}
