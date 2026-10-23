package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.http.Problem;

/**
 * A handler's rejection, as its caller receives it: the {@code effects().error(...)} it returned,
 * with the status its problem carries. An endpoint that lets one pass answers its own caller with
 * the same problem.
 */
public final class CommandException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Problem problem;

  public CommandException(Problem problem) {
    super(problem.detail().isEmpty() ? problem.title() : problem.detail());
    this.problem = problem;
  }

  /** The rejection: its status, title and detail. */
  public Problem problem() {
    return problem;
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
