package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.Ankka;
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder;

/** The banking service: the wallets and the workflows over them. */
public final class TransferCalls {

  private TransferCalls() {}

  // docs:start register
  /** A workflow is registered on the service like any other component. */
  public static ServiceBuilder service() {
    return Ankka.service("banking")
        .register(Wallet.class)
        .register(TransferWorkflow.class)
        .register(KycWorkflow.class)
        .register(OnboardingWorkflow.class);
  }
  // docs:end register
}
