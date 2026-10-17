package com.thinkmorestupidless.ankka.agent.providers

import com.thinkmorestupidless.ankka.agent.{ChatMessage, ToolResult}

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Rewrites tool call ids for an endpoint that constrains their shape.
 *
 * A session is ankka's, so it holds the ids the model gave, and a session begun on one provider may
 * carry ids another refuses. Each id is mapped from a digest of itself, so a call and its result
 * map to the same id in one request and in every later one, with nothing remembered; the session is
 * never touched, only what is sent.
 */
object ToolCallIds:

  /** The id `id` becomes under `rule`. An id already of the rule's shape is kept. */
  def under(rule: IdRule)(id: String): String = rule match
    case IdRule.Alphanumeric(length) =>
      if id.length == length && id.forall(c => c.isLetterOrDigit && c < 128) then id
      else
        val digest =
          MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8))
        val text = BigInteger(1, digest).toString(36)
        (text * (length / text.length + 1)).take(length)

  /** The same messages with every tool call id, and every result's, rewritten under `rule`. */
  def rewrite(messages: Vector[ChatMessage], rule: IdRule): Vector[ChatMessage] =
    val to = under(rule)
    messages.map {
      case assistant: ChatMessage.Assistant =>
        assistant.copy(toolCalls = assistant.toolCalls.map(call => call.copy(id = to(call.id))))
      case ChatMessage.ToolResults(results) =>
        ChatMessage.ToolResults(results.map((r: ToolResult) => r.copy(callId = to(r.callId))))
      case other => other
    }
