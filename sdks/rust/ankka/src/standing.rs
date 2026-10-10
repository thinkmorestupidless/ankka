//! Where a workflow stood once the effect that recorded a state was applied (protocol 1.15).
//!
//! A view or a consumer whose source is a workflow is handed, with each state the workflow
//! records, its standing: running, paused, completed or failed, the step it is on or waits after,
//! the retries of each step and, when it failed, why. `Unknown` is the standing of a state recorded
//! before the platform stamped standings. A change from an entity or a topic has none.

use std::collections::BTreeMap;

use crate::proto;

/// A workflow's standing, as `Context::standing` answers it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Standing {
    /// `NotStarted`, `Running`, `Paused`, `Completed`, `Failed` or `Unknown`.
    pub status: String,
    /// The step the workflow is on, or the step a pause names or follows.
    pub step: Option<String>,
    /// Retries so far, per step.
    pub retries: BTreeMap<String, i32>,
    /// Why it failed, when it did.
    pub failure: Option<String>,
}

impl Standing {
    /// A standing of `status` alone.
    pub fn of(status: impl Into<String>) -> Standing {
        Standing {
            status: status.into(),
            step: None,
            retries: BTreeMap::new(),
            failure: None,
        }
    }

    /// Whether the workflow is running a step.
    pub fn is_running(&self) -> bool {
        self.status == "Running"
    }

    /// Whether the workflow is paused.
    pub fn is_paused(&self) -> bool {
        self.status == "Paused"
    }

    /// Whether the workflow completed.
    pub fn is_completed(&self) -> bool {
        self.status == "Completed"
    }

    /// Whether the workflow failed.
    pub fn is_failed(&self) -> bool {
        self.status == "Failed"
    }

    /// Whether the workflow has ended, completed or failed.
    pub fn is_terminal(&self) -> bool {
        self.is_completed() || self.is_failed()
    }

    /// Whether the state was recorded before the platform stamped standings.
    pub fn is_unknown(&self) -> bool {
        self.status == "Unknown"
    }

    /// The standing on the wire.
    pub fn to_proto(&self) -> proto::WorkflowStanding {
        proto::WorkflowStanding {
            status: self.status.clone(),
            step: self.step.clone(),
            retries: self.retries.iter().map(|(k, v)| (k.clone(), *v)).collect(),
            failure: self.failure.clone(),
        }
    }
}

impl From<proto::WorkflowStanding> for Standing {
    fn from(standing: proto::WorkflowStanding) -> Standing {
        Standing {
            status: standing.status,
            step: standing.step.filter(|s| !s.is_empty()),
            retries: standing.retries.into_iter().collect(),
            failure: standing.failure.filter(|f| !f.is_empty()),
        }
    }
}
