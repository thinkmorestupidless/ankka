//! The unit testkits, on a counter entity and the endpoint in front of it: what a command did,
//! what the state became, what a refusal looks like, and an endpoint answering from its entities
//! in memory — and on two consumers of the counter: what one published, under which keys, and the
//! elements of the graph the other publishes.

use ankka::effects::consumer;
use ankka::graph::{ElementKind, Value};
use ankka::prelude::*;
use ankka::testkit::{ConsumerTestKit, EndpointTestKit, EventSourcedTestKit, GraphConsumerTestKit};

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
struct Tally {
    name: String,
    total: i64,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
#[serde(tag = "type")]
enum TallyEvent {
    Added { amount: i64 },
    Reset {},
}

struct Counter;

impl Counter {
    fn add(_: &Tally, amount: i64, _: &Context) -> Effect<TallyEvent, i64> {
        if amount <= 0 {
            return effects::error(ErrorCode::BadRequest, "add something").into();
        }
        effects::persist(TallyEvent::Added { amount }).then_reply(|t: &Tally| t.total)
    }

    fn reset(_: &Tally, _: (), _: &Context) -> Effect<TallyEvent, Done> {
        effects::persist(TallyEvent::Reset {})
            .delete_entity()
            .then_reply_value(Done)
    }

    fn get(tally: &Tally, _: (), _: &Context) -> ReadOnlyEffect<Tally> {
        effects::reply(tally.clone())
    }
}

impl EventSourcedEntity for Counter {
    type State = Tally;
    type Event = TallyEvent;
    const COMPONENT_ID: &'static str = "counter";

    fn empty_state(name: &str) -> Tally {
        Tally {
            name: name.to_string(),
            total: 0,
        }
    }

    fn apply(tally: Tally, event: &TallyEvent) -> Tally {
        match event {
            TallyEvent::Added { amount } => Tally {
                total: tally.total + amount,
                ..tally
            },
            TallyEvent::Reset {} => Tally { total: 0, ..tally },
        }
    }

    fn handlers() -> Handlers<Counter> {
        Handlers::new()
            .command("add", Counter::add)
            .command("reset", Counter::reset)
            .query("get", Counter::get)
    }
}

struct CounterApi;

impl CounterApi {
    fn get(request: &Request) -> Result<Tally, HttpProblem> {
        Ok(request
            .client()
            .invoke(Counter, request.path("name"), "get", ())?)
    }

    fn add(request: &Request, amount: i64) -> Result<i64, HttpProblem> {
        Ok(request
            .client()
            .invoke(Counter, request.path("name"), "add", amount)?)
    }
}

impl Endpoint for CounterApi {
    const ENDPOINT_ID: &'static str = "CounterApi";
    const PREFIX: &'static str = "/counters";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<CounterApi> {
        Routes::new()
            .get("/{name}", CounterApi::get)
            .post("/{name}", CounterApi::add)
            .get("/top", |_: &Request| Ok("the top counter".to_string()))
    }
}

#[test]
fn a_command_persists_events_folds_them_and_replies_from_the_state_after() {
    let mut kit = EventSourcedTestKit::<Counter>::new("apples");
    let outcome = kit.command("add", 3_i64);
    assert_eq!(outcome.events, vec![TallyEvent::Added { amount: 3 }]);
    assert_eq!(
        outcome.new_state,
        Tally {
            name: "apples".into(),
            total: 3
        }
    );
    assert_eq!(outcome.reply::<i64>(), Ok(3));
    assert!(outcome.persisted());

    // The state is carried to the next command.
    assert_eq!(kit.command("add", 4_i64).reply::<i64>(), Ok(7));
    assert_eq!(kit.state().total, 7);
    assert_eq!(kit.events().len(), 2);
}

