//! Graph deltas: what a graph consumer's elements are published as, held to the fixtures the
//! reader of those deltas is tested against.
//!
//! `protocol/fixtures/graph-deltas` holds `keys.json` and `deltas.json`, copied from ankka-flow,
//! whose merge sink reads the same rows, and `refused.json`, the elements every SDK's builder must
//! refuse. Every row is built through the builder here: what is published has the fixture's key
//! and reads back as the fixture's delta, and what is refused is refused for the reason named.

use std::path::PathBuf;

use ankka::components::{ComponentOf, kinds};
use ankka::graph::{Element, ElementKind, Refused, Value, Why};
use ankka::prelude::*;
use ankka::proto;
use ankka::testkit::GraphConsumerTestKit;
use serde_json::Value as Json;

// ── the fixtures ──

/// The fixture file as JSON. An integer that fits neither an `i64` nor a `u64` would be read as
/// the nearest float, and `-9223372036854775809` as `-9223372036854775808`; such a literal is
/// read as `{"$int": "<digits>"}` instead, so the row says what the file says.
fn fixture(name: &str) -> Vec<Json> {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("protocol/fixtures/graph-deltas")
        .join(name);
    let text = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("{}: {e}; run scripts/proto.sh", path.display()));
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    let mut in_string = false;
    while let Some(c) = chars.next() {
        if in_string {
            out.push(c);
            if c == '\\' {
                out.extend(chars.next());
            } else if c == '"' {
                in_string = false;
            }
        } else if c == '"' {
            in_string = true;
            out.push(c);
        } else if c == '-' || c.is_ascii_digit() {
            let mut token = String::from(c);
            while let Some(next) = chars.peek().filter(|n| "0123456789.eE+-".contains(**n)) {
                token.push(*next);
                chars.next();
            }
            let digits = token.strip_prefix('-').unwrap_or(&token);
            let integer = !digits.is_empty() && digits.chars().all(|d| d.is_ascii_digit());
            if integer && token.parse::<i64>().is_err() && token.parse::<u64>().is_err() {
                out.push_str(&format!("{{\"$int\":\"{token}\"}}"));
            } else {
                out.push_str(&token);
            }
        } else {
            out.push(c);
        }
    }
    serde_json::from_str::<Vec<Json>>(&out).unwrap_or_else(|e| panic!("{name}: {e}"))
}

/// A fixture's property value as a builder's. `Err`: the types have no way to say it, which is
/// the refusal a language that could say it would make.
fn scalar(value: &Json) -> Result<Value, Why> {
    match value {
        Json::String(text) => Ok(Value::Text(text.clone())),
        Json::Bool(flag) => Ok(Value::Bool(*flag)),
        Json::Number(n) => match (n.as_i64(), n.is_u64()) {
            (Some(n), _) => Ok(Value::Integer(n)),
            // Past the largest i64: `Value::Integer` cannot hold it.
            (None, true) => Err(Why::IntegerRange),
            (None, false) => Ok(Value::Float(n.as_f64().expect("a float"))),
        },
        Json::Object(tagged) if tagged.contains_key("$int") => Err(Why::IntegerRange),
        // `Value` has no null and no object.
        Json::Null | Json::Object(_) => Err(Why::PropertyValue),
        Json::Array(values) => Ok(Value::List(
            values.iter().map(scalar).collect::<Result<_, _>>()?,
        )),
    }
}

fn text<'a>(delta: &'a Json, field: &str) -> &'a str {
    delta[field].as_str().unwrap_or_default()
}

/// The element a fixture's delta describes, built as a handler would build it.
fn build(delta: &Json) -> Result<Element, Why> {
    let id = text(delta, "id");
    let (edge_type, from, to) = (text(delta, "type"), text(delta, "from"), text(delta, "to"));
    let tombstone = text(delta, "kind") == "tombstone";
    let mut element = match (text(delta, "kind"), text(delta, "element")) {
        ("node", _) => graph::node(id),
        ("edge", _) => graph::edge(id, edge_type, from, to),
        ("tombstone", "node") => graph::tombstone_node(id),
        ("tombstone", "edge") => graph::tombstone_edge(id, edge_type, from, to),
        other => panic!("a fixture with an unknown kind: {other:?}"),
    };
    for label in delta["labels"].as_array().into_iter().flatten() {
        element = element.label(label.as_str().expect("a label is text"));
    }
    if !tombstone {
        for (name, value) in delta["properties"].as_object().into_iter().flatten() {
            element = element.property(name, scalar(value)?);
        }
    }
    match &delta["version"] {
        Json::Null => Ok(element),
        // A version is an i64 by type: one that is not whole, or does not fit, cannot be stated.
        version => version.as_i64().map(|v| element.at(v)).ok_or(Why::Version),
    }
}

