package com.thinkmorestupidless.ankka.javasdk.effect;

import com.thinkmorestupidless.ankka.javasdk.Done;
import java.time.Duration;
import java.util.function.Function;

/** Events about to be persisted, waiting for the reply to be chosen. */
public interface PersistBuilder<S, E> {

  /** Reply with a value computed from the state after the events are applied. */
  <R> Effect<R> thenReply(Function<S, R> reply);

  /** Reply with this value. */
  <R> Effect<R> thenReply(R reply);

  /** Reply with the state after the events are applied. */
  Effect<S> thenReplyState();

  /** Reply {@link Done}. */
  Effect<Done> thenDone();

  /** Persist the events, then delete the entity. */
  PersistBuilder<S, E> thenDelete();

  /** Delete the entity once {@code after} passes with no further event. */
  PersistBuilder<S, E> expireAfter(Duration after);
}
