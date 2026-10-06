//! Autonomous agents: a durable agent that is handed a task and works it on its own, iteration by
//! iteration, until it completes the task with a result its type's rules accept, fails it, or spends
//! its budget.
//!
//! The module declares the agent — what it is for, its tools and guardrails, and the task types it
//! accepts, each with a result type, rules and an iteration budget. The runtime runs everything
//! else: the loop, the model, the task records and the instance's own record. It calls into the
//! module for three things only — run a tool, check a guardrail, check a result — each naming the
//! task it is for, which a tool reads as [`Context::task_id`].
//!
//! A tool may run more than once for one request of the model: after a crash, the tools of the last
//! recorded model response run again. A tool with a side effect should tolerate that.
//!
//! ```ignore
//! fn answer() -> TaskType<Answer> {
//!     TaskType::new("answer", "Answer a question, citing what you looked up",
//!         Schema::object().string("answer", "the answer").string_array("sources", "what it used"))
//!         .rule("cites-sources", |a: &Answer, _: &Context| {
//!             if a.sources.is_empty() { Verdict::rejected("sources must not be empty") } else { Verdict::Accepted }
//!         })
//! }
//!
//! impl AutonomousAgent for Answerer {
//!     const COMPONENT_ID: &'static str = "answerer";
//!     const DESCRIPTION: &'static str = "Answers questions";
//!     fn accepts() -> Vec<TaskAcceptance> { vec![TaskAcceptance::new(answer(), 4)] }
//! }
//! ```

use std::marker::PhantomData;
use std::rc::Rc;

use serde::de::DeserializeOwned;

use super::agent::{Guardrails, Schema, Tools};
use super::{ComponentOf, Registered, Shape, kinds};
use crate::context::{Context, Metadata};
use crate::proto::{self, Kind};

/// The tool the model completes a task with; the runtime's, never a module's.
pub const COMPLETE_TASK: &str = "complete_task";

/// The tool the model fails a task with; the runtime's, never a module's.
pub const FAIL_TASK: &str = "fail_task";

/// An autonomous agent. Implement it on a unit struct and register the struct's value. Its
/// instances are named by whoever assigns them work, or by the platform for a single task.
pub trait AutonomousAgent: Sized + 'static {
    /// The component's id.
    const COMPONENT_ID: &'static str;

    /// What the agent is for: the model reads it, and so does the console.
    const DESCRIPTION: &'static str;

    /// Standing instructions for every task, beside each task's own.
    const INSTRUCTIONS: Option<&'static str> = None;

    /// A model by its name in the runtime's configuration; `None` is the runtime's default.
    const MODEL: Option<&'static str> = None;

    /// The task types it takes, each with its iteration budget.
    fn accepts() -> Vec<TaskAcceptance>;

    /// The tools the model may call while working a task, by name.
    fn tools() -> Tools<Self> {
        Tools::new()
    }

    /// The guardrails every task's instructions and every result are checked by, by name.
    fn guardrails() -> Guardrails<Self> {
        Guardrails::new()
    }

    /// When an instance warns that it is struggling, and when it gives up; `None` keeps the
    /// runtime's defaults.
    fn settings() -> Option<AutonomousSettings> {
        None
    }
}

/// A rule's answer about a result.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Verdict {
    /// The result stands.
    Accepted,
    /// The result does not stand, and the reason goes back to the model.
    Rejected(String),
}

impl Verdict {
    /// The result does not stand, because of `reason`.
    pub fn rejected(reason: impl Into<String>) -> Verdict {
        Verdict::Rejected(reason.into())
    }
}

type Rule<R> = Rc<dyn Fn(&R, &Context) -> Verdict>;

/// A kind of work: a name written into every task of the type, a description, the result's shape,
/// and the rules a result must pass. `R` is the result's type: a record whose JSON schema the model
/// is shown, or `String` for a type whose result is text ([`TaskType::text`]).
pub struct TaskType<R> {
    name: String,
    description: String,
    schema: Option<Schema>,
    rules: Vec<(String, Rule<R>)>,
    problems: Vec<String>,
    marker: PhantomData<fn() -> R>,
}

