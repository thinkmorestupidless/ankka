package com.thinkmorestupidless.ankka.javasdk.secret;

import java.util.Optional;

/**
 * The service's own secrets: values it keeps for itself, such as a credential a user handed it,
 * encrypted with the service's secret key and readable only by the service. A name is 1 to 253
 * letters, digits, {@code .}, {@code _}, {@code -} or {@code /}; a value is not empty and at most 64
 * KiB. Where the service has no store, every call is a {@code CommandException} with status 503.
 */
public interface SecretStore {

  /** Keeps {@code value} under {@code name}, replacing what was there. */
  void put(String name, String value);

  Optional<String> get(String name);

  /** Forgets {@code name}; nothing when there is none. */
  void delete(String name);
}
