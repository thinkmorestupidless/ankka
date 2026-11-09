package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.annotations.Step;
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.RecoverStrategy;
import com.thinkmorestupidless.ankka.javasdk.component.Workflow;
import com.thinkmorestupidless.ankka.javasdk.component.WorkflowSettings;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import java.time.Duration;

/**
 * Moves money from one wallet to another: withdraw, then deposit, and put it back when the
 * deposit fails. Each {@code @Step} method is a step, registered under the name it declares.
 */
@ComponentId("transfer")
public class TransferWorkflow extends Workflow<TransferWorkflow.TransferState> {

  @Manifest("transfer")
  public record Transfer(String from, String to, long amount) {}

  @Manifest("transfer-state")
  public record TransferState(Transfer transfer, String status) {}

  @Override
  protected TransferState emptyState() {
    return new TransferState(new Transfer("", "", 0), "not-started");
  }

  // docs:start settings
  @Override
  protected WorkflowSettings settings() {
    return WorkflowSettings.builder()
        .timeout(Duration.ofSeconds(60))
        .defaultStepTimeout(Duration.ofSeconds(10))
        // One retry, then compensate. The deposit is the step that can fail for reasons
        // outside this workflow's control, so it is the one worth a second attempt.
        .stepRecovery(
            TransferWorkflow::deposit,
            RecoverStrategy.maxRetries(1).failoverTo(TransferWorkflow::compensate))
        .build();
  }
  // docs:end settings

  // docs:start start
  @Command("start")
  public Effect<Done> start(Transfer transfer) {
    if (transfer.amount() <= 0)
      return effects().error("transfer amount must be greater than zero");
    if (!currentState().status().equals("not-started"))
      return effects().error(Problem.conflict("transfer already started"));
    return effects()
        .updateState(new TransferState(transfer, "started"))
        .transitionTo(TransferWorkflow::withdraw, transfer)
        .thenDone();
  }
  // docs:end start

  // docs:start steps
  @Step("withdraw")
  public StepEffect withdraw(Transfer transfer) {
    wallet(transfer.from()).call(Wallet::withdraw).invoke(transfer.amount());
    return stepEffects()
        .updateState(new TransferState(transfer, "withdrawn"))
        .thenTransitionTo(TransferWorkflow::deposit, transfer);
  }

  @Step("deposit")
  public StepEffect deposit(Transfer transfer) {
    wallet(transfer.to()).call(Wallet::deposit).invoke(transfer.amount());
    return stepEffects().updateState(new TransferState(transfer, "completed")).thenEnd();
  }

  /**
   * Puts the money back.
   *
   * <p>Takes no input and reads the transfer from the state, which is why failover steps are
   * declared without one: the runtime names the step, and the state carries what it needs.
   */
  @Step("compensate")
  public StepEffect compensate() {
    var transfer = currentState().transfer();
    wallet(transfer.from()).call(Wallet::deposit).invoke(transfer.amount());
    return stepEffects().updateState(new TransferState(transfer, "compensated")).thenEnd();
  }

  private EntityRef wallet(String id) {
    return context().componentClient().forKeyValueEntity(id);
  }
  // docs:end steps

  @Query("get")
  public ReadOnlyEffect<TransferState> get() {
    return effects().reply(currentState());
  }
}
