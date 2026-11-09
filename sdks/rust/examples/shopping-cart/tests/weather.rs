//! The weather agent with nothing running: a scripted model decides what the loop does, the kit
//! runs the agent's tools and guardrails, and a scripted wallet service answers the tool that
//! calls one.

use ankka::prelude::*;
use ankka::serde_json::json;
use ankka::testkit::{AgentTestKit, EndpointTestKit, ScriptedModel, ScriptedServices};
use shopping_cart::weather::{Forecast, WalletAgent, WeatherAgent, WeatherApi};

// docs:start agent-test
#[test]
fn the_agent_plans_with_its_tools_and_the_loop_runs_them() {
    let model = ScriptedModel::new()
        .expect_tool_call("get_weather", json!({"location": "Lisbon"}))
        .expect_tool_call("get_weather", json!({"location": "Nowhere"}))
        .expect_text("Lisbon: 18C, sunny");
    let mut kit = AgentTestKit::<WeatherAgent>::new("s1", model);
    let reply = kit.call("ask", "Weather in Lisbon?".to_string());
    assert_eq!(
        reply.plan.system.as_deref(),
        Some(WeatherAgent::SYSTEM_MESSAGE)
    );
    assert_eq!(reply.plan.tools, vec!["get_weather", "current_date"]);
    // The tool's `Err` reaches the model as a message, and the loop goes on.
    assert_eq!(
        reply.tool_results,
        vec!["Lisbon: 18C, sunny", "error: unknown location"]
    );
    assert_eq!(reply.reply.as_deref(), Some("Lisbon: 18C, sunny"));
}

#[test]
fn a_guardrail_rejects_before_any_model_is_called() {
    let mut kit = AgentTestKit::<WeatherAgent>::new("s2", ScriptedModel::new());
    let question = "Will it rain in Lisbon on every day of the coming fortnight?".to_string();
    let rejected = kit.call("guarded", question);
    assert_eq!(rejected.error.map(|e| e.code), Some(ErrorCode::Forbidden));
    assert!(rejected.tool_calls.is_empty());
}
// docs:end agent-test

#[test]
fn an_empty_question_is_refused_in_the_plan() {
    let mut kit = AgentTestKit::<WeatherAgent>::new("s3", ScriptedModel::new());
    let refused = kit.call("ask", "   ".to_string());
    assert_eq!(refused.error.map(|e| e.code), Some(ErrorCode::BadRequest));
}

#[test]
fn a_reply_that_looks_like_a_key_is_rejected_on_the_way_out() {
    let model = ScriptedModel::new().expect_text("the key is sk-1");
    let mut kit = AgentTestKit::<WeatherAgent>::new("s4", model);
    let rejected = kit.call("ask", "key?".to_string());
    assert_eq!(rejected.error.map(|e| e.code), Some(ErrorCode::Forbidden));
}

#[test]
fn the_structured_variant_asks_for_json_the_caller_decodes() {
    let text = r#"{"location":"Lisbon","summary":"sunny","degreesCelsius":18}"#;
    let model = ScriptedModel::new().expect_text(text);
    let mut kit = AgentTestKit::<WeatherAgent>::new("s5", model);
    let reply = kit.call("ask-structured", "Lisbon?".to_string());
    assert!(reply.plan.system.unwrap().ends_with("Reply with JSON."));
    let forecast: Forecast = ankka::serde_json::from_str(&reply.reply.unwrap()).unwrap();
    assert_eq!(
        forecast,
        Forecast {
            location: "Lisbon".into(),
            summary: "sunny".into(),
            degrees_celsius: 18,
        }
    );
}

#[test]
fn the_date_tool_takes_no_arguments() {
    let model = ScriptedModel::new()
        .expect_tool_call("current_date", json!({}))
        .expect_text("today");
    let mut kit = AgentTestKit::<WeatherAgent>::new("s6", model);
    let reply = kit.call("ask", "What day is it?".to_string());
    assert_eq!(reply.tool_results[0].len(), "2026-09-23".len());
}

// docs:start service-call-tool-test
#[test]
fn a_tool_calls_the_wallet_service_as_this_service() {
    let services = ScriptedServices::new();
    services.answer("wallet", |request| match request.path.as_str() {
        "/wallet/balances/c-1" => ScriptedServices::text("42"),
        _ => ScriptedServices::status(404, "text/plain", "no such customer"),
    });
    let model = ScriptedModel::new()
        .expect_tool_call("read_balance", json!({"customer": "c-1"}))
        .expect_tool_call("read_balance", json!({"customer": "c-9"}))
        .expect_text("c-1 has 42");
    let mut kit = AgentTestKit::<WalletAgent>::new("s7", model);
    let reply = services.run(|| kit.call("ask", "How much does c-1 have?".to_string()));
    assert_eq!(reply.tool_results[0], "42");
    // A refusal is the tool's error: the model is told it and goes on.
    assert!(reply.tool_results[1].starts_with("error: "));
    assert_eq!(reply.reply.as_deref(), Some("c-1 has 42"));
    assert_eq!(services.requests().len(), 2);
}
// docs:end service-call-tool-test

#[test]
fn the_route_passes_the_agents_refusal_on() {
    // The unit kit answers event sourced entities only: an agent behind a route needs the runtime,
    // and the route answers its refusal rather than inventing a reply.
    let kit = EndpointTestKit::<WeatherApi>::with_service(shopping_cart::weather::build());
    let answered = kit.post("/weather/s1", &"Lisbon?".to_string());
    assert_eq!(answered.status, 503, "{}", answered.text());
}
