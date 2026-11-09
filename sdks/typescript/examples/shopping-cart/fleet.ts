// A fleet's vehicles as replicated entities: a vehicle is held at several installations and written at
// its home, which hands it over to a dealership; a consumer of its changes publishes each build step
// from every installation, saying where it was written.
import { Consumer, Done, done, EventSourcedEntity, command, jsonCodec, query, s, type EventContext, type Infer } from "ankka"

export const Vehicle = s.record("Vehicle", { steps: s.list(s.string), dealer: s.option(s.string), writtenAt: s.list(s.string) })
export type Vehicle = Infer<typeof Vehicle>
export const VehicleEvent = s.sumType("VehicleEvent", { StepAdded: { step: s.string }, Allotted: { dealer: s.string } })
export type VehicleEvent = Infer<typeof VehicleEvent>

export class Vehicles extends EventSourcedEntity<Vehicle, VehicleEvent> {
  static readonly componentId = "vehicles"
  static readonly replicated = true
  static readonly state = jsonCodec(Vehicle, "vehicle")
  static readonly events = jsonCodec(VehicleEvent, "vehicle-event")

  static readonly handlers = {
    addStep: command("add-step", s.string, Done, (v: Vehicles, step) => v.effects.persist({ type: "StepAdded", step }).thenReply(() => done)),
    allot: command("allot", s.string, Done, (v: Vehicles, dealer) => v.allot(dealer)),
    handOver: command("hand-over", s.string, Done, (v: Vehicles, installation) => v.handOver(installation)),
    get: query("get", Vehicle, (v: Vehicles) => v.effects.reply(v.state)),
  }

  emptyState(): Vehicle {
    return { steps: [], dealer: null, writtenAt: [] }
  }

  applyEvent(vehicle: Vehicle, event: VehicleEvent, context?: EventContext): Vehicle {
    // Where this event was written, on replay as well as when it is persisted here.
    const writtenAt = [...vehicle.writtenAt, context?.installation ?? ""]
    switch (event.type) {
      case "StepAdded":
        return { ...vehicle, steps: [...vehicle.steps, event.step], writtenAt }
      case "Allotted":
        return { ...vehicle, dealer: event.dealer, writtenAt }
    }
  }

  /** Allots the vehicle to a dealership, whose installation holds a copy from now on. */
  allot(dealer: string) {
    // docs:start replicate-to
    return this.effects.persist({ type: "Allotted", dealer }).replicateTo(dealer).thenReply(() => done)
    // docs:end replicate-to
  }

  /** Ships the vehicle: its home is the dealership's installation from now on. */
  handOver(installation: string) {
    // docs:start hand-over
    return this.effects.handOverTo(installation).thenReply(() => done)
    // docs:end hand-over
  }
}

export const Build = s.record("Build", { vehicle: s.string, step: s.string, installation: s.string, local: s.boolean })
export type Build = Infer<typeof Build>

/** Publishes each build step from every installation that holds the vehicle, saying where it was written. */
export class BuildsConsumer extends Consumer<VehicleEvent, Build> {
  static readonly componentId = "builds"
  static readonly source = Vehicles
  static readonly message = Vehicles.events
  static readonly out = jsonCodec(Build, "build")
  static readonly producesTo = "vehicle-builds"
  static readonly mirroredChanges = true

  // docs:start mirrored-change
  onMessage(message: VehicleEvent) {
    // The installation that wrote the change, and whether that is this one.
    const { installation, localOrigin } = this
    if (message.type !== "StepAdded") return this.effects.ignore()
    return this.effects.produce({ vehicle: this.subject, step: message.step, installation: installation ?? "", local: localOrigin })
  }
  // docs:end mirrored-change
}
