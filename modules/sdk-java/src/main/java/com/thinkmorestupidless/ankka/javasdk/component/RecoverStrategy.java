package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.client.NoInputStepRef;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What happens when a step throws or runs out of time: run it again up to {@code maxRetries} times,
 * then fail the workflow, or transition to a step that takes no input, such as a compensation.
 */
public final class RecoverStrategy {

  private final int maxRetries;
  private final @Nullable NoInputStepRef<?> failoverTo;

  private RecoverStrategy(int maxRetries, @Nullable NoInputStepRef<?> failoverTo) {
    if (maxRetries < 0) throw new IllegalArgumentException("maxRetries is 0 or more");
    this.maxRetries = maxRetries;
    this.failoverTo = failoverTo;
  }

  /** No retry: the workflow fails. */
  public static RecoverStrategy fail() {
    return new RecoverStrategy(0, null);
  }

  public static RecoverStrategy maxRetries(int count) {
    return new RecoverStrategy(count, null);
  }

  /** Once the retries are spent, transition to {@code step} rather than fail. */
  public <W> RecoverStrategy failoverTo(NoInputStepRef<W> step) {
    return new RecoverStrategy(maxRetries, Objects.requireNonNull(step));
  }

  public int maxRetries() {
    return maxRetries;
  }

  public Optional<NoInputStepRef<?>> failover() {
    return Optional.ofNullable(failoverTo);
  }
}