/// Publishes whatever elements its message describes: the fixtures' rows, through dispatch.
struct Rows;

impl GraphConsumer for Rows {
    type Message = Json;
    const COMPONENT_ID: &'static str = "rows";
    const TOPIC: &'static str = "graph";

    fn source() -> Source {
        Source::topic("rows")
    }

    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Earliest)
    }

    fn on_message(elements: Json, _: &Context) -> GraphEffect {
        let elements = elements.as_array().expect("a list of elements");
        graph::publish(
            elements
                .iter()
                .map(|e| build(e).expect("an element the types can say")),
        )
    }

    fn on_deleted(ctx: &Context) -> GraphEffect {
        graph::publish([graph::tombstone_node(format!("row:{}", ctx.entity_id()))])
    }
}

fn kind_of(value: &Value) -> String {
    match value {
        Value::Text(_) => "string".into(),
        Value::Bool(_) => "boolean".into(),
        Value::Integer(_) => "integer".into(),
        Value::Float(_) => "float".into(),
        Value::List(values) => format!("list:{}", kind_of(&values[0])),
    }
}

/// What a handler's panic said.
fn panic_of<T>(f: impl FnOnce() -> T) -> String {
    let caught = std::panic::catch_unwind(std::panic::AssertUnwindSafe(f));
    let payload = caught.err().expect("it panics");
    payload
        .downcast_ref::<String>()
        .cloned()
        .or_else(|| payload.downcast_ref::<&str>().map(|s| s.to_string()))
        .expect("a panic with a message")
}

#[test]
fn every_delta_of_the_shared_fixtures_is_published_under_its_key_and_reads_back_as_itself() {
    let kit = GraphConsumerTestKit::<Rows>::new();
    let mut kinds = std::collections::BTreeSet::new();
    let (keys, deltas) = (fixture("keys.json"), fixture("deltas.json"));
    assert!(
        keys.len() >= 8 && deltas.len() >= 12,
        "the fixtures are short"
    );
    for (file, row) in keys
        .iter()
        .map(|r| ("keys.json", r))
        .chain(deltas.iter().map(|r| ("deltas.json", r)))
    {
        let key = row["key"].as_str().expect("a key");
        let delta = &row["delta"];
        let what = format!("{file}: {key}");
        // The fixture's own bytes, as the reader of the topic would be given them.
        let given = graph::read(delta.to_string().as_bytes(), Some(key))
            .unwrap_or_else(|e| panic!("{what}: the fixture does not read: {e}"));

        let records = kit.records("subject", 1, Json::Array(vec![delta.clone()]));
        assert_eq!(records.len(), 1, "{what}");
        assert_eq!(records[0].key.as_deref(), Some(key), "{what}");
        let written = graph::read(&records[0].payload.data, records[0].key.as_deref())
            .unwrap_or_else(|e| panic!("{what}: what was published does not read: {e}"));
        // Equality is the reader's: `2.0` is `2`, and labels and properties left out are empty.
        assert_eq!(written, given, "{what}");
        assert_eq!(written.key(), key, "{what}");
        assert_eq!(written.version(), delta["version"].as_i64(), "{what}");

        // The kind the sink reads each property as is the kind it reads as here.
        if let Some(reads) = row["reads"].as_object() {
            let found: std::collections::BTreeMap<String, String> = written
                .properties()
                .iter()
                .map(|(name, value)| (name.clone(), kind_of(value)))
                .collect();
            let wanted: std::collections::BTreeMap<String, String> = reads
                .iter()
                .map(|(name, kind)| (name.clone(), kind.as_str().unwrap().to_string()))
                .collect();
            assert_eq!(found, wanted, "{what}");
            kinds.extend(found.into_values());
        }
    }
    let every: std::collections::BTreeSet<String> = ["string", "integer", "float", "boolean"]
        .iter()
        .flat_map(|k| [k.to_string(), format!("list:{k}")])
        .collect();
    assert_eq!(kinds, every, "every kind of property is in the fixture");
}

