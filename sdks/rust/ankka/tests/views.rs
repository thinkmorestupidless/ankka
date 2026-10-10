//! A view's declared queries: what discovery says of them, what asking one sends, and the runtime
//! too old to know them.

use std::cell::RefCell;
use std::rc::Rc;

use ankka::Client;
use ankka::abi::imports::{Import, NativeHost, with_native_host};
use ankka::prelude::*;
use ankka::proto;
use ankka::testkit::KeyedViewTestKit;
use prost::Message;
use std::collections::HashMap;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct NodeRow {
    key: String,
    under: Option<String>,
}

struct Nodes;

impl View for Nodes {
    type Row = NodeRow;
    type Event = NodeRow;
    const COMPONENT_ID: &'static str = "tree-rows";
    fn source() -> Source {
        Source::topic("nodes")
    }
    fn on_event(_: Option<NodeRow>, event: NodeRow, _: &Context) -> ViewEffect<NodeRow> {
        ViewEffect::UpdateRow(event)
    }
    fn declared() -> Vec<DeclaredQuery> {
        vec![query(
            "under",
            format!(
                "SELECT payload FROM {} WHERE payload::jsonb->>'under' = :row",
                table_of(Self::COMPONENT_ID)
            ),
        )]
    }
}

struct Plain;

