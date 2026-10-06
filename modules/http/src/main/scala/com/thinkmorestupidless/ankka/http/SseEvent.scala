package com.thinkmorestupidless.ankka.http

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}

/**
 * One server-sent event, with or without a name.
 *
 * A stream of text sends each part as an unnamed event whose data is the text JSON-encoded, so a
 * reader can tell the model's words from anything else the stream sends. A named event says what it
 * is in its name — an agent turn that stops for approval ends its stream with an event named
 * `approval` — and its data is JSON. `data` is always JSON text: raw text in a `data:` field loses
 * a leading space to the protocol's own rules, and a newline inside it splits the event.
 */
final case class SseEvent private (name: Option[String], data: String)

object SseEvent:

  /** An unnamed event carrying text, as `sse` routes send every part. */
  def text(value: String): SseEvent = SseEvent(None, JsonText.encode(value))

  /**
   * A named event whose data is JSON text already — what a process sends. A name or data that would
   * break the event's framing is refused, which fails the stream rather than corrupting it.
   */
  private[ankka] def named(name: String, json: String): SseEvent =
    if name.isEmpty || name.exists(c => c == '\n' || c == '\r') then
      throw IllegalArgumentException(s"an event name must be one non-empty line, not '$name'")
    else if json.exists(c => c == '\n' || c == '\r') then
      throw IllegalArgumentException(s"event '$name': its data must be JSON on one line")
    else SseEvent(Some(name), json)

  /** A named event carrying a value as JSON. */
  def json[A](name: String, value: A)(using codec: JsonValueCodec[A]): SseEvent =
    if name.isEmpty || name.exists(c => c == '\n' || c == '\r') then
      throw IllegalArgumentException(s"an event name must be one non-empty line, not '$name'")
    else SseEvent(Some(name), writeToString(value))
