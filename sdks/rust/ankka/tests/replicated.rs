//! A replicated entity in Rust (protocol 1.19): the declaration, the effects, the contexts over the
//! module's exports, a follower in the unit kit, and the refusal of a runtime from before
//! replication.

use ankka::codec::Codec;
use ankka::prelude::*;
use ankka::proto;
use ankka::testkit::{EventSourcedTestKit, ReplicationMode};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
struct Vehicle {
    steps: Vec<String>,
    written_at: Vec<String>,
    local: Vec<bool>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type")]
enum VehicleEvent {
    StepAdded { step: String },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Placement {
    installation: String,
    home: bool,
}

struct VehicleEntity;

impl VehicleEntity {
    fn add_step(_: &Vehicle, step: String, ctx: &Context) -> Effect<VehicleEvent, Placement> {
        let placement = Placement {
            installation: ctx.installation().unwrap_or_default().to_string(),
            home: ctx.is_home(),
        };
        effects::persist(VehicleEvent::StepAdded { step }).then_reply_value(placement)
    }

    fn hand_over(_: &Vehicle, target: String, _: &Context) -> Effect<VehicleEvent, String> {
        effects::hand_over_to(target.clone()).then_reply_value(target)
    }

    fn allot(_: &Vehicle, target: String, _: &Context) -> Effect<VehicleEvent, String> {
        effects::persist(VehicleEvent::StepAdded {
            step: "allotted".to_string(),
        })
        .replicate_to(target.clone())
        .stop_replicating_to("factory")
        .then_reply_value(target)
    }

    fn get(vehicle: &Vehicle, _: (), _: &Context) -> ReadOnlyEffect<Vehicle> {
        effects::reply(vehicle.clone())
    }
}

impl EventSourcedEntity for VehicleEntity {
    type State = Vehicle;
    type Event = VehicleEvent;
    const COMPONENT_ID: &'static str = "vehicle";
    const REPLICATED: bool = true;

    fn empty_state(_: &str) -> Vehicle {
        Vehicle::default()
    }

    fn apply(state: Vehicle, event: &VehicleEvent) -> Vehicle {
        Self::apply_at(state, event, &EventContext::new(None, true, 0))
    }

    fn apply_at(mut state: Vehicle, event: &VehicleEvent, ctx: &EventContext) -> Vehicle {
        let VehicleEvent::StepAdded { step } = event;
        state.steps.push(step.clone());
        state
            .written_at
            .push(ctx.installation().unwrap_or_default().to_string());
        state.local.push(ctx.is_local());
        state
    }

    fn handlers() -> Handlers<Self> {
        Handlers::new()
            .command("add-step", Self::add_step)
            .command("hand-over", Self::hand_over)
            .command("allot", Self::allot)
            .query("get", Self::get)
    }
}

/// The same entity, not declared replicated.
struct PlainVehicle;

impl EventSourcedEntity for PlainVehicle {
    type State = Vehicle;
    type Event = VehicleEvent;
    const COMPONENT_ID: &'static str = "plain-vehicle";

    fn empty_state(_: &str) -> Vehicle {
        Vehicle::default()
    }

    fn apply(state: Vehicle, event: &VehicleEvent) -> Vehicle {
        VehicleEntity::apply(state, event)
    }

    fn handlers() -> Handlers<Self> {
        Handlers::new().command("hand-over", |_: &Vehicle, target: String, _: &Context| {
            effects::hand_over_to::<VehicleEvent>(target.clone()).then_reply_value(target)
        })
    }
}

/// A consumer publishing every vehicle's steps, at every installation that holds a copy.
struct StepFeed;

impl Consumer for StepFeed {
    type Message = VehicleEvent;
    const COMPONENT_ID: &'static str = "step-feed";

    fn source() -> Source {
        Source::of(VehicleEntity)
    }

    fn produces_to() -> Option<&'static str> {
        Some("steps")
    }

    fn mirrored_changes() -> bool {
        true
    }

