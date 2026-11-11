//! The multi-agent planner the orchestration guide walks through, in Rust: a selector agent that
//! says which specialists a trip needs, the specialists, a summariser, and the workflow that runs
//! them one durable step at a time, every agent on the workflow's own session. None of it is in the
//! module's service; `build` assembles it for the tests.

use ankka::effects::agent;
use ankka::effects::workflow;
use ankka::prelude::*;

// ── the traveller's preferences, so an agent can be given context it did not ask for ──

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Preferences {
    #[serde(rename = "userId")]
    pub user_id: String,
    pub likes: Vec<String>,
    pub dislikes: Vec<String>,
    #[serde(rename = "maxBudget")]
    pub max_budget: i32,
}

impl Preferences {
    pub fn empty(user_id: &str) -> Preferences {
        Preferences {
            user_id: user_id.to_string(),
            likes: Vec::new(),
            dislikes: Vec::new(),
            max_budget: 0,
        }
    }

    pub fn summary(&self) -> String {
        if self.likes.is_empty() && self.dislikes.is_empty() {
            return "no stated preferences".to_string();
        }
        let mut parts = Vec::new();
        if !self.likes.is_empty() {
            parts.push(format!("likes {}", self.likes.join(", ")));
        }
        if !self.dislikes.is_empty() {
            parts.push(format!("dislikes {}", self.dislikes.join(", ")));
        }
        parts.push(format!("budget {}", self.max_budget));
        parts.join("; ")
    }
}

pub struct PreferencesEntity;

impl PreferencesEntity {
    fn set(_: &Preferences, set: Preferences, ctx: &Context) -> KeyValueEffect<Preferences, Done> {
        if set.max_budget < 0 {
            return effects::error(ErrorCode::BadRequest, "budget cannot be negative").into();
        }
        let kept = Preferences {
            user_id: ctx.entity_id().to_string(),
            ..set
        };
        effects::update_state(kept).then_reply_value(Done)
    }

    fn get(preferences: &Preferences, _: (), _: &Context) -> ReadOnlyEffect<Preferences> {
        effects::reply(preferences.clone())
    }
}

impl KeyValueEntity for PreferencesEntity {
    type State = Preferences;
    const COMPONENT_ID: &'static str = "preferences";
    const STATE_MANIFEST: Option<&'static str> = Some("preferences");

    fn empty_state(user_id: &str) -> Preferences {
        Preferences::empty(user_id)
    }

    fn handlers() -> KeyValueHandlers<PreferencesEntity> {
        KeyValueHandlers::new()
            .command("set", PreferencesEntity::set)
            .query("get", PreferencesEntity::get)
    }
}

// ── the specialists ──

/// Named once so the selector, the workflow and the agents cannot disagree.
pub mod specialist {
    pub const WEATHER: &str = "weather";
    pub const ACTIVITY: &str = "activity";
    pub const BUDGET: &str = "budget";
    pub const ALL: [&str; 3] = [WEATHER, ACTIVITY, BUDGET];
}

/// What the selector agent decided, and why: the JSON its reply is decoded from.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct AgentSelection {
    pub specialists: Vec<String>,
    pub reason: String,
}

// docs:start selector
pub struct SelectorAgent;

impl SelectorAgent {
    fn select(request: String, _: &Context) -> AgentEffect {
        if request.trim().is_empty() {
            return agent::error(ErrorCode::BadRequest, "nothing to plan");
        }
        agent::system_message(format!(
            "You route planning requests to specialists.\n\
             Available specialists: {}.\n\
             Reply with JSON: {{\"specialists\":[\"...\"],\"reason\":\"...\"}}.\n\
             Choose only the specialists the request genuinely needs.",
            specialist::ALL.join(", ")
        ))
        .user_message(request)
        // The selector's own deliberation is not part of the conversation the specialists and
        // summariser share.
        .session_memory(false)
        .json_shape(r#"{"specialists":["..."],"reason":"..."}"#)
        .then_reply()
    }
}

impl Agent for SelectorAgent {
    const COMPONENT_ID: &'static str = "selector-agent";

    fn handlers() -> AgentHandlers<SelectorAgent> {
        AgentHandlers::new().command("select", SelectorAgent::select)
    }
}
// docs:end selector

pub struct WeatherAgent;

impl WeatherAgent {
    fn consult(destination: String, _: &Context) -> AgentEffect {
        agent::system_message(
            "You are a concise weather specialist. Say what to pack, in one sentence.",
        )
        .user_message(format!("What is the weather like in {destination}?"))
        .then_reply()
    }
}

impl Agent for WeatherAgent {
    const COMPONENT_ID: &'static str = "weather-agent";

    fn handlers() -> AgentHandlers<WeatherAgent> {
        AgentHandlers::new().command("consult", WeatherAgent::consult)
    }
}

/// What the activity specialist is asked: whose trip, and where to.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct ActivityRequest {
    #[serde(rename = "userId")]
    pub user_id: String,
    pub destination: String,
}

// docs:start activity
pub struct ActivityAgent;

