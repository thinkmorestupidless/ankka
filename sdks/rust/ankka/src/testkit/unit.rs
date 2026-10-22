//! Unit testkits: one component, run natively, with nothing else running.
//!
//! Effects are values, so a handler can be run against an in-memory state and what it decided
//! inspected. The kits drive a component through the dispatch its module's exports use, so inputs,
//! events, state and replies all cross the component's own codecs: a type the codec cannot encode
//! fails here rather than on first deployment.
//!
//! ```ignore
//! let mut kit = EventSourcedTestKit::<ShoppingCart>::new("cart-1");
//! let outcome = kit.command("add-item", item.clone());
//! assert_eq!(outcome.events, vec![ShoppingCartEvent::ItemAdded { item }]);
//! assert_eq!(outcome.reply::<Done>(), Ok(Done));
//! ```

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use prost::Message;
use serde::Serialize;
use serde::de::DeserializeOwned;

use super::TestResponse;
use crate::abi::imports::{Import, NativeHost, with_native_host};
use crate::codec::time::Duration;
use crate::codec::{Codec, decode_payload, encode_payload};
use crate::components::endpoint::{EndpointRegistration, RegisteredEndpoint};
use crate::components::{ComponentOf, Endpoint, EventSourcedEntity, HeldState, Registered, kinds};
use crate::context::Metadata;
use crate::effects::{CommandError, ErrorCode, ReplicationChange, Retention};
use crate::proto::{self, Kind, Payload};
use crate::service::Service;

// ── Event sourced entities ───────────────────────────────────────────────────

/// What one command did: the events it persisted, the state after them, and its answer.
#[derive(Debug)]
pub struct CommandOutcome<C: EventSourcedEntity> {
    /// The events persisted, in order, decoded back from their stored form; none after a refusal.
    pub events: Vec<C::Event>,
    /// The state after the events, decoded back from its stored form.
    pub new_state: C::State,
    /// What happens to the entity afterwards, if the handler said.
    pub retention: Option<Retention>,
    /// The handover or change to the replication set the handler asked for, if any.
    pub replication: Option<ReplicationChange>,
    /// The metadata the handler set on its reply.
    pub metadata: Metadata,
    reply: Option<Payload>,
    error: Option<CommandError>,
}

impl<C: EventSourcedEntity> CommandOutcome<C> {
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

    /// Whether the command persisted anything.
    pub fn persisted(&self) -> bool {
        !self.events.is_empty()
    }
}

/// How a replicated entity's copy stands in the test kit (protocol 1.19).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ReplicationMode {
    /// Not replicated: every command runs, and a replication change is refused.
    Unreplicated,
    /// The home, at `installation`: every command runs.
    Home {
        /// This copy's installation.
        installation: String,
    },
    /// A follower at `installation`: a command that may persist is refused `NotHome`, naming
    /// `home`, and a query is answered from what [`replay`](EventSourcedTestKit::replay) gave it.
    Follower {
        /// The entity's home.
        home: String,
        /// This copy's installation.
        installation: String,
    },
}

/// Drives one instance of an event sourced entity through its handlers, carrying the state from
/// one command to the next as the runtime would.
pub struct EventSourcedTestKit<C: EventSourcedEntity> {
    entity_id: String,
    registration: Box<dyn Registered>,
    held: HeldState,
    stored: Option<Payload>,
    state: C::State,
    sequence: i64,
    commands: i64,
    events: Vec<C::Event>,
    mode: ReplicationMode,
}

impl<C: EventSourcedEntity> EventSourcedTestKit<C> {
    /// Instance `entity_id`, fresh: its empty state and no events.
    pub fn new(entity_id: &str) -> EventSourcedTestKit<C> {
        EventSourcedTestKit {
            entity_id: entity_id.to_string(),
            registration: <C as ComponentOf<kinds::EventSourced>>::registration(),
            held: HeldState::default(),
            stored: None,
            state: C::empty_state(entity_id),
            sequence: 0,
            commands: 0,
            events: Vec::new(),
            mode: ReplicationMode::Unreplicated,
        }
    }

    /// Drives the copy as `mode` says: the home, or a follower of another installation. Not
    /// replicated unless said.
    pub fn in_mode(mut self, mode: ReplicationMode) -> EventSourcedTestKit<C> {
        self.mode = mode;
        self
    }

