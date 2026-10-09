//! Personal fields against `protocol/fixtures/personal`: the envelopes the Scala codec wrote open
//! here, an envelope written here opens there (the same key, cipher, associated data and lookup
//! token), and the rules on a subject, an erased one and a corrupt envelope hold. The runtime's
//! `subject_key` and `lookup_token` imports are answered by a native host holding the fixtures' keys.

use std::path::PathBuf;

use ankka::abi::imports::{Import, NativeHost, with_native_host};
use ankka::personal::{Personal, allowing_lookup, check_subject};
use ankka::proto;
use prost::Message;
use serde::{Deserialize, Serialize};
use serde_json::Value;

fn fixtures() -> PathBuf {
    let copied = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("protocol/fixtures/personal");
    if copied.is_dir() {
        copied
    } else {
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../protocol/fixtures/personal")
    }
}

fn json(name: &str) -> Value {
    serde_json::from_str(&std::fs::read_to_string(fixtures().join(name)).expect("the fixture"))
        .expect("JSON")
}

fn decode_b64(text: &str) -> Vec<u8> {
    ankka::codec::base64::decode(text).expect("base64")
}

/// The fixtures' keyring: one key for brand's subjects and payments', `player/forgotten` destroyed.
struct Keys {
    key: Vec<u8>,
    lookup_key: Vec<u8>,
    destroyed: String,
    erased: std::cell::RefCell<Vec<String>>,
}

impl NativeHost for Keys {
    fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
        match import {
            Import::SubjectKey => {
                let fetch = proto::KeyFetch::decode(request).expect("a KeyFetch");
                let project = if fetch.project.is_empty() {
                    "brand".to_string()
                } else {
                    fetch.project
                };
                let gone = fetch.subject == self.destroyed
                    || self.erased.borrow().contains(&fetch.subject);
                let result = if gone {
                    proto::subject_key_reply::Result::Refused(proto::SubjectRefused {
                        subject: fetch.subject,
                        project,
                        reason: "erased".into(),
                        unavailable: false,
                    })
                } else {
                    proto::subject_key_reply::Result::Key(proto::SubjectKey {
                        subject: fetch.subject,
                        project,
                        key: self.key.clone(),
                        expires_millis: 300_000,
                    })
                };
                proto::SubjectKeyReply {
                    result: Some(result),
                }
                .encode_to_vec()
            }
            Import::LookupToken => {
                let r = proto::LookupTokenRequest::decode(request).expect("a LookupTokenRequest");
                proto::LookupTokenReply {
                    result: Some(proto::lookup_token_reply::Result::Token(hmac_hex(
                        &self.lookup_key,
                        &r.plaintext,
                    ))),
                }
                .encode_to_vec()
            }
            other => panic!("{other:?} is not asked of this host"),
        }
    }
}

