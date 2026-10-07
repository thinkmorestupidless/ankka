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

    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Earliest)
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

// ── a consumer that publishes several messages for one message ──

#[derive(Debug, PartialEq, Serialize, Deserialize)]
struct Line {
    n: i32,
}

struct Fanout;

impl Consumer for Fanout {
    type Message = Counted;
    const COMPONENT_ID: &'static str = "fanout";

    fn source() -> Source {
        Source::topic("counts")
    }

    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Earliest)
    }

    fn produces_to() -> Option<&'static str> {
        Some("lines")
    }

    fn on_message(message: Counted, ctx: &Context) -> ConsumerEffect {
        let Counted::Added { by } = message;
        match by {
            0 => consumer::produce_all([]),
            1 => consumer::produce_all([consumer::message(Line { n: 1 })]),
            -1 => consumer::produce_all([consumer::message(Line { n: 1 }).key("")]),
            -2 => consumer::produce_with(Line { n: 0 }, Metadata::new().set("x-n", "0")),
            _ => consumer::produce_all([
                consumer::message(Line { n: 1 }),
                consumer::message(Line { n: 2 }).key(format!("second:{}", ctx.entity_id())),
                consumer::message(Line { n: 3 }).metadata(Metadata::new().set("x-n", "3")),
            ]),
        }
    }

    fn on_deleted(ctx: &Context) -> ConsumerEffect {
        consumer::produce_all([
            consumer::message(Line { n: -1 }).key(format!("gone:{}", ctx.entity_id()))
        ])
    }
}

/// What a handler's panic said.
fn panic_of<T>(f: impl FnOnce() -> T) -> String {
    let caught = std::panic::catch_unwind(std::panic::AssertUnwindSafe(f));
    let payload = caught.err().expect("it panics");
    payload
        .downcast_ref::<String>()
        .cloned()
        .or_else(|| payload.downcast_ref::<&str>().map(|s| s.to_string()))
        .expect("a panic with a message")
}

#[test]
fn several_messages_keep_their_order_their_keys_and_their_headers() {
    let kit = ConsumerTestKit::<Fanout>::new();
    let effect = kit.on_message("cart-1", Counted::Added { by: 5 });
    assert!(
        matches!(effect, ConsumerEffect::ProduceAll(_)),
        "{effect:?}"
    );
    let messages = ConsumerTestKit::<Fanout>::messages(&effect);
    let lines: Vec<Line> = messages.iter().map(|m| m.read()).collect();
    assert_eq!(lines, vec![Line { n: 1 }, Line { n: 2 }, Line { n: 3 }]);
    // The key named, else none: the runtime keys that one by its subject.
    let keys: Vec<Option<&str>> = messages.iter().map(|m| m.key.as_deref()).collect();
    assert_eq!(keys, vec![None, Some("second:cart-1"), None]);
    let headers: Vec<Option<&str>> = messages.iter().map(|m| m.metadata.get("x-n")).collect();
    assert_eq!(headers, vec![None, None, Some("3")]);
    // Naming a key says nothing about the subject: none of them sets one.
    assert!(messages.iter().all(|m| m.metadata.subject().is_none()));
}

#[test]
fn no_messages_at_all_is_done_and_a_deletion_may_publish_too() {
    let kit = ConsumerTestKit::<Fanout>::new();
    let nothing = kit.on_message("cart-1", Counted::Added { by: 0 });
    assert!(matches!(nothing, ConsumerEffect::Done), "{nothing:?}");
    assert!(ConsumerTestKit::<Fanout>::messages(&nothing).is_empty());
    let gone = ConsumerTestKit::<Fanout>::messages(&kit.on_deleted("cart-1"));
    assert_eq!(gone.len(), 1);
    assert_eq!(gone[0].key.as_deref(), Some("gone:cart-1"));
}

#[test]
fn an_empty_record_key_is_refused_when_the_effect_is_dispatched() {
    let kit = ConsumerTestKit::<Fanout>::new();
    let said = panic_of(|| kit.on_message("cart-1", Counted::Added { by: -1 }));
    assert!(said.contains("consumer 'fanout'"), "{said}");
    assert!(said.contains("a record key must not be empty"), "{said}");
}

