//! The service's client keys: credentials it issues to its own customers' MCP clients, which the
//! runtime keeps, checks on every call to an MCP endpoint whose ACL admits them, and turns into a
//! [`Principal`](crate::components::Principal) before the module is asked to run a tool.
//!
//! ```ignore
//! let keys = ctx.client_keys().expect("an endpoint has the client keys");
//! let issued = keys.issue(
//!     IssueClientKey::new("acme", Expiry::After(Duration::of_hours(24 * 365)))
//!         .scope(["list_jobs", "book_job"])
//!         .attribute("person", "alice")
//!         .label("Alice's assistant"),
//! )?;
//! // issued.client_key is shown once; nothing can read it back.
//! keys.revoke(&issued.id)?;
//! ```
//!
//! They are offered where the secret store is, and not to an entity, a view or a workflow's
//! command handler: [`Context::client_keys`](crate::Context::client_keys) answers `None` there.
//! Every call blocks the handler until the runtime answers. A module that never calls them imports
//! none of their functions, and so runs on a runtime that predates them (protocol 1.20).

use std::collections::BTreeMap;

use prost::Message;

use crate::abi::imports::{Import, call_client_key};
use crate::codec::time::{Duration, Instant};
use crate::effects::{CommandError, ErrorCode};
use crate::proto;

/// When an issued key stops admitting its holder. Required: a key that never expires is a
/// decision, said as [`Expiry::Never`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Expiry {
    /// This long after it is issued.
    After(Duration),
    /// At this moment.
    At(Instant),
    /// Never; it ends only when it is revoked.
    Never,
}

/// What to issue a client key for: whom it names, until when, and what it may do.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IssueClientKey {
    subject: String,
    expiry: Expiry,
    endpoint: Option<String>,
    scope: Vec<String>,
    attributes: BTreeMap<String, String>,
    label: Option<String>,
}

impl IssueClientKey {
    /// A key for `subject` (the principal's subject when it is used) that ends as `expiry` says.
    pub fn new(subject: impl Into<String>, expiry: Expiry) -> IssueClientKey {
        IssueClientKey {
            subject: subject.into(),
            expiry,
            endpoint: None,
            scope: Vec::new(),
            attributes: BTreeMap::new(),
            label: None,
        }
    }

    /// The tools it may list and call; none named means every tool.
    pub fn scope<I, S>(mut self, tools: I) -> IssueClientKey
    where
        I: IntoIterator<Item = S>,
        S: Into<String>,
    {
        self.scope = tools.into_iter().map(Into::into).collect();
        self
    }

    /// A claim the principal carries when the key is used: `person` = `alice`.
    pub fn attribute(
        mut self,
        name: impl Into<String>,
        value: impl Into<String>,
    ) -> IssueClientKey {
        self.attributes.insert(name.into(), value.into());
        self
    }

    /// A name for the key in listings, never the key.
    pub fn label(mut self, label: impl Into<String>) -> IssueClientKey {
        self.label = Some(label.into());
        self
    }

    /// The MCP endpoint, by id, the key admits to; the service's only one when it is not named.
    pub fn endpoint(mut self, endpoint_id: impl Into<String>) -> IssueClientKey {
        self.endpoint = Some(endpoint_id.into());
        self
    }

    fn to_proto(&self) -> proto::IssueClientKeyRequest {
        use proto::issue_client_key_request::Expiry as Wire;
        proto::IssueClientKeyRequest {
            subject: self.subject.clone(),
            expiry: Some(match self.expiry {
                Expiry::After(d) => Wire::AfterMillis(d.to_millis()),
                Expiry::At(at) => Wire::AtEpochMillis(at.epoch_millis()),
                Expiry::Never => Wire::Never(true),
            }),
            endpoint: self.endpoint.clone(),
            scope: self.scope.clone(),
            attributes: self
                .attributes
                .iter()
                .map(|(k, v)| (k.clone(), v.clone()))
                .collect(),
            label: self.label.clone(),
            metadata: None,
        }
    }
}

/// A key just issued: its id, and the key itself, which is answered here once and kept nowhere.
#[derive(Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IssuedClientKey {
    /// What names the key in a listing and to `revoke`.
    pub id: String,
    /// The key, for the client to send as a bearer credential.
    pub client_key: String,
}

impl std::fmt::Debug for IssuedClientKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "IssuedClientKey({}, <the key>)", self.id)
    }
}

