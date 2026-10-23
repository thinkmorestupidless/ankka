package com.thinkmorestupidless.ankka.javasdk.http;

/** What an {@link Acl#authenticate} function decides about one request. */
public sealed interface AuthDecision {

  /** Admitted, as this principal. */
  record Allow(Principal principal) implements AuthDecision {}

  /** Not known: answered 401 with this challenge. */
  record Unauthenticated(String challenge) implements AuthDecision {}

  /** Known and refused: answered 403 with this reason. */
  record Forbidden(String reason) implements AuthDecision {}

  static AuthDecision allow(Principal principal) {
    return new Allow(principal);
  }

  static AuthDecision unauthenticated(String challenge) {
    return new Unauthenticated(challenge);
  }

  static AuthDecision forbidden(String reason) {
    return new Forbidden(reason);
  }
}
