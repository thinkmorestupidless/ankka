package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given

/** A structured answer, to exercise `thenReplyAs`. */
final case class Forecast(location: String, summary: String, degreesCelsius: Int)

// Top-level, not in the companion: `thenReplyAs` is called from inside the agent class,
// which does not see the companion object's givens.
given JsonValueCodec[Forecast] = Codecs.make[Forecast]
given Serializer[Forecast]     = Codecs.serializer[Forecast]("forecast")

/**
 * An agent with a tool, a system message and a structured variant.
 *
 * Deliberately ordinary: the point of the tests around it is that ankka does the work, so the agent
 * itself should look like something a developer would actually write.
 */
final class WeatherAgent(context: AgentContext) extends Agent:

  /** Proves context injection: an agent knows which session it is serving. */
  def whoAmI: Effect[String] =
    effects
      .systemMessage("Identify yourself.")
      .userMessage(s"You are ${context.componentId} serving session ${context.sessionId}.")
      .memory(MemoryProvider.none)
      .thenReply()

  def ask(question: String): Effect[String] =
    if question.isBlank then effects.error("ask me something", ErrorCode.BadRequest)
    else
      effects
        .systemMessage(WeatherAgent.SystemMessage)
        .userMessage(question)
        .tools(WeatherAgent.getWeather, WeatherAgent.currentDate)
        .thenReply()

  /** Same interaction, but the reply is parsed into a `Forecast`. */
  def askStructured(question: String): Effect[Forecast] =
    effects
      .systemMessage(WeatherAgent.SystemMessage + " Reply with JSON.")
      .userMessage(question)
      .thenReplyAs[Forecast]

  /** Streams the reply token by token, tools and all. */
  def chat(question: String): StreamEffect =
    effects
      .systemMessage(WeatherAgent.SystemMessage)
      .userMessage(question)
      .tools(WeatherAgent.getWeather)
      .thenStream()

  /** Streams, but rejected up front — so the caller sees a failed stream, not a hang. */
  def chatGuarded(question: String): StreamEffect =
    effects
      .systemMessage(WeatherAgent.SystemMessage)
      .userMessage(question)
      .guardrails(Guardrail.maxInputLength(20))
      .thenStream()

  /** No memory: a one-shot classification should not be coloured by the conversation. */
  def classify(question: String): Effect[String] =
    effects
      .systemMessage("Classify the request in one word.")
      .userMessage(question)
      .memory(MemoryProvider.none)
      .thenReply()

  /** Guarded, to exercise rejection before a model is called. */
  def guarded(question: String): Effect[String] =
    effects
      .systemMessage(WeatherAgent.SystemMessage)
      .userMessage(question)
      .guardrails(Guardrail.maxInputLength(40))
      .thenReply()

  /** Reads only the last two messages, to exercise the memory window. */
  def askWithShortMemory(question: String): Effect[String] =
    effects
      .systemMessage(WeatherAgent.SystemMessage)
      .userMessage(question)
      .memory(MemoryProvider.limitedWindow.readLast(2))
      .thenReply()

object WeatherAgent extends Agent.Companion[WeatherAgent](ComponentId("weather-agent")):

  val SystemMessage = "You are a concise weather assistant."

  /** Records what the tool was asked, so tests can assert the model's arguments arrived. */
  val toolCalls: java.util.concurrent.ConcurrentLinkedQueue[String] =
    java.util.concurrent.ConcurrentLinkedQueue[String]()

  val getWeather = FunctionTool
    .named("get_weather")
    .describedAs("Returns the weather forecast for a given city.")
    .param[String]("location", "A location or city name.")
    .param[Option[String]]("date", "Forecast date, in yyyy-MM-dd format.")
    .handle { (location, date) =>
      toolCalls.add(s"get_weather($location,${date.getOrElse("-")})"): Unit
      if location == "Nowhere" then throw RuntimeException("unknown location")
      else s"$location: 18C, sunny"
    }

  val currentDate = FunctionTool
    .named("current_date")
    .describedAs("Returns today's date in yyyy-MM-dd format.")
    .handle { () =>
      toolCalls.add("current_date()"): Unit
      "2026-09-06"
    }

  def create(context: AgentContext) = new WeatherAgent(context)

  val whoAmI             = command("who-am-i")(_.whoAmI)
  val ask                = command("ask")(_.ask)
  val chat               = stream("chat")(_.chat)
  val chatGuarded        = stream("chat-guarded")(_.chatGuarded)
  val askStructured      = command("ask-structured")(_.askStructured)
  val classify           = command("classify")(_.classify)
  val guarded            = command("guarded")(_.guarded)
  val askWithShortMemory = command("ask-short-memory")(_.askWithShortMemory)
