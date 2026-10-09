//! The entity, the view and the API with nothing running: milliseconds. Inputs, events, state and
//! replies still round-trip through the codecs, so a shape the codec cannot express fails here
//! rather than on first deployment. With `--features slow`, the module in the real runtime.
//!
//! The owner's email is a personal field, so the tests that write one run inside `with_keyring`:
//! outside a module there is no runtime to fetch a subject's key, and the keyring in memory stands in.

use ankka::prelude::*;
use ankka::testkit::{EndpointTestKit, EventSourcedTestKit, ViewTestKit, with_keyring};
use {{module_snake}}::api::ItemApi;
use {{module_snake}}::domain::{AddItem, Item, ItemEvent, ItemRead, RemoveItem, SetOwner};
use {{module_snake}}::item_entity::ItemEntity;
use {{module_snake}}::item_rows::ItemRows;

fn widget(count: i32) -> AddItem {
    AddItem {
        name: "Widget".into(),
        count,
    }
}

fn ada() -> SetOwner {
    SetOwner {
        user: "u1".into(),
        email: "ada@example.com".into(),
    }
}

#[test]
fn adding_persists_an_event_and_updates_the_state() {
    let mut kit = EventSourcedTestKit::<ItemEntity>::new("i1");
    let added = kit.command("add-item", widget(2));
    assert_eq!(
        added.events,
        vec![ItemEvent::ItemAdded {
            name: "Widget".into(),
            count: 2
        }]
    );
    kit.command("add-item", widget(3));
    let got = kit.command("get-item", ()).reply::<Item>();
    assert_eq!(
        got,
        Ok(Item {
            id: "i1".into(),
            name: "Widget".into(),
            count: 5,
            owner: None
        })
    );
}

#[test]
fn removing_more_than_there_is_is_refused_and_persists_nothing() {
    let mut kit = EventSourcedTestKit::<ItemEntity>::new("i1");
    kit.command("add-item", widget(2));
    let refused = kit.command("remove-item", RemoveItem { count: 3 });
    assert_eq!(refused.error().map(|e| e.code), Some(ErrorCode::Conflict));
    assert!(refused.events.is_empty());
    let removed = kit.command("remove-item", RemoveItem { count: 2 });
    assert_eq!(removed.events, vec![ItemEvent::ItemRemoved { count: 2 }]);
}

#[test]
fn a_count_below_one_is_a_bad_request() {
    let mut kit = EventSourcedTestKit::<ItemEntity>::new("i1");
    let refused = kit.command("add-item", widget(0));
    assert_eq!(refused.error().map(|e| e.code), Some(ErrorCode::BadRequest));
}

#[test]
fn the_owners_email_is_a_personal_field_and_reads_erased_once_its_owner_is_erased() {
    with_keyring(|keyring| {
        let mut kit = EventSourcedTestKit::<ItemEntity>::new("i1");
        assert!(kit.command("set-owner", ada()).persisted());
        let item: Item = kit.command("get-item", ()).reply().unwrap();
        assert_eq!(
            ItemRead::from(item).owner.as_deref(),
            Some("ada@example.com")
        );

        // An applied erasure destroys the owner's key: the event is still there, the email is not.
        keyring.erase("user/u1");
        let item: Item = kit.command("get-item", ()).reply().unwrap();
        assert_eq!(ItemRead::from(item).owner.as_deref(), Some("erased"));
    });
}

#[test]
fn the_view_keeps_one_row_per_item() {
    let mut kit = ViewTestKit::<ItemRows>::new();
    kit.on_event(
        "i1",
        ItemEvent::ItemAdded {
            name: "Widget".into(),
            count: 2,
        },
    );
    kit.on_event("i1", ItemEvent::ItemRemoved { count: 1 });
    let row = kit.row("i1").unwrap();
    assert_eq!((row.name.as_str(), row.count), ("Widget", 1));
}

#[test]
fn the_api_answers_from_the_entity_behind_it() {
    let kit = EndpointTestKit::<ItemApi>::with_service({{module_snake}}::build());
    assert_eq!(kit.post("/items/i1", &widget(2)).status, 204);
    let item: ItemRead = kit.get("/items/i1").json().unwrap();
    assert_eq!(
        item,
        ItemRead {
            id: "i1".into(),
            name: "Widget".into(),
            count: 2,
            owner: None
        }
    );
    assert_eq!(
        kit.post(
            "/items/i2",
            &AddItem {
                name: String::new(),
                count: 1
            }
        )
        .status,
        400
    );
}

#[test]
fn the_api_sets_an_owner_and_answers_their_email() {
    with_keyring(|_| {
        let kit = EndpointTestKit::<ItemApi>::with_service({{module_snake}}::build());
        assert_eq!(kit.post("/items/i1", &widget(1)).status, 204);
        assert_eq!(
            kit.request("PUT", "/items/i1/owner")
                .json(&ada())
                .send()
                .status,
            204
        );
        let item: ItemRead = kit.get("/items/i1").json().unwrap();
        assert_eq!(item.owner.as_deref(), Some("ada@example.com"));
    });
}

/// The whole service through the real runtime: Postgres and the runtime image in Docker, this
/// crate built to a module and loaded into it, and an HTTP client for the routes. Seconds, not
/// milliseconds. The runtime is the one published with the crate's version, pulled on first use;
/// `ANKKA_SIDECAR_IMAGE` names another. The kit starts a keyring beside it, so the owner's email
/// is kept under a real subject key.
#[cfg(feature = "slow")]
mod slow {
    use super::*;
    use ankka::testkit::{AnkkaTestKit, Module};
    use {{module_snake}}::item_rows::ItemRow;

    #[test]
    fn an_item_survives_a_restart_and_is_listed() {
        let mut rt = AnkkaTestKit::start(Module::build().unwrap()).unwrap();
        assert!(
            rt.http()
                .post("/items/i1")
                .json(&widget(2))
                .send()
                .unwrap()
                .status
                < 300
        );
        // A new runtime, the same database: the state is durable, not cached.
        rt.restart().unwrap();
        let item: ItemRead = rt.http().get("/items/i1").send().unwrap().json().unwrap();
        assert_eq!(
            item,
            ItemRead {
                id: "i1".into(),
                name: "Widget".into(),
                count: 2,
                owner: None
            }
        );

        // A view is eventually consistent: retry until the row is there.
        let expected = vec![ItemRow {
            id: "i1".into(),
            name: "Widget".into(),
            count: 2,
        }];
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
        loop {
            let rows: Vec<ItemRow> = rt.http().get("/items/").send().unwrap().json().unwrap();
            if rows == expected || std::time::Instant::now() > deadline {
                assert_eq!(rows, expected);
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(200));
        }
    }

    #[test]
    fn an_owners_email_is_kept_under_the_keyring_and_read_back_after_a_restart() {
        let mut rt = AnkkaTestKit::start(Module::build().unwrap()).unwrap();
        assert!(
            rt.http()
                .post("/items/i1")
                .json(&widget(1))
                .send()
                .unwrap()
                .status
                < 300
        );
        let set = rt
            .http()
            .put("/items/i1/owner")
            .json(&ada())
            .send()
            .unwrap();
        assert!(
            set.status < 300,
            "{} {}",
            set.status,
            String::from_utf8_lossy(&set.body)
        );
        rt.restart().unwrap();
        let item: ItemRead = rt.http().get("/items/i1").send().unwrap().json().unwrap();
        assert_eq!(item.owner.as_deref(), Some("ada@example.com"));
    }
}
