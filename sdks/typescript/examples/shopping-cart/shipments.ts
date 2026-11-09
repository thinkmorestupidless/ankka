// Which dealer each vehicle was shipped to: a keyed view over the fleet, at its second version.
import { KeyedView, jsonCodec, on, s, type Infer } from "ankka"
import { VehicleEvent, Vehicles } from "./fleet.ts"

export const ShipmentRow = s.record("ShipmentRow", { vehicle: s.string, dealer: s.string })
export type ShipmentRow = Infer<typeof ShipmentRow>

// docs:start version
export class Shipments extends KeyedView<ShipmentRow> {
  static readonly componentId = "shipments"
  static readonly row = jsonCodec(ShipmentRow, "shipment-row")
  // Raised from 1: the table is emptied once and every vehicle is read again from its beginning.
  static readonly version = 2
  // docs:end version

  static readonly sources = [on(Vehicles, VehicleEvent, (v: Shipments, event) => v.onVehicle(event))]

  onVehicle(event: VehicleEvent) {
    if (event.type !== "Allotted") return this.effects.ignore()
    return this.effects.updateRow(this.subject, { vehicle: this.subject, dealer: event.dealer })
  }
}
