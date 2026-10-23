package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.bridge.EntityCell;
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffects;
import com.thinkmorestupidless.ankka.javasdk.effect.WorkflowEffects;
import org.jspecify.annotations.Nullable;

/**
 * A durable process of named steps. Commands ({@code @Command}, {@code @Query}) start it, steer it
 * and read it; each {@code @Step} method runs once the workflow transitions to it, and says what
 * comes next: another step, a pause, the end, or a failure. Every transition is journaled, so a
 * workflow resumes where it was after a restart.
 *
 * <p>A step names the step that follows by a method reference ({@code Checkout::charge}), as a call
 * names a handler, so renaming the method leaves the journal's step name, the {@code @Step} value,
 * alone.
 *
 * @param <S> the workflow's state
 */
public abstract class Workflow<S> {

  private final EntityCell cell = EntityCell.take();

  protected Workflow() {}

  /** The state before anything is written; {@code null} unless overridden. */
  protected @Nullable S emptyState() {
    return null;
  }

  /** Timeouts and recovery, by step; the platform's defaults unless overridden. */
  protected WorkflowSettings settings() {
    return WorkflowSettings.defaults();
  }

  @SuppressWarnings("unchecked")
  protected final S currentState() {
    return (S) cell.currentState();
  }

  /** The workflow's id, its client, and the command's metadata while one is handled. */
  protected final EntityContext context() {
    return cell.entityContext();
  }

  /** The effects a command returns. */
  @SuppressWarnings("unchecked")
  protected final WorkflowEffects<S> effects() {
    return (WorkflowEffects<S>) cell.workflowEffects();
  }

  /** The effects a step returns. */
  @SuppressWarnings("unchecked")
  protected final StepEffects<S> stepEffects() {
    return (StepEffects<S>) cell.stepEffects();
  }
}
