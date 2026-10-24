package com.thinkmorestupidless.ankka.javasdk.client;

import org.jspecify.annotations.Nullable;
import java.util.Optional;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;

/**
 * A handler's rejection, as its caller receives it: the {@code effects().error(...)} it returned,
 * with the status its problem carries. An endpoint that lets one pass answers its own caller with
 * the same problem.
 */
public final class CommandException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Problem problem;

  private final @Nullable WorkflowFailure workflowFailure;

  public CommandException(Problem problem) {
    super(problem.detail().isEmpty() ? problem.title() : problem.detail());
    this.problem = problem;
    this.workflowFailure = null;
  }

  /** A refusal of a wait for a workflow's end, saying why the workflow ended without its state. */
  public CommandException(Problem problem, WorkflowFailure workflowFailure) {
    super(problem.detail().isEmpty() ? problem.title() : problem.detail());
    this.problem = problem;
    this.workflowFailure = workflowFailure;
  }

  /** The rejection: its status, title and detail. */
  public Problem problem() {
    return problem;
  }

  /** Why the workflow a wait was for failed or was deleted; empty for any other refusal. */
  public Optional<WorkflowFailure> workflowFailure() {
    return Optional.ofNullable(workflowFailure);
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    return this;
  }
}
