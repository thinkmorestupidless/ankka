package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.bridge.Topics;
import java.util.Objects;

/**
 * The contract a topic's messages keep: a name and the fingerprint of its schema document. A
 * consumer states it for the topic it reads or publishes to, and the platform holds the two ends to
 * one contract.
 */
public record Contract(String name, String fingerprint) {

  public Contract {
    Objects.requireNonNull(name);
    Objects.requireNonNull(fingerprint);
  }

  /**
   * The contract named {@code name} whose schema is {@code document}, a JSON document; refused with
   * an {@code IllegalArgumentException} when the name breaks the rule or the document is not JSON.
   */
  public static Contract fromSchema(String name, byte[] document) {
    return Topics.contract(name, document);
  }
}
