//! Workflows: a durable multi-step process. Commands start and inspect it; steps do the work, each
//! deciding what comes next. The runtime journals every transition, runs each step on its own
//! instance of the module, and applies the timeouts and recovery the workflow declares — a step
//! that panics is retried and failed over as [`settings`](Workflow::settings) says.
//!
//! ```ignore
//! impl Workflow for CheckoutWorkflow {
//!     type State = Checkout;
//!     const COMPONENT_ID: &'static str = "checkout";
//!     fn empty_state(id: &str) -> Checkout { Checkout::new(id) }
//!     fn handlers() -> WorkflowHandlers<Self> { WorkflowHandlers::new().command("start", Self::start) }
//!     fn steps() -> Steps<Self> { Steps::new().step("reserve", Self::reserve) }
//! }
//! ```

use std::marker::PhantomData;

use serde::Serialize;
use serde::de::DeserializeOwned;

use super::event_sourced::EncodedReply;
use super::{ComponentOf, HeldState, Registered, Shape, Stateful, failure, kinds, name_problem};
use crate::codec::time::Duration;
use crate::codec::{Auto, EncodingError, decode_payload, encode_payload};
use crate::context::{Context, Metadata};
use crate::effects::common::Outcome;
use crate::effects::workflow::{Next, StepEffect, StepRef, WorkflowEffect};
use crate::effects::{CommandError, ErrorCode, ReadOnlyEffect};
use crate::proto::{self, Kind, Payload};

/// A workflow. Implement it on a unit struct and register the struct's value.
pub trait Workflow: Sized + 'static {
    /// Its state.
    type State: Serialize + DeserializeOwned + 'static;

    /// The component's id.
    const COMPONENT_ID: &'static str;

    /// Whether its instances keep their state between commands; stateless unless said otherwise.
    /// A step always runs on an instance of its own, and is always handed the state.
    const SHAPE: Shape = Shape::Stateless;

    /// The manifest its state is stored under; the state type's simple name unless set.
    const STATE_MANIFEST: Option<&'static str> = None;

    /// The state of instance `entity_id` before it starts.
    fn empty_state(entity_id: &str) -> Self::State;

    /// The command handlers, by wire name.
    fn handlers() -> WorkflowHandlers<Self>;

    /// The steps, by name.
    fn steps() -> Steps<Self>;

    /// Timeouts and recovery, which the runtime enforces; its defaults unless said otherwise.
    fn settings() -> WorkflowSettings {
        WorkflowSettings::default()
    }

    /// The codec the state is stored with.
    fn state_codec() -> Auto<Self::State> {
        Self::STATE_MANIFEST.map_or_else(Auto::new, Auto::named)
    }
}

/// What to do when a step fails: retry it, then fail over to another step (run with no input).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Recovery {
    /// How many times the step is tried again.
    pub max_retries: u32,
    /// The step run once the retries are spent; the workflow fails if there is none.
    pub failover_to: Option<String>,
}

impl Recovery {
    /// Retry a failed step this many times.
    pub fn retries(max_retries: u32) -> Recovery {
        Recovery {
            max_retries,
            failover_to: None,
        }
    }

    /// Then run `step`.
    pub fn failover_to(mut self, step: &str) -> Recovery {
        self.failover_to = Some(step.to_string());
        self
    }

    fn to_proto(&self) -> proto::workflow_detail::Recovery {
        proto::workflow_detail::Recovery {
            max_retries: self.max_retries as i32,
            failover_to: self.failover_to.clone(),
        }
    }
}

/// A workflow's timeouts and recovery, which the runtime applies.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct WorkflowSettings {
    timeout: Option<Duration>,
    default_step_timeout: Option<Duration>,
    default_recovery: Option<Recovery>,
    steps: Vec<(String, Option<Duration>, Option<Recovery>)>,
}

impl WorkflowSettings {
    /// The runtime's defaults: no overall limit, 30 seconds a step, a failed step fails the workflow.
    pub fn new() -> WorkflowSettings {
        WorkflowSettings::default()
    }

    /// A ceiling on the whole workflow.
    pub fn timeout(mut self, timeout: Duration) -> WorkflowSettings {
        self.timeout = Some(timeout);
        self
    }

