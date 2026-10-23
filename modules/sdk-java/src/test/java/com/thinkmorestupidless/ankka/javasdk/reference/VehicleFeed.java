package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId;
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource;
import com.thinkmorestupidless.ankka.javasdk.component.Consumer;
import com.thinkmorestupidless.ankka.javasdk.component.Publication;
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect;
import java.util.Optional;

/** Publishes nothing; declared to show a consumer given mirrored changes discovers so. */
@ComponentId("vehicle-feed")
public class VehicleFeed
    extends Consumer<ReplicatedVehicle.StepAdded, ReplicatedVehicle.StepAdded> {

  @Override
  protected ChangeSource<ReplicatedVehicle.StepAdded> source() {
    return ChangeSource.of(ReplicatedVehicle.class);
  }

  @Override
  protected Optional<Publication> publishes() {
    return Optional.of(Publication.to("conformance-vehicle-steps"));
  }

  @Override
  protected boolean mirroredChanges() {
    return true;
  }

  @Override
  protected ConsumerEffect<ReplicatedVehicle.StepAdded> onChange(
      ReplicatedVehicle.StepAdded message) {
    return effects().ignore();
  }
}
