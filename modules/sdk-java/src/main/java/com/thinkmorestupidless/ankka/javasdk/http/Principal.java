package com.thinkmorestupidless.ankka.javasdk.http;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Who an {@link Acl#authenticate} function admitted. */
public record Principal(
    String subject,
    Optional<String> name,
    Optional<String> email,
    boolean emailVerified,
    Set<String> roles,
    Map<String, String> claims,
    Optional<String> issuer) {

  /** A principal known only by its subject. */
  public static Principal of(String subject) {
    return new Principal(
        subject, Optional.empty(), Optional.empty(), false, Set.of(), Map.of(), Optional.empty());
  }
}
