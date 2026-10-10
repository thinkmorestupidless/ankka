//! Effects for workflows. A command handler may change the state and start the workflow on a step
//! ([`update_state`], [`transition_to`]); a step decides what comes next ([`step`]). The runtime
//! journals every transition and runs the steps, applying the timeouts and recovery the workflow
//! declared.
//!
//! ```ignore
//! effects::workflow::update_state(checkout).transition_to("reserve").then_reply_value(Done)
//! step_effects::update_state(checkout).then_transition_to("charge")
//! step_effects::update_state(checkout).then_pause_for(Duration::of_millis(1500), "charge")
//! step_effects::update_state(checkout).then_end()
//! ```

use serde::Serialize;

use super::common::{CommandError, Outcome, constant, from_state};
use super::event_sourced::ReadOnlyEffect;
use crate::codec::time::Duration;
use crate::codec::{EncodingError, encode_payload};
use crate::context::Metadata;
use crate::proto::Payload;

/// A step to go to, with the input it is handed, encoded when the effect is built.
#[derive(Debug, Clone)]
pub struct StepRef {
    /// The step's name.
    pub step: String,
    /// Its input, if it takes one; an encoding failure is a fault when the effect is answered.
    pub input: Option<Result<Payload, EncodingError>>,
}

impl StepRef {
    fn named(step: &str) -> StepRef {
        StepRef {
            step: step.to_string(),
            input: None,
        }
    }

    fn with<I: Serialize + 'static>(step: &str, input: I) -> StepRef {
        StepRef {
            step: step.to_string(),
            input: Some(encode_payload(&input)),
        }
    }
}

/// What a workflow command decided: a new state, a step to start, and what to answer.
pub struct WorkflowEffect<S, R> {
    pub(crate) new_state: Option<S>,
    pub(crate) transition: Option<StepRef>,
    pub(crate) outcome: Outcome<R>,
}

impl<S, R> WorkflowEffect<S, R> {
    /// The state this effect writes, if it writes one.
    pub fn new_state(&self) -> Option<&S> {
        self.new_state.as_ref()
    }

    /// The step it starts, if any.
    pub fn transition(&self) -> Option<&StepRef> {
        self.transition.as_ref()
    }
}

/// A read-only effect changes nothing, so a command may answer with one.
impl<S, R> From<ReadOnlyEffect<R>> for WorkflowEffect<S, R> {
    fn from(effect: ReadOnlyEffect<R>) -> WorkflowEffect<S, R> {
        WorkflowEffect {
            new_state: None,
            transition: None,
            outcome: effect.outcome,
        }
    }
}

/// A command's change, before the reply is chosen.
pub struct Change<S> {
    new_state: Option<S>,
    transition: Option<StepRef>,
}

impl<S> Change<S> {
    /// Start the workflow on `step`, with no input.
    pub fn transition_to(mut self, step: &str) -> Change<S> {
        self.transition = Some(StepRef::named(step));
        self
    }

    /// Start the workflow on `step`, handing it `input`.
    pub fn transition_to_with<I: Serialize + 'static>(mut self, step: &str, input: I) -> Change<S> {
        self.transition = Some(StepRef::with(step, input));
        self
    }

    /// Reply with a value computed from the state after the change.
    pub fn then_reply<R>(self, reply: impl FnOnce(&S) -> R + 'static) -> WorkflowEffect<S, R>
    where
        S: 'static,
    {
        self.then(Outcome::Reply(from_state(reply), Metadata::default()))
    }

    /// Reply with this value.
    pub fn then_reply_value<R: 'static>(self, value: R) -> WorkflowEffect<S, R> {
        self.then(Outcome::Reply(constant(value), Metadata::default()))
    }

    fn then<R>(self, outcome: Outcome<R>) -> WorkflowEffect<S, R> {
        WorkflowEffect {
            new_state: self.new_state,
            transition: self.transition,
            outcome,
        }
    }
}

