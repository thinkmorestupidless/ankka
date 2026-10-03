//! Unit testkits for the kinds beside the event sourced entity: key value entities, workflows,
//! views, consumers, timed actions and agents. Like the event sourced kit, each drives its
//! component through the dispatch its module's exports use, so every value crosses its codec.
//!
//! A kit built `with_service(build())` answers its component's calls to the service's event
//! sourced entities in memory — a workflow step that reads a cart, a tool that counts something —
//! and without one those calls are refused as unavailable.

use std::collections::HashMap;
use std::rc::Rc;

use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::Value;

use super::unit::{InMemory, hosted, in_memory};
use crate::codec::{decode_payload, encode_payload};
use crate::components::agent::Stage;
use crate::components::autonomous::Registration as AutonomousRegistration;
use crate::components::{
    Agent, AutonomousAgent, ComponentOf, Consumer, HeldState, KeyValueEntity, Registered,
    ResultCheck, TaskType, TimedAction, Verdict, View, Workflow, kinds,
};
use crate::context::Metadata;
use crate::effects::consumer::{ConsumerEffect, Outgoing};
use crate::effects::view::ViewEffect;
use crate::effects::{CommandError, ErrorCode, Retention};
use crate::graph::{self, Element, GraphConsumer};
use crate::proto::{self, Kind, Payload};
use crate::service::{PROTOCOL_VERSION, Service};

fn encoded<T: Serialize + 'static>(value: &T, what: &str) -> Payload {
    encode_payload(value).unwrap_or_else(|e| panic!("{what} does not encode: {e}"))
}

fn fault(failure: Option<proto::Failure>, what: &str) -> ! {
    let error = failure.and_then(|f| f.error).unwrap_or_default();
    panic!("{what} faulted: {}", error.message)
}

/// A reply or a refusal, as a caller would see it.
#[derive(Debug, Clone)]
pub struct Answered {
    reply: Option<Payload>,
    error: Option<CommandError>,
}

impl Answered {
    fn of(outcome: Option<proto::Outcome>) -> Answered {
        match outcome.and_then(|o| o.outcome) {
            Some(proto::outcome::Outcome::Reply(r)) => Answered {
                reply: r.payload,
                error: None,
            },
            Some(proto::outcome::Outcome::Error(e)) => Answered {
                reply: None,
                error: Some(CommandError::from_proto(&e)),
            },
            _ => Answered {
                reply: None,
                error: None,
            },
        }
    }

    /// The reply, decoded as `R`; the refusal if the command was refused.
    pub fn reply<R: DeserializeOwned + 'static>(&self) -> Result<R, CommandError> {
        if let Some(error) = &self.error {
            return Err(error.clone());
        }
        let payload = self.reply.as_ref().ok_or_else(|| {
            CommandError::new(ErrorCode::Internal, "the command answered no reply")
        })?;
        decode_payload(payload).map_err(|e| CommandError::new(ErrorCode::Internal, e.0))
    }

    /// The refusal, if the command was refused.
    pub fn error(&self) -> Option<&CommandError> {
        self.error.as_ref()
    }
}

// ── Key value entities ───────────────────────────────────────────────────────

/// What one key value command did.
#[derive(Debug)]
pub struct KeyValueOutcome<S> {
    /// The state after the command.
    pub state: S,
    /// Whether the command wrote a state.
    pub written: bool,
    /// What happens to the entity afterwards, if the handler said.
    pub retention: Option<Retention>,
    /// The reply or the refusal.
    pub answer: Answered,
}

/// Drives one instance of a key value entity, carrying its state from one command to the next.
pub struct KeyValueEntityTestKit<C: KeyValueEntity> {
    entity_id: String,
    registration: Box<dyn Registered>,
    held: HeldState,
    stored: Option<Payload>,
    commands: i64,
    marker: std::marker::PhantomData<C>,
}

impl<C: KeyValueEntity> KeyValueEntityTestKit<C> {
    /// Instance `entity_id`, fresh.
    pub fn new(entity_id: &str) -> KeyValueEntityTestKit<C> {
        KeyValueEntityTestKit {
            entity_id: entity_id.to_string(),
            registration: <C as ComponentOf<kinds::KeyValue>>::registration(),
            held: HeldState::default(),
            stored: None,
            commands: 0,
            marker: std::marker::PhantomData,
        }
    }