#[test]
fn every_refused_element_of_the_fixture_is_refused_for_the_reason_it_names() {
    let rows = fixture("refused.json");
    assert!(rows.len() >= 40, "the fixture is short");
    let mut reasons = std::collections::BTreeSet::new();
    for row in &rows {
        let name = row["name"].as_str().expect("a name");
        let wanted = row["why"].as_str().expect("a reason");
        let sequence = row["sequence"].as_i64().unwrap_or(1);
        let described: Vec<&Json> = match row["elements"].as_array() {
            Some(elements) => elements.iter().collect(),
            None => vec![&row["element"]],
        };
        let built: Result<Vec<Element>, Why> = described.into_iter().map(build).collect();
        let why = match built {
            // What the types cannot say is refused before there is an element to check.
            Err(why) => why,
            Ok(elements) => match graph::resolve(elements, sequence) {
                Err(Refused { why, message }) => {
                    assert!(
                        message.contains('\''),
                        "{name}: the message names the element"
                    );
                    why
                }
                Ok(accepted) => panic!("{name}: accepted as {accepted:?}"),
            },
        };
        assert_eq!(why.as_str(), wanted, "{name}");
        reasons.insert(wanted.to_string());
    }
    assert_eq!(
        reasons.len(),
        9,
        "every reason is in the fixture: {reasons:?}"
    );
}

#[test]
fn what_json_cannot_say_is_refused_too() {
    for unreal in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY] {
        let alone = graph::resolve(vec![graph::node("n").property("p", unreal)], 1);
        assert_eq!(alone.unwrap_err().why, Why::PropertyValue, "{unreal}");
        let listed = graph::resolve(vec![graph::node("n").property("p", vec![1.5, unreal])], 1);
        assert_eq!(
            listed.unwrap_err().why,
            Why::PropertyValue,
            "{unreal} in a list"
        );
    }
    // A label is a node's, and a tombstone has no state to give properties to.
    let labelled = graph::edge("e", "KNOWS", "a", "b").label("Friend");
    assert_eq!(
        graph::resolve(vec![labelled], 1).unwrap_err().why,
        Why::Misplaced
    );
    let propertied = graph::tombstone_node("n").property("p", 1);
    assert_eq!(
        graph::resolve(vec![propertied], 1).unwrap_err().why,
        Why::Misplaced
    );
}

#[test]
fn a_node_and_an_edge_may_share_an_id_and_an_element_may_not_be_there_twice() {
    let both = vec![graph::node("same"), graph::edge("same", "LINKS", "a", "b")];
    let resolved = graph::resolve(both, 4).expect("separate id spaces");
    assert_eq!(resolved[0].key(), "node:same");
    assert_eq!(resolved[1].key(), "edge:same");
    assert_eq!(graph::node_key("same"), "node:same");
    assert_eq!(graph::edge_key("same"), "edge:same");

    let twice = vec![graph::node("n").label("A"), graph::tombstone_node("n")];
    let refused = graph::resolve(twice, 4).unwrap_err();
    assert_eq!(refused.why, Why::Duplicate);
    assert!(
        refused.message.contains("tombstone of node 'n'"),
        "{}",
        refused.message
    );
}

// ── versions ──

#[test]
fn an_element_takes_the_changes_sequence_number_unless_it_states_a_version() {
    let elements = vec![
        graph::node("a"),
        graph::node("b").at(900),
        graph::edge("e", "LINKS", "a", "b"),
    ];
    let versions: Vec<Option<i64>> = graph::resolve(elements, 7)
        .unwrap()
        .iter()
        .map(Element::version)
        .collect();
    assert_eq!(versions, vec![Some(7), Some(900), Some(7)]);
    for stated in [0, -1] {
        let refused = graph::resolve(vec![graph::node("a").at(stated)], 7).unwrap_err();
        assert_eq!(refused.why, Why::Version, "{stated}");
    }
}

#[test]
fn a_change_with_no_sequence_number_needs_a_stated_version() {
    // A topic's message: sequence 0.
    let kit = GraphConsumerTestKit::<Rows>::new();
    let unversioned = serde_json::json!([{"kind": "node", "id": "n"}]);
    let said = panic_of(|| kit.on_message("subject", 0, unversioned.clone()));
    assert!(said.contains("graph consumer 'rows'"), "{said}");
    assert!(said.contains("node 'n'"), "{said}");
    assert!(said.contains("no sequence number"), "{said}");
    assert!(said.contains("state a version"), "{said}");
    // The same from the deletion handler, which builds a tombstone with no version.
    assert!(panic_of(|| kit.on_deleted("subject", 0)).contains("no sequence number"));

    let versioned = serde_json::json!([{"kind": "node", "id": "n", "version": 5}]);
    let elements = kit.on_message("subject", 0, versioned);
    assert_eq!(elements[0].version(), Some(5));
    // With a sequence number, the stated version still wins.
    let elements = kit.on_message("subject", 3, unversioned);
    assert_eq!(elements[0].version(), Some(3));
}

