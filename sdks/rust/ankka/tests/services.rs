//! Calling another service, the clock and random bytes as a Rust module sees them, natively: who is
//! given the clients, what each answer of the runtime becomes, and the stand-ins a unit test puts in
//! the runtime's place. The runtime's own rule about where a call may be made is held by its host
//! suite, with guests that link no library.

use std::cell::Cell;

use ankka::abi::imports::{Import, NativeHost, call_request, with_native_host};
use ankka::effects::{consumer, view, workflow};
use ankka::prelude::*;
use ankka::proto;
use ankka::services::MAX_BODY_BYTES;
use ankka::testkit::{
    ConsumerTestKit, KeyValueEntityTestKit, ScriptedServices, ViewTestKit, WorkflowTestKit,
    with_clock, with_random,
};
use ankka::{ServiceClient, Services};
use prost::Message;

fn wallet() -> ServiceClient {
    Services::default().service("wallet")
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Credit {
    amount: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Receipt {
    id: String,
}

// ── what a call sends, and what an answer becomes ──

#[test]
fn a_typed_helper_sends_json_and_reads_a_json_answer() {
    let services = ScriptedServices::new();
    services.answer("wallet", |_| ScriptedServices::json(r#"{"id":"r1"}"#));
    let receipt: Receipt = services
        .run(|| wallet().post("/internal/credits", &Credit { amount: 5 }))
        .unwrap();
    assert_eq!(receipt, Receipt { id: "r1".into() });

    let asked = services.requests();
    assert_eq!(asked.len(), 1);
    assert_eq!(
        (asked[0].method.as_str(), asked[0].path.as_str()),
        ("POST", "/internal/credits")
    );
    assert_eq!(asked[0].content_type.as_deref(), Some("application/json"));
    assert_eq!(asked[0].text(), r#"{"amount":5}"#);
    assert_eq!(asked[0].project, None);
}

#[test]
fn every_helper_uses_its_method() {
    let services = ScriptedServices::new();
    services.answer("wallet", |request| match request.method.as_str() {
        "DELETE" => ScriptedServices::status(204, "", ""),
        "GET" if request.path == "/name" => ScriptedServices::text("wallet"),
        _ => ScriptedServices::json(r#"{"id":"r1"}"#),
    });
    services.run(|| {
        let _: Receipt = wallet().get("/receipts/r1").unwrap();
        assert_eq!(wallet().get_text("/name").unwrap(), "wallet");
        let _: Receipt = wallet().put("/receipts/r1", &Credit { amount: 1 }).unwrap();
        wallet().delete("/receipts/r1").unwrap();
    });
    let methods: Vec<String> = services.requests().into_iter().map(|r| r.method).collect();
    assert_eq!(methods, ["GET", "GET", "PUT", "DELETE"]);
}

#[test]
fn the_raw_request_answers_every_status_and_a_typed_helper_fails_outside_2xx() {
    let services = ScriptedServices::new();
    services.answer("wallet", |_| {
        ScriptedServices::status(403, "text/plain", "no")
    });
    services.run(|| {
        let answered = wallet()
            .request("GET", "/internal/credits", RequestOptions::default())
            .unwrap();
        assert_eq!((answered.status, answered.text()), (403, Ok("no")));

        let failed = wallet().get_text("/internal/credits").unwrap_err();
        assert_eq!(
            failed,
            ServiceError::CallFailed {
                service: "wallet".into(),
                status: 403,
                body: b"no".to_vec()
            }
        );
    });
}

#[test]
fn headers_and_a_project_reach_the_request() {
    let services = ScriptedServices::new();
    services.answer("billing/invoices", |_| ScriptedServices::text("ok"));
    let invoices = Services::default()
        .service_in("billing", "invoices")
        .with_headers(&[("X-Request-Id", "r-1")]);
    assert_eq!(invoices.target(), "billing/invoices");
    services.run(|| {
        invoices
            .request(
                "GET",
                "/x",
                RequestOptions {
                    headers: vec![("X-Other".into(), "o".into())],
                    ..Default::default()
                },
            )
            .unwrap()
    });
    let asked = &services.requests()[0];
    assert_eq!(asked.project.as_deref(), Some("billing"));
    assert_eq!(asked.header("x-request-id"), Some("r-1"));
    assert_eq!(asked.header("x-other"), Some("o"));
}

#[test]
fn each_way_a_call_gets_no_answer_is_the_error_of_its_name() {
    let services = ScriptedServices::new();
    services.unresolvable("ledger");
    services.unanswered("slow");
    services.mismatch("impostor");
    services.run(|| {
        let of = |name: &str| Services::default().service(name).get_text("/x").unwrap_err();
        assert!(
            matches!(of("ledger"), ServiceError::Unresolvable { service, .. } if service == "ledger")
        );
        assert!(
            matches!(of("slow"), ServiceError::Unanswered { service, .. } if service == "slow")
        );
        assert!(
            matches!(of("impostor"), ServiceError::IdentityMismatch { service, .. } if service == "impostor")
        );
    });
}

/// A host that answers a call to another service with exactly the reply it was built with.
struct Answering(proto::ServiceReply);

impl NativeHost for Answering {
    fn call(&self, import: Import, _: &[u8]) -> Vec<u8> {
        assert_eq!(import, Import::Request);
        self.0.encode_to_vec()
    }
}

#[test]
fn a_refusal_by_the_runtime_keeps_its_code_and_an_empty_answer_is_a_fault() {
    let refusal = proto::ServiceReply {
        result: Some(proto::service_reply::Result::Error(proto::Error {
            message: "'not a name' is not a name".into(),
            code: proto::ErrorCode::BadRequest as i32,
        })),
    };
    let refused = with_native_host(Answering(refusal), || wallet().get_text("/x")).unwrap_err();
    assert!(
        matches!(&refused, ServiceError::Refused(e) if e.code == ErrorCode::BadRequest && e.message.contains("not a name")),
        "{refused:?}"
    );

    let nothing = proto::ServiceReply { result: None };
    let fault = with_native_host(Answering(nothing), || wallet().get_text("/x")).unwrap_err();
    assert!(
        matches!(&fault, ServiceError::Refused(e) if e.code == ErrorCode::Internal && e.message.contains("with nothing")),
        "{fault:?}"
    );
}

#[test]
fn a_reason_this_crate_does_not_know_is_read_as_unanswered() {
    let unknown = proto::ServiceReply {
        result: Some(proto::service_reply::Result::Failure(
            proto::ServiceFailure {
                reason: 99,
                detail: "something new".into(),
            },
        )),
    };
    let failed = with_native_host(Answering(unknown), || wallet().get_text("/x")).unwrap_err();
    assert!(
        matches!(failed, ServiceError::Unanswered { .. }),
        "{failed:?}"
    );
}

#[test]
fn a_body_over_the_limit_is_refused_before_anything_is_asked() {
    let services = ScriptedServices::new();
    services.answer("wallet", |_| ScriptedServices::text("ok"));
    let refused = services.run(|| {
        wallet().request(
            "POST",
            "/x",
            RequestOptions {
                body: Some(vec![0; MAX_BODY_BYTES + 1]),
                ..Default::default()
            },
        )
    });
    assert!(
        matches!(&refused, Err(ServiceError::Refused(e)) if e.code == ErrorCode::BadRequest && e.message.contains("4000000")),
        "{refused:?}"
    );
    assert_eq!(services.requests(), vec![]);
}

#[test]
#[should_panic(expected = "no script for the service wallet")]
fn a_call_to_a_service_with_no_script_fails_the_test_naming_it() {
    let _ = ScriptedServices::new().run(|| wallet().get_text("/x"));
}

#[test]
fn with_no_runtime_and_no_script_a_call_is_refused_as_unavailable() {
    let refused = wallet().get_text("/x").unwrap_err();
    assert!(
        matches!(&refused, ServiceError::Refused(e) if e.code == ErrorCode::Unavailable),
        "{refused:?}"
    );
}

#[test]
fn a_service_error_becomes_a_command_error_a_handler_can_answer() {
    let unresolvable: CommandError = ServiceError::Unresolvable {
        service: "ledger".into(),
        reason: "no such service".into(),
    }
    .into();
    assert_eq!(unresolvable.code, ErrorCode::Unavailable);
    assert!(unresolvable.message.contains("ledger"));

    let failed: CommandError = ServiceError::CallFailed {
        service: "wallet".into(),
        status: 500,
        body: b"broken".to_vec(),
    }
    .into();
    assert_eq!(failed.code, ErrorCode::Internal);
    assert!(failed.message.contains("500") && failed.message.contains("broken"));
}

#[test]
fn the_import_beneath_the_client_is_public_and_carries_the_request_as_it_was_encoded() {
    // What a handler that goes round the client reaches: the crate does not stop it, and in a
    // module the runtime does.
    let services = ScriptedServices::new();
    services.answer("wallet", |_| ScriptedServices::text("ok"));
    let request = proto::ServiceRequest {
        service: "wallet".into(),
        method: "GET".into(),
        path: "/x".into(),
        ..Default::default()
    };
    let reply = services.run(|| call_request(&request.encode_to_vec()));
    let reply = proto::ServiceReply::decode(reply.as_slice()).unwrap();
    assert!(matches!(
        reply.result,
        Some(proto::service_reply::Result::Response(r)) if r.status == 200
    ));
}

// ── who is given the clients ──

thread_local! {
    static VIEW_HAD_SERVICES: Cell<Option<bool>> = const { Cell::new(None) };
    static STEP_HAD_SERVICES: Cell<Option<bool>> = const { Cell::new(None) };
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
struct Nothing {}

struct Account;

impl KeyValueEntity for Account {
    type State = Nothing;
    const COMPONENT_ID: &'static str = "account";

    fn empty_state(_: &str) -> Nothing {
        Nothing {}
    }

    fn handlers() -> KeyValueHandlers<Account> {
        KeyValueHandlers::new().query("has-services", |_: &Nothing, _: (), ctx: &Context| {
            effects::reply(ctx.services().is_some())
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Earned {
    id: String,
    amount: i64,
}

struct Totals;

impl View for Totals {
    type Row = Earned;
    type Event = Earned;
    const COMPONENT_ID: &'static str = "totals";

    fn source() -> Source {
        Source::topic("earned")
    }

    fn on_event(_: Option<Earned>, event: Earned, ctx: &Context) -> ViewEffect<Earned> {
        VIEW_HAD_SERVICES.with(|c| c.set(Some(ctx.services().is_some())));
        view::update_row(event)
    }
}

/// For each thing earned, credits the wallet service: the consumer the feature exists for.
struct Crediting;

impl Consumer for Crediting {
    type Message = Earned;
    const COMPONENT_ID: &'static str = "crediting";

    fn source() -> Source {
        Source::topic("earned")
    }

    fn on_message(earned: Earned, ctx: &Context) -> ConsumerEffect {
        let wallet = ctx
            .services()
            .expect("a consumer may call another service")
            .service("wallet");
        let _: Receipt = wallet
            .post(
                "/internal/credits",
                &Credit {
                    amount: earned.amount,
                },
            )
            .expect("the wallet answers");
        consumer::done()
    }
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
struct Run {
    status: String,
}

struct Payouts;

impl Workflow for Payouts {
    type State = Run;
    const COMPONENT_ID: &'static str = "payouts";

    fn empty_state(_: &str) -> Run {
        Run::default()
    }

    fn handlers() -> WorkflowHandlers<Payouts> {
        WorkflowHandlers::new().command("start", |_: &Run, _: (), ctx: &Context| {
            let in_command = ctx.services().is_some();
            workflow::update_state(Run {
                status: format!("command-had-services:{in_command}"),
            })
            .transition_to("pay-out")
            .then_reply_value(Done)
        })
    }

    fn steps() -> Steps<Payouts> {
        Steps::new().step("pay-out", |run: &Run, _: (), ctx: &Context| {
            STEP_HAD_SERVICES.with(|c| c.set(Some(ctx.services().is_some())));
            step_effects::update_state(run.clone()).then_end()
        })
    }
}

#[test]
fn an_entity_or_a_view_is_given_no_clients_for_other_services() {
    let mut account = KeyValueEntityTestKit::<Account>::new("a1");
    assert_eq!(
        account.command("has-services", ()).answer.reply::<bool>(),
        Ok(false)
    );

    let mut totals = ViewTestKit::<Totals>::new();
    totals.on_event(
        "e1",
        Earned {
            id: "e1".into(),
            amount: 5,
        },
    );
    assert_eq!(VIEW_HAD_SERVICES.with(|c| c.get()), Some(false));
}

#[test]
fn a_consumer_calls_another_service_with_what_it_read() {
    let services = ScriptedServices::new();
    services.answer("wallet", |_| ScriptedServices::json(r#"{"id":"r1"}"#));
    let kit = ConsumerTestKit::<Crediting>::new();
    services.run(|| {
        kit.on_message(
            "e1",
            Earned {
                id: "e1".into(),
                amount: 7,
            },
        )
    });
    let asked = services.requests();
    assert_eq!(asked.len(), 1);
    assert_eq!(asked[0].text(), r#"{"amount":7}"#);
}

#[test]
fn a_workflow_calls_another_service_in_a_step_and_not_in_a_command() {
    let mut kit = WorkflowTestKit::<Payouts>::new("p1");
    assert_eq!(kit.command("start", ()).reply::<Done>(), Ok(Done));
    assert_eq!(kit.state().status, "command-had-services:false");
    kit.run_step();
    assert_eq!(STEP_HAD_SERVICES.with(|c| c.get()), Some(true));
}

#[test]
fn a_context_has_the_clients_only_when_it_was_built_with_them() {
    let plain = Context::new("c", "e", 0, Metadata::default());
    assert!(plain.services().is_none());
    assert!(plain.with_services().services().is_some());
}

// ── the time and random bytes ──

#[test]
fn the_time_is_the_clock_a_test_fixed_and_not_what_the_metadata_says() {
    // A module built before this read `ankka.now`; the time is asked for now.
    let stamped = Metadata::default().set("ankka.now", "1000");
    let ctx = Context::new("c", "e", 0, stamped);
    let fixed = Instant::from_epoch_millis(1_700_000_000_123);
    assert_eq!(with_clock(fixed, || ctx.now()), fixed);
}

#[test]
fn with_no_clock_fixed_the_time_is_the_machines() {
    let ctx = Context::new("c", "e", 0, Metadata::default());
    let before = Instant::from(std::time::SystemTime::now()).epoch_millis();
    let told = ctx.now().epoch_millis();
    let after = Instant::from(std::time::SystemTime::now()).epoch_millis();
    assert!(before <= told && told <= after, "{before} {told} {after}");
}

#[test]
fn the_clock_is_put_back_when_the_test_is_done_with_it() {
    let ctx = Context::new("c", "e", 0, Metadata::default());
    let fixed = Instant::from_epoch_millis(42);
    assert_eq!(with_clock(fixed, || ctx.now()), fixed);
    assert_ne!(ctx.now(), fixed);
}

#[test]
fn random_bytes_are_the_ones_a_test_fixed_in_order() {
    let ctx = Context::new("c", "e", 0, Metadata::default());
    let (first, second) = with_random(&[1, 2, 3], || {
        let (mut first, mut second) = ([0u8; 4], [0u8; 4]);
        ctx.random(&mut first);
        ctx.random(&mut second);
        (first, second)
    });
    assert_eq!((first, second), ([1, 2, 3, 1], [2, 3, 1, 2]));
}

#[test]
fn with_nothing_fixed_two_fills_differ_and_a_long_buffer_is_filled_throughout() {
    let ctx = Context::new("c", "e", 0, Metadata::default());
    let (mut first, mut second) = ([0u8; 16], [0u8; 16]);
    ctx.random(&mut first);
    ctx.random(&mut second);
    assert_ne!(first, second);
    assert_ne!(first, [0u8; 16]);

    // Longer than the runtime fills in one call, so it is asked for in parts.
    let mut long = vec![0u8; 100_000];
    ctx.random(&mut long);
    for part in long.chunks(1000) {
        assert!(part.iter().any(|b| *b != 0), "a part was left unfilled");
    }
}
