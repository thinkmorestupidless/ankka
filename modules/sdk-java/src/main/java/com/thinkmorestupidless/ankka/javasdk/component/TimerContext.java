package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients;
import com.thinkmorestupidless.ankka.javasdk.timer.Timers;
import java.time.Clock;
import java.time.Instant;

/** What a timed action knows while a timer runs it. */
public interface TimerContext {

  /** The timer's name, as it was scheduled. */
  String timerName();

  /** How many attempts at this due time failed before this one. */
  int previousAttempts();

  /** The time this run is for; a retry is told the same. */
  Instant dueTime();

  /** The clock the timer fired by, to read as now so a test that moves it moves the action too. */
  Clock clock();

  /** The service's timers, to schedule what comes next. */
  Timers timers();

  ComponentClient componentClient();

  ServiceClients services();
}
