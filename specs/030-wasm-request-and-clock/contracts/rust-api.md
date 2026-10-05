# Contract: calling another service, the time and random bytes in Rust

What a developer writing a module with the crate `ankka` sees. Decisions are in
[research.md](../research.md) (R9 to R11). The names follow 025's `sdk-apis.md`, which this is the
Rust column of.

## The context

```rust
impl Context {
    /// The clients for other services, or `None` in an entity, a view and a workflow's command.
    pub fn services(&self) -> Option<Services>;

    /// This context with the clients, as the runtime builds it where they are offered.
    pub fn with_services(self) -> Context;

    /// The runtime's clock, read now. Natively, the machine's clock, or the one a test fixed.
    pub fn now(&self) -> Instant;

    /// Fills `buf` with random bytes from the runtime. Natively, from the system, or from what a
    /// test fixed.
    pub fn random(&self, buf: &mut [u8]);
}
```

`services()` is `Some` exactly where `secrets()` is: an endpoint's route, a workflow's step, a
consumer, a timed action, an agent's handler, tool, guardrail and task rule. It is the crate's
earlier refusal, for the better message; the runtime's rule does not depend on it.

`now()` no longer reads `ankka.now`. It is the time when it is called, so a handler that needs one
time reads it once. `random` fills any length, asking the runtime for at most 65,536 bytes at a
time.

## The client

```rust
impl Services {
    pub fn service(&self, name: &str) -> ServiceClient;
    pub fn service_in(&self, project: &str, name: &str) -> ServiceClient;
}

impl ServiceClient {
    pub fn target(&self) -> &str;                       // "project/name", or "name"
    pub fn get<R: DeserializeOwned>(&self, path: &str) -> Result<R, ServiceError>;
    pub fn get_text(&self, path: &str) -> Result<String, ServiceError>;
    pub fn post<B: Serialize, R: DeserializeOwned>(&self, path: &str, body: &B) -> Result<R, ServiceError>;
    pub fn put<B: Serialize, R: DeserializeOwned>(&self, path: &str, body: &B) -> Result<R, ServiceError>;
    pub fn delete(&self, path: &str) -> Result<(), ServiceError>;
    pub fn request(&self, method: &str, path: &str, options: RequestOptions) -> Result<ServiceResponse, ServiceError>;
    pub fn with_headers(self, headers: &[(&str, &str)]) -> ServiceClient;
}

#[derive(Default)]
pub struct RequestOptions {
    pub body: Option<Vec<u8>>,
    pub content_type: Option<String>,
    pub headers: Vec<(String, String)>,
}

pub struct ServiceResponse {
    pub status: u16,
    pub content_type: String,
    pub body: Vec<u8>,
    pub headers: Vec<(String, String)>,
}
impl ServiceResponse { pub fn text(&self) -> Result<&str, std::str::Utf8Error>; }

pub enum ServiceError {
    Unresolvable { service: String, reason: String },
    IdentityMismatch { service: String, detail: String },
    Unanswered { service: String, reason: String },
    CallFailed { service: String, status: u16, body: Vec<u8> },
    Refused(CommandError),
}
```

- Every call blocks the handler until the runtime answers, as every call the crate makes does.
- The typed helpers send and read JSON through the crate's codec, as an endpoint does, and answer
  `CallFailed` for a status outside 2xx. The raw `request` answers a `ServiceResponse` for every
  status and fails only with the first three errors or `Refused`.
- A body over 4,000,000 bytes is `Refused` by the crate before the import is called, naming the
  limit. A reply with no case set is `Refused` with `Internal`, never an empty answer.
- `ServiceError` implements `std::error::Error` and converts into `CommandError`, so `?` in a
  handler that answers `Result<_, CommandError>` works.

## The imports underneath

`abi::imports` gains `call_request(request: &[u8]) -> Vec<u8>`, `now() -> i64` and
`random(buf: &mut [u8])`, each over an `extern` block of its own, so a module links only the ones
it calls. `Import::Request` exists for a `NativeHost` to match on; passing it to `call` panics, as
the secret store's variants do.

They are public, and so is `Services::with_metadata`. A command that builds a client round its
context, or calls `abi::imports::call_request` itself, is not stopped by the crate, and is stopped
by the runtime.

## Natively, and in a test

Outside `wasm32` there is no runtime: `now()` is the machine's clock, `random` is the system's
source (`getrandom`, a dependency of the native target only), and a call to another service is
refused as unavailable, unless a test says otherwise.

A test says otherwise for the length of a closure, on its own thread, beside whichever
`NativeHost` a test kit installs for component calls — which is why these are not methods of
`NativeHost`: a kit that installs its own host would otherwise replace them. `abi::imports` has
`with_native_services`, `with_native_clock` and `with_native_random`; a `NativeHost` is still
asked for `Import::Request` when no services are in place. The unit test kit builds on them:

- `ScriptedServices::new()`, then `scripted.answer("wallet", |request| ScriptedServices::json(…))`,
  `scripted.unresolvable("ledger")`, `scripted.unanswered("wallet")`,
  `scripted.mismatch("wallet")`, `scripted.requests()`, every request made, in order, and
  `scripted.run(|| …)`, which runs the code under test with them in place. A call to a service
  with no script fails the test, naming the service.
- `with_clock(instant, || …)` and `with_random(&bytes, || …)`, which make `ctx.now()` and
  `ctx.random` answer what the test chose.

The integration test kit is unchanged: it starts the runtime's image, where the imports are the
runtime's.

## The version

`PROTOCOL_VERSION` becomes `1.10`. A module built with this crate that calls none of the three runs
on an older runtime; one that calls `ctx.now()` needs a runtime that speaks 1.10, and an older one
refuses it at start, naming `now`.

## The example

`sdks/rust/examples/shopping-cart`, under its `conformance` feature, gains what the suites drive:

- `POST /conformance/service-call`, the route 025's `service.*` cases use, in Rust. It is always
  there: a route is not a component.
- In `service_calls.rs`, and registered only when the module's `config` answers
  `ANKKA_CONFORMANCE_CALLS`, because the conformance suite holds every reference to exactly the
  same components:
  - `service-asks`, an event sourced entity: `ask {service, path}` records `Asked`, `answer
    {status, body}` records `Answered`, and its state is every one of each. Nothing but a test
    writes to it. Its queries `now` and `fill` read the time and two fills of random bytes.
  - `service-relay`, a consumer over the events of `service-asks`: for each `Asked` it calls the
    service through `ctx.services()` and sends what it was answered back as `answer`; no answer
    is recorded as status `0`, so nothing it is asked can stall it. The call is the region
    `docs:start service-call`, which the SDK reference includes.
  - `ask-in-command`, a command of `service-asks` that builds a client round its context with
    `Services::with_metadata`: the handler the crate does not stop and the runtime does.
  - `service-steps`, a workflow whose step reads the time and calls the service it was started
    with.
  - `ServiceCallsEndpoint`, the routes under `/service-calls` that reach them.

The example without the feature is unchanged: a consumer that called a service nobody runs would
fail every delivery and hold its projection for every test after it.

## What a test must show

- **The module links only what it calls.** `wasm-objdump -j Import` of the example built without
  the conformance feature lists `now`, which its notifier reads, and neither `request` nor
  `random`; built with the feature it lists all three.
- **`services()` is `None` where it must be.** A unit test per kind, entity, view and workflow
  command among them, beside the kinds where it is `Some`.
- **A script that runs out fails.** A call to an unscripted service panics naming it, and the test
  kit's own test asserts the panic.
