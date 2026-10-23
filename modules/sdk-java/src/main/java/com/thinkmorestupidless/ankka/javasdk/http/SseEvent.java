package com.thinkmorestupidless.ankka.javasdk.http;

import java.util.Optional;

/**
 * One server-sent event of an {@code @Sse} route. Its data is JSON on one line: {@link #text}
 * encodes a string as JSON, so a leading space or a newline in it survives the protocol; {@link
 * #named} carries JSON the caller wrote.
 */
public final class SseEvent {

  private final String name;
  private final String text;
  private final String json;

  private SseEvent(String name, String text, String json) {
    this.name = name;
    this.text = text;
    this.json = json;
  }

  /** An unnamed event carrying {@code value}, sent as a JSON string. */
  public static SseEvent text(String value) {
    return new SseEvent(null, value, null);
  }

  /** A named event whose data is JSON already; the name and the JSON are each one line. */
  public static SseEvent named(String name, String json) {
    if (name == null || name.isEmpty() || name.contains("\n") || name.contains("\r"))
      throw new IllegalArgumentException("an event name must be one non-empty line, not '" + name + "'");
    if (json.contains("\n") || json.contains("\r"))
      throw new IllegalArgumentException("event '" + name + "': its data must be JSON on one line");
    return new SseEvent(name, null, json);
  }

  public Optional<String> name() {
    return Optional.ofNullable(name);
  }

  /** The text of an unnamed event, before it is encoded. */
  public Optional<String> text() {
    return Optional.ofNullable(text);
  }

  /** The JSON of a named event. */
  public Optional<String> json() {
    return Optional.ofNullable(json);
  }
}