    /// How long a step may take unless its own setting says otherwise.
    pub fn default_step_timeout(mut self, timeout: Duration) -> WorkflowSettings {
        self.default_step_timeout = Some(timeout);
        self
    }

    /// What happens to a failed step unless its own setting says otherwise.
    pub fn default_recovery(mut self, recovery: Recovery) -> WorkflowSettings {
        self.default_recovery = Some(recovery);
        self
    }

    /// How long step `step` may take.
    pub fn step_timeout(mut self, step: &str, timeout: Duration) -> WorkflowSettings {
        self.step(step).1 = Some(timeout);
        self
    }

    /// What happens when step `step` fails.
    pub fn step_recovery(mut self, step: &str, recovery: Recovery) -> WorkflowSettings {
        self.step(step).2 = Some(recovery);
        self
    }

    fn step(&mut self, step: &str) -> &mut (String, Option<Duration>, Option<Recovery>) {
        if let Some(i) = self.steps.iter().position(|s| s.0 == step) {
            return &mut self.steps[i];
        }
        self.steps.push((step.to_string(), None, None));
        self.steps.last_mut().expect("just pushed")
    }

    fn is_default(&self) -> bool {
        *self == WorkflowSettings::default()
    }

    fn to_proto(&self) -> proto::workflow_detail::Settings {
        proto::workflow_detail::Settings {
            timeout_millis: self.timeout.map(|d| d.to_millis()),
            default_step_timeout_millis: self.default_step_timeout.map(|d| d.to_millis()),
            default_recovery: self.default_recovery.as_ref().map(Recovery::to_proto),
            steps: self
                .steps
                .iter()
                .map(
                    |(step, timeout, recovery)| proto::workflow_detail::StepSettings {
                        step: step.clone(),
                        timeout_millis: timeout.map(|d| d.to_millis()),
                        recovery: recovery.as_ref().map(Recovery::to_proto),
                    },
                )
                .collect(),
        }
    }
}

type Run<S> =
    Box<dyn Fn(&S, &Payload, &Context) -> Result<WorkflowEffect<S, EncodedReply>, String>>;
type StepRun<S> = Box<dyn Fn(&S, Option<&Payload>, &Context) -> Result<StepEffect<S>, String>>;

pub(crate) struct Entry<S> {
    pub(crate) name: String,
    pub(crate) read_only: bool,
    pub(crate) run: Run<S>,
}

/// A workflow's command handlers, by wire name. A query answers a [`ReadOnlyEffect`].
pub struct WorkflowHandlers<C: Workflow> {
    pub(crate) entries: Vec<Entry<C::State>>,
    pub(crate) problems: Vec<String>,
    marker: PhantomData<fn() -> C>,
}

impl<C: Workflow> Default for WorkflowHandlers<C> {
    fn default() -> WorkflowHandlers<C> {
        WorkflowHandlers::new()
    }
}

impl<C: Workflow> WorkflowHandlers<C> {
    /// No handlers yet.
    pub fn new() -> WorkflowHandlers<C> {
        WorkflowHandlers {
            entries: Vec::new(),
            problems: Vec::new(),
            marker: PhantomData,
        }
    }

    /// A command handler under wire name `name`: it may change the state and start a step.
    pub fn command<In, R, F>(self, name: &str, handler: F) -> WorkflowHandlers<C>
    where
        In: DeserializeOwned + 'static,
        R: Serialize + 'static,
        F: Fn(&C::State, In, &Context) -> WorkflowEffect<C::State, R> + 'static,
    {
        self.add(name, false, handler)
    }

    /// A read-only handler under wire name `name`, answered while a step runs.
    pub fn query<In, R, F>(self, name: &str, handler: F) -> WorkflowHandlers<C>
    where
        In: DeserializeOwned + 'static,
        R: Serialize + 'static,
        F: Fn(&C::State, In, &Context) -> ReadOnlyEffect<R> + 'static,
    {
        self.add(name, true, move |s: &C::State, i: In, c: &Context| {
            handler(s, i, c).into()
        })
    }

