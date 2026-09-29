//! The stub domain: an item with a name and a count. Replace it with yours — this file, the entity
//! that keeps it, the endpoint that exposes it and the view that lists it are the whole shape of an
//! ankka service, and they are all in this project.
//!
//! Field names are the stored format, so keep them once data exists. A sum type carries
//! `#[serde(tag = "type")]`, which is also what the journal holds.

use ankka::prelude::*;

/// An item: what its events fold to.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Item {
    pub id: String,
    pub name: String,
    pub count: i32,
}

/// What has happened to an item. Events are the durable record; state is derived from them.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(tag = "type")]
pub enum ItemEvent {
    ItemAdded { name: String, count: i32 },
    ItemRemoved { count: i32 },
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
