package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.agent.javasdk.ModelProvider;
import com.thinkmorestupidless.ankka.agent.javasdk.bridge.ScalaModels;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.AgentKits;
import java.util.List;

/**
 * A model that answers from a script, in order, for the agent test kit or a whole service under
 * {@code AnkkaTestKit}. It fails loudly when the script runs out: a turn that asks once more than
 * the test expected is a failed turn naming the script, never an answer made up.
 *
 * <pre>{@code
 * var model = new TestModelProvider()
 *     .expectToolCall("forecast", "{\"city\":\"Oslo\"}")
 *     .expectText("Oslo reaches 21");
 * }</pre>
 */
public final class TestModelProvider implements ModelProvider {

  private final AgentKits.Script script = AgentKits.script();

  public TestModelProvider() {
    ScalaModels.register(this, script.provider());
  }

  @Override
  public String name() {
    return "test";
  }

  /** The next answer is this text. */
  public TestModelProvider expectText(String text) {
    script.expectText(text);
    return this;
  }

  /** The next answer calls {@code tool} with these arguments, a JSON object. */
  public TestModelProvider expectToolCall(String tool, String argumentsJson) {
    return expectToolCall(tool, argumentsJson, "test-call");
  }

  /** As {@link #expectToolCall(String, String)}, under the call id {@code callId}. */
  public TestModelProvider expectToolCall(String tool, String argumentsJson, String callId) {
    script.expectToolCall(tool, argumentsJson, callId);
    return this;
  }

  /** The next answer is the model's refusal, for {@code reason}. */
  public TestModelProvider expectRefusal(String reason) {
    script.expectRefusal(reason);
    return this;
  }

  /** The next answer completes an autonomous agent's task with this result, as JSON. */
  public TestModelProvider expectCompleteTask(String resultJson) {
    script.expectCompleteTask(resultJson);
    return this;
  }

  /** The next answer completes a task whose result is text. */
  public TestModelProvider expectCompleteTaskText(String text) {
    script.expectCompleteTaskText(text);
    return this;
  }

  /** The next answer fails the task, for {@code reason}. */
  public TestModelProvider expectFailTask(String reason) {
    script.expectFailTask(reason);
    return this;
  }

  /**
   * Answers {@code text} to every request whose last user message contains {@code substring},
   * ahead of the script and without using it up: how a blueprint's steps are answered.
   */
  public TestModelProvider whenUserAsks(String substring, String text) {
    script.whenUserAsks(substring, text);
    return this;
  }

  /** How many times the model was asked. */
  public int callCount() {
    return script.callCount();
  }

  /** Each request the model was asked, as text, oldest first. */
  public List<String> requests() {
    return script.requests();
  }

  /** The last request, as text: what the model was told, tool results included. */
  public String lastRequest() {
    return script.lastRequest();
  }

  /** Forgets the script and what was asked. */
  public void reset() {
    script.reset();
  }

  /** The Scala provider the runtime asks. */
  Object scalaProvider() {
    return script.provider();
  }
}
