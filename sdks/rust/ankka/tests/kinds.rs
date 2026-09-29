//! Every kind beside the event sourced entity, through its unit testkit: what each decides, with
//! its values crossing its codecs as they would on the wire.

use ankka::effects::{agent, consumer, view, workflow};
use ankka::prelude::*;
use ankka::testkit::{
    AgentTestKit, ConsumerTestKit, KeyValueEntityTestKit, ScriptedModel, StepNext,
    TimedActionTestKit, ViewTestKit, WorkflowTestKit,
};
use serde_json::json;

// ── a key value entity ──

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
struct Profile {
    name: String,
}

struct Profiles;

impl KeyValueEntity for Profiles {
    type State = Profile;
    const COMPONENT_ID: &'static str = "profile";

    fn empty_state(_: &str) -> Profile {
        Profile::default()
    }

    fn handlers() -> KeyValueHandlers<Profiles> {
        KeyValueHandlers::new()
            .command("set", |_: &Profile, name: String, _: &Context| {
                if name.is_empty() {
                    return effects::error(ErrorCode::BadRequest, "a name is needed").into();
                }
                effects::update_state(Profile { name }).then_reply(|p: &Profile| p.name.clone())
            })
            .query("get", |p: &Profile, _: (), _: &Context| {
                effects::reply(p.name.clone())
            })
            .command("delete", |_: &Profile, _: (), _: &Context| {
                effects::delete_state().then_reply_value(Done)
            })
    }
}

#[test]
fn a_key_value_entity_replaces_its_state_and_forgets_it_when_deleted() {
    let mut kit = KeyValueEntityTestKit::<Profiles>::new("p1");
    let set = kit.command("set", "Ada".to_string());
    assert!(set.written);
    assert_eq!(set.answer.reply::<String>(), Ok("Ada".to_string()));
    assert_eq!(kit.state().name, "Ada");
    let refused = kit.command("set", String::new());
    assert!(!refused.written);
    assert_eq!(
        refused.answer.error().map(|e| e.code),
        Some(ErrorCode::BadRequest)
    );
    assert_eq!(kit.state().name, "Ada", "a refusal changes nothing");
    kit.command("delete", ());
    assert_eq!(kit.state(), Profile::default());
}

// ── a workflow ──

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Job {
    status: String,
}

struct Jobs;

impl Workflow for Jobs {
    type State = Job;
    const COMPONENT_ID: &'static str = "jobs";

    fn empty_state(_: &str) -> Job {
        Job {
            status: "new".into(),
        }
    }

    fn handlers() -> WorkflowHandlers<Jobs> {
        WorkflowHandlers::new()
            .command("start", |_: &Job, pause: bool, _: &Context| {
                workflow::update_state(Job {
                    status: "started".into(),
                })
                .transition_to_with("first", pause)
                .then_reply_value(Done)
            })
            .query("status", |job: &Job, _: (), _: &Context| {
                effects::reply(job.status.clone())
            })
    }

    fn steps() -> Steps<Jobs> {
        Steps::new()
            .step("first", |_: &Job, pause: bool, _: &Context| {
                let first = step_effects::update_state(Job {
                    status: "first".into(),
                });
                if pause {
                    first.then_pause_for(Duration::of_millis(10), "last")
                } else {
                    first.then_transition_to("last")
                }
            })
            .step("last", |_: &Job, _: (), _: &Context| {
                step_effects::update_state(Job {
                    status: "done".into(),
                })
                .then_end()
            })
    }

    fn settings() -> WorkflowSettings {
        WorkflowSettings::new().step_recovery("first", Recovery::retries(1).failover_to("last"))
    }
}

#[test]
fn a_workflow_runs_its_steps_from_the_input_its_command_handed_over() {
    let mut kit = WorkflowTestKit::<Jobs>::new("j1");
    assert_eq!(kit.command("start", false).reply::<Done>(), Ok(Done));
    assert_eq!(kit.run_step(), StepNext::TransitionTo("last".into()));
    assert_eq!(kit.run_step(), StepNext::End);
    assert_eq!(kit.state().status, "done");
    assert_eq!(
        kit.command("status", ()).reply::<String>(),
        Ok("done".into())
    );
}