#[test]
fn a_refusal_persists_nothing_and_says_why() {
    let mut kit = EventSourcedTestKit::<Counter>::new("pears");
    let outcome = kit.command("add", 0_i64);
    assert!(outcome.events.is_empty());
    let refusal = outcome.error().expect("refused");
    assert_eq!(refusal.code, ErrorCode::BadRequest);
    assert_eq!(
        outcome.reply::<i64>().unwrap_err().code,
        ErrorCode::BadRequest
    );
    assert_eq!(kit.state().total, 0);
}

#[test]
fn a_query_answers_from_the_state_and_a_start_state_can_be_given() {
    let mut kit = EventSourcedTestKit::<Counter>::new("plums").with_state(Tally {
        name: "plums".into(),
        total: 40,
    });
    let got = kit.command("get", ());
    assert!(got.events.is_empty());
    assert_eq!(got.reply::<Tally>().unwrap().total, 40);
}

#[test]
fn a_deleted_instance_is_fresh_for_the_next_command() {
    let mut kit = EventSourcedTestKit::<Counter>::new("figs");
    kit.command("add", 5_i64);
    let reset = kit.command("reset", ());
    assert_eq!(reset.retention, Some(ankka::effects::Retention::DeleteNow));
    assert_eq!(kit.command("get", ()).reply::<Tally>().unwrap().total, 0);
}

#[test]
fn an_unknown_handler_is_a_refusal_not_a_fault() {
    let mut kit = EventSourcedTestKit::<Counter>::new("kiwis");
    let refused = kit.command("nope", ());
    assert_eq!(refused.error().map(|e| e.code), Some(ErrorCode::NotFound));
}

#[test]
#[should_panic(expected = "faulted")]
fn an_input_that_does_not_decode_is_a_fault_the_test_sees() {
    let mut kit = EventSourcedTestKit::<Counter>::new("dates");
    kit.command("add", "not a number");
}

#[test]
fn an_endpoint_answers_from_its_entities_in_memory() {
    let service = Service::new("test").register(Counter);
    let kit = EndpointTestKit::<CounterApi>::with_service(service);
    assert_eq!(kit.post("/counters/limes", &2_i64).text(), "2");
    let r = kit.post("/counters/limes", &3_i64);
    assert_eq!((r.status, r.text()), (200, "5".to_string()));
    let tally: Tally = kit.get("/counters/limes").json().unwrap();
    assert_eq!(
        tally,
        Tally {
            name: "limes".into(),
            total: 5
        }
    );
    // A refusal from the entity is its status.
    assert_eq!(kit.post("/counters/limes", &0_i64).status, 400);
}

#[test]
fn a_literal_segment_outranks_a_parameter_and_no_route_is_a_404() {
    let kit = EndpointTestKit::<CounterApi>::new();
    assert_eq!(kit.get("/counters/top").text(), "the top counter");
    assert_eq!(kit.get("/elsewhere").status, 404);
    assert_eq!(kit.request("PUT", "/counters/x").send().status, 404);
}

#[test]
fn with_nothing_behind_it_an_endpoint_calls_nothing() {
    let kit = EndpointTestKit::<CounterApi>::new();
    assert_eq!(kit.get("/counters/limes").status, 503);
}

// ── consumers: the change's sequence number, what was published, and a graph ──

/// Publishes the sequence number it was handed, twice: once keyed by the subject, once by a key
/// of its own.
struct Echo;

impl Consumer for Echo {
    type Message = TallyEvent;
    const COMPONENT_ID: &'static str = "echo";

    fn source() -> Source {
        Source::of(Counter)
    }

    fn produces_to() -> Option<&'static str> {
        Some("echoes")
    }

    fn on_message(_: TallyEvent, ctx: &Context) -> ConsumerEffect {
        consumer::produce_all([
            consumer::message(ctx.sequence()),
            consumer::message(ctx.sequence()).key(format!("seq:{}", ctx.entity_id())),
        ])
    }

    fn on_deleted(ctx: &Context) -> ConsumerEffect {
        consumer::produce(ctx.sequence())
    }
}

