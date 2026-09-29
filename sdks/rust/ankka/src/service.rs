//! The service: the explicit registry of everything a module hosts, what discovery says about it,
//! and where each export's request goes.
//!
//! Registration is explicit, as in every ankka SDK: a component reaches the runtime only by being
//! registered here, so an unregistered one fails at startup rather than at its first request.
//! Problems are collected and reported together.

use std::cell::RefCell;
use std::collections::HashMap;
use std::fmt;

use prost::Message;

use crate::abi::exports::Export;
use crate::abi::imports::{Level, log};
use crate::components::endpoint::{EndpointRegistration, RegisteredEndpoint};
use crate::components::{ComponentOf, Endpoint, HeldState, Registered, Shape};
use crate::proto::{self, Kind};

/// The version of the protocol this library speaks: the one its copy of `protocol/` describes.
pub const PROTOCOL_VERSION: &str = "1.2";

/// The version of the WebAssembly ABI this library speaks: the `1` in every `ankka1_` export.
pub const ABI_VERSION: &str = "1";

/// Something wrong with a service's declaration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Problem {
    /// What is wrong, naming the component and the handler.
    pub message: String,
}

impl fmt::Display for Problem {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for Problem {}

/// Everything a module hosts. Built once, by the service's own `build` function:
///
/// ```ignore
/// fn build() -> Service {
///     Service::new("ankka-rust").register(ShoppingCart).register(CartRows)
/// }
/// ankka::service!(build);
/// ```
pub struct Service {
    sdk_name: String,
    components: Vec<Box<dyn Registered>>,
    endpoints: Vec<Box<dyn RegisteredEndpoint>>,
    problems: Vec<String>,
    held: RefCell<HashMap<String, HeldState>>,
}

impl Service {
    /// An empty service. `sdk_name` is what discovery reports the library as.
    pub fn new(sdk_name: impl Into<String>) -> Service {
        Service {
            sdk_name: sdk_name.into(),
            components: Vec::new(),
            endpoints: Vec::new(),
            problems: Vec::new(),
            held: RefCell::default(),
        }
    }

    /// Registers a component by its value: `.register(ShoppingCart)`.
    pub fn register<C: ComponentOf<M>, M>(mut self, component: C) -> Service {
        let _ = component;
        let registration = C::registration();
        if self.components.iter().any(|c| c.id() == registration.id()) {
            self.problems.push(format!(
                "component '{}' is registered twice",
                registration.id()
            ));
        } else {
            self.components.push(registration);
        }
        self
    }

    /// Registers a component with `shape` rather than the shape it declares — for a service that
    /// chooses at start, from its configuration, whether an entity keeps its state between calls.
    pub fn register_as<C: ComponentOf<M>, M>(self, component: C, shape: Shape) -> Service {
        let id = C::component_id();
        let mut service = self.register(component);
        if let Some(registered) = service.components.iter_mut().rev().find(|c| c.id() == id) {
            registered.set_shape(shape);
            if registered.shape() != shape {
                service.problems.push(format!(
                    "component '{id}' is registered stateful, but only an entity or a workflow keeps state"
                ));
            }
        }
        service
    }

    /// Registers an endpoint by its value: `.endpoint(CartApi)`.
    pub fn endpoint<E: Endpoint>(mut self, endpoint: E) -> Service {
        let _ = endpoint;
        let registration: Box<dyn RegisteredEndpoint> = Box::new(EndpointRegistration::<E>::new());
        if self.endpoints.iter().any(|e| e.id() == registration.id()) {
            self.problems.push(format!(
                "endpoint '{}' is registered twice",
                registration.id()
            ));
        } else if let Some(other) = self
            .endpoints
            .iter()
            .find(|e| e.prefix() == registration.prefix())
        {
            self.problems.push(format!(
                "endpoints '{}' and '{}' share the prefix '{}'",
                other.id(),
                registration.id(),
                registration.prefix()
            ));
        } else {
            self.endpoints.push(registration);
        }
        self
    }

    /// Every problem with the declaration, all at once: none means the runtime can host it.
    pub fn problems(&self) -> Vec<Problem> {
        let mut problems = self.problems.clone();
        for component in &self.components {
            problems.extend(component.problems());
            let stateful_kind = matches!(
                component.kind(),
                Kind::EventSourcedEntity | Kind::KeyValueEntity | Kind::Workflow
            );
            if component.shape() == Shape::Stateful && !stateful_kind {
                problems.push(format!(
                    "component '{}' is declared stateful, but only an entity or a workflow keeps state",
                    component.id()
                ));
            }
        }
        for endpoint in &self.endpoints {
            problems.extend(endpoint.problems());
        }
        problems
            .into_iter()
            .map(|message| Problem { message })
            .collect()
    }