impl ActivityAgent {
    fn consult(request: ActivityRequest, ctx: &Context) -> AgentEffect {
        // The client is the context's; no constructor plumbing needed.
        let preferences: Preferences =
            match ctx
                .client()
                .invoke(PreferencesEntity, &request.user_id, "get", ())
            {
                Ok(preferences) => preferences,
                Err(e) => return agent::error(e.code, e.message),
            };

        agent::system_message(
            "You are a concise activity specialist. Suggest two activities, in one sentence.",
        )
        .user_message(format!("What should I do in {}?", request.destination))
        // Preferences are context, not something the user said — so memory records the
        // question, not the whole assembled prompt.
        .context(format!("Traveller preferences: {}", preferences.summary()))
        .then_reply()
    }
}

impl Agent for ActivityAgent {
    const COMPONENT_ID: &'static str = "activity-agent";

    fn handlers() -> AgentHandlers<ActivityAgent> {
        AgentHandlers::new().command("consult", ActivityAgent::consult)
    }
}
// docs:end activity

pub struct BudgetAgent;

impl BudgetAgent {
    fn consult(destination: String, _: &Context) -> AgentEffect {
        agent::system_message(
            "You are a concise budget specialist. Estimate a daily cost, in one sentence.",
        )
        .user_message(format!("What does a day in {destination} cost?"))
        .then_reply()
    }
}

impl Agent for BudgetAgent {
    const COMPONENT_ID: &'static str = "budget-agent";

    fn handlers() -> AgentHandlers<BudgetAgent> {
        AgentHandlers::new().command("consult", BudgetAgent::consult)
    }
}

// docs:start summary
pub struct SummaryAgent;

impl SummaryAgent {
    fn summarise(destination: String, _: &Context) -> AgentEffect {
        agent::system_message(
            "You write short trip briefs. Combine what the specialists said into two sentences.",
        )
        .user_message(format!("Write a brief for a trip to {destination}."))
        // The session's memory is read whole: the specialists' contributions, and nothing of
        // the selector's, which kept its deliberation out of the session.
        .then_reply()
    }
}

impl Agent for SummaryAgent {
    const COMPONENT_ID: &'static str = "summary-agent";

    fn handlers() -> AgentHandlers<SummaryAgent> {
        AgentHandlers::new().command("summarise", SummaryAgent::summarise)
    }
}
// docs:end summary

// ── the workflow that orchestrates them ──

/// One specialist's contribution.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Contribution {
    pub specialist: String,
    pub answer: String,
}

/// The state a planning run accumulates.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct PlanState {
    #[serde(rename = "userId")]
    pub user_id: String,
    pub destination: String,
    pub status: String,
    pub selection: Option<AgentSelection>,
    pub contributions: Vec<Contribution>,
    pub summary: Option<String>,
}

pub mod plan_status {
    pub const NOT_STARTED: &str = "not-started";
    pub const SELECTING: &str = "selecting";
    pub const CONSULTING: &str = "consulting";
    pub const COMPLETED: &str = "completed";
}

impl PlanState {
    pub fn empty() -> PlanState {
        PlanState {
            user_id: String::new(),
            destination: String::new(),
            status: plan_status::NOT_STARTED.to_string(),
            selection: None,
            contributions: Vec::new(),
            summary: None,
        }
    }
}

/// What starts a plan.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Start {
    #[serde(rename = "userId")]
    pub user_id: String,
    pub destination: String,
}

pub struct PlannerWorkflow;

impl PlannerWorkflow {
    // docs:start session
    /// Every agent in a plan is addressed with the same session id: the workflow's own.
    fn session(ctx: &Context) -> &str {
        ctx.entity_id()
    }
    // docs:end session

    fn start(plan: &PlanState, request: Start, _: &Context) -> WorkflowEffect<PlanState, Done> {
        if request.destination.trim().is_empty() {
            return effects::error(ErrorCode::BadRequest, "a destination is required").into();
        }
        if plan.status != plan_status::NOT_STARTED {
            return effects::error(ErrorCode::Conflict, "this plan has already started").into();
        }
        workflow::update_state(PlanState {
            user_id: request.user_id,
            destination: request.destination.clone(),
            status: plan_status::SELECTING.to_string(),
            ..PlanState::empty()
        })
        .transition_to_with("select-specialists", request.destination)
        .then_reply_value(Done)
    }

    // docs:start select
    /// Asks the selector which specialists this request needs.
    fn select_specialists(
        plan: &PlanState,
        destination: String,
        ctx: &Context,
    ) -> StepEffect<PlanState> {
        let asked: Result<String, CommandError> = ctx.client().invoke(
            SelectorAgent,
            PlannerWorkflow::session(ctx),
            "select",
            format!("Plan a trip to {destination}"),
        );
        // The agent's reply is the model's text; the selector asked for JSON, so decode it.
        let selection: AgentSelection = match asked.and_then(|text| {
            ankka::serde_json::from_str(&text).map_err(|e| {
                CommandError::new(ErrorCode::Internal, format!("not a selection: {e}"))
            })
        }) {
            Ok(selection) => selection,
            Err(e) => return step_effects::fail(e),
        };

        // Ignore anything the model invented that is not a real specialist.
        let known: Vec<String> = selection
            .specialists
            .into_iter()
            .filter(|s| specialist::ALL.contains(&s.as_str()))
            .collect();
        let chosen = if known.is_empty() {
            // A selector that names nothing usable should not stall the plan.
            vec![specialist::ACTIVITY.to_string()]
        } else {
            known
        };

        step_effects::update_state(PlanState {
            status: plan_status::CONSULTING.to_string(),
            selection: Some(AgentSelection {
                specialists: chosen,
                reason: selection.reason,
            }),
            ..plan.clone()
        })
        .then_transition_to("consult-specialists")
    }
    // docs:end select

