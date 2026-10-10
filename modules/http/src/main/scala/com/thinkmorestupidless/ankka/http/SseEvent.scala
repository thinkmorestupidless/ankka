package com.thinkmorestupidless.ankka.http

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToString}
import com.thinkmorestupidless.ankka.core.{CommandError, WorkflowEnd}

import scala.concurrent.duration.FiniteDuration

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

  // ── A wait for a workflow's end, as events ─────────────────────────────────

  /**
   * The wait goes on: sent while it does, so the connection is never quiet for its idle timeout.
   */
  val heartbeat: SseEvent = SseEvent(Some("heartbeat"), "{}")

  /** The workflow completed with this state, JSON already. The stream's last event. */
  def ended(json: String): SseEvent = named("ended", json)

  /**
   * The workflow failed, was deleted, or the wait was refused: the code and the message, and a
   * failed workflow's step, reason and whether it was deleted. The stream's last event.
   */
  def failed(error: CommandError): SseEvent =
    val end = WorkflowEnd.failure(error)
    val fields = Vector(
      Some(s"\"code\":${JsonText.encode(error.code.toString)}"),
      Some(s"\"message\":${JsonText.encode(error.message)}"),
      end.flatMap(_.step).map(step => s"\"step\":${JsonText.encode(step)}"),
      end.map(f => s"\"reason\":${JsonText.encode(f.reason)}"),
      end.map(f => s"\"deleted\":${f.deleted}")
    ).flatten
    SseEvent(Some("failed"), fields.mkString("{", ",", "}"))

  /** The caller's time passed first; the workflow runs on. The stream's last event. */
  def timedOut(timeout: FiniteDuration): SseEvent =
    SseEvent(
      Some("timed-out"),
      s"""{"timeout":${JsonText.encode(java.time.Duration.ofMillis(timeout.toMillis).toString)}}"""
    )

  /** Whether `event` ends a wait's stream. */
  private[http] def endsAWait(event: SseEvent): Boolean =
    event.name.exists(Set("ended", "failed", "timed-out"))

  /** A named event carrying a value as JSON. */
  def json[A](name: String, value: A)(using codec: JsonValueCodec[A]): SseEvent =
    if name.isEmpty || name.exists(c => c == '\n' || c == '\r') then
      throw IllegalArgumentException(s"an event name must be one non-empty line, not '$name'")
    else SseEvent(Some(name), writeToString(value))
