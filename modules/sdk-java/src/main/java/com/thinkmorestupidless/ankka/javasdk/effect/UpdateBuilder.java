package com.thinkmorestupidless.ankka.javasdk.effect;

import com.thinkmorestupidless.ankka.javasdk.Done;
import java.time.Duration;
import java.util.function.Function;

/** A state about to be written, waiting for the reply to be chosen. */
public interface UpdateBuilder<S> {

  <R> Effect<R> thenReply(Function<S, R> reply);

  <R> Effect<R> thenReply(R reply);

  Effect<S> thenReplyState();

  Effect<Done> thenDone();

  /** Delete the entity once {@code after} passes with no further update. */
  UpdateBuilder<S> expireAfter(Duration after);
}