    fn add<In, R>(
        mut self,
        name: &str,
        read_only: bool,
        handler: impl Fn(&C::State, In, &Context) -> WorkflowEffect<C::State, R> + 'static,
    ) -> WorkflowHandlers<C>
    where
        In: DeserializeOwned + 'static,
        R: Serialize + 'static,
    {
        let taken = self.entries.iter().any(|e| e.name == name);
        if let Some(problem) = name_problem("workflow", C::COMPONENT_ID, name, taken) {
            self.problems.push(problem);
            if taken {
                return self;
            }
        }
        let run: Run<C::State> = Box::new(move |state, payload, ctx| {
            let input: In = decode_payload(payload).map_err(|e| {
                format!(
                    "the input to '{}' is not a {}: {e}",
                    ctx.component_id(),
                    std::any::type_name::<In>()
                )
            })?;
            let effect = handler(state, input, ctx);
            Ok(WorkflowEffect {
                new_state: effect.new_state,
                transition: effect.transition,
                outcome: effect.outcome.map(|reply| encode_payload(&reply)),
            })
        });
        self.entries.push(Entry {
            name: name.to_string(),
            read_only,
            run,
        });
        self
    }
}

pub(crate) struct StepEntry<S> {
    pub(crate) name: String,
    pub(crate) run: StepRun<S>,
}

/// A workflow's steps, by name. A step is handed the state and the input the transition to it
/// carried; a step that takes none takes `()`.
pub struct Steps<C: Workflow> {
    pub(crate) entries: Vec<StepEntry<C::State>>,
    pub(crate) problems: Vec<String>,
    marker: PhantomData<fn() -> C>,
}

impl<C: Workflow> Default for Steps<C> {
    fn default() -> Steps<C> {
        Steps::new()
    }
}

impl<C: Workflow> Steps<C> {
    /// No steps yet.
    pub fn new() -> Steps<C> {
        Steps {
            entries: Vec::new(),
            problems: Vec::new(),
            marker: PhantomData,
        }
    }

    /// Step `name`. It may call other components through `ctx.client()`, which waits for them.
    pub fn step<In, F>(mut self, name: &str, step: F) -> Steps<C>
    where
        In: DeserializeOwned + 'static,
        F: Fn(&C::State, In, &Context) -> StepEffect<C::State> + 'static,
    {
        if name.is_empty() || self.entries.iter().any(|e| e.name == name) {
            self.problems.push(format!(
                "workflow '{}' declares step '{name}' {}",
                C::COMPONENT_ID,
                if name.is_empty() {
                    "with no name"
                } else {
                    "twice"
                }
            ));
            return self;
        }
        let owned = name.to_string();
        let run: StepRun<C::State> = Box::new(move |state, input, ctx| {
            let unit = Payload::default();
            let input: In = decode_payload(input.unwrap_or(&unit)).map_err(|e| {
                format!(
                    "step '{owned}' was handed no {}: {e}",
                    std::any::type_name::<In>()
                )
            })?;
            Ok(step(state, input, ctx))
        });
        self.entries.push(StepEntry {
            name: name.to_string(),
            run,
        });
        self
    }
}

pub(crate) struct Registration<C: Workflow> {
    shape: Shape,
    handlers: WorkflowHandlers<C>,
    steps: Steps<C>,
    settings: WorkflowSettings,
    codec: Auto<C::State>,
}

impl<C: Workflow> ComponentOf<kinds::Workflow> for C {
    fn kind() -> Kind {
        Kind::Workflow
    }

    fn component_id() -> &'static str {
        C::COMPONENT_ID
    }

    fn registration() -> Box<dyn Registered> {
        Box::new(Registration::<C> {
            shape: C::SHAPE,
            handlers: C::handlers(),
            steps: C::steps(),
            settings: C::settings(),
            codec: C::state_codec(),
        })
    }
}

fn step_ref(step: &StepRef) -> Result<proto::StepRef, EncodingError> {
    Ok(proto::StepRef {
        step: step.step.clone(),
        input: step.input.clone().transpose()?,
    })
}

impl<C: Workflow> Registered for Registration<C> {
    fn id(&self) -> &str {
        C::COMPONENT_ID
    }

