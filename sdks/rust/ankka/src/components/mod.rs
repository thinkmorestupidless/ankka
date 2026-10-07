//! Declaring components. Each kind is a trait a service's type implements — the state and event
//! types, the component id, the handlers by wire name — and [`Service::register`](crate::Service)
//! takes the type's value. Nothing is discovered by scanning: a component reaches the runtime only
//! by being registered.

pub mod agent;
pub mod autonomous;
pub mod consumer;
pub mod endpoint;
pub mod event_sourced;
pub mod key_value;
pub mod keyed_view;
pub mod timed_action;
pub mod view;
pub mod workflow;

use std::any::Any;

use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::codec::Auto;
use crate::proto::{self, Kind, Payload};

pub use agent::{Agent, AgentHandlers, Guardrails, Schema, Stage, Tools};
pub use autonomous::{
    AutonomousAgent, AutonomousSettings, ResultCheck, TaskAcceptance, TaskType, Verdict,
};
pub use consumer::{Consumer, Publication};
pub use endpoint::{Acl, Caller, CallerMatcher, Endpoint, Principal, Request, Routes};
pub use event_sourced::{EventSourcedEntity, Handlers};
pub use key_value::{KeyValueEntity, KeyValueHandlers};
pub use keyed_view::{KeyedView, Sources};
pub use timed_action::{Actions, TimedAction};
pub use view::{DeclaredQuery, Source, TopicSource, View, query, table_of};
pub use workflow::{Recovery, Steps, Workflow, WorkflowHandlers, WorkflowSettings};

/// Whether a component's module instance keeps its state between calls.
///
/// The runtime holds the state either way, so a trap loses nothing. `Stateless` (the default) is
/// handed the state on every call and keeps nothing; `Stateful` is handed it once, on the first call
/// after the instance is loaded, and keeps the decoded value until the runtime passivates it —
/// cheaper per call for a large state, at the cost of the instance being pinned.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum Shape {
    /// Handed the state on every call.
    #[default]
    Stateless,
    /// Handed the state once per loaded instance, and keeps it.
    Stateful,
}

/// Markers telling the kinds apart, so one method can take a component of any kind. A service
/// never names one; they are inferred.
pub mod kinds {
    /// An event sourced entity.
    #[derive(Debug)]
    pub struct EventSourced;
    /// A key value entity.
    #[derive(Debug)]
    pub struct KeyValue;
    /// A workflow.
    #[derive(Debug)]
    pub struct Workflow;
    /// A view.
    #[derive(Debug)]
    pub struct View;
    /// A keyed view.
    #[derive(Debug)]
    pub struct KeyedView;
    /// A consumer.
    #[derive(Debug)]
    pub struct Consumer;
    /// A timed action.
    #[derive(Debug)]
    pub struct TimedAction;
    /// An agent.
    #[derive(Debug)]
    pub struct Agent;
    /// An autonomous agent.
    #[derive(Debug)]
    pub struct AutonomousAgent;
    /// A consumer that publishes a graph ([`GraphConsumer`](crate::graph::GraphConsumer)): a
    /// consumer to the runtime, a kind of its own here.
    #[derive(Debug)]
    pub struct GraphConsumer;
}

/// A type that is a component of some kind: what `Service::register` and the client take. It is
/// implemented for every type that implements a kind's trait, through that kind's marker `M`.
pub trait ComponentOf<M> {
    /// The component's kind.
    fn kind() -> Kind;

    /// The component's id.
    fn component_id() -> &'static str;

    /// The component as the service's registry holds it.
    #[doc(hidden)]
    fn registration() -> Box<dyn Registered>;
}

/// A registered component, whatever its kind: what discovery describes and the exports dispatch
/// to. Not implemented by services.
#[doc(hidden)]
pub trait Registered {
    /// The component's id.
    fn id(&self) -> &str;

    /// The component's kind.
    fn kind(&self) -> Kind;

    /// Whether its instances keep their state.
    fn shape(&self) -> Shape;

    /// Registers it with another shape than its own; only the stateful kinds have one to change.
    fn set_shape(&mut self, shape: Shape) {
        let _ = shape;
    }

    /// The component as discovery describes it.
    fn to_component(&self) -> proto::Component;

    /// What is wrong with the declaration, all at once.
    fn problems(&self) -> Vec<String>;

    /// An entity or workflow command.
    fn handle(&self, request: proto::HandleRequest, held: &mut HeldState) -> proto::HandleReply {
        let _ = held;
        proto::HandleReply {
            reply: None,
            state: request.state,
            failure: Some(unsupported(self.id(), "commands")),
        }
    }

    /// One event folded into an event sourced entity's state.
    fn fold(&self, request: proto::FoldRequest, held: &mut HeldState) -> proto::FoldReply {
        let _ = held;
        proto::FoldReply {
            state: request.state,
            failure: Some(unsupported(self.id(), "events")),
        }
    }

    /// One workflow step.
    fn run_step(&self, request: proto::StepRequest) -> proto::StepReply {
        proto::StepReply {
            reply: None,
            state: request.state,
            failure: Some(unsupported(self.id(), "steps")),
        }
    }

    /// One change to a view's source. `None`: this component is not a view.
    fn view(&self, request: proto::ViewRequest) -> Option<proto::ViewEffect> {
        let _ = request;
        None
    }

    /// One message to a consumer. `None`: this component is not a consumer.
    fn consumer(&self, request: proto::ConsumerRequest) -> Option<proto::ConsumerEffect> {
        let _ = request;
        None
    }

    /// A timer firing on a timed action. `None`: this component is not a timed action.
    fn timed_action(&self, request: proto::TimedActionRequest) -> Option<proto::TimedActionEffect> {
        let _ = request;
        None
    }

