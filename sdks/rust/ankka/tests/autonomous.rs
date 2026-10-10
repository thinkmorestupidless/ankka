//! Autonomous agents: what discovery says about one, what its declaration is refused for, the
//! verdicts `ankka1_check_task_result` answers, the calls the client makes and in what order, and
//! the testkit.

use std::cell::RefCell;
use std::rc::Rc;

use ankka::Client;
use ankka::abi::exports::Export;
use ankka::abi::imports::{Import, NativeHost, with_native_host};
use ankka::client::{Attachment, NewTask};
use ankka::components::ResultCheck;
use ankka::prelude::*;
use ankka::proto::{self, Kind};
use ankka::testkit::AutonomousAgentTestKit;
use prost::Message;
use serde_json::{Value, json};

// ── an autonomous agent ──

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Answer {
    answer: String,
    sources: Vec<String>,
}

fn answer() -> TaskType<Answer> {
    TaskType::new(
        "answer",
        "Answer a question",
        Schema::object()
            .string("answer", "the answer")
            .string_array("sources", "what it used"),
    )
    .rule("cites-sources", |a: &Answer, _: &Context| {
        if a.sources.is_empty() {
            Verdict::rejected("sources must not be empty")
        } else {
            Verdict::Accepted
        }
    })
    .rule("not-shouting", |a: &Answer, _: &Context| {
        if a.answer.chars().any(char::is_lowercase) {
            Verdict::Accepted
        } else {
            Verdict::rejected("no shouting")
        }
    })
    .rule("explodes", |a: &Answer, _: &Context| {
        if a.answer == "boom" {
            panic!("the rule threw")
        }
        Verdict::Accepted
    })
}

fn summary() -> TaskType<String> {
    TaskType::text("summary", "Summarise something")
}

#[derive(Debug, Deserialize)]
struct Lookup {
    id: String,
}

struct Answerer;

impl AutonomousAgent for Answerer {
    const COMPONENT_ID: &'static str = "answerer";
    const DESCRIPTION: &'static str = "Answers questions";
    const INSTRUCTIONS: Option<&'static str> = Some("Be exact.");
    const MODEL: Option<&'static str> = Some("scripted");

    fn accepts() -> Vec<TaskAcceptance> {
        vec![
            TaskAcceptance::new(answer(), 4),
            TaskAcceptance::of(summary()),
        ]
    }

    fn tools() -> Tools<Answerer> {
        Tools::new().tool(
            "lookup",
            "Looks an id up.",
            Schema::object().string("id", "what to look up"),
            |args: Lookup, ctx: &Context| {
                if args.id.is_empty() {
                    return Err("an id is needed".into());
                }
                Ok(format!(
                    "{} is known, for task {}",
                    args.id,
                    ctx.task_id().unwrap_or("none")
                ))
            },
        )
    }

    fn guardrails() -> Guardrails<Answerer> {
        Guardrails::new().guardrail("no-secrets", |stage: Stage, text: &str, _: &Context| {
            if text.contains("sk-") {
                Err(format!("{stage:?} rejected by no-secrets"))
            } else {
                Ok(())
            }
        })
    }

    fn settings() -> Option<AutonomousSettings> {
        Some(
            AutonomousSettings::new()
                .approaching_budget_at(0.5)
                .max_consecutive_failures(2),
        )
    }
}

fn detail(service: Service) -> proto::AutonomousAgentDetail {
    let spec = service.discover(&Default::default()).spec.unwrap();
    let component = spec.components.into_iter().next().unwrap();
    assert_eq!(component.kind, Kind::AutonomousAgent as i32);
    match component.detail {
        Some(proto::component::Detail::AutonomousAgent(d)) => d,
        other => panic!("not an autonomous agent: {other:?}"),
    }
}

// ── rendering ──

