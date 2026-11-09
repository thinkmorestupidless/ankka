package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail;
import com.thinkmorestupidless.ankka.agent.javasdk.Memory;
import com.thinkmorestupidless.ankka.agent.javasdk.StreamEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.Tool;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import com.thinkmorestupidless.ankka.javasdk.annotations.Stream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** The agents page's weather agent: tools, a structured reply, guardrails and a memory window. */
@ComponentId("weather-agent")
public class WeatherAgent extends Agent {

  static final String SYSTEM_MESSAGE = "You are a weather assistant. Use the tools for forecasts.";

  /** What the tools were called with, read by the suite. */
  public static final List<String> TOOL_CALLS = new CopyOnWriteArrayList<>();

  // docs:start forecast
  /** The model's reply, read as JSON into this record; the record is all that is declared. */
  public record Forecast(String location, String summary, int degreesCelsius) {}
  // docs:end forecast

  // docs:start tools
  /** The tools the model may call; each method's name and description are what it decides by. */
  public static final class Tools {

    @Tool(value = "get_weather", description = "Returns the weather forecast for a given city.")
    public String getWeather(
        @Describe("A location or city name.") String location,
        @Describe("Forecast date, in yyyy-MM-dd format.") Optional<String> date) {
      TOOL_CALLS.add("get_weather(" + location + "," + date.orElse("-") + ")");
      if (location.equals("Nowhere")) throw new IllegalArgumentException("unknown location");
      return location + ": 18C, sunny";
    }

    @Tool(value = "current_date", description = "Returns today's date in yyyy-MM-dd format.")
    public String currentDate() {
      TOOL_CALLS.add("current_date()");
      return "2026-09-06";
    }
  }
  // docs:end tools

  @Command("ask")
  public AgentEffect<String> ask(String question) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage(question)
        .tools(Tools.class)
        .thenReply();
  }

  // docs:start structured
  /** Same interaction, but the reply is parsed into a {@code Forecast}. */
  @Command("ask-structured")
  public AgentEffect<Forecast> askStructured(String question) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE + " Reply with JSON.")
        .userMessage(question)
        .thenReply(Forecast.class);
  }
  // docs:end structured

  // docs:start guarded
  /** Guarded, to exercise rejection before a model is called. */
  @Command("guarded")
  public AgentEffect<String> guarded(String question) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage(question)
        .guardrails(Guardrail.maxInputLength(40))
        .thenReply();
  }
  // docs:end guarded

  /** Guarded on the way out by a guardrail of this service's own. */
  @Command("ask-safely")
  public AgentEffect<String> askSafely(String question) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage(question)
        .guardrails(new NoKeys())
        .thenReply();
  }

  // docs:start chat
  /** Streams the reply token by token, tools and all: a stream, not a command. */
  @Stream("chat")
  public StreamEffect chat(String question) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage(question)
        .tools(Tools.class)
        .thenStream();
  }
  // docs:end chat

  // docs:start short-memory
  /** Reads only the last two messages, to exercise the memory window. */
  @Command("ask-with-short-memory")
  public AgentEffect<String> askWithShortMemory(String question) {
    return effects()
        .systemMessage(SYSTEM_MESSAGE)
        .userMessage(question)
        .memory(Memory.session().readLast(2))
        .thenReply();
  }
  // docs:end short-memory
}
