//! What the platform's own suites drive to see a module call another service, read the time and
//! ask for random bytes: an entity that records what was asked and what was answered, a consumer
//! that makes the call, a workflow whose step makes one, and the routes that reach them.
//!
//! They are part of the conformance reference only when the module is told so, through the
//! variable `ANKKA_CONFORMANCE_CALLS`: every SDK's reference declares the same components, and
//! these are Rust's alone. Nothing but a suite writes to the entity, so the consumer calls nobody
//! in a module that was not asked to.

use ankka::effects::{consumer, workflow};
use ankka::prelude::*;

/// A call a suite asks the module to make.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Ask {
    pub service: String,
    pub path: String,
}

/// What a call came to: the status and body the service answered, or status `0` and why no answer
/// came.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Answer {
    pub status: i32,
    pub body: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum AskEvent {
    Asked { service: String, path: String },
    Answered { status: i32, body: String },
}

/// Everything asked of one id, and every answer recorded for it, in order.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Asks {
    pub asked: Vec<Ask>,
    pub answers: Vec<Answer>,
}

fn asked(ask: Ask) -> AskEvent {
    AskEvent::Asked {
        service: ask.service,
        path: ask.path,
    }
}

/// The call itself, from wherever it is made: the answer, or why there was none.
fn call(services: ankka::Services, service: &str, path: &str) -> Answer {
    match services
        .service(service)
        .request("GET", path, RequestOptions::default())
    {
        Ok(response) => Answer {
            status: i32::from(response.status),
            body: String::from_utf8_lossy(&response.body).into_owned(),
        },
        Err(error) => Answer {
            status: 0,
            body: error.to_string(),
        },
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

// ── service-asks: what was asked, and what was answered ──

pub struct ServiceAsks;

impl ServiceAsks {
    fn ask(_: &Asks, ask: Ask, _: &Context) -> Effect<AskEvent, Done> {
        effects::persist(asked(ask)).then_reply_value(Done)
    }

    fn answer(_: &Asks, answer: Answer, _: &Context) -> Effect<AskEvent, Done> {
        effects::persist(AskEvent::Answered {
            status: answer.status,
            body: answer.body,
        })
        .then_reply_value(Done)
    }

    /// A command that calls another service, which a command may not: its context offers no
    /// client, so it builds one for itself. Nothing in the crate stops that. The runtime does: the
    /// call does not return, and the command is answered with a fault.
    fn ask_in_command(_: &Asks, ask: Ask, ctx: &Context) -> Effect<AskEvent, Done> {
        assert!(
            ctx.services().is_none(),
            "a command is offered no client for other services"
        );
        let round_the_context = ankka::Services::with_metadata(ctx.metadata().clone());
        let answer = call(round_the_context, &ask.service, &ask.path);
        effects::persist_all([
            asked(ask),
            AskEvent::Answered {
                status: answer.status,
                body: answer.body,
            },
        ])
        .then_reply_value(Done)
    }

    fn get(asks: &Asks, _: (), _: &Context) -> ReadOnlyEffect<Asks> {
        effects::reply(asks.clone())
    }

    /// The time, read in a command: it waits on nothing, so a command may.
    fn now(_: &Asks, _: (), ctx: &Context) -> ReadOnlyEffect<i64> {
        effects::reply(ctx.now().epoch_millis())
    }

    /// Two fills of sixteen random bytes, as hex.
    fn fill(_: &Asks, _: (), ctx: &Context) -> ReadOnlyEffect<Vec<String>> {
        let (mut first, mut second) = ([0u8; 16], [0u8; 16]);
        ctx.random(&mut first);
        ctx.random(&mut second);
        effects::reply(vec![hex(&first), hex(&second)])
    }
}

impl EventSourcedEntity for ServiceAsks {
    type State = Asks;
    type Event = AskEvent;
    const COMPONENT_ID: &'static str = "service-asks";

    fn empty_state(_: &str) -> Asks {
        Asks::default()
    }

    fn apply(mut asks: Asks, event: &AskEvent) -> Asks {
        match event {
            AskEvent::Asked { service, path } => asks.asked.push(Ask {
                service: service.clone(),
                path: path.clone(),
            }),
            AskEvent::Answered { status, body } => asks.answers.push(Answer {
                status: *status,
                body: body.clone(),
            }),
        }
        asks
    }

    fn handlers() -> Handlers<ServiceAsks> {
        Handlers::new()
            .command("ask", ServiceAsks::ask)
            .command("answer", ServiceAsks::answer)
            .command("ask-in-command", ServiceAsks::ask_in_command)
            .query("get", ServiceAsks::get)
            .query("now", ServiceAsks::now)
            .query("fill", ServiceAsks::fill)
    }
}

// ── service-relay: a consumer that calls another service for what it reads ──

pub struct ServiceRelay;

impl Consumer for ServiceRelay {
    type Message = AskEvent;
    const COMPONENT_ID: &'static str = "service-relay";

    fn source() -> Source {
        Source::of(ServiceAsks)
    }

    fn on_message(event: AskEvent, ctx: &Context) -> ConsumerEffect {
        let AskEvent::Asked { service, path } = event else {
            return consumer::ignore();
        };
        let id = ctx.metadata().subject().unwrap_or_default();
        // docs:start service-call
        // A consumer may call another service; an entity's context answers `None` here. The call
        // is made by the runtime as this service, so the other service's ACL can admit it by name.
        let services = ctx.services().expect("a consumer may call another service");
        let answer =
            match services
                .service(&service)
                .request("GET", &path, RequestOptions::default())
            {
                // Whatever the service answered, a refusal included: its status and its body.
                Ok(response) => Answer {
                    status: i32::from(response.status),
                    body: String::from_utf8_lossy(&response.body).into_owned(),
                },
                // No answer came: the service was not found, was not the one named, or did not
                // answer in time. What that means here is the handler's to decide.
                Err(error) => Answer {
                    status: 0,
                    body: error.to_string(),
                },
            };
        // docs:end service-call
        let recorded: Result<Done, CommandError> =
            ctx.client().invoke(ServiceAsks, id, "answer", answer);
        // A panic sends the event again, which is what a consumer that could not record wants.
        recorded.expect("the entity records the answer");
        consumer::done()
    }
}

// ── service-steps: a workflow whose step calls another service ──

/// What a step did: the call it made, what it was answered, and the time it read.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Stepped {
    pub service: String,
    pub path: String,
    pub status: i32,
    pub body: String,
    pub at: i64,
    pub done: bool,
}

pub struct ServiceSteps;

impl ServiceSteps {
    fn start(_: &Stepped, ask: Ask, _: &Context) -> WorkflowEffect<Stepped, Done> {
        workflow::update_state(Stepped {
            service: ask.service,
            path: ask.path,
            ..Stepped::default()
        })
        .transition_to("call")
        .then_reply_value(Done)
    }

    fn status(stepped: &Stepped, _: (), _: &Context) -> ReadOnlyEffect<Stepped> {
        effects::reply(stepped.clone())
    }

    fn call(stepped: &Stepped, _: (), ctx: &Context) -> StepEffect<Stepped> {
        let at = ctx.now().epoch_millis();
        let services = ctx.services().expect("a step may call another service");
        let answer = call(services, &stepped.service, &stepped.path);
        step_effects::update_state(Stepped {
            status: answer.status,
            body: answer.body,
            at,
            done: true,
            ..stepped.clone()
        })
        .then_end()
    }
}

impl Workflow for ServiceSteps {
    type State = Stepped;
    const COMPONENT_ID: &'static str = "service-steps";

    fn empty_state(_: &str) -> Stepped {
        Stepped::default()
    }

    fn handlers() -> WorkflowHandlers<ServiceSteps> {
        WorkflowHandlers::new()
            .command("start", ServiceSteps::start)
            .query("status", ServiceSteps::status)
    }

    fn steps() -> Steps<ServiceSteps> {
        Steps::new().step("call", ServiceSteps::call)
    }
}

// ── the routes that reach them ──

pub struct ServiceCallsEndpoint;

impl ServiceCallsEndpoint {
    /// Sends `command` to the asks of `id`: `ask`, `answer` or `ask-in-command`.
    fn command(request: &Request, ask: Ask) -> Result<Done, HttpProblem> {
        let (id, command) = (request.path("id"), request.path("command"));
        Ok(request.client().invoke(ServiceAsks, id, command, ask)?)
    }

    fn asks(request: &Request) -> Result<Asks, HttpProblem> {
        Ok(request
            .client()
            .invoke(ServiceAsks, request.path("id"), "get", ())?)
    }

    fn time_in_a_command(request: &Request) -> Result<i64, HttpProblem> {
        Ok(request
            .client()
            .invoke(ServiceAsks, request.path("id"), "now", ())?)
    }

    fn fills(request: &Request) -> Result<Vec<String>, HttpProblem> {
        Ok(request
            .client()
            .invoke(ServiceAsks, request.path("id"), "fill", ())?)
    }

    fn start_steps(request: &Request, ask: Ask) -> Result<Done, HttpProblem> {
        Ok(request
            .client()
            .invoke(ServiceSteps, request.path("id"), "start", ask)?)
    }

    fn steps(request: &Request) -> Result<Stepped, HttpProblem> {
        Ok(request
            .client()
            .invoke(ServiceSteps, request.path("id"), "status", ())?)
    }

    /// The time, read in a route.
    fn time(request: &Request) -> Result<i64, HttpProblem> {
        Ok(request.context().now().epoch_millis())
    }
}

impl Endpoint for ServiceCallsEndpoint {
    const ENDPOINT_ID: &'static str = "ServiceCallsEndpoint";
    const PREFIX: &'static str = "/service-calls";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<ServiceCallsEndpoint> {
        Routes::new()
            .get("/now", ServiceCallsEndpoint::time)
            .post("/steps/{id}", ServiceCallsEndpoint::start_steps)
            .get("/steps/{id}", ServiceCallsEndpoint::steps)
            .get("/asks/{id}", ServiceCallsEndpoint::asks)
            .get("/asks/{id}/now", ServiceCallsEndpoint::time_in_a_command)
            .get("/asks/{id}/fill", ServiceCallsEndpoint::fills)
            .post("/asks/{id}/{command}", ServiceCallsEndpoint::command)
    }
}
