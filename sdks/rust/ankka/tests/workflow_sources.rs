//! A workflow as the source of a view or a consumer (protocol 1.15): the standing it is handed,
//! and a runtime too old to know one refused.

use std::cell::RefCell;

use ankka::effects::{consumer, workflow};
use ankka::prelude::*;
use ankka::proto;
use ankka::testkit::{ConsumerTestKit, ViewTestKit};

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
        WorkflowHandlers::new().command("start", |_: &Job, (): (), _: &Context| {
            workflow::update_state(Job {
                status: "started".into(),
            })
            .transition_to("only")
            .then_reply_value(Done)
        })
    }

    fn steps() -> Steps<Jobs> {
        Steps::new().step("only", |_: &Job, (): (), _: &Context| {
            step_effects::update_state(Job {
                status: "done".into(),
            })
            .then_end()
        })
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct Row {
    status: String,
    standing: String,
}

struct JobRows;

impl View for JobRows {
    type Row = Row;
    type Event = Job;
    const COMPONENT_ID: &'static str = "job-rows";

    fn source() -> Source {
        Source::of(Jobs)
    }

    fn on_event(_: Option<Row>, job: Job, ctx: &Context) -> ViewEffect<Row> {
        ViewEffect::UpdateRow(Row {
            status: job.status,
            standing: ctx
                .standing()
                .map_or_else(|| "none".to_string(), |s| s.status.clone()),
        })
    }
}

thread_local! {
    static SEEN: RefCell<Vec<Option<Standing>>> = const { RefCell::new(Vec::new()) };
}

struct JobEnds;

impl Consumer for JobEnds {
    type Message = Job;
    const COMPONENT_ID: &'static str = "job-ends";

    fn source() -> Source {
        Source::of(Jobs)
    }

    fn on_message(_: Job, ctx: &Context) -> ConsumerEffect {
        SEEN.with(|seen| seen.borrow_mut().push(ctx.standing().cloned()));
        consumer::ignore()
    }
}

#[test]
fn a_view_of_a_workflow_is_handed_the_standing() {
    let mut kit = ViewTestKit::<JobRows>::new().standing(Standing::of("Completed"));
    kit.on_event(
        "j1",
        Job {
            status: "done".into(),
        },
    );
    assert_eq!(
        kit.row("j1"),
        Some(Row {
            status: "done".into(),
            standing: "Completed".into()
        })
    );
}

#[test]
fn a_change_handed_no_standing_has_none() {
    let mut kit = ViewTestKit::<JobRows>::new();
    kit.on_event(
        "j1",
        Job {
            status: "done".into(),
        },
    );
    assert_eq!(kit.row("j1").map(|r| r.standing), Some("none".to_string()));
}

#[test]
fn a_consumer_of_a_workflow_is_handed_the_standing() {
    let failed = Standing {
        failure: Some("payment declined".into()),
        ..Standing::of("Failed")
    };
    ConsumerTestKit::<JobEnds>::new()
        .standing(failed.clone())
        .on_message(
            "j1",
            Job {
                status: "aborted".into(),
            },
        );
    SEEN.with(|seen| assert_eq!(*seen.borrow(), vec![Some(failed.clone())]));
    assert!(failed.is_failed() && failed.is_terminal() && !failed.is_unknown());
}

#[test]
fn a_standing_reads_from_the_wire_as_it_was_written() {
    let wire = proto::WorkflowStanding {
        status: "Paused".into(),
        step: Some("charge".into()),
        retries: [("reserve".to_string(), 2)].into_iter().collect(),
        failure: None,
    };
    let standing = Standing::from(wire);
    assert_eq!(standing.step.as_deref(), Some("charge"));
    assert_eq!(standing.retries.get("reserve"), Some(&2));
    assert_eq!(Standing::from(standing.to_proto()), standing);
    assert!(Standing::of("Unknown").is_unknown());
}

#[test]
fn a_view_of_a_workflow_declares_the_workflow_as_its_source() {
    let spec = discover(
        Service::new("test").register(Jobs).register(JobRows),
        "1.15",
    );
    let view = spec
        .components
        .iter()
        .find(|c| c.id == "job-rows")
        .expect("the view");
    let Some(proto::component::Detail::View(detail)) = &view.detail else {
        panic!("not a view")
    };
    let Some(proto::source::Source::Component(source)) =
        detail.source.as_ref().and_then(|s| s.source.as_ref())
    else {
        panic!("not a component source")
    };
    assert_eq!(source.kind, proto::Kind::Workflow as i32);
    assert_eq!(source.id, "jobs");
}

#[test]
#[should_panic(expected = "Run a runtime speaking 1.15 or later")]
fn a_runtime_before_1_15_is_refused_when_a_workflow_is_read() {
    let _ = discover(
        Service::new("test")
            .register(Jobs)
            .register(JobRows)
            .register(JobEnds),
        "1.14",
    );
}

#[test]
fn a_service_reading_no_workflow_is_answered_by_a_1_14_runtime() {
    let _ = discover(Service::new("test").register(Jobs), "1.14");
}

fn discover(service: Service, protocol: &str) -> proto::Spec {
    service
        .discover(&proto::SidecarInfo {
            protocol_version: protocol.to_string(),
            runtime_version: String::new(),
        })
        .spec
        .expect("a spec")
}