#[test]
fn several_messages_are_not_sent_to_a_runtime_that_has_not_said_it_takes_them() {
    // A runtime from before 1.3 says nothing, and would read the reply as no effect at all.
    let silent = ConsumerTestKit::<Fanout>::new().speaking(None);
    assert_eq!(
        panic_of(|| silent.on_message("cart-1", Counted::Added { by: 5 })),
        "this runtime speaks protocol 1.2 or earlier; several messages or a record key need 1.3"
    );
    let earlier = ConsumerTestKit::<Fanout>::new().speaking(Some("1.2"));
    assert_eq!(
        panic_of(|| earlier.on_message("cart-1", Counted::Added { by: 5 })),
        "this runtime speaks protocol 1.2; several messages or a record key need 1.3"
    );
    // One keyed message needs it as much as three do.
    assert_eq!(
        panic_of(|| earlier.on_deleted("cart-1")),
        "this runtime speaks protocol 1.2; several messages or a record key need 1.3"
    );
    // Something that is not a version is not a promise.
    let garbled = ConsumerTestKit::<Fanout>::new().speaking(Some("soon"));
    assert!(panic_of(|| garbled.on_message("c", Counted::Added { by: 5 })).contains("soon"));
    // Later minors and majors take them: the comparison is of numbers, not of text.
    for later in ["1.3", "1.10", "2.0"] {
        let kit = ConsumerTestKit::<Fanout>::new().speaking(Some(later));
        let effect = kit.on_message("cart-1", Counted::Added { by: 5 });
        assert!(matches!(effect, ConsumerEffect::ProduceAll(_)), "{later}");
    }
}