/// HMAC-SHA-256 as the runtime makes a lookup token, for the stand-in host only.
fn hmac_hex(key: &[u8], data: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    let mut k = [0u8; 64];
    k[..key.len()].copy_from_slice(key);
    let mut inner = Sha256::new();
    inner.update(k.iter().map(|b| b ^ 0x36).collect::<Vec<u8>>());
    inner.update(data);
    let mut outer = Sha256::new();
    outer.update(k.iter().map(|b| b ^ 0x5c).collect::<Vec<u8>>());
    outer.update(inner.finalize());
    outer
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

fn keys() -> Keys {
    let k = json("keys.json");
    Keys {
        key: decode_b64(k["subjectKey"].as_str().unwrap()),
        lookup_key: decode_b64(k["lookupKey"].as_str().unwrap()),
        destroyed: k["destroyedSubject"].as_str().unwrap().to_string(),
        erased: Default::default(),
    }
}

fn rows() -> Vec<Value> {
    json("envelopes.json").as_array().unwrap().clone()
}

#[test]
fn every_scala_envelope_opens_reads_erased_or_is_refused_as_its_row_says() {
    with_native_host(keys(), || {
        for row in rows() {
            let name = row["name"].as_str().unwrap();
            let read: Result<Personal<Value>, _> = serde_json::from_value(row["envelope"].clone());
            match row["expect"].as_str().unwrap() {
                "value" => {
                    let read = read.unwrap_or_else(|e| panic!("{name}: {e}"));
                    let expected: Value =
                        serde_json::from_str(row["plaintext"].as_str().unwrap()).unwrap();
                    assert_eq!(read.as_ref(), Some(&expected), "{name}");
                    assert_eq!(read.subject(), row["subject"].as_str().unwrap(), "{name}");
                }
                "erased" => assert!(read.expect(name).is_erased(), "{name}"),
                "corrupt" => {
                    let e = read.err().unwrap_or_else(|| panic!("{name} opened"));
                    assert!(e.to_string().contains("corrupt"), "{name}: {e}");
                }
                other => panic!("{name}: unknown expectation {other}"),
            }
        }
    });
}

#[test]
fn an_envelope_written_here_opens_and_carries_the_scala_lookup_token_in_a_view_row() {
    let expected = rows().into_iter().find(|r| r["name"] == "lookup").unwrap()["lookup"].clone();
    with_native_host(keys(), || {
        let value = Personal::lookup("player/8c1f", "ada@example.com".to_string()).unwrap();
        let written = allowing_lookup(|| serde_json::to_value(&value)).unwrap();
        assert_eq!(written["project"], "brand");
        assert_eq!(written["lookup"], expected);
        let outside = serde_json::to_value(&value).unwrap();
        assert!(
            outside.get("lookup").is_none(),
            "no token outside a view row"
        );
        let read: Personal<String> = serde_json::from_value(written).unwrap();
        assert_eq!(read.as_ref().map(String::as_str), Some("ada@example.com"));
    });
}

#[derive(Serialize, Deserialize)]
struct Profile {
    email: Personal<String>,
    currency: String,
}

#[test]
fn a_record_round_trips_and_its_plaintext_never_reaches_the_bytes() {
    with_native_host(keys(), || {
        let profile = Profile {
            email: Personal::present("player/8c1f", "ada@example.com".to_string()).unwrap(),
            currency: "GBP".into(),
        };
        let bytes = serde_json::to_vec(&profile).unwrap();
        let text = String::from_utf8(bytes.clone()).unwrap();
        assert!(!text.contains("ada@example.com") && text.contains("\"currency\":\"GBP\""));
        let read: Profile = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(
            read.email.as_ref().map(String::as_str),
            Some("ada@example.com")
        );
    });
}

#[test]
fn a_fresh_value_for_an_erased_subject_is_refused_and_a_stored_one_written_erased() {
    let host = keys();
    host.erased.borrow_mut().push("player/77a0".into());
    with_native_host(host, || {
        let fresh = Personal::present("player/77a0", "x".to_string()).unwrap();
        assert!(serde_json::to_value(&fresh).is_err());
        let refusal = ankka::personal::take_refusal().expect("a refusal");
        assert!(refusal.message.contains("erased"), "{}", refusal.message);
        let stored = Personal::Present {
            subject: "player/77a0".to_string(),
            value: "x".to_string(),
            lookup: false,
            project: None,
            stored: std::cell::Cell::new(true),
        };
        assert_eq!(
            serde_json::to_value(&stored).unwrap(),
            serde_json::json!({"subject": "player/77a0", "project": "brand"})
        );
    });
}

#[test]
fn no_runtime_refuses_a_present_value() {
    let value = Personal::present("player/8c1f", "x".to_string()).unwrap();
    assert!(serde_json::to_value(&value).is_err());
    assert!(ankka::personal::take_refusal().is_some());
}

#[test]
fn a_subject_is_checked_and_a_value_never_printed() {
    for bad in ["", "player 8c1f", "pläyer"] {
        assert!(check_subject(bad).is_err(), "{bad:?}");
    }
    assert!(check_subject(&"a".repeat(254)).is_err());
    let value = Personal::present("player/8c1f", "ada@example.com".to_string()).unwrap();
    assert!(!format!("{value:?}").contains("ada@example.com"));
}
