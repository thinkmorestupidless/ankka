//! The one reduction of an event sourced effect: fold its events into the state and compute its
//! reply from the result. The unit testkit shows what this answers, and the exports encode what this
//! answers, so the two cannot disagree about what a handler did.

use super::common::{CommandError, Outcome, Retention};
use super::event_sourced::Effect;
use crate::context::Metadata;

/// What an effect came to, applied to a state.
#[derive(Debug)]
pub struct Materialised<S, E, R> {
    /// The events to persist, in order; none after a refusal.
    pub events: Vec<E>,
    /// The state after the events; the state before them after a refusal.
    pub new_state: S,
    /// What happens to the entity afterwards; nothing after a refusal.
    pub retention: Option<Retention>,
    /// The answer.
    pub outcome: Answer<R>,
}

/// An outcome with its reply computed.
#[derive(Debug, Clone, PartialEq)]
pub enum Answer<R> {
    /// The reply, and the metadata the handler set on it.
    Reply(R, Metadata),
    /// No reply.
    NoReply,
    /// A refusal: nothing was persisted.
    Error(CommandError),
}

/// Applies `effect` to `state` through `fold`: a refusal persists nothing and leaves the state as it
/// was; anything else folds every event, in order, then computes the reply from the state after
/// them.
pub fn materialise_event_sourced<S: 'static, E, R>(
    state: S,
    fold: impl Fn(S, &E) -> S,
    effect: Effect<E, R>,
) -> Materialised<S, E, R> {
    match effect.outcome {
        Outcome::Error(error) => Materialised {
            events: Vec::new(),
            new_state: state,
            retention: None,
            outcome: Answer::Error(error),
        },
        outcome => {
            let new_state = effect.events.iter().fold(state, &fold);
            let answer = match outcome {
                Outcome::Reply(reply, metadata) => Answer::Reply(reply(&new_state), metadata),
                Outcome::NoReply => Answer::NoReply,
                Outcome::Error(_) => unreachable!("handled above"),
            };
            Materialised {
                events: effect.events,
                new_state,
                retention: effect.retention,
                outcome: answer,
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::effects::{ErrorCode, event_sourced as effects};

    fn fold(total: i32, event: &i32) -> i32 {
        total + event
    }

    #[test]
    fn the_reply_sees_the_state_after_the_events() {
        let effect = effects::persist(2)
            .and(3)
            .then_reply(|total: &i32| total * 10);
        let m = materialise_event_sourced(1, fold, effect);
        assert_eq!(m.events, vec![2, 3]);
        assert_eq!(m.new_state, 6);
        assert_eq!(m.outcome, Answer::Reply(60, Metadata::default()));
    }

    #[test]
    fn a_refusal_persists_nothing_and_changes_nothing() {
        let effect: Effect<i32, i32> = effects::error(ErrorCode::Conflict, "no").into();
        let m = materialise_event_sourced(1, fold, effect);
        assert!(m.events.is_empty());
        assert_eq!(m.new_state, 1);
        assert_eq!(
            m.outcome,
            Answer::Error(CommandError::new(ErrorCode::Conflict, "no"))
        );
    }

    #[test]
    fn retention_is_carried() {
        let effect = effects::persist(1).delete_entity().then_no_reply::<()>();
        let m = materialise_event_sourced(0, fold, effect);
        assert_eq!(m.retention, Some(Retention::DeleteNow));
        assert_eq!(m.outcome, Answer::NoReply);
    }
}
