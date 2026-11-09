package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.annotations.Replicated;
import com.thinkmorestupidless.ankka.javasdk.annotations.SnapshotEvery;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import java.util.ArrayList;
import java.util.List;

// docs:start replicated-entity
/**
 * A replicated ledger: each entry records where its event was written, read from the event's
 * context, and whether it was written here. The installations that hold a ledger are the
 * service's configuration's.
 */
@ComponentId("ledger")
@Replicated
@SnapshotEvery(0)
public class Ledger extends EventSourcedEntity<Ledger.State, Ledger.Entered> {

  public record State(List<String> entries) {}

  public record Entered(String text) {}
  // docs:end replicated-entity

  @Override
  protected State emptyState() {
    return new State(List.of());
  }

  // docs:start replicated-apply
  @Override
  protected State applyEvent(State state, Entered event) {
    // Where this event was written, on replay as well as when it is persisted here.
    var where = eventContext().installation().orElse("nowhere");
    var entries = new ArrayList<>(state.entries());
    entries.add(event.text() + "@" + where + (eventContext().isLocal() ? "" : " (mirrored)"));
    return new State(entries);
  }
  // docs:end replicated-apply

  @Command("enter")
  public Effect<State> enter(String text) {
    return effects().persist(new Entered(text)).thenReplyState();
  }

  // docs:start replicated-effects
  /** Allots the ledger to an installation, which holds it from now on. */
  @Command("share")
  public Effect<Done> share(String installation) {
    return effects().replicateTo(installation).thenDone();
  }

  /** The installation keeps what it holds and mirrors no more. */
  @Command("unshare")
  public Effect<Done> unshare(String installation) {
    return effects().stopReplicatingTo(installation).thenDone();
  }

  /** Persists one more event, and then the home is the installation named from now on. */
  @Command("enter-and-hand-over")
  public Effect<Done> enterAndHandOver(String installation) {
    return effects().persist(new Entered("handing over")).handOverTo(installation).thenDone();
  }

  /** Its home is the installation named from now on. */
  @Command("hand-over")
  public Effect<Done> handOver(String installation) {
    return effects().handOverTo(installation).thenDone();
  }
  // docs:end replicated-effects

  @Command("forget")
  public Effect<Done> forget() {
    return effects().persist(new Entered("forgotten")).thenNoReply();
  }

  @Query("where")
  public ReadOnlyEffect<String> where() {
    return effects().reply(context().installation().orElse("nowhere"));
  }

  @Query("get")
  public ReadOnlyEffect<State> get() {
    return effects().reply(currentState());
  }

  @Query("ignore")
  public ReadOnlyEffect<String> ignore() {
    return effects().noReply();
  }
}
