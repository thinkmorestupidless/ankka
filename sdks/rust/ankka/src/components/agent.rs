//! Agents: a request planned here, run by the runtime. A handler describes what to ask the model
//! — messages, tools and guardrails by name — and the runtime runs the loop: the model call, the
//! session's memory, and calls back into the module to run a tool or check a guardrail.
//!
//! ```ignore
//! impl Agent for Assistant {
//!     const COMPONENT_ID: &'static str = "assistant";
//!     fn handlers() -> AgentHandlers<Self> { AgentHandlers::new().command("ask", Self::ask) }
//!     fn tools() -> Tools<Self> {
//!         Tools::new().tool("lookup", "Looks up a cart by its id.", Schema::object().string("cartId", "the cart"), Self::lookup)
//!     }
//!     fn guardrails() -> Guardrails<Self> { Guardrails::new().guardrail("no-secrets", Self::no_secrets) }
//! }
//! ```

use std::marker::PhantomData;

use serde::de::DeserializeOwned;
use serde_json::{Map, Value, json};

use super::{ComponentOf, Registered, Shape, failure, kinds, name_problem};
use crate::codec::decode_payload;
use crate::context::{Context, Metadata};
use crate::effects::ErrorCode;
use crate::effects::agent::AgentEffect;
use crate::proto::{self, Kind, Payload};

/// An agent. Implement it on a unit struct and register the struct's value. Its instances are
/// sessions: the id a caller names is the session's.
pub trait Agent: Sized + 'static {
    /// The component's id.
    const COMPONENT_ID: &'static str;

    /// A short description of the agent's role, for the console and for other agents.
    const ROLE: &'static str = "";

    /// The most tool calls one request may make before the runtime stops the loop.
    fn max_tool_call_steps() -> u32 {
        100
    }

    /// The request handlers, by wire name.
    fn handlers() -> AgentHandlers<Self>;

    /// The tools a request may offer the model, by name.
    fn tools() -> Tools<Self> {
        Tools::new()
    }

    /// The guardrails a request may be checked by, by name.
    fn guardrails() -> Guardrails<Self> {
        Guardrails::new()
    }
}

/// The JSON schema of a tool's arguments, which the model is shown. Built by hand, since a Rust
/// type carries no description of its fields at run time; the arguments are decoded into the
/// tool's own type, so a schema that disagrees with it is an error the model is told about.
#[derive(Debug, Clone, PartialEq)]
pub struct Schema {
    properties: Map<String, Value>,
    required: Vec<String>,
}

impl Schema {
    /// An object with no properties yet.
    pub fn object() -> Schema {
        Schema {
            properties: Map::new(),
            required: Vec::new(),
        }
    }

    fn property(mut self, name: &str, kind: &str, description: &str, required: bool) -> Schema {
        self.properties.insert(
            name.to_string(),
            json!({ "type": kind, "description": description }),
        );
        if required {
            self.required.push(name.to_string());
        }
        self
    }

    /// A required string property.
    pub fn string(self, name: &str, description: &str) -> Schema {
        self.property(name, "string", description, true)
    }

    /// A required integer property.
    pub fn integer(self, name: &str, description: &str) -> Schema {
        self.property(name, "integer", description, true)
    }

    /// A required number property.
    pub fn number(self, name: &str, description: &str) -> Schema {
        self.property(name, "number", description, true)
    }

    /// A required boolean property.
    pub fn boolean(self, name: &str, description: &str) -> Schema {
        self.property(name, "boolean", description, true)
    }

    /// A required property that is an array of strings.
    pub fn string_array(mut self, name: &str, description: &str) -> Schema {
        self.properties.insert(
            name.to_string(),
            json!({ "type": "array", "items": { "type": "string" }, "description": description }),
        );
        self.required.push(name.to_string());
        self
    }

    /// An optional string property.
    pub fn optional_string(self, name: &str, description: &str) -> Schema {
        self.property(name, "string", description, false)
    }

    /// The schema as JSON.
    pub fn to_json(&self) -> String {
        json!({
            "type": "object",
            "properties": Value::Object(self.properties.clone()),
            "required": self.required,
        })
        .to_string()
    }
}

