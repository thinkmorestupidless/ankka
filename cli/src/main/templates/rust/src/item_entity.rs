//! An event sourced entity: one instance per item id, holding the item's state as the fold of its
//! events. A handler returns an *effect* — a description of what should happen — and never performs
//! I/O itself; that is what lets `tests/item.rs` drive it with nothing running.

use ankka::prelude::*;

use crate::domain::{AddItem, Item, ItemEvent, RemoveItem};

pub struct ItemEntity;

impl ItemEntity {
    /// A command: validated, then persisted, then answered.
    fn add_item(_: &Item, request: AddItem, _: &Context) -> Effect<ItemEvent, Done> {
        if request.count <= 0 {
            let message = format!("count must be greater than zero, was {}", request.count);
            return effects::error(ErrorCode::BadRequest, message).into();
        }
        let event = ItemEvent::ItemAdded { name: request.name, count: request.count };
        effects::persist(event).then_reply_value(Done)
    }

    fn remove_item(item: &Item, request: RemoveItem, _: &Context) -> Effect<ItemEvent, Done> {
        if request.count <= 0 {
            let message = format!("count must be greater than zero, was {}", request.count);
            return effects::error(ErrorCode::BadRequest, message).into();
        }
        if request.count > item.count {
            let message = format!("only {} to remove", item.count);
            return effects::error(ErrorCode::Conflict, message).into();
        }
        effects::persist(ItemEvent::ItemRemoved { count: request.count }).then_reply_value(Done)
    }

    /// A query can only return a read-only effect: one that persists does not compile.
    fn get_item(item: &Item, _: (), _: &Context) -> ReadOnlyEffect<Item> {
        effects::reply(item.clone())
    }
}

impl EventSourcedEntity for ItemEntity {
    type State = Item;
    type Event = ItemEvent;
    const COMPONENT_ID: &'static str = "item";
    const STATE_MANIFEST: Option<&'static str> = Some("item");
    const EVENT_MANIFEST: Option<&'static str> = Some("item-event");

    fn empty_state(id: &str) -> Item {
        Item { id: id.to_string(), name: String::new(), count: 0 }
    }

    fn apply(item: Item, event: &ItemEvent) -> Item {
        match event {
            ItemEvent::ItemAdded { name, count } => {
                Item { name: name.clone(), count: item.count + count, ..item }
            }
            ItemEvent::ItemRemoved { count } => Item { count: item.count - count, ..item },
        }
    }

    // The first argument of `command` and `query` is the wire name — the versioning boundary.
    // Rename the function freely; change the wire name and in-flight callers break.
    fn handlers() -> Handlers<ItemEntity> {
        Handlers::new()
            .command("add-item", ItemEntity::add_item)
            .command("remove-item", ItemEntity::remove_item)
            .query("get-item", ItemEntity::get_item)
    }
}
