package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.Agent;
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect;
import com.thinkmorestupidless.ankka.agent.javasdk.Tool;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A support agent whose tools wait for a person, one of them for two seconds at most. */
@ComponentId("approval-agent")
public class ApprovalAgent extends Agent {

  /** What the tools did once approved, read by the suite. */
  public static final List<String> RUNS = new CopyOnWriteArrayList<>();

  public static final class Tools {

    // docs:start requires-approval
    @Tool(
        value = "issue_refund",
        description = "Refunds an order. A supervisor approves every refund before it is made.",
        requiresApproval = true)
    public String issueRefund(
        @Describe("The order to refund.") String order,
        @Describe("The amount to refund.") int amount) {
      RUNS.add("issue_refund(" + order + "," + amount + ")");
      return "refunded " + amount + " on " + order;
    }
    // docs:end requires-approval

    // docs:start requires-approval-within
    @Tool(
        value = "close_account",
        description = "Closes a customer's account.",
        requiresApproval = true,
        approvalWithin = "PT2S")
    public String closeAccount(@Describe("The customer whose account to close.") String customer) {
      RUNS.add("close_account(" + customer + ")");
      return "closed " + customer;
    }
    // docs:end requires-approval-within
  }

  @Command("ask")
  public AgentEffect<String> ask(String request) {
    return effects()
        .systemMessage("You handle support requests with the tools you are given.")
        .userMessage(request)
        .tools(Tools.class)
        .thenReply();
  }
}
