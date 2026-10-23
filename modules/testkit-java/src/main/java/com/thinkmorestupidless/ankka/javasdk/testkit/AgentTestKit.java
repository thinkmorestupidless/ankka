package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentCommandRef;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentNoArgRef;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.AgentKits;

/**
 * One agent driven turn by turn against a {@link TestModelProvider}, with no runtime, cluster or
 * database. A turn is the runtime's: the handler builds its effect, the model is asked, the tools it
 * asks for run and the guardrails check; the session is real session memory, so the next turn reads
 * what this one recorded.
 *
 * <pre>{@code
 * var model = new TestModelProvider().expectText("Oslo reaches 21");
 * var kit = AgentTestKit.of(Assistant.class, model);
 * assertEquals("Oslo reaches 21", kit.call(Assistant::ask, "How warm is Oslo?").reply());
 * }</pre>
 *
 * <p>A tool's calls to other components go to the {@link TestClient} the kit was given, and are
 * refused when none was. Not covered: compaction, an approval's time limit, and MCP servers, which
 * a running service connects at start.
 */
public final class AgentTestKit<A extends Agent> {

  private final AgentKits.AgentKit kit;

  private AgentTestKit(AgentKits.AgentKit kit) {
    this.kit = kit;
  }

  /** The agent, in the session {@code test-session}, answered by {@code model}. */
  public static <A extends Agent> AgentTestKit<A> of(Class<A> agent, TestModelProvider model) {
    return of(agent, model, "test-session", TestClient.of());
  }

  /** The agent in the session {@code sessionId}, its tools' calls answered by {@code client}. */
  public static <A extends Agent> AgentTestKit<A> of(
      Class<A> agent, TestModelProvider model, String sessionId, TestClient client) {
    return new AgentTestKit<>(
        AgentKits.agentKit(agent, model.scalaProvider(), sessionId, client.stubs()));
  }

  /** A turn of the handler {@code handler} names, given {@code input}. */
  public <I, O> AgentResult<O> call(AgentCommandRef<A, I, O> handler, I input) {
    return kit.call(handler, input);
  }

  /** A turn of a handler that takes nothing. */
  public <O> AgentResult<O> call(AgentNoArgRef<A, O> handler) {
    return kit.call(handler);
  }
}