    fn here(&self) -> Option<String> {
        match &self.mode {
            ReplicationMode::Unreplicated => None,
            ReplicationMode::Home { installation }
            | ReplicationMode::Follower { installation, .. } => Some(installation.clone()),
        }
    }

    /// Applies `events` as history the copy already holds, written at `written_at` (this
    /// installation when `None`): for a follower, what it has mirrored from its home. Each crosses
    /// the event codec and the entity's `apply_at`, as a replay in the runtime does.
    pub fn replay(&mut self, events: Vec<C::Event>, written_at: Option<&str>) {
        let here = self.here();
        let written = written_at.map(str::to_string).or_else(|| here.clone());
        let mirrored = matches!((&written, &here), (Some(w), Some(h)) if w != h);
        for event in events {
            let payload = C::event_codec().payload(&event).unwrap_or_else(|e| {
                panic!("an event of '{}' does not encode: {e}", C::COMPONENT_ID)
            });
            self.sequence += 1;
            let reply = self.registration.fold(
                proto::FoldRequest {
                    component_id: C::COMPONENT_ID.to_string(),
                    entity_id: self.entity_id.clone(),
                    state: self.stored.clone(),
                    event: Some(payload),
                    sequence: self.sequence,
                    installation: written.clone().unwrap_or_default(),
                    mirrored,
                    // History the copy already holds was written by nobody the kit names.
                    ..Default::default()
                },
                &mut self.held,
            );
            if let Some(failure) = reply.failure {
                let error = failure.error.map(|e| CommandError::from_proto(&e));
                panic!("replaying into '{}' faulted: {error:?}", C::COMPONENT_ID);
            }
            self.state = C::state_codec()
                .decode_value(
                    &reply
                        .state
                        .as_ref()
                        .map(|p| p.data.clone())
                        .unwrap_or_default(),
                )
                .unwrap_or_else(|e| panic!("the replayed state does not decode: {e}"));
            self.stored = reply.state;
            self.events.push(event);
        }
    }

    /// Starts from `state` instead, as if the instance had been recovered to it.
    pub fn with_state(mut self, state: C::State) -> EventSourcedTestKit<C> {
        let stored = C::state_codec()
            .to_payload(&state)
            .unwrap_or_else(|e| panic!("the state of '{}' does not encode: {e}", C::COMPONENT_ID));
        self.stored = Some(stored);
        self.state = state;
        self
    }

