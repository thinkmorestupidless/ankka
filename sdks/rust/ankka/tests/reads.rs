//! A workflow's standing and how a copy stands, as a module asks the runtime for them (protocol
//! 1.24), against a native host answering the imports' bytes: each standing the platform records
//! decoded, a copy's fields, and a refusal carried in the reply.

use std::cell::RefCell;
use std::collections::BTreeMap;
use std::rc::Rc;

use ankka::Client;
use ankka::abi::imports::{Import, NativeHost, with_native_host};
use ankka::effects::ErrorCode;
use ankka::proto;
use ankka::{Lifecycle, ReplicationStatus, WorkflowStatus};
use prost::Message;

/// Each import the host was asked, with its encoded request.
type Asked = Rc<RefCell<Vec<(Import, Vec<u8>)>>>;

#[derive(Clone, Default)]
struct Answering {
    lifecycle: Option<proto::LifecycleReply>,
    replication: Option<proto::ReplicationReply>,
    asked: Asked,
}

impl NativeHost for Answering {
    fn call(&self, import: Import, request: &[u8]) -> Vec<u8> {
        self.asked.borrow_mut().push((import, request.to_vec()));
        match import {
            Import::Lifecycle => self.lifecycle.clone().unwrap().encode_to_vec(),
            Import::Replication => self.replication.clone().unwrap().encode_to_vec(),
            other => panic!("unexpected {other:?}"),
        }
    }
}

fn standing(l: proto::WorkflowLifecycle) -> Answering {
    Answering {
        lifecycle: Some(proto::LifecycleReply {
            result: Some(proto::lifecycle_reply::Result::Lifecycle(l)),
        }),
        ..Default::default()
    }
}

#[test]
fn each_standing_the_platform_records_is_read() {
    for (status, expected) in [
        (
            proto::WorkflowStatus::NotStarted,
            WorkflowStatus::NotStarted,
        ),
        (proto::WorkflowStatus::Running, WorkflowStatus::Running),
        (proto::WorkflowStatus::Paused, WorkflowStatus::Paused),
        (proto::WorkflowStatus::Completed, WorkflowStatus::Completed),
        (proto::WorkflowStatus::Failed, WorkflowStatus::Failed),
    ] {
        let host = standing(proto::WorkflowLifecycle {
            status: status as i32,
            ..Default::default()
        });
        let read = with_native_host(host.clone(), || {
            Client::default().lifecycle_by_name("transfer", "t1")
        })
        .unwrap();
        assert_eq!(read.status, expected);
        let (import, bytes) = host.asked.borrow()[0].clone();
        assert_eq!(import, Import::Lifecycle);
        let asked = proto::LifecycleRequest::decode(bytes.as_slice()).unwrap();
        assert_eq!(
            (asked.component_id.as_str(), asked.workflow_id.as_str()),
            ("transfer", "t1")
        );
    }
}

#[test]
fn a_failed_workflow_carries_its_reason_and_its_retries() {
    let host = standing(proto::WorkflowLifecycle {
        status: proto::WorkflowStatus::Failed as i32,
        retries: [("deposit".to_string(), 2)].into_iter().collect(),
        failure: Some("the bank was shut".into()),
        ..Default::default()
    });
    let read = with_native_host(host, || {
        Client::default().lifecycle_by_name("transfer", "t1")
    })
    .unwrap();
    assert_eq!(
        read,
        Lifecycle {
            status: WorkflowStatus::Failed,
            pending_step: None,
            retries: BTreeMap::from([("deposit".to_string(), 2)]),
            failure: Some("the bank was shut".into()),
        }
    );
    assert!(read.is_failed() && read.is_terminal());
}

#[test]
fn a_running_workflow_names_its_step() {
    let host = standing(proto::WorkflowLifecycle {
        status: proto::WorkflowStatus::Running as i32,
        pending_step: Some("withdraw".into()),
        ..Default::default()
    });
    let read = with_native_host(host, || {
        Client::default().lifecycle_by_name("transfer", "t1")
    })
    .unwrap();
    assert!(read.is_running() && !read.is_terminal());
    assert_eq!(read.pending_step.as_deref(), Some("withdraw"));
}

