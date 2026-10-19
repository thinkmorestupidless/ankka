//! MCP endpoints: a service's own tools, served to MCP clients at a path of its own (protocol
//! 1.20). The runtime speaks the protocol — the session, `tools/list`, the arguments checked
//! against each schema, the ACL, a client key's scope — and asks the module only to run a tool,
//! with the checked arguments, the principal and the caller. The credential never reaches it.
//!
//! ```ignore
//! pub struct CartMcp;
//!
//! impl McpEndpoint for CartMcp {
//!     const ENDPOINT_ID: &'static str = "cart-mcp";
//!     const PATH: &'static str = "/mcp";
//!
//!     fn acl() -> Acl { Acl::AnyOf(vec![Acl::ClientKeys, Acl::Authenticated]) }
//!
//!     fn tools() -> McpTools<Self> {
//!         McpTools::new()
//!             .tool("get_cart", "Reads one cart",
//!                 Schema::object().string("cartId", "The cart's id"),
//!                 |args: GetCart, request: &McpRequest| {
//!                     request.client().invoke::<_, _, Cart, _>(ShoppingCart, &args.cart_id, "get-cart", ())
//!                 })
//!             .command("checkout", "Checks a cart out", ShoppingCart, "checkout", "cartId", Schema::object())
//!             .route("todays_orders", "The day's orders", OrdersApi::ENDPOINT_ID, "GET", "/orders/{day}")
//!     }
//! }
//! ```
//!
//! A tool answers `Result<R, CommandError>`: a refusal is a tool error the client is shown, with
//! its message. `R` becomes the answer's text — a string as itself, `Done` as `done`, anything else
//! as its JSON — and, when it is a JSON object, its structured content too.
//!
//! A Rust type carries no description of its fields at run time, so a tool's schema is written
//! with [`Schema`]; the runtime checks the arguments against it, and the tool decodes them into its
//! own type. A [`command`](McpTools::command) or [`route`](McpTools::route) reference is resolved
//! into an ordinary tool here, and checked against what the service registers when it is built.

use std::marker::PhantomData;

use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::{Map, Value};

use super::endpoint::{Acl, Caller, Principal, caller_of, principal_of};
use super::{ComponentOf, Schema};
use crate::client::Client;
use crate::client_keys::ClientKeys;
use crate::context::{Context, Metadata};
use crate::effects::CommandError;
use crate::proto::{self, Kind};

/// An MCP endpoint. Implement it on a unit struct and hand the value to
/// [`Service::mcp_endpoint`](crate::Service::mcp_endpoint).
pub trait McpEndpoint: Sized + 'static {
    /// The endpoint's id: what a client key names and log lines show, unique within the service.
    const ENDPOINT_ID: &'static str;

    /// The path MCP clients post to, starting with `/`: `"/mcp"`.
    const PATH: &'static str;

    /// The server's name in the protocol's `initialize`; empty for the service's own.
    const NAME: &'static str = "";

    /// The server's version in the protocol's `initialize`; empty for the service's own.
    const VERSION: &'static str = "";

    /// Whether the runtime brokers authorization for clients that cannot hold a token of the
    /// service's issuer (needs [`Acl::Authenticated`] among the ACL's members).
    const BROKERED: bool = false;

    /// Who may call it. Required, not defaulted, as an HTTP endpoint's is.
    fn acl() -> Acl;

    /// The tools, by name.
    fn tools() -> McpTools<Self>;
}

/// A call of one tool: the principal the credential proved, the caller, and the call's context.
#[derive(Debug, Clone)]
pub struct McpRequest {
    principal: Option<Principal>,
    caller: Caller,
    context: Context,
    wire_principal: Option<proto::Principal>,
    wire_caller: Option<proto::Caller>,
}

impl McpRequest {
    /// A request as the runtime builds it: what a test builds to call a tool directly.
    pub fn new(principal: Option<Principal>, caller: Caller, context: Context) -> McpRequest {
        McpRequest {
            principal,
            caller,
            context,
            wire_principal: None,
            wire_caller: None,
        }
    }