    /// Runs handler `name` with `input`, answering what it did. A handler that faults — its input
    /// does not decode, an event does not encode — panics with the fault, as the runtime would
    /// fail the command.
    pub fn command<In: Serialize + 'static>(&mut self, name: &str, input: In) -> CommandOutcome<C> {
        self.command_with(name, input, Metadata::default())
    }

    /// As [`command`](Self::command), with metadata on the command.
    pub fn command_with<In: Serialize + 'static>(
        &mut self,
        name: &str,
        input: In,
        metadata: Metadata,
    ) -> CommandOutcome<C> {
        if let ReplicationMode::Follower { home, .. } = &self.mode {
            let read_only = C::handlers().find(name).is_some_and(|h| h.read_only);
            if !read_only {
                // The runtime forwards such a command to the home; here there is no home to forward to.
                let message = format!(
                    "the home of '{}' is '{home}'; a follower handles no command that may persist",
                    self.entity_id
                );
                return self.refused(CommandError::new(ErrorCode::NotHome, message));
            }
        }
        self.commands += 1;
        let payload = encode_payload(&input)
            .unwrap_or_else(|e| panic!("the input to '{name}' does not encode: {e}"));
        let metadata = metadata.set("ankka.sequence", self.sequence.to_string());
        let request = proto::HandleRequest {
            kind: Kind::EventSourcedEntity as i32,
            component_id: C::COMPONENT_ID.to_string(),
            entity_id: self.entity_id.clone(),
            state: self.stored.clone(),
            command: Some(proto::handle_request::Command::EventSourced(
                proto::event_sourced_in::Command {
                    id: self.commands,
                    name: name.to_string(),
                    payload: Some(payload),
                    metadata: Some(metadata.to_proto()),
                    snapshot_requested: false,
                    installation: self.here().unwrap_or_default(),
                    at_home: !matches!(self.mode, ReplicationMode::Follower { .. }),
                    // The unit kit's calls are made by nobody and forwarded from nowhere.
                    attribution: String::new(),
                    forwarded_from: String::new(),
                },
            )),
        };
        let reply = self.registration.handle(request, &mut self.held);
        if let Some(failure) = reply.failure {
            let error = failure.error.map(|e| CommandError::from_proto(&e));
            panic!("'{name}' on '{}' faulted: {error:?}", C::COMPONENT_ID);
        }
        let Some(proto::handle_reply::Reply::EventSourced(out)) = reply.reply else {
            panic!(
                "'{name}' on '{}' answered no event sourced reply",
                C::COMPONENT_ID
            );
        };
        let codec = C::event_codec();
        let decode_events = || -> Vec<C::Event> {
            out.events
                .iter()
                .map(|e| {
                    codec
                        .decode_value(&e.data)
                        .unwrap_or_else(|err| panic!("an event of '{name}' does not decode: {err}"))
                })
                .collect()
        };
        let events = decode_events();
        self.events.extend(decode_events());
        let stored = reply.state.clone();
        let new_state = match &stored {
            Some(p) => C::state_codec()
                .decode_value(&p.data)
                .unwrap_or_else(|e| panic!("the state after '{name}' does not decode: {e}")),
            None => C::empty_state(&self.entity_id),
        };
        let (reply_payload, reply_metadata, error) = match out.outcome.and_then(|o| o.outcome) {
            Some(proto::outcome::Outcome::Reply(r)) => {
                (r.payload, Metadata::from_proto(r.metadata.as_ref()), None)
            }
            Some(proto::outcome::Outcome::Error(e)) => (
                None,
                Metadata::default(),
                Some(CommandError::from_proto(&e)),
            ),
            _ => (None, Metadata::default(), None),
        };
        let retention = out.retention.and_then(retention_of);
        let replication = out.replication.and_then(replication_of);
        if replication.is_some() && self.mode == ReplicationMode::Unreplicated {
            return self.refused(CommandError::new(
                ErrorCode::BadRequest,
                format!(
                    "'{}' is not replicated in this test kit; drive it with `in_mode(ReplicationMode::Home {{ .. }})` \
                     to see the handover or the change to the replication set it asks for",
                    C::COMPONENT_ID
                ),
            ));
        }
        self.sequence += events.len() as i64;
        if retention == Some(Retention::DeleteNow) {
            // Deleted: the next command finds the instance fresh.
            self.stored = None;
            self.state = C::empty_state(&self.entity_id);
        } else {
            self.state = match &stored {
                Some(p) => C::state_codec()
                    .decode_value(&p.data)
                    .expect("decoded once already"),
                None => C::empty_state(&self.entity_id),
            };
            self.stored = stored;
        }
        CommandOutcome {
            events,
            new_state,
            retention,
            replication,
            metadata: reply_metadata,
            reply: reply_payload,
            error,
        }
    }

    /// A refusal the kit answers itself, as the runtime would: nothing persisted, nothing changed.
    fn refused(&mut self, error: CommandError) -> CommandOutcome<C> {
        // A stateful entity may have kept what the refused call computed; the stored state stands.
        self.held = HeldState::default();
        let new_state = match &self.stored {
            Some(p) => C::state_codec()
                .decode_value(&p.data)
                .expect("the stored state decodes"),
            None => C::empty_state(&self.entity_id),
        };
        CommandOutcome {
            events: Vec::new(),
            new_state,
            retention: None,
            replication: None,
            metadata: Metadata::default(),
            reply: None,
            error: Some(error),
        }
    }

    /// The state now.
    pub fn state(&self) -> &C::State {
        &self.state
    }

    /// Every event persisted so far, in order.
    pub fn events(&self) -> &[C::Event] {
        &self.events
    }
}

fn replication_of(change: proto::ReplicationChange) -> Option<ReplicationChange> {
    match change.change? {
        proto::replication_change::Change::HandOverTo(to) => {
            Some(ReplicationChange::HandOver { to })
        }
        proto::replication_change::Change::Set(set) => Some(ReplicationChange::Set {
            add: set.add,
            remove: set.remove,
        }),
    }
}

fn retention_of(retention: proto::Retention) -> Option<Retention> {
    match retention.retention? {
        proto::retention::Retention::DeleteNow(_) => Some(Retention::DeleteNow),
        proto::retention::Retention::ExpireAfter(e) => {
            Some(Retention::ExpireAfter(Duration::of_millis(e.millis)))
        }
    }
}