    /// An agent request, planned. `None`: this component is not an agent.
    fn plan(&self, request: proto::PlanRequest) -> Option<proto::PlanReply> {
        let _ = request;
        None
    }

    /// One of an agent's tools, run. `None`: this component is not an agent.
    fn invoke_tool(&self, request: proto::ToolRequest) -> Option<proto::ToolResult> {
        let _ = request;
        None
    }

    /// One of an agent's guardrails, checked. `None`: this component is not an agent.
    fn check_guardrail(&self, request: proto::GuardrailRequest) -> Option<proto::GuardrailResult> {
        let _ = request;
        None
    }

    /// An autonomous agent's result, decoded and held to its task type's rules. `None`: this
    /// component is not an autonomous agent.
    fn check_task_result(
        &self,
        request: proto::TaskResultRequest,
    ) -> Option<proto::TaskResultVerdict> {
        let _ = request;
        None
    }
}

/// The decoded states a stateful component's instances keep between calls, by entity id. Empty
/// for a stateless component.
#[doc(hidden)]
#[derive(Default)]
pub struct HeldState {
    states: std::collections::HashMap<String, Box<dyn Any>>,
    encoded: std::collections::HashMap<String, proto::Payload>,
}

impl HeldState {
    /// The state kept for `entity_id`, if it is of type `S`.
    pub fn get<S: 'static>(&self, entity_id: &str) -> Option<&S> {
        self.states.get(entity_id).and_then(|s| s.downcast_ref())
    }

    /// Keeps `state` for `entity_id`.
    pub fn put<S: 'static>(&mut self, entity_id: &str, state: S) {
        self.states.insert(entity_id.to_string(), Box::new(state));
    }

    /// Takes the state kept for `entity_id`, if it is of type `S`.
    pub fn take<S: 'static>(&mut self, entity_id: &str) -> Option<S> {
        let state = self.states.remove(entity_id)?;
        state.downcast().ok().map(|s| *s)
    }

    /// Keeps the encoded form of the state last answered for `entity_id`: what a fault restores.
    pub fn put_encoded(&mut self, entity_id: &str, state: proto::Payload) {
        self.encoded.insert(entity_id.to_string(), state);
    }

    /// The encoded form of the state last answered for `entity_id`.
    pub fn encoded(&self, entity_id: &str) -> Option<&proto::Payload> {
        self.encoded.get(entity_id)
    }

    /// Forgets `entity_id`: the runtime passivated it.
    pub fn remove(&mut self, entity_id: &str) {
        self.states.remove(entity_id);
        self.encoded.remove(entity_id);
    }
}

/// A fault answering a request this component cannot take in this version of the library.
pub(crate) fn unsupported(component_id: &str, what: &str) -> proto::Failure {
    proto::Failure {
        command_id: 0,
        error: Some(proto::Error {
            message: format!(
                "component '{component_id}' does not take {what} in this version of the library"
            ),
            code: proto::ErrorCode::Internal as i32,
        }),
    }
}

/// A fault: nothing in the reply applies, and the runtime keeps the state it holds.
pub(crate) fn failure(
    command_id: i64,
    code: crate::effects::ErrorCode,
    message: String,
) -> proto::Failure {
    proto::Failure {
        command_id,
        error: Some(crate::effects::CommandError::new(code, message).to_proto()),
    }
}

/// The state of a key value entity or workflow, held between calls in the stateful shape: what a
/// call starts from, and what it keeps after. The event sourced entity has its own, since it
/// also folds.
pub(crate) struct Stateful<'a, S> {
    pub(crate) shape: Shape,
    pub(crate) codec: &'a Auto<S>,
    pub(crate) entity_id: &'a str,
}

impl<S: Serialize + DeserializeOwned + 'static> Stateful<'_, S> {
    /// The state the runtime sent, else — stateful — the one kept, else the empty one.
    pub(crate) fn current(
        &self,
        sent: Option<&Payload>,
        held: &mut HeldState,
        empty: impl FnOnce() -> S,
    ) -> Result<S, String> {
        match sent {
            Some(payload) => self
                .codec
                .decode_value(&payload.data)
                .map_err(|e| format!("the state does not decode: {e}")),
            None if self.shape == Shape::Stateful => {
                Ok(held.take::<S>(self.entity_id).unwrap_or_else(empty))
            }
            None => Ok(empty()),
        }
    }

    /// The state after a call, encoded; kept too in the stateful shape.
    pub(crate) fn keep(&self, state: S, held: &mut HeldState) -> Result<Payload, String> {
        let encoded = self
            .codec
            .to_payload(&state)
            .map_err(|e| format!("the state does not encode: {e}"))?;
        if self.shape == Shape::Stateful {
            held.put(self.entity_id, state);
            held.put_encoded(self.entity_id, encoded.clone());
        }
        Ok(encoded)
    }

    /// After a fault the call's work is discarded: a stateful instance goes on from what it kept.
    pub(crate) fn restore(&self, held: &mut HeldState) {
        if self.shape != Shape::Stateful || held.get::<S>(self.entity_id).is_some() {
            return;
        }
        if let Some(encoded) = held.encoded(self.entity_id)
            && let Ok(state) = self.codec.decode_value(&encoded.data)
        {
            held.put(self.entity_id, state);
        }
    }
}

/// Handlers' problems shared by every kind: a wire name empty or declared twice.
pub(crate) fn name_problem(kind: &str, owner: &str, name: &str, taken: bool) -> Option<String> {
    if name.is_empty() {
        Some(format!("{kind} '{owner}' has a handler with no name"))
    } else if taken {
        Some(format!("{kind} '{owner}' declares handler '{name}' twice"))
    } else {
        None
    }
}