impl<R> Clone for TaskType<R> {
    fn clone(&self) -> TaskType<R> {
        TaskType {
            name: self.name.clone(),
            description: self.description.clone(),
            schema: self.schema.clone(),
            rules: self.rules.clone(),
            problems: self.problems.clone(),
            marker: PhantomData,
        }
    }
}

impl<R> std::fmt::Debug for TaskType<R> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let rules: Vec<&str> = self.rules.iter().map(|(n, _)| n.as_str()).collect();
        f.debug_struct("TaskType")
            .field("name", &self.name)
            .field("rules", &rules)
            .finish()
    }
}

impl<R> TaskType<R> {
    /// The type's wire name.
    pub fn name(&self) -> &str {
        &self.name
    }
}

impl TaskType<String> {
    /// A type whose result is text: the model completes it with `{"result": "<text>"}`.
    pub fn text(name: &str, description: &str) -> TaskType<String> {
        TaskType::declare(name, description, None)
    }
}

impl<R: DeserializeOwned + 'static> TaskType<R> {
    /// A type whose result is an `R`, described to the model by `result` — written out, since a
    /// Rust type carries no description of its fields at run time. The result is decoded into `R`;
    /// one that does not decode goes back to the model as a mistake to correct.
    pub fn new(name: &str, description: &str, result: Schema) -> TaskType<R> {
        TaskType::declare(name, description, Some(result))
    }

    fn declare(name: &str, description: &str, schema: Option<Schema>) -> TaskType<R> {
        let mut problems = Vec::new();
        if name.is_empty() {
            problems.push("a task type needs a name".to_string());
        } else if description.is_empty() {
            problems.push(format!("task type '{name}' needs a description"));
        }
        TaskType {
            name: name.to_string(),
            description: description.to_string(),
            schema,
            rules: Vec::new(),
            problems,
            marker: PhantomData,
        }
    }

    /// Rule `name`: a check every result must pass. Rules run in the order they are declared, and
    /// the first rejection is the one reported. A rule that panics has decided nothing: the module
    /// traps, and the runtime checks the result again.
    pub fn rule<F>(mut self, name: &str, check: F) -> TaskType<R>
    where
        F: Fn(&R, &Context) -> Verdict + 'static,
    {
        if name.is_empty() {
            self.problems
                .push(format!("task type '{}' has a rule with no name", self.name));
        } else if self.rules.iter().any(|(n, _)| n == name) {
            self.problems.push(format!(
                "task type '{}' declares rule '{name}' twice",
                self.name
            ));
        } else {
            self.rules.push((name.to_string(), Rc::new(check)));
        }
        self
    }

    /// A result as it is stored — its JSON — decoded as this type's.
    pub fn decode(&self, stored: &str) -> Result<R, String> {
        serde_json::from_str(stored).map_err(|e| e.to_string())
    }

    /// Decodes a result and holds it to the rules in order.
    fn verdict(&self, result_json: &str, ctx: &Context) -> ResultCheck {
        let result = match self.decode(result_json) {
            Ok(result) => result,
            Err(problem) => return ResultCheck::Malformed(problem),
        };
        for (rule, check) in &self.rules {
            if let Verdict::Rejected(reason) = check(&result, ctx) {
                return ResultCheck::Rejected {
                    rule: rule.clone(),
                    reason,
                };
            }
        }
        ResultCheck::Accepted
    }
}

/// What checking one result came to: what the runtime is told.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResultCheck {
    /// It decoded and every rule accepted it: the task completes with it.
    Accepted,
    /// It does not decode as the task type's result; the model is told why and tries again.
    Malformed(String),
    /// `rule` rejected it, the first to; the model is told `reason` and tries again.
    Rejected {
        /// The rule's name.
        rule: String,
        /// Why.
        reason: String,
    },
}