    /// Runs handler `name` with `input`. A fault panics, as the runtime would fail the command.
    pub fn command<In: Serialize + 'static>(
        &mut self,
        name: &str,
        input: In,
    ) -> KeyValueOutcome<C::State> {
        self.commands += 1;
        let request = proto::HandleRequest {
            kind: Kind::KeyValueEntity as i32,
            component_id: C::COMPONENT_ID.to_string(),
            entity_id: self.entity_id.clone(),
            state: self.stored.clone(),
            command: Some(proto::handle_request::Command::KeyValue(
                proto::key_value_in::Command {
                    id: self.commands,
                    name: name.to_string(),
                    payload: Some(encoded(&input, "the input")),
                    metadata: None,
                },
            )),
        };
        let reply = self.registration.handle(request, &mut self.held);
        if reply.failure.is_some() {
            fault(reply.failure, name);
        }
        let Some(proto::handle_reply::Reply::KeyValue(out)) = reply.reply else {
            panic!("'{name}' answered no key value reply")
        };
        self.stored = reply.state;
        let retention = out.retention.and_then(|r| match r.retention {
            Some(proto::retention::Retention::DeleteNow(_)) => Some(Retention::DeleteNow),
            Some(proto::retention::Retention::ExpireAfter(e)) => {
                Some(Retention::ExpireAfter(crate::Duration::of_millis(e.millis)))
            }
            None => None,
        });
        KeyValueOutcome {
            state: self.state(),
            written: out.new_state.is_some(),
            retention,
            answer: Answered::of(out.outcome),
        }
    }

    /// The current state.
    pub fn state(&self) -> C::State {
        match &self.stored {
            Some(p) => C::state_codec()
                .decode_value(&p.data)
                .expect("the stored state decodes"),
            None => C::empty_state(&self.entity_id),
        }
    }
}

// ── Workflows ────────────────────────────────────────────────────────────────

/// What comes after a step, as the kit saw it.
#[derive(Debug, Clone, PartialEq)]
pub enum StepNext {
    /// Step `0` runs next.
    TransitionTo(String),
    /// Paused: until a command, or `after_millis`, and then `on_timeout`.
    Pause {
        /// How long it waits.
        after_millis: Option<i64>,
        /// What runs when the wait ends.
        on_timeout: Option<String>,
    },
    /// Finished.
    End,
    /// Failed, as a step said.
    Fail(CommandError),
}

/// Drives one workflow instance: its commands, and its steps one at a time or to the end.
pub struct WorkflowTestKit<C: Workflow> {
    entity_id: String,
    registration: Box<dyn Registered>,
    held: HeldState,
    stored: Option<Payload>,
    pending: Option<proto::StepRef>,
    paused: Option<proto::StepRef>,
    commands: i64,
    runtime: Option<Rc<InMemory>>,
    marker: std::marker::PhantomData<C>,
}

impl<C: Workflow> WorkflowTestKit<C> {
    /// Instance `entity_id`, not started.
    pub fn new(entity_id: &str) -> WorkflowTestKit<C> {
        WorkflowTestKit {
            entity_id: entity_id.to_string(),
            registration: <C as ComponentOf<kinds::Workflow>>::registration(),
            held: HeldState::default(),
            stored: None,
            pending: None,
            paused: None,
            commands: 0,
            runtime: None,
            marker: std::marker::PhantomData,
        }
    }

    /// Steps' calls to the service's event sourced entities are answered in memory.
    pub fn with_service(mut self, service: Service) -> WorkflowTestKit<C> {
        self.runtime = Some(in_memory(service));
        self
    }

