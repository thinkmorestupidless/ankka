//! Effects for key value entities: replace the state, or delete it, then decide what to reply.
//!
//! A query answers a [`ReadOnlyEffect`](super::ReadOnlyEffect), shared with the event sourced
//! entity, so a key value query that tried to change the state would not compile either.

use super::common::{Outcome, Retention, constant, from_state};
use super::event_sourced::ReadOnlyEffect;
use crate::codec::time::Duration;
use crate::context::Metadata;

/// What a key value command decided: the new state (or none), what happens to the entity, and what
/// to answer. A value: building one changes nothing.
pub struct KeyValueEffect<S, R> {
    pub(crate) new_state: Option<S>,
    pub(crate) retention: Option<Retention>,
    pub(crate) outcome: Outcome<R>,
}

impl<S, R> KeyValueEffect<S, R> {
    /// The state this effect writes, if it writes one.
    pub fn new_state(&self) -> Option<&S> {
        self.new_state.as_ref()
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

impl<S, R: 'static> KeyValueEffect<S, R> {
    /// The same effect with its reply transformed.
    pub fn map_reply<R2: 'static>(
        self,
        f: impl FnOnce(R) -> R2 + 'static,
    ) -> KeyValueEffect<S, R2> {
        KeyValueEffect {
            new_state: self.new_state,
            retention: self.retention,
            outcome: self.outcome.map(f),
        }
    }
}

/// A read-only effect changes nothing, so a command may answer with one — a refusal, for one.
impl<S, R> From<ReadOnlyEffect<R>> for KeyValueEffect<S, R> {
    fn from(effect: ReadOnlyEffect<R>) -> KeyValueEffect<S, R> {
        KeyValueEffect {
            new_state: None,
            retention: None,
            outcome: effect.outcome,
        }
    }
}

/// A state change, before the reply is chosen.
pub struct Update<S> {
    new_state: Option<S>,
    retention: Option<Retention>,
}

impl<S> Update<S> {
    /// Delete the entity once `duration` passes with no command.
    pub fn expire_after(mut self, duration: Duration) -> Update<S> {
        self.retention = Some(Retention::ExpireAfter(duration));
        self
    }

    /// Reply with a value computed from the state after the change: `|p: &Profile| p.name.clone()`.
    pub fn then_reply<R>(self, reply: impl FnOnce(&S) -> R + 'static) -> KeyValueEffect<S, R>
    where
        S: 'static,
    {
        self.then(Outcome::Reply(from_state(reply), Metadata::default()))
    }

    /// Reply with this value.
    pub fn then_reply_value<R: 'static>(self, value: R) -> KeyValueEffect<S, R> {
        self.then(Outcome::Reply(constant(value), Metadata::default()))
    }

    /// Answer nothing but that the command was handled.
    pub fn then_no_reply<R>(self) -> KeyValueEffect<S, R> {
        self.then(Outcome::NoReply)
    }

    fn then<R>(self, outcome: Outcome<R>) -> KeyValueEffect<S, R> {
        KeyValueEffect {
            new_state: self.new_state,
            retention: self.retention,
            outcome,
        }
    }
}

/// Replace the state with `state`, then choose a reply.
pub fn update_state<S>(state: S) -> Update<S> {
    Update {
        new_state: Some(state),
        retention: None,
    }
}

/// Delete the entity, then choose a reply: the next command finds it fresh.
pub fn delete_state<S>() -> Update<S> {
    Update {
        new_state: None,
        retention: Some(Retention::DeleteNow),
    }
}

/// What a key value effect came to, applied to a state.
#[derive(Debug)]
pub struct KeyValueMaterialised<S, R> {
    /// The state after the command: the new one, or the old one if nothing was written.
    pub state: S,
    /// Whether the command wrote a state.
    pub written: bool,
    /// What happens to the entity afterwards; nothing after a refusal.
    pub retention: Option<Retention>,
    /// The answer.
    pub outcome: super::Answer<R>,
}

/// Applies `effect` to `state`: a refusal changes nothing; anything else takes the new state, if
/// there is one, and computes the reply from the state after the command.
pub fn materialise_key_value<S: 'static, R>(
    state: S,
    effect: KeyValueEffect<S, R>,
) -> KeyValueMaterialised<S, R> {
    match effect.outcome {
        Outcome::Error(error) => KeyValueMaterialised {
            state,
            written: false,
            retention: None,
            outcome: super::Answer::Error(error),
        },
        outcome => {
            let written = effect.new_state.is_some();
            let state = effect.new_state.unwrap_or(state);
            let answer = match outcome {
                Outcome::Reply(reply, metadata) => super::Answer::Reply(reply(&state), metadata),
                Outcome::NoReply => super::Answer::NoReply,
                Outcome::Error(_) => unreachable!("handled above"),
            };
            KeyValueMaterialised {
                state,
                written,
                retention: effect.retention,
                outcome: answer,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::effects::{Answer, ErrorCode, error};

    #[test]
    fn the_reply_sees_the_state_after_the_update() {
        let m = materialise_key_value(1, update_state(5).then_reply(|n: &i32| n * 2));
        assert_eq!((m.state, m.written), (5, true));
        assert_eq!(m.outcome, Answer::Reply(10, Metadata::default()));
    }

    #[test]
    fn a_refusal_changes_nothing() {
        let effect: KeyValueEffect<i32, i32> = error(ErrorCode::BadRequest, "no").into();
        let m = materialise_key_value(1, effect);
        assert_eq!((m.state, m.written, m.retention), (1, false, None));
    }

    #[test]
    fn a_deletion_writes_nothing_and_says_so() {
        let m = materialise_key_value(1, delete_state::<i32>().then_reply_value("done"));
        assert_eq!((m.state, m.written), (1, false));
        assert_eq!(m.retention, Some(Retention::DeleteNow));
    }
}