#[test]
fn a_change_handled_twice_publishes_equal_records() {
    let kit = GraphConsumerTestKit::<Rows>::new();
    let change = serde_json::json!([
        {"kind": "node", "id": "cart:1", "labels": ["Cart"], "properties": {"n": 1, "f": 0.5}},
        {"kind": "edge", "id": "e", "type": "LINKS", "from": "cart:1", "to": "x"},
        {"kind": "tombstone", "element": "node", "id": "old"}
    ]);
    let first = kit.records("cart-1", 9, change.clone());
    let again = kit.records("cart-1", 9, change);
    assert_eq!(first.len(), 3);
    // Key, value and headers: a redelivered change is the same records, so a reader that keeps
    // the highest version is unmoved by it.
    assert_eq!(first, again);
}

// ── the record ──

#[test]
fn a_delta_is_written_as_the_contract_writes_it_under_its_element_key() {
    let kit = GraphConsumerTestKit::<Rows>::new();
    let change = serde_json::json!([
        {"kind": "node", "id": "cart:1", "labels": ["Cart"],
         "properties": {"cartId": "1", "checkedOut": false, "lines": 2, "ratio": 0.25}},
        {"kind": "node", "id": "bare"},
        {"kind": "edge", "id": "checked-out:1", "type": "CHECKED_OUT",
         "from": "cart:1", "to": "checkout:1"},
        {"kind": "tombstone", "element": "node", "id": "cart:1-old"},
        {"kind": "tombstone", "element": "edge", "id": "e", "type": "LINKS", "from": "a", "to": "b"}
    ]);
    let records = kit.records("1", 4, change);
    let written: Vec<&str> = records
        .iter()
        .map(|r| std::str::from_utf8(&r.payload.data).unwrap())
        .collect();
    assert_eq!(
        written,
        vec![
            r#"{"kind":"node","id":"cart:1","version":4,"labels":["Cart"],"properties":{"cartId":"1","checkedOut":false,"lines":2,"ratio":0.25}}"#,
            r#"{"kind":"node","id":"bare","version":4,"labels":[],"properties":{}}"#,
            r#"{"kind":"edge","id":"checked-out:1","version":4,"type":"CHECKED_OUT","from":"cart:1","to":"checkout:1","properties":{}}"#,
            r#"{"kind":"tombstone","element":"node","id":"cart:1-old","version":4}"#,
            r#"{"kind":"tombstone","element":"edge","id":"e","version":4,"type":"LINKS","from":"a","to":"b"}"#,
        ]
    );
    let keys: Vec<Option<&str>> = records.iter().map(|r| r.key.as_deref()).collect();
    assert_eq!(
        keys,
        vec![
            Some("node:cart:1"),
            Some("node:bare"),
            Some("edge:checked-out:1"),
            Some("node:cart:1-old"),
            Some("edge:e"),
        ]
    );
    for record in &records {
        // The contract's name, not a type's: what makes the record self-describing.
        assert_eq!(record.payload.manifest, graph::SCHEMA_NAME);
        assert_eq!(record.payload.manifest, "ankka.graph-delta.v1");
        assert_eq!(record.payload.content_type, "application/json");
        assert_eq!(record.metadata.get("ce-type"), Some("ankka.graph-delta.v1"));
        // The subject is the runtime's to set: the source entity's id.
        assert_eq!(record.metadata.subject(), None);
    }
}

#[test]
fn an_element_the_reader_would_refuse_fails_the_change_naming_it_and_publishes_nothing() {
    let kit = GraphConsumerTestKit::<Rows>::new();
    let change = serde_json::json!([
        {"kind": "node", "id": "fine"},
        {"kind": "node", "id": "n", "labels": ["Has Space"]}
    ]);
    let said = panic_of(|| kit.on_message("subject", 1, change));
    assert_eq!(
        said,
        "graph consumer 'rows': node 'n': label 'Has Space' is not an identifier \
         ([A-Za-z_][A-Za-z0-9_]*)"
    );
}

// ── the reader ──

