//! What discovery says about a topic source's contract, broker and parallel reading, and a
//! consumer's publication (protocol 1.14).

use ankka::prelude::*;
use ankka::proto;

#[derive(Serialize, Deserialize)]
struct Event {}

fn orders() -> Contract {
    Contract::from_bytes(br#"{"type": "object"}"#, "order.v1").expect("a contract")
}

struct Relay;

impl Consumer for Relay {
    type Message = Event;
    const COMPONENT_ID: &'static str = "relay";

    fn source() -> Source {
        Source::topic("events")
            .contract(orders())
            .broker("legacy")
            .parallel()
    }

    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Earliest)
    }

    fn produces_to() -> Option<&'static str> {
        Some("enriched")
    }

    fn produces() -> Option<Publication> {
        Some(
            Publication::to("enriched")
                .contract(orders())
                .broker("legacy"),
        )
    }

    fn on_message(_: Event, _: &Context) -> ConsumerEffect {
        ConsumerEffect::Done
    }
}

struct Plain;

impl Consumer for Plain {
    type Message = Event;
    const COMPONENT_ID: &'static str = "plain";

    fn source() -> Source {
        Source::topic("events")
    }

    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Earliest)
    }

    fn produces_to() -> Option<&'static str> {
        Some("enriched")
    }

    fn on_message(_: Event, _: &Context) -> ConsumerEffect {
        ConsumerEffect::Done
    }
}

struct Disagreeing;

impl Consumer for Disagreeing {
    type Message = Event;
    const COMPONENT_ID: &'static str = "disagreeing";

    fn source() -> Source {
        Source::topic("events")
    }

    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Earliest)
    }

    fn produces_to() -> Option<&'static str> {
        Some("one")
    }

    fn produces() -> Option<Publication> {
        Some(Publication::to("another"))
    }

    fn on_message(_: Event, _: &Context) -> ConsumerEffect {
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

fn consumer_detail(spec: &proto::Spec, id: &str) -> proto::ConsumerDetail {
    let component = spec.components.iter().find(|c| c.id == id).expect(id);
    match &component.detail {
        Some(proto::component::Detail::Consumer(d)) => d.clone(),
        other => panic!("{id} is not a consumer: {other:?}"),
    }
}

#[test]
fn discovery_carries_the_contract_the_broker_and_parallel() {
    let spec = discover(Service::new("test").register(Relay), "1.14");
    assert_eq!(spec.protocol_version, "1.14");
    let relay = consumer_detail(&spec, "relay");
    let source = relay.source.expect("a source");
    assert_eq!(
        source.contract.as_ref().map(|c| c.name.as_str()),
        Some("order.v1")
    );
    assert_eq!(
        source.contract.as_ref().map(|c| c.fingerprint.clone()),
        Some(orders().fingerprint)
    );
    assert_eq!(source.broker.as_deref(), Some("legacy"));
    assert_eq!(source.parallel, Some(true));
    assert_eq!(relay.produces_to.as_deref(), Some("enriched"));
    let produces = relay.produces.expect("a publication");
    assert_eq!(produces.topic, "enriched");
    assert_eq!(
        produces.contract.map(|c| c.name),
        Some("order.v1".to_string())
    );
    assert_eq!(produces.broker.as_deref(), Some("legacy"));
}

#[test]
fn a_plain_consumer_states_nothing_new() {
    let spec = discover(Service::new("test").register(Plain), "1.14");
    let plain = consumer_detail(&spec, "plain");
    let source = plain.source.expect("a source");
    assert_eq!(source.contract, None);
    assert_eq!(source.broker, None);
    assert_eq!(source.parallel, None);
    assert_eq!(plain.produces_to.as_deref(), Some("enriched"));
    let produces = plain.produces.expect("the topic alone");
    assert_eq!(
        (produces.topic.as_str(), produces.contract, produces.broker),
        ("enriched", None, None)
    );
}

#[test]
fn produces_to_and_produces_naming_different_topics_is_refused() {
    let problems = Service::new("test")
        .register(Disagreeing)
        .build()
        .err()
        .expect("refused");
    assert!(
        problems.iter().any(|p| p
            .to_string()
            .contains("names 'one' in produces_to and 'another' in produces")),
        "{problems:?}"
    );
}

#[test]
#[should_panic(expected = "Run a runtime speaking 1.14 or later")]
fn a_runtime_before_1_14_is_refused_when_a_contract_is_stated() {
    // A module is refused to start rather than served wrong: the host would ignore the contract.
    let _ = discover(Service::new("test").register(Relay), "1.13");
}
