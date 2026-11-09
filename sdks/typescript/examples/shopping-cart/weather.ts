// The weather agent of the documentation: a structured reply, two tools, a guardrail declared by hand.
// The sidecar runs the loop; this process declares the plan and runs the tools it is asked to.
import { Agent, command, guardrail, s, tool, type Infer } from "ankka"

// docs:start forecast
export const Forecast = s.record("Forecast", { location: s.string, summary: s.string, degreesCelsius: s.int })
export type Forecast = Infer<typeof Forecast>
// docs:end forecast

// docs:start no-keys
export const noKeys = guardrail("no-keys", (stage, text) =>
  stage === "output" && text.includes("sk-") ? "the reply contains what looks like a key" : null,
)
// docs:end no-keys

/** What the tools were asked, for a test to read. */
export const toolCalls: string[] = []

// docs:start tools
const GetWeather = s.record("GetWeather", { location: s.string, date: s.option(s.string) })
const NoArguments = s.record("NoArguments", {})

export class WeatherAgent extends Agent {
  static readonly componentId = "weather"
  static readonly systemMessage = "You are a weather assistant. Use your tools, then answer in one sentence."

  static readonly tools = {
    // The model sees the description and the input's JSON Schema: `location` required, `date` not.
    getWeather: tool(
      "get_weather",
      "Returns the weather forecast for a given city. Takes a location or city name, and a date in yyyy-MM-dd format.",
      GetWeather,
      (_a: WeatherAgent, { location, date }) => {
        toolCalls.push(`get_weather(${location},${date ?? "-"})`)
        if (location === "Nowhere") throw new Error("unknown location")
        return `${location}: 18C, sunny`
      },
    ),
    currentDate: tool("current_date", "Returns today's date in yyyy-MM-dd format.", NoArguments, () => {
      toolCalls.push("current_date()")
      return "2026-09-06"
    }),
  }
  // docs:end tools

  static readonly handlers = {
    ask: command("ask", s.string, s.string, (a: WeatherAgent, question) => a.ask(question)),
    askStructured: command("ask-structured", s.string, Forecast, (a: WeatherAgent, question) => a.askStructured(question)),
    guarded: command("guarded", s.string, s.string, (a: WeatherAgent, question) => a.guarded(question)),
  }

  ask(question: string) {
    return this.effects
      .systemMessage(WeatherAgent.systemMessage)
      .userMessage(question)
      .tools("get_weather", "current_date")
      .guardrails("no-keys")
      .thenReply()
  }

  // docs:start structured
  /** Same interaction, but the reply is parsed into a `Forecast`. */
  askStructured(question: string) {
    return this.effects
      .systemMessage(WeatherAgent.systemMessage + " Reply with JSON.")
      .userMessage(question)
      .thenReplyJson<Forecast>()
  }
  // docs:end structured

  // docs:start guarded
  // No guardrail is provided ready-made: each is a named check, declared here and named in the plan.
  static readonly guardrails = {
    maxInputLength: guardrail("max-input-length", (stage, text) =>
      stage === "input" && text.length > 40 ? `the input is longer than 40 characters` : null,
    ),
    noKeys,
  }

  /** Guarded, to exercise rejection before a model is called. */
  guarded(question: string) {
    return this.effects
      .systemMessage(WeatherAgent.systemMessage)
      .userMessage(question)
      .guardrails("max-input-length")
      .thenReply()
  }
  // docs:end guarded
}
