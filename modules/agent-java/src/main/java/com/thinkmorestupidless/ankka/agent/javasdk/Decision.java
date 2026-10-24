package com.thinkmorestupidless.ankka.agent.javasdk;

/**
 * A person's answer to an approval request. {@code by} is recorded and shown, and never what
 * authorises the decision: who may decide is the ACL of the route that sends it.
 */
public record Decision(String approvalId, boolean approved, String by, String note) {

  public static Decision approved(String approvalId, String by) {
    return new Decision(approvalId, true, by, "");
  }

  public static Decision refused(String approvalId, String by, String note) {
    return new Decision(approvalId, false, by, note);
  }
}
