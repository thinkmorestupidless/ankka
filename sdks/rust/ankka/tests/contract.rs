//! A contract's fingerprint: the same document fingerprints the same in every SDK, held to
//! `protocol/fixtures/contracts/fingerprints.json`, which `core`'s `ContractFixturesSuite` writes.

use std::path::PathBuf;

use ankka::Contract;
use ankka::contract::{fingerprint, valid_name};

fn fixtures() -> serde_json::Value {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("protocol/fixtures/contracts/fingerprints.json");
    let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    serde_json::from_slice(&bytes).expect("fingerprints.json is JSON")
}

#[test]
fn every_fixture_row_fingerprints_to_its_value() {
    let rows = fixtures();
    let rows = rows.as_array().expect("an array");
    assert!(!rows.is_empty());
    for row in rows {
        let name = row["name"].as_str().expect("name");
        let expected = row["fingerprint"].as_str().expect("fingerprint");
        // The schema as the fixture holds it, canonical.
        let canonical = serde_json::to_vec(&row["schema"]).expect("bytes");
        let contract =
            Contract::from_bytes(&canonical, name).unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(contract.fingerprint, expected, "{name}");
        // The same document pretty-printed, with serde's key order, which is not the canonical one.
        let pretty = serde_json::to_string_pretty(&row["schema"]).expect("text");
        let reordered = Contract::from_bytes(pretty.as_bytes(), name).expect("a contract");
        assert_eq!(reordered.fingerprint, expected, "{name} after reformatting");
    }
}

#[test]
fn key_order_and_whitespace_do_not_change_the_fingerprint() {
    let a = fingerprint(br#"{"b": 1, "a": {"y": [1, 2], "x": "s"}}"#).unwrap();
    let b = fingerprint(b"{\"a\":{\"x\":\"s\",\"y\":[1,2]},\"b\":1}\n").unwrap();
    assert_eq!(a, b);
    assert!(a.starts_with("sha256:") && a.len() == 7 + 64);
    assert_ne!(
        fingerprint(br#"{"a": 1}"#).unwrap(),
        fingerprint(br#"{"a": 2}"#).unwrap()
    );
}

#[test]
fn a_document_that_is_not_json_is_refused() {
    assert!(fingerprint(b"{not json").is_err());
    assert!(Contract::from_bytes(b"[", "order.v1").is_err());
    assert!(Contract::from_file("/nowhere/order.v1.json", "order.v1").is_err());
}

#[test]
fn the_name_rule() {
    assert!(valid_name("order.v1"));
    assert!(valid_name("cart-events_v2"));
    assert!(!valid_name("Order"));
    assert!(!valid_name(".v1"));
    assert!(!valid_name("a"));
    assert!(Contract::from_bytes(b"{}", "Order").is_err());
}