#[test]
fn a_consumer_is_handed_the_sequence_number_the_kit_states_and_its_messages_are_read_back() {
    let kit = ConsumerTestKit::<Echo>::new().at(7);
    let effect = kit.on_message("apples", TallyEvent::Added { amount: 1 });
    let messages = ConsumerTestKit::<Echo>::messages(&effect);
    let sequences: Vec<i64> = messages.iter().map(|m| m.read()).collect();
    assert_eq!(sequences, vec![7, 7]);
    let keys: Vec<Option<&str>> = messages.iter().map(|m| m.key.as_deref()).collect();
    assert_eq!(keys, vec![None, Some("seq:apples")]);

    // A single produce is one message, with no key.
    let deleted = ConsumerTestKit::<Echo>::messages(&kit.on_deleted("apples"));
    assert_eq!(deleted.len(), 1);
    assert_eq!(deleted[0].read::<i64>(), 7);
    assert_eq!(deleted[0].key, None);

    // Without `at`, a change carries no sequence number, as a topic's message does.
    let unsequenced = ConsumerTestKit::<Echo>::new();
    let messages = ConsumerTestKit::<Echo>::messages(&unsequenced.on_deleted("apples"));
    assert_eq!(messages[0].read::<i64>(), 0);
}

/// A graph of tallies, each node built from the counter's state read through the client.
struct TallyGraph;

impl GraphConsumer for TallyGraph {
    type Message = TallyEvent;
    const COMPONENT_ID: &'static str = "tally-graph";
    const TOPIC: &'static str = "tallies";

    fn source() -> Source {
        Source::of(Counter)
    }

    fn on_message(_: TallyEvent, ctx: &Context) -> GraphEffect {
        let name = ctx.entity_id();
        let tally: Tally = ctx
            .client()
            .invoke(Counter, name, "get", ())
            .expect("the counter answers");
        graph::publish([graph::node(format!("tally:{name}"))
            .label("Tally")
            .property("name", tally.name)
            .property("total", tally.total)])
    }

    fn on_deleted(ctx: &Context) -> GraphEffect {
        graph::publish([graph::tombstone_node(format!("tally:{}", ctx.entity_id()))])
    }
}

#[test]
fn a_graph_consumers_elements_are_read_back_and_its_calls_are_answered_in_memory() {
    let service = Service::new("test").register(Counter).register(TallyGraph);
    let kit = GraphConsumerTestKit::<TallyGraph>::new().with_service(service);

    let elements = kit.on_message("apples", 3, TallyEvent::Added { amount: 2 });
    assert_eq!(elements.len(), 1);
    let node = &elements[0];
    assert_eq!(node.kind(), ElementKind::Node);
    assert_eq!(node.id(), "tally:apples");
    assert_eq!(node.key(), "node:tally:apples");
    // The change's sequence number, with nothing in the consumer saying so.
    assert_eq!(node.version(), Some(3));
    assert_eq!(node.labels(), ["Tally".to_string()]);
    // The counter's state came from the service in memory: its empty state carries its name.
    assert_eq!(node.get("name"), Some(&Value::from("apples")));
    assert_eq!(node.get("total"), Some(&Value::Integer(0)));

    let gone = kit.on_deleted("apples", 4);
    assert_eq!(gone[0].kind(), ElementKind::NodeTombstone);
    assert_eq!(gone[0].key(), "node:tally:apples");
    assert_eq!(gone[0].version(), Some(4));

    // The records behind them: a delta's bytes, its key and its type.
    let records = kit.records("apples", 5, TallyEvent::Reset {});
    assert_eq!(records[0].key.as_deref(), Some("node:tally:apples"));
    assert_eq!(records[0].payload.manifest, "ankka.graph-delta.v1");
    assert_eq!(
        String::from_utf8_lossy(&records[0].payload.data),
        r#"{"kind":"node","id":"tally:apples","version":5,"labels":["Tally"],"properties":{"name":"apples","total":0}}"#
    );
}
