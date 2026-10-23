package com.thinkmorestupidless.ankka.javasdk.effect;

import com.thinkmorestupidless.ankka.javasdk.http.Problem;

/** The effects a key value entity's handlers return. */
public interface KeyValueEffects<S> {

  /** Replace the state, then choose the reply. */
  UpdateBuilder<S> updateState(S state);

  /** Delete the state, then choose the reply. */
  UpdateBuilder<S> deleteEntity();

  <R> ReadOnlyEffect<R> reply(R reply);

  <R> ReadOnlyEffect<R> error(String message);

  <R> ReadOnlyEffect<R> error(Problem problem);
}
