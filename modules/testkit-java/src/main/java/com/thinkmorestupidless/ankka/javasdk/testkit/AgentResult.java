package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.agent.javasdk.ApprovalRequest;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import java.util.List;

/**
 * What one turn of an agent came to in the agent test kit: the answer, the tool calls it stopped
 * for, or the refusal the caller would be answered with.
 */
public interface AgentResult<O> {

  /**
   * The answer, read through the handler's own serializer; throws {@link AssertionError} when the
   * turn was refused or waits for a decision.
   */
  O reply();

  boolean isError();

  /** The refusal's message; throws {@link AssertionError} when the turn was not refused. */
  String error();

  /** The refusal as the caller would see it; throws {@link AssertionError} when it was not. */
  Problem problem();

  /** Whether the turn stopped before a tool that needs a person's decision. */
  boolean isAwaiting();

  /** The tool calls waiting for a decision; empty when the turn answered or was refused. */
  List<ApprovalRequest> awaiting();

  /** How many messages the turn added to the session. */
  int messages();
}
