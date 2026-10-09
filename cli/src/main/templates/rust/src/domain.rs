//! The stub domain: an item with a name and a count. Replace it with yours — this file, the entity
//! that keeps it, the endpoint that exposes it and the view that lists it are the whole shape of an
//! ankka service, and they are all in this project.
//!
//! Field names are the stored format, so keep them once data exists. A sum type carries
//! `#[serde(tag = "type")]`, which is also what the journal holds.

use ankka::personal::Personal;
use ankka::prelude::*;

/// An item: what its events fold to.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
pub struct Item {
    pub id: String,
    pub name: String,
    pub count: i32,
    /// The owner's email, a personal field; none until an owner is set.
    #[serde(default)]
    pub owner: Option<Personal<String>>,
}

/// What has happened to an item. Events are the durable record; state is derived from them.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(tag = "type")]
pub enum ItemEvent {
    ItemAdded {
        name: String,
        count: i32,
    },
    ItemRemoved {
        count: i32,
    },
    /// The owner's email: a *personal field*, written encrypted under its data subject's key — the
    /// owner, `user/<their id>` — and read as erased everywhere once that subject is erased.
    OwnerSet {
        owner: Personal<String>,
    },
}

/// The request body of `add-item`.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct AddItem {
    pub name: String,
    pub count: i32,
}

/// The request body of `remove-item`.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct RemoveItem {
    pub count: i32,
}

/// The request body of `set-owner`: an opaque id, which names the data subject — never put
/// anything personal in it, since it stays readable — and the email, the personal field.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct SetOwner {
    pub user: String,
    pub email: String,
}

/// What `GET /items/{id}` answers: plain values, never a personal field's stored form. The owner is
/// the email, or `"erased"`, which a reader has to handle.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct ItemRead {
    pub id: String,
    pub name: String,
    pub count: i32,
    pub owner: Option<String>,
}

impl From<Item> for ItemRead {
    fn from(item: Item) -> ItemRead {
        let owner = item.owner.map(|owner| {
            owner
                .as_ref()
                .cloned()
                .unwrap_or_else(|| "erased".to_string())
        });
        ItemRead {
            id: item.id,
            name: item.name,
            count: item.count,
            owner,
        }
    }
}
