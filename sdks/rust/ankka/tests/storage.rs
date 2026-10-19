//! The storage client as a Rust module sees it: the rules, held to the fixture every SDK reads;
//! the client's contract over the unit test's store, which answers the `storage` import as the
//! runtime does; the bound on a body, both ways; and who is given the client. The runtime's own
//! answers are held by the conformance suite's `storage.*` cases.

use std::cell::Cell;
use std::path::PathBuf;

use ankka::effects::{consumer, view, workflow};
use ankka::prelude::*;
use ankka::storage::{
    BucketExposure, DEFAULT_SIGNED_URL_SECONDS, MAX_BODY_BYTES, MAX_SIGNED_URL_SECONDS,
    bucket_name_problem, name_problem, prefix_problem,
};
use ankka::testkit::{
    ConsumerTestKit, EndpointTestKit, InMemoryStorage, KeyValueEntityTestKit, ViewTestKit,
    WorkflowTestKit, with_storage,
};

thread_local! {
    static VIEW_HAD_STORAGE: Cell<Option<bool>> = const { Cell::new(None) };
    static STEP_HAD_STORAGE: Cell<Option<bool>> = const { Cell::new(None) };
}

// ── the fixture ──

#[derive(serde::Deserialize)]
struct Row {
    unit: String,
    repeat: usize,
    accepted: bool,
    why: String,
    #[serde(default)]
    says: String,
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct SignedUrl {
    max_seconds: i64,
    default_seconds: i64,
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct Rules {
    object_names: Vec<Row>,
    prefixes: Vec<Row>,
    bucket_names: Vec<Row>,
    signed_url: SignedUrl,
}

fn rules() -> Rules {
    let path =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("protocol/fixtures/storage/rules.json");
    serde_json::from_str(&std::fs::read_to_string(path).expect("the rules fixture")).expect("rules")
}

/// Every row's verdict from `rule`, and a refusal's words containing the row's `says`.
fn holds(rows: &[Row], rule: impl Fn(&str) -> Option<String>) -> usize {
    assert!(!rows.is_empty());
    for row in rows {
        let verdict = rule(&row.unit.repeat(row.repeat));
        assert_eq!(verdict.is_none(), row.accepted, "{}: {verdict:?}", row.why);
        if let Some(problem) = verdict {
            assert!(problem.contains(&row.says), "{}: {problem}", row.why);
        }
    }
    rows.len()
}

#[test]
fn every_name_prefix_and_bucket_name_in_the_fixture_gets_its_verdict_and_its_words() {
    let rules = rules();
    let checked = holds(&rules.object_names, name_problem)
        + holds(&rules.prefixes, prefix_problem)
        + holds(&rules.bucket_names, bucket_name_problem);
    assert_eq!(
        checked,
        rules.object_names.len() + rules.prefixes.len() + rules.bucket_names.len()
    );
    assert_eq!(rules.signed_url.max_seconds, MAX_SIGNED_URL_SECONDS);
    assert_eq!(rules.signed_url.default_seconds, DEFAULT_SIGNED_URL_SECONDS);
}

#[test]
fn the_client_refuses_through_the_store_with_the_fixtures_words() {
    let store = InMemoryStorage::new();
    with_storage(&store, || {
        let bucket = storage().default();
        for row in rules().object_names.iter().filter(|r| !r.accepted) {
            let name = row.unit.repeat(row.repeat);
            let refused = bucket.stat(&name).unwrap_err();
            assert!(
                matches!(&refused, StorageError::BadRequest(m) if m.contains(&row.says)),
                "{}: {refused}",
                row.why
            );
        }
        for row in rules().prefixes.iter() {
            let listed = bucket.list(&row.unit.repeat(row.repeat));
            assert_eq!(listed.is_ok(), row.accepted, "{}: {listed:?}", row.why);
        }
    });
}

// ── the contract, over the unit test's store ──

fn storage() -> ankka::Storage {
    Context::new("test", "", 0, Default::default())
        .with_storage()
        .storage()
        .expect("a context given the buckets has them")
}

fn photos() -> InMemoryStorage {
    InMemoryStorage::new().declare("photos", BucketExposure::Public)
}

#[test]
fn an_object_kept_is_read_back_with_its_content_type_and_length() {
    let store = photos();
    with_storage(&store, || {
        let bucket = storage().default();
        bucket.put("notes/a.jpg", b"hello", "image/jpeg").unwrap();
        let got = bucket.get("notes/a.jpg").unwrap().expect("kept");
        assert_eq!(
            (got.content_type.as_str(), got.body.as_slice(), got.length),
            ("image/jpeg", &b"hello"[..], 5)
        );
        assert_eq!(bucket.get("notes/never").unwrap(), None);
        // Replaced whole.
        bucket.put("notes/a.jpg", b"bye", "text/plain").unwrap();
        assert_eq!(bucket.get("notes/a.jpg").unwrap().unwrap().body, b"bye");
    });
    assert_eq!(
        store.get(None, "notes/a.jpg"),
        Some(("text/plain".to_string(), b"bye".to_vec()))
    );
}

#[test]
fn a_listing_answers_the_names_under_a_prefix_in_order() {
    let store = photos();
    with_storage(&store, || {
        let bucket = storage().default();
        for n in ["listed/b", "listed/a", "elsewhere/c"] {
            bucket.put(n, b"x", "image/jpeg").unwrap();
        }
        let names: Vec<String> = bucket
            .list("listed/")
            .unwrap()
            .into_iter()
            .map(|e| e.name)
            .collect();
        assert_eq!(names, vec!["listed/a", "listed/b"]);
        assert_eq!(bucket.list("").unwrap().len(), 3);
    });
}

#[test]
fn a_listing_longer_than_a_page_is_every_page_joined() {
    let store = InMemoryStorage::new();
    for i in 0..2500 {
        store.seed(None, &format!("many/{i:05}"), b"x", "text/plain");
    }
    store.seed(None, "other", b"x", "text/plain");
    with_storage(&store, || {
        let listed = storage().default().list("many/").unwrap();
        assert_eq!(listed.len(), 2500);
        assert_eq!(listed.first().unwrap().name, "many/00000");
        assert_eq!(listed.last().unwrap().name, "many/02499");
        assert!(listed.windows(2).all(|w| w[0].name < w[1].name));
    });
}

#[test]
fn a_stat_answers_the_length_and_content_type_and_nothing_for_no_object() {
    with_storage(&photos(), || {
        let bucket = storage().default();
        bucket.put("stat/one", b"four", "image/png").unwrap();
        let info = bucket.stat("stat/one").unwrap().expect("kept");
        assert_eq!(
            (info.name.as_str(), info.content_type.as_str(), info.length),
            ("stat/one", "image/png", 4)
        );
        assert_eq!(bucket.stat("stat/never").unwrap(), None);
    });
}

#[test]
fn a_second_deletion_removes_nothing_and_is_not_an_error() {
    with_storage(&photos(), || {
        let bucket = storage().default();
        bucket.put("gone/one", b"x", "image/jpeg").unwrap();
        bucket.delete("gone/one").unwrap();
        bucket.delete("gone/one").unwrap();
        assert_eq!(bucket.get("gone/one").unwrap(), None);
    });
}

#[test]
fn a_named_bucket_keeps_its_own_objects() {
    let store = photos();
    with_storage(&store, || {
        let storage = storage();
        storage
            .bucket("photos")
            .put("front.jpg", b"named", "image/jpeg")
            .unwrap();
        assert_eq!(
            storage
                .bucket("photos")
                .get("front.jpg")
                .unwrap()
                .unwrap()
                .body,
            b"named"
        );
        assert_eq!(storage.default().get("front.jpg").unwrap(), None);
        assert_eq!(storage.bucket("photos").name(), Some("photos"));
        assert_eq!(storage.default().name(), None);
    });
    assert_eq!(store.names(Some("photos")), vec!["front.jpg"]);
    assert!(store.names(None).is_empty());
}

#[test]
fn a_bucket_the_service_does_not_have_is_unavailable_naming_its_own() {
    with_storage(&photos(), || {
        let refused = storage().bucket("videos").get("x").unwrap_err();
        let StorageError::Unavailable(message) = &refused else {
            panic!("{refused}")
        };
        assert!(
            message.contains("'videos'") && message.contains("'photos'"),
            "{message}"
        );
        assert_eq!(CommandError::from(refused).code, ErrorCode::Unavailable);
    });
}

#[test]
fn a_public_bucket_answers_an_address_and_the_unnamed_bucket_refuses_one() {
    with_storage(&photos(), || {
        let storage = storage();
        let url = storage
            .bucket("photos")
            .url("listings/42/front.jpg")
            .unwrap();
        assert!(url.ends_with("/listings/42/front.jpg"), "{url}");
        let refused = storage.default().url("front.jpg").unwrap_err();
        assert!(
            matches!(&refused, StorageError::BadRequest(m) if m.contains("public bucket")),
            "{refused}"
        );
    });
}

#[test]
fn a_signed_url_is_made_for_a_signed_bucket_within_seven_days() {
    with_storage(&photos(), || {
        let storage = storage();
        let url = storage
            .default()
            .signed_url("a b.jpg", SignedAccess::Keep, Duration::of_minutes(5))
            .unwrap();
        assert!(url.contains("/a%20b.jpg?access=keep&expires="), "{url}");
        let public = storage
            .bucket("photos")
            .signed_url("a.jpg", SignedAccess::Read, Duration::of_minutes(5))
            .unwrap_err();
        assert!(
            matches!(&public, StorageError::BadRequest(m) if m.contains("signed bucket")),
            "{public}"
        );
        for span in [Duration::ZERO, Duration::of_hours(24 * 7 + 1)] {
            let refused = storage
                .default()
                .signed_url("a.jpg", SignedAccess::Read, span)
                .unwrap_err();
            assert!(
                matches!(&refused, StorageError::BadRequest(m) if m.contains("signed URL")),
                "{refused}"
            );
        }
        assert!(
            storage
                .default()
                .signed_url("a.jpg", SignedAccess::Read, Duration::of_hours(24 * 7))
                .is_ok()
        );
    });
}

// ── the bound, both ways ──

#[test]
fn a_put_above_the_bound_is_too_large_and_nothing_reaches_the_store() {
    let store = InMemoryStorage::new();
    with_storage(&store, || {
        let bucket = storage().default();
        let refused = bucket
            .put(
                "big",
                &vec![0u8; MAX_BODY_BYTES + 1],
                "application/octet-stream",
            )
            .unwrap_err();
        let StorageError::TooLarge(message) = &refused else {
            panic!("{refused}")
        };
        assert!(message.contains("4000000"), "{message}");
        assert_eq!(refused.code(), ErrorCode::BadRequest);
        bucket
            .put(
                "exact",
                &vec![1u8; MAX_BODY_BYTES],
                "application/octet-stream",
            )
            .unwrap();
    });
    assert_eq!(store.names(None), vec!["exact"]);
}

/// A store that refuses to be asked anything: a call that reaches it fails the test.
struct Untouchable;

impl ankka::abi::imports::NativeHost for Untouchable {
    fn call(&self, import: ankka::abi::imports::Import, _: &[u8]) -> Vec<u8> {
        panic!("{import:?} reached the store")
    }
}

#[test]
fn a_put_above_the_bound_makes_no_call() {
    ankka::abi::imports::with_native_storage(Untouchable, || {
        let refused = storage()
            .default()
            .put("big", &vec![0u8; MAX_BODY_BYTES + 1], "image/jpeg")
            .unwrap_err();
        assert!(matches!(refused, StorageError::TooLarge(_)), "{refused}");
    });
}

#[test]
fn a_get_of_an_object_above_the_bound_is_too_large_naming_it() {
    let store = InMemoryStorage::new();
    store.seed(None, "big", &vec![0u8; MAX_BODY_BYTES + 1], "image/jpeg");
    store.seed(None, "exact", &vec![0u8; MAX_BODY_BYTES], "image/jpeg");
    with_storage(&store, || {
        let bucket = storage().default();
        let refused = bucket.get("big").unwrap_err();
        let StorageError::TooLarge(message) = &refused else {
            panic!("{refused}")
        };
        assert!(
            message.contains("4000000") && message.contains(&(MAX_BODY_BYTES + 1).to_string()),
            "{message}"
        );
        assert_eq!(bucket.get("exact").unwrap().unwrap().length, 4_000_000);
        // A stat reads no bytes, so it is not bounded.
        assert_eq!(
            bucket.stat("big").unwrap().unwrap().length,
            MAX_BODY_BYTES as u64 + 1
        );
    });
}

// ── who is given the buckets ──

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
struct Nothing {}

struct Album;

impl KeyValueEntity for Album {
    type State = Nothing;
    const COMPONENT_ID: &'static str = "album";

    fn empty_state(_: &str) -> Nothing {
        Nothing {}
    }

    fn handlers() -> KeyValueHandlers<Album> {
        KeyValueHandlers::new().query("has-storage", |_: &Nothing, _: (), ctx: &Context| {
            effects::reply(ctx.storage().is_some())
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Seen {
    id: String,
}

struct Gallery;

impl View for Gallery {
    type Row = Seen;
    type Event = Seen;
    const COMPONENT_ID: &'static str = "gallery";

    fn source() -> Source {
        Source::topic("seen")
    }

    fn on_event(_: Option<Seen>, event: Seen, ctx: &Context) -> ViewEffect<Seen> {
        VIEW_HAD_STORAGE.with(|c| c.set(Some(ctx.storage().is_some())));
        view::update_row(event)
    }
}

struct Archivist;

impl Consumer for Archivist {
    type Message = Seen;
    const COMPONENT_ID: &'static str = "archivist";

    fn source() -> Source {
        Source::topic("seen")
    }

    fn on_message(message: Seen, ctx: &Context) -> ConsumerEffect {
        let storage = ctx.storage().expect("a consumer has the buckets");
        storage
            .default()
            .put(&format!("seen/{}", message.id), b"kept", "text/plain")
            .unwrap();
        consumer::ignore()
    }
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
struct Run {
    status: String,
}

struct Upload;

impl Workflow for Upload {
    type State = Run;
    const COMPONENT_ID: &'static str = "upload";

    fn empty_state(_: &str) -> Run {
        Run::default()
    }

    fn handlers() -> WorkflowHandlers<Upload> {
        WorkflowHandlers::new().command("start", |_: &Run, _: (), ctx: &Context| {
            let in_command = ctx.storage().is_some();
            workflow::update_state(Run {
                status: format!("command-had-storage:{in_command}"),
            })
            .transition_to("keep")
            .then_reply_value(Done)
        })
    }

    fn steps() -> Steps<Upload> {
        Steps::new().step("keep", |run: &Run, _: (), ctx: &Context| {
            STEP_HAD_STORAGE.with(|c| c.set(Some(ctx.storage().is_some())));
            step_effects::update_state(run.clone()).then_end()
        })
    }
}

struct Photos;

impl Photos {
    fn keep(request: &Request, _: String) -> Result<String, HttpProblem> {
        let storage = request
            .context()
            .storage()
            .ok_or_else(|| HttpProblem::new(500, "an endpoint has the buckets"))?;
        let name = request.path("name");
        storage
            .bucket("photos")
            .put(name, request.body(), request.content_type())?;
        Ok(storage.bucket("photos").url(name)?)
    }
}

impl Endpoint for Photos {
    const ENDPOINT_ID: &'static str = "photos";
    const PREFIX: &'static str = "/photos";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<Photos> {
        Routes::new().post("/{name}", Photos::keep)
    }
}

#[test]
fn an_entity_or_a_view_is_given_no_buckets() {
    let mut album = KeyValueEntityTestKit::<Album>::new("a1");
    assert_eq!(
        album.command("has-storage", ()).answer.reply::<bool>(),
        Ok(false)
    );

    let mut gallery = ViewTestKit::<Gallery>::new();
    gallery.on_event("g1", Seen { id: "g1".into() });
    assert_eq!(VIEW_HAD_STORAGE.with(|c| c.get()), Some(false));
}

#[test]
fn a_consumer_is_given_them_and_with_none_given_keeps_in_the_threads_own_store() {
    let kit = ConsumerTestKit::<Archivist>::new();
    kit.on_message("k1", Seen { id: "k1".into() });
    assert_eq!(
        storage().default().get("seen/k1").unwrap().unwrap().body,
        b"kept"
    );

    let store = InMemoryStorage::new();
    with_storage(&store, || kit.on_message("k2", Seen { id: "k2".into() }));
    assert_eq!(store.names(None), vec!["seen/k2"]);
}

#[test]
fn a_workflow_reaches_its_buckets_in_a_step_and_not_in_a_command() {
    let mut kit = WorkflowTestKit::<Upload>::new("u1");
    assert_eq!(kit.command("start", ()).reply::<Done>(), Ok(Done));
    assert_eq!(kit.state().status, "command-had-storage:false");
    kit.run_step();
    assert_eq!(STEP_HAD_STORAGE.with(|c| c.get()), Some(true));
}

#[test]
fn an_endpoint_keeps_a_body_and_answers_its_public_address() {
    let store = photos();
    let kit = EndpointTestKit::<Photos>::new();
    let answer = with_storage(&store, || {
        kit.request("POST", "/photos/front.jpg")
            .text("a photo")
            .send()
    });
    assert_eq!(answer.status, 200, "{}", answer.text());
    assert!(
        answer.text().ends_with("/photos/front.jpg"),
        "{}",
        answer.text()
    );
    assert_eq!(
        store.get(Some("photos"), "front.jpg"),
        Some(("text/plain".to_string(), b"a photo".to_vec()))
    );

    let unknown = InMemoryStorage::new();
    let refused = with_storage(&unknown, || {
        kit.request("POST", "/photos/front.jpg")
            .text("a photo")
            .send()
    });
    assert_eq!(refused.status, 503, "{}", refused.text());
}
