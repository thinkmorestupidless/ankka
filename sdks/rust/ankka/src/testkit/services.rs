//! What a unit test gives a handler in place of the runtime's three newer imports: other services
//! that answer from a script, a clock that says what the test chose, and random bytes the test
//! knows beforehand.
//!
//! Each runs a closure with the stand-in in place on this thread, beside whatever a testkit
//! installs for component calls:
//!
//! ```ignore
//! let services = ScriptedServices::new();
//! services.answer("wallet", |_| ScriptedServices::json(r#"{"id":"r1"}"#));
//! services.run(|| kit.deliver(&checked_out));
//! assert_eq!(services.requests()[0].path, "/internal/credits");
//! ```

use std::cell::RefCell;
use std::collections::HashMap;
use std::rc::Rc;

use crate::abi::imports::{with_native_clock, with_native_random, with_native_services};
use crate::codec::time::Instant;
use crate::proto;
use crate::services::ServiceResponse;

/// One request a handler made to a scripted service.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScriptedRequest {
    /// The service's name.
    pub service: String,
    /// The project named, when one was.
    pub project: Option<String>,
    /// The method.
    pub method: String,
    /// The path, its query included.
    pub path: String,
    /// The headers, in the order they were sent.
    pub headers: Vec<(String, String)>,
    /// The body's content type, when there was one.
    pub content_type: Option<String>,
    /// The body's bytes; empty when there was none.
    pub body: Vec<u8>,
}

impl ScriptedRequest {
    /// The body as text.
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    /// The first header named `name`, whatever its case.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    /// The service as it was named: `name`, or `project/name`.
    pub fn target(&self) -> String {
        match &self.project {
            Some(project) => format!("{project}/{}", self.service),
            None => self.service.clone(),
        }
    }
}

type Answer = Rc<dyn Fn(&ScriptedRequest) -> ServiceResponse>;

#[derive(Clone)]
enum Script {
    Answer(Answer),
    Unresolvable,
    Unanswered,
    Mismatch,
}

#[derive(Default)]
struct Scripts {
    by_target: RefCell<HashMap<String, Script>>,
    requests: RefCell<Vec<ScriptedRequest>>,
}

/// Other services, played from a script. A service is named as a handler names it: `wallet`, or
/// `billing/invoices` for one in another project. A call to a service with no script fails the
/// test, naming it: a call that quietly returned a default would not be testing what it says.
#[derive(Clone, Default)]
pub struct ScriptedServices {
    scripts: Rc<Scripts>,
}

impl ScriptedServices {
    /// No service scripted yet.
    pub fn new() -> ScriptedServices {
        ScriptedServices::default()
    }

    /// What `service` answers, from the request it was sent.
    pub fn answer(
        &self,
        service: &str,
        answer: impl Fn(&ScriptedRequest) -> ServiceResponse + 'static,
    ) -> &ScriptedServices {
        self.script(service, Script::Answer(Rc::new(answer)))
    }

    /// `service` cannot be found: nothing is sent.
    pub fn unresolvable(&self, service: &str) -> &ScriptedServices {
        self.script(service, Script::Unresolvable)
    }

    /// `service` gives no answer.
    pub fn unanswered(&self, service: &str) -> &ScriptedServices {
        self.script(service, Script::Unanswered)
    }

    /// What answers as `service` is not it: nothing is sent.
    pub fn mismatch(&self, service: &str) -> &ScriptedServices {
        self.script(service, Script::Mismatch)
    }

    /// Every request made, in order, whatever it was answered.
    pub fn requests(&self) -> Vec<ScriptedRequest> {
        self.scripts.requests.borrow().clone()
    }

    /// Runs `f` with these services answering every call to another service made on this thread.
    pub fn run<T>(&self, f: impl FnOnce() -> T) -> T {
        let scripts = self.scripts.clone();
        with_native_services(move |request| scripts.reply(request), f)
    }

    /// A text answer.
    pub fn text(text: &str) -> ServiceResponse {
        ScriptedServices::status(200, "text/plain", text)
    }

    /// A JSON answer.
    pub fn json(json: &str) -> ServiceResponse {
        ScriptedServices::status(200, "application/json", json)
    }

    /// An answer with `status`, a content type and a body.
    pub fn status(status: u16, content_type: &str, body: &str) -> ServiceResponse {
        ServiceResponse {
            status,
            content_type: content_type.to_string(),
            body: body.as_bytes().to_vec(),
            headers: Vec::new(),
        }
    }

    fn script(&self, service: &str, script: Script) -> &ScriptedServices {
        self.scripts
            .by_target
            .borrow_mut()
            .insert(service.to_string(), script);
        self
    }
}

impl Scripts {
    fn reply(&self, request: proto::ServiceRequest) -> proto::ServiceReply {
        use proto::service_failure::Reason;
        use proto::service_reply::Result as Answered;
        let asked = ScriptedRequest {
            service: request.service,
            project: request.project,
            method: request.method,
            path: request.path,
            headers: request
                .headers
                .into_iter()
                .map(|pair| (pair.name, pair.value))
                .collect(),
            content_type: request.content_type,
            body: request.body.unwrap_or_default(),
        };
        let target = asked.target();
        let script = self.by_target.borrow().get(&target).cloned();
        let Some(script) = script else {
            panic!(
                "no script for the service {target}: say what it answers with ScriptedServices::answer"
            );
        };
        self.requests.borrow_mut().push(asked.clone());
        let failure = |reason: Reason, detail: String| {
            Answered::Failure(proto::ServiceFailure {
                reason: reason as i32,
                detail,
            })
        };
        let result = match script {
            Script::Answer(answer) => {
                let response = answer(&asked);
                Answered::Response(proto::HttpResponse {
                    status: i32::from(response.status),
                    content_type: response.content_type,
                    body: response.body,
                    headers: response
                        .headers
                        .into_iter()
                        .map(|(name, value)| proto::http_request::Pair { name, value })
                        .collect(),
                })
            }
            Script::Unresolvable => failure(
                Reason::Unresolvable,
                format!("no service {target} was found"),
            ),
            Script::Unanswered => failure(Reason::Unanswered, "no answer came".to_string()),
            Script::Mismatch => failure(
                Reason::IdentityMismatch,
                format!("what answers as {target} is another service"),
            ),
        };
        proto::ServiceReply {
            result: Some(result),
        }
    }
}

/// Runs `f` with [`Context::now`](crate::Context::now) answering `now` on this thread.
pub fn with_clock<T>(now: Instant, f: impl FnOnce() -> T) -> T {
    with_native_clock(now.epoch_millis(), f)
}

/// Runs `f` with [`Context::random`](crate::Context::random) handing out `bytes` on this thread,
/// in order, and from the start again when they run out.
pub fn with_random<T>(bytes: &[u8], f: impl FnOnce() -> T) -> T {
    with_native_random(bytes, f)
}
