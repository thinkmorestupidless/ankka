//! HTTP endpoints: routes declared here and served by the runtime. The runtime's router matches a
//! request to a declared route, applies the ACL and forwards it; the module only runs the handler.
//!
//! ```ignore
//! pub struct CartApi;
//!
//! impl Endpoint for CartApi {
//!     const ENDPOINT_ID: &'static str = "CartApi";
//!     const PREFIX: &'static str = "/carts";
//!
//!     fn acl() -> Acl { Acl::AllowAll }
//!
//!     fn routes() -> Routes<Self> {
//!         Routes::new()
//!             .get("/{cartId}", get_cart)
//!             .post("/{cartId}/items", add_item)          // fn(&Request, LineItem) -> …
//!             .delete("/{cartId}/items/{productId}", remove_item)
//!     }
//! }
//! ```
//!
//! A handler answers `Result<R, HttpProblem>`, where `R` is a [`Response`] or any value its
//! default codec encodes. A `post`, `put` or `patch` handler takes the request's body, decoded
//! as its second parameter's type; `()` there means the route takes no body. A route answers
//! whole: nothing here streams, since a module answers every call at once.

use std::marker::PhantomData;

use serde::de::DeserializeOwned;

use crate::client::Client;
use crate::codec::Auto;
use crate::context::{Context, Metadata};
use crate::effects::http::{HttpProblem, IntoResponse, Response};
use crate::proto;

/// An HTTP endpoint. Implement it on a unit struct and hand the value to
/// [`Service::endpoint`](crate::Service::endpoint).
pub trait Endpoint: Sized + 'static {
    /// The endpoint's id: what log lines and the console name it by, unique within the service.
    const ENDPOINT_ID: &'static str;

    /// The path every route is under, starting with `/`: `"/carts"`.
    const PREFIX: &'static str;

    /// Who may call it. Required, not defaulted: an endpoint open to the internet because nobody
    /// said otherwise is a decision nobody made.
    fn acl() -> Acl;

    /// The routes, relative to [`PREFIX`](Endpoint::PREFIX).
    fn routes() -> Routes<Self>;
}

/// Who may call an endpoint or a route. In a cluster the caller is read from the certificate the
/// platform issued the calling workload, never from the request; outside one every caller is the
/// local machine, which `Callers` admits.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Acl {
    /// Anyone.
    AllowAll,
    /// No one.
    DenyAll,
    /// A caller with a verified token; the route sees its [`Principal`].
    Authenticated,
    /// Only the callers named.
    Callers(Vec<CallerMatcher>),
}

impl Acl {
    fn kind(&self) -> proto::endpoint::Acl {
        match self {
            Acl::AllowAll => proto::endpoint::Acl::AllowAll,
            Acl::DenyAll => proto::endpoint::Acl::DenyAll,
            Acl::Authenticated => proto::endpoint::Acl::Authenticated,
            Acl::Callers(_) => proto::endpoint::Acl::Callers,
        }
    }

    fn callers(&self) -> Vec<proto::CallerMatcher> {
        match self {
            Acl::Callers(matchers) => matchers.iter().map(CallerMatcher::to_proto).collect(),
            _ => Vec::new(),
        }
    }
}

/// One kind of caller an [`Acl::Callers`] admits.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CallerMatcher {
    /// Requests from the internet, through the installation's gateway.
    Internet,
    /// A named service — in this service's project unless `project` names another.
    Service {
        /// The service's name.
        name: String,
        /// Its project; `None` for this service's own.
        project: Option<String>,
    },
    /// Any service in this service's project.
    AnyInProject,
    /// This service itself.
    SelfService,
}

impl CallerMatcher {
    /// A named service in this service's own project.
    pub fn service(name: impl Into<String>) -> CallerMatcher {
        CallerMatcher::Service {
            name: name.into(),
            project: None,
        }
    }

