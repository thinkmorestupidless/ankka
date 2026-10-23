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

/**
 * A replicated ledger: each entry records where its event was written, read from the event's
 * context, and whether it was written here.
 */
@ComponentId("ledger")
@Replicated
@SnapshotEvery(0)
public class Ledger extends EventSourcedEntity<Ledger.State, Ledger.Entered> {

  public record State(List<String> entries) {}

  public record Entered(String text) {}

  @Override
  protected State emptyState() {
    return new State(List.of());
  }

  @Override
  protected State applyEvent(State state, Entered event) {
    var where = eventContext().installation().orElse("nowhere");
    var entries = new ArrayList<>(state.entries());
    entries.add(event.text() + "@" + where + (eventContext().isLocal() ? "" : " (mirrored)"));
    return new State(entries);
  }

  @Command("enter")
  public Effect<State> enter(String text) {
    return effects().persist(new Entered(text)).thenReplyState();
  }

  @Command("share")
  public Effect<Done> share(String installation) {
    return effects().replicateTo(installation).thenDone();
  }

  @Command("unshare")
  public Effect<Done> unshare(String installation) {
    return effects().stopReplicatingTo(installation).thenDone();
  }

  @Command("enter-and-hand-over")
  public Effect<Done> enterAndHandOver(String installation) {
    return effects().persist(new Entered("handing over")).handOverTo(installation).thenDone();
  }

  @Command("hand-over")
  public Effect<Done> handOver(String installation) {
    return effects().handOverTo(installation).thenDone();
  }

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
