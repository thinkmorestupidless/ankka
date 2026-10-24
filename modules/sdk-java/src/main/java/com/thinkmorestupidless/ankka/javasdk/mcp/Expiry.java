package com.thinkmorestupidless.ankka.javasdk.mcp;

import java.time.Duration;
import java.time.Instant;

/** When a client key stops being admitted. There is no default: its issuer says, every time. */
public sealed interface Expiry {

  record After(Duration duration) implements Expiry {}

  record At(Instant instant) implements Expiry {}

  record Never() implements Expiry {}

  static Expiry after(Duration duration) {
    return new After(duration);
  }

  static Expiry at(Instant instant) {
    return new At(instant);
  }

  static Expiry never() {
    return new Never();
  }
}
