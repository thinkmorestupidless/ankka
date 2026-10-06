//! Calling another service, as this one: a route of a service of the same project, or of another
//! project, by its name. The runtime makes the call with this service's certificate, so the
//! service called reads the caller as this service and its ACL can admit it by name.
//!
//! It is offered to endpoints, workflow steps, consumers, timed actions and agents, and not to an
//! entity, a view or a workflow's command handler:
//! [`Context::services`](crate::Context::services) answers `None` there. Those handlers must not
//! wait on anything outside the service, and the runtime ends a call that tries.
//!
//! ```ignore
//! let wallet = ctx.services().expect("a consumer may call another service").service("wallet");
//! let credited: Receipt = wallet.post("/internal/credits", &Credit { amount: 5 })?;
//! ```
//!
//! Every call blocks the handler until the service answers or the runtime's wait for it ends. A
//! module that never calls another service imports nothing for it, and so runs on a runtime that
//! predates the call (protocol 1.10).

use prost::Message;
use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::abi::imports::call_request;
use crate::codec::{decode_payload, encode_payload};
use crate::context::Metadata;
use crate::effects::{CommandError, ErrorCode};
use crate::proto;

/// The largest body a call carries, either way: what the runtime carries in one message.
pub const MAX_BODY_BYTES: usize = 4_000_000;

/// The clients for other services, from a handler's [`Context`](crate::Context). Its calls carry
/// the handler's metadata, which is how each is counted from the handler and joins its trace.
#[derive(Debug, Clone, Default)]
pub struct Services {
    metadata: Metadata,
}

impl Services {
    /// Clients whose calls carry `metadata`.
    pub fn with_metadata(metadata: Metadata) -> Services {
        Services { metadata }
    }

    /// The service `name` of this service's own project.
    pub fn service(&self, name: &str) -> ServiceClient {
        ServiceClient::new(name, None, self.metadata.clone())
    }

    /// The service `name` of `project`. Whether it admits this service is its own ACL's decision.
    pub fn service_in(&self, project: &str, name: &str) -> ServiceClient {
        ServiceClient::new(name, Some(project), self.metadata.clone())
    }
}

/// What a raw [`ServiceClient::request`] may carry beyond its method and path.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RequestOptions {
    /// The body; `None` sends none.
    pub body: Option<Vec<u8>>,
    /// The body's content type.
    pub content_type: Option<String>,
    /// Headers, in order, after any the client itself carries. The platform's own — the caller,
    /// the host, the forwarding headers — are the runtime's to write and are never sent on.
    pub headers: Vec<(String, String)>,
}

/// What a service answered: its status, content type, body and headers, as it sent them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceResponse {
    /// The status code.
    pub status: u16,
    /// The content type, empty when the service sent none.
    pub content_type: String,
    /// The body's bytes.
    pub body: Vec<u8>,
    /// The headers, in the order the service sent them.
    pub headers: Vec<(String, String)>,
}

impl ServiceResponse {
    /// The body as text.
    pub fn text(&self) -> Result<&str, std::str::Utf8Error> {
        std::str::from_utf8(&self.body)
    }

    /// The first header named `name`, whatever its case.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    fn succeeded(&self) -> bool {
        self.status / 100 == 2
    }
}

/// A call to another service that did not end in an answer its caller accepts.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ServiceError {
    /// No such service was found; nothing was sent.
    Unresolvable {
        /// The service as it was named.
        service: String,
        /// What was tried.
        reason: String,
    },
    /// What answers for the name is not the service asked for; nothing was sent.
    IdentityMismatch {
        /// The service as it was named.
        service: String,
        /// Who answered instead.
        detail: String,
    },
    /// No answer came: the connection was refused or broke, or the runtime's wait ended. The
    /// service may have received the request.
    Unanswered {
        /// The service as it was named.
        service: String,
        /// What happened.
        reason: String,
    },
    /// The service answered with a status outside 2xx, to a typed helper. The raw
    /// [`request`](ServiceClient::request) returns every answer instead.
    CallFailed {
        /// The service as it was named.
        service: String,
        /// The status it answered.
        status: u16,
        /// The body it answered.
        body: Vec<u8>,
    },
    /// The runtime refused to make the call, or the crate did before asking it: a body over the
    /// limit, a name that is not one, an answer that does not decode.
    Refused(CommandError),
}