    fn to_proto(&self) -> proto::CallerMatcher {
        use proto::caller_matcher::Kind;
        let kind = match self {
            CallerMatcher::Internet => Kind::Internet(proto::Empty {}),
            CallerMatcher::AnyInProject => Kind::AnyInProject(proto::Empty {}),
            CallerMatcher::SelfService => Kind::Self_(proto::Empty {}),
            CallerMatcher::Service { name, project } => Kind::Service(proto::NamedService {
                project: project.clone(),
                name: name.clone(),
            }),
        };
        proto::CallerMatcher { kind: Some(kind) }
    }
}

/// The verified identity a request carried, on a route whose ACL is [`Acl::Authenticated`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Principal {
    /// The token's subject: the one stable key for a user.
    pub subject: String,
    /// Their name, for display.
    pub name: Option<String>,
    /// Their email, for display.
    pub email: Option<String>,
    /// Whether the identity provider verified the email.
    pub email_verified: bool,
    /// Their roles.
    pub roles: Vec<String>,
    /// Every other claim of the verified token, as text.
    pub claims: std::collections::BTreeMap<String, String>,
    /// The service's own name for the issuer that verified the token, from `ANKKA_AUTH_ISSUERS`.
    pub issuer: Option<String>,
}

/// Which workload sent a request, as the platform established it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Caller {
    /// The internet, through the gateway.
    Gateway,
    /// A service of this installation.
    Service {
        /// Its project.
        project: String,
        /// Its name.
        name: String,
    },
    /// The local machine, outside a cluster.
    Local,
}

/// A request forwarded to a route.
#[derive(Debug, Clone)]
pub struct Request {
    names: Vec<String>,
    path_args: Vec<String>,
    query: Vec<(String, String)>,
    headers: Vec<(String, String)>,
    content_type: String,
    body: Vec<u8>,
    principal: Option<Principal>,
    caller: Caller,
    context: Context,
}

impl Request {
    /// The path parameter the route's template names `name`: `"cartId"` in `/{cartId}/items`.
    ///
    /// # Panics
    ///
    /// When the template names no such parameter, which is a mistake in the route, not the
    /// request.
    pub fn path(&self, name: &str) -> &str {
        match self.names.iter().position(|n| n == name) {
            Some(i) => self.path_args.get(i).map_or("", String::as_str),
            None => panic!("the route's template has no parameter '{name}'"),
        }
    }

    /// The path parameters, in template order.
    pub fn path_args(&self) -> &[String] {
        &self.path_args
    }

    /// The first query parameter `name`.
    pub fn query(&self, name: &str) -> Option<&str> {
        self.query
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, v)| v.as_str())
    }

    /// Every query parameter, in request order.
    pub fn query_pairs(&self) -> &[(String, String)] {
        &self.query
    }

    /// The first header `name`, matched without regard to case.
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(n, _)| n.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    /// The request body's content type.
    pub fn content_type(&self) -> &str {
        &self.content_type
    }

    /// The raw body.
    pub fn body(&self) -> &[u8] {
        &self.body
    }

    /// The body decoded as `T` by its default codec; one that does not decode is a `400`.
    pub fn body_as<T: DeserializeOwned + 'static>(&self) -> Result<T, HttpProblem> {
        Auto::<T>::new()
            .decode_value(&self.body)
            .map_err(|e| HttpProblem::new(400, format!("cannot decode the request body: {e}")))
    }

    /// The verified identity, on an [`Acl::Authenticated`] route.
    pub fn principal(&self) -> Option<&Principal> {
        self.principal.as_ref()
    }

    /// Which workload sent the request.
    pub fn caller(&self) -> &Caller {
        &self.caller
    }

    /// The request's metadata: its trace, and the runtime's clock.
    pub fn metadata(&self) -> &Metadata {
        self.context.metadata()
    }

    /// The request as a handler's context: the clock, the client.
    pub fn context(&self) -> &Context {
        &self.context
    }

    /// A client for calling components, carrying this request's trace on.
    pub fn client(&self) -> Client {
        self.context.client()
    }
}