    /// Runs command `name`; a transition it starts is run by [`run_step`](Self::run_step).
    pub fn command<In: Serialize + 'static>(&mut self, name: &str, input: In) -> Answered {
        self.commands += 1;
        let request = proto::HandleRequest {
            kind: Kind::Workflow as i32,
            component_id: C::COMPONENT_ID.to_string(),
            entity_id: self.entity_id.clone(),
            state: self.stored.clone(),
            command: Some(proto::handle_request::Command::Workflow(
                proto::workflow_in::Command {
                    id: self.commands,
                    name: name.to_string(),
                    payload: Some(encoded(&input, "the input")),
                    metadata: None,
                },
            )),
        };
        let reply = self.registration.handle(request, &mut self.held);
        if reply.failure.is_some() {
            fault(reply.failure, name);
        }
        let Some(proto::handle_reply::Reply::Workflow(out)) = reply.reply else {
            panic!("'{name}' answered no workflow reply")
        };
        self.stored = reply.state;
        if out.transition.is_some() {
            self.pending = out.transition;
            self.paused = None;
        }
        Answered::of(out.outcome)
    }

    /// Runs the step the workflow is on, answering what comes next. A step that panics panics
    /// here: the kit applies no recovery.
    pub fn run_step(&mut self) -> StepNext {
        let step = self
            .pending
            .take()
            .expect("no step is pending: start the workflow first");
        self.commands += 1;
        let request = proto::StepRequest {
            component_id: C::COMPONENT_ID.to_string(),
            entity_id: self.entity_id.clone(),
            state: self.stored.clone(),
            run_step: Some(proto::workflow_in::RunStep {
                id: self.commands,
                step: step.step.clone(),
                input: step.input,
            }),
        };
        let registration = &self.registration;
        let reply = hosted(&self.runtime, || registration.run_step(request));
        if reply.failure.is_some() {
            fault(reply.failure, &step.step);
        }
        self.stored = reply.state;
        let next = reply.reply.and_then(|r| r.next).and_then(|n| n.outcome);
        use proto::step_outcome::Outcome;
        match next {
            Some(Outcome::TransitionTo(to)) => {
                let name = to.step.clone();
                self.pending = Some(to);
                StepNext::TransitionTo(name)
            }
            Some(Outcome::Pause(p)) => {
                self.paused = p.on_timeout.clone();
                StepNext::Pause {
                    after_millis: p.after_millis,
                    on_timeout: p.on_timeout.map(|s| s.step),
                }
            }
            Some(Outcome::Fail(e)) => StepNext::Fail(CommandError::from_proto(&e)),
            _ => StepNext::End,
        }
    }

    /// Runs steps until the workflow ends, fails or pauses, answering which.
    pub fn run_until_pause(&mut self) -> StepNext {
        for _ in 0..100 {
            match self.run_step() {
                StepNext::TransitionTo(_) => continue,
                other => return other,
            }
        }
        panic!("the workflow ran 100 steps without pausing or ending")
    }

    /// Runs steps until the workflow ends or fails, going on past a pause as its timeout would.
    pub fn run_to_end(&mut self) -> StepNext {
        if self.pending.is_none() {
            self.resume();
        }
        for _ in 0..100 {
            match self.run_until_pause() {
                StepNext::Pause { .. } if self.paused.is_some() => self.resume(),
                other => return other,
            }
        }
        panic!("the workflow paused 100 times without ending")
    }

    /// Ends a pause as its timeout would: the step it names is next.
    pub fn resume(&mut self) {
        self.pending = self.paused.take();
    }

    /// The current state.
    pub fn state(&self) -> C::State {
        match &self.stored {
            Some(p) => C::state_codec()
                .decode_value(&p.data)
                .expect("the stored state decodes"),
            None => C::empty_state(&self.entity_id),
        }
    }
}

// ── Views ────────────────────────────────────────────────────────────────────

/// Feeds a view changes, keeping its rows by source entity.
pub struct ViewTestKit<C: View> {
    registration: Box<dyn Registered>,
    rows: HashMap<String, Payload>,
    sequence: i64,
    marker: std::marker::PhantomData<C>,
}

impl<C: View> Default for ViewTestKit<C> {
    fn default() -> ViewTestKit<C> {
        ViewTestKit::new()
    }
}

impl<C: View> ViewTestKit<C> {
    /// No rows.
    pub fn new() -> ViewTestKit<C> {
        ViewTestKit {
            registration: <C as ComponentOf<kinds::View>>::registration(),
            rows: HashMap::new(),
            sequence: 0,
            marker: std::marker::PhantomData,
        }
    }

    fn feed(&mut self, key: &str, event: Option<Payload>) -> ViewEffect<C::Row> {
        self.sequence += 1;
        let metadata = Metadata::new()
            .set("ce-subject", key)
            .set("ankka.sequence", self.sequence.to_string());
        let request = proto::ViewRequest {
            component_id: C::COMPONENT_ID.to_string(),
            deleted: event.is_none(),
            event,
            metadata: Some(metadata.to_proto()),
            row: self.rows.get(key).cloned(),
        };
        let effect = self.registration.view(request).expect("a view answers");
        use proto::view_effect::Effect;
        match effect.effect {
            Some(Effect::UpdateRow(row)) => {
                let decoded = C::row_codec()
                    .decode_value(&row.data)
                    .expect("the row decodes");
                self.rows.insert(key.to_string(), row);
                ViewEffect::UpdateRow(decoded)
            }
            Some(Effect::DeleteRow(_)) => {
                self.rows.remove(key);
                ViewEffect::DeleteRow
            }
            _ => ViewEffect::Ignore,
        }
    }