    /// The service, or everything wrong with it.
    pub fn build(self) -> Result<Service, Vec<Problem>> {
        let problems = self.problems();
        if problems.is_empty() {
            Ok(self)
        } else {
            Err(problems)
        }
    }

    /// What `ankka1_discover` answers: every component, sorted by id, and which keep their state.
    pub fn discover(&self, info: &proto::SidecarInfo) -> proto::WasmSpec {
        let _ = info;
        let mut components: Vec<proto::Component> =
            self.components.iter().map(|c| c.to_component()).collect();
        components.sort_by(|a, b| a.id.cmp(&b.id));
        let mut stateful: Vec<String> = self
            .components
            .iter()
            .filter(|c| c.shape() == Shape::Stateful)
            .map(|c| c.id().to_string())
            .collect();
        stateful.sort();
        proto::WasmSpec {
            spec: Some(proto::Spec {
                protocol_version: PROTOCOL_VERSION.to_string(),
                sdk: Some(proto::SdkInfo {
                    name: self.sdk_name.clone(),
                    version: env!("CARGO_PKG_VERSION").to_string(),
                }),
                components,
                endpoints: self.endpoints.iter().map(|e| e.to_endpoint()).collect(),
            }),
            stateful,
            abi_version: ABI_VERSION.to_string(),
        }
    }

    fn component(&self, id: &str) -> Option<&dyn Registered> {
        self.components
            .iter()
            .find(|c| c.id() == id)
            .map(|c| c.as_ref())
    }

    /// `ankka1_handle`: an entity or workflow command.
    pub fn handle(&self, request: proto::HandleRequest) -> proto::HandleReply {
        let Some(component) = self.component(&request.component_id) else {
            let failure = not_found(&request.component_id);
            return proto::HandleReply {
                reply: None,
                state: request.state,
                failure: Some(failure),
            };
        };
        let mut held = self.held.borrow_mut();
        component.handle(request, held.entry(component.id().to_string()).or_default())
    }

    /// `ankka1_fold`: one event folded into an event sourced entity's state.
    pub fn fold(&self, request: proto::FoldRequest) -> proto::FoldReply {
        let Some(component) = self.component(&request.component_id) else {
            return proto::FoldReply {
                state: request.state,
                failure: Some(not_found(&request.component_id)),
            };
        };
        let mut held = self.held.borrow_mut();
        component.fold(request, held.entry(component.id().to_string()).or_default())
    }

    /// `ankka1_http`: a request the runtime's router matched to one of an endpoint's routes.
    pub fn http(&self, request: proto::HttpRequest) -> proto::HttpReply {
        match self
            .endpoints
            .iter()
            .find(|e| e.id() == request.endpoint_id)
        {
            Some(endpoint) => endpoint.handle(request),
            None => proto::HttpReply {
                message: Some(proto::http_reply::Message::Failure(proto::Failure {
                    command_id: 0,
                    error: Some(proto::Error {
                        message: format!("no endpoint '{}' in this service", request.endpoint_id),
                        code: proto::ErrorCode::NotFound as i32,
                    }),
                })),
            },
        }
    }

    /// `ankka1_run_step`: one workflow step, on an instance of its own, handed the state.
    pub fn run_step(&self, request: proto::StepRequest) -> proto::StepReply {
        match self.component(&request.component_id) {
            Some(component) => component.run_step(request),
            None => proto::StepReply {
                reply: None,
                failure: Some(not_found(&request.component_id)),
                state: request.state,
            },
        }
    }

    /// A call whose reply has no field for a fault: a component that is not there, or not of the
    /// kind the call is for, is a trap naming it, which the runtime answers as a fault.
    fn answer<T>(
        &self,
        component_id: &str,
        what: &str,
        call: impl FnOnce(&dyn Registered) -> Option<T>,
    ) -> T {
        let component = self
            .component(component_id)
            .unwrap_or_else(|| panic!("no component '{component_id}' in this service"));
        call(component).unwrap_or_else(|| panic!("component '{component_id}' does not take {what}"))
    }

    /// `ankka1_close`: a stateful instance the runtime passivated; what it kept is dropped.
    pub fn close(&self, request: proto::Passivate) {
        if let Some(held) = self.held.borrow_mut().get_mut(&request.component_id) {
            held.remove(&request.entity_id);
        }
    }

