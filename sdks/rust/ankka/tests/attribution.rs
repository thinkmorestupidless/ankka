//! Who acted, and where a command was forwarded from (protocol 1.22): read off the command into
//! the handler's context, and into the event context of each event the command persists.

use ankka::prelude::*;
use ankka::proto;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
struct Inspections {
    acted_by: Vec<Option<String>>,
    forwarded_from: Vec<Option<String>>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Inspected {
    note: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Seen {
    attribution: Option<String>,
    forwarded_from: Option<String>,
}

struct Vehicle;

impl Vehicle {
    fn inspect(_: &Inspections, note: String, ctx: &Context) -> Effect<Inspected, Seen> {
        let seen = Seen {
            attribution: ctx.attribution().map(str::to_string),
            forwarded_from: ctx.forwarded_from().map(str::to_string),
        };
        effects::persist(Inspected { note }).then_reply_value(seen)
    }
}

impl EventSourcedEntity for Vehicle {
    type State = Inspections;
    type Event = Inspected;
    const COMPONENT_ID: &'static str = "vehicle";

    fn empty_state(_: &str) -> Inspections {
        Inspections::default()
    }

    fn apply(state: Inspections, event: &Inspected) -> Inspections {
        Self::apply_at(state, event, &EventContext::new(None, true, 0))
    }

    fn apply_at(mut state: Inspections, _: &Inspected, ctx: &EventContext) -> Inspections {
        state.acted_by.push(ctx.attribution().map(str::to_string));
        state
            .forwarded_from
            .push(ctx.forwarded_from().map(str::to_string));
        state
    }

    fn handlers() -> Handlers<Self> {
        Handlers::new().command("inspect", Self::inspect)
    }
}

fn inspect(attribution: &str, forwarded_from: &str) -> (Seen, Inspections) {
    let service = Service::new("test").register(Vehicle);
    let reply = service.handle(proto::HandleRequest {
        kind: proto::Kind::EventSourcedEntity as i32,
        component_id: "vehicle".to_string(),
        entity_id: "v1".to_string(),
        state: None,
        command: Some(proto::handle_request::Command::EventSourced(
            proto::event_sourced_in::Command {
                id: 1,
                name: "inspect".to_string(),
                payload: Some(
                    ankka::codec::encode_payload(&"brakes".to_string()).expect("encodes"),
                ),
                attribution: attribution.to_string(),
                forwarded_from: forwarded_from.to_string(),
                ..Default::default()
            },
        )),
    });
    let state: Inspections = Vehicle::state_codec()
        .decode_value(&reply.state.clone().expect("a state").data)
        .expect("decodes");
    let Some(proto::handle_reply::Reply::EventSourced(out)) = reply.reply else {
        panic!("no event sourced reply: {:?}", reply.failure);
    };
    let Some(proto::outcome::Outcome::Reply(answer)) = out.outcome.and_then(|o| o.outcome) else {
        panic!("no reply");
    };
    let seen: Seen =
        ankka::codec::decode_payload(&answer.payload.expect("a payload")).expect("decodes");
    (seen, state)
}

#[test]
fn the_protocol_version_is_1_22() {
    assert_eq!(ankka::service::PROTOCOL_VERSION, "1.22");
}

#[test]
fn a_command_tells_its_handler_and_its_events_who_acted_and_where_it_was_forwarded_from() {
    let (seen, state) = inspect("ada", "bratislava");
    let ada = Some("ada".to_string());
    let bratislava = Some("bratislava".to_string());
    assert_eq!(
        seen,
        Seen {
            attribution: ada.clone(),
            forwarded_from: bratislava.clone()
        }
    );
    assert_eq!(state.acted_by, vec![ada]);
    assert_eq!(state.forwarded_from, vec![bratislava]);
}

#[test]
fn a_command_nobody_attributed_has_neither() {
    let (seen, state) = inspect("", "");
    assert_eq!(
        seen,
        Seen {
            attribution: None,
            forwarded_from: None
        }
    );
    assert_eq!(state.acted_by, vec![None]);
}
