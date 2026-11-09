package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentClients;
import com.thinkmorestupidless.ankka.agent.javasdk.AssignResult;
import com.thinkmorestupidless.ankka.agent.javasdk.Decision;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.CartAnswerer;
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.CartTasks;

/** The calls the autonomous agents page shows, written as a service would write them. */
public final class OperatorCalls {

  private OperatorCalls() {}

  // docs:start autonomous-decide
  public static void approve(ComponentClient client, String approvalId) {
    AgentClients.forAutonomousAgent(client, Operator.class, "ops-1")
        .decide(Decision.approved(approvalId, "dana"));
  }
  // docs:end autonomous-decide

  // docs:start assign-tasks
  public static AssignResult review(ComponentClient client) {
    var tasks = AgentClients.tasks(client);
    var first = tasks.create(CartTasks.ANSWER, "What is in cart c-1?").create();
    var second = tasks.create(CartTasks.ANSWER, "And in c-2?").dependsOn(first).create();
    return AgentClients.forAutonomousAgent(client, CartAnswerer.class, "reviewer-1")
        .assign(first, second);
  }
  // docs:end assign-tasks
}
