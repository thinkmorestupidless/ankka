package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;

/** Drives the replicated vehicle: a step, a read, and the three replication changes. */
public class VehiclesEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  private EntityRef vehicle(String id) {
    return componentClient().forEventSourcedEntity(id);
  }

  @Post("/vehicles/{vehicleId}/steps")
  public ReplicatedVehicle.VehiclePlacement addStep(String vehicleId, @Body String step) {
    return vehicle(vehicleId).call(ReplicatedVehicle::addStep).invoke(step);
  }

  @Get("/vehicles/{vehicleId}")
  public ReplicatedVehicle.VehicleRecord get(String vehicleId) {
    return vehicle(vehicleId).call(ReplicatedVehicle::get).invoke();
  }

  @Post("/vehicles/{vehicleId}/hand-over/{target}")
  public String handOver(String vehicleId, String target) {
    return vehicle(vehicleId).call(ReplicatedVehicle::handOver).invoke(target);
  }

  @Post("/vehicles/{vehicleId}/replicate-to/{target}")
  public String replicateTo(String vehicleId, String target) {
    return vehicle(vehicleId).call(ReplicatedVehicle::replicateTo).invoke(target);
  }

  @Post("/vehicles/{vehicleId}/stop-replicating-to/{target}")
  public String stopReplicatingTo(String vehicleId, String target) {
    return vehicle(vehicleId).call(ReplicatedVehicle::stopReplicatingTo).invoke(target);
  }
}
