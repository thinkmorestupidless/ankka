package com.thinkmorestupidless.ankka.javasdk.client;

/** One thing a {@link ViewWatch} says. */
public sealed interface WatchEvent<Row> {

  /** A row as it is now, under its key. */
  record Written<Row>(String key, Row row) implements WatchEvent<Row> {}

  /** The row under this key is gone. */
  record Removed<Row>(String key) implements WatchEvent<Row> {}

  /** Every row there was when the watch began has been given. */
  record CaughtUp<Row>() implements WatchEvent<Row> {}

  /**
   * The platform ended the watch, and why: {@code rebuilt}, {@code instance-stopping}, {@code
   * listener-lost} or {@code unread}. Nothing follows it.
   */
  record Ended<Row>(String reason) implements WatchEvent<Row> {}
}
