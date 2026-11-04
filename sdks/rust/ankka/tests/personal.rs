//! Personal fields and the erasure handler as a Rust module sees them (feature 042): the data
//! subject's rule, held to the fixture every SDK reads; the edge form, held to `edge.json`, as an
//! endpoint's reply and body; a field inside a record sealed and opened by the host under the
//! record's manifest, through the native stand-in for `keyring_seal` and `keyring_open` (the
//! envelope's bytes are the runtime's, held by its own suites); the refusals of the SDK contract;
//! and `ankka1_erase`.

use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;

use ankka::abi::exports::Export;
use ankka::abi::imports::{Import, NativeHost, with_native_keyring};
use ankka::codec::{Auto, Codec};
use ankka::personal::{CORRUPT, ERASED, ERROR_KEY, SUBJECT_RULE, UNAVAILABLE, take_refusal};
use ankka::prelude::*;
use ankka::proto;
use ankka::testkit::{
    EndpointTestKit, EventSourcedTestKit, InMemoryKeyring, InMemoryStorage, with_keyring,
    with_storage,
};
use prost::Message;
use serde_json::{Value, json};

fn fixture(name: &str) -> Value {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("protocol/fixtures/personal")
        .join(name);
    serde_json::from_str(&std::fs::read_to_string(path).expect("the fixture")).expect("JSON")
}

fn subject(text: &str) -> DataSubject {
    DataSubject::new(text).expect("a data subject")
}

// ── the data subject ──

#[test]
fn every_subject_in_the_fixture_gets_its_verdict_in_the_fixtures_words() {
    let subjects = fixture("subjects.json");
    assert_eq!(subjects["message"], SUBJECT_RULE);
    for valid in subjects["valid"].as_array().unwrap() {
        let text = valid.as_str().unwrap();
        assert_eq!(subject(text).as_str(), text);
    }
    for invalid in subjects["invalid"].as_array().unwrap() {
        let text = invalid.as_str().unwrap();
        let refused = DataSubject::new(text).expect_err(text);
        assert!(refused.0.ends_with(SUBJECT_RULE), "{refused}");
        let read: Result<DataSubject, _> = serde_json::from_value(invalid.clone());
        assert!(read.is_err(), "{text:?} read as a subject");
    }
}

#[test]
fn a_personal_field_prints_its_subject_and_never_its_value() {
    let field = Personal::present(subject("player/8c1f"), "ada@example.com".to_string());
    assert_eq!(format!("{field}"), "Personal(player/8c1f)");
    assert_eq!(format!("{field:?}"), "Personal(player/8c1f)");
    let inside = format!("{:?}", Some(&field));
    assert!(!inside.contains("ada"), "{inside}");
    let erased: Personal<String> = Personal::Erased {
        subject: subject("player/8c1f"),
    };
    assert_eq!(format!("{erased}"), "Personal(player/8c1f)");
}

#[test]
fn or_else_is_the_value_or_the_default_once_erased() {
    let field = Personal::present(subject("player/8c1f"), "ada".to_string());
    assert_eq!(field.value().map(String::as_str), Some("ada"));
    assert_eq!(field.clone().or_else("gone".into()), "ada");
    let erased: Personal<String> = Personal::Erased {
        subject: subject("player/8c1f"),
    };
    assert!(erased.is_erased());
    assert_eq!(erased.or_else("gone".into()), "gone");
}

// ── the edge ──

fn edge_case(case: &Value) -> Personal<Value> {
    let s = subject(case["subject"].as_str().unwrap());
    match case.get("value") {
        Some(value) => Personal::present(s, value.clone()),
        None => Personal::Erased { subject: s },
    }
}

