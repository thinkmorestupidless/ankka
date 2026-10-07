//! Effects: what a handler decides, as values. Building one performs no I/O, reads no state and
//! calls nothing; the runtime interprets it, and [`materialise`] is the one reduction both the
//! exports and the unit testkit use.
//!
//! The event sourced effects are this module's own functions, so a handler reads
//! `effects::persist(event).then_reply(…)`; a key value entity's too (`effects::update_state`,
//! `effects::delete_state`). A workflow's are in [`workflow`] (its steps' in [`step`], which the
//! prelude names `step_effects`), and a view's, a consumer's and an agent's in their own modules.

pub mod agent;
pub mod common;
pub mod consumer;
pub mod event_sourced;
pub mod http;
pub mod key_value;
pub mod keyed_view;
pub mod materialise;
pub mod view;
pub mod workflow;

pub use common::{CommandError, ErrorCode, Outcome, Retention};
pub use event_sourced::{
    Effect, Persist, ReadOnlyEffect, delete_entity, error, no_reply, persist, persist_all, reply,
    reply_from,
};
pub use http::{HttpProblem, IntoResponse, Response};
pub use key_value::{KeyValueEffect, delete_state, materialise_key_value, update_state};
pub use materialise::{Answer, Materialised, materialise_event_sourced};
pub use workflow::{StepEffect, WorkflowEffect, step};