// ── Endpoints ────────────────────────────────────────────────────────────────

/// A service's components in memory, answering the calls an endpoint's handlers make: what lets
/// an endpoint be tested with the entities behind it and nothing else running.
pub(crate) struct InMemory {
    service: Service,
    states: RefCell<HashMap<(String, String), Payload>>,
    commands: Cell<i64>,
}

impl InMemory {
    fn invoke(&self, request: proto::InvokeRequest) -> proto::InvokeReply {
        let refuse = |code: ErrorCode, message: String| proto::InvokeReply {
            result: Some(proto::invoke_reply::Result::Error(
                CommandError::new(code, message).to_proto(),
            )),
        };
        if request.kind != Kind::EventSourcedEntity as i32 {
            return refuse(
                ErrorCode::Unavailable,
                format!(
                    "the unit testkit answers event sourced entities, not '{}'",
                    request.component_id
                ),
            );
        }
        self.commands.set(self.commands.get() + 1);
        let key = (request.component_id.clone(), request.entity_id.clone());
        let state = self.states.borrow().get(&key).cloned();
        let reply = self.service.handle(proto::HandleRequest {
            kind: request.kind,
            component_id: request.component_id,
            entity_id: request.entity_id,
            state,
            command: Some(proto::handle_request::Command::EventSourced(
                proto::event_sourced_in::Command {
                    id: self.commands.get(),
                    name: request.name,
                    payload: request.payload,
                    metadata: request.metadata,
                    snapshot_requested: false,
                    ..Default::default()
                },
            )),
        });
        if let Some(failure) = reply.failure {
            let error = failure.error.unwrap_or_default();
            return proto::InvokeReply {
                result: Some(proto::invoke_reply::Result::Error(error)),
            };
        }
        let Some(proto::handle_reply::Reply::EventSourced(out)) = reply.reply else {
            return refuse(ErrorCode::Internal, "no reply".to_string());
        };
        let deleted = matches!(
            out.retention.as_ref().and_then(|r| r.retention.as_ref()),
            Some(proto::retention::Retention::DeleteNow(_))
        );
        match (deleted, reply.state) {
            (false, Some(state)) => {
                self.states.borrow_mut().insert(key, state);
            }
            _ => {
                self.states.borrow_mut().remove(&key);
            }
        }
        match out.outcome.and_then(|o| o.outcome) {
            Some(proto::outcome::Outcome::Error(e)) => proto::InvokeReply {
                result: Some(proto::invoke_reply::Result::Error(e)),
            },
            Some(proto::outcome::Outcome::Reply(r)) => proto::InvokeReply {
                result: Some(proto::invoke_reply::Result::Reply(r)),
            },
            _ => proto::InvokeReply {
                result: Some(proto::invoke_reply::Result::Reply(proto::outcome::Reply {
                    payload: encode_payload(&crate::Done).ok(),
                    metadata: None,
                })),
            },
        }
    }
}

struct Shared(Rc<InMemory>);

/// A service's event sourced entities in memory, for the kits whose components call them.
pub(crate) fn in_memory(service: Service) -> Rc<InMemory> {
    Rc::new(InMemory {
        service,
        states: RefCell::default(),
        commands: Cell::new(0),
    })
}

/// Runs `f` with `runtime` answering its client calls, if there is one.
pub(crate) fn hosted<T>(runtime: &Option<Rc<InMemory>>, f: impl FnOnce() -> T) -> T {
    match runtime {
        Some(runtime) => with_native_host(Shared(runtime.clone()), f),
        None => f(),
    }
}

impl NativeHost for Shared {
    fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
        match import {
            Import::Invoke => match proto::InvokeRequest::decode(request) {
                Ok(request) => self.0.invoke(request).encode_to_vec(),
                Err(e) => panic!("an invoke that does not decode: {e}"),
            },
            // Sent and not waited for: answered in memory, the answer dropped.
            Import::Send => match proto::InvokeRequest::decode(request) {
                Ok(request) => {
                    let _ = self.0.invoke(request);
                    Vec::new()
                }
                Err(e) => panic!("a send that does not decode: {e}"),
            },
            Import::Config => proto::ConfigReply { value: None }.encode_to_vec(),
            other => {
                panic!("the unit testkit does not answer {other:?}; use the integration testkit")
            }
        }
    }
}