#[test]
fn outside_a_record_a_field_is_its_edge_form_both_ways() {
    for case in fixture("edge.json")["cases"].as_array().unwrap() {
        let field = edge_case(case);
        assert_eq!(
            serde_json::to_value(&field).unwrap(),
            case["edge"],
            "{}",
            case["name"]
        );
        let read: Personal<Value> = serde_json::from_value(case["edge"].clone()).unwrap();
        assert_eq!(read, field, "{}", case["name"]);
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
struct Contact {
    id: String,
    email: Personal<Value>,
}

struct Contacts;

impl Endpoint for Contacts {
    const ENDPOINT_ID: &'static str = "contacts";
    const PREFIX: &'static str = "/contacts";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<Contacts> {
        Routes::new()
            .get("/{case}", |r: &Request| {
                let cases = fixture("edge.json");
                let index: usize = r.path("case").parse().unwrap_or(usize::MAX);
                let case = cases["cases"]
                    .as_array()
                    .unwrap()
                    .get(index)
                    .cloned()
                    .ok_or_else(|| HttpProblem::new(404, "no such case"))?;
                Ok(Contact {
                    id: "c1".into(),
                    email: edge_case(&case),
                })
            })
            .post("/", |_: &Request, contact: Contact| Ok(contact))
    }
}

#[test]
fn an_endpoint_answers_and_reads_the_edge_form_and_the_keyring_is_never_asked() {
    let keyring = InMemoryKeyring::new();
    let kit = EndpointTestKit::<Contacts>::new();
    with_keyring(&keyring, || {
        for (index, case) in fixture("edge.json")["cases"]
            .as_array()
            .unwrap()
            .iter()
            .enumerate()
        {
            let name = case["name"].as_str().unwrap();
            let answered = kit.get(&format!("/contacts/{index}"));
            assert_eq!(answered.status, 200, "{name}: {answered:?}");
            let body: Value = answered.json().unwrap();
            assert_eq!(body, json!({"id": "c1", "email": case["edge"]}), "{name}");

            let echoed = kit.post("/contacts", &json!({"id": "c2", "email": case["edge"]}));
            assert_eq!(echoed.status, 200, "{name}: {echoed:?}");
            let body: Value = echoed.json().unwrap();
            assert_eq!(body["email"], case["edge"], "{name}");
        }
    });
    assert!(keyring.seals().is_empty() && keyring.opens().is_empty());
}

// ── inside a record ──

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(tag = "type")]
enum PlayerEvent {
    Registered {
        player_id: String,
        email: Personal<String>,
    },
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Default)]
struct PlayerState {
    email: Option<Personal<String>>,
}

#[derive(Serialize, Deserialize)]
struct Register {
    player_id: String,
    email: String,
}

struct Player;

impl EventSourcedEntity for Player {
    type State = PlayerState;
    type Event = PlayerEvent;
    const COMPONENT_ID: &'static str = "player";
    const EVENT_MANIFEST: Option<&'static str> = Some("player-registered");
    const STATE_MANIFEST: Option<&'static str> = Some("player-state");

    fn empty_state(_: &str) -> PlayerState {
        PlayerState::default()
    }

    fn apply(_: PlayerState, event: &PlayerEvent) -> PlayerState {
        match event {
            PlayerEvent::Registered { email, .. } => PlayerState {
                email: Some(email.clone()),
            },
        }
    }

    fn handlers() -> Handlers<Player> {
        Handlers::new().command("register", |_: &PlayerState, r: Register, _: &Context| {
            let s = match DataSubject::new(format!("player/{}", r.player_id)) {
                Ok(s) => s,
                Err(e) => return effects::error(ErrorCode::BadRequest, e.0).into(),
            };
            effects::persist(PlayerEvent::Registered {
                player_id: r.player_id,
                email: Personal::present(s, r.email),
            })
            .then_reply_value(Done)
        })
    }
}

#[test]
fn a_field_in_an_event_is_sealed_by_the_host_under_the_events_manifest() {
    let keyring = InMemoryKeyring::new();
    let mut kit = EventSourcedTestKit::<Player>::new("8c1f");
    let outcome = with_keyring(&keyring, || {
        kit.command(
            "register",
            Register {
                player_id: "8c1f".into(),
                email: "ada@example.com".into(),
            },
        )
    });
    assert_eq!(outcome.reply::<Done>(), Ok(Done));
    let PlayerEvent::Registered { email, .. } = &outcome.events[0];
    assert_eq!(email.value().map(String::as_str), Some("ada@example.com"));

    let seals = keyring.seals();
    let event_seal = seals
        .iter()
        .find(|s| s.manifest == "player-registered")
        .expect("the event's field was sealed under the event's manifest");
    assert_eq!(event_seal.subject, "player/8c1f");
    assert_eq!(event_seal.value, br#""ada@example.com""#);
    assert!(seals.iter().any(|s| s.manifest == "player-state"));
    assert!(
        keyring
            .opens()
            .iter()
            .any(|o| o.manifest == "player-registered")
    );
}

/// The host as the ABI describes it, answering from a fixture: the request is recorded and the
/// envelope given back is the fixture's, byte for byte.
#[derive(Clone, Default)]
struct FixtureHost {
    seals: Rc<RefCell<Vec<proto::KeyringSealRequest>>>,
    opens: Rc<RefCell<Vec<proto::KeyringOpenRequest>>>,
    envelope: Vec<u8>,
    open: Option<proto::keyring_open_reply::Reply>,
}

impl NativeHost for FixtureHost {
    fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
        match import {
            Import::KeyringSeal => {
                self.seals
                    .borrow_mut()
                    .push(proto::KeyringSealRequest::decode(request).unwrap());
                proto::KeyringSealReply {
                    reply: Some(proto::keyring_seal_reply::Reply::Envelope(
                        self.envelope.clone(),
                    )),
                }
                .encode_to_vec()
            }
            Import::KeyringOpen => {
                self.opens
                    .borrow_mut()
                    .push(proto::KeyringOpenRequest::decode(request).unwrap());
                proto::KeyringOpenReply {
                    reply: self.open.clone(),
                }
                .encode_to_vec()
            }
            other => panic!("not a keyring import: {other:?}"),
        }
    }
}

