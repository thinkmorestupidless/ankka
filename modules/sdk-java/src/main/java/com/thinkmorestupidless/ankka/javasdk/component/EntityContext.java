package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import java.util.Optional;

/**
 * What an entity reads about itself and the command it is handling. The command's parts ({@link
 * #metadata}, {@link #sequenceNumber}, {@link #attribution} and the rest) are readable only inside
 * a handler, and throw {@link IllegalStateException} anywhere else.
 *
 * <p>An entity has no secret store, service client or object storage: a blocking call on an
 * entity's single-writer path holds every other command to it. A workflow step, a consumer or an
 * endpoint makes those calls.
 */
public interface EntityContext {

  /** This instance's id. */
  String entityId();

  /** The component's id. */
  String componentId();

  /** Calls to other components of this service. */
  ComponentClient componentClient();

  /** The value of the request's metadata entry {@code key}, if it has one. */
  Optional<String> metadata(String key);

  /** The sequence number of the last persisted event; 0 before anything is persisted. */
  long sequenceNumber();

  /** Who acted: the subject an endpoint authenticated, whose call led to this command. */
  Optional<String> attribution();

  /** The installation handling the command, for a replicated entity. */
  Optional<String> installation();

  /** Whether this installation is a replicated entity's home; always true otherwise. */
  boolean isHome();

  /** The installation that forwarded this command to its home, for a forwarded write. */
  Optional<String> forwardedFrom();
}
