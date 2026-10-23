package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.client.CommandRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.EntityKits;

/** One key value entity driven with no runtime, as {@link EventSourcedTestKit} drives one. */
public final class KeyValueEntityTestKit<S> {

  private final EntityKits.KeyValue kit;

  private KeyValueEntityTestKit(EntityKits.KeyValue kit) {
    this.kit = kit;
  }

  public static <S> KeyValueEntityTestKit<S> of(Class<? extends KeyValueEntity<S>> entity) {
    return of(entity, "test-entity");
  }

  public static <S> KeyValueEntityTestKit<S> of(
      Class<? extends KeyValueEntity<S>> entity, String entityId) {
    return new KeyValueEntityTestKit<>(EntityKits.keyValue(entity, entityId));
  }

  public <C, I, O> Result<O> call(CommandRef<C, I, O> handler, I input) {
    return kit.call(handler, input);
  }

  public <C, O> Result<O> call(NoArgRef<C, O> handler) {
    return kit.call(handler);
  }

  @SuppressWarnings("unchecked")
  public S state() {
    return (S) kit.state();
  }

  public boolean deleted() {
    return kit.deleted();
  }
}