fn first_case() -> Value {
    fixture("envelopes.json")["cases"][0].clone()
}

fn registered(email: Personal<String>) -> PlayerEvent {
    PlayerEvent::Registered {
        player_id: "8c1f".into(),
        email,
    }
}

#[test]
fn the_seal_request_is_the_fields_json_and_the_envelope_is_written_as_the_host_gave_it() {
    let case = first_case();
    let host = FixtureHost {
        envelope: serde_json::to_vec(&case["envelope"]).unwrap(),
        ..Default::default()
    };
    let codec = Auto::<PlayerEvent>::named(case["manifest"].as_str().unwrap().to_string());
    let event = registered(Personal::present(
        subject(case["subject"].as_str().unwrap()),
        "ada@example.com".into(),
    ));
    let bytes = with_native_keyring(host.clone(), || codec.encode(&event)).unwrap();

    let seals = host.seals.borrow();
    let [seal] = seals.as_slice() else {
        panic!("{seals:?}")
    };
    assert_eq!(seal.subject, case["subject"]);
    assert_eq!(seal.manifest, case["manifest"]);
    assert_eq!(seal.value, case["plaintext"].as_str().unwrap().as_bytes());

    let written: Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(written["email"], case["envelope"]);
    let text = String::from_utf8(bytes).unwrap();
    assert!(!text.contains("ada@example.com"), "{text}");
    let e = &case["envelope"];
    let envelope = format!(
        r#"{{"$personal":1,"project":"{}","subject":"{}","c":"{}"}}"#,
        e["project"].as_str().unwrap(),
        e["subject"].as_str().unwrap(),
        e["c"].as_str().unwrap()
    );
    assert!(
        text.contains(&envelope),
        "the keys keep the runtime's order: {text}"
    );
}

#[test]
fn the_open_request_is_the_envelope_under_the_manifest_and_its_value_is_the_field() {
    let case = first_case();
    let host = FixtureHost {
        open: Some(proto::keyring_open_reply::Reply::Value(
            case["plaintext"].as_str().unwrap().as_bytes().to_vec(),
        )),
        ..Default::default()
    };
    let stored = json!({"type": "Registered", "player_id": "8c1f", "email": case["envelope"]});
    let codec = Auto::<PlayerEvent>::named(case["manifest"].as_str().unwrap().to_string());
    let event = with_native_keyring(host.clone(), || {
        codec.decode(&serde_json::to_vec(&stored).unwrap())
    })
    .unwrap();
    assert_eq!(
        event,
        registered(Personal::present(
            subject("player/8c1f"),
            "ada@example.com".into()
        ))
    );
    let opens = host.opens.borrow();
    let [open] = opens.as_slice() else {
        panic!("{opens:?}")
    };
    assert_eq!(open.manifest, case["manifest"]);
    let sent: Value = serde_json::from_slice(&open.envelope).unwrap();
    assert_eq!(sent, case["envelope"]);
}

#[test]
fn an_open_answered_erased_reads_as_erased_and_never_fails() {
    let case = first_case();
    let host = FixtureHost {
        open: Some(proto::keyring_open_reply::Reply::Erased(true)),
        ..Default::default()
    };
    let stored = json!({"type": "Registered", "player_id": "8c1f", "email": case["envelope"]});
    let codec = Auto::<PlayerEvent>::named("player-registered");
    let event = with_native_keyring(host, || codec.decode(&serde_json::to_vec(&stored).unwrap()));
    assert_eq!(
        event.unwrap(),
        registered(Personal::Erased {
            subject: subject("player/8c1f")
        })
    );

    // A field written erased needs no key at all.
    let erased = &fixture("envelopes.json")["erased"][0]["envelope"];
    let stored = json!({"type": "Registered", "player_id": "8c1f", "email": erased});
    let event = codec.decode(&serde_json::to_vec(&stored).unwrap()).unwrap();
    let PlayerEvent::Registered { email, .. } = event;
    assert!(email.is_erased());
}

