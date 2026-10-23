package com.thinkmorestupidless.ankka.javasdk.testkit;

import java.util.List;
import java.util.Optional;

/**
 * What one change came to in the consumer test kit: the messages the consumer would publish, each
 * as a broker would be given it, and their payloads read back through the consumer's serializer.
 */
public interface ConsumerResult<Out> {

  /** Each message's payload, decoded from the bytes it was encoded to. */
  List<Out> payloads();

  /** Each message as published: its key, its text and its metadata, {@code ce-subject} set. */
  List<Published> messages();

  /** The key a broker is given for each message: the one it named, else its subject. */
  List<Optional<String>> recordKeys();
}
