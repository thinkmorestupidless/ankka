//! Asking for an erasure request from code (feature 042, protocol 1.23).
//!
//! ```ignore
//! let erasures = ctx.erasures().expect("an endpoint may ask for an erasure");
//! let asked = erasures.ask(AskErasure::of(subject).reason("the player closed their account"))?;
//! // asked.id names the request in its project; asked.state is held, applying or applied.
//! ```
//!
//! The request is asked of the control plane as this service, which needs the `erasure` grant of
//! the project it asks in, its own included, and is named as who asked: without the grant it is
//! refused [`ErrorCode::Forbidden`]. It is offered where a call to another service is — endpoints,
//! workflow steps, consumers, timed actions, agents and the erasure handler — and
//! [`Context::erasures`](crate::Context::erasures) answers `None` anywhere else. Each ask is one
//! `erasure_ask` import and blocks the handler until the control plane answers. A module that never
//! asks does not import the function, and so runs on a runtime that predates it.

use prost::Message;

use crate::abi::imports::call_erasure_ask;
use crate::codec::time::LocalDate;
use crate::context::Metadata;
use crate::effects::{CommandError, ErrorCode};
use crate::personal::DataSubject;
use crate::proto;
use crate::proto::ask_erasure_reply::Reply;

/// What to ask: whose data, in which project, from when, why, and under what correlation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AskErasure {
    subject: DataSubject,
    project: Option<String>,
    not_before: Option<LocalDate>,
    reason: Option<String>,
    correlation: Option<String>,
}

impl AskErasure {
    /// The erasure of `subject`, in this service's own project, as soon as it may be applied.
    pub fn of(subject: DataSubject) -> AskErasure {
        AskErasure {
            subject,
            project: None,
            not_before: None,
            reason: None,
            correlation: None,
        }
    }

    /// In `project` rather than this service's own; the service needs that project's `erasure`
    /// grant.
    pub fn project(mut self, project: impl Into<String>) -> AskErasure {
        self.project = Some(project.into());
        self
    }

    /// Not applied before `date`.
    pub fn not_before(mut self, date: LocalDate) -> AskErasure {
        self.not_before = Some(date);
        self
    }

    /// Why it is asked, kept with the request.
    pub fn reason(mut self, reason: impl Into<String>) -> AskErasure {
        self.reason = Some(reason.into());
        self
    }

    /// The asker's own reference for it, kept with the request.
    pub fn correlation(mut self, correlation: impl Into<String>) -> AskErasure {
        self.correlation = Some(correlation.into());
        self
    }

    fn to_proto(&self, metadata: &Metadata) -> proto::AskErasureRequest {
        proto::AskErasureRequest {
            project: self.project.clone().unwrap_or_default(),
            subject: self.subject.as_str().to_string(),
            not_before: self.not_before.as_ref().map(|d| d.as_str().to_string()),
            reason: self.reason.clone(),
            correlation: self.correlation.clone(),
            metadata: Some(metadata.to_proto()),
        }
    }
}

impl From<DataSubject> for AskErasure {
    fn from(subject: DataSubject) -> AskErasure {
        AskErasure::of(subject)
    }
}

/// An erasure request as asked: its id in its project and its state (held, applying, applied).
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ErasureAsked {
    /// The request's id in its project.
    pub id: String,
    /// Where it stands: `held`, `applying` or `applied`.
    pub state: String,
}

/// Asking for erasure requests, from a handler's [`Context`](crate::Context).
#[derive(Debug, Clone)]
pub struct Erasures {
    metadata: Metadata,
}

impl Erasures {
    pub(crate) fn with_metadata(metadata: Metadata) -> Erasures {
        Erasures { metadata }
    }

    /// Asks for the erasure `ask` describes: a [`DataSubject`] alone, or an [`AskErasure`]. Answers
    /// the request's id and where it stands; a refusal is a [`CommandError`] with the control
    /// plane's code and words, [`ErrorCode::Forbidden`] without the `erasure` grant.
    pub fn ask(&self, ask: impl Into<AskErasure>) -> Result<ErasureAsked, CommandError> {
        let request = ask.into().to_proto(&self.metadata);
        let answered = call_erasure_ask(&request.encode_to_vec());
        let reply = proto::AskErasureReply::decode(answered.as_slice()).map_err(|e| {
            CommandError::new(
                ErrorCode::Internal,
                format!("the runtime's answer to an erasure ask does not decode: {e}"),
            )
        })?;
        match reply.reply {
            Some(Reply::Ok(asked)) => Ok(ErasureAsked {
                id: asked.id,
                state: asked.state,
            }),
            Some(Reply::Error(error)) => Err(CommandError::new(
                ErrorCode::from_proto(error.code),
                error.message,
            )),
            None => Err(CommandError::new(
                ErrorCode::Internal,
                "the runtime answered an erasure ask with nothing",
            )),
        }
    }
}
