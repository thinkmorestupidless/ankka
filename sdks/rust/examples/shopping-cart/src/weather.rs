//! The weather agent the documentation's agent pages describe: a system message, two tools, two
//! guardrails and a structured variant, plus an agent that names its provider, one whose tool calls
//! another service, and the route that calls an agent. None of these is in the module's service;
//! `build` here assembles them for the tests.

use ankka::effects::agent;
use ankka::prelude::*;
use ankka::serde_json::Value;

// docs:start forecast
/// A structured answer: the model is asked for this shape, and the caller decodes it.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Forecast {
    pub location: String,
    pub summary: String,
    #[serde(rename = "degreesCelsius")]
    pub degrees_celsius: i32,
}
// docs:end forecast

/// The `get_weather` tool's arguments, as the model fills its schema.
#[derive(Debug, Deserialize)]
pub struct Location {
    pub location: String,
    pub date: Option<String>,
}

pub struct WeatherAgent;

impl WeatherAgent {
    pub const SYSTEM_MESSAGE: &'static str = "You are a concise weather assistant.";

    fn ask(question: String, _: &Context) -> AgentEffect {
        if question.trim().is_empty() {
            return agent::error(ErrorCode::BadRequest, "ask me something");
        }
        agent::system_message(WeatherAgent::SYSTEM_MESSAGE)
            .user_message(question)
            .tools(["get_weather", "current_date"])
            .guardrails(["no-keys"])
            .then_reply()
    }

    // docs:start structured
    /// Same interaction, but the model is asked for a `Forecast` as JSON; whoever called the
    /// agent decodes the reply into one.
    fn ask_structured(question: String, _: &Context) -> AgentEffect {
        agent::system_message(format!("{} Reply with JSON.", WeatherAgent::SYSTEM_MESSAGE))
            .user_message(question)
            .json_shape(r#"{"location":"...","summary":"...","degreesCelsius":0}"#)
            .then_reply()
    }
    // docs:end structured

    // docs:start guarded
    /// Guarded, to exercise rejection before a model is called.
    fn guarded(question: String, _: &Context) -> AgentEffect {
        agent::system_message(WeatherAgent::SYSTEM_MESSAGE)
            .user_message(question)
            .guardrails(["max-input-length"])
            .then_reply()
    }

    /// Checks the input only: a question longer than forty characters is rejected before any
    /// model is called.
    fn max_input_length(stage: Stage, text: &str, _: &Context) -> Result<(), String> {
        if stage == Stage::Input && text.chars().count() > 40 {
            Err("the input is longer than 40 characters".to_string())
        } else {
            Ok(())
        }
    }
    // docs:end guarded

    // docs:start no-keys
    /// Checks the output only: a reply that looks like it carries a key is rejected before memory
    /// is written.
    fn no_keys(stage: Stage, text: &str, _: &Context) -> Result<(), String> {
        if stage == Stage::Output && text.contains("sk-") {
            Err("the reply contains what looks like a key".to_string())
        } else {
            Ok(())
        }
    }
    // docs:end no-keys

    // docs:start tools
    /// `Err` is a message for the model, which carries on.
    fn get_weather(args: Location, _: &Context) -> Result<String, String> {
        let _ = args.date;
        if args.location == "Nowhere" {
            return Err("unknown location".to_string());
        }
        Ok(format!("{}: 18C, sunny", args.location))
    }

    /// A tool with no arguments takes the empty object the model sends.
    fn current_date(_: Value, ctx: &Context) -> Result<String, String> {
        // The instant prints as `2026-09-23T10:00:00Z`; the date is its first ten characters.
        Ok(ctx.now().to_string().chars().take(10).collect())
    }
    // docs:end tools
}

impl Agent for WeatherAgent {
    const COMPONENT_ID: &'static str = "weather-agent";

    fn handlers() -> AgentHandlers<WeatherAgent> {
        AgentHandlers::new()
            .command("ask", WeatherAgent::ask)
            .command("ask-structured", WeatherAgent::ask_structured)
            .command("guarded", WeatherAgent::guarded)
    }