    /// Who the client key or the token named, when the ACL admitted the call by one.
    pub fn principal(&self) -> Option<&Principal> {
        self.principal.as_ref()
    }

    /// Which workload sent the call.
    pub fn caller(&self) -> &Caller {
        &self.caller
    }

    /// The call's metadata: its trace, and the runtime's clock.
    pub fn metadata(&self) -> &Metadata {
        self.context.metadata()
    }

    /// The call as a handler's context: the clock, the client, the secret store.
    pub fn context(&self) -> &Context {
        &self.context
    }

    /// A client for calling components, carrying this call's trace on.
    pub fn client(&self) -> Client {
        self.context.client()
    }

    /// The service's client keys.
    pub fn client_keys(&self) -> ClientKeys {
        ClientKeys::with_metadata(self.context.metadata().clone())
    }
}

/// What a tool's run answers: its answer, or the tool error's message.
type ToolRun =
    Box<dyn Fn(&str, &McpRequest, &dyn RouteCall) -> Result<proto::McpToolAnswer, String>>;

/// How a route reference reaches the route: the service's own endpoints, handed in by the service.
#[doc(hidden)]
pub trait RouteCall {
    /// Runs a route of an endpoint of this service.
    fn call_route(&self, request: proto::HttpRequest) -> proto::HttpReply;
}

/// What a reference tool names, checked against what the service registers.
#[derive(Debug, Clone, PartialEq, Eq)]
#[doc(hidden)]
pub enum Reference {
    /// A handler of a component.
    Handler {
        /// The component's kind.
        kind: Kind,
        /// The component's id.
        component_id: String,
        /// The handler's wire name.
        handler: String,
    },
    /// A route of an HTTP endpoint.
    Route {
        /// The endpoint's id.
        endpoint_id: String,
        /// The route's id: `"GET /orders/{day}"`.
        route_id: String,
    },
}

struct ToolEntry {
    name: String,
    description: String,
    schema: String,
    acl: Option<Acl>,
    reference: Option<Reference>,
    run: ToolRun,
}

/// An MCP endpoint's tools, by name.
pub struct McpTools<E: McpEndpoint> {
    entries: Vec<ToolEntry>,
    problems: Vec<String>,
    marker: PhantomData<fn() -> E>,
}

impl<E: McpEndpoint> Default for McpTools<E> {
    fn default() -> McpTools<E> {
        McpTools::new()
    }
}

/// A value a tool answered, as the protocol carries it: a string as itself, nothing as `done`,
/// anything else as its JSON, with an object also as structured content.
pub(crate) fn answer_of(value: Value) -> proto::McpToolAnswer {
    match value {
        Value::String(text) => proto::McpToolAnswer {
            text,
            structured_json: None,
        },
        Value::Null => proto::McpToolAnswer {
            text: "done".to_string(),
            structured_json: None,
        },
        Value::Object(_) => {
            let text = value.to_string();
            proto::McpToolAnswer {
                structured_json: Some(text.clone()),
                text,
            }
        }
        other => proto::McpToolAnswer {
            text: other.to_string(),
            structured_json: None,
        },
    }
}

fn arguments_of(arguments: &str) -> Result<Map<String, Value>, String> {
    if arguments.trim().is_empty() {
        return Ok(Map::new());
    }
    match serde_json::from_str::<Value>(arguments) {
        Ok(Value::Object(map)) => Ok(map),
        Ok(other) => Err(format!("the arguments are not an object: {other}")),
        Err(e) => Err(format!("the arguments are not JSON: {e}")),
    }
}

/// A payload's value, read by its manifest: a primitive by its form, anything else as JSON.
fn value_of(payload: &proto::Payload) -> Result<Value, String> {
    if let Some(form) = crate::codec::codec_for_manifest(&payload.manifest) {
        return form.decode_value(&payload.data).map_err(|e| e.0);
    }
    if payload.data.is_empty() {
        return Ok(Value::Null);
    }
    serde_json::from_slice(&payload.data).map_err(|e| e.to_string())
}

