package com.thinkmorestupidless.ankka.javasdk.effect;

/**
 * An effect that changes nothing: a reply or an error. A {@code @Query} handler returns one, so
 * "a query cannot persist" is checked by the compiler.
 */
public interface ReadOnlyEffect<R> extends Effect<R> {}
