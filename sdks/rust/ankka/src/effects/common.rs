//! What every kind of effect shares: outcomes, refusals, retention.

use std::any::Any;
use std::collections::BTreeMap;
use std::fmt;

use crate::codec::time::Duration;
use crate::context::Metadata;
use crate::proto;

/// Why a call was refused. Mirrors the platform's `ErrorCode`; the wire form is the protocol's enum
/// of the same names.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ErrorCode {
    /// Something went wrong that the caller could not have prevented.
    Internal,
    /// The request is not one this handler takes.
    BadRequest,
    /// The caller is not known.
    Unauthorized,
    /// The caller is known and may not do this.
    Forbidden,
    /// There is no such thing.
    NotFound,
    /// The thing is not in a state that allows this.
    Conflict,
    /// An answer did not arrive in time.
    Timeout,
    /// Something needed is not there right now; trying again may work.
    Unavailable,
    /// A workflow a caller waited for failed or was deleted (protocol 1.15). The call was answered;
    /// the step and the reason are in the error's `details`.
    WorkflowFailed,
}

impl ErrorCode {
    /// The status an endpoint answers with for a refusal of this code.
    pub fn http_status(&self) -> u16 {
        match self {
            ErrorCode::BadRequest => 400,
            ErrorCode::Unauthorized => 401,
            ErrorCode::Forbidden => 403,
            ErrorCode::NotFound => 404,
            ErrorCode::Conflict => 409,
            ErrorCode::Timeout => 504,
            ErrorCode::Unavailable => 503,
            ErrorCode::Internal => 500,
            ErrorCode::WorkflowFailed => 424,
        }
    }

    /// The protocol's number for this code.
    pub fn to_proto(&self) -> i32 {
        let code = match self {
            ErrorCode::Internal => proto::ErrorCode::Internal,
            ErrorCode::BadRequest => proto::ErrorCode::BadRequest,
            ErrorCode::Unauthorized => proto::ErrorCode::Unauthorized,
            ErrorCode::Forbidden => proto::ErrorCode::Forbidden,
            ErrorCode::NotFound => proto::ErrorCode::NotFound,
            ErrorCode::Conflict => proto::ErrorCode::Conflict,
            ErrorCode::Timeout => proto::ErrorCode::Timeout,
            ErrorCode::Unavailable => proto::ErrorCode::Unavailable,
            ErrorCode::WorkflowFailed => proto::ErrorCode::WorkflowFailed,
        };
        code as i32
    }

    /// The code for the protocol's number; one this library does not know is `Internal`.
    pub fn from_proto(value: i32) -> ErrorCode {
        match proto::ErrorCode::try_from(value) {
            Ok(proto::ErrorCode::BadRequest) => ErrorCode::BadRequest,
            Ok(proto::ErrorCode::Unauthorized) => ErrorCode::Unauthorized,
            Ok(proto::ErrorCode::Forbidden) => ErrorCode::Forbidden,
            Ok(proto::ErrorCode::NotFound) => ErrorCode::NotFound,
            Ok(proto::ErrorCode::Conflict) => ErrorCode::Conflict,
            Ok(proto::ErrorCode::Timeout) => ErrorCode::Timeout,
            Ok(proto::ErrorCode::Unavailable) => ErrorCode::Unavailable,
            Ok(proto::ErrorCode::WorkflowFailed) => ErrorCode::WorkflowFailed,
            _ => ErrorCode::Internal,
        }
    }
}

/// A refusal: nothing is persisted, and the caller sees the code and the message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CommandError {
    /// Why.
    pub code: ErrorCode,
    /// What to tell the caller.
    pub message: String,
    /// What a caller reads without parsing the message (protocol 1.15): a failed workflow's `step`
    /// (when one failed), its `reason`, and `deleted` = `"true"` for a deleted one.
    pub details: BTreeMap<String, String>,
}

impl CommandError {
    /// A refusal with this code.
    pub fn new(code: ErrorCode, message: impl Into<String>) -> CommandError {
        CommandError {
            code,
            message: message.into(),
            details: BTreeMap::new(),
        }
    }

    /// The refusal as the protocol carries it.
    pub fn to_proto(&self) -> proto::Error {
        proto::Error {
            message: self.message.clone(),
            code: self.code.to_proto(),
            details: self
                .details
                .iter()
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect(),
        }
    }

    /// The protocol's refusal as a `CommandError`.
    pub fn from_proto(error: &proto::Error) -> CommandError {
        CommandError {
            code: ErrorCode::from_proto(error.code),
            message: error.message.clone(),
            details: error
                .details
                .iter()
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect(),
        }
    }
}

impl fmt::Display for CommandError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}: {}", self.code, self.message)
    }
}

impl std::error::Error for CommandError {}

/// What happens to an entity after a command: deleted now, or deleted after a quiet spell.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Retention {
    /// Deleted now: the next command finds it fresh.
    DeleteNow,
    /// Deleted once this long has passed with no command.
    ExpireAfter(Duration),
}

impl Retention {
    /// The retention as the protocol carries it.
    pub fn to_proto(&self) -> proto::Retention {
        use proto::retention::{DeleteNow, ExpireAfter, Retention as R};
        let retention = match self {
            Retention::DeleteNow => R::DeleteNow(DeleteNow {}),
            Retention::ExpireAfter(d) => R::ExpireAfter(ExpireAfter {
                millis: d.to_millis(),
            }),
        };
        proto::Retention {
            retention: Some(retention),
        }
    }
}

/// How a reply is computed: from the state after the events, which the handler has not seen yet.
pub(crate) type ReplyFn<R> = Box<dyn FnOnce(&dyn Any) -> R>;

/// The three things a handler can decide to answer.
pub enum Outcome<R> {
    /// A reply, computed from the state after the effect's events.
    Reply(ReplyFn<R>, Metadata),
    /// No reply: the caller learns only that the command was handled.
    NoReply,
    /// A refusal.
    Error(CommandError),
}

impl<R: 'static> Outcome<R> {
    /// The same outcome with its reply transformed.
    pub fn map<R2: 'static>(self, f: impl FnOnce(R) -> R2 + 'static) -> Outcome<R2> {
        match self {
            Outcome::Reply(reply, metadata) => {
                Outcome::Reply(Box::new(move |state| f(reply(state))), metadata)
            }
            Outcome::NoReply => Outcome::NoReply,
            Outcome::Error(e) => Outcome::Error(e),
        }
    }
}

impl<R> fmt::Debug for Outcome<R> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Outcome::Reply(_, metadata) => f.debug_tuple("Reply").field(metadata).finish(),
            Outcome::NoReply => f.write_str("NoReply"),
            Outcome::Error(e) => f.debug_tuple("Error").field(e).finish(),
        }
    }
}

/// A reply computed from the state after the events. The state's type is the entity's; a closure
/// that names another type is a bug, reported when the reply is computed.
pub(crate) fn from_state<S: 'static, R>(f: impl FnOnce(&S) -> R + 'static) -> ReplyFn<R> {
    Box::new(move |state: &dyn Any| {
        let state = state.downcast_ref::<S>().unwrap_or_else(|| {
            panic!(
                "a reply closure takes &{}, which is not the entity's state",
                std::any::type_name::<S>()
            )
        });
        f(state)
    })
}

/// A reply that ignores the state.
pub(crate) fn constant<R: 'static>(value: R) -> ReplyFn<R> {
    Box::new(move |_| value)
}