#[test]
fn the_reader_holds_a_delta_to_the_contract_and_a_key_to_its_element() {
    let node = br#"{"kind":"node","id":"cart:1","version":0,"properties":{"whole":2.0,"n":2}}"#;
    let read = graph::read(node, Some("node:cart:1")).expect("version 0 is the contract's");
    assert_eq!(read.kind(), ElementKind::Node);
    assert_eq!(read.version(), Some(0));
    assert_eq!(read.get("whole"), Some(&Value::Integer(2)));
    assert_eq!(read.get("whole"), read.get("n"));
    assert_eq!(Value::Float(2.0), Value::Integer(2));
    assert_ne!(Value::Float(2.5), Value::Integer(2));

    let wrong = graph::read(node, Some("cart:1")).unwrap_err();
    assert_eq!(
        wrong,
        "key 'cart:1' is not this delta's element key 'node:cart:1'"
    );
    assert!(graph::read(node, Some("edge:cart:1")).is_err());
    assert!(graph::read(node, None).is_ok());

    let refusals: [(&[u8], &str); 12] = [
        (b"[1]", "not a JSON object"),
        (b"nonsense", "not a JSON object"),
        (br#"{"id":"n","version":1}"#, "kind missing"),
        (
            br#"{"kind":"thing","id":"n","version":1}"#,
            "unknown kind 'thing'",
        ),
        (
            br#"{"kind":"node","id":"","version":1}"#,
            "id missing or empty",
        ),
        (
            br#"{"kind":"node","id":"n","version":-1}"#,
            "version is not a non-negative integer",
        ),
        (
            br#"{"kind":"node","id":"n","version":1.5}"#,
            "version is not a non-negative integer",
        ),
        (
            br#"{"kind":"node","id":"n","version":1,"labels":["a b"]}"#,
            "labels must be an array of identifiers",
        ),
        (
            br#"{"kind":"edge","id":"e","version":1,"type":"T","from":"a"}"#,
            "edge needs type, from and to",
        ),
        (
            br#"{"kind":"tombstone","id":"n","version":1}"#,
            "tombstone needs element 'node' or 'edge'",
        ),
        (
            br#"{"kind":"node","id":"n","version":1,"properties":{"p":[1,"a"]}}"#,
            "property 'p' is not a scalar or array of scalars",
        ),
        (
            br#"{"kind":"node","id":"n","version":1,"properties":{"_version":1}}"#,
            "property '_version' is reserved",
        ),
    ];
    for (value, message) in refusals {
        assert_eq!(graph::read(value, None).unwrap_err(), message);
    }
    let edge_tombstone = br#"{"kind":"tombstone","element":"edge","id":"e","version":1}"#;
    assert_eq!(
        graph::read(edge_tombstone, None).unwrap_err(),
        "tombstone of an edge needs type, from and to"
    );
}

// ── registration, and the runtime it answers ──

#[test]
fn a_graph_consumer_is_discovered_as_a_consumer_that_produces_to_its_topic() {
    let service = Service::new("graphs")
        .register(Rows)
        .build()
        .expect("no problems");
    let spec = service
        .discover(&proto::SidecarInfo::default())
        .spec
        .unwrap();
    assert_eq!(spec.components.len(), 1);
    let component = &spec.components[0];
    assert_eq!(component.id, "rows");
    assert_eq!(component.kind, proto::Kind::Consumer as i32);
    match &component.detail {
        Some(proto::component::Detail::Consumer(detail)) => {
            assert_eq!(detail.produces_to.as_deref(), Some("graph"));
        }
        other => panic!("{other:?}"),
    }
    // It is named as a source, and found by its id, like any component.
    assert_eq!(
        Source::of(Rows),
        Source::Component(proto::Kind::Consumer, "rows")
    );
}

#[test]
fn a_graph_consumer_answers_with_no_deltas_a_runtime_that_has_not_said_it_takes_them() {
    let registration = <Rows as ComponentOf<kinds::GraphConsumer>>::registration();
    let request = |protocol: Option<&str>| {
        let mut metadata = Metadata::new()
            .set("ce-subject", "s")
            .set("ankka.sequence", "1");
        if let Some(protocol) = protocol {
            metadata = metadata.set("ankka.protocol", protocol);
        }
        proto::ConsumerRequest {
            component_id: "rows".into(),
            message: None,
            metadata: Some(metadata.to_proto()),
            deleted: true,
        }
    };
    assert_eq!(
        panic_of(|| registration.consumer(request(None))),
        "this runtime speaks protocol 1.2 or earlier; several messages or a record key need 1.3"
    );
    assert_eq!(
        panic_of(|| registration.consumer(request(Some("1.2")))),
        "this runtime speaks protocol 1.2; several messages or a record key need 1.3"
    );
    let effect = registration
        .consumer(request(Some("1.3")))
        .expect("it answers");
    match effect.effect {
        Some(proto::consumer_effect::Effect::ProduceAll(all)) => {
            assert_eq!(all.messages.len(), 1);
            assert_eq!(all.messages[0].key.as_deref(), Some("node:row:s"));
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn no_elements_at_all_is_done() {
    let kit = GraphConsumerTestKit::<Rows>::new();
    assert!(
        kit.on_message("subject", 1, serde_json::json!([]))
            .is_empty()
    );
}
