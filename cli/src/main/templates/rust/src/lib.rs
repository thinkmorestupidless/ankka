//! The whole service definition. Registration is explicit — nothing is discovered by scanning — so
//! this is also the complete inventory of what the service hosts, and a component you forget is
//! simply not there.
//!
//! `cargo module` builds this crate to a WebAssembly module, which the ankka runtime loads: locally
//! from `docker compose up -d runtime`; on a platform, from the image `Dockerfile` builds.

pub mod api;
pub mod domain;
pub mod item_entity;
pub mod item_rows;

use ankka::Service;

pub fn build() -> Service {
    Service::new("ankka-rust")
        .register(item_entity::ItemEntity)
        .register(item_rows::ItemRows)
        .endpoint(api::ItemApi)
}

ankka::service!(build);
