//! The secrets page's examples: where a person gives the service a credential, a workflow step
//! that reads one when it needs it, and a consumer that keeps one the first time it sees a
//! warehouse. Not in the module's service; `build` assembles them for the tests.

use ankka::effects::{consumer, workflow};
use ankka::prelude::*;

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct KeepSecret {
    pub name: String,
    pub value: String,
}

// docs:start credentials-endpoint
/// Where a person gives the service a credential it must keep. The value goes to the secret store
/// and nowhere else: not to an entity, so it reaches no journal, snapshot or view.
pub struct ProviderCredentialsEndpoint;

impl ProviderCredentialsEndpoint {
    fn secrets(request: &Request) -> Result<ankka::Secrets, HttpProblem> {
        request
            .context()
            .secrets()
            .ok_or_else(|| HttpProblem::new(500, "an endpoint has a secret store"))
    }

    fn keep(request: &Request, keep: KeepSecret) -> Result<Done, HttpProblem> {
        Self::secrets(request)?.put(&keep.name, &keep.value)?;
        Ok(Done)
    }

    fn read(request: &Request) -> Result<String, HttpProblem> {
        let name = request
            .query("name")
            .ok_or_else(|| HttpProblem::new(400, "the query needs 'name'"))?;
        Self::secrets(request)?
            .get(name)?
            .ok_or_else(|| HttpProblem::new(404, format!("no secret '{name}'")))
    }

    fn remove(request: &Request) -> Result<Done, HttpProblem> {
        let name = request
            .query("name")
            .ok_or_else(|| HttpProblem::new(400, "the query needs 'name'"))?;
        Self::secrets(request)?.delete(name)?;
        Ok(Done)
    }
}

impl Endpoint for ProviderCredentialsEndpoint {
    const ENDPOINT_ID: &'static str = "ProviderCredentialsEndpoint";
    const PREFIX: &'static str = "/credentials";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<ProviderCredentialsEndpoint> {
        Routes::new()
            .post("/", ProviderCredentialsEndpoint::keep)
            .get("/", ProviderCredentialsEndpoint::read)
            .delete("/", ProviderCredentialsEndpoint::remove)
    }
}
// docs:end credentials-endpoint

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Charge {
    pub provider: String,
    pub amount: i32,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct ChargeState {
    pub provider: String,
    #[serde(rename = "credentialLength")]
    pub credential_length: usize,
    pub status: String,
}

// docs:start charge-workflow
/// Charges a customer through a payment provider. The step reads the provider's credential from
/// the secret store when it needs it, and records only that it charged.
pub struct ChargeWorkflow;

impl ChargeWorkflow {
    fn start(_: &ChargeState, charge: Charge, _: &Context) -> WorkflowEffect<ChargeState, Done> {
        workflow::update_state(ChargeState {
            provider: charge.provider.clone(),
            credential_length: 0,
            status: "started".to_string(),
        })
        .transition_to_with("charge", charge)
        .then_reply_value(Done)
    }

    fn charge(state: &ChargeState, charge: Charge, ctx: &Context) -> StepEffect<ChargeState> {
        // A step has the store; the command handler above does not.
        let secrets = ctx.secrets().expect("a workflow step has a secret store");
        let credential = match secrets.get(&format!("provider/{}", charge.provider)) {
            Ok(Some(credential)) => credential,
            Ok(None) => {
                let why = format!("no credential for {}", charge.provider);
                return step_effects::fail(CommandError::new(ErrorCode::Internal, why));
            }
            Err(e) => return step_effects::fail(e),
        };
        // Calling the provider with `credential` goes here. The state records that it was
        // charged, and never the credential.
        step_effects::update_state(ChargeState {
            credential_length: credential.len(),
            status: "charged".to_string(),
            ..state.clone()
        })
        .then_end()
    }

    fn status(state: &ChargeState, _: (), _: &Context) -> ReadOnlyEffect<ChargeState> {
        effects::reply(state.clone())
    }
}

impl Workflow for ChargeWorkflow {
    type State = ChargeState;
    const COMPONENT_ID: &'static str = "charge";
    const STATE_MANIFEST: Option<&'static str> = Some("charge-state");

    fn empty_state(_: &str) -> ChargeState {
        ChargeState {
            provider: String::new(),
            credential_length: 0,
            status: "not-started".to_string(),
        }
    }

    fn handlers() -> WorkflowHandlers<ChargeWorkflow> {
        WorkflowHandlers::new()
            .command("start", ChargeWorkflow::start)
            .query("status", ChargeWorkflow::status)
    }

    fn steps() -> Steps<ChargeWorkflow> {
        Steps::new().step("charge", ChargeWorkflow::charge)
    }
}
// docs:end charge-workflow

/// What the warehouses' topic carries.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct StockEvent {
    pub sku: String,
    pub delta: i32,
    pub warehouse: String,
}

// docs:start credential-keeper
/// A consumer that keeps a warehouse's credential the first time it sees the warehouse.
pub struct WarehouseCredentialKeeper;

impl Consumer for WarehouseCredentialKeeper {
    type Message = StockEvent;
    const COMPONENT_ID: &'static str = "warehouse-credential-keeper";

    fn source() -> Source {
        Source::topic("stock-events")
    }

    fn start_from() -> Option<StartFrom> {
        Some(StartFrom::Earliest)
    }

    fn on_message(event: StockEvent, ctx: &Context) -> ConsumerEffect {
        let secrets = ctx.secrets().expect("a consumer has a secret store");
        let name = format!("warehouse/{}", event.warehouse);
        // A refused read or write panics, which has the message delivered again.
        if secrets.get(&name).expect("the store answers").is_none() {
            secrets
                .put(&name, &format!("token-{}", event.warehouse))
                .expect("the store keeps it");
        }
        consumer::ignore()
    }
}
// docs:end credential-keeper

/// The three, as a service the tests run against.
pub fn build() -> Service {
    Service::new("ankka-rust")
        .register(ChargeWorkflow)
        .register(WarehouseCredentialKeeper)
        .endpoint(ProviderCredentialsEndpoint)
}