impl View for Plain {
    type Row = NodeRow;
    type Event = NodeRow;
    const COMPONENT_ID: &'static str = "plain-rows";
    fn source() -> Source {
        Source::topic("nodes")
    }
    fn on_event(_: Option<NodeRow>, event: NodeRow, _: &Context) -> ViewEffect<NodeRow> {
        ViewEffect::UpdateRow(event)
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

#[test]
fn a_view_table_is_named_as_the_runtime_names_it() {
    assert_eq!(table_of("tree-rows"), "ankka_view_tree_rows");
    assert_eq!(table_of("a.b-c_d"), "ankka_view_a_b_c_d");
}

#[test]
fn discovery_carries_the_declared_queries_as_written() {
    let spec = discover(Service::new("test").register(Nodes), "1.13");
    let Some(proto::component::Detail::View(detail)) = &spec.components[0].detail else {
        panic!("a view");
    };
    assert_eq!(
        detail.declared_queries,
        vec![proto::DeclaredQuery {
            name: "under".to_string(),
            statement:
                "SELECT payload FROM ankka_view_tree_rows WHERE payload::jsonb->>'under' = :row"
                    .to_string(),
            watched: false,
        }]
    );
}

#[test]
#[should_panic(expected = "tree-rows declare queries")]
fn a_runtime_too_old_for_declared_queries_is_refused_naming_the_view() {
    let _ = discover(Service::new("test").register(Nodes), "1.7");
}

#[test]
fn a_view_that_declares_no_query_is_answered_to_an_older_runtime() {
    let spec = discover(Service::new("test").register(Plain), "1.7");
    assert_eq!(spec.components.len(), 1);
}

/// Answers every query with two rows, keeping what it was asked.
struct Recorder {
    asked: Rc<RefCell<Vec<proto::QueryRequest>>>,
}

impl NativeHost for Recorder {
    fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
        assert_eq!(import, Import::Query);
        self.asked
            .borrow_mut()
            .push(proto::QueryRequest::decode(request).unwrap());
        proto::QueryReply {
            result: Some(proto::query_reply::Result::Rows(proto::Payload {
                content_type: "application/json".into(),
                manifest: "rows".into(),
                data: br#"[{"key":"b","under":"a"},{"key":"c","under":"b"}]"#.to_vec(),
            })),
        }
        .encode_to_vec()
    }
}

#[test]
fn asking_a_declared_query_sends_its_name_and_values_and_reads_the_rows() {
    let asked = Rc::new(RefCell::new(Vec::new()));
    let rows: Vec<NodeRow> = with_native_host(
        Recorder {
            asked: asked.clone(),
        },
        || Client::default().ask(Nodes, "under", &[("row", "a")]),
    )
    .unwrap();
    assert_eq!(
        rows.iter().map(|r| r.key.as_str()).collect::<Vec<_>>(),
        vec!["b", "c"]
    );
    let request = &asked.borrow()[0];
    assert_eq!(request.view_id, "tree-rows");
    assert_eq!(request.name, "under");
    assert_eq!(request.values.get("row").map(String::as_str), Some("a"));
    assert_eq!(request.limit, None);
    assert!(request.payload.is_none());
}

#[test]
fn asking_by_id_sends_the_limit() {
    let asked = Rc::new(RefCell::new(Vec::new()));
    let _: Vec<NodeRow> = with_native_host(
        Recorder {
            asked: asked.clone(),
        },
        || Client::default().ask_by_name("tree-rows", "under", &[("row", "a")], Some(5)),
    )
    .unwrap();
    assert_eq!(asked.borrow()[0].limit, Some(5));
}

// ── Keyed views ──────────────────────────────────────────────────────────────

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Noted {
    text: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct JoinedRow {
    key: String,
    holding: String,
    notes: Vec<String>,
}

struct Left;
impl EventSourcedEntity for Left {
    type State = i32;
    type Event = Noted;
    const COMPONENT_ID: &'static str = "left";
    fn empty_state(_: &str) -> i32 {
        0
    }
    fn apply(state: i32, _: &Noted) -> i32 {
        state + 1
    }
    fn handlers() -> Handlers<Left> {
        Handlers::new()
    }
}

struct Right;
impl EventSourcedEntity for Right {
    type State = i32;
    type Event = Noted;
    const COMPONENT_ID: &'static str = "right";
    fn empty_state(_: &str) -> i32 {
        0
    }
    fn apply(state: i32, _: &Noted) -> i32 {
        state + 1
    }
    fn handlers() -> Handlers<Right> {
        Handlers::new()
    }
}

struct Joined;

impl Joined {
    /// `key|holding`: read the row, and write it again with the left's note.
    fn on_left(event: Noted, ctx: &Context) -> KeyedViewEffect<JoinedRow> {
        let (key, holding) = event.text.split_once('|').expect("key|holding");
        let mut notes = ctx
            .rows()
            .get::<JoinedRow>(key)
            .map(|r| r.notes)
            .unwrap_or_default();
        notes.push("left".into());
        KeyedViewEffect::update_row(
            key,
            JoinedRow {
                key: key.into(),
                holding: holding.into(),
                notes,
            },
        )
    }

    /// Every row holding this right entity, found by asking the view's own query.
    fn on_right(_: Noted, ctx: &Context) -> KeyedViewEffect<JoinedRow> {
        let subject = ctx.metadata().subject().unwrap_or_default();
        let theirs: Vec<JoinedRow> = ctx.rows().ask("of-right", &[("holding", subject)]);
        KeyedViewEffect::update_rows(theirs.into_iter().map(|mut row| {
            row.notes.push("right".into());
            (row.key.clone(), row)
        }))
    }

    fn on_right_deleted(ctx: &Context) -> KeyedViewEffect<JoinedRow> {
        KeyedViewEffect::delete_row(ctx.metadata().subject().unwrap_or_default())
    }
}

impl KeyedView for Joined {
    type Row = JoinedRow;
    const COMPONENT_ID: &'static str = "joined";
    fn sources() -> Sources<Joined> {
        Sources::new()
            .on::<Noted>(Source::of(Left), Joined::on_left)
            .on::<Noted>(Source::of(Right), Joined::on_right)
            .on_deleted(Source::of(Right), Joined::on_right_deleted)
    }
    fn declared() -> Vec<DeclaredQuery> {
        vec![query(
            "of-right",
            format!(
                "SELECT payload FROM {} WHERE payload::jsonb->>'holding' = :holding",
                table_of(Self::COMPONENT_ID)
            ),
        )]
    }
}

fn noted(text: &str) -> Noted {
    Noted { text: text.into() }
}

#[test]
fn a_keyed_view_is_discovered_with_its_sources_and_no_single_source() {
    let spec = discover(
        Service::new("test")
            .register(Left)
            .register(Right)
            .register(Joined),
        "1.13",
    );
    let joined = spec.components.iter().find(|c| c.id == "joined").unwrap();
    let Some(proto::component::Detail::View(detail)) = &joined.detail else {
        panic!("a view");
    };
    assert!(detail.source.is_none());
    let read: Vec<String> = detail
        .sources
        .iter()
        .map(|s| match &s.source {
            Some(proto::source::Source::Component(c)) => c.id.clone(),
            other => panic!("{other:?}"),
        })
        .collect();
    assert_eq!(read, vec!["left", "right"]);
    assert_eq!(detail.declared_queries.len(), 1);
}

#[test]
#[should_panic(expected = "joined declare queries, keyed views")]
fn a_runtime_too_old_for_a_keyed_view_is_refused_naming_it() {
    let _ = discover(
        Service::new("test")
            .register(Left)
            .register(Right)
            .register(Joined),
        "1.7",
    );
}

#[test]
fn a_change_is_dispatched_by_its_source_and_answered_with_rows() {
    let mut kit = KeyedViewTestKit::<Joined>::new();
    kit.change(Source::of(Left), "l-1", noted("s1|r1"));
    kit.change(Source::of(Left), "l-1", noted("s1|r1"));
    assert_eq!(kit.row("s1").unwrap().notes, vec!["left", "left"]);
}

#[test]
fn a_handler_finds_the_rows_a_change_is_about_by_asking_its_own_query() {
    let mut kit = KeyedViewTestKit::<Joined>::new().answering("of-right", |values, rows| {
        rows.iter()
            .filter(|r| Some(&r.holding) == values.get("holding"))
            .cloned()
            .collect()
    });
    kit.change(Source::of(Left), "l-1", noted("s1|r1"));
    kit.change(Source::of(Left), "l-2", noted("s2|r1"));
    kit.change(Source::of(Left), "l-3", noted("s3|r2"));
    kit.change(Source::of(Right), "r1", noted(""));
    assert_eq!(kit.row("s1").unwrap().notes, vec!["left", "right"]);
    assert_eq!(kit.row("s2").unwrap().notes, vec!["left", "right"]);
    assert_eq!(kit.row("s3").unwrap().notes, vec!["left"]);
}

#[test]
#[should_panic(expected = "has not said what it answers")]
fn a_query_the_test_has_not_answered_panics_naming_it() {
    let mut kit = KeyedViewTestKit::<Joined>::new();
    kit.change(Source::of(Right), "r1", noted(""));
}

#[test]
fn a_deletion_runs_the_source_deletion_handler_and_otherwise_does_nothing() {
    let mut kit = KeyedViewTestKit::<Joined>::new();
    kit.change(Source::of(Left), "l-1", noted("r9|r9"));
    kit.deleted(Source::of(Left), "l-1");
    assert!(kit.row("r9").is_some(), "a left deletion names no row");
    kit.deleted(Source::of(Right), "r9");
    assert_eq!(kit.row("r9"), None);
}

#[test]
fn the_wire_effect_is_rows_in_order() {
    let registration = <Joined as ankka::components::ComponentOf<
        ankka::components::kinds::KeyedView,
    >>::registration();
    let effect = with_native_host(NoRows, || {
        registration.view(proto::ViewRequest {
            component_id: "joined".into(),
            event: Some(proto::Payload {
                content_type: "application/json".into(),
                manifest: "noted".into(),
                data: br#"{"text":"s1|r1"}"#.to_vec(),
            }),
            metadata: None,
            row: None,
            deleted: false,
            source_id: Some("left".into()),
        })
    })
    .unwrap();
    let Some(proto::view_effect::Effect::Rows(rows)) = effect.effect else {
        panic!("rows");
    };
    assert_eq!(rows.changes.len(), 1);
    assert_eq!(rows.changes[0].key, "s1");
}

/// Answers every read of a view's rows with none.
struct NoRows;

impl NativeHost for NoRows {
    fn call(&self, import: Import, _: &[u8]) -> Vec<u8> {
        assert_eq!(import, Import::Query);
        proto::QueryReply {
            result: Some(proto::query_reply::Result::Rows(proto::Payload {
                content_type: "application/json".into(),
                manifest: "rows".into(),
                data: b"[]".to_vec(),
            })),
        }
        .encode_to_vec()
    }
}

struct Twice;
impl KeyedView for Twice {
    type Row = JoinedRow;
    const COMPONENT_ID: &'static str = "twice";
    fn sources() -> Sources<Twice> {
        Sources::new()
            .on::<Noted>(Source::of(Left), Joined::on_left)
            .on::<Noted>(Source::of(Left), Joined::on_left)
            .on::<Noted>(Source::topic("orders"), Joined::on_left)
    }
}

struct Sourceless;
impl KeyedView for Sourceless {
    type Row = JoinedRow;
    const COMPONENT_ID: &'static str = "sourceless";
    fn sources() -> Sources<Sourceless> {
        Sources::new()
    }
}

#[test]
fn no_source_a_component_read_twice_and_a_topic_are_refused() {
    let problems: Vec<String> = Service::new("test")
        .register(Left)
        .register(Twice)
        .register(Sourceless)
        .build()
        .err()
        .unwrap()
        .into_iter()
        .map(|p| p.message)
        .collect();
    assert!(
        problems
            .iter()
            .any(|m| m.contains("'twice' reads 'left' twice")),
        "{problems:?}"
    );
    assert!(
        problems
            .iter()
            .any(|m| m.contains("a topic and an entity may not be sources of one view")),
        "{problems:?}"
    );
    assert!(
        problems
            .iter()
            .any(|m| m.contains("'sourceless' declares no source")),
        "{problems:?}"
    );
}

#[test]
fn values_reach_the_answering_function() {
    let seen = Rc::new(RefCell::new(HashMap::new()));
    let captured = seen.clone();
    let mut kit = KeyedViewTestKit::<Joined>::new().answering("of-right", move |values, _| {
        *captured.borrow_mut() = values.clone();
        Vec::new()
    });
    kit.change(Source::of(Right), "r7", noted(""));
    assert_eq!(seen.borrow().get("holding").map(String::as_str), Some("r7"));
}
