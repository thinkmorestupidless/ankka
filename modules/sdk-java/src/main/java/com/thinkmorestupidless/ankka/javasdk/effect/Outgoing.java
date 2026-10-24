package com.thinkmorestupidless.ankka.javasdk.effect;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One message a consumer publishes: the payload, the record key (the change's subject when empty)
 * and metadata sent beside it.
 */
public record Outgoing<Out>(Out payload, Optional<String> key, Map<String, String> metadata) {

  public Outgoing {
    Objects.requireNonNull(payload, "payload");
    key.ifPresent(
        k -> {
          if (k.isEmpty())
            throw new IllegalArgumentException(
                "a record key must not be empty; leave it out to key by the subject");
        });
    metadata = Map.copyOf(metadata);
  }

  public static <Out> Outgoing<Out> of(Out payload) {
    return new Outgoing<>(payload, Optional.empty(), Map.of());
  }

  public Outgoing<Out> withKey(String key) {
    return new Outgoing<>(payload, Optional.of(key), metadata);
  }

  public Outgoing<Out> withMetadata(String name, String value) {
    var more = new java.util.HashMap<>(metadata);
    more.put(name, value);
    return new Outgoing<>(payload, key, more);
  }
}
