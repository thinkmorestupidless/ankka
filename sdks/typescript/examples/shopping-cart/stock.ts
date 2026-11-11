// A view whose source is a topic rather than an entity: each message moves a product's stock, and the
// row is keyed by the message's subject, the product.
import { View, jsonCodec, s, type Infer } from "ankka"

// docs:start topic-view
export const StockEvent = s.record("StockEvent", { productId: s.string, delta: s.int })
export type StockEvent = Infer<typeof StockEvent>

export const StockRow = s.record("StockRow", { productId: s.string, level: s.int })
export type StockRow = Infer<typeof StockRow>

export class StockLevels extends View<StockEvent, StockRow> {
  static readonly componentId = "stock-levels"
  static readonly topic = "stock-events"
  static readonly events = jsonCodec(StockEvent, "stock-event")
  static readonly row = jsonCodec(StockRow, "stock-row")

  onChange(event: StockEvent) {
    const current = this.row ?? { productId: this.subject, level: 0 }
    return this.effects.updateRow({ ...current, level: current.level + event.delta })
  }
}
// docs:end topic-view
