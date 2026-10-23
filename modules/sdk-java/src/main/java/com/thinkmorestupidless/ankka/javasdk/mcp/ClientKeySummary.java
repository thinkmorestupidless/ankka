package com.thinkmorestupidless.ankka.javasdk.mcp;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** One client key as a listing shows it: never the key, never its digest. */
public record ClientKeySummary(
    String id,
    String subject,
    Map<String, String> attributes,
    Optional<String> label,
    String endpoint,
    Set<String> scope,
    Instant issuedAt,
    Optional<Instant> expiresAt,
    Optional<Instant> lastUsed,
    Optional<Instant> revokedAt) {}
