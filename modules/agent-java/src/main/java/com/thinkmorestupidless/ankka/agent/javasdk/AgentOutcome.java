package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.List;

/** What a call to an agent came to: its answer, or the approval requests its turn waits on. */
public sealed interface AgentOutcome<O> {

  record Answered<O>(O value) implements AgentOutcome<O> {}

  record AwaitingApproval<O>(List<ApprovalRequest> requests) implements AgentOutcome<O> {
    public AwaitingApproval {
      requests = List.copyOf(requests);
    }
  }
}
