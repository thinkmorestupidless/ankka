package com.thinkmorestupidless.ankka.agent.javasdk.fixtures;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail;
import com.thinkmorestupidless.ankka.agent.javasdk.StreamEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.Tool;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import com.thinkmorestupidless.ankka.javasdk.annotations.Stream;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An agent with a tool, a guardrail, a tool that waits for a person, a stream and a plan. */
@ComponentId("assistant")
public class Assistant extends Agent {

  /** What the booking tool booked, read by the suite. */
  public static final List<String> BOOKED = new CopyOnWriteArrayList<>();

  public record CityQuery(@Describe("the city to look up") String city) {}

  public record Forecast(String city, int high) {}

  public record Plan(String city, List<String> steps) {}

  public static final class WeatherTools {
    @Tool(value = "forecast", description = "The forecast for a city")
    public Forecast forecast(CityQuery query) {
      return new Forecast(query.city(), 21);
    }
  }

  public static final class BookingTools {
    @Tool(
        value = "book",
        description = "Books a table",
        requiresApproval = true,
        approvalWithin = "PT10M")
    public String book(@Describe("where to book") String restaurant, int people) {
      BOOKED.add(restaurant);
      return "booked " + restaurant + " for " + people;
    }
  }

  @Command("ask")
  public AgentEffect<String> ask(String question) {
    return effects()
        .systemMessage("You answer questions about the weather.")
        .userMessage(question)
        .tools(WeatherTools.class)
        .guardrails(Guardrail.forbidding("no-secrets", "(?i)secret"))
        .thenReply();
  }

  @Command("book")
  public AgentEffect<String> book(String request) {
    return effects().userMessage(request).tools(new BookingTools()).thenReply();
  }

  @Command("plan")
  public AgentEffect<Plan> plan(String city) {
    return effects()
        .systemMessage("Answer with JSON: {\"city\": ..., \"steps\": [...]}")
        .userMessage("Plan a day in " + city)
        .thenReply(Plan.class);
  }

  @Command("refuse")
  public AgentEffect<String> refuse(String why) {
    return effects().error(Problem.notFound(why));
  }

  @Stream("chat")
  public StreamEffect chat(String text) {
    return effects().userMessage(text).thenStream();
  }
}
