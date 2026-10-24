package com.thinkmorestupidless.ankka.javasdk.testkit;

import java.util.Map;
import java.util.Optional;

/**
 * A message a consumer of the service published to a topic, as the kit's broker accepted it: the
 * record key a broker is given, the payload as text, and its headers, the CloudEvents ones among
 * them ({@code ce-subject}, {@code ce-type}).
 */
public record Published(Optional<String> key, String text, Map<String, String> metadata) {

  /** The subject the message is about: the entity id of the change it was published for. */
  public Optional<String> subject() {
    return Optional.ofNullable(metadata.get("ce-subject"));
  }
}