type RouteHandler = Box<dyn Fn(&Request) -> Result<Response, HttpProblem>>;

struct RouteEntry {
    method: &'static str,
    template: String,
    names: Vec<String>,
    has_body: bool,
    acl: Option<Acl>,
    handler: RouteHandler,
}

impl RouteEntry {
    fn id(&self) -> String {
        format!("{} {}", self.method, self.template)
    }
}

/// An endpoint's routes.
pub struct Routes<E: Endpoint> {
    entries: Vec<RouteEntry>,
    problems: Vec<String>,
    marker: PhantomData<fn() -> E>,
}

impl<E: Endpoint> Default for Routes<E> {
    fn default() -> Routes<E> {
        Routes::new()
    }
}

impl<E: Endpoint> Routes<E> {
    /// No routes yet.
    pub fn new() -> Routes<E> {
        Routes {
            entries: Vec::new(),
            problems: Vec::new(),
            marker: PhantomData,
        }
    }

    /// `GET template`.
    pub fn get<R, F>(self, template: &str, handler: F) -> Routes<E>
    where
        R: IntoResponse,
        F: Fn(&Request) -> Result<R, HttpProblem> + 'static,
    {
        self.without_body("GET", template, handler)
    }

    /// `DELETE template`.
    pub fn delete<R, F>(self, template: &str, handler: F) -> Routes<E>
    where
        R: IntoResponse,
        F: Fn(&Request) -> Result<R, HttpProblem> + 'static,
    {
        self.without_body("DELETE", template, handler)
    }

    /// `POST template`, the body decoded as `B` (`()` for none).
    pub fn post<B, R, F>(self, template: &str, handler: F) -> Routes<E>
    where
        B: DeserializeOwned + 'static,
        R: IntoResponse,
        F: Fn(&Request, B) -> Result<R, HttpProblem> + 'static,
    {
        self.with_body("POST", template, handler)
    }

    /// `PUT template`, the body decoded as `B` (`()` for none).
    pub fn put<B, R, F>(self, template: &str, handler: F) -> Routes<E>
    where
        B: DeserializeOwned + 'static,
        R: IntoResponse,
        F: Fn(&Request, B) -> Result<R, HttpProblem> + 'static,
    {
        self.with_body("PUT", template, handler)
    }

    /// `PATCH template`, the body decoded as `B` (`()` for none).
    pub fn patch<B, R, F>(self, template: &str, handler: F) -> Routes<E>
    where
        B: DeserializeOwned + 'static,
        R: IntoResponse,
        F: Fn(&Request, B) -> Result<R, HttpProblem> + 'static,
    {
        self.with_body("PATCH", template, handler)
    }

    /// Gives the route declared last its own ACL, replacing the endpoint's for it alone.
    pub fn with_acl(mut self, acl: Acl) -> Routes<E> {
        match self.entries.last_mut() {
            Some(route) => route.acl = Some(acl),
            None => self.problems.push(format!(
                "endpoint '{}' gives an acl before declaring any route",
                E::ENDPOINT_ID
            )),
        }
        self
    }

