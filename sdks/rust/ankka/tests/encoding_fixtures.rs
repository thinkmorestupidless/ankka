//! Every fixture in `protocol/fixtures` (the crate's copy of the platform's) decodes to its stated
//! value with the codec its manifest and content type select, and re-encodes to the same bytes.
//!
//! A fixture with no matching codec is a failure, never a skip: that is the whole point. The shapes
//! below are the Scala `EncodingShapes` the fixtures were generated from, redeclared in Rust.

use std::collections::BTreeMap;
use std::path::PathBuf;

use ankka::codec::{Auto, Codec, base64};
use ankka::{Bytes, Done, Duration, Instant};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
struct LineItem {
    product_id: String,
    name: String,
    quantity: i32,
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
struct ShoppingCart {
    items: Vec<LineItem>,
    checked_out: bool,
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
#[serde(tag = "type", rename_all_fields = "camelCase")]
enum ShoppingCartEvent {
    ItemAdded { item: LineItem },
    ItemRemoved { product_id: String },
    CheckedOut,
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
struct Step {
    name: String,
    order: i32,
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
struct Plan {
    title: String,
    steps: Vec<Step>,
    labels: BTreeMap<String, String>,
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
#[serde(tag = "type")]
enum Status {
    Ready,
    Failed,
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
struct Service {
    name: String,
    created_at: Instant,
    owner: Option<String>,
    note: Option<String>,
    status: Status,
}

/// A one-case sum type is still a sum type on the wire.
#[derive(Serialize, Deserialize, Debug, PartialEq)]
#[serde(tag = "type")]
enum ServiceEvent {
    Applied {
        generation: i64,
        at: Option<Instant>,
    },
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
struct Numbers {
    big: i64,
    half: f64,
    ten_billion: f64,
    tenth: f64,
    negative: i32,
}

#[derive(Serialize, Deserialize, Debug, PartialEq)]
struct Tree {
    label: String,
    children: Vec<Tree>,
}

struct Fixture {
    name: String,
    manifest: String,
    content_type: String,
    data: Vec<u8>,
    value: Value,
}

fn fixtures() -> Vec<Fixture> {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("protocol/fixtures");
    let mut paths: Vec<PathBuf> = std::fs::read_dir(&dir)
        .unwrap_or_else(|e| {
            panic!(
                "no fixtures under {}: {e}; run scripts/proto.sh",
                dir.display()
            )
        })
        .map(|entry| entry.expect("a directory entry").path())
        .filter(|path| path.extension().is_some_and(|e| e == "json"))
        .collect();
    paths.sort();
    assert!(!paths.is_empty(), "no fixtures under {}", dir.display());
    paths
        .into_iter()
        .map(|path| {
            let text = std::fs::read_to_string(&path).expect("a readable fixture");
            let doc: Value = serde_json::from_str(&text).expect("a fixture is JSON");
            Fixture {
                name: path
                    .file_stem()
                    .expect("a name")
                    .to_string_lossy()
                    .into_owned(),
                manifest: doc["manifest"].as_str().expect("a manifest").to_string(),
                content_type: doc["content_type"]
                    .as_str()
                    .expect("a content type")
                    .to_string(),
                data: base64::decode(doc["bytes_base64"].as_str().expect("bytes")).expect("base64"),
                value: doc["value"].clone(),
            }
        })
        .collect()
}

/// Decodes the fixture as `T` under `manifest`, answering the decoded value's neutral JSON form
/// (through `neutral`) and the bytes it re-encodes to.
fn round_trip<T: Serialize + DeserializeOwned>(
    fixture: &Fixture,
    codec: Auto<T>,
    neutral: impl Fn(&T) -> Value,
) -> (Value, Vec<u8>) {
    assert_eq!(
        codec.manifest(),
        fixture.manifest,
        "{}: the codec's manifest",
        fixture.name
    );
    assert_eq!(
        codec.content_type().as_str(),
        fixture.content_type,
        "{}: the codec's content type",
        fixture.name
    );
    let decoded = codec
        .decode(&fixture.data)
        .unwrap_or_else(|e| panic!("{}: decoding failed: {e}", fixture.name));
    let encoded = codec
        .encode(&decoded)
        .unwrap_or_else(|e| panic!("{}: re-encoding failed: {e}", fixture.name));
    (neutral(&decoded), encoded)
}

fn serde_form<T: Serialize>(value: &T) -> Value {
    serde_json::to_value(value).expect("a serde value")
}

/// The codec a fixture's manifest and content type select, run both ways.
fn run(fixture: &Fixture) -> (Value, Vec<u8>) {
    let json = fixture.content_type == "application/json";
    match (json, fixture.manifest.as_str()) {
        (true, "shopping-cart") => round_trip(
            fixture,
            Auto::<ShoppingCart>::named("shopping-cart"),
            serde_form,
        ),
        (true, "shopping-cart-event") => round_trip(
            fixture,
            Auto::<ShoppingCartEvent>::named("shopping-cart-event"),
            serde_form,
        ),
        (true, "plan") => round_trip(fixture, Auto::<Plan>::named("plan"), serde_form),
        (true, "service") => round_trip(fixture, Auto::<Service>::named("service"), serde_form),
        (true, "service-event") => round_trip(
            fixture,
            Auto::<ServiceEvent>::named("service-event"),
            serde_form,
        ),
        (true, "status") => round_trip(fixture, Auto::<Status>::named("status"), serde_form),
        (true, "numbers") => round_trip(fixture, Auto::<Numbers>::named("numbers"), serde_form),
        (true, "tree") => round_trip(fixture, Auto::<Tree>::named("tree"), serde_form),
        (false, "string") => round_trip(fixture, Auto::<String>::new(), serde_form),
        (false, "int") => round_trip(fixture, Auto::<i32>::new(), serde_form),
        (false, "long") => round_trip(fixture, Auto::<i64>::new(), serde_form),
        (false, "double") => round_trip(fixture, Auto::<f64>::new(), serde_form),
        (false, "boolean") => round_trip(fixture, Auto::<bool>::new(), serde_form),
        (false, "duration-millis") => {
            round_trip(fixture, Auto::<Duration>::new(), |d: &Duration| {
                Value::from(d.to_millis())
            })
        }
        (false, "done") => round_trip(fixture, Auto::<Done>::new(), serde_form),
        (false, "unit") => round_trip(fixture, Auto::<()>::new(), serde_form),
        (false, "bytes") => round_trip(fixture, Auto::<Bytes>::new(), serde_form),
        (false, "option[int]") => round_trip(fixture, Auto::<Option<i32>>::new(), serde_form),
        (_, manifest) => panic!(
            "{}: no codec for manifest {manifest:?} ({}); declare its shape in this file",
            fixture.name, fixture.content_type
        ),
    }
}

#[test]
fn every_fixture_decodes_to_its_value_and_re_encodes_to_its_bytes() {
    let all = fixtures();
    let mut failures = Vec::new();
    for fixture in &all {
        let outcome = std::panic::catch_unwind(|| run(fixture));
        match outcome {
            Err(panic) => {
                let message = panic
                    .downcast_ref::<String>()
                    .cloned()
                    .or_else(|| panic.downcast_ref::<&str>().map(|s| s.to_string()))
                    .unwrap_or_default();
                failures.push(message);
            }
            Ok((value, bytes)) => {
                if value != fixture.value {
                    failures.push(format!(
                        "{}: decoded {value}, expected {}",
                        fixture.name, fixture.value
                    ));
                }
                if bytes != fixture.data {
                    failures.push(format!(
                        "{}: re-encoded {:?}, expected {:?}",
                        fixture.name,
                        String::from_utf8_lossy(&bytes),
                        String::from_utf8_lossy(&fixture.data)
                    ));
                }
            }
        }
    }
    assert!(
        failures.is_empty(),
        "{} of {} fixtures failed:\n{}",
        failures.len(),
        all.len(),
        failures.join("\n")
    );
}

#[test]
fn every_fixture_file_is_one_this_suite_names() {
    // The run above fails on an unknown manifest; this makes the count visible, so a fixture that
    // is added upstream and copied in shows up here as a number that moved.
    let all = fixtures();
    assert_eq!(
        all.len(),
        23,
        "the fixtures changed: {:?}",
        all.iter().map(|f| &f.name).collect::<Vec<_>>()
    );
}