    /// One change to source entity `key`.
    pub fn on_event<E: Serialize + 'static>(&mut self, key: &str, event: E) -> ViewEffect<C::Row> {
        let event = encoded(&event, "the change");
        self.feed(key, Some(event))
    }

    /// Source entity `key` was deleted.
    pub fn on_deleted(&mut self, key: &str) -> ViewEffect<C::Row> {
        self.feed(key, None)
    }

    /// The row for source entity `key`.
    pub fn row(&self, key: &str) -> Option<C::Row> {
        self.rows.get(key).map(|p| {
            C::row_codec()
                .decode_value(&p.data)
                .expect("the row decodes")
        })
    }
}

// ── Consumers ────────────────────────────────────────────────────────────────

/// One message a consumer asked to have published, as a test reads it.
#[derive(Debug, Clone, PartialEq)]
pub struct Published {
    /// The message, encoded as it would be published.
    pub payload: Payload,
    /// The record key it named; `None`: keyed by its subject.
    pub key: Option<String>,
    /// Its headers.
    pub metadata: Metadata,
}

impl Published {
    /// The message read back as `T`.
    pub fn read<T: DeserializeOwned + 'static>(&self) -> T {
        decode_payload(&self.payload).unwrap_or_else(|e| panic!("the message does not decode: {e}"))
    }
}

/// What a change's request says beside its message: its subject, its sequence number, and the
/// protocol version the runtime speaks.
fn change_metadata(subject: &str, sequence: Option<i64>, protocol: Option<&str>) -> Metadata {
    let mut metadata = Metadata::new().set("ce-subject", subject);
    if let Some(sequence) = sequence {
        metadata = metadata.set("ankka.sequence", sequence.to_string());
    }
    if let Some(protocol) = protocol {
        metadata = metadata.set("ankka.protocol", protocol);
    }
    metadata
}

/// A consumer's answer on the wire, as the effect it was.
fn consumer_effect(effect: proto::ConsumerEffect) -> ConsumerEffect {
    use proto::consumer_effect::Effect;
    match effect.effect {
        Some(Effect::Done(_)) => ConsumerEffect::Done,
        Some(Effect::Produce(p)) => ConsumerEffect::Produce(
            Ok(p.payload.unwrap_or_default()),
            Metadata::from_proto(p.metadata.as_ref()),
        ),
        Some(Effect::ProduceAll(all)) => ConsumerEffect::ProduceAll(
            all.messages
                .into_iter()
                .map(|m| {
                    let message = Outgoing::of(Ok(m.payload.unwrap_or_default()))
                        .metadata(Metadata::from_proto(m.metadata.as_ref()));
                    match m.key {
                        Some(key) => message.key(key),
                        None => message,
                    }
                })
                .collect(),
        ),
        _ => ConsumerEffect::Ignore,
    }
}

/// Hands a consumer messages.
pub struct ConsumerTestKit<C: Consumer> {
    registration: Box<dyn Registered>,
    runtime: Option<Rc<InMemory>>,
    sequence: Option<i64>,
    protocol: Option<String>,
    marker: std::marker::PhantomData<C>,
}

impl<C: Consumer> Default for ConsumerTestKit<C> {
    fn default() -> ConsumerTestKit<C> {
        ConsumerTestKit::new()
    }
}

impl<C: Consumer> ConsumerTestKit<C> {
    /// A consumer whose client calls are refused.
    pub fn new() -> ConsumerTestKit<C> {
        ConsumerTestKit {
            registration: <C as ComponentOf<kinds::Consumer>>::registration(),
            runtime: None,
            sequence: None,
            protocol: Some(PROTOCOL_VERSION.to_string()),
            marker: std::marker::PhantomData,
        }
    }

    /// Its calls to the service's event sourced entities are answered in memory.
    pub fn with_service(mut self, service: Service) -> ConsumerTestKit<C> {
        self.runtime = Some(in_memory(service));
        self
    }

    /// The changes it is handed are at this sequence number (`ankka.sequence`): an event's, or a
    /// key value state's revision. Without it they carry none, as a topic's messages do.
    pub fn at(mut self, sequence: i64) -> ConsumerTestKit<C> {
        self.sequence = Some(sequence);
        self
    }

    /// The runtime it is answering speaks this protocol version (`ankka.protocol`) — this
    /// library's own unless said otherwise — or, with `None`, is one from before runtimes said.
    pub fn speaking(mut self, protocol: Option<&str>) -> ConsumerTestKit<C> {
        self.protocol = protocol.map(str::to_string);
        self
    }