/// Calls an endpoint's routes by method and path, binding the path's parameters as the runtime's
/// router does — a literal segment outranks a parameter. With no service behind it, a handler's
/// component calls are refused as unavailable; [`with_service`](Self::with_service) answers them
/// from the service's own components, in memory.
pub struct EndpointTestKit<E: Endpoint> {
    registration: EndpointRegistration<E>,
    runtime: Option<Rc<InMemory>>,
}

impl<E: Endpoint> Default for EndpointTestKit<E> {
    fn default() -> EndpointTestKit<E> {
        EndpointTestKit::new()
    }
}

impl<E: Endpoint> EndpointTestKit<E> {
    /// The endpoint alone.
    pub fn new() -> EndpointTestKit<E> {
        EndpointTestKit {
            registration: EndpointRegistration::new(),
            runtime: None,
        }
    }

    /// The endpoint in front of `service`'s entities, held in memory for the kit's life.
    pub fn with_service(service: Service) -> EndpointTestKit<E> {
        EndpointTestKit {
            registration: EndpointRegistration::new(),
            runtime: Some(Rc::new(InMemory {
                service,
                states: RefCell::default(),
                commands: Cell::new(0),
            })),
        }
    }

    /// A request to send.
    pub fn request(&self, method: &str, path: &str) -> TestRequest<'_, E> {
        TestRequest {
            kit: self,
            method: method.to_ascii_uppercase(),
            path: path.to_string(),
            query: Vec::new(),
            headers: Vec::new(),
            content_type: String::new(),
            body: Vec::new(),
        }
    }

    /// `GET path`.
    pub fn get(&self, path: &str) -> TestResponse {
        self.request("GET", path).send()
    }

    /// `DELETE path`.
    pub fn delete(&self, path: &str) -> TestResponse {
        self.request("DELETE", path).send()
    }

    /// `POST path` with `body` as JSON.
    pub fn post<B: Serialize>(&self, path: &str, body: &B) -> TestResponse {
        self.request("POST", path).json(body).send()
    }

    /// The route `method path` matches, and the path's arguments in template order.
    fn route(&self, method: &str, path: &str) -> Option<(String, Vec<String>)> {
        let rest = path.strip_prefix(E::PREFIX)?;
        let rest = if rest.is_empty() { "/" } else { rest };
        let segments: Vec<&str> = rest.split('/').collect();
        let mut matches: Vec<(usize, String, Vec<String>)> = Vec::new();
        for (m, template, id) in self.registration.route_table() {
            if m != method {
                continue;
            }
            let parts: Vec<&str> = template.split('/').collect();
            if parts.len() != segments.len() {
                continue;
            }
            let mut args = Vec::new();
            let mut literals = 0;
            let fits = parts.iter().zip(&segments).all(|(p, s)| {
                if p.starts_with('{') && p.ends_with('}') {
                    args.push((*s).to_string());
                    !s.is_empty()
                } else {
                    literals += usize::from(!p.is_empty());
                    p == s
                }
            });
            if fits {
                matches.push((literals, id, args));
            }
        }
        matches.sort_by_key(|m| std::cmp::Reverse(m.0));
        matches.into_iter().next().map(|(_, id, args)| (id, args))
    }
}

/// A request being built for [`EndpointTestKit`].
pub struct TestRequest<'k, E: Endpoint> {
    kit: &'k EndpointTestKit<E>,
    method: String,
    path: String,
    query: Vec<(String, String)>,
    headers: Vec<(String, String)>,
    content_type: String,
    body: Vec<u8>,
}