impl std::fmt::Display for ServiceError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ServiceError::Unresolvable { service, reason } => {
                write!(f, "cannot reach {service}: {reason}")
            }
            ServiceError::IdentityMismatch { service, detail } => {
                write!(f, "the service reached as {service} is not it: {detail}")
            }
            ServiceError::Unanswered { service, reason } => {
                write!(f, "{service} did not answer: {reason}")
            }
            ServiceError::CallFailed {
                service,
                status,
                body,
            } => write!(
                f,
                "{service} answered {status}: {}",
                String::from_utf8_lossy(body)
            ),
            ServiceError::Refused(error) => write!(f, "{}", error.message),
        }
    }
}

impl std::error::Error for ServiceError {}

/// So that `?` works in a handler that answers a `CommandError`: a call that got no answer is
/// `Unavailable`, a service's own refusal or failure is `Internal` with what it said, and a
/// refusal by the runtime is itself.
impl From<ServiceError> for CommandError {
    fn from(error: ServiceError) -> CommandError {
        match error {
            ServiceError::Refused(refused) => refused,
            ServiceError::CallFailed { .. } => {
                CommandError::new(ErrorCode::Internal, error.to_string())
            }
            other => CommandError::new(ErrorCode::Unavailable, other.to_string()),
        }
    }
}

fn refused(code: ErrorCode, message: impl Into<String>) -> ServiceError {
    ServiceError::Refused(CommandError::new(code, message))
}

/// Calls one service.
#[derive(Debug, Clone)]
pub struct ServiceClient {
    service: String,
    project: Option<String>,
    target: String,
    metadata: Metadata,
    headers: Vec<(String, String)>,
}

impl ServiceClient {
    fn new(service: &str, project: Option<&str>, metadata: Metadata) -> ServiceClient {
        ServiceClient {
            service: service.to_string(),
            project: project.map(str::to_string),
            target: match project {
                Some(project) => format!("{project}/{service}"),
                None => service.to_string(),
            },
            metadata,
            headers: Vec::new(),
        }
    }

    /// The service as it was named: `name`, or `project/name`.
    pub fn target(&self) -> &str {
        &self.target
    }

    /// This client, sending `headers` with every call it makes.
    pub fn with_headers(mut self, headers: &[(&str, &str)]) -> ServiceClient {
        self.headers
            .extend(headers.iter().map(|(k, v)| (k.to_string(), v.to_string())));
        self
    }

