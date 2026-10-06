//! Timed actions: what runs when a timer fires. A timer is set with `client.schedule(…)`, naming
//! the action and its input; the runtime keeps it in its timer table and calls the action when it
//! is due, again after a failure — `Err` or a panic — until it succeeds.

use std::marker::PhantomData;

use serde::de::DeserializeOwned;

use super::view::change_context;
use super::{ComponentOf, Registered, Shape, kinds};
use crate::codec::decode_payload;
use crate::context::Context;
use crate::effects::CommandError;
use crate::proto::{self, Kind, Payload};

/// A timed action. Implement it on a unit struct and register the struct's value.
pub trait TimedAction: Sized + 'static {
    /// The component's id.
    const COMPONENT_ID: &'static str;

    /// The actions, by name.
    fn actions() -> Actions<Self>;
}

type Run = Box<dyn Fn(&Payload, &Context) -> Result<(), CommandError>>;

pub(crate) struct Entry {
    pub(crate) name: String,
    pub(crate) run: Run,
}

/// A timed action's actions, by name.
pub struct Actions<C: TimedAction> {
    pub(crate) entries: Vec<Entry>,
    pub(crate) problems: Vec<String>,
    marker: PhantomData<fn() -> C>,
}

impl<C: TimedAction> Default for Actions<C> {
    fn default() -> Actions<C> {
        Actions::new()
    }
}

impl<C: TimedAction> Actions<C> {
    /// No actions yet.
    pub fn new() -> Actions<C> {
        Actions {
            entries: Vec::new(),
            problems: Vec::new(),
            marker: PhantomData,
        }
    }

    /// Action `name`, handed the input the timer was scheduled with.
    pub fn action<In, F>(mut self, name: &str, action: F) -> Actions<C>
    where
        In: DeserializeOwned + 'static,
        F: Fn(In, &Context) -> Result<(), CommandError> + 'static,
    {
        if name.is_empty() || self.entries.iter().any(|e| e.name == name) {
            self.problems.push(format!(
                "timed action '{}' declares action '{name}' {}",
                C::COMPONENT_ID,
                if name.is_empty() {
                    "with no name"
                } else {
                    "twice"
                }
            ));
            return self;
        }
        let run: Run = Box::new(move |payload, ctx| {
            let input: In = decode_payload(payload).map_err(|e| {
                CommandError::new(
                    crate::effects::ErrorCode::BadRequest,
                    format!(
                        "the input to '{}' is not a {}: {e}",
                        ctx.component_id(),
                        std::any::type_name::<In>()
                    ),
                )
            })?;
            action(input, ctx)
        });
        self.entries.push(Entry {
            name: name.to_string(),
            run,
        });
        self
    }
}

pub(crate) struct Registration<C: TimedAction> {
    actions: Actions<C>,
}

impl<C: TimedAction> ComponentOf<kinds::TimedAction> for C {
    fn kind() -> Kind {
        Kind::TimedAction
    }

    fn component_id() -> &'static str {
        C::COMPONENT_ID
    }

    fn registration() -> Box<dyn Registered> {
        Box::new(Registration::<C> {
            actions: C::actions(),
        })
    }
}

impl<C: TimedAction> Registered for Registration<C> {
    fn id(&self) -> &str {
        C::COMPONENT_ID
    }

    fn kind(&self) -> Kind {
        Kind::TimedAction
    }

    fn shape(&self) -> Shape {
        Shape::Stateless
    }

    fn to_component(&self) -> proto::Component {
        let mut handlers: Vec<proto::Handler> = self
            .actions
            .entries
            .iter()
            .map(|e| proto::Handler {
                name: e.name.clone(),
                read_only: false,
                streaming: false,
            })
            .collect();
        handlers.sort_by(|a, b| a.name.cmp(&b.name));
        proto::Component {
            kind: Kind::TimedAction as i32,
            id: C::COMPONENT_ID.to_string(),
            handlers,
            detail: Some(proto::component::Detail::TimedAction(
                proto::TimedActionDetail {},
            )),
        }
    }

    fn problems(&self) -> Vec<String> {
        let mut problems = self.actions.problems.clone();
        if C::COMPONENT_ID.is_empty() {
            problems.push("a timed action has an empty component id".to_string());
        }
        problems
    }

    fn timed_action(&self, request: proto::TimedActionRequest) -> Option<proto::TimedActionEffect> {
        use proto::timed_action_effect::Effect;
        let ctx = change_context(C::COMPONENT_ID, request.metadata.as_ref())
            .with_secrets()
            .with_services();
        let result = match self.actions.entries.iter().find(|e| e.name == request.name) {
            None => Err(CommandError::new(
                crate::effects::ErrorCode::NotFound,
                format!(
                    "timed action '{}' has no action '{}'",
                    C::COMPONENT_ID,
                    request.name
                ),
            )),
            Some(entry) => (entry.run)(&request.payload.unwrap_or_default(), &ctx),
        };
        let effect = match result {
            Ok(()) => Effect::Done(proto::Empty {}),
            Err(error) => Effect::Fail(error.to_proto()),
        };
        Some(proto::TimedActionEffect {
            effect: Some(effect),
        })
    }
}
