package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.javasdk.client.Invocation;
import java.util.List;
import java.util.concurrent.Flow;

/** One session's calls to the service's agents, each naming its handler by method reference. */
public interface AgentRef {

  /**
   * The handler's answer; {@link ApprovalAwaited} is thrown when the turn waits for a person, and
   * {@code CommandException} when the agent refused the request.
   */
  <A extends Agent, I, O> Invocation<I, O> call(AgentCommandRef<A, I, O> handler);

  <A extends Agent, O> Invocation<Void, O> call(AgentNoArgRef<A, O> handler);

  /** The handler's outcome: its answer, or the approval requests the turn waits on. */
  <A extends Agent, I, O> Invocation<I, AgentOutcome<O>> ask(AgentCommandRef<A, I, O> handler);

  <A extends Agent, O> Invocation<Void, AgentOutcome<O>> ask(AgentNoArgRef<A, O> handler);

  /**
   * The reply as it is written. When the turn waits for a person, the stream fails with {@link
   * ApprovalAwaited} once the text before it has arrived.
   */
  <A extends Agent, I> Flow.Publisher<String> stream(AgentStreamRef<A, I> handler, I input);

  /**
   * Decides one of the session's approval requests, naming the handler that was called. When it was
   * the turn's last, the turn goes on and this answers as the handler's caller would have been.
   */
  <A extends Agent, I, O> AgentOutcome<O> decide(
      AgentCommandRef<A, I, O> handler, Decision decision);

  <A extends Agent, O> AgentOutcome<O> decide(AgentNoArgRef<A, O> handler, Decision decision);

  /** Decides a request of a turn that began as a stream; the rest of the turn is answered whole. */
  <A extends Agent, I> AgentOutcome<String> decideStream(
      AgentStreamRef<A, I> handler, Decision decision);

  /** The session's approval requests that await a decision. */
  List<ApprovalRequest> approvals();
}
