//! Build ankka services in Rust. A service is a WebAssembly module the ankka runtime loads and
//! hosts: its entities, workflows, views, consumers, timed actions, agents and endpoints are
//! declared here and handled in the module, and the runtime does everything else — the journal,
//! sharding, HTTP, the model.
//!
//! A service declares its components, registers them, and emits the module's exports once:
//!
//! ```ignore
//! use ankka::prelude::*;
//!
//! fn build() -> Service {
//!     Service::new("ankka-rust").register(ShoppingCart)
//! }
//! ankka::service!(build);
//! ```
//!
//! It is built for `wasm32-unknown-unknown`. Natively the same code builds without the exports,
//! which is how its unit tests run.

pub mod abi;
pub mod client;
pub mod codec;
pub mod components;
pub mod config;
pub mod context;
pub mod effects;
pub mod graph;
pub mod prelude;
pub mod proto;
pub mod secrets;
pub mod service;
pub mod start_from;
pub mod testkit;

pub use client::Client;
pub use codec::time::{Duration, Instant, LocalDate, LocalDateTime};
pub use codec::{Bytes, Done};
pub use config::config;
pub use context::{Context, Metadata};
pub use secrets::Secrets;
pub use service::{Problem, Service};
pub use start_from::StartFrom;

/// serde, as this library uses it: the derives a service's types need.
pub use serde;
/// serde_json, as this library uses it: the JSON a task's record is read as.
pub use serde_json;