    fn without_body<R, F>(self, method: &'static str, template: &str, handler: F) -> Routes<E>
    where
        R: IntoResponse,
        F: Fn(&Request) -> Result<R, HttpProblem> + 'static,
    {
        self.add(
            method,
            template,
            false,
            Box::new(move |request| handler(request)?.into_response()),
        )
    }

    fn with_body<B, R, F>(self, method: &'static str, template: &str, handler: F) -> Routes<E>
    where
        B: DeserializeOwned + 'static,
        R: IntoResponse,
        F: Fn(&Request, B) -> Result<R, HttpProblem> + 'static,
    {
        let has_body = std::any::type_name::<B>() != std::any::type_name::<()>();
        self.add(
            method,
            template,
            has_body,
            Box::new(move |request| {
                let body: B = request.body_as()?;
                handler(request, body)?.into_response()
            }),
        )
    }

    fn add(
        mut self,
        method: &'static str,
        template: &str,
        has_body: bool,
        handler: RouteHandler,
    ) -> Routes<E> {
        let owner = E::ENDPOINT_ID;
        if !template.starts_with('/') {
            self.problems.push(format!(
                "endpoint '{owner}': template '{template}' must start with '/'"
            ));
        }
        let names = match placeholders(template) {
            Ok(names) => names,
            Err(problem) => {
                self.problems.push(format!("endpoint '{owner}': {problem}"));
                Vec::new()
            }
        };
        if self
            .entries
            .iter()
            .any(|e| e.method == method && e.template == template)
        {
            self.problems.push(format!(
                "endpoint '{owner}' declares {method} {template} twice"
            ));
            return self;
        }
        self.entries.push(RouteEntry {
            method,
            template: template.to_string(),
            names,
            has_body,
            acl: None,
            handler,
        });
        self
    }
}

/// The parameter names a template declares, in order: `/{cartId}/items/{productId}`.
fn placeholders(template: &str) -> Result<Vec<String>, String> {
    let mut names = Vec::new();
    let mut rest = template;
    while let Some(open) = rest.find('{') {
        let after = &rest[open + 1..];
        let Some(close) = after.find('}') else {
            return Err(format!("template '{template}' has unbalanced braces"));
        };
        let name = &after[..close];
        let valid = name
            .chars()
            .next()
            .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
            && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_');
        if !valid {
            return Err(format!(
                "template '{template}' has a parameter named '{name}', which is not a name"
            ));
        }
        names.push(name.to_string());
        rest = &after[close + 1..];
    }
    if rest.contains('}') {
        return Err(format!("template '{template}' has unbalanced braces"));
    }
    Ok(names)
}

/// An endpoint as the service's registry holds it. Not implemented by services.
#[doc(hidden)]
pub trait RegisteredEndpoint {
    /// The endpoint's id.
    fn id(&self) -> &str;

    /// Its prefix.
    fn prefix(&self) -> &str;

    /// The endpoint as discovery describes it.
    fn to_endpoint(&self) -> proto::Endpoint;

    /// What is wrong with the declaration, all at once.
    fn problems(&self) -> Vec<String>;

    /// Runs the route a request was matched to.
    fn handle(&self, request: proto::HttpRequest) -> proto::HttpReply;
}

pub(crate) struct EndpointRegistration<E: Endpoint> {
    routes: Routes<E>,
}

impl<E: Endpoint> EndpointRegistration<E> {
    pub(crate) fn new() -> EndpointRegistration<E> {
        EndpointRegistration {
            routes: E::routes(),
        }
    }

    /// Every route as `(method, template, id)`: what the unit testkit matches a path against.
    #[cfg(not(target_arch = "wasm32"))]
    pub(crate) fn route_table(&self) -> Vec<(String, String, String)> {
        self.routes
            .entries
            .iter()
            .map(|r| (r.method.to_string(), r.template.clone(), r.id()))
            .collect()
    }
}

impl<E: Endpoint> RegisteredEndpoint for EndpointRegistration<E> {
    fn id(&self) -> &str {
        E::ENDPOINT_ID
    }

    fn prefix(&self) -> &str {
        E::PREFIX
    }

    fn to_endpoint(&self) -> proto::Endpoint {
        let acl = E::acl();
        let mut routes: Vec<proto::Route> = self
            .routes
            .entries
            .iter()
            .map(|r| proto::Route {
                id: r.id(),
                method: r.method.to_string(),
                template: r.template.clone(),
                has_body: r.has_body,
                streaming: false,
                // A module cannot hold a socket: the runtime refuses a socket route from one.
                socket: false,
                // Left unset when the route says nothing, so the runtime reads "the endpoint's"
                // rather than ALLOW_ALL.
                acl: r.acl.as_ref().map(|a| a.kind() as i32),
                allow_callers: r.acl.as_ref().map(Acl::callers).unwrap_or_default(),
            })
            .collect();
        routes.sort_by(|a, b| a.id.cmp(&b.id));
        proto::Endpoint {
            id: E::ENDPOINT_ID.to_string(),
            prefix: E::PREFIX.to_string(),
            acl: acl.kind() as i32,
            routes,
            allow_callers: acl.callers(),
        }
    }

    fn problems(&self) -> Vec<String> {
        let owner = E::ENDPOINT_ID;
        let mut problems = self.routes.problems.clone();
        if owner.is_empty() {
            problems.push(format!("an endpoint with prefix '{}' has no id", E::PREFIX));
        }
        if !E::PREFIX.starts_with('/') {
            problems.push(format!(
                "endpoint '{owner}': prefix '{}' must start with '/'",
                E::PREFIX
            ));
        }
        let mut acls = vec![E::acl()];
        acls.extend(self.routes.entries.iter().filter_map(|r| r.acl.clone()));
        if acls
            .iter()
            .any(|a| matches!(a, Acl::Callers(matchers) if matchers.is_empty()))
        {
            problems.push(format!(
                "endpoint '{owner}': an acl of named callers must name at least one"
            ));
        }
        problems
    }

    fn handle(&self, request: proto::HttpRequest) -> proto::HttpReply {
        let Some(route) = self
            .routes
            .entries
            .iter()
            .find(|r| r.id() == request.route_id)
        else {
            return proto::HttpReply {
                message: Some(proto::http_reply::Message::Failure(proto::Failure {
                    command_id: 0,
                    error: Some(proto::Error {
                        message: format!("unknown route {}/{}", E::ENDPOINT_ID, request.route_id),
                        code: proto::ErrorCode::NotFound as i32,
                    }),
                })),
            };
        };
        let pairs = |pairs: Vec<proto::http_request::Pair>| -> Vec<(String, String)> {
            pairs.into_iter().map(|p| (p.name, p.value)).collect()
        };
        let metadata = Metadata::from_proto(request.metadata.as_ref());
        let forwarded = Request {
            names: route.names.clone(),
            path_args: request.path_args,
            query: pairs(request.query),
            headers: pairs(request.headers),
            content_type: request.content_type,
            body: request.body,
            principal: request.principal.map(|p| Principal {
                subject: p.subject,
                name: p.name,
                email: p.email,
                email_verified: p.email_verified,
                roles: p.roles,
                claims: p.claims.into_iter().collect(),
                issuer: p.issuer,
            }),
            caller: caller_of(request.caller),
            context: Context::new(E::ENDPOINT_ID, "", 0, metadata).with_secrets(),
        };
        let response = (route.handler)(&forwarded).unwrap_or_else(|problem| Response {
            status: problem.status,
            content_type: "text/plain".to_string(),
            body: problem.message.into_bytes(),
            headers: Vec::new(),
        });
        proto::HttpReply {
            message: Some(proto::http_reply::Message::Response(proto::HttpResponse {
                status: i32::from(response.status),
                content_type: response.content_type,
                body: response.body,
                headers: response
                    .headers
                    .into_iter()
                    .map(|(name, value)| proto::http_request::Pair { name, value })
                    .collect(),
            })),
        }
    }
}

fn caller_of(caller: Option<proto::Caller>) -> Caller {
    use proto::caller::Kind;
    match caller.and_then(|c| c.kind) {
        Some(Kind::Gateway(_)) => Caller::Gateway,
        Some(Kind::Service(s)) => Caller::Service {
            project: s.project,
            name: s.name,
        },
        Some(Kind::Local(_)) | None => Caller::Local,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Done;
    use serde::Deserialize;

    #[derive(Deserialize)]
    struct Item {
        name: String,
    }

    struct Api;

    impl Endpoint for Api {
        const ENDPOINT_ID: &'static str = "Api";
        const PREFIX: &'static str = "/things";

        fn acl() -> Acl {
            Acl::AllowAll
        }

        fn routes() -> Routes<Api> {
            Routes::new()
                .get("/{id}", |r: &Request| Ok(format!("thing {}", r.path("id"))))
                .post("/{id}/items", |r: &Request, item: Item| {
                    Ok(format!("{} gets {}", r.path("id"), item.name))
                })
                .post("/{id}/touch", |_: &Request, (): ()| Ok(Done))
                .with_acl(Acl::Callers(vec![CallerMatcher::service("orders")]))
                .delete("/{id}", |_: &Request| -> Result<Done, HttpProblem> {
                    Err(HttpProblem::new(409, "in use"))
                })
        }
    }

    fn call(method: &str, template: &str, args: &[&str], body: &[u8]) -> proto::HttpResponse {
        let reply = EndpointRegistration::<Api>::new().handle(proto::HttpRequest {
            endpoint_id: "Api".into(),
            route_id: format!("{method} {template}"),
            path_args: args.iter().map(|a| a.to_string()).collect(),
            body: body.to_vec(),
            ..Default::default()
        });
        match reply.message {
            Some(proto::http_reply::Message::Response(r)) => r,
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn discovery_describes_the_routes_their_bodies_and_their_own_acls() {
        let endpoint = EndpointRegistration::<Api>::new().to_endpoint();
        assert_eq!(
            (endpoint.id.as_str(), endpoint.prefix.as_str()),
            ("Api", "/things")
        );
        let routes: Vec<(&str, bool, Option<i32>)> = endpoint
            .routes
            .iter()
            .map(|r| (r.id.as_str(), r.has_body, r.acl))
            .collect();
        assert_eq!(
            routes,
            vec![
                ("DELETE /{id}", false, None),
                ("GET /{id}", false, None),
                ("POST /{id}/items", true, None),
                (
                    "POST /{id}/touch",
                    false,
                    Some(proto::endpoint::Acl::Callers as i32)
                ),
            ]
        );
        assert!(endpoint.routes.iter().all(|r| !r.streaming));
    }

    #[test]
    fn a_route_binds_its_path_and_body_and_answers_its_value() {
        let got = call("GET", "/{id}", &["t1"], b"");
        assert_eq!(
            (got.status, got.body.as_slice()),
            (200, b"thing t1".as_slice())
        );
        let posted = call("POST", "/{id}/items", &["t1"], br#"{"name":"pen"}"#);
        assert_eq!(posted.body, b"t1 gets pen");
        assert_eq!(call("POST", "/{id}/touch", &["t1"], b"").status, 204);
    }

    #[test]
    fn a_problem_and_a_bad_body_answer_their_status() {
        let refused = call("DELETE", "/{id}", &["t1"], b"");
        assert_eq!(
            (refused.status, refused.body.as_slice()),
            (409, b"in use".as_slice())
        );
        assert_eq!(
            call("POST", "/{id}/items", &["t1"], b"not json").status,
            400
        );
    }

    #[test]
    fn templates_and_acls_are_checked_when_registered() {
        struct Bad;
        impl Endpoint for Bad {
            const ENDPOINT_ID: &'static str = "Bad";
            const PREFIX: &'static str = "things";
            fn acl() -> Acl {
                Acl::Callers(vec![])
            }
            fn routes() -> Routes<Bad> {
                Routes::new()
                    .get("{id", |_: &Request| Ok(Done))
                    .get("/a", |_: &Request| Ok(Done))
                    .get("/a", |_: &Request| Ok(Done))
            }
        }
        let problems = EndpointRegistration::<Bad>::new().problems();
        for expected in [
            "must start with '/'",
            "unbalanced braces",
            "GET /a twice",
            "prefix 'things'",
            "at least one",
        ] {
            assert!(
                problems.iter().any(|p| p.contains(expected)),
                "{expected} in {problems:?}"
            );
        }
    }
}
