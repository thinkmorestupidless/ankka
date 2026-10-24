package com.thinkmorestupidless.ankka.javasdk.problems;

import java.util.List;

/**
 * Every problem with a service's declarations, one per line in registration order. Thrown by {@code
 * start()} before anything listens, so a service with one is never half running.
 */
public final class ServiceProblems extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<DeclarationProblem> problems;

  public ServiceProblems(List<DeclarationProblem> problems) {
    super(message(problems));
    this.problems = List.copyOf(problems);
  }

  /** The problems, in the order the classes were registered. */
  public List<DeclarationProblem> problems() {
    return problems;
  }

  private static String message(List<DeclarationProblem> problems) {
    StringBuilder sb = new StringBuilder("the service cannot start:");
    for (DeclarationProblem p : problems) sb.append("\n  - ").append(p);
    return sb.toString();
  }
}
