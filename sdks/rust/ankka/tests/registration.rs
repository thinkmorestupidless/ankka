//! What `Service::build` refuses: every problem with a declaration, reported together, so a
//! service with four mistakes takes one run to fix.

use ankka::prelude::*;

#[derive(Serialize, Deserialize, Default)]
struct Nothing {}

#[derive(Serialize, Deserialize)]
#[serde(tag = "type")]
enum NoEvent {
    Happened {},
}

struct Twice;

impl Twice {
    fn get(_: &Nothing, _: (), _: &Context) -> ReadOnlyEffect<Done> {
        effects::reply(Done)
    }
}

impl EventSourcedEntity for Twice {
    type State = Nothing;
    type Event = NoEvent;
    const COMPONENT_ID: &'static str = "twice";

    fn empty_state(_: &str) -> Nothing {
        Nothing {}
    }

    fn apply(state: Nothing, _: &NoEvent) -> Nothing {
        state
    }

    fn handlers() -> Handlers<Twice> {
        // The same wire name twice: which one a request meant would be a guess.
        Handlers::new()
            .query("get", Twice::get)
            .query("get", Twice::get)
    }
}

struct Open;

impl Endpoint for Open {
    const ENDPOINT_ID: &'static str = "Open";
    const PREFIX: &'static str = "/things";

    fn acl() -> Acl {
        // Named callers, and none named: nobody could call it, which is never what was meant.
        Acl::Callers(Vec::new())
    }

    fn routes() -> Routes<Open> {
        Routes::new().get("/", |_: &Request| Ok(Done))
    }
}

struct SamePrefix;

impl Endpoint for SamePrefix {
    const ENDPOINT_ID: &'static str = "SamePrefix";
    const PREFIX: &'static str = "/things";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<SamePrefix> {
        Routes::new()
    }
}

#[test]
fn every_problem_is_reported_together() {
    let problems = Service::new("test")
        .register(Twice)
        .register(Twice)
        .endpoint(Open)
        .endpoint(SamePrefix)
        .build()
        .err()
        .expect("refused");
    let messages: Vec<String> = problems.iter().map(|p| p.message.clone()).collect();
    for expected in [
        "component 'twice' is registered twice",
        "declares handler 'get' twice",
        "share the prefix '/things'",
        "must name at least one",
    ] {
        assert!(
            messages.iter().any(|m| m.contains(expected)),
            "{expected:?} not in {messages:?}"
        );
    }
}

#[test]
fn a_sound_declaration_builds() {
    struct Once;
    impl EventSourcedEntity for Once {
        type State = Nothing;
        type Event = NoEvent;
        const COMPONENT_ID: &'static str = "once";
        fn empty_state(_: &str) -> Nothing {
            Nothing {}
        }
        fn apply(state: Nothing, _: &NoEvent) -> Nothing {
            state
        }
        fn handlers() -> Handlers<Once> {
            Handlers::new()
        }
    }
    assert!(Service::new("test").register(Once).build().is_ok());
}

// ── What a view or consumer declares about the topic it reads ──

#[derive(Serialize, Deserialize, Clone)]
struct Message {
    n: i32,
}

struct Notifier;
impl Consumer for Notifier {
    type Message = Message;
    const COMPONENT_ID: &'static str = "notifier";
    fn source() -> Source {
        Source::topic("orders")
    }
    fn on_message(_: Message, _: &Context) -> ConsumerEffect {
        effects::consumer::done()
    }
}

struct LatestNotifier;
impl Consumer for LatestNotifier {
    type Message = Message;
    const COMPONENT_ID: &'static str = "latest-notifier";
    fn source() -> Source {
        Source::topic("orders")
    }
    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Latest)
    }
    fn on_message(_: Message, _: &Context) -> ConsumerEffect {
        effects::consumer::done()
    }
}

struct VersionZero;
impl View for VersionZero {
    type Row = Message;
    type Event = Message;
    const COMPONENT_ID: &'static str = "version-zero";
    fn source() -> Source {
        Source::topic("orders")
    }
    fn version() -> Option<u32> {
        Some(0)
    }
    fn on_event(_: Option<Message>, event: Message, _: &Context) -> ViewEffect<Message> {
        ViewEffect::UpdateRow(event)
    }
}

struct VersionedOverEntity;
impl View for VersionedOverEntity {
    type Row = Message;
    type Event = NoEvent;
    const COMPONENT_ID: &'static str = "versioned-over-entity";
    fn source() -> Source {
        Source::Component(ankka::proto::Kind::EventSourcedEntity, "twice")
    }
    fn version() -> Option<u32> {
        Some(2)
    }
    fn on_event(row: Option<Message>, _: NoEvent, _: &Context) -> ViewEffect<Message> {
        row.map_or(ViewEffect::Ignore, ViewEffect::UpdateRow)
    }
}

fn messages(service: Service) -> Vec<String> {
    service
        .build()
        .err()
        .map(|problems| problems.into_iter().map(|p| p.message).collect())
        .unwrap_or_default()
}

#[test]
fn a_consumer_reading_a_topic_must_declare_its_start_position() {
    assert_eq!(
        messages(Service::new("test").register(Notifier)),
        vec![
            "consumer 'notifier' reads topic 'orders' and declares no start position; declare \
             StartFrom::Earliest, StartFrom::Latest or a time in start_from()"
                .to_string()
        ]
    );
    assert!(messages(Service::new("test").register(LatestNotifier)).is_empty());
}

#[test]
fn a_version_of_zero_or_on_a_component_that_reads_an_entity_is_refused() {
    let found = messages(
        Service::new("test")
            .register(VersionZero)
            .register(VersionedOverEntity),
    );
    assert!(
        found
            .iter()
            .any(|m| m.contains("view 'version-zero' declares version 0")),
        "{found:?}"
    );
    assert!(
        found.iter().any(|m| m
            .contains("view 'versioned-over-entity' declares a version, which applies to a topic")),
        "{found:?}"
    );
}

#[test]
fn a_start_position_is_written_into_discovery() {
    let spec = Service::new("test")
        .register(LatestNotifier)
        .discover(&ankka::proto::SidecarInfo {
            protocol_version: "1.4".to_string(),
            runtime_version: String::new(),
        })
        .spec
        .expect("a spec");
    let Some(ankka::proto::component::Detail::Consumer(detail)) = &spec.components[0].detail else {
        panic!("a consumer");
    };
    let start = detail
        .source
        .as_ref()
        .and_then(|s| s.start_from.as_ref())
        .and_then(|s| s.position);
    assert_eq!(
        start,
        Some(ankka::proto::start_from::Position::Named(
            ankka::proto::start_from::Named::Latest as i32
        ))
    );
}

#[test]
#[should_panic(expected = "latest-notifier declare where a topic source starts or its version")]
fn a_runtime_too_old_for_start_positions_is_refused_naming_what_declares_one() {
    let _ = Service::new("test")
        .register(LatestNotifier)
        .discover(&ankka::proto::SidecarInfo {
            protocol_version: "1.3".to_string(),
            runtime_version: String::new(),
        });
}
