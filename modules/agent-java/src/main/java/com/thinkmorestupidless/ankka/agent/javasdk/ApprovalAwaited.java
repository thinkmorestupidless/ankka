package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.List;

/** Thrown by {@link AgentRef#call} when the turn waits for a person's decision. */
public final class ApprovalAwaited extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final List<ApprovalRequest> requests;

  public ApprovalAwaited(List<ApprovalRequest> requests) {
    super("approval awaited for " + requests.stream().map(ApprovalRequest::tool).toList());
    this.requests = List.copyOf(requests);
  }

  public List<ApprovalRequest> requests() {
    return requests;
  }
}
