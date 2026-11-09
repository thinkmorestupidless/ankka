package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;

// docs:start key-value
/** When a cart was checked out: the value is the record, and nothing cares how it got there. */
@ComponentId("checkout-log")
public class CheckoutLog extends KeyValueEntity<CheckoutLog.CheckoutRecord> {

  @Manifest("checkout-record")
  public record CheckoutRecord(String cartId, long at, boolean notified) {}

  @Override
  protected CheckoutRecord emptyState() {
    return new CheckoutRecord(context().entityId(), 0L, false);
  }

  @Command("record")
  public Effect<Done> record(Long at) {
    return effects().updateState(new CheckoutRecord(currentState().cartId(), at, true)).thenDone();
  }

  @Query("get")
  public ReadOnlyEffect<CheckoutRecord> get() {
    return effects().reply(currentState());
  }
}
// docs:end key-value
