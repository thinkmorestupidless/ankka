package com.thinkmorestupidless.ankka.agent.javasdk;

import java.util.Optional;

/**
 * Something an instance did: its kind ({@code TaskAssigned}, {@code IterationStarted}, {@code
 * TaskCompleted}, and the rest the Scala SDK's {@code Notification} names), the instance, the task
 * when it was about one, when it happened in epoch milliseconds, and the whole notification as the
 * JSON every SDK reads.
 */
public record Notification(
    String kind, String instanceId, Optional<String> taskId, long at, String json) {}
