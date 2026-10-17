package com.thinkmorestupidless.ankka.agent.providers

/** A shape an endpoint constrains tool call ids to. */
enum IdRule:
  /** Letters and digits only, exactly `length` of them: Mistral's rule. */
  case Alphanumeric(length: Int)

/**
 * What an endpoint is known to accept or need, beyond its protocol.
 *
 * A preset carries the quirks of the endpoint it names; a provider configured by hand gets those of
 * a bare endpoint of its protocol. Nothing here ever fails a call: a setting an endpoint does not
 * accept is withheld.
 *
 * @param reasoningEfforts
 *   the reasoning effort values the endpoint accepts, lowest first, or `None` when nothing is
 *   known: then an effort is sent only when one was asked for, as asked
 * @param acceptsTemperature
 *   whether a sampling temperature is sent at all
 * @param streamUsage
 *   whether a stream asks the endpoint to report usage at its end (Chat Completions)
 * @param toolCallIds
 *   a shape the endpoint constrains tool call ids to, which ids are rewritten into on the way out
 * @param sendsThinking
 *   on Anthropic's protocol, whether adaptive thinking and an effort are sent: Anthropic's own
 *   fields, which a compatible server may refuse
 */
final case class Quirks(
    reasoningEfforts: Option[Vector[String]] = None,
    acceptsTemperature: Boolean = true,
    streamUsage: Boolean = true,
    toolCallIds: Option[IdRule] = None,
    sendsThinking: Boolean = false
)

object Quirks:

  /** A bare endpoint of OpenAI's protocol: only what Chat Completions defines. */
  val compatibleOpenAi: Quirks = Quirks()

  /** A bare endpoint of Anthropic's protocol: no fields newer than the protocol's core. */
  val compatibleAnthropic: Quirks = Quirks(acceptsTemperature = false)

  /** OpenAI's own API: reasoning models, which refuse a sampling temperature. */
  val openAi: Quirks = Quirks(
    reasoningEfforts = Some(Vector("none", "minimal", "low", "medium", "high", "xhigh", "max")),
    acceptsTemperature = false
  )

  /** Anthropic's own API: adaptive thinking and effort, and no sampling parameters. */
  val anthropic: Quirks = Quirks(acceptsTemperature = false, sendsThinking = true)

  /** Every reasoning effort word, lowest first, as the OpenAI protocol spells them. */
  val EffortOrder: Vector[String] =
    Vector("none", "minimal", "low", "medium", "high", "xhigh", "max")
