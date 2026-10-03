//! Testing a service at two levels, as every ankka SDK does.
//!
//! [`unit`](mod@unit) runs one component natively — no module, no runtime, no Docker — driving it through the
//! same dispatch its exports use, so every input, event, state and reply crosses its codec as it
//! would on the wire. `integration` (feature `testkit`) builds the service's module and runs it in
//! the real runtime image against a throwaway Postgres.

#[cfg(all(feature = "testkit", not(target_arch = "wasm32")))]
pub mod integration;
#[cfg(not(target_arch = "wasm32"))]
pub mod kinds;
#[cfg(not(target_arch = "wasm32"))]
pub mod unit;

#[cfg(all(feature = "testkit", not(target_arch = "wasm32")))]
pub use integration::{AnkkaTestKit, Http, Module, TestkitError};
#[cfg(not(target_arch = "wasm32"))]
pub use kinds::{
    AgentReply, AgentTestKit, Answered, AutonomousAgentTestKit, ConsumerTestKit,
    GraphConsumerTestKit, KeyValueEntityTestKit, KeyValueOutcome, Published, ScriptedModel,
    StepNext, TimedActionTestKit, ViewTestKit, WorkflowTestKit,
};
#[cfg(not(target_arch = "wasm32"))]
pub use unit::{CommandOutcome, EndpointTestKit, EventSourcedTestKit, with_config};

use serde::de::DeserializeOwned;

/// What an HTTP request to a service answered, from either testkit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TestResponse {
    /// The status code.
    pub status: u16,
    /// The content type, empty when there is no body.
    pub content_type: String,
    /// The body's bytes.
    pub body: Vec<u8>,
}

impl TestResponse {
    /// The body as text.
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    /// The body read as JSON into `T`.
    pub fn json<T: DeserializeOwned>(&self) -> Result<T, serde_json::Error> {
        serde_json::from_slice(&self.body)
    }
}
