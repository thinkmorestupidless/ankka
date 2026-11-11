//! The workflows page's examples in Rust: a transfer between two wallets with compensation, a quote
//! an endpoint starts and waits for, and an onboarding whose step starts another workflow and waits
//! for its end. None of it is in the module's service; `build` assembles it for the tests.

use ankka::effects::workflow;
use ankka::prelude::*;

// ── the wallets a transfer moves money between ──

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Wallet {
    pub balance: i32,
    pub frozen: bool,
}

pub struct WalletEntity;

impl WalletEntity {
    fn deposit(wallet: &Wallet, amount: i32, ctx: &Context) -> KeyValueEffect<Wallet, Done> {
        if amount <= 0 {
            return effects::error(ErrorCode::BadRequest, "deposit must be positive").into();
        }
        if wallet.frozen {
            let message = format!("wallet '{}' is frozen", ctx.entity_id());
            return effects::error(ErrorCode::Conflict, message).into();
        }
        effects::update_state(Wallet {
            balance: wallet.balance + amount,
            ..*wallet
        })
        .then_reply_value(Done)
    }

    fn withdraw(wallet: &Wallet, amount: i32, ctx: &Context) -> KeyValueEffect<Wallet, Done> {
        if amount <= 0 {
            return effects::error(ErrorCode::BadRequest, "withdrawal must be positive").into();
        }
        if wallet.balance < amount {
            let message = format!(
                "wallet '{}' has {}, cannot withdraw {amount}",
                ctx.entity_id(),
                wallet.balance
            );
            return effects::error(ErrorCode::Conflict, message).into();
        }
        effects::update_state(Wallet {
            balance: wallet.balance - amount,
            ..*wallet
        })
        .then_reply_value(Done)
    }

    /// Makes deposits fail, so compensation paths can be exercised deliberately.
    fn freeze(wallet: &Wallet, _: (), _: &Context) -> KeyValueEffect<Wallet, Done> {
        effects::update_state(Wallet {
            frozen: true,
            ..*wallet
        })
        .then_reply_value(Done)
    }

    fn balance(wallet: &Wallet, _: (), _: &Context) -> ReadOnlyEffect<i32> {
        effects::reply(wallet.balance)
    }
}

impl KeyValueEntity for WalletEntity {
    type State = Wallet;
    const COMPONENT_ID: &'static str = "wallet";
    const STATE_MANIFEST: Option<&'static str> = Some("wallet");

    fn empty_state(_: &str) -> Wallet {
        Wallet {
            balance: 0,
            frozen: false,
        }
    }

    fn handlers() -> KeyValueHandlers<WalletEntity> {
        KeyValueHandlers::new()
            .command("deposit", WalletEntity::deposit)
            .command("withdraw", WalletEntity::withdraw)
            .command("freeze", WalletEntity::freeze)
            .query("balance", WalletEntity::balance)
    }
}