    /// Runs one export over its encoded request, answering the encoded reply.
    pub fn call(&self, export: Export, request: &[u8]) -> Vec<u8> {
        match export {
            Export::Discover => {
                let problems = self.problems();
                if !problems.is_empty() {
                    for problem in &problems {
                        log(Level::Error, &problem.message);
                    }
                    let listed: Vec<String> = problems.iter().map(|p| p.message.clone()).collect();
                    panic!(
                        "the service cannot be hosted:\n  - {}",
                        listed.join("\n  - ")
                    );
                }
                self.discover(&decode(export, request)).encode_to_vec()
            }
            Export::Handle => self.handle(decode(export, request)).encode_to_vec(),
            Export::Fold => self.fold(decode(export, request)).encode_to_vec(),
            Export::Http => self.http(decode(export, request)).encode_to_vec(),
            Export::Close => {
                self.close(decode(export, request));
                Vec::new()
            }
            Export::RunStep => self.run_step(decode(export, request)).encode_to_vec(),
            Export::View => {
                let request: proto::ViewRequest = decode(export, request);
                let id = request.component_id.clone();
                self.answer(&id, "views", |c| c.view(request))
                    .encode_to_vec()
            }
            Export::Consumer => {
                let request: proto::ConsumerRequest = decode(export, request);
                let id = request.component_id.clone();
                self.answer(&id, "messages", |c| c.consumer(request))
                    .encode_to_vec()
            }
            Export::TimedAction => {
                let request: proto::TimedActionRequest = decode(export, request);
                let id = request.component_id.clone();
                self.answer(&id, "timers", |c| c.timed_action(request))
                    .encode_to_vec()
            }
            Export::Plan => {
                let request: proto::PlanRequest = decode(export, request);
                let id = request.component_id.clone();
                self.answer(&id, "agent requests", |c| c.plan(request))
                    .encode_to_vec()
            }
            Export::InvokeTool => {
                let request: proto::ToolRequest = decode(export, request);
                let id = request.component_id.clone();
                self.answer(&id, "tools", |c| c.invoke_tool(request))
                    .encode_to_vec()
            }
            Export::CheckGuardrail => {
                let request: proto::GuardrailRequest = decode(export, request);
                let id = request.component_id.clone();
                self.answer(&id, "guardrails", |c| c.check_guardrail(request))
                    .encode_to_vec()
            }
            Export::CheckTaskResult => {
                let request: proto::TaskResultRequest = decode(export, request);
                let id = request.component_id.clone();
                self.answer(&id, "task results", |c| c.check_task_result(request))
                    .encode_to_vec()
            }
        }
    }
}

fn decode<T: Message + Default>(export: Export, request: &[u8]) -> T {
    T::decode(request).unwrap_or_else(|e| panic!("the request to {export:?} does not decode: {e}"))
}

fn not_found(component_id: &str) -> proto::Failure {
    proto::Failure {
        command_id: 0,
        error: Some(proto::Error {
            message: format!("no component '{component_id}' in this service"),
            code: proto::ErrorCode::NotFound as i32,
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::components::{EventSourcedEntity, Handlers};
    use crate::context::Context;
    use crate::effects::{self, Effect, ReadOnlyEffect};
    use crate::{Done, proto};
    use serde::{Deserialize, Serialize};

    #[derive(Serialize, Deserialize, Default)]
    struct Count {
        n: i32,
    }

    #[derive(Serialize, Deserialize)]
    #[serde(tag = "type")]
    enum Counted {
        Added { by: i32 },
    }

    struct Counter;

    impl Counter {
        fn add(_: &Count, by: i32, _: &Context) -> Effect<Counted, Done> {
            effects::persist(Counted::Added { by }).then_reply_value(Done)
        }

        fn get(count: &Count, _: (), _: &Context) -> ReadOnlyEffect<i32> {
            effects::reply(count.n)
        }
    }

    impl EventSourcedEntity for Counter {
        type State = Count;
        type Event = Counted;
        const COMPONENT_ID: &'static str = "counter";
        const SHAPE: Shape = Shape::Stateful;

        fn empty_state(_: &str) -> Count {
            Count::default()
        }

        fn apply(count: Count, event: &Counted) -> Count {
            match event {
                Counted::Added { by } => Count { n: count.n + by },
            }
        }

        fn handlers() -> Handlers<Counter> {
            Handlers::new()
                .command("add", Counter::add)
                .query("get", Counter::get)
        }

        fn snapshot_every() -> u32 {
            50
        }
    }

    struct Twice;

    impl EventSourcedEntity for Twice {
        type State = Count;
        type Event = Counted;
        const COMPONENT_ID: &'static str = "counter";

        fn empty_state(_: &str) -> Count {
            Count::default()
        }

        fn apply(count: Count, _: &Counted) -> Count {
            count
        }

        fn handlers() -> Handlers<Twice> {
            Handlers::new()
                .query("get", |c: &Count, _: (), _: &Context| effects::reply(c.n))
                .query("get", |c: &Count, _: (), _: &Context| effects::reply(c.n))
        }
    }

    #[test]
    fn discovery_describes_every_component_and_its_shape() {
        let service = Service::new("ankka-rust").register(Counter);
        let spec = service.discover(&proto::SidecarInfo::default());
        assert_eq!(spec.abi_version, "1");
        assert_eq!(spec.stateful, vec!["counter".to_string()]);
        let inner = spec.spec.unwrap();
        assert_eq!(inner.protocol_version, PROTOCOL_VERSION);
        assert_eq!(inner.sdk.unwrap().name, "ankka-rust");
        let component = &inner.components[0];
        assert_eq!(component.id, "counter");
        let handlers: Vec<(&str, bool)> = component
            .handlers
            .iter()
            .map(|h| (h.name.as_str(), h.read_only))
            .collect();
        assert_eq!(handlers, vec![("add", false), ("get", true)]);
        assert_eq!(
            component.detail,
            Some(proto::component::Detail::EventSourced(
                proto::EventSourcedDetail { snapshot_every: 50 }
            ))
        );
    }

    #[test]
    fn every_problem_is_reported_at_once() {
        let problems = Service::new("ankka-rust")
            .register(Twice)
            .register(Counter)
            .build()
            .err()
            .unwrap();
        let messages: Vec<&str> = problems.iter().map(|p| p.message.as_str()).collect();
        assert_eq!(messages.len(), 2, "{messages:?}");
        assert!(
            messages.iter().any(|m| m.contains("registered twice")),
            "{messages:?}"
        );
        assert!(
            messages
                .iter()
                .any(|m| m.contains("declares handler 'get' twice")),
            "{messages:?}"
        );
    }

    #[test]
    fn a_handler_decodes_its_input_and_encodes_its_reply() {
        let handlers = Counter::handlers();
        let ctx = Context::new("counter", "c1", 0, Default::default());
        let input = crate::codec::encode_payload(&5_i32).unwrap();
        let effect = (handlers.find("add").unwrap().run)(&Count::default(), &input, &ctx).unwrap();
        let m = effects::materialise_event_sourced(Count::default(), Counter::apply, effect);
        assert_eq!(m.new_state.n, 5);
        match m.outcome {
            effects::Answer::Reply(Ok(payload), _) => assert_eq!(payload.manifest, "done"),
            other => panic!("{other:?}"),
        }
        let refused =
            (handlers.find("add").unwrap().run)(&Count::default(), &Default::default(), &ctx);
        assert!(refused.is_err(), "an empty payload is not an int");
    }

    #[test]
    fn a_request_for_a_component_that_is_not_there_is_a_fault_naming_it() {
        let service = Service::new("ankka-rust").register(Counter);
        let reply = service.handle(proto::HandleRequest {
            component_id: "nope".into(),
            ..Default::default()
        });
        let error = reply.failure.unwrap().error.unwrap();
        assert_eq!(error.code, proto::ErrorCode::NotFound as i32);
        assert!(error.message.contains("nope"));
    }

    fn add(id: i64, by: i32, state: Option<proto::Payload>) -> proto::HandleRequest {
        proto::HandleRequest {
            kind: Kind::EventSourcedEntity as i32,
            component_id: "counter".into(),
            entity_id: "c1".into(),
            state,
            command: Some(proto::handle_request::Command::EventSourced(
                proto::event_sourced_in::Command {
                    id,
                    name: "add".into(),
                    payload: Some(crate::codec::encode_payload(&by).unwrap()),
                    metadata: None,
                    snapshot_requested: id == 2,
                },
            )),
        }
    }

    fn state_of(reply: &proto::HandleReply) -> String {
        String::from_utf8(reply.state.clone().unwrap().data).unwrap()
    }

    fn es(reply: &proto::HandleReply) -> &proto::event_sourced_out::Reply {
        match &reply.reply {
            Some(proto::handle_reply::Reply::EventSourced(r)) => r,
            other => panic!("{other:?} {:?}", reply.failure),
        }
    }

    #[test]
    fn a_command_is_handled_from_the_state_it_is_sent_and_answers_the_state_after_it() {
        struct Stateless;
        impl EventSourcedEntity for Stateless {
            type State = Count;
            type Event = Counted;
            const COMPONENT_ID: &'static str = "counter";
            const EVENT_MANIFEST: Option<&'static str> = Some("counted");
            fn empty_state(_: &str) -> Count {
                Count::default()
            }
            fn apply(count: Count, event: &Counted) -> Count {
                Counter::apply(count, event)
            }
            fn handlers() -> Handlers<Stateless> {
                Handlers::new().command("add", |c: &Count, by: i32, ctx: &Context| {
                    Counter::add(c, by, ctx)
                })
            }
        }
        let service = Service::new("ankka-rust").register(Stateless);
        let first = service.handle(add(1, 2, None));
        let reply = es(&first);
        assert_eq!(reply.command_id, 1);
        assert_eq!(reply.events[0].manifest, "counted");
        assert_eq!(reply.events[0].data, br#"{"type":"Added","by":2}"#);
        assert_eq!(state_of(&first), r#"{"n":2}"#);
        // Stateless: nothing kept, so the state must be sent again.
        let second = service.handle(add(2, 3, first.state.clone()));
        assert_eq!(state_of(&second), r#"{"n":5}"#);
        assert_eq!(es(&second).snapshot.as_ref().unwrap().data, br#"{"n":5}"#);
        let fresh = service.handle(add(3, 3, None));
        assert_eq!(state_of(&fresh), r#"{"n":3}"#);
    }

    #[test]
    fn a_stateful_entity_keeps_its_state_until_it_is_closed() {
        let service = Service::new("ankka-rust").register(Counter);
        let first = service.handle(add(1, 2, None));
        assert_eq!(state_of(&first), r#"{"n":2}"#);
        let kept = service.handle(add(3, 3, None));
        assert_eq!(
            state_of(&kept),
            r#"{"n":5}"#,
            "the second call is not sent the state"
        );
        service.close(proto::Passivate {
            component_id: "counter".into(),
            entity_id: "c1".into(),
        });
        let reopened = service.handle(add(4, 1, None));
        assert_eq!(state_of(&reopened), r#"{"n":1}"#);
    }

    #[test]
    fn an_unknown_handler_is_a_refusal_and_a_bad_input_a_fault() {
        let service = Service::new("ankka-rust").register(Counter);
        let mut request = add(1, 2, None);
        if let Some(proto::handle_request::Command::EventSourced(c)) = request.command.as_mut() {
            c.name = "nope".into();
        }
        let refused = service.handle(request);
        match es(&refused).outcome.clone().unwrap().outcome {
            Some(proto::outcome::Outcome::Error(e)) => {
                assert_eq!(e.code, proto::ErrorCode::NotFound as i32)
            }
            other => panic!("{other:?}"),
        }
        let mut bad = add(1, 2, None);
        if let Some(proto::handle_request::Command::EventSourced(c)) = bad.command.as_mut() {
            c.payload = Some(proto::Payload::default());
        }
        assert!(service.handle(bad).failure.is_some());
    }

    #[test]
    fn a_fault_leaves_a_stateful_entity_with_the_state_it_had() {
        let service = Service::new("ankka-rust").register(Counter);
        assert_eq!(state_of(&service.handle(add(1, 2, None))), r#"{"n":2}"#);
        let mut bad = add(3, 0, None);
        if let Some(proto::handle_request::Command::EventSourced(c)) = bad.command.as_mut() {
            c.payload = Some(proto::Payload::default());
        }
        assert!(service.handle(bad).failure.is_some());
        // The runtime does not send the state after a fault: the entity must still have it.
        assert_eq!(state_of(&service.handle(add(4, 3, None))), r#"{"n":5}"#);
    }

    #[test]
    fn replay_folds_one_event_at_a_time() {
        let service = Service::new("ankka-rust").register(Counter);
        let event = crate::codec::encode_payload(&Counted::Added { by: 4 }).unwrap();
        let reply = service.fold(proto::FoldRequest {
            component_id: "counter".into(),
            entity_id: "c9".into(),
            state: None,
            event: Some(event),
            sequence: 1,
        });
        assert_eq!(reply.state.unwrap().data, br#"{"n":4}"#);
        assert!(reply.failure.is_none());
    }
}