    fn feed(&self, subject: &str, message: Option<Payload>) -> ConsumerEffect {
        let metadata = change_metadata(subject, self.sequence, self.protocol.as_deref());
        let request = proto::ConsumerRequest {
            component_id: C::COMPONENT_ID.to_string(),
            deleted: message.is_none(),
            message,
            metadata: Some(metadata.to_proto()),
        };
        let registration = &self.registration;
        let effect =
            hosted(&self.runtime, || registration.consumer(request)).expect("a consumer answers");
        consumer_effect(effect)
    }

    /// What `effect` asks to have published, in order: one message for a single `produce`, as
    /// many as it holds for `produce_all`, none otherwise.
    pub fn messages(effect: &ConsumerEffect) -> Vec<Published> {
        published(effect)
    }

    /// One message about source entity `subject`.
    pub fn on_message<M: Serialize + 'static>(&self, subject: &str, message: M) -> ConsumerEffect {
        self.feed(subject, Some(encoded(&message, "the message")))
    }

    /// Source entity `subject` was deleted.
    pub fn on_deleted(&self, subject: &str) -> ConsumerEffect {
        self.feed(subject, None)
    }
}

/// What an effect asks to have published, in order.
fn published(effect: &ConsumerEffect) -> Vec<Published> {
    let of = |payload: Result<Payload, crate::codec::EncodingError>, key, metadata| Published {
        payload: payload.unwrap_or_else(|e| panic!("the message does not encode: {e}")),
        key,
        metadata,
    };
    match effect.clone() {
        ConsumerEffect::Produce(payload, metadata) => vec![of(payload, None, metadata)],
        ConsumerEffect::ProduceAll(messages) => messages
            .into_iter()
            .map(|m| {
                let (payload, key, metadata) = m.into_parts();
                of(payload, key, metadata)
            })
            .collect(),
        ConsumerEffect::Done | ConsumerEffect::Ignore => Vec::new(),
    }
}

// ── Graph consumers ──────────────────────────────────────────────────────────

/// Hands a graph consumer changes and gives back the elements it published: each read back from
/// the bytes that would be on the topic, under the key it would have there, so a test asserts on
/// what a reader of the graph would see. An answer the library refuses — an element the reader
/// would not accept, a change with no sequence number and no stated version — panics, as it does
/// in a module.
pub struct GraphConsumerTestKit<G: GraphConsumer> {
    registration: Box<dyn Registered>,
    runtime: Option<Rc<InMemory>>,
    marker: std::marker::PhantomData<G>,
}

impl<G: GraphConsumer> Default for GraphConsumerTestKit<G> {
    fn default() -> GraphConsumerTestKit<G> {
        GraphConsumerTestKit::new()
    }
}

impl<G: GraphConsumer> GraphConsumerTestKit<G> {
    /// A graph consumer whose client calls are refused.
    pub fn new() -> GraphConsumerTestKit<G> {
        GraphConsumerTestKit {
            registration: <G as ComponentOf<kinds::GraphConsumer>>::registration(),
            runtime: None,
            marker: std::marker::PhantomData,
        }
    }

    /// Its calls to the service's event sourced entities are answered in memory.
    pub fn with_service(mut self, service: Service) -> GraphConsumerTestKit<G> {
        self.runtime = Some(in_memory(service));
        self
    }

    fn feed(&self, subject: &str, sequence: i64, message: Option<Payload>) -> Vec<Published> {
        let metadata = change_metadata(subject, Some(sequence), Some(PROTOCOL_VERSION));
        let request = proto::ConsumerRequest {
            component_id: G::COMPONENT_ID.to_string(),
            deleted: message.is_none(),
            message,
            metadata: Some(metadata.to_proto()),
        };
        let registration = &self.registration;
        let effect = hosted(&self.runtime, || registration.consumer(request))
            .expect("a graph consumer answers");
        published(&consumer_effect(effect))
    }

    fn elements(records: Vec<Published>) -> Vec<Element> {
        records
            .iter()
            .map(|record| {
                graph::read(&record.payload.data, record.key.as_deref())
                    .unwrap_or_else(|e| panic!("a published delta does not read back: {e}"))
            })
            .collect()
    }