impl ResultCheck {
    pub(crate) fn to_proto(&self) -> proto::TaskResultVerdict {
        use proto::task_result_verdict::{Rejection, Verdict as V};
        let verdict = match self {
            ResultCheck::Accepted => V::Accept(proto::Empty {}),
            ResultCheck::Malformed(problem) => V::Malformed(problem.clone()),
            ResultCheck::Rejected { rule, reason } => V::Reject(Rejection {
                rule: rule.clone(),
                reason: reason.clone(),
            }),
        };
        proto::TaskResultVerdict {
            verdict: Some(verdict),
        }
    }
}

/// A task type with its result type forgotten, as an agent's declaration holds it.
trait Declared {
    fn name(&self) -> &str;
    fn to_proto(&self) -> proto::autonomous_agent_detail::TaskType;
    fn problems(&self) -> &[String];
    fn check(&self, result_json: &str, ctx: &Context) -> ResultCheck;
    /// One rule alone, for the testkit.
    #[cfg(not(target_arch = "wasm32"))]
    fn rule(&self, rule: &str, result_json: &str, ctx: &Context)
    -> Option<Result<Verdict, String>>;
}

impl<R: DeserializeOwned + 'static> Declared for TaskType<R> {
    fn name(&self) -> &str {
        &self.name
    }

    fn to_proto(&self) -> proto::autonomous_agent_detail::TaskType {
        proto::autonomous_agent_detail::TaskType {
            name: self.name.clone(),
            description: self.description.clone(),
            result_schema_json: self.schema.as_ref().map(Schema::to_json),
            rules: self.rules.iter().map(|(n, _)| n.clone()).collect(),
        }
    }

    fn problems(&self) -> &[String] {
        &self.problems
    }

    fn check(&self, result_json: &str, ctx: &Context) -> ResultCheck {
        self.verdict(result_json, ctx)
    }

    #[cfg(not(target_arch = "wasm32"))]
    fn rule(
        &self,
        rule: &str,
        result_json: &str,
        ctx: &Context,
    ) -> Option<Result<Verdict, String>> {
        let (_, check) = self.rules.iter().find(|(n, _)| n == rule)?;
        Some(self.decode(result_json).map(|result| check(&result, ctx)))
    }
}

/// Takes tasks of one type, spending at most `max_iterations` model calls on each.
#[derive(Clone)]
pub struct TaskAcceptance {
    task_type: Rc<dyn Declared>,
    max_iterations: u32,
}

impl std::fmt::Debug for TaskAcceptance {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TaskAcceptance")
            .field("task_type", &self.task_type.name())
            .field("max_iterations", &self.max_iterations)
            .finish()
    }
}

impl TaskAcceptance {
    /// Takes tasks of `task_type`, at most `max_iterations` model calls on each.
    pub fn new<R: DeserializeOwned + 'static>(
        task_type: TaskType<R>,
        max_iterations: u32,
    ) -> TaskAcceptance {
        TaskAcceptance {
            task_type: Rc::new(task_type),
            max_iterations,
        }
    }

    /// Takes tasks of `task_type`, at most ten model calls on each.
    pub fn of<R: DeserializeOwned + 'static>(task_type: TaskType<R>) -> TaskAcceptance {
        TaskAcceptance::new(task_type, 10)
    }

    /// The accepted type's wire name.
    pub fn task_type(&self) -> &str {
        self.task_type.name()
    }

    /// The budget per task.
    pub fn max_iterations(&self) -> u32 {
        self.max_iterations
    }
}

/// When an instance warns that it is struggling, and when it gives up. `None` keeps the runtime's
/// default, given beside each.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct AutonomousSettings {
    /// The share of a task's budget at which it warns that the budget is running out (0.8).
    pub approaching_budget_at: Option<f64>,
    /// How many failures in a row of the same kind it warns at (3).
    pub repeated_failure_at: Option<u32>,
    /// How many failed iterations in a row fail the task (5).
    pub max_consecutive_failures: Option<u32>,
    /// How long a task may wait on a dependency before it warns, in milliseconds (300000).
    pub dependency_stuck_after_millis: Option<i64>,
}

