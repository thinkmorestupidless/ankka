package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Command;
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.annotations.Query;
import com.thinkmorestupidless.ankka.javasdk.annotations.Replicated;
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest;
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity;
import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
import java.util.ArrayList;
import java.util.List;

/**
 * A replicated entity whose state says, for each step, where its event was written ("" when no
 * installation is named), whether that was here, who acted and where it was forwarded from.
 */
@ComponentId("replicated-vehicle")
@Replicated
public class ReplicatedVehicle
    extends EventSourcedEntity<ReplicatedVehicle.VehicleRecord, ReplicatedVehicle.StepAdded> {

  @Manifest("vehicle-record")
  public record VehicleRecord(
      List<String> steps,
      List<String> writtenAt,
      List<Boolean> local,
      List<String> actedBy,
      List<String> forwardedFrom) {}

  /** Where a command ran, who acted and where it was forwarded from ("" when none). */
  @Manifest("vehicle-placement")
  public record VehiclePlacement(
      String installation, boolean home, String attribution, String forwardedFrom) {}

  @Manifest("step-added")
  public record StepAdded(String step) {}

  @Override
  protected VehicleRecord emptyState() {
    return new VehicleRecord(List.of(), List.of(), List.of(), List.of(), List.of());
  }

  @Override
  protected VehicleRecord applyEvent(VehicleRecord s, StepAdded event) {
    var facts = eventContext();
    return new VehicleRecord(
        plus(s.steps(), event.step()),
        plus(s.writtenAt(), facts.installation().orElse("")),
        plus(s.local(), facts.isLocal()),
        plus(s.actedBy(), facts.attribution().orElse("")),
        plus(s.forwardedFrom(), facts.forwardedFrom().orElse("")));
  }

  private static <A> List<A> plus(List<A> list, A more) {
    var out = new ArrayList<>(list);
    out.add(more);
    return List.copyOf(out);
  }

  @Command("add-step")
  public Effect<VehiclePlacement> addStep(String step) {
    var placement =
        new VehiclePlacement(
            context().installation().orElse(""),
            context().isHome(),
            context().attribution().orElse(""),
            context().forwardedFrom().orElse(""));
    return effects().persist(new StepAdded(step)).thenReply(placement);
  }

  @Command("hand-over")
  public Effect<String> handOver(String target) {
    return effects().handOverTo(target).thenReply(target);
  }

  @Command("replicate-to")
  public Effect<String> replicateTo(String target) {
    return effects().replicateTo(target).thenReply(target);
  }

  @Command("stop-replicating-to")
  public Effect<String> stopReplicatingTo(String target) {
    return effects().stopReplicatingTo(target).thenReply(target);
  }

  @Query("get")
  public ReadOnlyEffect<VehicleRecord> get() {
    return effects().reply(currentState());
  }
}
