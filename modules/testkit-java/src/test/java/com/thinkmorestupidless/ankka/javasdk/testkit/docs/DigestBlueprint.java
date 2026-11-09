package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Blueprint;
import com.thinkmorestupidless.ankka.agent.javasdk.Schedule;
import com.thinkmorestupidless.ankka.agent.javasdk.Shape;
import com.thinkmorestupidless.ankka.agent.javasdk.Step;
import com.thinkmorestupidless.ankka.agent.javasdk.Worker;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

/** A blueprint on a weekly schedule, whose input is the period each run covers. */
public final class DigestBlueprint {

  private DigestBlueprint() {}

  // docs:start schedule
  /** The input a scheduled run is given: the period it covers and the due times it stands for. */
  public static final Shape PERIOD =
      Shape.obj(
          Map.of(
              "from", Shape.string(),
              "to", Shape.string(),
              "dueTimes", Shape.arr(Shape.string())));

  public static final Blueprint DIGEST =
      Blueprint.named("digest")
          .input(PERIOD)
          .worker(Worker.named("editor").instructions("You write the week's digest.").budget(2))
          .step(Step.named("write").ask("editor").reads("input").result(GuideBlueprints.TEXT))
          .schedule(
              Schedule.weekly(DayOfWeek.SUNDAY, LocalTime.of(20, 0), ZoneId.of("Europe/London"))
                  .perMissedPeriod());
  // docs:end schedule
}
