//! The autonomous agent's parts with nothing running: its tools, its guardrails and its task type's
//! rules, as the runtime calls them. There is no loop and no model here; the runtime runs those.

use ankka::prelude::*;
use ankka::serde_json::json;
use ankka::testkit::AutonomousAgentTestKit;
use shopping_cart::conformance::{Answer, ConformanceAnswerer, answer};

// docs:start autonomous-test
#[test]
fn the_kit_runs_the_tools_the_rules_and_the_guardrails_for_one_task() {
    let kit = AutonomousAgentTestKit::<ConformanceAnswerer>::new("t7")
        .with_service(shopping_cart::conformance::build());
    // The tool acts through the client, against the kit's in-memory entities.
    assert_eq!(
        kit.run_tool("lookup", json!({"id": "c-1"})),
        Ok("count for c-1 is 1".to_string())
    );
    assert_eq!(
        kit.run_tool("lookup", json!({"id": ""})),
        Err("an id is needed".to_string())
    );
    // A result that cites nothing is sent back to the model; one that does is accepted.
    let quiet = Answer {
        answer: "three".into(),
        sources: vec![],
    };
    assert_eq!(
        kit.check_rule(&answer(), "cites-sources", &quiet),
        Verdict::rejected("sources must not be empty")
    );
    let cited = Answer {
        sources: vec!["cart_total".into()],
        ..quiet
    };
    assert_eq!(
        kit.check_rule(&answer(), "cites-sources", &cited),
        Verdict::Accepted
    );
    assert!(
        kit.check_guardrail("no-secrets", Stage::Output, "the key is sk-1")
            .is_err()
    );
}
// docs:end autonomous-test