    /// The elements published for one change to source entity `subject`, at `sequence`: an
    /// event's sequence number, or a key value state's revision. `0` is a change with none, as a
    /// topic's messages have.
    pub fn on_message<M: Serialize + 'static>(
        &self,
        subject: &str,
        sequence: i64,
        message: M,
    ) -> Vec<Element> {
        Self::elements(self.records(subject, sequence, message))
    }

    /// The elements published when source entity `subject` is deleted, at `sequence`.
    pub fn on_deleted(&self, subject: &str, sequence: i64) -> Vec<Element> {
        Self::elements(self.feed(subject, sequence, None))
    }

    /// The same change as [`on_message`](Self::on_message), as the records that would be on the
    /// topic: the delta's bytes, its record key and its headers.
    pub fn records<M: Serialize + 'static>(
        &self,
        subject: &str,
        sequence: i64,
        message: M,
    ) -> Vec<Published> {
        self.feed(subject, sequence, Some(encoded(&message, "the message")))
    }
}

// ── Timed actions ────────────────────────────────────────────────────────────

/// Fires a timed action's actions.
pub struct TimedActionTestKit<C: TimedAction> {
    registration: Box<dyn Registered>,
    runtime: Option<Rc<InMemory>>,
    marker: std::marker::PhantomData<C>,
}

impl<C: TimedAction> Default for TimedActionTestKit<C> {
    fn default() -> TimedActionTestKit<C> {
        TimedActionTestKit::new()
    }
}

impl<C: TimedAction> TimedActionTestKit<C> {
    /// A timed action whose client calls are refused.
    pub fn new() -> TimedActionTestKit<C> {
        TimedActionTestKit {
            registration: <C as ComponentOf<kinds::TimedAction>>::registration(),
            runtime: None,
            marker: std::marker::PhantomData,
        }
    }

    /// Its calls to the service's event sourced entities are answered in memory.
    pub fn with_service(mut self, service: Service) -> TimedActionTestKit<C> {
        self.runtime = Some(in_memory(service));
        self
    }

    /// Fires action `name` with `input`, as its timer would.
    pub fn fire<In: Serialize + 'static>(&self, name: &str, input: In) -> Result<(), CommandError> {
        let request = proto::TimedActionRequest {
            component_id: C::COMPONENT_ID.to_string(),
            name: name.to_string(),
            payload: Some(encoded(&input, "the input")),
            metadata: None,
        };
        let registration = &self.registration;
        let effect = hosted(&self.runtime, || registration.timed_action(request))
            .expect("a timed action answers");
        match effect.effect {
            Some(proto::timed_action_effect::Effect::Fail(e)) => Err(CommandError::from_proto(&e)),
            _ => Ok(()),
        }
    }
}

// ── Agents ───────────────────────────────────────────────────────────────────

/// A model that answers from a script, in order, and fails loudly when it runs out: a test whose
/// model quietly returned a default is no longer testing what it says.
#[derive(Debug, Default)]
pub struct ScriptedModel {
    script: std::collections::VecDeque<Scripted>,
}

#[derive(Debug)]
enum Scripted {
    Text(String),
    Tool(String, Value),
    Refusal(String),
}

impl ScriptedModel {
    /// An empty script.
    pub fn new() -> ScriptedModel {
        ScriptedModel::default()
    }

    /// Next, the model answers `text`.
    pub fn expect_text(mut self, text: impl Into<String>) -> ScriptedModel {
        self.script.push_back(Scripted::Text(text.into()));
        self
    }

    /// Next, the model calls tool `name` with `arguments`.
    pub fn expect_tool_call(mut self, name: impl Into<String>, arguments: Value) -> ScriptedModel {
        self.script
            .push_back(Scripted::Tool(name.into(), arguments));
        self
    }

    /// Next, the model refuses.
    pub fn expect_refusal(mut self, reason: impl Into<String>) -> ScriptedModel {
        self.script.push_back(Scripted::Refusal(reason.into()));
        self
    }

    fn next(&mut self) -> Scripted {
        self.script
            .pop_front()
            .expect("the scripted model ran out of script; add expect_text or expect_tool_call")
    }
}

/// One request as the runtime's loop would run it against the scripted model.
#[derive(Debug)]
pub struct AgentReply {
    /// The plan the handler produced.
    pub plan: proto::AgentPlan,
    /// The tools the model called, with their arguments, in order.
    pub tool_calls: Vec<(String, Value)>,
    /// What each tool call answered; an error is prefixed `error: `.
    pub tool_results: Vec<String>,
    /// The model's reply, if it got that far.
    pub reply: Option<String>,
    /// The refusal, if there was one.
    pub error: Option<CommandError>,
}

