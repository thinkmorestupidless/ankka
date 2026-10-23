package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.bridge.Effects;
import com.thinkmorestupidless.ankka.javasdk.bridge.TimerCell;
import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffects;

/**
 * What a timer calls when it is due. Each {@code @Action("name")} method takes one input or none and
 * returns a {@code TimedActionEffect}: {@code done()} completes the timer, {@code error(...)} or an
 * exception has it retried. A timer is delivered at least once, so an action must be safe to run
 * again; {@link TimerContext#dueTime()} is the same for every attempt at one due time.
 */
public abstract class TimedAction {

  private final TimerCell cell = TimerCell.take();

  protected TimedAction() {}

  /** The timer being run: its name, due time and attempts, and what it may call. */
  protected final TimerContext context() {
    return cell.context();
  }

  protected final TimedActionEffects effects() {
    return Effects.timedAction();
  }
}
