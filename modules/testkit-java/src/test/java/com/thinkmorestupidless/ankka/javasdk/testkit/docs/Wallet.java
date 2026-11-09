package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;

/** A wallet the transfer moves money between; one whose id starts "frozen" takes no deposit. */
@ComponentId("wallet")
public class Wallet extends KeyValueEntity<Wallet.Balance> {

  @Manifest("balance")
  public record Balance(long amount) {}

  @Override
  protected Balance emptyState() {
    return new Balance(0);
  }

  @Command("deposit")
  public Effect<Done> deposit(Long amount) {
    if (context().entityId().startsWith("frozen"))
      return effects().error(Problem.conflict("wallet is frozen"));
    return effects().updateState(new Balance(currentState().amount() + amount)).thenDone();
  }

  @Command("withdraw")
  public Effect<Done> withdraw(Long amount) {
    if (currentState().amount() < amount)
      return effects().error(Problem.conflict("insufficient funds"));
    return effects().updateState(new Balance(currentState().amount() - amount)).thenDone();
  }

  @Query("balance")
  public ReadOnlyEffect<Balance> balance() {
    return effects().reply(currentState());
  }
}
