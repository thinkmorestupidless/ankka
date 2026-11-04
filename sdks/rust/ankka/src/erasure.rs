//! The service's erasure handler (feature 042): what the platform cannot erase for it.
//!
//! ```ignore
//! Service::new("players").erasure_handler(|ctx, subject| {
//!     let storage = ctx.storage().expect("an erasure handler has the service's buckets");
//!     let _ = storage; // erase what the service keeps about `subject` elsewhere
//!     Ok(ErasureReport::immediate(0))
//! })
//! ```
//!
//! One per service. [`service!`](crate::service!) exports it as `ankka1_erase`; the runtime runs it
//! on a fresh instance on every application of an erasure request and on each re-run, so it must
//! be idempotent. A handler that fails is recorded as failed for that run and run again on the next
//! pass; the platform's own duties do not wait for it.

use std::fmt;

use crate::codec::time::Duration;
use crate::context::{Context, Metadata};
use crate::effects::{CommandError, ErrorCode};
use crate::personal::DataSubject;
use crate::proto;

/// When what a handler erased is gone for good.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Finality {
    /// Gone now.
    Immediate,
    /// Gone once this long has passed: a store that keeps deleted versions for a while.
    After(Duration),
}

impl Finality {
    /// The wire form: `immediate`, or `after:` and an ISO-8601 duration.
    pub fn to_wire(&self) -> String {
        match self {
            Finality::Immediate => "immediate".to_string(),
            Finality::After(duration) => format!("after:{duration}"),
        }
    }

    /// The wire form read back; empty is immediate.
    pub fn parse(text: &str) -> Result<Finality, String> {
        match text {
            "" | "immediate" => Ok(Finality::Immediate),
            other => match other.strip_prefix("after:") {
                Some(duration) => Duration::parse(duration)
                    .map(Finality::After)
                    .map_err(|e| format!("'{other}' is not a finality: {e}")),
                None => Err(format!(
                    "'{other}' is not a finality: 'immediate', or 'after:' and an ISO-8601 duration"
                )),
            },
        }
    }
}

impl fmt::Display for Finality {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.to_wire())
    }
}

/// What a handler erased: how many objects, and when they are gone for good.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ErasureReport {
    /// The objects removed.
    pub objects_erased: i64,
    /// When they are gone for good.
    pub finality: Finality,
}

impl ErasureReport {
    /// `objects_erased` objects, gone now.
    pub fn immediate(objects_erased: i64) -> ErasureReport {
        ErasureReport {
            objects_erased,
            finality: Finality::Immediate,
        }
    }

    /// `objects_erased` objects, gone once `duration` has passed.
    pub fn after(objects_erased: i64, duration: Duration) -> ErasureReport {
        ErasureReport {
            objects_erased,
            finality: Finality::After(duration),
        }
    }
}

/// A service's erasure handler, as [`Service::erasure_handler`](crate::Service::erasure_handler)
/// takes it.
pub(crate) type Handler =
    Box<dyn Fn(&Context, &DataSubject) -> Result<ErasureReport, CommandError>>;

/// The component id an erasure handler's context carries.
pub const ERASURE_COMPONENT: &str = "erasure-handler";

/// Runs `handler` for one call, answering the reply `ankka1_erase` gives back. With no handler,
/// the reply's error says so.
pub(crate) fn answer(handler: Option<&Handler>, call: proto::ErasureCall) -> proto::ErasureReply {
    let failed = |error: CommandError| proto::ErasureReply {
        objects_erased: 0,
        finality: String::new(),
        error: Some(error.to_proto()),
    };
    let Some(handler) = handler else {
        return failed(CommandError::new(
            ErrorCode::NotFound,
            "this service declares no erasure handler",
        ));
    };
    let subject = match DataSubject::new(call.subject.clone()) {
        Ok(subject) => subject,
        Err(e) => return failed(e.into()),
    };
    let ctx = Context::new(
        ERASURE_COMPONENT,
        subject.as_str(),
        call.erasure_seq,
        Metadata::from_proto(call.metadata.as_ref()),
    )
    .with_secrets()
    .with_storage()
    .with_services();
    match handler(&ctx, &subject) {
        Ok(report) => proto::ErasureReply {
            objects_erased: report.objects_erased,
            finality: report.finality.to_wire(),
            error: None,
        },
        Err(error) => failed(error),
    }
}