impl<E: McpEndpoint> McpTools<E> {
    /// No tools yet.
    pub fn new() -> McpTools<E> {
        McpTools {
            entries: Vec::new(),
            problems: Vec::new(),
            marker: PhantomData,
        }
    }

    /// A tool `name` the model is told is `description`, whose arguments `schema` describes and
    /// `handler` takes decoded as `A`.
    pub fn tool<A, R, F>(
        self,
        name: &str,
        description: &str,
        schema: Schema,
        handler: F,
    ) -> McpTools<E>
    where
        A: DeserializeOwned + 'static,
        R: Serialize + 'static,
        F: Fn(A, &McpRequest) -> Result<R, CommandError> + 'static,
    {
        let tool = name.to_string();
        let run: ToolRun = Box::new(move |arguments, request, _| {
            let text = if arguments.trim().is_empty() {
                "{}"
            } else {
                arguments
            };
            let args: A = serde_json::from_str(text)
                .map_err(|e| format!("the arguments to '{tool}' do not decode: {e}"))?;
            let answered = handler(args, request).map_err(|e| e.message)?;
            let value = serde_json::to_value(&answered)
                .map_err(|e| format!("the answer of '{tool}' does not encode: {e}"))?;
            Ok(answer_of(value))
        });
        self.add(name, description, schema.to_json(), None, run)
    }

    /// A tool that calls handler `handler` of the entity or workflow `component`, the id read from
    /// the argument `id` and every other argument sent as its input (nothing, when there are none).
    /// `schema` describes those other arguments; the id is added to it.
    pub fn command<C, M>(
        self,
        name: &str,
        description: &str,
        component: C,
        handler: &str,
        id: &str,
        schema: Schema,
    ) -> McpTools<E>
    where
        C: ComponentOf<M>,
    {
        let _ = component;
        let reference = Reference::Handler {
            kind: C::kind(),
            component_id: C::component_id().to_string(),
            handler: handler.to_string(),
        };
        let schema = schema.with_leading_string(id, "The id of the one it acts on");
        let (kind, component_id, handler, id) = (
            C::kind(),
            C::component_id().to_string(),
            handler.to_string(),
            id.to_string(),
        );
        let run: ToolRun = Box::new(move |arguments, request, _| {
            let mut args = arguments_of(arguments)?;
            let entity_id = match args.remove(&id) {
                Some(Value::String(s)) => s,
                Some(other) => other.to_string(),
                None => return Err(format!("the argument '{id}' is required")),
            };
            let payload = if args.is_empty() {
                crate::codec::encode_payload(&())
            } else {
                crate::codec::encode_payload(&Value::Object(args))
            }
            .map_err(|e| e.0)?;
            let reply = request
                .client()
                .invoke_payload(kind, &component_id, &entity_id, &handler, payload)
                .map_err(|e| e.message)?;
            Ok(answer_of(value_of(&reply)?))
        });
        self.add(name, description, schema.to_json(), Some(reference), run)
    }