    // docs:start tools-declared
    fn tools() -> Tools<WeatherAgent> {
        Tools::new()
            .tool(
                "get_weather",
                "Returns the weather forecast for a given city.",
                Schema::object()
                    .string("location", "A location or city name.")
                    .optional_string("date", "Forecast date, in yyyy-MM-dd format."),
                WeatherAgent::get_weather,
            )
            .tool(
                "current_date",
                "Returns today's date in yyyy-MM-dd format.",
                Schema::object(),
                WeatherAgent::current_date,
            )
    }
    // docs:end tools-declared

    fn guardrails() -> Guardrails<WeatherAgent> {
        Guardrails::new()
            .guardrail("max-input-length", WeatherAgent::max_input_length)
            .guardrail("no-keys", WeatherAgent::no_keys)
    }
}

// docs:start naming-a-provider
pub struct Triage;

impl Triage {
    fn route(question: String, _: &Context) -> AgentEffect {
        agent::system_message("Say which team should answer.")
            .user_message(question)
            // A provider the runtime's configuration knows, by its name; the runtime's default
            // when none is named.
            .model("gpt")
            .then_reply()
    }
}

impl Agent for Triage {
    const COMPONENT_ID: &'static str = "triage";

    fn handlers() -> AgentHandlers<Triage> {
        AgentHandlers::new().command("route", Triage::route)
    }
}
// docs:end naming-a-provider

/// The `read_balance` tool's arguments.
#[derive(Debug, Deserialize)]
pub struct Customer {
    pub customer: String,
}

// docs:start service-call-tool
/// A support agent whose tool reads a balance from the wallet service, as its own service.
pub struct WalletAgent;

impl WalletAgent {
    fn ask(question: String, _: &Context) -> AgentEffect {
        agent::system_message("You answer questions about a customer's wallet.")
            .user_message(question)
            .tools(["read_balance"])
            .then_reply()
    }

    fn read_balance(args: Customer, ctx: &Context) -> Result<String, String> {
        let services = ctx
            .services()
            .ok_or_else(|| "a tool may call another service".to_string())?;
        services
            .service("wallet")
            .get_text(&format!("/wallet/balances/{}", args.customer))
            .map_err(|e| e.to_string())
    }
}

impl Agent for WalletAgent {
    const COMPONENT_ID: &'static str = "wallet-agent";

    fn handlers() -> AgentHandlers<WalletAgent> {
        AgentHandlers::new().command("ask", WalletAgent::ask)
    }

    fn tools() -> Tools<WalletAgent> {
        Tools::new().tool(
            "read_balance",
            "Reads a customer's wallet balance.",
            Schema::object().string("customer", "The customer whose balance to read."),
            WalletAgent::read_balance,
        )
    }
}
// docs:end service-call-tool

// docs:start call-agent
pub struct WeatherApi;

impl WeatherApi {
    /// Calls the agent for the session in the path; the reply is whole, since a module cannot
    /// stream one.
    fn ask(request: &Request, question: String) -> Result<String, HttpProblem> {
        let session = request.path("session");
        Ok(request
            .client()
            .invoke(WeatherAgent, session, "ask", question)?)
    }

    /// The structured variant: the reply is decoded as a `Forecast` on the way out.
    fn forecast(request: &Request, question: String) -> Result<Forecast, HttpProblem> {
        let reply: String = request.client().invoke(
            WeatherAgent,
            request.path("session"),
            "ask-structured",
            question,
        )?;
        ankka::serde_json::from_str(&reply)
            .map_err(|e| HttpProblem::new(502, format!("the reply is not a forecast: {e}")))
    }
}

impl Endpoint for WeatherApi {
    const ENDPOINT_ID: &'static str = "WeatherApi";
    const PREFIX: &'static str = "/weather";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<WeatherApi> {
        Routes::new()
            .post("/{session}", WeatherApi::ask)
            .post("/{session}/forecast", WeatherApi::forecast)
    }
}
// docs:end call-agent

/// The agents and the route in front of them, as a service the tests run against.
pub fn build() -> Service {
    Service::new("ankka-rust")
        .register(WeatherAgent)
        .register(Triage)
        .register(WalletAgent)
        .endpoint(WeatherApi)
}
