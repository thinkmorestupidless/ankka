package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffect;
import java.io.Serializable;

/**
 * A timed action's handler that takes an input, named by a method reference: {@code
 * CartTimers::expire}. It is resolved to the {@code @Action} name the timer stores, so renaming the
 * method leaves timers already scheduled working.
 */
@FunctionalInterface
public interface ActionRef<A, I> extends Serializable {
  TimedActionEffect apply(A action, I input) throws Exception;
}
