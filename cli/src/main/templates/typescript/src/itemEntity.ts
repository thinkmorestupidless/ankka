// An event sourced entity: one instance per item id, holding the item's state as the fold of its
// events. A handler returns an *effect* — a description of what should happen — and never performs I/O
// itself; that is what lets `test/item.test.ts` drive it with no sidecar.
import { Done, done, ErrorCode, EventSourcedEntity, command, jsonCodec, present, query, valueOf } from "ankka"
import { AddItem, Item, ItemEvent, ItemView, RemoveItem, SetOwner } from "./domain.ts"

export class ItemEntity extends EventSourcedEntity<Item, ItemEvent> {
  static readonly componentId = "item"
  static readonly state = jsonCodec(Item, "item")
  static readonly events = jsonCodec(ItemEvent, "item-event")

  // The first argument of `command` and `query` is the wire name — the versioning boundary. Rename the
  // property or the method freely; change the wire name and in-flight callers break.
  static readonly handlers = {
    addItem: command("add-item", AddItem, Done, (item: ItemEntity, request) => item.addItem(request)),
    removeItem: command("remove-item", RemoveItem, Done, (item: ItemEntity, request) => item.removeItem(request)),
    setOwner: command("set-owner", SetOwner, Done, (item: ItemEntity, request) => item.setOwner(request)),
    // A query's function must return a read-only effect: one that persists does not compile.
    getItem: query("get-item", ItemView, (item: ItemEntity) => item.effects.reply(item.view())),
  }

  emptyState(): Item {
    return { id: this.entityId, name: "", count: 0, owner: null }
  }

  applyEvent(item: Item, event: ItemEvent): Item {
    switch (event.type) {
      case "ItemAdded":
        return { ...item, name: event.name, count: item.count + event.count }
      case "ItemRemoved":
        return { ...item, count: item.count - event.count }
      case "OwnerSet":
        return { ...item, owner: event.email }
    }
  }

  /** The item as a read answers it: the owner's email only while they are not erased. */
  view(): ItemView {
    const { owner, ...rest } = this.state
    return { ...rest, owner: owner === null ? null : (valueOf(owner) ?? "erased") }
  }

  /** A command: validated, then persisted, then answered. */
  addItem(request: AddItem) {
    if (request.count <= 0) return this.effects.error(`count must be greater than zero, was ${request.count}`)
    return this.effects.persist({ type: "ItemAdded", name: request.name, count: request.count }).thenReply(() => done)
  }

  /** The email is the person's, so it is written under their subject, `user/<id>`, and never in the clear. */
  setOwner(request: SetOwner) {
    if (request.user === "" || request.email === "") return this.effects.error("an owner needs a user and an email")
    return this.effects.persist({ type: "OwnerSet", email: present(`user/${request.user}`, request.email) }).thenReply(() => done)
  }

  removeItem(request: RemoveItem) {
    if (request.count <= 0) return this.effects.error(`count must be greater than zero, was ${request.count}`)
    if (request.count > this.state.count) return this.effects.error(`only ${this.state.count} to remove`, ErrorCode.Conflict)
    return this.effects.persist({ type: "ItemRemoved", count: request.count }).thenReply(() => done)
  }
}