// ── the transfer ──

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Transfer {
    pub from: String,
    pub to: String,
    pub amount: i32,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct TransferState {
    pub transfer: Transfer,
    pub status: String,
}

/// Move money between two wallets, with compensation if the deposit fails.
///
/// The interesting property is not the happy path: it is that a crash after the withdrawal but
/// before the deposit does not lose the money. The withdrawal is journalled as a completed step,
/// so recovery resumes at the deposit rather than starting over or giving up.
pub struct TransferWorkflow;

impl TransferWorkflow {
    // docs:start transfer-start
    fn start(
        state: &TransferState,
        transfer: Transfer,
        _: &Context,
    ) -> WorkflowEffect<TransferState, Done> {
        if transfer.amount <= 0 {
            let message = "transfer amount must be greater than zero";
            return effects::error(ErrorCode::BadRequest, message).into();
        }
        if state.status != "not-started" {
            return effects::error(ErrorCode::Conflict, "transfer already started").into();
        }
        workflow::update_state(TransferState {
            transfer: transfer.clone(),
            status: "started".to_string(),
        })
        .transition_to_with("withdraw", transfer)
        .then_reply_value(Done)
    }
    // docs:end transfer-start

    // docs:start transfer-steps
    fn withdraw(
        state: &TransferState,
        transfer: Transfer,
        ctx: &Context,
    ) -> StepEffect<TransferState> {
        let withdrawn: Result<Done, CommandError> =
            ctx.client()
                .invoke(WalletEntity, &transfer.from, "withdraw", transfer.amount);
        if let Err(e) = withdrawn {
            return step_effects::fail(e);
        }
        step_effects::update_state(TransferState {
            status: "withdrawn".to_string(),
            ..state.clone()
        })
        .then_transition_to_with("deposit", transfer)
    }

    fn deposit(
        state: &TransferState,
        transfer: Transfer,
        ctx: &Context,
    ) -> StepEffect<TransferState> {
        let deposited: Result<Done, CommandError> =
            ctx.client()
                .invoke(WalletEntity, &transfer.to, "deposit", transfer.amount);
        if let Err(e) = deposited {
            return step_effects::fail(e);
        }
        step_effects::update_state(TransferState {
            status: "completed".to_string(),
            ..state.clone()
        })
        .then_end()
    }

    /// Puts the money back.
    ///
    /// Takes no input and reads the transfer from the state, which is why failover steps are
    /// input-free: what needs compensating is whatever the workflow has recorded, not whatever
    /// was known when the settings were written.
    fn compensate(state: &TransferState, _: (), ctx: &Context) -> StepEffect<TransferState> {
        let transfer = &state.transfer;
        let returned: Result<Done, CommandError> =
            ctx.client()
                .invoke(WalletEntity, &transfer.from, "deposit", transfer.amount);
        if let Err(e) = returned {
            return step_effects::fail(e);
        }
        step_effects::update_state(TransferState {
            status: "compensated".to_string(),
            ..state.clone()
        })
        .then_end()
    }
    // docs:end transfer-steps

    fn status(state: &TransferState, _: (), _: &Context) -> ReadOnlyEffect<TransferState> {
        effects::reply(state.clone())
    }
}

// docs:start transfer-companion
impl Workflow for TransferWorkflow {
    type State = TransferState;
    const COMPONENT_ID: &'static str = "transfer";
    const STATE_MANIFEST: Option<&'static str> = Some("transfer-state");

    fn empty_state(_: &str) -> TransferState {
        TransferState {
            transfer: Transfer {
                from: String::new(),
                to: String::new(),
                amount: 0,
            },
            status: "not-started".to_string(),
        }
    }

    fn handlers() -> WorkflowHandlers<TransferWorkflow> {
        WorkflowHandlers::new()
            .command("start", TransferWorkflow::start)
            .query("status", TransferWorkflow::status)
    }

    // `deposit` takes an input, a `Transfer`; `compensate` takes none. Step names and handler
    // names are wire names: renaming the Rust function is safe, renaming the string is a
    // protocol change for every instance already in flight.
    fn steps() -> Steps<TransferWorkflow> {
        Steps::new()
            .step("withdraw", TransferWorkflow::withdraw)
            .step("deposit", TransferWorkflow::deposit)
            .step("compensate", TransferWorkflow::compensate)
    }
    // docs:end transfer-companion

    // docs:start transfer-settings
    fn settings() -> WorkflowSettings {
        WorkflowSettings::new()
            .timeout(Duration::of_seconds(60))
            .default_step_timeout(Duration::of_seconds(10))
            // One retry, then compensate. The deposit is the step that can fail for reasons
            // outside this workflow's control, so it is the one worth a second attempt.
            .step_recovery("deposit", Recovery::retries(1).failover_to("compensate"))
    }
    // docs:end transfer-settings
}

// ── a quote, started and waited for from a route ──

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct QuoteRequest {
    pub amount: i32,
}

/// A quote, worked out over two steps: the rate, then the offer.
#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Quote {
    pub amount: i32,
    pub rate: Option<i32>,
    pub total: Option<i32>,
}

pub struct QuoteWorkflow;

impl QuoteWorkflow {
    fn start(_: &Quote, request: QuoteRequest, _: &Context) -> WorkflowEffect<Quote, Done> {
        workflow::update_state(Quote {
            amount: request.amount,
            rate: None,
            total: None,
        })
        .transition_to("rate")
        .then_reply_value(Done)
    }

    fn rate(quote: &Quote, _: (), _: &Context) -> StepEffect<Quote> {
        step_effects::update_state(Quote {
            rate: Some(3),
            ..quote.clone()
        })
        .then_transition_to("offer")
    }

