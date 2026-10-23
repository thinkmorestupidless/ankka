package com.thinkmorestupidless.ankka.javasdk.client;

import java.util.Optional;

/**
 * Why a wait for a workflow's end was refused: the workflow failed, in {@code step} when one
 * failed, or was deleted. Read from a {@link CommandException} by {@code workflowFailure()}.
 */
public record WorkflowFailure(Optional<String> step, String reason, boolean deleted) {}
