package com.thinkmorestupidless.ankka.javasdk.mcp;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A client key to issue: for {@code subject}, at the MCP endpoint at {@code endpoint} (implied when
 * the service has one), able to call the tools in {@code scope} (every tool when empty). Its {@code
 * attributes} are what a handler reads as the principal's claims.
 */
public record ClientKeyRequest(
    String subject,
    Expiry expiry,
    Optional<String> endpoint,
    Set<String> scope,
    Map<String, String> attributes,
    Optional<String> label) {

  public static ClientKeyRequest of(String subject, Expiry expiry) {
    return new ClientKeyRequest(
        subject, expiry, Optional.empty(), Set.of(), Map.of(), Optional.empty());
  }

  public ClientKeyRequest atEndpoint(String path) {
    return new ClientKeyRequest(subject, expiry, Optional.of(path), scope, attributes, label);
  }

  public ClientKeyRequest scopedTo(String... tools) {
    return new ClientKeyRequest(subject, expiry, endpoint, Set.of(tools), attributes, label);
  }

  public ClientKeyRequest withAttribute(String name, String value) {
    Map<String, String> more = new HashMap<>(attributes);
    more.put(name, value);
    return new ClientKeyRequest(subject, expiry, endpoint, scope, Map.copyOf(more), label);
  }

  public ClientKeyRequest labelled(String label) {
    return new ClientKeyRequest(subject, expiry, endpoint, scope, attributes, Optional.of(label));
  }
}