impl<E: Endpoint> TestRequest<'_, E> {
    /// A body of `value` as JSON.
    pub fn json<B: Serialize>(mut self, value: &B) -> Self {
        self.body = serde_json::to_vec(value).expect("the body serializes");
        self.content_type = "application/json".to_string();
        self
    }

    /// A text body.
    pub fn text(mut self, text: &str) -> Self {
        self.body = text.as_bytes().to_vec();
        self.content_type = "text/plain".to_string();
        self
    }

    /// A query parameter.
    pub fn query(mut self, name: &str, value: &str) -> Self {
        self.query.push((name.to_string(), value.to_string()));
        self
    }

    /// A header.
    pub fn header(mut self, name: &str, value: &str) -> Self {
        self.headers.push((name.to_string(), value.to_string()));
        self
    }

    /// Sends it: a 404 when no route matches, as the runtime's router answers.
    pub fn send(self) -> TestResponse {
        let Some((route_id, path_args)) = self.kit.route(&self.method, &self.path) else {
            return TestResponse {
                status: 404,
                content_type: "text/plain".to_string(),
                body: format!("no route {} {}", self.method, self.path).into_bytes(),
            };
        };
        let pair = |(name, value): (String, String)| proto::http_request::Pair { name, value };
        let request = proto::HttpRequest {
            endpoint_id: E::ENDPOINT_ID.to_string(),
            route_id,
            path_args,
            query: self.query.into_iter().map(pair).collect(),
            headers: self.headers.into_iter().map(pair).collect(),
            content_type: self.content_type,
            body: self.body,
            principal: None,
            metadata: None,
            caller: None,
        };
        let registration = &self.kit.registration;
        let reply = match &self.kit.runtime {
            Some(runtime) => {
                with_native_host(Shared(runtime.clone()), || registration.handle(request))
            }
            None => registration.handle(request),
        };
        match reply.message {
            Some(proto::http_reply::Message::Response(r)) => TestResponse {
                status: u16::try_from(r.status).unwrap_or(500),
                content_type: r.content_type,
                body: r.body,
            },
            Some(proto::http_reply::Message::Failure(f)) => TestResponse {
                status: 500,
                content_type: "text/plain".to_string(),
                body: f.error.map(|e| e.message).unwrap_or_default().into_bytes(),
            },
            None => TestResponse {
                status: 500,
                content_type: "text/plain".to_string(),
                body: b"the endpoint answered nothing".to_vec(),
            },
        }
    }
}

/// Answers `ankka::config` from a list of variables, and nothing else.
struct Configured(Vec<(String, String)>);

impl NativeHost for Configured {
    fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
        match import {
            Import::Config => {
                let name = proto::ConfigRequest::decode(request)
                    .unwrap_or_else(|e| panic!("a config request that does not decode: {e}"))
                    .name;
                let value = self
                    .0
                    .iter()
                    .find(|(variable, _)| *variable == name)
                    .map(|(_, value)| value.clone());
                proto::ConfigReply { value }.encode_to_vec()
            }
            other => panic!("with_config answers only config, not {other:?}"),
        }
    }
}

/// Runs `f` with the service's descriptor setting these variables, as [`crate::config()`] reads
/// them: for testing what a service does with its configuration, such as registering a component
/// only where a broker is named. Outside it, a test's `config` reads every variable as unset.
pub fn with_config<T>(variables: &[(&str, &str)], f: impl FnOnce() -> T) -> T {
    let variables = variables
        .iter()
        .map(|(name, value)| (name.to_string(), value.to_string()))
        .collect();
    with_native_host(Configured(variables), f)
}

// ── Storage ──────────────────────────────────────────────────────────────────

/// What one object is kept as in the unit test's store.
#[derive(Debug, Clone)]
struct Kept {
    content_type: String,
    body: Vec<u8>,
    last_kept: crate::codec::time::Instant,
}

#[derive(Debug, Default)]
struct Buckets {
    /// The named buckets the service declares, and how each is reached.
    declared: HashMap<String, crate::storage::BucketExposure>,
    /// Every object, by bucket (`""` for the unnamed one: no bucket name is empty) and name.
    objects: HashMap<(String, String), Kept>,
}

/// The service's buckets in memory, answering the `storage` import as the runtime does: a unit
/// test's store. It applies the runtime's rules in the runtime's words — names and prefixes, the
/// bound on a body, a signed URL only from a signed bucket and an address only from a public one,
/// a bucket the service does not declare refused naming those it does — and answers a listing in
/// pages of 1000, as the runtime does.
///
/// The unnamed bucket is always there, and signed, as a service that sets `exposeObjectStorage`'s
/// is. Named buckets are declared: `InMemoryStorage::new().declare("photos", BucketExposure::Public)`.
/// A signed URL is computed (`http://memory/…`) and reaches nothing; a public bucket's address is
/// `http://memory/public/<bucket>/<name>`.
///
/// A handler reaches it through [`with_storage`]; with none given, a test's handlers reach a store
/// of this kind that belongs to the test's thread. Clones share the objects.
#[derive(Debug, Clone, Default)]
pub struct InMemoryStorage {
    buckets: Rc<RefCell<Buckets>>,
}

