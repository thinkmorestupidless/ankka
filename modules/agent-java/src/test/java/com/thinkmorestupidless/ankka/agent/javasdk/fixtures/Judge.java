package com.thinkmorestupidless.ankka.agent.javasdk.fixtures;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.ChoiceOption;
import com.thinkmorestupidless.ankka.agent.javasdk.ChoiceQuestion;
import com.thinkmorestupidless.ankka.agent.javasdk.Guardrail;
import com.thinkmorestupidless.ankka.agent.javasdk.Judgment;
import com.thinkmorestupidless.ankka.agent.javasdk.Question;
import com.thinkmorestupidless.ankka.agent.javasdk.Refusal;
import com.thinkmorestupidless.ankka.agent.javasdk.ScoreQuestion;
import com.thinkmorestupidless.ankka.agent.javasdk.YesNoQuestion;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;

/** An agent that judges a text with a question of each kind, and one guarded by a judgment. */
@ComponentId("judge")
public class Judge extends Agent {

  public enum Tone {
    POLITE,
    RUDE
  }

  // docs:start questions
  public static final ChoiceQuestion<Tone> TONE =
      Question.choice(
          "tone",
          "The tone of the text",
          ChoiceOption.of(Tone.POLITE, "polite", "Courteous"),
          ChoiceOption.of(Tone.RUDE, "rude", "Discourteous"));

  public static final ScoreQuestion QUALITY =
      Question.score("quality", "How well written the text is", "poor", "fair", "good");

  public static final YesNoQuestion ABUSIVE =
      Question.yesNo("abusive", "Is the text abusive?").describing("it is", "it is not");
  // docs:end questions

  public record Verdict(String tone, int quality, boolean abusive, String model) {}

  @Command("judge")
  public AgentEffect<Judgment> judge(String text) {
    return effects().judgment().state(text).question(TONE, QUALITY, ABUSIVE).thenReply();
  }

  // docs:start judgment
  @Command("verdict")
  public AgentEffect<Verdict> verdict(String text) {
    return effects()
        .judgment()
        .state(text)
        .question(TONE, QUALITY, ABUSIVE)
        .thenReply(
            j ->
                new Verdict(
                    j.answer(TONE).choice().name(),
                    j.answer(QUALITY).level(),
                    j.answer(ABUSIVE).probability() >= 0.5,
                    j.model()));
  }

  @Command("guarded")
  public AgentEffect<String> guarded(String text) {
    return effects()
        .userMessage(text)
        .guardrails(Guardrail.judged("abuse").onInput(Refusal.ifYes(ABUSIVE, 0.5)))
        .thenReply();
  }
  // docs:end judgment
}
