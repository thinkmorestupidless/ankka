//! The keyed view with nothing running: the kit hands a source handler one change and writes the
//! rows it names to a map the test reads; a declared query is SQL, so the test says what it answers.

use ankka::prelude::*;
use ankka::testkit::KeyedViewTestKit;
use shopping_cart::conformance::{JoinedLeft, JoinedRight, JoinedRows, Noted};

fn noted(text: &str) -> Noted {
    Noted {
        text: text.to_string(),
    }
}

// docs:start keyed-view-test
#[test]
fn the_rights_change_notes_every_row_holding_it_found_by_the_views_own_query() {
    // The query is the database's to run; the test says what it answers.
    let mut kit = KeyedViewTestKit::<JoinedRows>::new().answering("of-right", |values, rows| {
        rows.iter()
            .filter(|row| row.holding == values["holding"])
            .cloned()
            .collect()
    });
    kit.change(Source::of(JoinedLeft), "a", noted("r1|b"));
    kit.change(Source::of(JoinedLeft), "a", noted("r2|b"));
    kit.change(Source::of(JoinedLeft), "a", noted("r3|c"));
    kit.change(Source::of(JoinedRight), "b", noted("anything"));
    assert_eq!(
        kit.row("r1").map(|r| r.notes),
        Some(vec!["left".to_string(), "right".to_string()])
    );
    assert_eq!(
        kit.row("r2").map(|r| r.notes),
        Some(vec!["left".to_string(), "right".to_string()])
    );
    assert_eq!(
        kit.row("r3").map(|r| r.notes),
        Some(vec!["left".to_string()])
    );
}
// docs:end keyed-view-test

#[test]
#[should_panic(expected = "of-right")]
fn a_query_the_test_has_not_answered_fails_the_test_naming_it() {
    let mut kit = KeyedViewTestKit::<JoinedRows>::new();
    kit.change(Source::of(JoinedRight), "b", noted("anything"));
}
