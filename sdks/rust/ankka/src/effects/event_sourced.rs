//! Effects for event sourced entities: persist events, then decide what to reply.
//!
//! State changes only by persisting an event — there is deliberately no `update_state` here. A
//! [`ReadOnlyEffect`] provably persists nothing: it has no events to hold, and a query handler must
//! return one, so a persisting query is a type error rather than a convention.

use super::common::{CommandError, ErrorCode, Outcome, Retention, constant, from_state};
use crate::codec::time::Duration;
use crate::context::Metadata;
use crate::proto;

/// What a replicated entity's command asks of its home beside the events it persists (protocol
/// 1.19): one handover, or one change to the replication set.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReplicationChange {
    /// Hand the home to this installation.
    HandOver {
        /// The installation that becomes the home.
        to: String,
    },
    /// Add installations to the replication set and remove others.
    Set {
        /// Installations that start holding a copy.
        add: Vec<String>,
        /// Installations that stop being written to; each keeps the copy it has.
        remove: Vec<String>,
    },
}

impl ReplicationChange {
    /// The protocol's form.
    pub fn to_proto(&self) -> proto::ReplicationChange {
        let change = match self {
            ReplicationChange::HandOver { to } => {
                proto::replication_change::Change::HandOverTo(to.clone())
            }
            ReplicationChange::Set { add, remove } => {
                proto::replication_change::Change::Set(proto::replication_change::SetChange {
                    add: add.clone(),
                    remove: remove.clone(),
                })
            }
        };
        proto::ReplicationChange {
            change: Some(change),
        }
    }
}

/// What a command handler decided: the events to persist, what happens to the entity, and what to
/// answer. A value: building one persists nothing and computes nothing.
pub struct Effect<E, R> {
    pub(crate) events: Vec<E>,
    pub(crate) retention: Option<Retention>,
    pub(crate) replication: Option<ReplicationChange>,
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

    /// The handover or change to the replication set this effect asks for, if any.
    pub fn replication(&self) -> Option<&ReplicationChange> {
        self.replication.as_ref()
    }

    /// The outcome: a reply, no reply, or a refusal.
    pub fn outcome(&self) -> &Outcome<R> {
        &self.outcome
    }
}

impl<E, R: 'static> Effect<E, R> {
    /// The same effect with its reply transformed; its events, retention and replication unchanged.
    pub fn map_reply<R2: 'static>(self, f: impl FnOnce(R) -> R2 + 'static) -> Effect<E, R2> {
        Effect {
            events: self.events,
            retention: self.retention,
            replication: self.replication,
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
            replication: None,
            outcome: effect.outcome,
        }
    }
}

/// Events, retention and a replication change gathered before the reply is chosen.
pub struct Persist<E> {
    events: Vec<E>,
    retention: Option<Retention>,
    replication: Option<ReplicationChange>,
    /// A mistake in how the effect was built, answered as a fault in place of the reply.
    problem: Option<String>,
}

impl<E> Persist<E> {
    fn of(events: Vec<E>, retention: Option<Retention>) -> Persist<E> {
        Persist {
            events,
            retention,
            replication: None,
            problem: None,
        }
    }

    /// Hand the home of this replicated entity to `installation`, once the events are persisted.
    /// The caller is answered once the handover is written; the new home takes writes once its copy
    /// has caught up to it. One handover or one change to the replication set per command.
    pub fn hand_over_to(mut self, installation: impl Into<String>) -> Persist<E> {
        match &self.replication {
            None => {
                self.replication = Some(ReplicationChange::HandOver {
                    to: installation.into(),
                })
            }
            Some(other) => self.refuse(two_changes(other)),
        }
        self
    }

    /// Add `installation` to this replicated entity's replication set: it starts holding a copy.
    pub fn replicate_to(self, installation: impl Into<String>) -> Persist<E> {
        self.change_set(vec![installation.into()], Vec::new())
    }

    /// Remove `installation` from the replication set: it is no longer written to, and keeps the
    /// copy it has.
    pub fn stop_replicating_to(self, installation: impl Into<String>) -> Persist<E> {
        self.change_set(Vec::new(), vec![installation.into()])
    }

    fn change_set(mut self, add: Vec<String>, remove: Vec<String>) -> Persist<E> {
        let (mut added, mut removed) = match self.replication.take() {
            None => (Vec::new(), Vec::new()),
            Some(ReplicationChange::Set { add, remove }) => (add, remove),
            Some(other) => {
                self.refuse(two_changes(&other));
                self.replication = Some(other);
                return self;
            }
        };
        added.extend(add);
        removed.extend(remove);
        let both: Vec<&String> = added.iter().filter(|a| removed.contains(a)).collect();
        if !both.is_empty() {
            let names: Vec<&str> = both.iter().map(|s| s.as_str()).collect();
            self.refuse(format!(
                "a command both adds and removes {} in the replication set",
                names.join(", ")
            ));
        }
        self.replication = Some(ReplicationChange::Set {
            add: added,
            remove: removed,
        });
        self
    }

    fn refuse(&mut self, problem: String) {
        self.problem.get_or_insert(problem);
    }
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
        match self.problem {
            // Built wrongly: nothing applies, and the caller is told what the handler asked.
            Some(problem) => Effect {
                events: Vec::new(),
                retention: None,
                replication: None,
                outcome: Outcome::Error(CommandError::new(ErrorCode::Internal, problem)),
            },
            None => Effect {
                events: self.events,
                retention: self.retention,
                replication: self.replication,
                outcome,
            },
        }
    }
}

fn two_changes(already: &ReplicationChange) -> String {
    format!(
        "a command asks for one handover or one change to the replication set; it asked for \
         {already:?} already"
    )
}

/// Persist `event`, then choose a reply.
pub fn persist<E>(event: E) -> Persist<E> {
    Persist::of(vec![event], None)
}

/// Persist `events`, in order, then choose a reply.
pub fn persist_all<E>(events: impl IntoIterator<Item = E>) -> Persist<E> {
    Persist::of(events.into_iter().collect(), None)
}

/// Delete the entity without recording a final event, then choose a reply.
pub fn delete_entity<E>() -> Persist<E> {
    Persist::of(Vec::new(), Some(Retention::DeleteNow))
}

/// Hand the home of this replicated entity to `installation`, persisting no event of its own.
pub fn hand_over_to<E>(installation: impl Into<String>) -> Persist<E> {
    Persist::of(Vec::new(), None).hand_over_to(installation)
}

/// Add `installation` to this replicated entity's replication set, persisting no event of its own.
pub fn replicate_to<E>(installation: impl Into<String>) -> Persist<E> {
    Persist::of(Vec::new(), None).replicate_to(installation)
}

/// Remove `installation` from the replication set, persisting no event of its own.
pub fn stop_replicating_to<E>(installation: impl Into<String>) -> Persist<E> {
    Persist::of(Vec::new(), None).stop_replicating_to(installation)
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