impl AutonomousSettings {
    /// Every setting at the runtime's default.
    pub fn new() -> AutonomousSettings {
        AutonomousSettings::default()
    }

    /// Warns when `share` of a task's budget is spent.
    pub fn approaching_budget_at(mut self, share: f64) -> AutonomousSettings {
        self.approaching_budget_at = Some(share);
        self
    }

    /// Warns at `n` failures in a row of the same kind.
    pub fn repeated_failure_at(mut self, n: u32) -> AutonomousSettings {
        self.repeated_failure_at = Some(n);
        self
    }

    /// Fails the task after `n` failed iterations in a row.
    pub fn max_consecutive_failures(mut self, n: u32) -> AutonomousSettings {
        self.max_consecutive_failures = Some(n);
        self
    }

    /// Warns when a task has waited on a dependency for `millis`.
    pub fn dependency_stuck_after_millis(mut self, millis: i64) -> AutonomousSettings {
        self.dependency_stuck_after_millis = Some(millis);
        self
    }

    fn to_proto(&self) -> proto::autonomous_agent_detail::AutonomousSettings {
        proto::autonomous_agent_detail::AutonomousSettings {
            approaching_budget_at: self.approaching_budget_at,
            repeated_failure_at: self.repeated_failure_at.map(|n| n as i32),
            max_consecutive_failures: self.max_consecutive_failures.map(|n| n as i32),
            dependency_stuck_after_millis: self.dependency_stuck_after_millis,
        }
    }
}

pub(crate) struct Registration<C: AutonomousAgent> {
    accepts: Vec<TaskAcceptance>,
    tools: Tools<C>,
    guardrails: Guardrails<C>,
    settings: Option<AutonomousSettings>,
}

impl<C: AutonomousAgent> ComponentOf<kinds::AutonomousAgent> for C {
    fn kind() -> Kind {
        Kind::AutonomousAgent
    }

    fn component_id() -> &'static str {
        C::COMPONENT_ID
    }

    fn registration() -> Box<dyn Registered> {
        Box::new(Registration::<C>::new())
    }
}

impl<C: AutonomousAgent> Registration<C> {
    pub(crate) fn new() -> Registration<C> {
        Registration {
            accepts: C::accepts(),
            tools: C::tools(),
            guardrails: C::guardrails(),
            settings: C::settings(),
        }
    }

    fn owner() -> String {
        format!("autonomous agent '{}'", C::COMPONENT_ID)
    }

    /// Each accepted type once, in the order first accepted.
    fn task_types(&self) -> Vec<&Rc<dyn Declared>> {
        let mut types: Vec<&Rc<dyn Declared>> = Vec::new();
        for a in &self.accepts {
            if !types.iter().any(|t| t.name() == a.task_type.name()) {
                types.push(&a.task_type);
            }
        }
        types
    }

    fn task_type(&self, name: &str) -> &Rc<dyn Declared> {
        &self
            .accepts
            .iter()
            .find(|a| a.task_type.name() == name)
            .unwrap_or_else(|| panic!("{} accepts no task type '{name}'", Self::owner()))
            .task_type
    }

    /// The context a result is checked in: the task's, as a tool's. `metadata` is what the runtime
    /// says about the check: its trace, and the agent's handler as the caller of whatever a rule
    /// calls. A runtime before 1.3 sends none.
    pub(crate) fn task_context(task_id: &str, metadata: Option<&proto::Metadata>) -> Context {
        Context::new(
            C::COMPONENT_ID,
            format!("task:{task_id}"),
            0,
            Metadata::from_proto(metadata),
        )
        .with_secrets()
        .with_services()
    }

    /// Checks one result as `ankka1_check_task_result` does.
    pub(crate) fn check(
        &self,
        task_type: &str,
        result_json: &str,
        task_id: &str,
        metadata: Option<&proto::Metadata>,
    ) -> ResultCheck {
        self.task_type(task_type)
            .check(result_json, &Self::task_context(task_id, metadata))
    }

