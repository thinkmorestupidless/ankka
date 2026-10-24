package com.thinkmorestupidless.ankka.javasdk.component;

import com.thinkmorestupidless.ankka.javasdk.bridge.Effects;
import com.thinkmorestupidless.ankka.javasdk.bridge.ViewCell;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.effect.GraphEffect;
import com.thinkmorestupidless.ankka.javasdk.effect.GraphEffects;
import com.thinkmorestupidless.ankka.javasdk.effect.GraphElements;
import java.util.OptionalInt;

/**
 * Publishes its source's changes as graph deltas, nodes and edges keyed for a compacted topic, for
 * a graph sink to keep a graph store in step with. Each element is stamped with the change's
 * sequence number unless it says otherwise.
 *
 * @param <Src> what the source delivers
 */
public abstract class GraphConsumer<Src> {

  private final ViewCell cell = ViewCell.take();

  protected GraphConsumer() {}

  protected abstract ChangeSource<Src> source();

  /** The topic the deltas are published to. */
  protected abstract String topic();

  protected abstract GraphEffect onChange(Src change);

  /** What happens when the source is deleted: nothing unless overridden. */
  protected GraphEffect onDelete() {
    return effects().ignore();
  }

  protected OptionalInt version() {
    return OptionalInt.empty();
  }

  protected int parallelism() {
    return 4;
  }

  protected final ChangeContext messageContext() {
    return cell.context();
  }

  protected final ComponentClient componentClient() {
    return cell.componentClient();
  }

  /** The node and edge builders. */
  protected final GraphElements graph() {
    return Effects.graphElements();
  }

  protected final GraphEffects effects() {
    return Effects.graph();
  }
}
