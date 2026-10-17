package com.thinkmorestupidless.ankka.agent.providers

/**
 * One of the two wire protocols ankka speaks to a model in.
 *
 * Every model provider is one of them pointed at an address: the market has collapsed onto these
 * two, and a vendor that speaks one needs no adapter of its own.
 */
enum ModelProtocol(val wireName: String):
  /** Anthropic's Messages protocol. */
  case Anthropic extends ModelProtocol("anthropic")

  /** OpenAI's protocol, in one of its two shapes. */
  case OpenAi extends ModelProtocol("openai")

object ModelProtocol:
  def fromWire(value: String): Option[ModelProtocol] = values.find(_.wireName == value)

/** The two shapes of OpenAI's protocol. */
enum Shape(val wireName: String):
  /**
   * The Responses API: what OpenAI's own current models need to call tools while they reason. ankka
   * speaks it without state on the vendor's side, handing reasoning back within a turn itself.
   */
  case Responses extends Shape("responses")

  /** Chat Completions: what every compatible endpoint serves. */
  case ChatCompletions extends Shape("chat-completions")

object Shape:
  def fromWire(value: String): Option[Shape] = values.find(_.wireName == value)
