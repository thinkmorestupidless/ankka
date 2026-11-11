//! The samples of the personal data guide, with nothing running: a record with a personal field, a
//! client whose agent calls name a data subject, an erasure handler over a bucket in memory, and an
//! erasure asked from code of a scripted control plane.

use std::cell::RefCell;
use std::rc::Rc;

use ankka::Client;
use ankka::abi::imports::{Import, NativeHost, with_native_host};
use ankka::prelude::*;
use ankka::proto::{self, Kind};
use ankka::testkit::{InMemoryStorage, ScriptedErasures, with_erasures, with_storage};
use prost::Message;

// ── marking a field ──

// docs:start marking
use ankka::{DataSubject, Personal};

#[derive(Serialize, Deserialize)]
struct PlayerRegistered {
    #[serde(rename = "playerId")]
    player_id: String,
    email: Personal<String>,
    currency: String,
}

fn registered(id: &str, email: String) -> Result<PlayerRegistered, CommandError> {
    let subject = DataSubject::new(format!("player/{id}"))?;
    Ok(PlayerRegistered {
        player_id: id.to_string(),
        email: Personal::present(subject, email),
        currency: "EUR".into(),
    })
}
// docs:end marking

#[test]
fn a_personal_field_is_built_from_a_subject_and_printed_without_its_value() {
    let event = registered("8c1f", "ada@example.com".into()).expect("a data subject");
    assert_eq!(event.player_id, "8c1f");
    assert_eq!(event.currency, "EUR");
    assert_eq!(event.email.subject().as_str(), "player/8c1f");
    assert_eq!(
        event.email.value().map(String::as_str),
        Some("ada@example.com")
    );
    assert_eq!(event.email.to_string(), "Personal(player/8c1f)");

    let refused = registered("8c 1f", "ada@example.com".into()).err();
    assert_eq!(refused.map(|e| e.code), Some(ErrorCode::BadRequest));
}

// ── agents ──

/// Keeps every request, and answers each with `noted`.
#[derive(Clone, Default)]
struct Requests(Rc<RefCell<Vec<proto::InvokeRequest>>>);

impl NativeHost for Requests {
    fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
        assert_eq!(import, Import::Invoke);
        self.0
            .borrow_mut()
            .push(proto::InvokeRequest::decode(request).unwrap());
        proto::InvokeReply {
            result: Some(proto::invoke_reply::Result::Reply(proto::outcome::Reply {
                payload: Some(proto::Payload {
                    content_type: "text/plain".into(),
                    manifest: String::new(),
                    data: b"noted".to_vec(),
                }),
                ..Default::default()
            })),
        }
        .encode_to_vec()
    }
}

fn ask_helper(client: &Client) -> Result<String, CommandError> {
    // docs:start agent-subject
    let client = client.with_data_subject(DataSubject::new("player/8c1f")?);
    // every call to an agent made through it names the data subject
    // docs:end agent-subject
    client.invoke_by_name(
        Kind::Agent,
        "helper",
        "support-91",
        "ask",
        "my card was declined",
    )
}

#[test]
fn a_call_to_an_agent_through_the_client_names_the_data_subject() {
    let host = Requests::default();
    let answer = with_native_host(host.clone(), || {
        ask_helper(&Client::with_metadata(Metadata::new()))
    });
    assert_eq!(answer, Ok("noted".to_string()));
    let sent = host.0.borrow();
    let subjects: Vec<&str> = sent[0]
        .metadata
        .iter()
        .flat_map(|m| m.entries.iter())
        .filter(|e| e.key == "ankka-data-subject")
        .map(|e| e.value.as_str())
        .collect();
    assert_eq!(subjects, vec!["player/8c1f"]);
}

// ── the erasure handler ──

fn players() -> Service {
    // docs:start erasure-handler
    Service::new("players").erasure_handler(|ctx, subject| {
        let storage = ctx.storage().expect("an erasure handler has the buckets");
        Ok(storage.bucket("kyc").erase(subject)?)
    })
    // docs:end erasure-handler
}

#[test]
fn the_erasure_handler_erases_the_subjects_objects_and_keeps_the_rest() {
    let service = players();
    assert!(service.has_erasure_handler());
    let store = InMemoryStorage::new().declare("kyc", ankka::BucketExposure::Private);
    store.seed(
        Some("kyc"),
        "subjects/player%2F8c1f/passport.jpg",
        b"x",
        "image/jpeg",
    );
    store.seed(
        Some("kyc"),
        "subjects/player%2F41d2/passport.jpg",
        b"x",
        "image/jpeg",
    );
    let call = || proto::ErasureCall {
        subject: "player/8c1f".into(),
        erasure_seq: 7,
        rerun: false,
        metadata: None,
    };

    let reply = with_storage(&store, || service.erase(call()));
    assert_eq!((reply.objects_erased, reply.error), (1, None));
    assert_eq!(reply.finality, "immediate");
    assert_eq!(
        store.names(Some("kyc")),
        vec!["subjects/player%2F41d2/passport.jpg"]
    );

    let again = with_storage(&store, || service.erase(call()));
    assert_eq!((again.objects_erased, again.error), (0, None), "idempotent");
}

// ── asking for an erasure from code ──

fn close_account(ctx: &Context, subject: DataSubject) -> Result<ErasureAsked, CommandError> {
    // docs:start ask-erasure
    let erasures = ctx.erasures().expect("an endpoint may ask");
    let asked = erasures.ask(AskErasure::of(subject).reason("aml-retention"))?;
    // docs:end ask-erasure
    Ok(asked)
}

#[test]
fn an_endpoint_asks_for_an_erasure_with_its_reason() {
    let erasures = ScriptedErasures::new().answer(ErasureAsked {
        id: "er-7".into(),
        state: "held".into(),
    });
    // A context as the runtime builds one for an endpoint: it may call another service.
    let ctx = Context::new("players", "", 0, Metadata::new()).with_services();
    let subject = DataSubject::new("player/8c1f").unwrap();
    let asked = with_erasures(&erasures, || close_account(&ctx, subject)).expect("accepted");
    assert_eq!(asked.id, "er-7");
    let request = &erasures.asked()[0];
    assert_eq!(request.subject, "player/8c1f");
    assert_eq!(request.reason.as_deref(), Some("aml-retention"));
}