/// Which side of the model a guardrail checks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stage {
    /// The request, before the model sees it.
    Input,
    /// The model's reply, before the caller sees it.
    Output,
}

type Plan = Box<dyn Fn(&Payload, &Context) -> Result<AgentEffect, String>>;
type ToolRun = Box<dyn Fn(&str, &Context) -> Result<String, String>>;
type Check = Box<dyn Fn(Stage, &str, &Context) -> Result<(), String>>;

/// An agent's request handlers, by wire name.
pub struct AgentHandlers<C: Agent> {
    entries: Vec<(String, Plan)>,
    problems: Vec<String>,
    marker: PhantomData<fn() -> C>,
}

impl<C: Agent> Default for AgentHandlers<C> {
    fn default() -> AgentHandlers<C> {
        AgentHandlers::new()
    }
}

impl<C: Agent> AgentHandlers<C> {
    /// No handlers yet.
    pub fn new() -> AgentHandlers<C> {
        AgentHandlers {
            entries: Vec::new(),
            problems: Vec::new(),
            marker: PhantomData,
        }
    }

    /// A request handler under wire name `name`: it plans the request, and the runtime runs it.
    pub fn command<In, F>(mut self, name: &str, handler: F) -> AgentHandlers<C>
    where
        In: DeserializeOwned + 'static,
        F: Fn(In, &Context) -> AgentEffect + 'static,
    {
        let taken = self.entries.iter().any(|(n, _)| n == name);
        if let Some(problem) = name_problem("agent", C::COMPONENT_ID, name, taken) {
            self.problems.push(problem);
            if taken {
                return self;
            }
        }
        let plan: Plan = Box::new(move |payload, ctx| {
            let input: In = decode_payload(payload).map_err(|e| {
                format!(
                    "the input to '{}' is not a {}: {e}",
                    ctx.component_id(),
                    std::any::type_name::<In>()
                )
            })?;
            Ok(handler(input, ctx))
        });
        self.entries.push((name.to_string(), plan));
        self
    }
}

/// An agent's tools, by name: an [`Agent`]'s or an
/// [`AutonomousAgent`](super::AutonomousAgent)'s.
pub struct Tools<C> {
    pub(super) entries: Vec<(String, String, Schema, ToolRun)>,
    problems: Vec<Declared>,
    marker: PhantomData<fn() -> C>,
}

