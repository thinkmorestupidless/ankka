package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.FunctionTool;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;

// docs:start service-call-tool
/** A support agent whose tool reads a balance from the wallet service, as its own service. */
@ComponentId("wallet-agent")
public class WalletAgent extends Agent {

  public record Customer(@Describe("The customer whose balance to read.") String customer) {}

  private final FunctionTool readBalance =
      FunctionTool.of(
          "read_balance",
          "Reads a customer's wallet balance.",
          Customer.class,
          c -> services().of("wallet").getText("/wallet/balances/" + c.customer()));

  @Command("ask")
  public AgentEffect<String> ask(String question) {
    return effects()
        .systemMessage("You answer questions about customers' wallets.")
        .userMessage(question)
        .tools(readBalance)
        .thenReply();
  }
}
// docs:end service-call-tool
