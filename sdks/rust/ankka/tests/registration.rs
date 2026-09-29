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
