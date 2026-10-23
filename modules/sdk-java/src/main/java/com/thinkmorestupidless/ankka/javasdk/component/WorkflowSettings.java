package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.client.NoInputStepRef;
import com.thinkmorestupidless.ankka.javasdk.client.StepRef;
import java.io.Serializable;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A workflow's timeouts and recovery: a deadline for the whole workflow, a step's time to run, and
 * what happens when a step fails or runs out of time. Steps are named by method reference and
 * checked when the service starts.
 */
public final class WorkflowSettings {

  private final Optional<Duration> timeout;
  private final Duration defaultStepTimeout;
  private final Map<Serializable, Duration> stepTimeouts;
  private final RecoverStrategy defaultRecovery;
  private final Map<Serializable, RecoverStrategy> stepRecovery;

  private WorkflowSettings(
      Optional<Duration> timeout,
      Duration defaultStepTimeout,
      Map<Serializable, Duration> stepTimeouts,
      RecoverStrategy defaultRecovery,
      Map<Serializable, RecoverStrategy> stepRecovery) {
    this.timeout = timeout;
    this.defaultStepTimeout = defaultStepTimeout;
    this.stepTimeouts = Map.copyOf(stepTimeouts);
    this.defaultRecovery = defaultRecovery;
    this.stepRecovery = Map.copyOf(stepRecovery);
  }

  /** No deadline, thirty seconds a step, and a failed step fails the workflow. */
  public static WorkflowSettings defaults() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Optional<Duration> timeout() {
    return timeout;
  }

  public Duration defaultStepTimeout() {
    return defaultStepTimeout;
  }

  /** Each step's own timeout, keyed by the method reference that names it. */
  public Map<Serializable, Duration> stepTimeouts() {
    return stepTimeouts;
  }

  public RecoverStrategy defaultRecovery() {
    return defaultRecovery;
  }

  /** Each step's own recovery, keyed by the method reference that names it. */
  public Map<Serializable, RecoverStrategy> stepRecovery() {
    return stepRecovery;
  }

  public static final class Builder {
    private Optional<Duration> timeout = Optional.empty();
    private Duration defaultStepTimeout = Duration.ofSeconds(30);
    private final Map<Serializable, Duration> stepTimeouts = new LinkedHashMap<>();
    private RecoverStrategy defaultRecovery = RecoverStrategy.fail();
    private final Map<Serializable, RecoverStrategy> stepRecovery = new LinkedHashMap<>();

    private Builder() {}

    /** The workflow fails when it has not ended within {@code timeout} of starting. */
    public Builder timeout(Duration timeout) {
      this.timeout = Optional.of(Objects.requireNonNull(timeout));
      return this;
    }

    public Builder defaultStepTimeout(Duration timeout) {
      this.defaultStepTimeout = Objects.requireNonNull(timeout);
      return this;
    }

    public <W, I> Builder stepTimeout(StepRef<W, I> step, Duration timeout) {
      stepTimeouts.put(Objects.requireNonNull(step), Objects.requireNonNull(timeout));
      return this;
    }

    public <W> Builder stepTimeout(NoInputStepRef<W> step, Duration timeout) {
      stepTimeouts.put(Objects.requireNonNull(step), Objects.requireNonNull(timeout));
      return this;
    }

    public Builder defaultRecovery(RecoverStrategy strategy) {
      this.defaultRecovery = Objects.requireNonNull(strategy);
      return this;
    }

    public <W, I> Builder stepRecovery(StepRef<W, I> step, RecoverStrategy strategy) {
      stepRecovery.put(Objects.requireNonNull(step), Objects.requireNonNull(strategy));
      return this;
    }

    public <W> Builder stepRecovery(NoInputStepRef<W> step, RecoverStrategy strategy) {
      stepRecovery.put(Objects.requireNonNull(step), Objects.requireNonNull(strategy));
      return this;
    }

    public WorkflowSettings build() {
      return new WorkflowSettings(
          timeout, defaultStepTimeout, stepTimeouts, defaultRecovery, stepRecovery);
    }
  }
}
