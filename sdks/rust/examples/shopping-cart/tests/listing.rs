//! The buckets with nothing running: the kit holds a store in memory, which a test declares
//! buckets on, seeds and reads.

use ankka::prelude::*;
use ankka::storage::BucketExposure;
use ankka::testkit::{EndpointTestKit, EventSourcedTestKit, InMemoryStorage, with_storage};
use shopping_cart::listing::{Described, KeepObject, Listing, ListingState, PhotosEndpoint};

fn png(name: &str) -> KeepObject {
    KeepObject {
        name: name.into(),
        text: "png".into(),
        content_type: "image/png".into(),
    }
}

// docs:start storage-test
#[test]
fn what_a_route_keeps_is_in_the_store_and_what_the_test_seeds_the_route_reads() {
    let store = InMemoryStorage::new().declare("photos", BucketExposure::Public);
    let kit = EndpointTestKit::<PhotosEndpoint>::with_service(shopping_cart::listing::build());
    with_storage(&store, || {
        let kept = kit
            .request("POST", "/storage/photos/l-1")
            .query("bucket", "photos")
            .json(&png("a/b.png"))
            .send();
        assert_eq!(kept.status, 204, "{}", kept.text());
        // The bytes went to the bucket, the name to the entity.
        assert_eq!(
            store.get(Some("photos"), "a/b.png"),
            Some(("image/png".to_string(), b"png".to_vec()))
        );
        let listing: ListingState = kit.get("/storage/photos/l-1").json().unwrap();
        assert_eq!(listing.photos, vec!["a/b.png"]);

        store.seed(Some("photos"), "a/c.png", b"more", "image/png");
        let described: Described = kit
            .request("GET", "/storage/stat")
            .query("bucket", "photos")
            .query("name", "a/c.png")
            .send()
            .json()
            .unwrap();
        assert_eq!(
            (described.content_type.as_str(), described.length),
            ("image/png", 4)
        );
        let listed: Vec<String> = kit
            .request("GET", "/storage/list")
            .query("bucket", "photos")
            .query("prefix", "a/")
            .send()
            .json()
            .unwrap();
        assert_eq!(listed, vec!["a/b.png", "a/c.png"]);
    });
}
// docs:end storage-test

#[test]
fn an_object_is_read_back_under_its_own_content_type_and_gone_once_deleted() {
    let store = InMemoryStorage::new();
    let kit = EndpointTestKit::<PhotosEndpoint>::new();
    with_storage(&store, || {
        assert_eq!(kit.post("/storage/objects", &png("one.png")).status, 204);
        let read = kit
            .request("GET", "/storage/objects")
            .query("name", "one.png")
            .send();
        assert_eq!(
            (
                read.status,
                read.content_type.as_str(),
                read.body.as_slice()
            ),
            (200, "image/png", b"png".as_slice())
        );
        let removed = kit
            .request("DELETE", "/storage/objects")
            .query("name", "one.png")
            .send();
        assert_eq!(removed.status, 204);
        let gone = kit
            .request("GET", "/storage/objects")
            .query("name", "one.png")
            .send();
        assert_eq!(gone.status, 404);
    });
}

#[test]
fn the_listing_records_names_and_never_bytes() {
    let mut kit = EventSourcedTestKit::<Listing>::new("l-1");
    assert_eq!(
        kit.command("record-photo", "a/b.png".to_string())
            .reply::<Done>(),
        Ok(Done)
    );
    assert_eq!(kit.state().photos, vec!["a/b.png"]);
}
