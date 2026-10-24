package com.thinkmorestupidless.ankka.javasdk.effect;

import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import java.util.List;

/** The effects an event sourced entity's handlers return. */
public interface EventSourcedEffects<S, E> {

  /** Persist one event, then choose the reply. */
  PersistBuilder<S, E> persist(E event);

  /**
   * Persist two events in order, then choose the reply. Overloads rather than varargs: a generic
   * varargs parameter makes every caller's compiler warn of heap pollution.
   */
  PersistBuilder<S, E> persist(E first, E second);

  PersistBuilder<S, E> persist(E first, E second, E third);

  /** Persist the events in order, then choose the reply. */
  PersistBuilder<S, E> persistAll(List<? extends E> events);

  /** Delete the entity, with no event of its own. */
  PersistBuilder<S, E> deleteEntity();

  /**
   * Hand a replicated entity's home to {@code installation}, which must be in its replication
   * set, with no event of its own. The handover is recorded once the new home holds everything
   * before it; until then this home goes on as home.
   */
  PersistBuilder<S, E> handOverTo(String installation);

  /** Add {@code installation} to a replicated entity's replication set. */
  PersistBuilder<S, E> replicateTo(String installation);

  /** Take {@code installation} out of a replicated entity's replication set; it keeps its copy. */
  PersistBuilder<S, E> stopReplicatingTo(String installation);

  /** Persist nothing and send no reply: the caller is answered by no one and times out. */
  <R> ReadOnlyEffect<R> noReply();

  /** Reply without persisting. */
  <R> ReadOnlyEffect<R> reply(R reply);

  /** Reject the command: nothing is persisted, and the caller receives a 400 with this detail. */
  <R> ReadOnlyEffect<R> error(String message);

  /** Reject the command with a status of the handler's choosing. */
  <R> ReadOnlyEffect<R> error(Problem problem);
}
