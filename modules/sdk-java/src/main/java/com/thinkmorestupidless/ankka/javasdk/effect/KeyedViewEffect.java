package com.thinkmorestupidless.ankka.javasdk.effect;

/** The rows a keyed view writes and deletes for one change; built by {@code effects()}. */
public interface KeyedViewEffect<Row> {

  /** This effect's changes, then {@code other}'s; a later change to the same key wins. */
  KeyedViewEffect<Row> and(KeyedViewEffect<Row> other);
}
