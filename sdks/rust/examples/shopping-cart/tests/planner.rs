//! The planner with nothing running: each agent's plan is read from a scripted model, and the
//! workflow's steps are run by the kit, which answers entities but has no agents to call.

use ankka::prelude::*;
use ankka::testkit::{
    AgentTestKit, KeyValueEntityTestKit, ScriptedModel, StepNext, WorkflowTestKit,
};
use shopping_cart::planner::{
    ActivityAgent, ActivityRequest, PlanState, PlannerWorkflow, Preferences, PreferencesEntity,
    SelectorAgent, Start, observe, plan_status,
};

// docs:start planner-test
#[test]
fn the_selector_keeps_its_deliberation_out_of_the_session() {
    let text = r#"{"specialists":["weather","budget"],"reason":"rain and cost"}"#;
    let model = ScriptedModel::new().expect_text(text);
    let mut kit = AgentTestKit::<SelectorAgent>::new("plan-1", model);
    let reply = kit.call("select", "Plan a trip to Lisbon".to_string());
    assert_eq!(
        reply.plan.memory,
        ankka::proto::agent_plan::Memory::None as i32
    );
    assert!(
        reply
            .plan
            .system
            .unwrap()
            .contains("weather, activity, budget")
    );
    assert_eq!(reply.reply.as_deref(), Some(text));
}

#[test]
fn the_activity_agent_reads_the_travellers_preferences_as_context() {
    // With nothing answering the preferences entity, the agent's handler refuses rather than
    // guessing; the kit reports that refusal as the plan's.
    let mut kit = AgentTestKit::<ActivityAgent>::new("plan-1", ScriptedModel::new());
    let request = ActivityRequest {
        user_id: "u-1".into(),
        destination: "Lisbon".into(),
    };
    let refused = kit.call("consult", request);
    assert_eq!(refused.error.map(|e| e.code), Some(ErrorCode::Unavailable));
}

#[test]
fn a_plan_starts_at_the_selection_and_a_step_without_its_agent_fails() {
    let mut kit = WorkflowTestKit::<PlannerWorkflow>::new("plan-1")
        .with_service(shopping_cart::planner::build());
    let start = Start {
        user_id: "u-1".into(),
        destination: "Lisbon".into(),
    };
    assert_eq!(
        kit.command("start", start.clone()).reply::<Done>(),
        Ok(Done)
    );
    assert_eq!(kit.state().status, plan_status::SELECTING);
    // Started twice is a conflict.
    assert_eq!(
        kit.command("start", start).error().map(|e| e.code),
        Some(ErrorCode::Conflict)
    );
    // The selector is an agent, which the unit kit does not answer: the step's call fails and the
    // step fails with it, as it would with a model that could not be reached.
    match kit.run_step() {
        StepNext::Fail(e) => assert_eq!(e.code, ErrorCode::Unavailable),
        other => panic!("the step ran without its agent: {other:?}"),
    }
}
// docs:end planner-test

#[test]
fn preferences_are_kept_under_the_travellers_id() {
    let mut kit = KeyValueEntityTestKit::<PreferencesEntity>::new("u-1");
    let set = Preferences {
        user_id: "someone-else".into(),
        likes: vec!["museums".into()],
        dislikes: Vec::new(),
        max_budget: 120,
    };
    assert_eq!(kit.command("set", set).answer.reply::<Done>(), Ok(Done));
    let kept = kit.state();
    assert_eq!(kept.user_id, "u-1");
    assert_eq!(kept.summary(), "likes museums; budget 120");
}

/// Answers the plan query and the standing, as the runtime would for a run that has ended.
struct Ended;

impl ankka::abi::imports::NativeHost for Ended {
    fn call(&self, import: ankka::abi::imports::Import, _: &[u8]) -> Vec<u8> {
        use ankka::abi::imports::Import;
        use ankka::proto;
        use prost::Message;
        match import {
            Import::Invoke => proto::InvokeReply {
                result: Some(proto::invoke_reply::Result::Reply(proto::outcome::Reply {
                    payload: ankka::codec::encode_payload(&PlanState {
                        status: plan_status::COMPLETED.to_string(),
                        summary: Some("Lisbon in the rain".into()),
                        ..PlanState::empty()
                    })
                    .ok(),
                    metadata: None,
                })),
            }
            .encode_to_vec(),
            Import::Lifecycle => proto::LifecycleReply {
                result: Some(proto::lifecycle_reply::Result::Lifecycle(
                    proto::WorkflowLifecycle {
                        status: proto::WorkflowStatus::Completed as i32,
                        ..Default::default()
                    },
                )),
            }
            .encode_to_vec(),
            other => panic!("unexpected {other:?}"),
        }
    }
}

#[test]
fn a_run_is_observed_as_its_plan_and_as_the_engines_standing() {
    let (state, lifecycle) = ankka::abi::imports::with_native_host(Ended, || {
        observe(&ankka::Client::default(), "plan-1")
    })
    .unwrap();
    assert_eq!(state.status, plan_status::COMPLETED);
    assert!(lifecycle.is_completed());
}
