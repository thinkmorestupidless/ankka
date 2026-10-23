package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.javasdk.spi.ServiceModule;
import java.time.Duration;
import java.util.function.Function;

/**
 * The agents and autonomous agents of a service, and what they share: the model an effect that names
 * none is answered by, how long a model may take, whether long sessions are compacted, who answers a
 * judgment, and what blueprints may name. Each method returns a new module; the one given to the
 * service is the one used.
 */
public interface AgentModule extends ServiceModule {

  /** The model every effect that names none is answered by, waited for two minutes at most. */
  AgentModule withModelProvider(ModelProvider provider);

  /** As {@link #withModelProvider(ModelProvider)}, waited for {@code timeout} at most. */
  AgentModule withModelProvider(ModelProvider provider, Duration timeout);

  /**
   * Replaces a long session's oldest messages with a summary the default model writes, once its
   * text passes {@code maxHistoryBytes}, keeping the last {@code keepRecentMessages} as they were.
   * Needs a default model.
   */
  AgentModule withCompaction(int maxHistoryBytes, int keepRecentMessages);

  /** As {@link #withCompaction(int, int)}, the summary written by {@code summariser}. */
  AgentModule withCompaction(int maxHistoryBytes, int keepRecentMessages, ModelProvider summariser);

  /**
   * The provider judgments and judged guardrails are answered by, waited for five seconds at most:
   * a judgment is meant to be quick, and the limit bounds a provider's retries too.
   */
  AgentModule withJudgments(JudgmentProvider provider);

  /** As {@link #withJudgments(JudgmentProvider)}, waited for {@code timeout} at most. */
  AgentModule withJudgments(JudgmentProvider provider, Duration timeout);

  /**
   * What this service's blueprints may name: tools, models, guardrails, questions and handlers,
   * built once when the service starts from what a tool may need to call.
   */
  AgentModule withBlueprints(Function<BlueprintContext, BlueprintRegistry> registry);
}
