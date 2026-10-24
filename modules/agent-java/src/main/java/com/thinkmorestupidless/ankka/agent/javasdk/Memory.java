package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge;

/** How one interaction reads and writes its session's history. */
public interface Memory {

  /** Reads nothing and writes nothing: a one-off classification or extraction. */
  static Memory none() {
    return AgentBridge.memoryNone();
  }

  /** Reads and writes the whole session, the default. */
  static Memory session() {
    return AgentBridge.memorySession();
  }

  /** Reads only the last {@code count} messages. */
  Memory readLast(int count);

  /** Reads the session and writes nothing to it. */
  Memory readOnly();

  /** Writes to the session and reads nothing from it. */
  Memory writeOnly();
}