/// The most entries one page of a listing holds, as the runtime pages them.
const LIST_PAGE: usize = 1000;

impl InMemoryStorage {
    /// A store with the unnamed bucket and no named one.
    pub fn new() -> InMemoryStorage {
        InMemoryStorage::default()
    }

    /// This store with a named bucket `name`, reached as `exposure` says.
    ///
    /// # Panics
    ///
    /// When `name` is not a bucket name.
    pub fn declare(self, name: &str, exposure: crate::storage::BucketExposure) -> InMemoryStorage {
        if let Some(problem) = crate::storage::bucket_name_problem(name) {
            panic!("{problem}");
        }
        self.buckets
            .borrow_mut()
            .declared
            .insert(name.to_string(), exposure);
        self
    }

    /// Keeps `body` under `name` in `bucket` (`None`: the unnamed one) as a test sets the scene,
    /// with no rule applied and no bound: how a test puts an object larger than the import carries.
    pub fn seed(&self, bucket: Option<&str>, name: &str, body: &[u8], content_type: &str) {
        let kept = Kept {
            content_type: content_type.to_string(),
            body: body.to_vec(),
            last_kept: crate::codec::time::Instant::from_epoch_millis(crate::abi::imports::now()),
        };
        self.buckets.borrow_mut().objects.insert(
            (bucket.unwrap_or_default().to_string(), name.to_string()),
            kept,
        );
    }

    /// The object under `name` in `bucket`, with its content type, or `None`.
    pub fn get(&self, bucket: Option<&str>, name: &str) -> Option<(String, Vec<u8>)> {
        self.buckets
            .borrow()
            .objects
            .get(&(bucket.unwrap_or_default().to_string(), name.to_string()))
            .map(|k| (k.content_type.clone(), k.body.clone()))
    }

    /// Every name kept in `bucket`, in order.
    pub fn names(&self, bucket: Option<&str>) -> Vec<String> {
        let bucket = bucket.unwrap_or_default();
        let mut names: Vec<String> = self
            .buckets
            .borrow()
            .objects
            .keys()
            .filter(|(b, _)| b == bucket)
            .map(|(_, n)| n.clone())
            .collect();
        names.sort();
        names
    }

    /// Forgets every object; the declared buckets stay.
    pub fn clear(&self) {
        self.buckets.borrow_mut().objects.clear();
    }

    /// Answers one request as the runtime's `storage` import does.
    pub fn answer(&self, request: proto::StorageRequest) -> proto::StorageReply {
        use proto::storage_reply::Reply;
        let reply = self.respond(request).unwrap_or_else(|(code, message)| {
            Reply::Error(CommandError::new(code, message).to_proto())
        });
        proto::StorageReply { reply: Some(reply) }
    }

