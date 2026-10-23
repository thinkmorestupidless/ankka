package com.thinkmorestupidless.ankka.agent.javasdk;

import java.time.Instant;
import java.util.Optional;

/**
 * A tool call waiting for a person: its id, which a {@link Decision} names, the tool and the
 * arguments the model gave it as JSON, when it was asked for and when it expires.
 */
public record ApprovalRequest(
    String id, String tool, String arguments, Instant requestedAt, Optional<Instant> expiresAt) {}
