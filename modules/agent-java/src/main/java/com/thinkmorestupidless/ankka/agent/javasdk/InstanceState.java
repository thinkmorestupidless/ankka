package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.List;
import java.util.Optional;

/**
 * An instance as a caller is told it: its phase ({@code Idle}, {@code Working}, {@code Waiting},
 * {@code Suspended} or {@code Terminated}), the task it is working and the iteration it is on, the
 * tasks queued behind it, and the tool calls waiting for a person.
 */
public record InstanceState(
    String instanceId,
    String phase,
    boolean suspended,
    boolean terminated,
    Optional<String> currentTask,
    int iteration,
    List<String> queued,
    List<ApprovalRequest> awaiting) {}
