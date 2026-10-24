package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.client.CommandRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.EntityKits;
import java.util.List;

/**
 * One event sourced entity driven with no runtime, cluster or database: each command's input and
 * reply cross the entity's own serializers, and its effect is reduced by the function the runtime
 * reduces it with. A call to another component from a handler is refused.
 */
public final class EventSourcedTestKit<S, E> {

  private final EntityKits.EventSourced kit;

  private EventSourcedTestKit(EntityKits.EventSourced kit) {
    this.kit = kit;
  }

  /** The entity, empty, as {@code test-entity}. */
  public static <S, E> EventSourcedTestKit<S, E> of(
      Class<? extends EventSourcedEntity<S, E>> entity) {
    return of(entity, "test-entity");
  }

  /** The entity, empty, under {@code entityId}. */
  public static <S, E> EventSourcedTestKit<S, E> of(
      Class<? extends EventSourcedEntity<S, E>> entity, String entityId) {
    return new EventSourcedTestKit<>(EntityKits.eventSourced(entity, entityId, List.of()));
  }

  /** The entity recovered from {@code events}, as if it had recorded them. */
  public static <S, E> EventSourcedTestKit<S, E> withEvents(
      Class<? extends EventSourcedEntity<S, E>> entity, List<? extends E> events) {
    return new EventSourcedTestKit<>(EntityKits.eventSourced(entity, "test-entity", events));
  }

  /**
   * A replicated entity's copy as its home, at {@code installation}: every command runs, and a
   * handover or a change to the replication set is reported on the result.
   */
  public static <S, E> EventSourcedTestKit<S, E> home(
      Class<? extends EventSourcedEntity<S, E>> entity, String entityId, String installation) {
    return new EventSourcedTestKit<>(
        EntityKits.eventSourced(entity, entityId, List.of(), null, installation));
  }

  /**
   * A replicated entity's copy as a follower of {@code home}, at {@code installation}: a
   * command that may persist is refused 421, and a query is answered from what {@link
   * #replay(String, List)} gave it.
   */
  public static <S, E> EventSourcedTestKit<S, E> follower(
      Class<? extends EventSourcedEntity<S, E>> entity,
      String entityId,
      String home,
      String installation) {
    return new EventSourcedTestKit<>(
        EntityKits.eventSourced(entity, entityId, List.of(), home, installation));
  }

  /** Gives the copy {@code events}, as written at its own installation. */
  public void replay(List<? extends E> events) {
    kit.replay(null, events);
  }

  /** Gives the copy {@code events}, as written at {@code installation}. */
  public void replay(String installation, List<? extends E> events) {
    kit.replay(installation, events);
  }

  /** Sends a command to the handler {@code handler} names. */
  public <C, I, O> Result<O> call(CommandRef<C, I, O> handler, I input) {
    return kit.call(handler, input);
  }

  /** Sends a command that takes nothing. */
  public <C, O> Result<O> call(NoArgRef<C, O> handler) {
    return kit.call(handler);
  }

  /** The state now. */
  @SuppressWarnings("unchecked")
  public S state() {
    return (S) kit.state();
  }

  /** Every event persisted since the kit was made, those it was given included. */
  @SuppressWarnings("unchecked")
  public List<E> events() {
    return (List<E>) kit.events();
  }

  /** Whether a command deleted the entity. */
  public boolean deleted() {
    return kit.deleted();
  }

  /** Drops the instance and recovers a new one from every event, as a restarted service does. */
  public void restart() {
    kit.restart();
  }
}
