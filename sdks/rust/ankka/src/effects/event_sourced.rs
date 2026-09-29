//! Effects for event sourced entities: persist events, then decide what to reply.
//!
//! State changes only by persisting an event — there is deliberately no `update_state` here. A
//! [`ReadOnlyEffect`] provably persists nothing: it has no events to hold, and a query handler must
//! return one, so a persisting query is a type error rather than a convention.

use super::common::{CommandError, ErrorCode, Outcome, Retention, constant, from_state};
use crate::codec::time::Duration;
use crate::context::Metadata;

/// What a command handler decided: the events to persist, what happens to the entity, and what to
/// answer. A value: building one persists nothing and computes nothing.
pub struct Effect<E, R> {
    pub(crate) events: Vec<E>,
    pub(crate) retention: Option<Retention>,
    pub(crate) outcome: Outcome<R>,
}

/// What a query handler decided: an answer, and nothing else.
pub struct ReadOnlyEffect<R> {
    pub(crate) outcome: Outcome<R>,
}

impl<E, R> Effect<E, R> {
    /// The events this effect persists.
    pub fn events(&self) -> &[E] {
        &self.events
    }

    /// What happens to the entity afterwards, if anything.
    pub fn retention(&self) -> Option<Retention> {
        self.retention
    }

    /// The outcome: a reply, no reply, or a refusal.
    pub fn outcome(&self) -> &Outcome<R> {
        &self.outcome
    }
}

impl<E, R: 'static> Effect<E, R> {
    /// The same effect with its reply transformed; its events and retention unchanged.
    pub fn map_reply<R2: 'static>(self, f: impl FnOnce(R) -> R2 + 'static) -> Effect<E, R2> {
        Effect {
            events: self.events,
            retention: self.retention,
            outcome: self.outcome.map(f),
        }
    }
}

impl<R> ReadOnlyEffect<R> {
    /// The outcome: a reply, no reply, or a refusal.
    pub fn outcome(&self) -> &Outcome<R> {
        &self.outcome
    }
}

impl<R: 'static> ReadOnlyEffect<R> {
    /// The same effect with its reply transformed.
    pub fn map_reply<R2: 'static>(self, f: impl FnOnce(R) -> R2 + 'static) -> ReadOnlyEffect<R2> {
        ReadOnlyEffect {
            outcome: self.outcome.map(f),
        }
    }
}

/// A read-only effect is an effect that persists nothing, so a command may answer with one.
impl<E, R> From<ReadOnlyEffect<R>> for Effect<E, R> {
    fn from(effect: ReadOnlyEffect<R>) -> Effect<E, R> {
        Effect {
            events: Vec::new(),
            retention: None,
            outcome: effect.outcome,
        }
    }
}

/// Events and retention gathered before the reply is chosen.
pub struct Persist<E> {
    events: Vec<E>,
    retention: Option<Retention>,
}

impl<E> Persist<E> {
    /// One more event, after the others.
    pub fn and(mut self, event: E) -> Persist<E> {
        self.events.push(event);
        self
    }

    /// Delete the entity once the events are persisted.
    pub fn delete_entity(mut self) -> Persist<E> {
        self.retention = Some(Retention::DeleteNow);
        self
    }

    /// Delete the entity once `duration` passes with no command.
    pub fn expire_after(mut self, duration: Duration) -> Persist<E> {
        self.retention = Some(Retention::ExpireAfter(duration));
        self
    }

    /// Reply with a value computed from the state *after* the events are applied. The closure
    /// names the entity's state type: `|cart: &Cart| cart.items.len()`.
    pub fn then_reply<S: 'static, R>(self, reply: impl FnOnce(&S) -> R + 'static) -> Effect<E, R> {
        self.then(Outcome::Reply(from_state(reply), Metadata::default()))
    }

    /// Reply with a value computed from the state after the events, and metadata of the handler's
    /// choosing.
    pub fn then_reply_with<S: 'static, R>(
        self,
        reply: impl FnOnce(&S) -> R + 'static,
        metadata: Metadata,
    ) -> Effect<E, R> {
        self.then(Outcome::Reply(from_state(reply), metadata))
    }

    /// Reply with this value.
    pub fn then_reply_value<R: 'static>(self, value: R) -> Effect<E, R> {
        self.then(Outcome::Reply(constant(value), Metadata::default()))
    }

    /// Answer nothing but that the command was handled.
    pub fn then_no_reply<R>(self) -> Effect<E, R> {
        self.then(Outcome::NoReply)
    }

    fn then<R>(self, outcome: Outcome<R>) -> Effect<E, R> {
        Effect {
            events: self.events,
            retention: self.retention,
            outcome,
        }
    }
}

/// Persist `event`, then choose a reply.
pub fn persist<E>(event: E) -> Persist<E> {
    Persist {
        events: vec![event],
        retention: None,
    }
}

/// Persist `events`, in order, then choose a reply.
pub fn persist_all<E>(events: impl IntoIterator<Item = E>) -> Persist<E> {
    Persist {
        events: events.into_iter().collect(),
        retention: None,
    }
}

/// Delete the entity without recording a final event, then choose a reply.
pub fn delete_entity<E>() -> Persist<E> {
    Persist {
        events: Vec::new(),
        retention: Some(Retention::DeleteNow),
    }
}

/// Reply with this value and persist nothing.
pub fn reply<R: 'static>(value: R) -> ReadOnlyEffect<R> {
    ReadOnlyEffect {
        outcome: Outcome::Reply(constant(value), Metadata::default()),
    }
}

/// Reply with a value computed from the current state and persist nothing.
pub fn reply_from<S: 'static, R>(reply: impl FnOnce(&S) -> R + 'static) -> ReadOnlyEffect<R> {
    ReadOnlyEffect {
        outcome: Outcome::Reply(from_state(reply), Metadata::default()),
    }
}

/// Answer nothing but that the command was handled, and persist nothing.
pub fn no_reply<R>() -> ReadOnlyEffect<R> {
    ReadOnlyEffect {
        outcome: Outcome::NoReply,
    }
}

/// Refuse the command: nothing is persisted, and the caller sees the code and the message. Returned
/// from a command handler with `.into()`.
pub fn error<R>(code: ErrorCode, message: impl Into<String>) -> ReadOnlyEffect<R> {
    ReadOnlyEffect {
        outcome: Outcome::Error(CommandError::new(code, message)),
    }
}