    fn kind(&self) -> Kind {
        Kind::Workflow
    }

    fn shape(&self) -> Shape {
        self.shape
    }

    fn set_shape(&mut self, shape: Shape) {
        self.shape = shape;
    }

    fn to_component(&self) -> proto::Component {
        let mut handlers: Vec<proto::Handler> = self
            .handlers
            .entries
            .iter()
            .map(|e| proto::Handler {
                name: e.name.clone(),
                read_only: e.read_only,
                streaming: false,
            })
            .collect();
        handlers.sort_by(|a, b| a.name.cmp(&b.name));
        proto::Component {
            kind: Kind::Workflow as i32,
            id: C::COMPONENT_ID.to_string(),
            handlers,
            detail: Some(proto::component::Detail::Workflow(proto::WorkflowDetail {
                steps: self.steps.entries.iter().map(|s| s.name.clone()).collect(),
                settings: (!self.settings.is_default()).then(|| self.settings.to_proto()),
            })),
        }
    }

    fn problems(&self) -> Vec<String> {
        let mut problems = self.handlers.problems.clone();
        problems.extend(self.steps.problems.clone());
        if C::COMPONENT_ID.is_empty() {
            problems.push("a workflow has an empty component id".to_string());
        }
        let declared = |step: &str| self.steps.entries.iter().any(|s| s.name == step);
        for (step, _, recovery) in &self.settings.steps {
            if !declared(step) {
                problems.push(format!(
                    "workflow '{}': settings name step '{step}', which is not declared",
                    C::COMPONENT_ID
                ));
            }
            if let Some(target) = recovery.as_ref().and_then(|r| r.failover_to.as_ref())
                && !declared(target)
            {
                problems.push(format!(
                    "workflow '{}': step '{step}' fails over to '{target}', which is not declared",
                    C::COMPONENT_ID
                ));
            }
        }
        problems
    }

    fn handle(&self, request: proto::HandleRequest, held: &mut HeldState) -> proto::HandleReply {
        let entity_id = request.entity_id.clone();
        let stateful = Stateful {
            shape: self.shape,
            codec: &self.codec,
            entity_id: &entity_id,
        };
        let reply = self.handle_command(&stateful, request, held);
        if reply.failure.is_some() {
            stateful.restore(held);
        }
        reply
    }

    fn run_step(&self, request: proto::StepRequest) -> proto::StepReply {
        let sent = request.state.clone();
        let fault = |id: i64, message: String| proto::StepReply {
            reply: None,
            state: sent.clone(),
            failure: Some(failure(id, ErrorCode::Internal, message)),
        };
        let Some(run_step) = request.run_step else {
            return fault(0, "a step request names no step".to_string());
        };
        let Some(entry) = self.steps.entries.iter().find(|s| s.name == run_step.step) else {
            return fault(
                run_step.id,
                format!(
                    "workflow '{}' has no step '{}'",
                    C::COMPONENT_ID,
                    run_step.step
                ),
            );
        };
        let state = match &sent {
            Some(payload) => match self.codec.decode_value(&payload.data) {
                Ok(state) => state,
                Err(e) => return fault(run_step.id, format!("the state does not decode: {e}")),
            },
            None => C::empty_state(&request.entity_id),
        };
        let ctx = Context::new(
            C::COMPONENT_ID,
            request.entity_id.clone(),
            0,
            Metadata::default(),
        );
        let effect = match (entry.run)(&state, run_step.input.as_ref(), &ctx) {
            Ok(effect) => effect,
            Err(message) => return fault(run_step.id, message),
        };
        let next = match &effect.next {
            Next::TransitionTo(to) => step_ref(to).map(proto::step_outcome::Outcome::TransitionTo),
            Next::Pause { after, on_timeout } => {
                on_timeout
                    .as_ref()
                    .map(step_ref)
                    .transpose()
                    .map(|on_timeout| {
                        proto::step_outcome::Outcome::Pause(proto::step_outcome::Pause {
                            after_millis: after.map(|d| d.to_millis()),
                            on_timeout,
                        })
                    })
            }
            Next::End => Ok(proto::step_outcome::Outcome::End(
                proto::step_outcome::End {},
            )),
            Next::Fail(error) => Ok(proto::step_outcome::Outcome::Fail(error.to_proto())),
        };
        let next = match next {
            Ok(next) => next,
            Err(e) => return fault(run_step.id, format!("a step's input does not encode: {e}")),
        };
        let written = effect.new_state.is_some();
        let state = effect.new_state.unwrap_or(state);
        let encoded = match self.codec.to_payload(&state) {
            Ok(encoded) => encoded,
            Err(e) => return fault(run_step.id, format!("the state does not encode: {e}")),
        };
        proto::StepReply {
            reply: Some(proto::workflow_out::StepReply {
                command_id: run_step.id,
                new_state: written.then(|| encoded.clone()),
                next: Some(proto::StepOutcome {
                    outcome: Some(next),
                }),
            }),
            state: Some(encoded),
            failure: None,
        }
    }
}

