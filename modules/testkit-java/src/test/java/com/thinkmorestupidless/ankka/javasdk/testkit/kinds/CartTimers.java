package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.Action;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.TimedAction;
import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffect;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Timed actions that record each run, for the suite to read. */
@ComponentId("cart-timers")
public class CartTimers extends TimedAction {

  public record Run(String timer, String input, int previousAttempts) {}

  public static final Queue<Run> runs = new ConcurrentLinkedQueue<>();

  private void record(String input) {
    runs.add(new Run(context().timerName(), input, context().previousAttempts()));
  }

  @Action("expire")
  public TimedActionEffect expire(String cartId) {
    record(cartId);
    return effects().done();
  }

  @Action("tick")
  public TimedActionEffect tick() {
    record("tick");
    return effects().done();
  }

  /** Fails its first attempt, so the timer is retried. */
  @Action("flaky")
  public TimedActionEffect flaky() {
    record("flaky");
    return context().previousAttempts() == 0 ? effects().error("not yet") : effects().done();
  }

  /** Schedules what comes next through its own context. */
  @Action("chain")
  public TimedActionEffect chain(String next) {
    record("chain");
    context().timers().schedule(context().timerName() + "-next", Duration.ZERO, CartTimers::expire, next);
    return effects().done();
  }
}
