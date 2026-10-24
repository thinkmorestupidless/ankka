package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.bridge.Effects;
import com.thinkmorestupidless.ankka.javasdk.bridge.ViewCell;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients;
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore;
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reacts to each change of its source: calls components or other services, or publishes messages
 * to the topic {@link #publishes} names. A change is delivered at least once, so what it does must
 * be safe to do again.
 *
 * @param <Src> what the source delivers
 * @param <Out> what it publishes; {@code Void} for a consumer that publishes nothing
 */
public abstract class Consumer<Src, Out> {

  private final ViewCell cell = ViewCell.take();

  protected Consumer() {}

  /** Where the consumer's changes come from, built with {@link ChangeSource}'s factories. */
  protected abstract ChangeSource<Src> source();

  protected abstract ConsumerEffect<Out> onChange(Src change);

  /** What happens when the source is deleted: nothing unless overridden. */
  protected ConsumerEffect<Out> onDelete() {
    return effects().ignore();
  }

  /** The topic the consumer publishes to, if it publishes. */
  protected Optional<Publication> publishes() {
    return Optional.empty();
  }

  /**
   * Whether a consumer that publishes is given a replicated entity's changes written at other
   * installations too. Off unless overridden: each change is then published once, from the
   * installation that wrote it. A consumer that publishes nothing is given every change.
   */
  protected boolean mirroredChanges() {
    return false;
  }

  /** The consumer's version; raising it reads a topic source again under a new group. */
  protected OptionalInt version() {
    return OptionalInt.empty();
  }

  /** How many source ids the consumer handles at once; four unless overridden. */
  protected int parallelism() {
    return 4;
  }

  /** The change being handled: its source id and position. */
  protected final ChangeContext messageContext() {
    return cell.context();
  }

  /** Calls to this service's components. */
  protected final ComponentClient componentClient() {
    return cell.componentClient();
  }

  /** Calls to other services, by name. */
  protected final ServiceClients services() {
    return cell.services();
  }

  /** The service's own secrets. */
  protected final SecretStore secrets() {
    return cell.secrets();
  }

  /** The service's buckets. */
  protected final ObjectStorage storage() {
    return cell.storage();
  }

  @SuppressWarnings("unchecked")
  protected final ConsumerEffects<Out> effects() {
    return (ConsumerEffects<Out>) Effects.consumer();
  }
}