#[test]
fn a_status_this_library_does_not_know_is_refused() {
    let host = standing(proto::WorkflowLifecycle::default());
    let refused = with_native_host(host, || {
        Client::default().lifecycle_by_name("transfer", "t1")
    })
    .unwrap_err();
    assert_eq!(refused.code, ErrorCode::Internal);
}

#[test]
fn a_copy_reads_its_home_its_set_a_handover_a_conflict_and_its_position() {
    let host = Answering {
        replication: Some(proto::ReplicationReply {
            result: Some(proto::replication_reply::Result::Status(
                proto::ReplicationStatus {
                    home: Some("leipzig".into()),
                    replication_set: vec!["vienna".into(), "leipzig".into()],
                    pending_handover: Some("vienna".into()),
                    conflict: Some("vienna".into()),
                    position: 5,
                },
            )),
        }),
        ..Default::default()
    };
    let read = with_native_host(host.clone(), || {
        Client::default().replication_by_name("vehicle", "v1")
    })
    .unwrap();
    assert_eq!(
        read,
        ReplicationStatus {
            home: Some("leipzig".into()),
            replication_set: vec!["leipzig".into(), "vienna".into()],
            pending_handover: Some("vienna".into()),
            conflict: Some("vienna".into()),
            position: 5,
        }
    );
    assert!(read.in_conflict());
    let (import, bytes) = host.asked.borrow()[0].clone();
    assert_eq!(import, Import::Replication);
    let asked = proto::ReplicationRequest::decode(bytes.as_slice()).unwrap();
    assert_eq!(
        (asked.component_id.as_str(), asked.entity_id.as_str()),
        ("vehicle", "v1")
    );
}

#[test]
fn a_refusal_crosses_with_the_runtimes_code() {
    let refusal = proto::Error {
        message: "not replicated".into(),
        code: proto::ErrorCode::NotFound as i32,
        ..Default::default()
    };
    let host = Answering {
        lifecycle: Some(proto::LifecycleReply {
            result: Some(proto::lifecycle_reply::Result::Error(refusal.clone())),
        }),
        replication: Some(proto::ReplicationReply {
            result: Some(proto::replication_reply::Result::Error(refusal)),
        }),
        ..Default::default()
    };
    let (standing, copy) = with_native_host(host, || {
        (
            Client::default().lifecycle_by_name("transfer", "t1"),
            Client::default().replication_by_name("vehicle", "v1"),
        )
    });
    assert_eq!(standing.unwrap_err().code, ErrorCode::NotFound);
    assert_eq!(copy.unwrap_err().code, ErrorCode::NotFound);
}

#[test]
fn with_no_runtime_both_reads_are_unavailable() {
    assert_eq!(
        Client::default()
            .lifecycle_by_name("transfer", "t1")
            .unwrap_err()
            .code,
        ErrorCode::Unavailable
    );
    assert_eq!(
        Client::default()
            .replication_by_name("vehicle", "v1")
            .unwrap_err()
            .code,
        ErrorCode::Unavailable
    );
}

#[test]
fn a_standing_round_trips_through_the_protocol() {
    let l = Lifecycle {
        status: WorkflowStatus::Paused,
        pending_step: Some("approve".into()),
        retries: BTreeMap::from([("charge".to_string(), 1)]),
        failure: None,
    };
    assert_eq!(Lifecycle::from_proto(&l.to_proto()).unwrap(), l);
}

#[test]
fn a_status_is_shown_as_the_platform_names_it() {
    let shown: Vec<String> = [
        WorkflowStatus::NotStarted,
        WorkflowStatus::Running,
        WorkflowStatus::Paused,
        WorkflowStatus::Completed,
        WorkflowStatus::Failed,
    ]
    .iter()
    .map(ToString::to_string)
    .collect();
    assert_eq!(
        shown,
        ["NotStarted", "Running", "Paused", "Completed", "Failed"]
    );
}
