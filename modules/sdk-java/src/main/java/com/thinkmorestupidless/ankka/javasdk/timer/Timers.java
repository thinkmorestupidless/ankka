package com.thinkmorestupidless.ankka.javasdk.timer;

import com.thinkmorestupidless.ankka.javasdk.client.ActionRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgActionRef;
import java.time.Duration;

/**
 * The service's timers: a named call to a timed action, made once its delay has passed, or every
 * period after it. Timers are kept in the database, so they survive a restart, and a name is one
 * timer: scheduling a name again replaces what it would call and when.
 */
public interface Timers {

  <A, I> void schedule(String name, Duration delay, ActionRef<A, I> action, I input);

  <A> void schedule(String name, Duration delay, NoArgActionRef<A> action);

  /** Runs {@code action} once {@code delay} has passed, then every {@code period}, until cancelled. */
  <A, I> void scheduleRecurring(
      String name, Duration delay, Duration period, ActionRef<A, I> action, I input);

  <A> void scheduleRecurring(
      String name, Duration delay, Duration period, NoArgActionRef<A> action);

  /** Removes the timer; nothing when there is none. */
  void cancel(String name);

  boolean exists(String name);
}