/// One client key as a listing shows it: never the key, never its digest.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClientKeySummary {
    /// The key's id.
    pub id: String,
    /// The subject it names.
    pub subject: String,
    /// The claims it carries.
    pub attributes: BTreeMap<String, String>,
    /// Its label, if it was given one.
    pub label: Option<String>,
    /// The MCP endpoint it admits to.
    pub endpoint: String,
    /// The tools it may use; empty for every tool.
    pub scope: Vec<String>,
    /// When it was issued.
    pub issued_at: Instant,
    /// When it expires, if it does.
    pub expires_at: Option<Instant>,
    /// When it was last used, if it has been.
    pub last_used: Option<Instant>,
    /// When it was revoked, if it has been.
    pub revoked_at: Option<Instant>,
}

impl ClientKeySummary {
    fn from_proto(s: proto::ClientKeySummary) -> ClientKeySummary {
        let mut scope = s.scope;
        scope.sort();
        ClientKeySummary {
            id: s.id,
            subject: s.subject,
            attributes: s.attributes.into_iter().collect(),
            label: s.label,
            endpoint: s.endpoint,
            scope,
            issued_at: Instant::from_epoch_millis(s.issued_at_millis),
            expires_at: s.expires_at_millis.map(Instant::from_epoch_millis),
            last_used: s.last_used_millis.map(Instant::from_epoch_millis),
            revoked_at: s.revoked_at_millis.map(Instant::from_epoch_millis),
        }
    }
}

/// The service's client keys, from a handler's [`Context`](crate::Context).
///
/// A refusal is a [`CommandError`] with the runtime's code: `BadRequest` for a key the rules
/// refuse (no subject, no expiry, a scope naming no tool), `NotFound` for a revoke of a key there
/// is not, and `Unavailable` when the runtime keeps no keys.
#[derive(Debug, Clone, Default)]
pub struct ClientKeys {
    metadata: crate::context::Metadata,
}

fn refusal(error: proto::Error) -> CommandError {
    CommandError::new(ErrorCode::from_proto(error.code), error.message)
}

fn answer<T: Message + Default>(import: Import, request: impl Message) -> T {
    let reply = call_client_key(import, &request.encode_to_vec());
    T::decode(reply.as_slice())
        .unwrap_or_else(|e| panic!("the runtime's answer to {import:?} does not decode: {e}"))
}

impl ClientKeys {
    pub(crate) fn with_metadata(metadata: crate::context::Metadata) -> ClientKeys {
        ClientKeys { metadata }
    }

    /// Issues a key, answering its id and the key itself.
    pub fn issue(&self, key: IssueClientKey) -> Result<IssuedClientKey, CommandError> {
        let mut request = key.to_proto();
        request.metadata = Some(self.metadata.to_proto());
        let reply: proto::IssueClientKeyReply = answer(Import::IssueClientKey, request);
        match reply.reply {
            Some(proto::issue_client_key_reply::Reply::Issued(issued)) => Ok(IssuedClientKey {
                id: issued.id,
                client_key: issued.client_key,
            }),
            Some(proto::issue_client_key_reply::Reply::Error(e)) => Err(refusal(e)),
            None => Err(CommandError::new(
                ErrorCode::Internal,
                "the runtime answered a client key's issue with nothing",
            )),
        }
    }

    /// Every key the service has issued, revoked ones included.
    pub fn list(&self) -> Result<Vec<ClientKeySummary>, CommandError> {
        let reply: proto::ListClientKeysReply = answer(
            Import::ListClientKeys,
            proto::ListClientKeysRequest {
                metadata: Some(self.metadata.to_proto()),
            },
        );
        match reply.error {
            Some(e) => Err(refusal(e)),
            None => Ok(reply
                .keys
                .into_iter()
                .map(ClientKeySummary::from_proto)
                .collect()),
        }
    }

    /// Revokes the key `id`: it admits nobody from now on.
    pub fn revoke(&self, id: &str) -> Result<(), CommandError> {
        let reply: proto::RevokeClientKeyReply = answer(
            Import::RevokeClientKey,
            proto::RevokeClientKeyRequest {
                id: id.to_string(),
                metadata: Some(self.metadata.to_proto()),
            },
        );
        reply.error.map_or(Ok(()), |e| Err(refusal(e)))
    }
}