impl<C: Workflow> Registration<C> {
    fn handle_command(
        &self,
        stateful: &Stateful<'_, C::State>,
        request: proto::HandleRequest,
        held: &mut HeldState,
    ) -> proto::HandleReply {
        let sent = request.state.clone();
        let fault = |id: i64, message: String| proto::HandleReply {
            reply: None,
            state: sent.clone(),
            failure: Some(failure(id, ErrorCode::Internal, message)),
        };
        let Some(proto::handle_request::Command::Workflow(command)) = request.command else {
            return fault(
                0,
                format!(
                    "'{}' is a workflow and takes only its commands",
                    C::COMPONENT_ID
                ),
            );
        };
        let state =
            match stateful.current(sent.as_ref(), held, || C::empty_state(stateful.entity_id)) {
                Ok(state) => state,
                Err(message) => return fault(command.id, message),
            };
        let metadata = Metadata::from_proto(command.metadata.as_ref());
        let ctx = Context::new(C::COMPONENT_ID, stateful.entity_id, 0, metadata);
        let effect = match self
            .handlers
            .entries
            .iter()
            .find(|e| e.name == command.name)
        {
            None => WorkflowEffect {
                new_state: None,
                transition: None,
                outcome: Outcome::Error(CommandError::new(
                    ErrorCode::NotFound,
                    format!("no handler '{}' on '{}'", command.name, C::COMPONENT_ID),
                )),
            },
            Some(entry) => match (entry.run)(&state, &command.payload.unwrap_or_default(), &ctx) {
                Ok(effect) => effect,
                Err(message) => return fault(command.id, message),
            },
        };
        let refused = matches!(effect.outcome, Outcome::Error(_));
        let (written, transition) = if refused {
            (None, None)
        } else {
            (effect.new_state, effect.transition)
        };
        let is_written = written.is_some();
        let state = written.unwrap_or(state);
        let outcome = match effect.outcome {
            Outcome::Reply(reply, metadata) => match reply(&state) {
                Ok(payload) => proto::outcome::Outcome::Reply(proto::outcome::Reply {
                    payload: Some(payload),
                    metadata: Some(metadata.to_proto()),
                }),
                Err(e) => return fault(command.id, format!("computing the reply failed: {e}")),
            },
            Outcome::NoReply => proto::outcome::Outcome::NoReply(proto::outcome::NoReply {}),
            Outcome::Error(refusal) => proto::outcome::Outcome::Error(refusal.to_proto()),
        };
        let transition = match transition.as_ref().map(step_ref).transpose() {
            Ok(transition) => transition,
            Err(e) => return fault(command.id, format!("a step's input does not encode: {e}")),
        };
        let encoded = match stateful.keep(state, held) {
            Ok(encoded) => encoded,
            Err(message) => return fault(command.id, message),
        };
        proto::HandleReply {
            reply: Some(proto::handle_reply::Reply::Workflow(
                proto::workflow_out::Reply {
                    command_id: command.id,
                    new_state: is_written.then(|| encoded.clone()),
                    transition,
                    outcome: Some(proto::Outcome {
                        outcome: Some(outcome),
                    }),
                },
            )),
            state: Some(encoded),
            failure: None,
        }
    }
}