    fn offer(quote: &Quote, _: (), _: &Context) -> StepEffect<Quote> {
        let rate = quote.rate.unwrap_or(0);
        step_effects::update_state(Quote {
            total: Some(quote.amount + quote.amount * rate / 100),
            ..quote.clone()
        })
        .then_end()
    }
}

impl Workflow for QuoteWorkflow {
    type State = Quote;
    const COMPONENT_ID: &'static str = "quote";

    fn empty_state(_: &str) -> Quote {
        Quote {
            amount: 0,
            rate: None,
            total: None,
        }
    }

    fn handlers() -> WorkflowHandlers<QuoteWorkflow> {
        WorkflowHandlers::new().command("start", QuoteWorkflow::start)
    }

    fn steps() -> Steps<QuoteWorkflow> {
        Steps::new()
            .step("rate", QuoteWorkflow::rate)
            .step("offer", QuoteWorkflow::offer)
    }
}

/// A transfer's standing, over HTTP.
pub struct TransferApi;

impl TransferApi {
    // docs:start lifecycle
    /// The engine's view of a transfer: running, paused, completed or failed, and why it failed.
    /// The transfer's own state answers what it decided; this read runs no handler of it.
    fn lifecycle(request: &Request) -> Result<String, HttpProblem> {
        let lifecycle = request
            .client()
            .lifecycle(TransferWorkflow, request.path("id"))?;
        Ok(match lifecycle.failure {
            Some(reason) => format!("{:?}: {reason}", lifecycle.status),
            None => format!("{:?}", lifecycle.status),
        })
    }
    // docs:end lifecycle
}

impl Endpoint for TransferApi {
    const ENDPOINT_ID: &'static str = "TransferApi";
    const PREFIX: &'static str = "/transfers";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<TransferApi> {
        Routes::new().get("/{id}/lifecycle", TransferApi::lifecycle)
    }
}

/// The routes of a pricing service that answer a quote's request with the quote.
pub struct QuoteEndpoint;

impl QuoteEndpoint {
    // docs:start start-and-await
    /// Starts the quote and answers the request with the quote, once the workflow has ended.
    fn quote(request: &Request, asked: QuoteRequest) -> Result<Quote, HttpProblem> {
        Ok(request.client().invoke_then_await_end(
            QuoteWorkflow,
            request.path("id"),
            "start",
            asked,
            Duration::of_seconds(30),
        )?)
    }
    // docs:end start-and-await

    // docs:start await-later
    /// Answers with a quote started earlier, by anyone: at once if it has ended, when it ends if
    /// not.
    fn wait(request: &Request) -> Result<Quote, HttpProblem> {
        Ok(request.client().await_end(
            QuoteWorkflow,
            request.path("id"),
            Duration::of_seconds(10),
        )?)
    }
    // docs:end await-later
}

impl Endpoint for QuoteEndpoint {
    const ENDPOINT_ID: &'static str = "QuoteEndpoint";
    const PREFIX: &'static str = "/quotes";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<QuoteEndpoint> {
        Routes::new()
            .post("/{id}", QuoteEndpoint::quote)
            .get("/{id}/wait", QuoteEndpoint::wait)
    }
}

// ── onboarding: a step that starts another workflow and waits for its end ──

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Kyc {
    pub applicant: String,
    pub decision: Option<String>,
}

/// A check of an applicant over two steps, `documents` and `decision`.
pub struct KycWorkflow;

impl KycWorkflow {
    fn start(_: &Kyc, applicant: String, _: &Context) -> WorkflowEffect<Kyc, Done> {
        workflow::update_state(Kyc {
            applicant,
            decision: None,
        })
        .transition_to("documents")
        .then_reply_value(Done)
    }

    fn documents(_: &Kyc, _: (), _: &Context) -> StepEffect<Kyc> {
        step_effects::transition_to("decision")
    }

    fn decision(kyc: &Kyc, _: (), _: &Context) -> StepEffect<Kyc> {
        let decision = if kyc.applicant.starts_with("refused") {
            "declined"
        } else {
            "approved"
        };
        step_effects::update_state(Kyc {
            decision: Some(decision.to_string()),
            ..kyc.clone()
        })
        .then_end()
    }
}