/// Something wrong with a tool or a guardrail, said once its owner is known.
#[derive(Debug, Clone)]
enum Declared {
    NoName(&'static str),
    Twice(&'static str, String),
    NoDescription(String),
}

fn render(problems: &[Declared], owner: &str) -> Vec<String> {
    problems
        .iter()
        .map(|p| match p {
            Declared::NoName(what) => format!("{owner} declares {what} '' with no name"),
            Declared::Twice(what, name) => format!("{owner} declares {what} '{name}' twice"),
            Declared::NoDescription(name) => {
                format!("{owner}: tool '{name}' has no description; the model decides by it")
            }
        })
        .collect()
}

impl<C> Default for Tools<C> {
    fn default() -> Tools<C> {
        Tools::new()
    }
}

impl<C> Tools<C> {
    /// No tools yet.
    pub fn new() -> Tools<C> {
        Tools {
            entries: Vec::new(),
            problems: Vec::new(),
            marker: PhantomData,
        }
    }

    /// Tool `name`: the model decides whether to call it by `description`, and is shown `schema`.
    /// `Err` is a message for the model, which carries on; a panic fails the request.
    pub fn tool<Args, F>(
        mut self,
        name: &str,
        description: &str,
        schema: Schema,
        tool: F,
    ) -> Tools<C>
    where
        Args: DeserializeOwned + 'static,
        F: Fn(Args, &Context) -> Result<String, String> + 'static,
    {
        if name.is_empty() {
            self.problems.push(Declared::NoName("tool"));
            return self;
        }
        if self.entries.iter().any(|(n, ..)| n == name) {
            self.problems
                .push(Declared::Twice("tool", name.to_string()));
            return self;
        }
        if description.is_empty() {
            self.problems
                .push(Declared::NoDescription(name.to_string()));
        }
        let run: ToolRun = Box::new(move |arguments, ctx| {
            let arguments = if arguments.trim().is_empty() {
                "{}"
            } else {
                arguments
            };
            let args: Args = serde_json::from_str(arguments)
                .map_err(|e| format!("the arguments do not match the tool's schema: {e}"))?;
            tool(args, ctx)
        });
        self.entries
            .push((name.to_string(), description.to_string(), schema, run));
        self
    }

    /// What is wrong with the tools, each naming `owner` (`agent 'assistant'`).
    pub(super) fn problems(&self, owner: &str) -> Vec<String> {
        render(&self.problems, owner)
    }

    /// The tools as discovery describes them.
    pub(super) fn to_proto(&self) -> Vec<proto::Tool> {
        self.entries
            .iter()
            .map(|(name, description, schema, _)| proto::Tool {
                name: name.clone(),
                description: description.clone(),
                input_schema_json: schema.to_json(),
            })
            .collect()
    }

    /// Runs one tool for `owner`, answering what the model is told.
    pub(super) fn invoke(&self, owner: &str, request: &proto::ToolRequest) -> proto::ToolResult {
        use proto::tool_result::Result as R;
        let ctx = session_context(&request.component_id, &request.session_id, None);
        let result = match self.entries.iter().find(|(n, ..)| *n == request.tool) {
            None => R::Error(format!("{owner} has no tool '{}'", request.tool)),
            Some((.., run)) => match run(&request.arguments_json, &ctx) {
                Ok(text) => R::Ok(text),
                Err(text) => R::Error(text),
            },
        };
        proto::ToolResult {
            result: Some(result),
        }
    }
}

/// An agent's guardrails, by name: an [`Agent`]'s or an
/// [`AutonomousAgent`](super::AutonomousAgent)'s.
pub struct Guardrails<C> {
    pub(super) entries: Vec<(String, Check)>,
    problems: Vec<Declared>,
    marker: PhantomData<fn() -> C>,
}

impl<C> Default for Guardrails<C> {
    fn default() -> Guardrails<C> {
        Guardrails::new()
    }
}

impl<C> Guardrails<C> {
    /// No guardrails yet.
    pub fn new() -> Guardrails<C> {
        Guardrails {
            entries: Vec::new(),
            problems: Vec::new(),
            marker: PhantomData,
        }
    }

    /// Guardrail `name`: `Err` blocks the text, and the reason is what the caller is told.
    pub fn guardrail<F>(mut self, name: &str, check: F) -> Guardrails<C>
    where
        F: Fn(Stage, &str, &Context) -> Result<(), String> + 'static,
    {
        if name.is_empty() {
            self.problems.push(Declared::NoName("guardrail"));
            return self;
        }
        if self.entries.iter().any(|(n, _)| n == name) {
            self.problems
                .push(Declared::Twice("guardrail", name.to_string()));
            return self;
        }
        self.entries.push((name.to_string(), Box::new(check)));
        self
    }

    /// What is wrong with the guardrails, each naming `owner`.
    pub(super) fn problems(&self, owner: &str) -> Vec<String> {
        render(&self.problems, owner)
    }

    /// The guardrails' names, as discovery lists them.
    pub(super) fn names(&self) -> Vec<String> {
        self.entries.iter().map(|(n, _)| n.clone()).collect()
    }

    /// Checks one guardrail for `owner`.
    pub(super) fn check(
        &self,
        owner: &str,
        request: &proto::GuardrailRequest,
    ) -> proto::GuardrailResult {
        use proto::guardrail_result::Result as R;
        let ctx = session_context(&request.component_id, &request.session_id, None);
        let stage = if request.stage == proto::guardrail_request::Stage::Output as i32 {
            Stage::Output
        } else {
            Stage::Input
        };
        let result = match self.entries.iter().find(|(n, _)| *n == request.guardrail) {
            None => R::Block(format!("{owner} has no guardrail '{}'", request.guardrail)),
            Some((_, check)) => match check(stage, &request.text, &ctx) {
                Ok(()) => R::Pass(proto::Empty {}),
                Err(reason) => R::Block(reason),
            },
        };
        proto::GuardrailResult {
            result: Some(result),
        }
    }
}

pub(crate) struct Registration<C: Agent> {
    handlers: AgentHandlers<C>,
    tools: Tools<C>,
    guardrails: Guardrails<C>,
}

impl<C: Agent> ComponentOf<kinds::Agent> for C {
    fn kind() -> Kind {
        Kind::Agent
    }

    fn component_id() -> &'static str {
        C::COMPONENT_ID
    }

    fn registration() -> Box<dyn Registered> {
        Box::new(Registration::<C> {
            handlers: C::handlers(),
            tools: C::tools(),
            guardrails: C::guardrails(),
        })
    }
}

pub(super) fn session_context(
    component_id: &str,
    session_id: &str,
    metadata: Option<&proto::Metadata>,
) -> Context {
    Context::new(component_id, session_id, 0, Metadata::from_proto(metadata))
}

impl<C: Agent> Registered for Registration<C> {
    fn id(&self) -> &str {
        C::COMPONENT_ID
    }