/// Replace the workflow's state, then (optionally) start a step and choose a reply.
pub fn update_state<S>(state: S) -> Change<S> {
    Change {
        new_state: Some(state),
        transition: None,
    }
}

/// Start the workflow on `step` without changing its state, then choose a reply.
pub fn transition_to<S>(step: &str) -> Change<S> {
    Change {
        new_state: None,
        transition: Some(StepRef::named(step)),
    }
}

/// What a step decided comes next.
#[derive(Debug, Clone)]
pub enum Next {
    /// Run this step.
    TransitionTo(StepRef),
    /// Wait for a command, or until `after` passes and then run `on_timeout`.
    Pause {
        /// How long to wait before `on_timeout`; forever if absent.
        after: Option<Duration>,
        /// What runs when the wait ends.
        on_timeout: Option<StepRef>,
    },
    /// The workflow is finished.
    End,
    /// The workflow failed, as the step said.
    Fail(CommandError),
}

/// What a step decided: a new state, and what comes next.
pub struct StepEffect<S> {
    pub(crate) new_state: Option<S>,
    pub(crate) next: Next,
}

impl<S> StepEffect<S> {
    /// The state this step writes, if it writes one.
    pub fn new_state(&self) -> Option<&S> {
        self.new_state.as_ref()
    }

    /// What comes next.
    pub fn next(&self) -> &Next {
        &self.next
    }
}

/// Step effects: `step_effects::update_state(s).then_transition_to("next")`.
pub mod step {
    use super::*;

    /// A step's new state, before what comes next is chosen.
    pub struct StepChange<S> {
        new_state: Option<S>,
    }

    /// Replace the workflow's state, then choose what comes next.
    pub fn update_state<S>(state: S) -> StepChange<S> {
        StepChange {
            new_state: Some(state),
        }
    }

    impl<S> StepChange<S> {
        /// Run `step` next, with no input.
        pub fn then_transition_to(self, step: &str) -> StepEffect<S> {
            self.then(Next::TransitionTo(StepRef::named(step)))
        }

        /// Run `step` next, handing it `input`.
        pub fn then_transition_to_with<I: Serialize + 'static>(
            self,
            step: &str,
            input: I,
        ) -> StepEffect<S> {
            self.then(Next::TransitionTo(StepRef::with(step, input)))
        }

        /// Wait for a command, however long.
        pub fn then_pause(self) -> StepEffect<S> {
            self.then(Next::Pause {
                after: None,
                on_timeout: None,
            })
        }

        /// Wait for a command, or for `after`, and then run `on_timeout`.
        pub fn then_pause_for(self, after: Duration, on_timeout: &str) -> StepEffect<S> {
            self.then(Next::Pause {
                after: Some(after),
                on_timeout: Some(StepRef::named(on_timeout)),
            })
        }

        /// The workflow is finished.
        pub fn then_end(self) -> StepEffect<S> {
            self.then(Next::End)
        }

        /// The workflow fails, as the step decided, with the state recorded: a compensation that
        /// says what happened before it ends the workflow, so a reader of the workflow sees it.
        pub fn then_fail(self, error: CommandError) -> StepEffect<S> {
            self.then(Next::Fail(error))
        }

        fn then(self, next: Next) -> StepEffect<S> {
            StepEffect {
                new_state: self.new_state,
                next,
            }
        }
    }

    /// Run `step` next, the state unchanged.
    pub fn transition_to<S>(step: &str) -> StepEffect<S> {
        StepEffect {
            new_state: None,
            next: Next::TransitionTo(StepRef::named(step)),
        }
    }

    /// The workflow is finished, the state unchanged.
    pub fn end<S>() -> StepEffect<S> {
        StepEffect {
            new_state: None,
            next: Next::End,
        }
    }

    /// The workflow fails, as the step decided. A step that panics instead is retried and failed
    /// over as the workflow declared; this is the step saying it is over.
    pub fn fail<S>(error: CommandError) -> StepEffect<S> {
        StepEffect {
            new_state: None,
            next: Next::Fail(error),
        }
    }
}
