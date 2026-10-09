// A view: fed the entity's events in order, keeping one row per item — the read side an entity cannot
// answer on its own. Rows live in Postgres, beside the journal, and belong to the sidecar.
import { View, jsonCodec, s, type Infer } from "ankka"
import { ItemEvent } from "./domain.ts"
import { ItemEntity } from "./itemEntity.ts"

export const ItemRow = s.record("ItemRow", { id: s.string, name: s.string, count: s.int })
export type ItemRow = Infer<typeof ItemRow>

export class ItemRows extends View<ItemEvent, ItemRow> {
  static readonly componentId = "item-rows"
  static readonly source = ItemEntity
  static readonly events = ItemEntity.events
  static readonly row = jsonCodec(ItemRow, "item-row")
  static readonly queries = ["by-id", "all"]

  onChange(event: ItemEvent) {
    const current = this.row ?? { id: this.subject, name: "", count: 0 }
    switch (event.type) {
      case "ItemAdded":
        return this.effects.updateRow({ ...current, name: event.name, count: current.count + event.count })
      case "ItemRemoved":
        return this.effects.updateRow({ ...current, count: current.count - event.count })
      case "OwnerSet":
        return this.effects.ignore() // the listing shows no owner, so it keeps nothing personal
    }
  }
}