    fn kind(&self) -> Kind {
        Kind::Agent
    }

    fn shape(&self) -> Shape {
        Shape::Stateless
    }

    fn to_component(&self) -> proto::Component {
        let mut handlers: Vec<proto::Handler> = self
            .handlers
            .entries
            .iter()
            .map(|(name, _)| proto::Handler {
                name: name.clone(),
                read_only: false,
                streaming: false,
            })
            .collect();
        handlers.sort_by(|a, b| a.name.cmp(&b.name));
        proto::Component {
            kind: Kind::Agent as i32,
            id: C::COMPONENT_ID.to_string(),
            handlers,
            detail: Some(proto::component::Detail::Agent(proto::AgentDetail {
                role: C::ROLE.to_string(),
                max_tool_call_steps: C::max_tool_call_steps() as i32,
                tools: self.tools.to_proto(),
                guardrails: self.guardrails.names(),
            })),
        }
    }

    fn problems(&self) -> Vec<String> {
        let owner = format!("agent '{}'", C::COMPONENT_ID);
        let mut problems = self.handlers.problems.clone();
        problems.extend(self.tools.problems(&owner));
        problems.extend(self.guardrails.problems(&owner));
        if C::COMPONENT_ID.is_empty() {
            problems.push("an agent has an empty component id".to_string());
        }
        if C::max_tool_call_steps() == 0 {
            problems.push(format!(
                "agent '{}': max_tool_call_steps must be positive",
                C::COMPONENT_ID
            ));
        }
        problems
    }

    fn plan(&self, request: proto::PlanRequest) -> Option<proto::PlanReply> {
        use proto::plan_reply::Message;
        let ctx = session_context(
            C::COMPONENT_ID,
            &request.session_id,
            request.metadata.as_ref(),
        );
        let message = match self
            .handlers
            .entries
            .iter()
            .find(|(n, _)| *n == request.name)
        {
            None => Message::Failure(failure(
                0,
                ErrorCode::NotFound,
                format!(
                    "agent '{}' has no handler '{}'",
                    C::COMPONENT_ID,
                    request.name
                ),
            )),
            Some((_, plan)) => match plan(&request.payload.unwrap_or_default(), &ctx) {
                Ok(effect) => Message::Plan(effect.to_proto()),
                Err(message) => Message::Failure(failure(0, ErrorCode::Internal, message)),
            },
        };
        Some(proto::PlanReply {
            message: Some(message),
        })
    }

    fn invoke_tool(&self, request: proto::ToolRequest) -> Option<proto::ToolResult> {
        Some(
            self.tools
                .invoke(&format!("agent '{}'", C::COMPONENT_ID), &request),
        )
    }

    fn check_guardrail(&self, request: proto::GuardrailRequest) -> Option<proto::GuardrailResult> {
        Some(
            self.guardrails
                .check(&format!("agent '{}'", C::COMPONENT_ID), &request),
        )
    }
}