#[test]
fn an_erased_field_is_written_by_the_host_on_an_instance_that_has_read_nothing() {
    let erased = json!({"$personal": 1, "project": "brand", "subject": "player/8c1f"});
    let host = FixtureHost {
        envelope: serde_json::to_vec(&erased).unwrap(),
        ..Default::default()
    };
    let codec = Auto::<PlayerEvent>::named("player-registered");
    let event = registered(Personal::Erased {
        subject: subject("player/8c1f"),
    });
    let bytes = with_native_keyring(host.clone(), || codec.encode(&event)).unwrap();
    let seals = host.seals.borrow();
    let [seal] = seals.as_slice() else {
        panic!("{seals:?}")
    };
    assert!(seal.erased, "{seal:?}");
    assert!(seal.value.is_empty(), "{seal:?}");
    assert_eq!(seal.manifest, "player-registered");
    let written: Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(written["email"], erased);
}

#[test]
fn an_erased_subject_reads_back_erased_from_the_journal() {
    let keyring = InMemoryKeyring::new();
    let codec = Auto::<PlayerEvent>::named("player-registered");
    let event = registered(Personal::present(subject("player/8c1f"), "ada".into()));
    let stored = with_keyring(&keyring, || codec.encode(&event)).unwrap();
    keyring.erase("player/8c1f");
    let read = with_keyring(&keyring, || codec.decode(&stored)).unwrap();
    assert_eq!(
        read,
        registered(Personal::Erased {
            subject: subject("player/8c1f")
        })
    );
    // Written again, the erased field stays erased: the envelope without its ciphertext.
    let again = with_keyring(&keyring, || codec.encode(&read)).unwrap();
    let again: Value = serde_json::from_slice(&again).unwrap();
    assert_eq!(
        again["email"],
        json!({"$personal": 1, "project": "test", "subject": "player/8c1f"})
    );
}

#[test]
fn a_write_for_an_erased_subject_is_the_erased_refusal_and_persists_nothing() {
    let keyring = InMemoryKeyring::new();
    keyring.erase("player/8c1f");
    let mut kit = EventSourcedTestKit::<Player>::new("8c1f");
    let outcome = with_keyring(&keyring, || {
        kit.command(
            "register",
            Register {
                player_id: "8c1f".into(),
                email: "ada@example.com".into(),
            },
        )
    });
    let error = outcome.error().expect("refused");
    assert_eq!(error.code, ErrorCode::Conflict);
    assert_eq!(
        error.message,
        "the data subject 'player/8c1f' is erased; nothing was written"
    );
    assert_eq!(
        error.details.get(ERROR_KEY).map(String::as_str),
        Some(ERASED)
    );
    assert!(!outcome.persisted());
}

#[test]
fn no_keyring_is_the_unavailable_refusal() {
    let keyring = InMemoryKeyring::new();
    keyring.set_unavailable(true);
    let codec = Auto::<PlayerEvent>::named("player-registered");
    let event = registered(Personal::present(subject("player/8c1f"), "ada".into()));
    let failed = with_keyring(&keyring, || codec.encode(&event));
    assert!(failed.is_err());
    let refusal = take_refusal().expect("the refusal is kept");
    assert_eq!(refusal.code, ErrorCode::Unavailable);
    assert_eq!(
        refusal.details.get(ERROR_KEY).map(String::as_str),
        Some(UNAVAILABLE)
    );
}

#[test]
fn an_envelope_read_under_another_manifest_is_corrupt() {
    let keyring = InMemoryKeyring::new();
    let event = registered(Personal::present(subject("player/8c1f"), "ada".into()));
    let stored = with_keyring(&keyring, || {
        Auto::<PlayerEvent>::named("player-registered").encode(&event)
    })
    .unwrap();
    let read = with_keyring(&keyring, || {
        Auto::<PlayerEvent>::named("player-registered-other").decode(&stored)
    });
    let error = read.expect_err("read under another manifest");
    assert!(error.0.contains("failed authentication"), "{error}");
    let refusal = take_refusal().expect("the refusal is kept");
    assert_eq!(
        refusal.details.get(ERROR_KEY).map(String::as_str),
        Some(CORRUPT)
    );
}

