package com.thinkmorestupidless.ankka.proxy.core

import scala.concurrent.duration.FiniteDuration

/**
 * An answer the proxy gives by itself, when neither the process nor a service answered. Each
 * carries `X-Ankka-Answered-By: proxy`, so a reader can tell it from anything the process said, and
 * one line of JSON, `{"error": "<reason>"}`.
 */
final case class Answer(status: Int, reason: String):

  def body: String = s"""{"error":"${Answers.escape(reason)}"}"""

  def headers: Vector[(String, String)] =
    Vector(Answers.Marker, "Content-Type" -> "application/json")

object Answers:

  val MarkerName: String       = "X-Ankka-Answered-By"
  val MarkerValue: String      = "proxy"
  val Marker: (String, String) = MarkerName -> MarkerValue

  /** 403: the connection's certificate names nobody the proxy knows. */
  def refused(reason: String): Answer = Answer(403, reason)

  /** 403: a service the descriptor does not name. */
  def notAdmitted(sender: Sender): Answer =
    Answer(403, s"the caller is not admitted: ${Sender.describe(sender)}")

  /** 400: a call at the calling address with no service in its first segment. */
  def namesNoService(callingUrl: String): Answer =
    Answer(400, s"a call names a service: $callingUrl/<service>/<path>")

  /** 502: the connection ended before a status line arrived. */
  def closedBeforeAnswering(who: String): Answer =
    Answer(502, s"$who closed the connection before answering")

  /** 502: whoever answered the service's address is not the service that was asked for. */
  def notTheServiceAskedFor(project: String, service: String): Answer =
    Answer(502, s"the certificate presented is not the service $project/$service")

  /** 503: nothing accepts a connection on the process's port. */
  val notListening: Answer = Answer(503, "the process is not listening")

  /** 503: a mount's or a call's service has no address. */
  def cannotBeFound(project: String, service: String): Answer =
    Answer(503, s"the service $project/$service cannot be found")

  /**
   * 504: no status line within the proxy's bound, said as the duration says itself (`60 seconds`).
   * A response that has begun is never cut.
   */
  def noAnswerInTime(who: String, within: FiniteDuration): Answer =
    Answer(504, s"$who did not answer within $within")

  /** `text` as the inside of a JSON string. */
  def escape(text: String): String =
    val out = new StringBuilder(text.length + 8)
    text.foreach {
      case '"'           => out.append("\\\"")
      case '\\'          => out.append("\\\\")
      case '\n'          => out.append("\\n")
      case '\r'          => out.append("\\r")
      case '\t'          => out.append("\\t")
      case c if c < 0x20 => out.append(f"\\u${c.toInt}%04x")
      case c             => out.append(c)
    }
    out.toString
