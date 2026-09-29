//! A view: fed the entity's events in order, keeping one row per item — the read side an entity
//! cannot answer on its own. Rows live in Postgres, beside the journal, and belong to the runtime.

use ankka::effects::view;
use ankka::prelude::*;

use crate::domain::ItemEvent;
use crate::item_entity::ItemEntity;

/// One row per item.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct ItemRow {
    pub id: String,
    pub name: String,
    pub count: i32,
}

pub struct ItemRows;

impl View for ItemRows {
    type Row = ItemRow;
    type Event = ItemEvent;
    const COMPONENT_ID: &'static str = "item-rows";
    const ROW_MANIFEST: Option<&'static str> = Some("item-row");

    fn source() -> Source {
        Source::of(ItemEntity)
    }

    fn on_event(row: Option<ItemRow>, event: ItemEvent, ctx: &Context) -> ViewEffect<ItemRow> {
        let id = ctx.metadata().subject().unwrap_or_default();
        let current =
            row.unwrap_or_else(|| ItemRow { id: id.to_string(), name: String::new(), count: 0 });
        match event {
            ItemEvent::ItemAdded { name, count } => {
                view::update_row(ItemRow { name, count: current.count + count, ..current })
            }
            ItemEvent::ItemRemoved { count } => {
                view::update_row(ItemRow { count: current.count - count, ..current })
            }
        }
    }

    fn queries() -> Vec<&'static str> {
        vec!["by-id", "all"]
    }
}
