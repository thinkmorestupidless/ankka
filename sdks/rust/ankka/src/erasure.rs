//! The erasure handler (protocol 1.15): what a service does of its own when a data subject of its
//! project is erased.
//!
//! The platform does everything it can see before the handler runs: the subject's key is
//! destroyed, its view rows redacted, its agent sessions forgotten. The handler is for what the
//! platform cannot see, chiefly the subject's objects in the service's bucket, which
//! [`ObjectErasure::erase`] removes under `subjects/<subject>/`. It runs on every application and
//! again on each later one, so it must be safe to run twice. Registered with
//! [`Service::on_erasure`](crate::Service::on_erasure); the module's `ankka1_erase` export runs it.

use prost::Message;

use crate::abi::imports;
use crate::effects::{CommandError, ErrorCode};
use crate::proto;

/// The prefix a service keeps a data subject's objects under.
pub fn object_prefix(subject: &str) -> String {
    format!("subjects/{subject}/")
}

/// How many objects an erasure deleted, and when their deletion is final on the object store.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ErasedObjects {
    /// How many objects were deleted.
    pub count: i64,
    /// Milliseconds since the Unix epoch.
    pub final_at_millis: i64,
}

/// A data subject's objects in the service's bucket.
#[derive(Debug, Clone)]
pub struct ObjectErasure {
    subject: String,
}

impl ObjectErasure {
    /// Every object under the subject's prefix, every version where the store keeps versions.
    pub fn erase(&self) -> Result<ErasedObjects, CommandError> {
        let request = proto::EraseObjectsRequest {
            subject: self.subject.clone(),
        }
        .encode_to_vec();
        let reply =
            proto::EraseObjectsReply::decode(imports::call_erase_objects(&request).as_slice())
                .unwrap_or_default();
        match reply.result {
            Some(proto::erase_objects_reply::Result::Erased(e)) => Ok(ErasedObjects {
                count: e.count,
                final_at_millis: e.final_at_millis,
            }),
            Some(proto::erase_objects_reply::Result::Error(e)) => Err(CommandError::from_proto(&e)),
            None => Err(CommandError::new(
                ErrorCode::Unavailable,
                "the runtime answered nothing",
            )),
        }
    }
}

/// One application of an erasure to this service.
#[derive(Debug, Clone)]
pub struct ErasureContext {
    /// The data subject being erased.
    pub subject: String,
    /// The erasure request this application is for.
    pub erasure_id: String,
    /// Whether the handler has run for this erasure before.
    pub reapply: bool,
}

impl ErasureContext {
    /// The subject's objects in this service's bucket.
    pub fn objects(&self) -> ObjectErasure {
        ObjectErasure {
            subject: self.subject.clone(),
        }
    }
}

/// What the handler answers.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ErasureOutcome {
    /// Done; `detail` is recorded with the completion.
    Done {
        /// Shown on the certificate.
        detail: String,
        /// What [`ObjectErasure::erase`] reported, when it was called.
        objects: Option<ErasedObjects>,
    },
    /// Not done: run again on the next application.
    Failed(String),
}

/// The handler: run on every application of an erasure.
pub type ErasureHandler = fn(&ErasureContext) -> ErasureOutcome;

pub(crate) fn run(
    handler: Option<ErasureHandler>,
    request: proto::ErasureHandleRequest,
) -> proto::ErasureHandleReply {
    use proto::erasure_handle_reply::Outcome;
    let Some(handler) = handler else {
        return proto::ErasureHandleReply {
            outcome: Some(Outcome::Failed(proto::ErasureFailed {
                reason: "this module registers no erasure handler".into(),
            })),
        };
    };
    let ctx = ErasureContext {
        subject: request.subject,
        erasure_id: request.erasure_id,
        reapply: request.reapply,
    };
    match handler(&ctx) {
        ErasureOutcome::Done { detail, objects } => proto::ErasureHandleReply {
            outcome: Some(Outcome::Done(proto::ErasureDone {
                detail,
                objects: objects.map(|o| proto::ErasedObjects {
                    count: o.count,
                    final_at_millis: o.final_at_millis,
                }),
            })),
        },
        ErasureOutcome::Failed(reason) => proto::ErasureHandleReply {
            outcome: Some(Outcome::Failed(proto::ErasureFailed { reason })),
        },
    }
}