#[test]
fn discovery_describes_the_whole_agent() {
    let d = detail(Service::new("test").register(Answerer));
    assert_eq!(d.description, "Answers questions");
    assert_eq!(d.instructions.as_deref(), Some("Be exact."));
    assert_eq!(d.model.as_deref(), Some("scripted"));
    assert_eq!(d.tools.len(), 1);
    assert_eq!(d.tools[0].name, "lookup");
    assert_eq!(d.guardrails, vec!["no-secrets"]);

    let names: Vec<&str> = d.task_types.iter().map(|t| t.name.as_str()).collect();
    assert_eq!(names, vec!["answer", "summary"]);
    let schema: Value =
        serde_json::from_str(d.task_types[0].result_schema_json.as_deref().unwrap()).unwrap();
    assert_eq!(schema["required"], json!(["answer", "sources"]));
    assert_eq!(schema["properties"]["sources"]["type"], json!("array"));
    assert_eq!(
        d.task_types[0].rules,
        vec!["cites-sources", "not-shouting", "explodes"]
    );
    // A type with no result type is text: no schema, and the runtime asks for {"result": "..."}.
    assert_eq!(d.task_types[1].result_schema_json, None);

    let accepts: Vec<(&str, i32)> = d
        .accepts
        .iter()
        .map(|a| (a.task_type.as_str(), a.max_iterations))
        .collect();
    assert_eq!(accepts, vec![("answer", 4), ("summary", 10)]);

    let settings = d.settings.unwrap();
    assert_eq!(settings.approaching_budget_at, Some(0.5));
    assert_eq!(settings.max_consecutive_failures, Some(2));
    assert_eq!(settings.repeated_failure_at, None);
    assert_eq!(settings.dependency_stuck_after_millis, None);
}

// ── validation ──

struct Careless;

impl AutonomousAgent for Careless {
    const COMPONENT_ID: &'static str = "careless";
    const DESCRIPTION: &'static str = "";

    fn accepts() -> Vec<TaskAcceptance> {
        let untidy = TaskType::<Answer>::new("untidy", "", Schema::object())
            .rule("twice", |_: &Answer, _: &Context| Verdict::Accepted)
            .rule("twice", |_: &Answer, _: &Context| Verdict::Accepted);
        vec![
            TaskAcceptance::new(answer(), 0),
            TaskAcceptance::new(answer(), 3),
            TaskAcceptance::of(untidy),
        ]
    }

    fn tools() -> Tools<Careless> {
        Tools::new()
            .tool(
                "complete_task",
                "Completes it.",
                Schema::object(),
                |_: Value, _: &Context| Ok(String::new()),
            )
            .tool("vague", "", Schema::object(), |_: Value, _: &Context| {
                Ok(String::new())
            })
    }
}

struct Idle;

impl AutonomousAgent for Idle {
    const COMPONENT_ID: &'static str = "idle";
    const DESCRIPTION: &'static str = "Does nothing";

    fn accepts() -> Vec<TaskAcceptance> {
        Vec::new()
    }
}

#[test]
fn every_problem_with_a_declaration_is_reported_together() {
    let problems: Vec<String> = Service::new("test")
        .register(Careless)
        .register(Idle)
        .problems()
        .into_iter()
        .map(|p| p.message)
        .collect();
    let expected = [
        "autonomous agent 'careless': a description is required",
        "autonomous agent 'careless': task type 'answer' is accepted 2 times",
        "autonomous agent 'careless': task type 'untidy' needs a description",
        "autonomous agent 'careless': task type 'untidy' declares rule 'twice' twice",
        "autonomous agent 'careless': task type 'answer' needs a budget of at least one iteration",
        "autonomous agent 'careless': tool name 'complete_task' is reserved",
        "autonomous agent 'careless': tool 'vague' has no description; the model decides by it",
        "autonomous agent 'idle': it accepts no task type: return a TaskAcceptance from accepts()",
    ];
    for e in expected {
        assert!(
            problems.iter().any(|p| p == e),
            "missing {e:?} in {problems:#?}"
        );
    }
    assert_eq!(problems.len(), expected.len(), "{problems:#?}");
}

#[test]
fn a_sound_declaration_has_no_problems() {
    assert!(
        Service::new("test")
            .register(Answerer)
            .problems()
            .is_empty()
    );
}