    fn on_message(_: VehicleEvent, _: &Context) -> ConsumerEffect {
        ConsumerEffect::Done
    }
}

fn discover(service: Service, protocol: &str) -> proto::Spec {
    service
        .discover(&proto::SidecarInfo {
            protocol_version: protocol.to_string(),
            runtime_version: String::new(),
        })
        .spec
        .expect("a spec")
}

fn home() -> EventSourcedTestKit<VehicleEntity> {
    EventSourcedTestKit::<VehicleEntity>::new("v1").in_mode(ReplicationMode::Home {
        installation: "leipzig".to_string(),
    })
}

#[test]
fn an_entity_declared_replicated_says_so_in_discovery() {
    let spec = discover(Service::new("test").register(VehicleEntity), "1.19");
    let vehicle = spec
        .components
        .iter()
        .find(|c| c.id == "vehicle")
        .expect("the vehicle");
    let Some(proto::component::Detail::EventSourced(detail)) = &vehicle.detail else {
        panic!("not an event sourced entity");
    };
    assert!(detail.replicated);
}

#[test]
#[should_panic(expected = "Run a runtime speaking 1.19 or later")]
fn a_runtime_from_before_replication_is_refused_naming_the_protocol_version() {
    let _ = discover(Service::new("test").register(VehicleEntity), "1.18");
}

#[test]
fn a_consumer_given_mirrored_changes_says_so_and_needs_1_19() {
    let spec = discover(
        Service::new("test")
            .register(VehicleEntity)
            .register(StepFeed),
        "1.19",
    );
    let feed = spec
        .components
        .iter()
        .find(|c| c.id == "step-feed")
        .expect("the feed");
    let Some(proto::component::Detail::Consumer(detail)) = &feed.detail else {
        panic!("not a consumer");
    };
    assert!(detail.mirrored_changes);
    let refused =
        std::panic::catch_unwind(|| discover(Service::new("test").register(StepFeed), "1.18"));
    assert!(
        refused.is_err(),
        "a 1.18 runtime would publish every change at every installation"
    );
}

#[test]
fn a_change_says_where_it_was_written() {
    let here = Context::new("step-feed", "", 0, Metadata::default());
    assert_eq!(here.written_at(), None);
    assert!(here.local_origin());
    let mirrored = Context::new(
        "step-feed",
        "",
        0,
        Metadata::default()
            .set("ankka.installation", "leipzig")
            .set("ankka.local", "false"),
    );
    assert_eq!(mirrored.written_at(), Some("leipzig"));
    assert!(!mirrored.local_origin());
}

#[test]
fn an_entity_not_replicated_is_answered_by_an_older_runtime() {
    let _ = discover(Service::new("test").register(PlainVehicle), "1.18");
}

#[test]
fn the_effects_are_carried_beside_the_events() {
    let mut kit = home();
    let handed = kit.command("hand-over", "vienna".to_string());
    assert_eq!(
        handed.replication,
        Some(ReplicationChange::HandOver {
            to: "vienna".to_string()
        })
    );
    let allotted = kit.command("allot", "vienna".to_string());
    assert_eq!(
        allotted.events,
        vec![VehicleEvent::StepAdded {
            step: "allotted".to_string()
        }]
    );
    assert_eq!(
        allotted.replication,
        Some(ReplicationChange::Set {
            add: vec!["vienna".to_string()],
            remove: vec!["factory".to_string()],
        })
    );
}

#[test]
fn at_the_home_a_command_is_told_it_is_at_the_home_and_its_events_are_local() {
    let mut kit = home();
    let outcome = kit.command("add-step", "frame".to_string());
    assert_eq!(
        outcome.reply::<Placement>(),
        Ok(Placement {
            installation: "leipzig".to_string(),
            home: true,
        })
    );
    assert_eq!(kit.state().written_at, vec!["leipzig"]);
    assert_eq!(kit.state().local, vec![true]);
}

#[test]
fn a_follower_refuses_a_persisting_command_naming_the_home_and_answers_a_query() {
    let mut kit =
        EventSourcedTestKit::<VehicleEntity>::new("v1").in_mode(ReplicationMode::Follower {
            home: "leipzig".to_string(),
            installation: "bratislava".to_string(),
        });
    kit.replay(
        vec![VehicleEvent::StepAdded {
            step: "frame".to_string(),
        }],
        Some("leipzig"),
    );
    let refused = kit.command("add-step", "wheels".to_string());
    let error = refused.error().expect("refused");
    assert_eq!(error.code, ErrorCode::NotHome);
    assert!(error.message.contains("leipzig"), "{}", error.message);
    assert!(!refused.persisted());
    assert_eq!(
        kit.command("get", ()).reply::<Vehicle>(),
        Ok(Vehicle {
            steps: vec!["frame".to_string()],
            written_at: vec!["leipzig".to_string()],
            local: vec![false],
        })
    );
}

#[test]
fn an_entity_not_replicated_in_the_kit_refuses_a_replication_change() {
    let mut kit = EventSourcedTestKit::<VehicleEntity>::new("v1");
    let refused = kit.command("hand-over", "vienna".to_string());
    assert_eq!(refused.error().map(|e| e.code), Some(ErrorCode::BadRequest));
}

#[test]
fn an_entity_not_declared_replicated_cannot_ask_for_a_handover() {
    let service = Service::new("test").register(PlainVehicle);
    let reply = service.handle(proto::HandleRequest {
        kind: proto::Kind::EventSourcedEntity as i32,
        component_id: "plain-vehicle".to_string(),
        entity_id: "v1".to_string(),
        state: None,
        command: Some(proto::handle_request::Command::EventSourced(
            proto::event_sourced_in::Command {
                id: 1,
                name: "hand-over".to_string(),
                payload: Some(
                    ankka::codec::encode_payload(&"vienna".to_string()).expect("encodes"),
                ),
                ..Default::default()
            },
        )),
    });
    let failure = reply.failure.expect("a fault");
    assert!(
        failure
            .error
            .expect("an error")
            .message
            .contains("is not replicated"),
    );
}

#[test]
fn over_the_exports_a_replayed_event_is_told_where_it_was_written() {
    let service = Service::new("test").register(VehicleEntity);
    let event = VehicleEntity::event_codec()
        .payload(&VehicleEvent::StepAdded {
            step: "frame".to_string(),
        })
        .expect("encodes");
    let folded = service.fold(proto::FoldRequest {
        component_id: "vehicle".to_string(),
        entity_id: "v1".to_string(),
        state: None,
        event: Some(event),
        sequence: 1,
        installation: "leipzig".to_string(),
        mirrored: true,
    });
    assert!(folded.failure.is_none());
    let state: Vehicle = VehicleEntity::state_codec()
        .decode_value(&folded.state.expect("a state").data)
        .expect("decodes");
    assert_eq!(state.written_at, vec!["leipzig"]);
    assert_eq!(state.local, vec![false]);
}

#[test]
fn over_the_exports_a_handover_is_sent_on_the_reply() {
    let service = Service::new("test").register(VehicleEntity);
    let reply = service.handle(proto::HandleRequest {
        kind: proto::Kind::EventSourcedEntity as i32,
        component_id: "vehicle".to_string(),
        entity_id: "v1".to_string(),
        state: None,
        command: Some(proto::handle_request::Command::EventSourced(
            proto::event_sourced_in::Command {
                id: 1,
                name: "hand-over".to_string(),
                payload: Some(
                    ankka::codec::encode_payload(&"vienna".to_string()).expect("encodes"),
                ),
                installation: "leipzig".to_string(),
                at_home: true,
                ..Default::default()
            },
        )),
    });
    let Some(proto::handle_reply::Reply::EventSourced(out)) = reply.reply else {
        panic!("no event sourced reply: {:?}", reply.failure);
    };
    assert_eq!(
        out.replication.and_then(|r| r.change),
        Some(proto::replication_change::Change::HandOverTo(
            "vienna".to_string()
        ))
    );
}