impl Workflow for KycWorkflow {
    type State = Kyc;
    const COMPONENT_ID: &'static str = "kyc";
    const STATE_MANIFEST: Option<&'static str> = Some("kyc");

    fn empty_state(_: &str) -> Kyc {
        Kyc {
            applicant: String::new(),
            decision: None,
        }
    }

    fn handlers() -> WorkflowHandlers<KycWorkflow> {
        WorkflowHandlers::new().command("start", KycWorkflow::start)
    }

    fn steps() -> Steps<KycWorkflow> {
        Steps::new()
            .step("documents", KycWorkflow::documents)
            .step("decision", KycWorkflow::decision)
    }
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq, Eq)]
pub struct Onboarding {
    pub applicant: String,
    pub status: String,
}

/// Onboards an applicant: its `verify` step starts a `kyc` workflow for the applicant and waits
/// for its end within the step's own timeout, then goes on to the step the decision chooses.
pub struct OnboardingWorkflow;

impl OnboardingWorkflow {
    fn verify_timeout() -> Duration {
        Duration::of_seconds(2)
    }

    fn start(_: &Onboarding, applicant: String, _: &Context) -> WorkflowEffect<Onboarding, Done> {
        workflow::update_state(Onboarding {
            applicant,
            status: "verifying".to_string(),
        })
        .transition_to("verify")
        .then_reply_value(Done)
    }

    // docs:start step-awaits
    fn verify(onboarding: &Onboarding, _: (), ctx: &Context) -> StepEffect<Onboarding> {
        let kyc: Kyc = match ctx.client().invoke_then_await_end(
            KycWorkflow,
            &format!("kyc-{}", ctx.entity_id()),
            "start",
            onboarding.applicant.clone(),
            OnboardingWorkflow::verify_timeout(),
        ) {
            Ok(kyc) => kyc,
            Err(e) => return step_effects::fail(e),
        };
        if kyc.decision.as_deref() == Some("approved") {
            step_effects::transition_to("welcome")
        } else {
            step_effects::transition_to("decline")
        }
    }
    // docs:end step-awaits

    fn welcome(onboarding: &Onboarding, _: (), _: &Context) -> StepEffect<Onboarding> {
        step_effects::update_state(Onboarding {
            status: "welcomed".to_string(),
            ..onboarding.clone()
        })
        .then_end()
    }

    fn decline(onboarding: &Onboarding, _: (), _: &Context) -> StepEffect<Onboarding> {
        step_effects::update_state(Onboarding {
            status: "declined".to_string(),
            ..onboarding.clone()
        })
        .then_end()
    }

    fn status(onboarding: &Onboarding, _: (), _: &Context) -> ReadOnlyEffect<Onboarding> {
        effects::reply(onboarding.clone())
    }
}

impl Workflow for OnboardingWorkflow {
    type State = Onboarding;
    const COMPONENT_ID: &'static str = "onboarding";
    const STATE_MANIFEST: Option<&'static str> = Some("onboarding");

    fn empty_state(_: &str) -> Onboarding {
        Onboarding {
            applicant: String::new(),
            status: "not-started".to_string(),
        }
    }

    fn handlers() -> WorkflowHandlers<OnboardingWorkflow> {
        WorkflowHandlers::new()
            .command("start", OnboardingWorkflow::start)
            .query("status", OnboardingWorkflow::status)
    }

    fn steps() -> Steps<OnboardingWorkflow> {
        Steps::new()
            .step("verify", OnboardingWorkflow::verify)
            .step("welcome", OnboardingWorkflow::welcome)
            .step("decline", OnboardingWorkflow::decline)
    }

    fn settings() -> WorkflowSettings {
        WorkflowSettings::new()
            .default_step_timeout(Duration::of_seconds(60))
            .step_timeout("verify", OnboardingWorkflow::verify_timeout())
    }
}

// docs:start transfer-service
/// The wallets and the workflows over them, as a service the tests run against.
pub fn build() -> Service {
    Service::new("ankka-rust")
        .register(WalletEntity)
        .register(TransferWorkflow)
        .register(QuoteWorkflow)
        .register(KycWorkflow)
        .register(OnboardingWorkflow)
        .endpoint(QuoteEndpoint)
}
// docs:end transfer-service
