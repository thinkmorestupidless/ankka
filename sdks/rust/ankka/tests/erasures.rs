//! Asking for an erasure request from a Rust module (feature 042): the `AskErasureRequest` the
//! client writes, the answer it reads back, a refusal surfaced with the control plane's code, and
//! which handlers are given the client. The runtime's own answers are the sidecar's.

use ankka::prelude::*;
use ankka::testkit::{ScriptedErasures, with_erasures};

fn subject() -> DataSubject {
    DataSubject::new("player/8c1f").expect("a data subject")
}

/// A context as the runtime builds one for an endpoint: it may call another service.
fn endpoint_context() -> Context {
    Context::new(
        "players",
        "",
        0,
        Metadata::new().set("traceparent", "00-abc-def-01"),
    )
    .with_secrets()
    .with_storage()
    .with_services()
}

#[test]
fn an_ask_writes_the_subject_project_date_reason_correlation_and_the_trace() {
    let erasures = ScriptedErasures::new();
    let ctx = endpoint_context();
    with_erasures(&erasures, || {
        ctx.erasures()
            .expect("an endpoint may ask")
            .ask(
                AskErasure::of(subject())
                    .project("casino")
                    .not_before(LocalDate::parse("2026-12-01").expect("a date"))
                    .reason("the player closed their account")
                    .correlation("ticket-42"),
            )
            .expect("accepted")
    });
    let asked = erasures.asked();
    assert_eq!(asked.len(), 1);
    let request = &asked[0];
    assert_eq!(request.subject, "player/8c1f");
    assert_eq!(request.project, "casino");
    assert_eq!(request.not_before.as_deref(), Some("2026-12-01"));
    assert_eq!(
        request.reason.as_deref(),
        Some("the player closed their account")
    );
    assert_eq!(request.correlation.as_deref(), Some("ticket-42"));
    let trace = request.metadata.as_ref().expect("the call's metadata");
    assert!(
        trace
            .entries
            .iter()
            .any(|e| e.key == "traceparent" && e.value == "00-abc-def-01"),
        "{trace:?}"
    );
}

#[test]
fn a_subject_alone_asks_in_the_services_own_project_with_nothing_else_said() {
    let erasures = ScriptedErasures::new();
    let ctx = endpoint_context();
    with_erasures(&erasures, || ctx.erasures().unwrap().ask(subject())).expect("accepted");
    let request = &erasures.asked()[0];
    assert_eq!(request.project, "", "empty is the service's own project");
    assert_eq!(request.not_before, None);
    assert_eq!(request.reason, None);
    assert_eq!(request.correlation, None);
}

#[test]
fn the_answer_is_read_back_as_the_requests_id_and_state() {
    let erasures = ScriptedErasures::new().answer(ErasureAsked {
        id: "er-7".into(),
        state: "applying".into(),
    });
    let ctx = endpoint_context();
    let asked = with_erasures(&erasures, || ctx.erasures().unwrap().ask(subject()));
    assert_eq!(
        asked,
        Ok(ErasureAsked {
            id: "er-7".into(),
            state: "applying".into()
        })
    );
    // With the script spent, the next ask is held under an id of its own.
    let next = with_erasures(&erasures, || ctx.erasures().unwrap().ask(subject()));
    assert_eq!(
        next.map(|a| (a.id, a.state)),
        Ok(("erasure-2".into(), "held".into()))
    );
}

#[test]
fn a_refusal_for_want_of_the_erasure_grant_is_surfaced_as_forbidden_with_its_words() {
    let erasures = ScriptedErasures::new().refuse(
        ErrorCode::Forbidden,
        "service 'players' holds no erasure grant on project 'casino'",
    );
    let ctx = endpoint_context();
    let refused = with_erasures(&erasures, || {
        ctx.erasures()
            .unwrap()
            .ask(AskErasure::of(subject()).project("casino"))
    })
    .expect_err("refused");
    assert_eq!(refused.code, ErrorCode::Forbidden);
    assert_eq!(
        refused.message,
        "service 'players' holds no erasure grant on project 'casino'"
    );
}

#[test]
fn with_no_script_an_ask_is_unavailable() {
    let ctx = endpoint_context();
    let refused = ctx.erasures().unwrap().ask(subject()).expect_err("refused");
    assert_eq!(refused.code, ErrorCode::Unavailable);
    assert!(
        refused.message.contains("with_erasures"),
        "{}",
        refused.message
    );
}

#[test]
fn an_entitys_context_is_given_no_erasures() {
    let ctx = Context::new("cart", "cart-1", 3, Metadata::new());
    assert!(ctx.erasures().is_none());
}
