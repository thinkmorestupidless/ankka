package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;

/** What decides whether a repeated step's result is good enough: a critic worker, or a question. */
public interface Verdict {

  /** A worker that answers whether the result passed, and why not. */
  static Verdict critic(String worker) {
    return AgentBridge.critic(worker);
  }

  /** A registered yes/no question asked of the result. */
  static Verdict judgment(String question) {
    return AgentBridge.verdictJudgment(question);
  }
}