    // docs:start consult
    /// Consults every chosen specialist, one after another. A module's calls wait for their
    /// answer, so the step takes the sum of the model calls; each is journalled with the step.
    fn consult_specialists(plan: &PlanState, _: (), ctx: &Context) -> StepEffect<PlanState> {
        let session = PlannerWorkflow::session(ctx);
        let client = ctx.client();
        let chosen = plan
            .selection
            .as_ref()
            .map(|s| s.specialists.clone())
            .unwrap_or_default();

        let mut contributions = Vec::new();
        for specialist in chosen {
            let answer: Result<String, CommandError> = match specialist.as_str() {
                specialist::WEATHER => {
                    client.invoke(WeatherAgent, session, "consult", plan.destination.clone())
                }
                specialist::ACTIVITY => {
                    let request = ActivityRequest {
                        user_id: plan.user_id.clone(),
                        destination: plan.destination.clone(),
                    };
                    client.invoke(ActivityAgent, session, "consult", request)
                }
                specialist::BUDGET => {
                    client.invoke(BudgetAgent, session, "consult", plan.destination.clone())
                }
                other => Ok(format!("no specialist named '{other}'")),
            };
            match answer {
                Ok(answer) => contributions.push(Contribution { specialist, answer }),
                Err(e) => return step_effects::fail(e),
            }
        }

        step_effects::update_state(PlanState {
            contributions,
            ..plan.clone()
        })
        .then_transition_to("summarise")
    }
    // docs:end consult

    // docs:start summarise
    /// Combines the contributions, reading them back from the shared session.
    fn summarise(plan: &PlanState, _: (), ctx: &Context) -> StepEffect<PlanState> {
        let brief: String = match ctx.client().invoke(
            SummaryAgent,
            PlannerWorkflow::session(ctx),
            "summarise",
            plan.destination.clone(),
        ) {
            Ok(brief) => brief,
            Err(e) => return step_effects::fail(e),
        };

        step_effects::update_state(PlanState {
            status: plan_status::COMPLETED.to_string(),
            summary: Some(brief),
            ..plan.clone()
        })
        .then_end()
    }
    // docs:end summarise

    fn plan(plan: &PlanState, _: (), _: &Context) -> ReadOnlyEffect<PlanState> {
        effects::reply(plan.clone())
    }
}

impl Workflow for PlannerWorkflow {
    type State = PlanState;
    const COMPONENT_ID: &'static str = "planner";
    const STATE_MANIFEST: Option<&'static str> = Some("plan-state");

    fn empty_state(_: &str) -> PlanState {
        PlanState::empty()
    }

    fn handlers() -> WorkflowHandlers<PlannerWorkflow> {
        WorkflowHandlers::new()
            .command("start", PlannerWorkflow::start)
            .query("plan", PlannerWorkflow::plan)
    }

    fn steps() -> Steps<PlannerWorkflow> {
        Steps::new()
            .step("select-specialists", PlannerWorkflow::select_specialists)
            .step("consult-specialists", PlannerWorkflow::consult_specialists)
            .step("summarise", PlannerWorkflow::summarise)
    }

    // docs:start planner-settings
    fn settings() -> WorkflowSettings {
        WorkflowSettings::new()
            .timeout(Duration::of_minutes(5))
            // Model calls are slow, and a tool loop can legitimately take a while.
            .default_step_timeout(Duration::of_seconds(90))
            .default_recovery(Recovery::retries(1))
    }
    // docs:end planner-settings
}

// docs:start observe
/// A run read two ways: the plan the workflow keeps, from its own `plan` query, and the engine's
/// standing of the run, which runs no handler. They are separate on purpose: the plan says what the
/// workflow decided, the standing whether it is running, paused, completed or failed.
pub fn observe(
    client: &ankka::Client,
    plan_id: &str,
) -> Result<(PlanState, Lifecycle), CommandError> {
    let state: PlanState = client.invoke(PlannerWorkflow, plan_id, "plan", ())?; // the domain state
    let lifecycle = client.lifecycle(PlannerWorkflow, plan_id)?; // the engine's state
    Ok((state, lifecycle))
}
// docs:end observe

/// The planner and its agents, as a service the tests run against.
pub fn build() -> Service {
    Service::new("ankka-rust")
        .register(PreferencesEntity)
        .register(SelectorAgent)
        .register(WeatherAgent)
        .register(ActivityAgent)
        .register(BudgetAgent)
        .register(SummaryAgent)
        .register(PlannerWorkflow)
}
