package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;

/**
 * One step of a blueprint: what it does (asks a worker, has one work with tools, judges, or calls a
 * handler), what it reads (the run's {@code input} or an earlier step's result, by name), how often
 * it repeats, and the shape of its result. Each method returns a new step.
 */
public interface Step {

  static Start named(String name) {
    return AgentBridge.step(name);
  }

  String name();

  /** Reads these: {@code input}, or earlier steps by name. */
  Step reads(String... names);

  /** The shape the step's result must have; text by default. */
  Step result(Shape shape);

  /** Repeats over each item of the array {@code read}, four at a time. */
  Step each(String read);

  /** Repeats over each item of the array {@code read}, {@code limit} at a time. */
  Step each(String read, int limit, boolean keepGoing);

  /** Repeats once for each of these workers. */
  Step overWorkers(String... workers);

  /** Repeats {@code n} times. */
  Step times(int n);

  /** Repeats until {@code verdict} passes the result, {@code rounds} times at most. */
  Step until(Verdict verdict, int rounds);

  /** A step with a name and nothing it does yet. */
  interface Start {

    /** Asks {@code worker} for an answer in one turn. */
    Step ask(String worker);

    /** Has {@code worker} work with its tools until it answers. */
    Step work(String worker);

    /** Asks the registered judgment questions of what the step reads. */
    Step judge(String... questions);

    /** Calls the registered handler with what the step reads. */
    Step call(String handler);

    /** Asks {@code worker} once for each item of the array {@code over}. */
    Step forEach(String worker, String over);

    /** Asks each of {@code workers} the same thing. */
    Step gather(String... workers);

    /** Asks {@code worker} the same thing {@code times} times. */
    Step gather(String worker, int times);

    /** Asks {@code drafter}, then {@code verdict}, again until it passes or rounds run out. */
    Step critique(String drafter, Verdict verdict, int rounds);
  }
}