#[test]
fn what_any_runtime_takes_is_answered_the_old_way_whatever_the_runtime() {
    let earlier = ConsumerTestKit::<Fanout>::new().speaking(None);
    // One message that names no key is a single produce.
    let one = earlier.on_message("cart-1", Counted::Added { by: 1 });
    match &one {
        ConsumerEffect::Produce(Ok(payload), _) => assert_eq!(payload.data, br#"{"n":1}"#),
        other => panic!("{other:?}"),
    }
    // No messages is done; a single produce, with its headers, is what it always was.
    let nothing = earlier.on_message("cart-1", Counted::Added { by: 0 });
    assert!(matches!(nothing, ConsumerEffect::Done));
    let with = earlier.on_message("cart-1", Counted::Added { by: -2 });
    let published = ConsumerTestKit::<Fanout>::messages(&with);
    assert_eq!(published.len(), 1);
    assert_eq!(published[0].key, None);
    assert_eq!(published[0].metadata.get("x-n"), Some("0"));
    assert!(matches!(with, ConsumerEffect::Produce(..)));
    // And the consumer that only ever produced one is untouched.
    let relay = ConsumerTestKit::<Relay>::new().speaking(None);
    assert!(matches!(
        relay.on_message("a", Counted::Added { by: 11 }),
        ConsumerEffect::Produce(Ok(_), _)
    ));
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

// ── a recurring timer ──

struct Clock;

impl TimedAction for Clock {
    const COMPONENT_ID: &'static str = "clock";

    fn actions() -> Actions<Clock> {
        // Answers what it was told by failing with it, so a test can read it.
        Actions::new().action("tick", |_: String, ctx: &Context| {
            Err(CommandError::new(
                ErrorCode::Conflict,
                match ctx.due() {
                    Some(due) => format!("due:{}", due.epoch_millis()),
                    None => "no due".to_string(),
                },
            ))
        })
    }
}

#[test]
fn a_timed_action_is_told_the_due_time_it_runs_for() {
    let told = TimedActionTestKit::<Clock>::new()
        .with_metadata("ankka.due", "1767225600000")
        .fire("tick", "c")
        .unwrap_err();
    assert_eq!(told.message, "due:1767225600000");
    let untold = TimedActionTestKit::<Clock>::new()
        .fire("tick", "c")
        .unwrap_err();
    assert_eq!(untold.message, "no due");
}

#[test]
fn due_reads_ankka_due_and_is_none_without_it() {
    let with = Context::new(
        "clock",
        "",
        0,
        Metadata::new().set("ankka.due", "1767225600000"),
    );
    assert_eq!(
        with.due(),
        Some(ankka::Instant::from_epoch_millis(1767225600000))
    );
    assert_eq!(Context::new("clock", "", 0, Metadata::new()).due(), None);
}

mod recurring {
    use std::cell::RefCell;
    use std::rc::Rc;

    use ankka::Client;
    use ankka::Duration;
    use ankka::abi::imports::{Import, NativeHost, with_native_host};
    use ankka::effects::{CommandError, ErrorCode};
    use ankka::proto;
    use prost::Message;

    use super::Clock;

    #[derive(Clone, Default)]
    struct Host {
        seen: Rc<RefCell<Vec<(Import, proto::ScheduleRecurringRequest)>>>,
        error: Option<proto::Error>,
    }

    impl NativeHost for Host {
        fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
            let request = proto::ScheduleRecurringRequest::decode(request).unwrap();
            self.seen.borrow_mut().push((import, request));
            proto::ScheduleRecurringReply {
                error: self.error.clone(),
            }
            .encode_to_vec()
        }
    }

    fn payload(text: &str) -> proto::Payload {
        ankka::codec::encode_payload(&text.to_string()).unwrap()
    }

    fn recur(host: &Host, timer: &str, period: Duration) -> Result<(), CommandError> {
        with_native_host(host.clone(), || {
            Client::default().schedule_recurring(
                timer,
                Duration::ZERO,
                period,
                Clock,
                "tick",
                "x".to_string(),
            )
        })
    }

    #[test]
    fn a_recurring_timer_hands_the_host_its_schedule() {
        let host = Host::default();
        with_native_host(host.clone(), || {
            Client::default().schedule_recurring(
                "recur-a",
                Duration::ZERO,
                Duration::of_seconds(1),
                Clock,
                "tick",
                "a".to_string(),
            )
        })
        .unwrap();
        with_native_host(host.clone(), || {
            Client::default().schedule_recurring_by_name(
                "recur-b",
                Duration::of_seconds(60),
                Duration::of_hours(24),
                "clock",
                "tick",
                "b".to_string(),
            )
        })
        .unwrap();
        let seen = host.seen.borrow();
        assert_eq!(
            *seen,
            vec![
                (
                    Import::ScheduleRecurring,
                    proto::ScheduleRecurringRequest {
                        timer_id: "recur-a".into(),
                        delay_millis: 0,
                        period_millis: 1_000,
                        component_id: "clock".into(),
                        name: "tick".into(),
                        payload: Some(payload("a")),
                    }
                ),
                (
                    Import::ScheduleRecurring,
                    proto::ScheduleRecurringRequest {
                        timer_id: "recur-b".into(),
                        delay_millis: 60_000,
                        period_millis: 86_400_000,
                        component_id: "clock".into(),
                        name: "tick".into(),
                        payload: Some(payload("b")),
                    }
                ),
            ]
        );
    }

    #[test]
    fn a_period_out_of_bounds_is_refused_before_anything_is_sent() {
        let host = Host::default();
        for period in [
            Duration::ZERO,
            Duration::of_millis(-5),
            Duration::of_nanos(999_999),
            Duration::of_millis(36_500 * 86_400_000 + 1),
        ] {
            let refused = recur(&host, "recur-x", period).unwrap_err();
            assert_eq!(refused.code, ErrorCode::BadRequest, "{period}");
            assert!(
                refused.message.contains("'recur-x'")
                    && refused.message.contains("1 millisecond")
                    && refused.message.contains("36500 days"),
                "{}",
                refused.message
            );
        }
        assert!(host.seen.borrow().is_empty());
        // The bounds themselves are accepted.
        recur(&host, "recur-y", Duration::of_millis(1)).unwrap();
        recur(&host, "recur-y", Duration::of_millis(36_500 * 86_400_000)).unwrap();
        assert_eq!(host.seen.borrow().len(), 2);
    }

    #[test]
    fn a_refusal_in_the_reply_is_an_error() {
        let host = Host {
            error: Some(proto::Error {
                message: "the runtime is not bound".into(),
                code: proto::ErrorCode::Unavailable as i32,
            }),
            ..Host::default()
        };
        let refused = recur(&host, "recur-z", Duration::of_seconds(1)).unwrap_err();
        assert_eq!(refused.code, ErrorCode::Unavailable);
        assert_eq!(refused.message, "the runtime is not bound");
    }

    #[test]
    #[should_panic(expected = "there is no ankka runtime outside a module")]
    fn with_no_host_a_recurring_timer_panics_as_schedule_does() {
        let _ = Client::default().schedule_recurring(
            "recur-n",
            Duration::ZERO,
            Duration::of_seconds(1),
            Clock,
            "tick",
            "n".to_string(),
        );
    }
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