/// Runs an agent's handler and then the loop the runtime would run: tools invoked with the
/// scripted arguments, guardrails checked on the way in and out.
pub struct AgentTestKit<C: Agent> {
    session_id: String,
    registration: Box<dyn Registered>,
    model: ScriptedModel,
    runtime: Option<Rc<InMemory>>,
    marker: std::marker::PhantomData<C>,
}

impl<C: Agent> AgentTestKit<C> {
    /// Session `session_id`, answered by `model`.
    pub fn new(session_id: &str, model: ScriptedModel) -> AgentTestKit<C> {
        AgentTestKit {
            session_id: session_id.to_string(),
            registration: <C as ComponentOf<kinds::Agent>>::registration(),
            model,
            runtime: None,
            marker: std::marker::PhantomData,
        }
    }

    /// Tools' calls to the service's event sourced entities are answered in memory.
    pub fn with_service(mut self, service: Service) -> AgentTestKit<C> {
        self.runtime = Some(in_memory(service));
        self
    }

    fn guardrail(&self, name: &str, stage: Stage, text: &str) -> Option<String> {
        let request = proto::GuardrailRequest {
            component_id: C::COMPONENT_ID.to_string(),
            session_id: self.session_id.clone(),
            guardrail: name.to_string(),
            stage: match stage {
                Stage::Input => proto::guardrail_request::Stage::Input as i32,
                Stage::Output => proto::guardrail_request::Stage::Output as i32,
            },
            text: text.to_string(),
        };
        match self.registration.check_guardrail(request)?.result {
            Some(proto::guardrail_result::Result::Block(reason)) => {
                Some(format!("guardrail '{name}': {reason}"))
            }
            _ => None,
        }
    }

    /// Runs handler `name` with `input`, and the loop after it.
    pub fn call<In: Serialize + 'static>(&mut self, name: &str, input: In) -> AgentReply {
        let request = proto::PlanRequest {
            component_id: C::COMPONENT_ID.to_string(),
            session_id: self.session_id.clone(),
            name: name.to_string(),
            payload: Some(encoded(&input, "the input")),
            metadata: None,
        };
        let planned = self.registration.plan(request).expect("an agent answers");
        let plan = match planned.message {
            Some(proto::plan_reply::Message::Plan(plan)) => plan,
            Some(proto::plan_reply::Message::Failure(f)) => fault(Some(f), name),
            None => panic!("'{name}' planned nothing"),
        };
        let mut reply = AgentReply {
            plan: plan.clone(),
            tool_calls: Vec::new(),
            tool_results: Vec::new(),
            reply: None,
            error: plan.failure.as_ref().map(CommandError::from_proto),
        };
        if reply.error.is_some() {
            return reply;
        }
        let forbidden = |reason: String| Some(CommandError::new(ErrorCode::Forbidden, reason));
        let user = plan.user.clone().unwrap_or_default();
        for g in &plan.guardrails {
            if let Some(reason) = self.guardrail(g, Stage::Input, &user) {
                reply.error = forbidden(reason);
                return reply;
            }
        }
        let mut steps = 0;
        loop {
            match self.model.next() {
                Scripted::Refusal(reason) => {
                    reply.error = forbidden(reason);
                    return reply;
                }
                Scripted::Tool(tool, arguments) => {
                    steps += 1;
                    if steps > C::max_tool_call_steps() {
                        reply.error = Some(CommandError::new(
                            ErrorCode::Internal,
                            format!("exceeded {} tool-call steps", C::max_tool_call_steps()),
                        ));
                        return reply;
                    }
                    let result = if plan.tools.contains(&tool) {
                        let request = proto::ToolRequest {
                            component_id: C::COMPONENT_ID.to_string(),
                            session_id: self.session_id.clone(),
                            tool: tool.clone(),
                            arguments_json: arguments.to_string(),
                        };
                        let registration = &self.registration;
                        match hosted(&self.runtime, || registration.invoke_tool(request))
                            .and_then(|r| r.result)
                        {
                            Some(proto::tool_result::Result::Ok(text)) => text,
                            Some(proto::tool_result::Result::Error(text)) => {
                                format!("error: {text}")
                            }
                            None => String::new(),
                        }
                    } else {
                        format!("no tool named '{tool}' is available")
                    };
                    reply.tool_calls.push((tool, arguments));
                    reply.tool_results.push(result);
                }
                Scripted::Text(text) => {
                    for g in &plan.guardrails {
                        if let Some(reason) = self.guardrail(g, Stage::Output, &text) {
                            reply.error = forbidden(reason);
                            return reply;
                        }
                    }
                    reply.reply = Some(text);
                    return reply;
                }
            }
        }
    }
}