#[test]
fn an_envelope_outside_a_record_is_refused_and_an_unknown_version_too() {
    let envelope = first_case()["envelope"].clone();
    let read: Result<Personal<String>, _> = serde_json::from_value(envelope);
    assert!(read.unwrap_err().to_string().contains("inside a record"));

    let unknown = fixture("envelopes.json")["unknown-version"].clone();
    let stored = json!({"type": "Registered", "player_id": "8c1f", "email": unknown});
    let read = Auto::<PlayerEvent>::named("player-registered")
        .decode(&serde_json::to_vec(&stored).unwrap());
    assert!(read.unwrap_err().0.contains("version 2"));
}

// ── the erasure handler ──

fn erase_call(service: &Service, subject: &str) -> proto::ErasureReply {
    let call = proto::ErasureCall {
        subject: subject.into(),
        erasure_seq: 7,
        rerun: false,
        metadata: None,
    };
    let reply = service.call(Export::Erase, &call.encode_to_vec());
    proto::ErasureReply::decode(reply.as_slice()).unwrap()
}

fn discovered(service: &Service) -> proto::Spec {
    service
        .discover(&proto::SidecarInfo {
            protocol_version: "1.23".into(),
            ..Default::default()
        })
        .spec
        .unwrap()
}

fn kyc() -> Service {
    Service::new("players").erasure_handler(|ctx, subject| {
        let storage = ctx.storage().expect("an erasure handler has the buckets");
        let mut report = storage.bucket("kyc").erase(subject)?;
        if subject.as_str() == "player/slow" {
            report.finality = Finality::After(Duration::of_hours(720));
        }
        Ok(report)
    })
}

#[test]
fn ankka1_erase_runs_the_handler_and_answers_its_count_and_finality() {
    let service = kyc();
    assert!(discovered(&service).erasure_handler);
    let store = InMemoryStorage::new().declare("kyc", ankka::BucketExposure::Private);
    for name in [
        "subjects/player%2F8c1f/passport.jpg",
        "subjects/player%2F8c1f/bill.pdf",
    ] {
        store.seed(Some("kyc"), name, b"x", "application/octet-stream");
    }
    store.seed(
        Some("kyc"),
        "subjects/player%2F41d2/passport.jpg",
        b"x",
        "image/jpeg",
    );

    let reply = with_storage(&store, || erase_call(&service, "player/8c1f"));
    assert_eq!(reply.error, None);
    assert_eq!(reply.objects_erased, 2);
    assert_eq!(reply.finality, "immediate");
    assert_eq!(
        store.names(Some("kyc")),
        vec!["subjects/player%2F41d2/passport.jpg"]
    );

    let again = with_storage(&store, || erase_call(&service, "player/8c1f"));
    assert_eq!((again.objects_erased, again.error), (0, None), "idempotent");

    let slow = with_storage(&store, || erase_call(&service, "player/slow"));
    assert_eq!(slow.finality, "after:PT720H");
}

#[test]
fn a_handler_that_fails_answers_its_error_and_none_answers_an_error() {
    let failing = Service::new("players").erasure_handler(|_, _| {
        Err(CommandError::new(
            ErrorCode::Unavailable,
            "the archive is down",
        ))
    });
    let reply = erase_call(&failing, "player/8c1f");
    assert_eq!(reply.error.unwrap().message, "the archive is down");

    let none = Service::new("players");
    assert!(!discovered(&none).erasure_handler);
    let reply = erase_call(&none, "player/8c1f");
    assert!(reply.error.unwrap().message.contains("no erasure handler"));

    let bad = erase_call(&kyc(), "player 8c1f");
    assert!(bad.error.unwrap().message.contains(SUBJECT_RULE));
}

#[test]
fn an_erasure_handler_is_declared_once_and_refused_by_a_runtime_before_1_23() {
    let twice = kyc().erasure_handler(|_, _| Ok(ErasureReport::immediate(0)));
    assert!(
        twice
            .problems()
            .iter()
            .any(|p| p.message.contains("erasure handler twice"))
    );
    let older = std::panic::catch_unwind(|| {
        kyc().discover(&proto::SidecarInfo {
            protocol_version: "1.22".into(),
            ..Default::default()
        })
    });
    assert!(older.is_err());
}

#[test]
fn finality_reads_and_writes_its_wire_form() {
    assert_eq!(Finality::Immediate.to_wire(), "immediate");
    assert_eq!(
        Finality::After(Duration::of_hours(24)).to_wire(),
        "after:PT24H"
    );
    assert_eq!(
        Finality::parse("after:PT24H"),
        Ok(Finality::After(Duration::of_hours(24)))
    );
    assert_eq!(Finality::parse(""), Ok(Finality::Immediate));
    assert!(Finality::parse("later").is_err());
}