#[test]
fn a_paused_workflow_goes_on_when_its_timeout_would_end_the_pause() {
    let mut kit = WorkflowTestKit::<Jobs>::new("j2");
    kit.command("start", true);
    assert_eq!(
        kit.run_until_pause(),
        StepNext::Pause {
            after_millis: Some(10),
            on_timeout: Some("last".into())
        }
    );
    assert_eq!(kit.state().status, "first");
    assert_eq!(kit.run_to_end(), StepNext::End);
    assert_eq!(kit.state().status, "done");
}

#[test]
fn a_workflow_declares_its_steps_and_the_recovery_the_runtime_applies() {
    let spec = Service::new("test")
        .register(Jobs)
        .discover(&Default::default())
        .spec
        .unwrap();
    let Some(ankka::proto::component::Detail::Workflow(detail)) = &spec.components[0].detail else {
        panic!("not a workflow")
    };
    assert_eq!(detail.steps, vec!["first", "last"]);
    let recovery = detail.settings.as_ref().unwrap().steps[0]
        .recovery
        .as_ref()
        .unwrap();
    assert_eq!(
        (recovery.max_retries, recovery.failover_to.as_deref()),
        (1, Some("last"))
    );
}

// ── a view ──

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
enum Counted {
    Added { by: i32 },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Total {
    id: String,
    total: i32,
}

struct Totals;

impl View for Totals {
    type Row = Total;
    type Event = Counted;
    const COMPONENT_ID: &'static str = "totals";

    fn source() -> Source {
        Source::topic("counts")
    }

    fn on_event(row: Option<Total>, event: Counted, ctx: &Context) -> ViewEffect<Total> {
        let id = ctx.metadata().subject().unwrap_or_default().to_string();
        let Counted::Added { by } = event;
        let total = row.map_or(0, |r| r.total) + by;
        view::update_row(Total { id, total })
    }
}

#[test]
fn a_view_keeps_a_row_per_source_entity_and_drops_it_when_the_entity_goes() {
    let mut kit = ViewTestKit::<Totals>::new();
    kit.on_event("a", Counted::Added { by: 2 });
    kit.on_event("a", Counted::Added { by: 3 });
    kit.on_event("b", Counted::Added { by: 1 });
    assert_eq!(
        kit.row("a"),
        Some(Total {
            id: "a".into(),
            total: 5
        })
    );
    assert_eq!(kit.on_deleted("a"), ViewEffect::DeleteRow);
    assert_eq!(kit.row("a"), None);
    assert_eq!(kit.row("b").map(|r| r.total), Some(1));
}

// ── a consumer and a timed action ──

struct Relay;

impl Consumer for Relay {
    type Message = Counted;
    const COMPONENT_ID: &'static str = "relay";

    fn source() -> Source {
        Source::topic("counts")
    }

    fn produces_to() -> Option<&'static str> {
        Some("big-counts")
    }

    fn on_message(message: Counted, _: &Context) -> ConsumerEffect {
        let Counted::Added { by } = message;
        if by > 10 {
            consumer::produce(by)
        } else {
            consumer::ignore()
        }
    }
}

#[test]
fn a_consumer_decides_per_message() {
    let kit = ConsumerTestKit::<Relay>::new();
    assert!(matches!(
        kit.on_message("a", Counted::Added { by: 1 }),
        ConsumerEffect::Ignore
    ));
    match kit.on_message("a", Counted::Added { by: 11 }) {
        ConsumerEffect::Produce(Ok(payload), _) => assert_eq!(payload.data, b"11"),
        other => panic!("{other:?}"),
    }
    assert!(matches!(kit.on_deleted("a"), ConsumerEffect::Ignore));
}

