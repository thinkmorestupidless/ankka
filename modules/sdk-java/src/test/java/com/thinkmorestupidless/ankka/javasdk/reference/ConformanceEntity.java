package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.annotations.SnapshotEvery;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** An entity whose handlers are the protocol's edge cases. */
@ComponentId("conformance")
@SnapshotEvery(3)
public class ConformanceEntity
    extends EventSourcedEntity<ConformanceEntity.Recordings, ConformanceEntity.Recorded> {

  /** What was recorded, in order. */
  @Manifest("conformance-state")
  public record Recordings(List<String> inputs) {}

  @Manifest("conformance-event")
  public record Recorded(String input) {}

  @Override
  protected Recordings emptyState() {
    return new Recordings(List.of());
  }

  @Override
  protected Recordings applyEvent(Recordings state, Recorded event) {
    var inputs = new ArrayList<>(state.inputs());
    inputs.add(event.input());
    return new Recordings(List.copyOf(inputs));
  }

  @Command("record")
  public Effect<String> record(String input) {
    return effects().persist(new Recorded(input)).thenReply("done");
  }

  @Command("record-many")
  public Effect<String> recordMany(int n) {
    var events = IntStream.range(0, n).mapToObj(i -> new Recorded("many-" + i)).toList();
    return effects().persistAll(events).thenReply("done");
  }

  @Command("refuse")
  public Effect<String> refuse() {
    return effects().error(Problem.conflict("refused on purpose"));
  }

  @Command("no-reply")
  public Effect<String> noReply() {
    return effects().persist(new Recorded("silent")).thenNoReply();
  }

  @Command("delete")
  public Effect<String> delete() {
    return effects().deleteEntity().thenReply("done");
  }

  @Command("expire")
  public Effect<String> expire(long millis) {
    return effects()
        .persist(new Recorded("expiring"))
        .expireAfter(Duration.ofMillis(millis))
        .thenReply("done");
  }

  @Query("count")
  public ReadOnlyEffect<Integer> count() {
    return effects().reply(currentState().inputs().size());
  }

  @Command("misbehave")
  public Effect<String> misbehave() {
    throw new RuntimeException("boom");
  }
}
