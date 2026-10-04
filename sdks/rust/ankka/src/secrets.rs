//! The service's secret store: named text values the runtime keeps in the service's own database,
//! encrypted with a key a module never sees. A value kept here is in no journal, snapshot or view.
//!
//! It is offered to endpoints, workflow steps, consumers, timed actions and agents, and not to an
//! entity, a view or a workflow's command handler: [`Context::secrets`](crate::Context::secrets)
//! answers `None` there.
//!
//! ```ignore
//! let secrets = ctx.secrets().expect("a consumer has a secret store");
//! secrets.put("provider/acme", &credential)?;
//! let credential = secrets.get("provider/acme")?;
//! ```
//!
//! Every call blocks the handler until the runtime answers. A module that never calls the store
//! imports none of its functions, and so runs on a runtime that predates it.

use prost::Message;

use crate::abi::imports::{Import, call_secret};
use crate::effects::{CommandError, ErrorCode};
use crate::proto;

/// The longest name a secret may have.
pub const MAX_NAME_LENGTH: usize = 253;
/// The largest value a secret may have, in bytes as UTF-8.
pub const MAX_VALUE_BYTES: usize = 65536;

const NAME_RULE: &str =
    "a secret's name is 1 to 253 characters, each a letter, a digit, '.', '_', '-' or '/'";

/// What is wrong with a secret's name, if anything: the runtime's own rule.
pub fn name_problem(name: &str) -> Option<String> {
    let valid = !name.is_empty()
        && name.chars().count() <= MAX_NAME_LENGTH
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-' | '/'));
    if valid {
        return None;
    }
    let shown: String = if name.chars().count() > 40 {
        name.chars().take(40).chain(std::iter::once('…')).collect()
    } else {
        name.to_string()
    };
    Some(format!("{NAME_RULE}; \"{shown}\" is not"))
}

/// What is wrong with a secret's value, if anything. Never quotes the value.
pub fn value_problem(value: &str) -> Option<String> {
    if value.is_empty() {
        return Some("a secret's value must not be empty".to_string());
    }
    let size = value.len();
    (size > MAX_VALUE_BYTES).then(|| {
        format!("a secret's value is at most {MAX_VALUE_BYTES} bytes as UTF-8; this one is {size}")
    })
}

/// The secret store, from a handler's [`Context`](crate::Context).
///
/// Every refusal is a [`CommandError`] with the runtime's code: `BadRequest` for a name or a value
/// that breaks its rule, `Internal` when the service has no secret key or a value was kept with
/// another one, `Unavailable` when the database cannot be reached.
#[derive(Debug, Clone, Default)]
pub struct Secrets {
    _private: (),
}

fn refusal(error: proto::Error) -> CommandError {
    CommandError::new(ErrorCode::from_proto(error.code), error.message)
}

fn answer<T: Message + Default>(import: Import, request: impl Message) -> T {
    let reply = call_secret(import, &request.encode_to_vec());
    T::decode(reply.as_slice())
        .unwrap_or_else(|e| panic!("the runtime's answer to {import:?} does not decode: {e}"))
}

impl Secrets {
    pub(crate) fn new() -> Secrets {
        Secrets { _private: () }
    }

    /// Keeps `value` under `name`, replacing what was there.
    pub fn put(&self, name: &str, value: &str) -> Result<(), CommandError> {
        let reply: proto::PutSecretReply = answer(
            Import::PutSecret,
            proto::PutSecretRequest {
                name: name.into(),
                value: value.into(),
            },
        );
        reply.error.map_or(Ok(()), |e| Err(refusal(e)))
    }

    /// The value kept under `name`, or `None` when there is none. Never an empty string.
    pub fn get(&self, name: &str) -> Result<Option<String>, CommandError> {
        let reply: proto::GetSecretReply = answer(
            Import::GetSecret,
            proto::GetSecretRequest { name: name.into() },
        );
        match reply.result {
            Some(proto::get_secret_reply::Result::Value(value)) => Ok(Some(value)),
            Some(proto::get_secret_reply::Result::Absent(_)) => Ok(None),
            Some(proto::get_secret_reply::Result::Error(e)) => Err(refusal(e)),
            None => Err(CommandError::new(
                ErrorCode::Internal,
                "the runtime answered a secret's read with nothing",
            )),
        }
    }

    /// Removes what is kept under `name`. Removing nothing is not an error.
    pub fn delete(&self, name: &str) -> Result<(), CommandError> {
        let reply: proto::DeleteSecretReply = answer(
            Import::DeleteSecret,
            proto::DeleteSecretRequest { name: name.into() },
        );
        reply.error.map_or(Ok(()), |e| Err(refusal(e)))
    }
}