    /// A tool that calls route `method template` of this service's HTTP endpoint `endpoint_id`:
    /// each of the template's parameters is a string argument, and any other argument is sent as
    /// the body's JSON. A status of 400 or above is a tool error with the body's text.
    pub fn route(
        self,
        name: &str,
        description: &str,
        endpoint_id: &str,
        method: &str,
        template: &str,
    ) -> McpTools<E> {
        let method = method.to_ascii_uppercase();
        let route_id = format!("{method} {template}");
        let names = super::endpoint::template_names(template).unwrap_or_default();
        let mut schema = Schema::object();
        for n in names.iter().rev() {
            schema = schema.with_leading_string(n, &format!("The route's '{n}'"));
        }
        let reference = Reference::Route {
            endpoint_id: endpoint_id.to_string(),
            route_id: route_id.clone(),
        };
        let endpoint_id = endpoint_id.to_string();
        let run: ToolRun = Box::new(move |arguments, request, routes| {
            let mut args = arguments_of(arguments)?;
            let mut path_args = Vec::new();
            for n in &names {
                match args.remove(n) {
                    Some(Value::String(s)) => path_args.push(s),
                    Some(other) => path_args.push(other.to_string()),
                    None => return Err(format!("the argument '{n}' is required")),
                }
            }
            let body = if args.is_empty() {
                Vec::new()
            } else {
                Value::Object(args).to_string().into_bytes()
            };
            let reply = routes.call_route(proto::HttpRequest {
                endpoint_id: endpoint_id.clone(),
                route_id: route_id.clone(),
                path_args,
                content_type: if body.is_empty() {
                    String::new()
                } else {
                    "application/json".to_string()
                },
                body,
                principal: request.wire_principal.clone(),
                caller: request.wire_caller.clone(),
                metadata: Some(request.metadata().to_proto()),
                ..Default::default()
            });
            match reply.message {
                Some(proto::http_reply::Message::Response(r)) => {
                    let text = String::from_utf8_lossy(&r.body).into_owned();
                    if r.status >= 400 {
                        return Err(text);
                    }
                    if r.body.is_empty() {
                        return Ok(answer_of(Value::Null));
                    }
                    if r.content_type.starts_with("application/json")
                        && let Ok(value) = serde_json::from_slice::<Value>(&r.body)
                    {
                        return Ok(answer_of(value));
                    }
                    Ok(answer_of(Value::String(text)))
                }
                Some(proto::http_reply::Message::Failure(f)) => Err(f
                    .error
                    .map(|e| e.message)
                    .unwrap_or_else(|| "the route failed".to_string())),
                None => Err("the route answered nothing".to_string()),
            }
        });
        self.add(name, description, schema.to_json(), Some(reference), run)
    }

    /// Gives the tool declared last its own ACL, replacing the endpoint's for it alone.
    pub fn with_acl(mut self, acl: Acl) -> McpTools<E> {
        match self.entries.last_mut() {
            Some(tool) => tool.acl = Some(acl),
            None => self.problems.push(format!(
                "MCP endpoint '{}' gives an acl before declaring any tool",
                E::ENDPOINT_ID
            )),
        }
        self
    }

    fn add(
        mut self,
        name: &str,
        description: &str,
        schema: String,
        reference: Option<Reference>,
        run: ToolRun,
    ) -> McpTools<E> {
        let owner = E::ENDPOINT_ID;
        let valid = !name.is_empty()
            && name.len() <= 64
            && name
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-');
        if !valid {
            self.problems.push(format!(
                "MCP endpoint '{owner}': tool name '{name}' is not 1 to 64 letters, digits, '_' or '-'"
            ));
        }
        if description.trim().is_empty() {
            self.problems.push(format!(
                "MCP endpoint '{owner}': tool '{name}' has no description"
            ));
        }
        if self.entries.iter().any(|t| t.name == name) {
            self.problems.push(format!(
                "MCP endpoint '{owner}' declares tool '{name}' twice"
            ));
            return self;
        }
        self.entries.push(ToolEntry {
            name: name.to_string(),
            description: description.to_string(),
            schema,
            acl: None,
            reference,
            run,
        });
        self
    }
}

/// An MCP endpoint as the service's registry holds it. Not implemented by services.
#[doc(hidden)]
pub trait RegisteredMcpEndpoint {
    /// The endpoint's id.
    fn id(&self) -> &str;

    /// Its path.
    fn path(&self) -> &str;

    /// The endpoint as discovery describes it.
    fn to_proto(&self) -> proto::McpEndpoint;

    /// What is wrong with the declaration, all at once.
    fn problems(&self) -> Vec<String>;

    /// Every reference tool, by name, and what it names.
    fn references(&self) -> Vec<(String, Reference)>;