// ── Autonomous agents ────────────────────────────────────────────────────────

/// Runs an autonomous agent's parts for one task — its tools, its guardrails and its task types'
/// rules — as the runtime calls them. There is no loop here and no model: the runtime runs those,
/// and what a module decides is only ever one of these three calls.
pub struct AutonomousAgentTestKit<C: AutonomousAgent> {
    task_id: String,
    registration: AutonomousRegistration<C>,
    runtime: Option<Rc<InMemory>>,
}

impl<C: AutonomousAgent> AutonomousAgentTestKit<C> {
    /// Calls made for task `task_id`, which a tool reads as `ctx.task_id()`.
    pub fn new(task_id: &str) -> AutonomousAgentTestKit<C> {
        AutonomousAgentTestKit {
            task_id: task_id.to_string(),
            registration: AutonomousRegistration::new(),
            runtime: None,
        }
    }

    /// Tools' and rules' calls to the service's event sourced entities are answered in memory.
    pub fn with_service(mut self, service: Service) -> AutonomousAgentTestKit<C> {
        self.runtime = Some(in_memory(service));
        self
    }

    fn session(&self) -> String {
        format!("task:{}", self.task_id)
    }

    /// Runs tool `name` with `arguments`: `Ok` is what the model is told, `Err` the error it is
    /// told instead.
    pub fn run_tool(&self, name: &str, arguments: Value) -> Result<String, String> {
        let request = proto::ToolRequest {
            component_id: C::COMPONENT_ID.to_string(),
            session_id: self.session(),
            tool: name.to_string(),
            arguments_json: arguments.to_string(),
        };
        let registration = &self.registration;
        match hosted(&self.runtime, || registration.invoke_tool(request)).and_then(|r| r.result) {
            Some(proto::tool_result::Result::Ok(text)) => Ok(text),
            Some(proto::tool_result::Result::Error(text)) => Err(text),
            None => Err(String::new()),
        }
    }

    /// Checks guardrail `name` at `stage`: `Err` is the reason the task fails.
    pub fn check_guardrail(&self, name: &str, stage: Stage, text: &str) -> Result<(), String> {
        let request = proto::GuardrailRequest {
            component_id: C::COMPONENT_ID.to_string(),
            session_id: self.session(),
            guardrail: name.to_string(),
            stage: match stage {
                Stage::Input => proto::guardrail_request::Stage::Input as i32,
                Stage::Output => proto::guardrail_request::Stage::Output as i32,
            },
            text: text.to_string(),
        };
        let registration = &self.registration;
        match hosted(&self.runtime, || registration.check_guardrail(request)).and_then(|r| r.result)
        {
            Some(proto::guardrail_result::Result::Block(reason)) => Err(reason),
            _ => Ok(()),
        }
    }

    /// Runs rule `rule` of `task_type` alone on `result`, which crosses JSON as the model's would.
    pub fn check_rule<R>(&self, task_type: &TaskType<R>, rule: &str, result: &R) -> Verdict
    where
        R: Serialize + DeserializeOwned + 'static,
    {
        let json = serde_json::to_string(result)
            .unwrap_or_else(|e| panic!("the result does not encode: {e}"));
        let registration = &self.registration;
        let checked = hosted(&self.runtime, || {
            registration.rule(task_type.name(), rule, &json, &self.task_id)
        });
        match checked {
            Some(Ok(verdict)) => verdict,
            Some(Err(problem)) => panic!("the result does not decode: {problem}"),
            None => panic!(
                "task type '{}' of '{}' has no rule '{rule}'",
                task_type.name(),
                C::COMPONENT_ID
            ),
        }
    }

    /// Checks `result` as the runtime asks the module to: decoded, then every rule in order.
    pub fn check_result<R>(&self, task_type: &TaskType<R>, result: &R) -> ResultCheck
    where
        R: Serialize + DeserializeOwned + 'static,
    {
        let json = serde_json::to_string(result)
            .unwrap_or_else(|e| panic!("the result does not encode: {e}"));
        self.check_result_json(task_type.name(), &json)
    }

    /// Checks a result as the model wrote it, JSON that may not decode.
    pub fn check_result_json(&self, task_type: &str, result_json: &str) -> ResultCheck {
        let registration = &self.registration;
        hosted(&self.runtime, || {
            registration.check(task_type, result_json, &self.task_id)
        })
    }
}