struct Alarm;

impl TimedAction for Alarm {
    const COMPONENT_ID: &'static str = "alarm";

    fn actions() -> Actions<Alarm> {
        Actions::new().action("ring", |times: i32, _: &Context| {
            if times > 0 {
                Ok(())
            } else {
                Err(CommandError::new(
                    ErrorCode::BadRequest,
                    "ring at least once",
                ))
            }
        })
    }
}

#[test]
fn a_timed_action_succeeds_or_fails_and_is_tried_again() {
    let kit = TimedActionTestKit::<Alarm>::new();
    assert_eq!(kit.fire("ring", 2), Ok(()));
    assert_eq!(
        kit.fire("ring", 0).map_err(|e| e.code),
        Err(ErrorCode::BadRequest)
    );
    assert_eq!(
        kit.fire("snooze", 1).map_err(|e| e.code),
        Err(ErrorCode::NotFound)
    );
}

// ── an agent ──

#[derive(Debug, Deserialize)]
struct Lookup {
    id: String,
}

struct Helper;

impl Agent for Helper {
    const COMPONENT_ID: &'static str = "helper";

    fn handlers() -> AgentHandlers<Helper> {
        AgentHandlers::new().command("ask", |question: String, _: &Context| {
            agent::system_message("Be brief.")
                .user_message(question)
                .tools(["lookup"])
                .guardrails(["no-secrets"])
                .then_reply()
        })
    }

    fn tools() -> Tools<Helper> {
        Tools::new().tool(
            "lookup",
            "Looks an id up.",
            Schema::object().string("id", "what to look up"),
            |args: Lookup, _: &Context| {
                if args.id.is_empty() {
                    Err("an id is needed".into())
                } else {
                    Ok(format!("{} is known", args.id))
                }
            },
        )
    }

    fn guardrails() -> Guardrails<Helper> {
        Guardrails::new().guardrail("no-secrets", |_: Stage, text: &str, _: &Context| {
            if text.contains("sk-") {
                Err("a key leaked".into())
            } else {
                Ok(())
            }
        })
    }
}

#[test]
fn an_agent_plans_and_the_loop_runs_its_tools_against_the_script() {
    let model = ScriptedModel::new()
        .expect_tool_call("lookup", json!({"id": "x"}))
        .expect_tool_call("lookup", json!({"id": ""}))
        .expect_text("x is known");
    let mut kit = AgentTestKit::<Helper>::new("s1", model);
    let reply = kit.call("ask", "what is x?".to_string());
    assert_eq!(reply.plan.system.as_deref(), Some("Be brief."));
    assert_eq!(
        reply.tool_results,
        vec!["x is known", "error: an id is needed"]
    );
    assert_eq!(reply.reply.as_deref(), Some("x is known"));
}

#[test]
fn a_guardrail_blocks_what_it_should() {
    let mut kit =
        AgentTestKit::<Helper>::new("s2", ScriptedModel::new().expect_text("the key is sk-1"));
    let reply = kit.call("ask", "key?".to_string());
    assert_eq!(reply.error.map(|e| e.code), Some(ErrorCode::Forbidden));
    let mut blocked = AgentTestKit::<Helper>::new("s3", ScriptedModel::new());
    assert!(
        blocked
            .call("ask", "sk-in the question".to_string())
            .error
            .is_some()
    );
}

#[test]
fn an_agent_declares_its_tools_with_their_schemas() {
    let spec = Service::new("test")
        .register(Helper)
        .discover(&Default::default())
        .spec
        .unwrap();
    let Some(ankka::proto::component::Detail::Agent(detail)) = &spec.components[0].detail else {
        panic!("not an agent")
    };
    let schema: serde_json::Value =
        serde_json::from_str(&detail.tools[0].input_schema_json).unwrap();
    assert_eq!(schema["required"], json!(["id"]));
    assert_eq!(detail.guardrails, vec!["no-secrets"]);
}
