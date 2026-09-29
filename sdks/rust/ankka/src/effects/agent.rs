//! Effects for agents: what to ask the model, described, not done. The runtime runs the loop — the
//! model call, session memory, the tools and guardrails by name — and answers the caller.
//!
//! ```ignore
//! effects::agent::system_message("You are helpful.")
//!     .user_message(question)
//!     .tools(["lookup"])
//!     .guardrails(["no-secrets"])
//!     .then_reply()
//! ```

use super::common::{CommandError, ErrorCode};
use crate::proto;

/// An agent request, planned: the messages, the model, the tools and guardrails by name.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct AgentEffect {
    model: Option<String>,
    system: Option<String>,
    user: Option<String>,
    context: Vec<String>,
    no_memory: bool,
    tools: Vec<String>,
    guardrails: Vec<String>,
    json_shape: Option<String>,
    failure: Option<CommandError>,
}

/// Start a plan with a system message.
pub fn system_message(text: impl Into<String>) -> AgentEffect {
    AgentEffect::default().system_message(text)
}

/// Start a plan with the user's message.
pub fn user_message(text: impl Into<String>) -> AgentEffect {
    AgentEffect::default().user_message(text)
}

/// Refuse the request before any model call: the caller sees the code and message.
pub fn error(code: ErrorCode, message: impl Into<String>) -> AgentEffect {
    AgentEffect {
        failure: Some(CommandError::new(code, message)),
        ..AgentEffect::default()
    }
}

impl AgentEffect {
    /// The system message.
    pub fn system_message(mut self, text: impl Into<String>) -> AgentEffect {
        self.system = Some(text.into());
        self
    }

    /// The user's message.
    pub fn user_message(mut self, text: impl Into<String>) -> AgentEffect {
        self.user = Some(text.into());
        self
    }

    /// More context, after the system message.
    pub fn context(mut self, text: impl Into<String>) -> AgentEffect {
        self.context.push(text.into());
        self
    }

    /// The model, by a name the runtime's configuration knows; its default if never said.
    pub fn model(mut self, name: impl Into<String>) -> AgentEffect {
        self.model = Some(name.into());
        self
    }

    /// The agent's tools this request may use, by name.
    pub fn tools<I, T>(mut self, names: I) -> AgentEffect
    where
        I: IntoIterator<Item = T>,
        T: Into<String>,
    {
        self.tools.extend(names.into_iter().map(Into::into));
        self
    }

    /// The agent's guardrails this request is checked by, by name.
    pub fn guardrails<I, T>(mut self, names: I) -> AgentEffect
    where
        I: IntoIterator<Item = T>,
        T: Into<String>,
    {
        self.guardrails.extend(names.into_iter().map(Into::into));
        self
    }

    /// Whether the session's memory is used; it is unless said otherwise.
    pub fn session_memory(mut self, on: bool) -> AgentEffect {
        self.no_memory = !on;
        self
    }

    /// Ask for a JSON reply, described by `hint`.
    pub fn json_shape(mut self, hint: impl Into<String>) -> AgentEffect {
        self.json_shape = Some(hint.into());
        self
    }

    /// Reply with the model's answer. The plan is complete.
    pub fn then_reply(self) -> AgentEffect {
        self
    }

    /// The plan as the protocol carries it.
    pub fn to_proto(&self) -> proto::AgentPlan {
        use proto::agent_plan::{Memory, ResponseShape, response_shape};
        let shape = match &self.json_shape {
            Some(hint) => response_shape::Shape::Json(response_shape::Json {
                schema_hint: hint.clone(),
            }),
            None => response_shape::Shape::Text(proto::Empty {}),
        };
        proto::AgentPlan {
            model: self.model.clone(),
            system: self.system.clone(),
            user: self.user.clone(),
            context: self.context.clone(),
            memory: if self.no_memory {
                Memory::None as i32
            } else {
                Memory::Session as i32
            },
            tools: self.tools.clone(),
            response_shape: Some(ResponseShape { shape: Some(shape) }),
            guardrails: self.guardrails.clone(),
            failure: self.failure.as_ref().map(CommandError::to_proto),
        }
    }

    /// The system message, if set.
    pub fn system(&self) -> Option<&str> {
        self.system.as_deref()
    }

    /// The user's message, if set.
    pub fn user(&self) -> Option<&str> {
        self.user.as_deref()
    }

    /// The tools named.
    pub fn tool_names(&self) -> &[String] {
        &self.tools
    }

    /// The guardrails named.
    pub fn guardrail_names(&self) -> &[String] {
        &self.guardrails
    }

    /// The refusal, if the plan is one.
    pub fn failure(&self) -> Option<&CommandError> {
        self.failure.as_ref()
    }
}
