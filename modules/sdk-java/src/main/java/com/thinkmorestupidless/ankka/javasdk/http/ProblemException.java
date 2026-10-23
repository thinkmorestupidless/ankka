package com.thinkmorestupidless.ankka.javasdk.http;

/** Thrown from a route handler to answer with {@code problem}'s status and detail. */
public final class ProblemException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Problem problem;

  public ProblemException(Problem problem) {
    super(problem.detail());
    this.problem = problem;
  }

  public Problem problem() {
    return problem;
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
