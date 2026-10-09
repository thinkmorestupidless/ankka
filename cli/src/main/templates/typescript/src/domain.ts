// The stub domain: an item with a name and a count. Replace it with yours — this file, the entity that
// keeps it, the endpoint that exposes it and the view that lists it are the whole shape of an ankka
// service, and they are all in this project.
//
// A shape is declared once: `s.record(...)` describes the JSON, and `Infer<typeof Item>` is the
// TypeScript type. Field names are the stored format, so keep them once data exists.
import { s, type Infer } from "ankka"

// The owner's email is a personal field: one person's data, kept encrypted under that person's key and
// read as erased everywhere once they are erased. The store never holds it in the clear.
export const Item = s.record("Item", { id: s.string, name: s.string, count: s.int, owner: s.option(s.personal(s.string)) })
export type Item = Infer<typeof Item>

/** What a read of an item answers: the owner's email, `"erased"` once its owner is erased, or `null`. */
export const ItemView = s.record("ItemView", { id: s.string, name: s.string, count: s.int, owner: s.option(s.string) })
export type ItemView = Infer<typeof ItemView>

// What has happened to an item. Events are the durable record; state is derived from them. A sum type
// is a discriminated union on `type`, which is also what the journal holds.
export const ItemEvent = s.sumType("ItemEvent", {
  ItemAdded: { name: s.string, count: s.int },
  ItemRemoved: { count: s.int },
  OwnerSet: { email: s.personal(s.string) },
})
export type ItemEvent = Infer<typeof ItemEvent>

/** The request body of `add-item`. */
export const AddItem = s.record("AddItem", { name: s.string, count: s.int })
export type AddItem = Infer<typeof AddItem>

/** The request body of `set-owner`: an opaque id for the person, which names them as a data subject, and their email. */
export const SetOwner = s.record("SetOwner", { user: s.string, email: s.string })
export type SetOwner = Infer<typeof SetOwner>

/** The request body of `remove-item`. */
export const RemoveItem = s.record("RemoveItem", { count: s.int })
export type RemoveItem = Infer<typeof RemoveItem>
