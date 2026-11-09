//! Personal fields with nothing running: the kit's keyring in memory seals what a handler records
//! and opens what it reads, and a test destroys a subject's key to see the field read back erased.

use ankka::prelude::*;
use ankka::testkit::{EventSourcedTestKit, InMemoryKeyring, with_keyring};
use shopping_cart::conformance::{Contact, PersonalContact, RecordContact};

fn ada() -> RecordContact {
    RecordContact {
        subject: "player/8c1f".into(),
        email: "ada@example.com".into(),
    }
}

// docs:start erasure-test
#[test]
fn an_erased_subjects_field_reads_back_erased_and_the_record_stands() {
    let keyring = InMemoryKeyring::new();
    let mut kit = EventSourcedTestKit::<PersonalContact>::new("c-1");
    let recorded = with_keyring(&keyring, || kit.command("record", ada()));
    assert_eq!(recorded.reply::<String>(), Ok("done".to_string()));
    // The value was sealed under the subject's key, never written as it is.
    let seals = keyring.seals();
    assert!(seals.iter().all(|s| s.subject == "player/8c1f"));
    assert!(seals.iter().any(|s| s.value == br#""ada@example.com""#));

    keyring.erase("player/8c1f"); // destroys the key now
    let read: Contact = with_keyring(&keyring, || kit.command("email", ()))
        .reply()
        .unwrap();
    let email = read.email.expect("the record stands");
    assert!(email.is_erased());
    assert_eq!(email.or_else("erased".to_string()), "erased");
}
// docs:end erasure-test

#[test]
fn a_subject_that_is_not_a_subject_is_refused_before_anything_is_sealed() {
    let keyring = InMemoryKeyring::new();
    let mut kit = EventSourcedTestKit::<PersonalContact>::new("c-2");
    let refused = with_keyring(&keyring, || {
        kit.command(
            "record",
            RecordContact {
                subject: "".into(),
                email: "ada@example.com".into(),
            },
        )
    });
    assert_eq!(refused.error().map(|e| e.code), Some(ErrorCode::BadRequest));
    assert!(keyring.seals().is_empty());
}