// ── the task result check ──

fn check(task_type: &str, result_json: &str) -> proto::task_result_verdict::Verdict {
    let service = Service::new("test").register(Answerer);
    let request = proto::TaskResultRequest {
        component_id: "answerer".into(),
        task_id: "t1".into(),
        task_type: task_type.into(),
        result_json: result_json.into(),
        metadata: None,
    };
    let reply = service.call(Export::CheckTaskResult, &request.encode_to_vec());
    proto::TaskResultVerdict::decode(reply.as_slice())
        .unwrap()
        .verdict
        .unwrap()
}

#[test]
fn a_result_that_passes_every_rule_is_accepted() {
    use proto::task_result_verdict::Verdict as V;
    assert_eq!(
        check("answer", r#"{"answer":"three","sources":["memory"]}"#),
        V::Accept(proto::Empty {})
    );
    // A text result is a JSON string.
    assert_eq!(check("summary", r#""short""#), V::Accept(proto::Empty {}));
}

#[test]
fn a_result_that_does_not_decode_is_malformed() {
    use proto::task_result_verdict::Verdict as V;
    let V::Malformed(problem) = check("answer", r#"{"answer":1}"#) else {
        panic!("not malformed")
    };
    assert!(problem.contains("invalid type"), "{problem}");
    assert!(matches!(
        check("summary", r#"{"result":"x"}"#),
        V::Malformed(_)
    ));
}

#[test]
fn the_first_rule_to_refuse_is_the_one_reported() {
    use proto::task_result_verdict::{Rejection, Verdict as V};
    assert_eq!(
        check("answer", r#"{"answer":"LOUD","sources":[]}"#),
        V::Reject(Rejection {
            rule: "cites-sources".into(),
            reason: "sources must not be empty".into()
        })
    );
    assert_eq!(
        check("answer", r#"{"answer":"LOUD","sources":["memory"]}"#),
        V::Reject(Rejection {
            rule: "not-shouting".into(),
            reason: "no shouting".into()
        })
    );
}

#[test]
#[should_panic(expected = "the rule threw")]
fn a_rule_that_panics_traps_rather_than_deciding() {
    check("answer", r#"{"answer":"boom","sources":["memory"]}"#);
}

#[test]
fn a_tool_is_run_for_its_task() {
    let service = Service::new("test").register(Answerer);
    let request = proto::ToolRequest {
        component_id: "answerer".into(),
        session_id: "task:t9".into(),
        tool: "lookup".into(),
        arguments_json: r#"{"id":"x"}"#.into(),
        metadata: None,
    };
    let reply = proto::ToolResult::decode(
        service
            .call(Export::InvokeTool, &request.encode_to_vec())
            .as_slice(),
    )
    .unwrap();
    assert_eq!(
        reply.result,
        Some(proto::tool_result::Result::Ok(
            "x is known, for task t9".into()
        ))
    );
}

// ── the client ──

type Call = (i32, String, String, String, Value);
type Answers = Rc<dyn Fn(&str, &str) -> Result<Value, proto::Error>>;

/// Answers the client's calls from a script, keeping each call in order.
#[derive(Clone)]
struct Recorder {
    calls: Rc<RefCell<Vec<Call>>>,
    answer: Answers,
}

impl Recorder {
    fn new(answer: impl Fn(&str, &str) -> Result<Value, proto::Error> + 'static) -> Recorder {
        Recorder {
            calls: Rc::default(),
            answer: Rc::new(answer),
        }
    }

    fn calls(&self) -> Vec<(String, String, String, Value)> {
        self.calls
            .borrow()
            .iter()
            .map(|(_, c, e, n, b)| (c.clone(), e.clone(), n.clone(), b.clone()))
            .collect()
    }
}

impl NativeHost for Recorder {
    fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
        assert_eq!(import, Import::Invoke);
        let request = proto::InvokeRequest::decode(request).unwrap();
        let payload = request.payload.unwrap_or_default();
        assert_eq!(payload.content_type, "application/json");
        let body = if payload.data.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&payload.data).unwrap()
        };
        self.calls.borrow_mut().push((
            request.kind,
            request.component_id.clone(),
            request.entity_id.clone(),
            request.name.clone(),
            body,
        ));
        let result = match (self.answer)(&request.component_id, &request.name) {
            Ok(value) => proto::invoke_reply::Result::Reply(proto::outcome::Reply {
                payload: Some(proto::Payload {
                    content_type: "application/json".into(),
                    manifest: String::new(),
                    data: if value.is_null() {
                        Vec::new()
                    } else {
                        value.to_string().into_bytes()
                    },
                }),
                ..Default::default()
            }),
            Err(error) => proto::invoke_reply::Result::Error(error),
        };
        proto::InvokeReply {
            result: Some(result),
        }
        .encode_to_vec()
    }
}

fn names(calls: &[(String, String, String, Value)]) -> Vec<String> {
    calls
        .iter()
        .map(|(c, e, n, _)| format!("{c}/{e} {n}"))
        .collect()
}

#[test]
fn creating_a_task_checks_its_dependencies_then_links_them() {
    let host = Recorder::new(|_, name| match name {
        "add-dependent" => Ok(json!({})),
        _ => Ok(Value::Null),
    });
    let id = with_native_host(host.clone(), || {
        Client::default().tasks().create(
            &answer(),
            NewTask::new("How many?")
                .id("t2")
                .depends_on(["t1", "t0", "t1"])
                .attachment(Attachment::inline("notes", "text/plain", "hello")),
        )
    })
    .unwrap();
    assert_eq!(id, "t2");
    let calls = host.calls();
    assert_eq!(
        names(&calls),
        vec![
            "ankka-task/t1 get",
            "ankka-task/t0 get",
            "ankka-task/t2 create",
            "ankka-task/t1 add-dependent",
            "ankka-task/t0 add-dependent",
        ]
    );
    assert_eq!(
        calls[2].3,
        json!({
            "typeName": "answer",
            "instructions": "How many?",
            "attachments": [{"name": "notes", "contentType": "text/plain",
                             "content": {"type": "Inline", "text": "hello"}}],
            "dependencies": ["t1", "t0"],
        })
    );
    assert_eq!(calls[3].3, json!({"taskId": "t2"}));
}

#[test]
fn a_dependency_that_already_ended_cancels_the_new_task() {
    let host = Recorder::new(|_, name| match name {
        "add-dependent" => Ok(json!({"alreadyEnded": "failed"})),
        _ => Ok(Value::Null),
    });
    with_native_host(host.clone(), || {
        Client::default()
            .tasks()
            .create(&answer(), NewTask::new("q").id("t2").depends_on(["a", "b"]))
    })
    .unwrap();
    let calls = host.calls();
    assert_eq!(
        names(&calls)[3..],
        ["ankka-task/a add-dependent", "ankka-task/t2 cancel"]
    );
    assert_eq!(calls[4].3, json!({"reason": "dependency 'a' failed"}));
}

#[test]
fn a_missing_dependency_is_refused_before_anything_is_written() {
    let host = Recorder::new(|_, _| {
        Err(proto::Error {
            message: "no task".into(),
            code: proto::ErrorCode::NotFound as i32,
            ..Default::default()
        })
    });
    let refused = with_native_host(host.clone(), || {
        Client::default()
            .tasks()
            .create(&answer(), NewTask::new("q").depends_on(["gone"]))
    });
    assert_eq!(refused.map_err(|e| e.code), Err(ErrorCode::NotFound));
    assert_eq!(names(&host.calls()), vec!["ankka-task/gone get"]);
}

#[test]
fn a_generated_id_that_is_taken_is_tried_again() {
    let tries = Rc::new(RefCell::new(0));
    let seen = tries.clone();
    let host = Recorder::new(move |_, name| {
        if name == "create" {
            *seen.borrow_mut() += 1;
            if *seen.borrow() == 1 {
                return Err(proto::Error {
                    message: "exists".into(),
                    code: proto::ErrorCode::Conflict as i32,
                    ..Default::default()
                });
            }
        }
        Ok(Value::Null)
    });
    let id = with_native_host(host.clone(), || {
        Client::default().tasks().create(&summary(), "Summarise")
    })
    .unwrap();
    let calls = host.calls();
    assert_eq!(*tries.borrow(), 2);
    assert_ne!(calls[0].1, calls[1].1, "the second attempt has a new id");
    assert_eq!(id, calls[1].1);
    assert_eq!(id.len(), 36);
}

#[test]
fn running_a_single_task_creates_it_then_starts_an_instance_on_it() {
    let host = Recorder::new(|_, _| Ok(Value::Null));
    let id = with_native_host(host.clone(), || {
        Client::default()
            .autonomous_agent(Answerer)
            .run_single_task(&answer(), NewTask::new("q").id("t1"))
    })
    .unwrap();
    assert_eq!(id, "t1");
    let calls = host.calls.borrow();
    assert_eq!(calls.len(), 2);
    assert_eq!(calls[0].3, "create");
    let (kind, component, instance, name, body) = &calls[1];
    assert_eq!(*kind, Kind::AutonomousAgent as i32);
    assert_eq!(
        (component.as_str(), name.as_str()),
        ("answerer", "run-single-task")
    );
    assert!(!instance.is_empty());
    assert_eq!(body, &json!({"taskId": "t1"}));
}

#[test]
fn a_task_is_read_and_decoded_as_its_type() {
    let host = Recorder::new(|_, _| {
        Ok(json!({
            "id": "t1", "typeName": "answer", "status": "completed",
            "result": "{\"answer\":\"three\",\"sources\":[\"memory\"]}",
            "iterations": 2, "assignee": {"componentId": "answerer", "instanceId": "i1"}
        }))
    });
    let (typed, untyped, wrong) = with_native_host(host, || {
        let client = Client::default();
        (
            client.task("t1").get_as(&answer()),
            client.task("t1").get(),
            client.task("t1").get_as(&summary()),
        )
    });
    let typed = typed.unwrap();
    assert!(typed.ended());
    assert_eq!(typed.iterations, 2);
    assert_eq!(
        typed.result,
        Some(Answer {
            answer: "three".into(),
            sources: vec!["memory".into()]
        })
    );
    assert_eq!(typed.assignee, Some(("answerer".into(), "i1".into())));
    assert_eq!(untyped.unwrap().result.unwrap()["answer"], json!("three"));
    assert_eq!(wrong.map_err(|e| e.code), Err(ErrorCode::BadRequest));
}

#[test]
fn waiting_reads_until_the_task_ends_or_the_reads_run_out() {
    let reads = Rc::new(RefCell::new(0));
    let counted = reads.clone();
    let host = Recorder::new(move |_, _| {
        *counted.borrow_mut() += 1;
        let status = if *counted.borrow() < 3 {
            "in-progress"
        } else {
            "failed"
        };
        Ok(json!({"id": "t1", "typeName": "answer", "status": status, "reason": "no"}))
    });
    let (ended, gave_up) = with_native_host(host, || {
        let task = Client::default().task("t1");
        (task.wait(10), task.wait(1))
    });
    assert_eq!(ended.unwrap().reason.as_deref(), Some("no"));
    // The fourth read already sees the end: one read is enough.
    assert!(gave_up.is_ok());
    let host = Recorder::new(|_, _| Ok(json!({"status": "pending"})));
    let refused = with_native_host(host, || Client::default().task("t1").wait(3));
    let error = refused.unwrap_err();
    assert_eq!(error.code, ErrorCode::Timeout);
    assert!(error.message.contains("it is pending"), "{}", error.message);
}

#[test]
fn cancelling_a_task_takes_it_off_its_assignee() {
    let host = Recorder::new(|_, name| match name {
        "get" => Ok(json!({"status": "cancelled",
                           "assignee": {"componentId": "answerer", "instanceId": "i1"}})),
        _ => Ok(Value::Null),
    });
    with_native_host(host.clone(), || Client::default().task("t1").cancel()).unwrap();
    let calls = host.calls();
    assert_eq!(
        names(&calls),
        vec![
            "ankka-task/t1 cancel",
            "ankka-task/t1 get",
            "answerer/i1 dequeue"
        ]
    );
    assert_eq!(calls[0].3, json!({"reason": "cancelled by caller"}));
    assert_eq!(
        calls[2].3,
        json!({"taskId": "t1", "reason": "cancelled by caller"})
    );
}

#[test]
fn an_instance_is_driven_and_read() {
    let host = Recorder::new(|component, name| match (component, name) {
        (_, "assign") => Ok(json!({
            "accepted": ["a"],
            "refused": {"b": {"message": "task 'b' is completed", "code": "Conflict"}}
        })),
        ("ankka-agent-instance", "get") => Ok(json!({
            "current": {"taskId": "a", "iteration": 2}, "queue": ["c"]
        })),
        _ => Ok(Value::Null),
    });
    let (assigned, state) = with_native_host(host.clone(), || {
        let instance = Client::default().autonomous_agent(Answerer).instance("i1");
        let assigned = instance.assign(["a", "b"]).unwrap();
        instance.suspend().unwrap();
        instance.resume().unwrap();
        instance.terminate().unwrap();
        (assigned, instance.state().unwrap())
    });
    assert_eq!(assigned.accepted, vec!["a"]);
    assert_eq!(assigned.refused["b"].code, ErrorCode::Conflict);
    assert_eq!(
        names(&host.calls()),
        vec![
            "answerer/i1 assign",
            "answerer/i1 suspend",
            "answerer/i1 resume",
            "answerer/i1 terminate",
            "ankka-agent-instance/answerer/i1 get",
        ]
    );
    assert_eq!(host.calls()[0].3, json!({"taskIds": ["a", "b"]}));
    assert_eq!(state.phase, "working");
    assert_eq!(state.current_task.as_deref(), Some("a"));
    assert_eq!(state.iteration, 2);
    assert_eq!(state.queued, vec!["c"]);
}

#[test]
fn an_instance_never_used_is_idle() {
    let host = Recorder::new(|_, _| Ok(json!({})));
    let state = with_native_host(host, || {
        Client::default()
            .autonomous_agent(Answerer)
            .instance("never-used")
            .state()
    })
    .unwrap();
    assert_eq!(state.phase, "idle");
    assert!(state.queued.is_empty());
}

// ── the testkit ──

#[test]
fn the_testkit_runs_tools_rules_and_guardrails_for_a_task() {
    let kit = AutonomousAgentTestKit::<Answerer>::new("t7");
    assert_eq!(
        kit.run_tool("lookup", json!({"id": "x"})),
        Ok("x is known, for task t7".to_string())
    );
    assert_eq!(
        kit.run_tool("lookup", json!({"id": ""})),
        Err("an id is needed".to_string())
    );
    let quiet = Answer {
        answer: "three".into(),
        sources: vec![],
    };
    assert_eq!(
        kit.check_rule(&answer(), "cites-sources", &quiet),
        Verdict::rejected("sources must not be empty")
    );
    assert_eq!(
        kit.check_rule(&answer(), "not-shouting", &quiet),
        Verdict::Accepted
    );
    assert_eq!(
        kit.check_result(&answer(), &quiet),
        ResultCheck::Rejected {
            rule: "cites-sources".into(),
            reason: "sources must not be empty".into()
        }
    );
    assert!(matches!(
        kit.check_result_json("answer", r#"{"answer":1}"#),
        ResultCheck::Malformed(_)
    ));
    assert_eq!(
        kit.check_guardrail("no-secrets", Stage::Input, "the key is sk-1"),
        Err("Input rejected by no-secrets".to_string())
    );
    assert_eq!(
        kit.check_guardrail("no-secrets", Stage::Output, "fine"),
        Ok(())
    );
}