    /// Runs one rule of `task_type` alone: `None` when there is no such rule, `Err` when the result
    /// does not decode.
    #[cfg(not(target_arch = "wasm32"))]
    pub(crate) fn rule(
        &self,
        task_type: &str,
        rule: &str,
        result_json: &str,
        task_id: &str,
    ) -> Option<Result<Verdict, String>> {
        self.task_type(task_type)
            .rule(rule, result_json, &Self::task_context(task_id, None))
    }
}

impl<C: AutonomousAgent> Registered for Registration<C> {
    fn id(&self) -> &str {
        C::COMPONENT_ID
    }

    fn kind(&self) -> Kind {
        Kind::AutonomousAgent
    }

    fn shape(&self) -> Shape {
        Shape::Stateless
    }

    fn to_component(&self) -> proto::Component {
        proto::Component {
            kind: Kind::AutonomousAgent as i32,
            id: C::COMPONENT_ID.to_string(),
            handlers: Vec::new(),
            detail: Some(proto::component::Detail::AutonomousAgent(
                proto::AutonomousAgentDetail {
                    description: C::DESCRIPTION.to_string(),
                    instructions: C::INSTRUCTIONS.map(str::to_string),
                    tools: self.tools.to_proto(),
                    guardrails: self.guardrails.names(),
                    task_types: self.task_types().iter().map(|t| t.to_proto()).collect(),
                    accepts: self
                        .accepts
                        .iter()
                        .map(|a| proto::autonomous_agent_detail::TaskAcceptance {
                            task_type: a.task_type.name().to_string(),
                            max_iterations: a.max_iterations as i32,
                        })
                        .collect(),
                    settings: self.settings.as_ref().map(AutonomousSettings::to_proto),
                    model: C::MODEL.map(str::to_string),
                },
            )),
        }
    }

    fn problems(&self) -> Vec<String> {
        if C::COMPONENT_ID.is_empty() {
            return vec!["an autonomous agent has an empty component id".to_string()];
        }
        let owner = Self::owner();
        let mut problems = Vec::new();
        if C::DESCRIPTION.is_empty() {
            problems.push(format!("{owner}: a description is required"));
        }
        if self.accepts.is_empty() {
            problems.push(format!(
                "{owner}: it accepts no task type: return a TaskAcceptance from accepts()"
            ));
        }
        for t in self.task_types() {
            let times = self
                .accepts
                .iter()
                .filter(|a| a.task_type.name() == t.name())
                .count();
            if times > 1 {
                problems.push(format!(
                    "{owner}: task type '{}' is accepted {times} times",
                    t.name()
                ));
            }
            problems.extend(t.problems().iter().map(|p| format!("{owner}: {p}")));
        }
        for a in &self.accepts {
            if a.max_iterations < 1 {
                problems.push(format!(
                    "{owner}: task type '{}' needs a budget of at least one iteration",
                    a.task_type.name()
                ));
            }
        }
        for (name, ..) in &self.tools.entries {
            if name == COMPLETE_TASK || name == FAIL_TASK {
                problems.push(format!("{owner}: tool name '{name}' is reserved"));
            }
        }
        problems.extend(self.tools.problems(&owner));
        problems.extend(self.guardrails.problems(&owner));
        problems
    }

    fn invoke_tool(&self, request: proto::ToolRequest) -> Option<proto::ToolResult> {
        Some(self.tools.invoke(&Self::owner(), &request))
    }

    fn check_guardrail(&self, request: proto::GuardrailRequest) -> Option<proto::GuardrailResult> {
        Some(self.guardrails.check(&Self::owner(), &request))
    }

    fn check_task_result(
        &self,
        request: proto::TaskResultRequest,
    ) -> Option<proto::TaskResultVerdict> {
        Some(
            self.check(
                &request.task_type,
                &request.result_json,
                &request.task_id,
                request.metadata.as_ref(),
            )
            .to_proto(),
        )
    }
}
