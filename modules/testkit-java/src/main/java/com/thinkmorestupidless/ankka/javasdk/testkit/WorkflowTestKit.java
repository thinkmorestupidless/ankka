package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.client.CommandRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef;
import com.thinkmorestupidless.ankka.javasdk.client.WorkflowLifecycle;
import com.thinkmorestupidless.ankka.javasdk.component.Workflow;
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.KindKits;
import java.util.Optional;

/**
 * One workflow driven with no runtime, database or timer. A command runs as the engine runs it,
 * then each step it moves to runs at once, until the workflow pauses, ends or fails; a step that
 * throws is retried or failed over as the workflow's settings say. A pause's timeout fires only
 * when the test calls {@link #timeOut()}.
 *
 * <p>A step's calls to other components go to the {@link TestClient} the kit was given, and are
 * refused when none was.
 */
public final class WorkflowTestKit<S> {

  private final KindKits.WorkflowKit kit;

  private WorkflowTestKit(KindKits.WorkflowKit kit) {
    this.kit = kit;
  }

  /** The workflow, not started, as {@code test}. */
  public static <S> WorkflowTestKit<S> of(Class<? extends Workflow<S>> workflow) {
    return of(workflow, "test");
  }

  /** The workflow, not started, under {@code workflowId}. */
  public static <S> WorkflowTestKit<S> of(
      Class<? extends Workflow<S>> workflow, String workflowId) {
    return of(workflow, workflowId, TestClient.of());
  }

  /** The workflow under {@code workflowId}, its steps' calls answered by {@code client}. */
  public static <S> WorkflowTestKit<S> of(
      Class<? extends Workflow<S>> workflow, String workflowId, TestClient client) {
    return new WorkflowTestKit<>(KindKits.workflow(workflow, workflowId, client.stubs()));
  }

  /** Sends a command to the handler {@code handler} names, and runs the steps it moves to. */
  public <C, I, O> StepResult<S, O> call(CommandRef<C, I, O> handler, I input) {
    return kit.call(handler, input);
  }

  /** Sends a command that takes nothing. */
  public <C, O> StepResult<S, O> call(NoArgRef<C, O> handler) {
    return kit.call(handler);
  }

  /**
   * The pause's timeout fires, and the step it names runs, and on.
   *
   * @throws AssertionError when the workflow is not paused with a timeout
   */
  public StepResult<S, Void> timeOut() {
    return kit.timeOut();
  }

  /** The workflow's state now. */
  @SuppressWarnings("unchecked")
  public S state() {
    return (S) kit.state();
  }

  /** Where the workflow stands now. */
  public WorkflowLifecycle lifecycle() {
    return kit.lifecycle();
  }

  /** Whether a command deleted the workflow, and nothing has been recorded since. */
  public boolean deleted() {
    return kit.deleted();
  }

  /** The step the workflow failed at, when it failed at one. */
  public Optional<String> failedStep() {
    return kit.failedStep();
  }
}
