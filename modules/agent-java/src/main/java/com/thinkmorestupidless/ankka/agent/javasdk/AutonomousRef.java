package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.concurrent.Flow;

/** One instance of an autonomous agent, from {@link AgentClients#forAutonomousAgent}. */
public interface AutonomousRef {

  /**
   * Queues tasks for this instance, in order. A task of a type it does not accept refuses the whole
   * call with a {@code CommandException}, queueing none; a task that cannot be taken for another
   * reason (one already assigned, say) is refused alone, and named in the result.
   */
  AssignResult assign(String... taskIds);

  /** Stops taking iterations after the one in hand, until resumed. */
  void suspend();

  void resume();

  /** Ends the instance; its queued tasks are failed. */
  void terminate();

  InstanceState state();

  /** What the instance does from now on, as it does it. A subscription keeps the instance awake. */
  Flow.Publisher<Notification> notifications();

  /** Decides a tool call the instance is waiting on. */
  void decide(Decision decision);
}