    /// A JSON answer, decoded as `R`. A status outside 2xx is [`ServiceError::CallFailed`].
    pub fn get<R: DeserializeOwned + 'static>(&self, path: &str) -> Result<R, ServiceError> {
        self.decoded(self.request("GET", path, RequestOptions::default())?)
    }

    /// A text answer: what a route returning a string sends.
    pub fn get_text(&self, path: &str) -> Result<String, ServiceError> {
        let response = self.succeeded(self.request("GET", path, RequestOptions::default())?)?;
        match response.text() {
            Ok(text) => Ok(text.to_string()),
            Err(e) => Err(refused(
                ErrorCode::Internal,
                format!("the answer of {} is not text: {e}", self.target),
            )),
        }
    }

    /// Sends `body` as JSON and decodes the JSON answer as `R`.
    pub fn post<B, R>(&self, path: &str, body: &B) -> Result<R, ServiceError>
    where
        B: Serialize + 'static,
        R: DeserializeOwned + 'static,
    {
        self.decoded(self.with_body("POST", path, body)?)
    }

    /// Sends `body` as JSON and decodes the JSON answer as `R`.
    pub fn put<B, R>(&self, path: &str, body: &B) -> Result<R, ServiceError>
    where
        B: Serialize + 'static,
        R: DeserializeOwned + 'static,
    {
        self.decoded(self.with_body("PUT", path, body)?)
    }

    /// For a route that answers nothing: succeeds on any 2xx.
    pub fn delete(&self, path: &str) -> Result<(), ServiceError> {
        self.succeeded(self.request("DELETE", path, RequestOptions::default())?)
            .map(|_| ())
    }

    /// One request, answered whatever its status. It fails only when no answer came, or when the
    /// runtime or this crate refused to make it.
    pub fn request(
        &self,
        method: &str,
        path: &str,
        options: RequestOptions,
    ) -> Result<ServiceResponse, ServiceError> {
        if let Some(size) = options.body.as_ref().map(Vec::len)
            && size > MAX_BODY_BYTES
        {
            return Err(refused(
                ErrorCode::BadRequest,
                format!("a call's body is at most {MAX_BODY_BYTES} bytes; this one is {size}"),
            ));
        }
        let headers = self.headers.iter().chain(options.headers.iter());
        let request = proto::ServiceRequest {
            service: self.service.clone(),
            project: self.project.clone(),
            method: method.to_string(),
            path: path.to_string(),
            headers: headers
                .map(|(name, value)| proto::http_request::Pair {
                    name: name.clone(),
                    value: value.clone(),
                })
                .collect(),
            content_type: options.content_type,
            body: options.body,
            metadata: Some(self.metadata.to_proto()),
        };
        let reply = call_request(&request.encode_to_vec());
        let reply = proto::ServiceReply::decode(reply.as_slice()).unwrap_or_else(|e| {
            panic!(
                "the runtime's answer to a call to {} does not decode: {e}",
                self.target
            )
        });
        self.answer(reply)
    }

    fn answer(&self, reply: proto::ServiceReply) -> Result<ServiceResponse, ServiceError> {
        use proto::service_failure::Reason;
        use proto::service_reply::Result as Answer;
        let service = self.target.clone();
        match reply.result {
            Some(Answer::Response(response)) => Ok(ServiceResponse {
                status: response.status as u16,
                content_type: response.content_type,
                body: response.body,
                headers: response
                    .headers
                    .into_iter()
                    .map(|pair| (pair.name, pair.value))
                    .collect(),
            }),
            Some(Answer::Failure(failure)) => Err(match Reason::try_from(failure.reason) {
                Ok(Reason::Unresolvable) => ServiceError::Unresolvable {
                    service,
                    reason: failure.detail,
                },
                Ok(Reason::IdentityMismatch) => ServiceError::IdentityMismatch {
                    service,
                    detail: failure.detail,
                },
                // A reason this crate does not know is read as the one that sends nothing twice.
                _ => ServiceError::Unanswered {
                    service,
                    reason: failure.detail,
                },
            }),
            Some(Answer::Error(error)) => Err(ServiceError::Refused(CommandError::new(
                ErrorCode::from_proto(error.code),
                error.message,
            ))),
            None => Err(refused(
                ErrorCode::Internal,
                format!("the runtime answered a call to {service} with nothing"),
            )),
        }
    }

    fn with_body<B: Serialize + 'static>(
        &self,
        method: &str,
        path: &str,
        body: &B,
    ) -> Result<ServiceResponse, ServiceError> {
        let payload = encode_payload(body).map_err(|e| refused(ErrorCode::BadRequest, e.0))?;
        self.request(
            method,
            path,
            RequestOptions {
                body: Some(payload.data),
                content_type: Some(payload.content_type),
                headers: Vec::new(),
            },
        )
    }

    fn succeeded(&self, response: ServiceResponse) -> Result<ServiceResponse, ServiceError> {
        if response.succeeded() {
            Ok(response)
        } else {
            Err(ServiceError::CallFailed {
                service: self.target.clone(),
                status: response.status,
                body: response.body,
            })
        }
    }

    fn decoded<R: DeserializeOwned + 'static>(
        &self,
        response: ServiceResponse,
    ) -> Result<R, ServiceError> {
        let response = self.succeeded(response)?;
        let payload = proto::Payload {
            content_type: response.content_type,
            data: response.body,
            ..Default::default()
        };
        decode_payload(&payload).map_err(|e| {
            refused(
                ErrorCode::Internal,
                format!("the answer of {} does not decode: {}", self.target, e.0),
            )
        })
    }
}