    /// Runs the tool a call names.
    fn handle(&self, request: proto::McpToolRequest, routes: &dyn RouteCall)
    -> proto::McpToolReply;
}

pub(crate) struct McpEndpointRegistration<E: McpEndpoint> {
    tools: McpTools<E>,
}

impl<E: McpEndpoint> McpEndpointRegistration<E> {
    pub(crate) fn new() -> McpEndpointRegistration<E> {
        McpEndpointRegistration { tools: E::tools() }
    }
}

impl<E: McpEndpoint> RegisteredMcpEndpoint for McpEndpointRegistration<E> {
    fn id(&self) -> &str {
        E::ENDPOINT_ID
    }

    fn path(&self) -> &str {
        E::PATH
    }

    fn to_proto(&self) -> proto::McpEndpoint {
        proto::McpEndpoint {
            id: E::ENDPOINT_ID.to_string(),
            path: E::PATH.to_string(),
            name: E::NAME.to_string(),
            version: E::VERSION.to_string(),
            acl: Some(E::acl().to_mcp()),
            tools: self
                .tools
                .entries
                .iter()
                .map(|t| proto::McpTool {
                    name: t.name.clone(),
                    description: t.description.clone(),
                    input_schema_json: t.schema.clone(),
                    acl: t.acl.as_ref().map(Acl::to_mcp),
                })
                .collect(),
            brokered: E::BROKERED,
        }
    }

    fn problems(&self) -> Vec<String> {
        let owner = E::ENDPOINT_ID;
        let mut problems = self.tools.problems.clone();
        if owner.is_empty() {
            problems.push(format!("an MCP endpoint at '{}' has no id", E::PATH));
        }
        if !E::PATH.starts_with('/') {
            problems.push(format!(
                "MCP endpoint '{owner}': path '{}' must start with '/'",
                E::PATH
            ));
        }
        if self.tools.entries.is_empty() {
            problems.push(format!("MCP endpoint '{owner}' declares no tool"));
        }
        let label = format!("MCP endpoint '{owner}'");
        problems.extend(E::acl().mcp_problems(&label));
        for tool in &self.tools.entries {
            if let Some(acl) = &tool.acl {
                problems.extend(acl.mcp_problems(&format!("{label}, tool '{}'", tool.name)));
            }
        }
        problems
    }

    fn references(&self) -> Vec<(String, Reference)> {
        self.tools
            .entries
            .iter()
            .filter_map(|t| t.reference.clone().map(|r| (t.name.clone(), r)))
            .collect()
    }

    fn handle(
        &self,
        request: proto::McpToolRequest,
        routes: &dyn RouteCall,
    ) -> proto::McpToolReply {
        use proto::mcp_tool_reply::Reply;
        let Some(tool) = self.tools.entries.iter().find(|t| t.name == request.tool) else {
            return proto::McpToolReply {
                reply: Some(Reply::Failure(proto::Failure {
                    command_id: 0,
                    error: Some(proto::Error {
                        message: format!(
                            "MCP endpoint '{}' has no tool '{}'",
                            E::ENDPOINT_ID,
                            request.tool
                        ),
                        code: proto::ErrorCode::NotFound as i32,
                        ..Default::default()
                    }),
                })),
            };
        };
        let metadata = Metadata::from_proto(request.metadata.as_ref());
        let call = McpRequest {
            principal: request.principal.clone().map(principal_of),
            caller: caller_of(request.caller.clone()),
            context: Context::new(E::ENDPOINT_ID, "", 0, metadata)
                .with_secrets()
                .with_services()
                .with_client_keys(),
            wire_principal: request.principal,
            wire_caller: request.caller,
        };
        let reply = match (tool.run)(&request.arguments_json, &call, routes) {
            Ok(answer) => Reply::Ok(answer),
            Err(message) => Reply::Error(message),
        };
        proto::McpToolReply { reply: Some(reply) }
    }
}
