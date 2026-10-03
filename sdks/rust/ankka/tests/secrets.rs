//! The secret store as a Rust module sees it: who is given one, the rules, and the store a unit test
//! talks to. The runtime's store is held by the conformance suite's `secret.*` cases.

use std::cell::Cell;
use std::path::PathBuf;

use ankka::effects::{consumer, view, workflow};
use ankka::prelude::*;
use ankka::secrets::{name_problem, value_problem};
use ankka::testkit::{ConsumerTestKit, KeyValueEntityTestKit, ViewTestKit, WorkflowTestKit};

thread_local! {
    static VIEW_HAD_SECRETS: Cell<Option<bool>> = const { Cell::new(None) };
    static STEP_HAD_SECRETS: Cell<Option<bool>> = const { Cell::new(None) };
}

#[derive(serde::Deserialize)]
struct Row {
    unit: String,
    repeat: usize,
    accepted: bool,
    why: String,
}

#[derive(serde::Deserialize)]
struct Rules {
    names: Vec<Row>,
    values: Vec<Row>,
}

fn rules() -> Rules {
    let path =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("protocol/fixtures/secrets/rules.json");
    serde_json::from_str(&std::fs::read_to_string(path).expect("the rules fixture")).expect("rules")
}

fn store() -> ankka::Secrets {
    Context::new("test", "", 0, Default::default())
        .with_secrets()
        .secrets()
        .expect("a context given the store has one")
}

#[test]
fn every_name_and_value_in_the_fixture_gets_its_verdict() {
    let rules = rules();
    assert!(!rules.names.is_empty() && !rules.values.is_empty());
    for row in rules.names {
        assert_eq!(
            name_problem(&row.unit.repeat(row.repeat)).is_none(),
            row.accepted,
            "{}",
            row.why
        );
    }
    for row in rules.values {
        assert_eq!(
            value_problem(&row.unit.repeat(row.repeat)).is_none(),
            row.accepted,
            "{}",
            row.why
        );
    }
}

#[test]
fn the_unit_test_store_keeps_reads_and_refuses_what_the_runtime_refuses() {
    let secrets = store();
    secrets.put("provider/acme", "sk-1").unwrap();
    assert_eq!(secrets.get("provider/acme"), Ok(Some("sk-1".to_string())));
    assert_eq!(secrets.get("never"), Ok(None));
    let refused = secrets.put("provider acme", "sk-1").unwrap_err();
    assert_eq!(refused.code, ErrorCode::BadRequest);
    assert!(
        refused.message.contains("'.', '_', '-' or '/'"),
        "{}",
        refused.message
    );
    assert_eq!(
        secrets.put("empty", "").unwrap_err().code,
        ErrorCode::BadRequest
    );
    secrets.delete("provider/acme").unwrap();
    assert_eq!(secrets.get("provider/acme"), Ok(None));
}

// ── who is given a store ──

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
struct Nothing {}

struct Vault;

impl KeyValueEntity for Vault {
    type State = Nothing;
    const COMPONENT_ID: &'static str = "vault";

    fn empty_state(_: &str) -> Nothing {
        Nothing {}
    }

    fn handlers() -> KeyValueHandlers<Vault> {
        KeyValueHandlers::new().query("has-secrets", |_: &Nothing, _: (), ctx: &Context| {
            effects::reply(ctx.secrets().is_some())
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Seen {
    id: String,
}

struct Watcher;

impl View for Watcher {
    type Row = Seen;
    type Event = Seen;
    const COMPONENT_ID: &'static str = "watcher";

    fn source() -> Source {
        Source::topic("seen")
    }

    fn on_event(_: Option<Seen>, event: Seen, ctx: &Context) -> ViewEffect<Seen> {
        VIEW_HAD_SECRETS.with(|c| c.set(Some(ctx.secrets().is_some())));
        view::update_row(event)
    }
}

struct Keeper;

impl Consumer for Keeper {
    type Message = Seen;
    const COMPONENT_ID: &'static str = "keeper";

    fn source() -> Source {
        Source::topic("seen")
    }

    fn on_message(message: Seen, ctx: &Context) -> ConsumerEffect {
        let secrets = ctx.secrets().expect("a consumer has a secret store");
        secrets
            .put(&format!("seen/{}", message.id), "kept")
            .unwrap();
        consumer::ignore()
    }
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
struct Run {
    status: String,
}

struct Charges;

impl Workflow for Charges {
    type State = Run;
    const COMPONENT_ID: &'static str = "charges";

    fn empty_state(_: &str) -> Run {
        Run::default()
    }

    fn handlers() -> WorkflowHandlers<Charges> {
        WorkflowHandlers::new().command("start", |_: &Run, _: (), ctx: &Context| {
            let in_command = ctx.secrets().is_some();
            workflow::update_state(Run {
                status: format!("command-had-secrets:{in_command}"),
            })
            .transition_to("charge")
            .then_reply_value(Done)
        })
    }

    fn steps() -> Steps<Charges> {
        Steps::new().step("charge", |run: &Run, _: (), ctx: &Context| {
            STEP_HAD_SECRETS.with(|c| c.set(Some(ctx.secrets().is_some())));
            step_effects::update_state(run.clone()).then_end()
        })
    }
}

#[test]
fn an_entity_or_a_view_is_given_no_secret_store() {
    let mut vault = KeyValueEntityTestKit::<Vault>::new("v1");
    assert_eq!(
        vault.command("has-secrets", ()).answer.reply::<bool>(),
        Ok(false)
    );

    let mut watcher = ViewTestKit::<Watcher>::new();
    watcher.on_event("w1", Seen { id: "w1".into() });
    assert_eq!(VIEW_HAD_SECRETS.with(|c| c.get()), Some(false));
}

#[test]
fn a_consumer_is_given_one() {
    let kit = ConsumerTestKit::<Keeper>::new();
    kit.on_message("k1", Seen { id: "k1".into() });
    assert_eq!(store().get("seen/k1"), Ok(Some("kept".to_string())));
}

#[test]
fn a_workflow_reads_a_service_secret_in_a_step_and_not_in_a_command() {
    let mut kit = WorkflowTestKit::<Charges>::new("c1");
    assert_eq!(kit.command("start", ()).reply::<Done>(), Ok(Done));
    assert_eq!(kit.state().status, "command-had-secrets:false");
    kit.run_step();
    assert_eq!(STEP_HAD_SECRETS.with(|c| c.get()), Some(true));
}
