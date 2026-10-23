package com.thinkmorestupidless.ankka.javasdk.effect;

import com.thinkmorestupidless.ankka.javasdk.http.Problem;

/** The effects a timed action returns. */
public interface TimedActionEffects {

  /** The timer is complete; a recurring timer waits for its next period. */
  TimedActionEffect done();

  /** The run failed and the timer is retried. */
  TimedActionEffect error(String message);

  TimedActionEffect error(Problem problem);
}
