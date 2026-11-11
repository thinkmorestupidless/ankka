//! A workflow's standing and how a replicated entity's copy stands (protocol 1.24): the runtime's own
//! reads, which run no handler and journal nothing, made through `ctx.client().lifecycle(..)` and
//! `.replication(..)`.
//!
//! Each import is in a link block of its own, so a module that reads neither imports neither and
//! runs on a runtime from before 1.24. A runtime from before 1.24 refuses to load a module that
//! imports either, naming the import.

use std::collections::BTreeMap;

use crate::effects::{CommandError, ErrorCode};
use crate::proto;

/// Where a workflow has got to, as the engine records it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum WorkflowStatus {
    /// Nothing was ever started under its id.
    NotStarted,
    /// Running a step, or between steps.
    Running,
    /// Paused, waiting for a command or its deadline.
    Paused,
    /// Ended.
    Completed,
    /// Ended by a step that failed past its retries.
    Failed,
}

/// A workflow's standing: its status, the step it is on or waits after, how many times each step was
/// retried (a step never retried is absent), and why it failed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Lifecycle {
    /// Where it has got to.
    pub status: WorkflowStatus,
    /// The step it is on or waits after.
    pub pending_step: Option<String>,
    /// Retries so far, per step.
    pub retries: BTreeMap<String, u32>,
    /// Why it failed, when it did.
    pub failure: Option<String>,
}

impl Lifecycle {
    /// A standing of `status` alone.
    pub fn of(status: WorkflowStatus) -> Lifecycle {
        Lifecycle {
            status,
            pending_step: None,
            retries: BTreeMap::new(),
            failure: None,
        }
    }

    /// Whether it is running.
    pub fn is_running(&self) -> bool {
        self.status == WorkflowStatus::Running
    }

    /// Whether it is paused.
    pub fn is_paused(&self) -> bool {
        self.status == WorkflowStatus::Paused
    }

    /// Whether it completed.
    pub fn is_completed(&self) -> bool {
        self.status == WorkflowStatus::Completed
    }

    /// Whether it failed.
    pub fn is_failed(&self) -> bool {
        self.status == WorkflowStatus::Failed
    }

    /// Whether it has ended, completed or failed.
    pub fn is_terminal(&self) -> bool {
        self.is_completed() || self.is_failed()
    }

    /// The standing as the protocol carries it; a status this library does not know is refused.
    pub fn from_proto(message: &proto::WorkflowLifecycle) -> Result<Lifecycle, CommandError> {
        let status = match proto::WorkflowStatus::try_from(message.status) {
            Ok(proto::WorkflowStatus::NotStarted) => WorkflowStatus::NotStarted,
            Ok(proto::WorkflowStatus::Running) => WorkflowStatus::Running,
            Ok(proto::WorkflowStatus::Paused) => WorkflowStatus::Paused,
            Ok(proto::WorkflowStatus::Completed) => WorkflowStatus::Completed,
            Ok(proto::WorkflowStatus::Failed) => WorkflowStatus::Failed,
            _ => {
                return Err(CommandError::new(
                    ErrorCode::Internal,
                    format!(
                        "a workflow's standing this library does not know: {}",
                        message.status
                    ),
                ));
            }
        };
        Ok(Lifecycle {
            status,
            pending_step: message.pending_step.clone(),
            retries: message
                .retries
                .iter()
                .map(|(step, n)| (step.clone(), (*n).max(0) as u32))
                .collect(),
            failure: message.failure.clone(),
        })
    }

    /// The standing as the protocol carries it.
    pub fn to_proto(&self) -> proto::WorkflowLifecycle {
        let status = match self.status {
            WorkflowStatus::NotStarted => proto::WorkflowStatus::NotStarted,
            WorkflowStatus::Running => proto::WorkflowStatus::Running,
            WorkflowStatus::Paused => proto::WorkflowStatus::Paused,
            WorkflowStatus::Completed => proto::WorkflowStatus::Completed,
            WorkflowStatus::Failed => proto::WorkflowStatus::Failed,
        };
        proto::WorkflowLifecycle {
            status: status as i32,
            pending_step: self.pending_step.clone(),
            retries: self
                .retries
                .iter()
                .map(|(step, n)| (step.clone(), *n as i32))
                .collect(),
            failure: self.failure.clone(),
        }
    }
}

/// How the copy of a replicated entity at this installation stands: its home, the installations
/// that hold it (sorted), the installation a handover waiting goes to, the other home when it was
/// created at two, and how many records this copy's history holds.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReplicationStatus {
    /// The installation that handles its writes.
    pub home: Option<String>,
    /// The installations that hold it, sorted.
    pub replication_set: Vec<String>,
    /// The installation a handover waiting goes to.
    pub pending_handover: Option<String>,
    /// The other home, when it was created at two installations.
    pub conflict: Option<String>,
    /// How many records this copy's history holds.
    pub position: i64,
}

impl ReplicationStatus {
    /// Whether it was created at two installations.
    pub fn in_conflict(&self) -> bool {
        self.conflict.is_some()
    }

    /// How a copy stands, as the protocol carries it.
    pub fn from_proto(message: &proto::ReplicationStatus) -> ReplicationStatus {
        let mut replication_set = message.replication_set.clone();
        replication_set.sort();
        ReplicationStatus {
            home: message.home.clone(),
            replication_set,
            pending_handover: message.pending_handover.clone(),
            conflict: message.conflict.clone(),
            position: message.position,
        }
    }

    /// How a copy stands, as the protocol carries it.
    pub fn to_proto(&self) -> proto::ReplicationStatus {
        let mut replication_set = self.replication_set.clone();
        replication_set.sort();
        proto::ReplicationStatus {
            home: self.home.clone(),
            replication_set,
            pending_handover: self.pending_handover.clone(),
            conflict: self.conflict.clone(),
            position: self.position,
        }
    }
}
