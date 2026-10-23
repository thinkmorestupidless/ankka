package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.javasdk.spi.ServiceModule;
import java.time.Duration;

/**
 * The agents of a service, and what they share: the model an effect that names none is answered by,
 * how long a model may take, and whether long sessions are compacted. Each method returns a new
 * module; the one given to the service is the one used.
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
}
