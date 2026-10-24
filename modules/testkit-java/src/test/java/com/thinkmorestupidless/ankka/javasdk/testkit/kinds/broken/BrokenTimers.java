package com.thinkmorestupidless.ankka.javasdk.testkit.kinds.broken;

import com.thinkmorestupidless.ankka.javasdk.annotations.Action;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.TimedAction;
import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffect;

/** Timed actions declared wrongly, each refused at start. */
public final class BrokenTimers {
  private BrokenTimers() {}

  /** Two actions with one name, one of two parameters, and one not marked. */
  @ComponentId("misshapen-actions")
  public static class Misshapen extends TimedAction {
    @Action("same")
    public TimedActionEffect one() {
      return effects().done();
    }

    @Action("same")
    public TimedActionEffect two() {
      return effects().done();
    }

    @Action("pair")
    public TimedActionEffect pair(String a, String b) {
      return effects().done();
    }

    public TimedActionEffect unmarked() {
      return effects().done();
    }
  }

  /** Not one action. */
  @ComponentId("no-actions")
  public static class Empty extends TimedAction {}
}