    fn respond(
        &self,
        r: proto::StorageRequest,
    ) -> Result<proto::storage_reply::Reply, (ErrorCode, String)> {
        use crate::storage::{
            BucketExposure, DEFAULT_SIGNED_URL_SECONDS, MAX_BODY_BYTES, name_problem,
            percent_encode, prefix_problem, refusals, span_problem,
        };
        use proto::storage_reply::Reply;
        use proto::storage_request::{Access, Op};
        let bad = |message: String| (ErrorCode::BadRequest, message);
        let done = || Reply::Done(proto::Empty {});
        let exposure = match &r.bucket {
            None => BucketExposure::Signed,
            Some(name) => {
                let buckets = self.buckets.borrow();
                *buckets.declared.get(name).ok_or_else(|| {
                    (
                        ErrorCode::Unavailable,
                        refusals::unknown(name, buckets.declared.keys()),
                    )
                })?
            }
        };
        let bucket = r.bucket.clone().unwrap_or_default();
        let key = (bucket.clone(), r.name.clone());
        let op = Op::try_from(r.op).map_err(|_| {
            bad(format!(
                "storage operation {} is not one this runtime knows",
                r.op
            ))
        })?;
        if op != Op::List
            && let Some(problem) = name_problem(&r.name)
        {
            return Err(bad(problem));
        }
        match op {
            Op::Put => {
                let body = r.body.unwrap_or_default();
                if body.len() > MAX_BODY_BYTES {
                    return Err(bad(refusals::too_large(
                        "an object kept",
                        body.len() as u64,
                    )));
                }
                self.seed(r.bucket.as_deref(), &r.name, &body, &r.content_type);
                Ok(done())
            }
            Op::Get => match self.buckets.borrow().objects.get(&key) {
                None => Ok(done()),
                Some(k) if k.body.len() > MAX_BODY_BYTES => Err(bad(refusals::too_large(
                    "an object read",
                    k.body.len() as u64,
                ))),
                Some(k) => Ok(Reply::Object(proto::StoredObject {
                    content_type: k.content_type.clone(),
                    body: k.body.clone(),
                    length: k.body.len() as u64,
                    last_kept: k.last_kept.to_string(),
                })),
            },
            Op::Delete => {
                self.buckets.borrow_mut().objects.remove(&key);
                Ok(done())
            }
            Op::List => {
                if let Some(problem) = prefix_problem(&r.name) {
                    return Err(bad(problem));
                }
                let after = r.continuation.unwrap_or_default();
                let buckets = self.buckets.borrow();
                let mut found: Vec<(&String, &Kept)> = buckets
                    .objects
                    .iter()
                    .filter(|((b, n), _)| {
                        *b == bucket && n.starts_with(&r.name) && (after.is_empty() || *n > after)
                    })
                    .map(|((_, n), k)| (n, k))
                    .collect();
                found.sort_by(|a, b| a.0.cmp(b.0));
                let more = found.len() > LIST_PAGE;
                found.truncate(LIST_PAGE);
                Ok(Reply::Entries(proto::ObjectEntries {
                    next: more
                        .then(|| found.last().map(|(n, _)| (*n).clone()))
                        .flatten(),
                    entries: found
                        .into_iter()
                        .map(|(n, k)| proto::ObjectEntry {
                            name: n.clone(),
                            length: k.body.len() as u64,
                            last_kept: k.last_kept.to_string(),
                        })
                        .collect(),
                }))
            }
            Op::Stat => Ok(match self.buckets.borrow().objects.get(&key) {
                None => done(),
                Some(k) => Reply::Info(proto::ObjectInfo {
                    name: r.name.clone(),
                    content_type: k.content_type.clone(),
                    length: k.body.len() as u64,
                    last_kept: k.last_kept.to_string(),
                }),
            }),
            Op::SignedUrl => {
                if exposure != BucketExposure::Signed {
                    return Err(bad(match &r.bucket {
                        None => refusals::not_exposed(),
                        Some(b) => refusals::not_signed(b, exposure),
                    }));
                }
                let span = match i64::from(r.expires_seconds) {
                    0 => DEFAULT_SIGNED_URL_SECONDS,
                    s => s,
                };
                if let Some(problem) = span_problem(span) {
                    return Err(bad(problem));
                }
                let expires = crate::abi::imports::now() / 1000 + span;
                let access = if r.access == Access::Keep as i32 {
                    "keep"
                } else {
                    "read"
                };
                Ok(Reply::Url(format!(
                    "http://memory/{}/{}?access={access}&expires={expires}",
                    r.bucket.as_deref().unwrap_or("default"),
                    percent_encode(&r.name, false)
                )))
            }
            Op::Url => match (&r.bucket, exposure) {
                (Some(b), BucketExposure::Public) => Ok(Reply::Url(format!(
                    "http://memory/public/{b}/{}",
                    percent_encode(&r.name, false)
                ))),
                (b, exposure) => Err(bad(refusals::not_public(b.as_deref(), exposure))),
            },
        }
    }
}

impl NativeHost for InMemoryStorage {
    fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
        match import {
            Import::Storage => {
                let request = proto::StorageRequest::decode(request)
                    .unwrap_or_else(|e| panic!("a storage request that does not decode: {e}"));
                self.answer(request).encode_to_vec()
            }
            other => panic!("the storage double answers only storage, not {other:?}"),
        }
    }
}

/// Runs `f` with `store` answering every storage call its handlers make on this thread, beside
/// whatever else the kit answers: how a test seeds the objects a handler reads and reads what it
/// kept.
///
/// ```ignore
/// let store = InMemoryStorage::new().declare("photos", BucketExposure::Public);
/// with_storage(&store, || kit.post("/photos/front.jpg", &body));
/// assert_eq!(store.get(Some("photos"), "front.jpg").unwrap().0, "image/jpeg");
/// ```
pub fn with_storage<T>(store: &InMemoryStorage, f: impl FnOnce() -> T) -> T {
    crate::abi::imports::with_native_storage(store.clone(), f)
}
