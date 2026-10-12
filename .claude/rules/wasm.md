---
paths:
  - "sdks/rust/**"
  - "sidecar/**"
  - "protocol/WASM-ABI.md"
  - "protocol/**/wasm*"
---

# WebAssembly modules and the Rust crate

## A service can be a WebAssembly module the runtime loads

The sidecar image has a second mode (feature 016). Given `ANKKA_WASM_MODULE`, it loads that module
into its own JVM through Chicory's compiler, discovers what the module declares from its
`ankka1_discover` export, and hosts it with the same remote hosts a process gets: `sidecar/wasm` is
a second `Conversation` (`WasmConversation`), and nothing in `runtime` changes. The ABI is
`protocol/WASM-ABI.md` — `ankka1_`-prefixed exports, one `ankka1` import module, the protocol's own
messages across linear memory, and the envelopes in `wasm.proto`. The runtime holds each instance's
encoded state (`HeldState`) in both guest shapes, so a trapped guest instance is discarded and
replaced and loses nothing; commands run on a pool of reused instances (`CommandPool`, a stateful
component pinned to one by its key) and anything that may wait on a fresh instance per call
(`BlockingPool`), so a step blocked in an import never holds an instance a command needs. The
`config` import answers the descriptor's variables and withholds the platform's own — the read-time
version of the split the operator makes for a process. On the platform a wasm service is one
container, the runtime's image, with the module copied into an `emptyDir` by the service's own image
run as an init container. `sdks/rust` is the first guest library, the crate `ankka`. An autonomous
agent in a module is the runtime's loop as for a process; the module answers only its tools,
guardrails and `ankka1_check_task_result`, on fresh instances — which is why a Rust rule takes a
`&Context`: a fresh instance remembers nothing between checks. Notifications are a stream, so a
module cannot forward them.

## Three imports ask the runtime for what a module cannot have: another service, the time, random bytes

Feature 030, protocol 1.10, which changed no message. `request` carries 025's `ServiceRequest` and
`ServiceReply` and is `ClientLogic.request`, the call a process makes over gRPC, so the certificate, the
directory, the headers, the bounds, the span and the count are the service client's and none is written
again. `now` answers `HostImports.clock`, the clock `ankka.now` is stamped from; `random` fills a buffer
the guest owns from one `SecureRandom` that nothing seeds, 65,536 bytes a call at most.

**Where `request` may be called is the runtime's rule, and it is a list of exports.** A call into a
module holds a `CallSite` — the export, the component, the handler, the metadata the host sent, the
instance — in a thread-local for as long as it lasts (`GuestInstance.call`, which has no form without
one), because an import is handed only Chicory's instance and the imports are built once for all of them.
`request` proceeds from `CallSite.Permitted` (a step, a consumer, a timed action, a plan, a tool, a
guardrail, a result check, a route) and throws `ImportRefused` from everything else, which is a trap like
any other: the instance is discarded, `HeldState` is untouched, the caller is answered `Internal` naming
the import and the handler. It is not the pool that decides: a view runs on the blocking pool and is
refused. It fails closed twice — an export the host gains is refused until it is listed, and a thread on
which no call site was set refuses every call rather than allowing every call. The host overwrites the
request's metadata with the call site's, so attribution and the trace do not depend on the guest library,
and it refuses a `request` from a call it has already abandoned, so a consumer past its deadline cannot
go on calling other services beside its own redelivery.

`WasmImportsSuite` holds all of it with guests written as WebAssembly text in the suite, which link no
library, against a stand-in for the client (`ServiceCalls`, the one-method trait `ClientLogic` extends):
no cargo, no Docker, and it iterates `ModuleLoader.Exports`, so a new export fails it until it is put in
one list or the other. The crate's `Context::services()` answering `None` in an entity is the earlier
refusal with the better message; `Services::with_metadata` is public, a command can build a client round
its context, and that is the case the runtime's rule exists for (`service_calls.rs`, `ask-in-command`).

## Traps

- **`await_end` has a link block of its own** (`call_await`), so a module that never waits does not import
  it; `WasmHostSuite` checks the plain example does not and the conformance reference does.
- **A Rust import referenced from a shared `match` is imported by every module.** `abi::imports::call`
  dispatches every import in one function, so all of them are in any module that makes one call — a new
  import there would make every module built with the new crate need a runtime that provides it. The
  secret imports go through `call_secret`, a function of their own, and a module that never keeps a
  secret imports none of them (checked with `wasm-objdump -j Import`).
- **`ankka.now` was not on every call.** `WASM-ABI.md` said the host set it on every request with
  metadata, and `WasmConversation` stamped a command, a view, a consumer, a timed action, a plan and a
  route, and not a step, a tool call, a guardrail check or a result check — so `ctx.now()` in a Rust step
  panicked in a module, and nothing had asked. `WasmImportsSuite` drives every entry point of the
  conversation with a guest that traps unless its request holds the entry, and a second guest needing an
  entry no call carries, which is what shows the first one's check runs at all.
- **Every reference declares the same components to the conformance suite**, by exact comparison
  (`discovery.lists-every-component`). What only the Rust reference needs — an entity, a consumer and a
  workflow that call another service — is registered only when the module's `config` answers
  `ANKKA_CONFORMANCE_CALLS`, which the host suite and the cluster suite set and the conformance run does
  not. A route is not a component, so `POST /conformance/service-call` is always there.
- **Every cargo build of the example writes one file**, `shopping_cart.wasm`, whatever its features. A
  suite that needs both the example and the conformance reference copies each out after its build, or the
  second build replaces the first's module under a test that has not loaded it yet.
- **A module that reads the time needs a runtime that offers `now`.** Imports are resolved when a module
  is loaded, so `Context::now()` reading the import means a module built with the crate from 1.10 on is
  refused at start by an earlier runtime, naming `now` — the plain example included, whose notifier reads
  the time. No fallback inside `now()` can soften that: the fallback would still link the import.
- **The module loader keeps its own list of the imports it admits** (`ModuleLoader.Imports`), apart from
  what `HostImports.values` provides. Adding an import to one only fails at a module's start, naming the
  import "the runtime does not provide". `WasmHostSuite` now holds the two equal.
- **A `val` that lists functions defined below it lists nulls.** `HostImports.values` was an eager
  `val` naming the `log` import declared after it, so the `ImportValues` held `null` for `log` and
  Chicory failed building any instance of a module that imported it — with a
  `NullPointerException` in `mapHostImports` that names nothing of ankka's. The spike guest never
  imported `log`, so every host test passed until the first real crate module arrived. It is a
  `lazy val`.
- **`export` is a keyword in Scala 3**, and the ABI is all exports. A parameter or a helper named
  `export` is a syntax error that scalafmt reports before the compiler does; the host says
  `function` and `fn`.
- **cargo reads `.cargo/config.toml` from the directory it is run in, upward — not from the package
  it builds.** The Rust example's config sets its target; run from `sdks/rust`, `cargo build -p
  shopping-cart --release` ignores it and builds for the host. Every build of a module from the
  workspace says `--target wasm32-unknown-unknown`, and the stack size a module needs lives in the
  *workspace's* `.cargo/config.toml`, where both directories see it.
- **A bind mount of a file that does not exist yet makes Docker create a directory in its place.**
  Starting the `wasm` compose profile before `cargo module` had built the module left a directory
  named `….wasm` where the module goes, and the next build failed `Operation not permitted`; OrbStack
  then kept that path's directory view even after the file existed. Both compose files mount the
  module with `bind.create_host_path: false`, so a missing module is refused (or, on OrbStack, the
  runtime refuses `no module at /module/service.wasm`) and nothing is created on the host.
