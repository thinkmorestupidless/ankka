# Research: WebAssembly Request and Clock

Decisions for [plan.md](plan.md), each with what in the repository it rests on. File references
are to this branch's base (`d169c990`) unless they say 025, in which case they are to
`specs/025-polyglot-service-client/contracts/` on the branch `025-polyglot-service-client` at
`29c42db1`: a contract, not yet code. "Verify first" marks a claim read from code or a dependency
and not yet run; the task that touches it starts with a test that would show it false.

## R1. The rule is a list of permitted exports, read from a call site the host sets on the thread

**Decision**: `GuestInstance.call` takes a `CallSite` — the export, the component, the handler's
name where there is one, the metadata the host sent, and the instance — and holds it in a
thread-local for the length of the export call. `request` reads it and proceeds only when the
export is one of `CallSite.Permitted`: `run_step`, `consumer`, `timed_action`, `plan`,
`invoke_tool`, `check_guardrail`, `check_task_result`, `http`. Any other export, and no call site
at all, traps. `now` and `random` do not read it.

**Rationale**: The imports are built once and shared by every instance (`HostImports.values`,
`HostImports.scala:48`), and a host function is handed only Chicory's `Instance`, so the import
cannot ask the `GuestInstance` what it is running. What it can rely on is the thread: "every import
runs on the thread that called the export" (`HostImports.scala:19`), because Chicory runs a call
synchronously, and `Deadline.run` gives each call a virtual thread of its own
(`InstancePools.scala:26`). The spec's first statement of the rule was the pool, and the pool is
not the test: a view's handler runs on the blocking pool (`WasmConversation.scala:279-284`) and
must still trap. A list of permitted exports fails closed in the two ways that matter. An export
the host gains later traps until someone adds it, and a thread on which nothing was set — the
general hazard of a thread-local, which this repository has met twice — refuses every call rather
than permitting every call, so the first permitted-path test shows it.

`plan` is permitted: it runs on a fresh instance (`WasmConversation.scala:302-307`) and 025 lets a
process's agent handler make the call ("any other declared handler", `protocol.md`, "Who is
calling").

**Alternatives considered**: Imports built per instance, each closing over its own call site —
no thread-local, and an `ImportValues` of fourteen functions built for every fresh instance, on
the path whose cost is the reason fresh instances are affordable. A map from Chicory's `Instance`
to its `GuestInstance` — a global weak map for something the stack already knows. Reading
`ankka-caller` from the request's metadata, as 025 does for a process — that is the guest's word,
which FR-007 says the runtime does not take.

**Verify first**: that an import called from an export runs on the thread that called
`GuestInstance.call`, under the compiled machine `ModuleLoader` builds. The permitted-path case of
`WasmImportsSuite` is that test: were it false, every `request` would trap.

## R2. The trap is an exception the import throws; what follows is already built

**Decision**: a refused `request` throws `ImportRefused(import, site)` from the host function. It
leaves the export call as any host failure does: `GuestInstance.call` catches it, marks the
instance broken and answers `Left(GuestFault(export, message))` (`GuestInstance.scala:48-52`); the
command pool replaces the instance and forgets what was resident in it
(`InstancePools.scala:119-124`); `WasmConversation` answers the caller `the module failed in
<export>: <message>` as `Internal` and leaves `HeldState` as it was, so the next command is handed
the state again. Nothing is parsed and `ClientLogic` is not reached before the throw.

The message names the import and the handler, and says where the call belongs:

| Export | The message says |
|---|---|
| `handle` | `request may not be called from the command <component>/<handler>` |
| `fold` | `request may not be called while <component> reads an event` |
| `view` | `request may not be called from the view <component>` |
| `close`, `discover`, anything else | `request may not be called from <export>` |

each followed by `: a module calls another service from a workflow step, a consumer, a timed
action, an agent, a tool, a guardrail, a result check or a route`.

**Rationale**: The spec asks for a trap, an instance discarded and replaced, and state kept
(FR-005, FR-006). That is exactly what a guest's own trap gets today, proven by
`WasmHostSuite`'s trap cases, so the feature adds a reason to trap and no new recovery. A fold
that traps puts its unfolded events back (`WasmConversation.scala:150-151`), so an entity whose
event handler calls out fails every command, naming the reason, and loses nothing.

This differs from a process, where 025 answers the same mistake with `Error(BAD_REQUEST)` in the
reply and the handler carries on. A module's instance is pinned and cannot be interrupted, so the
runtime ends the call; the spec's input says so, and it is not reopened here.

**Alternatives considered**: Answering `ServiceReply.error` and letting the guest carry on — the
handler then decides what a refused call means, in the one place a wait must never happen, and a
guest that retries in a loop holds its key for ever.

**Verify first**: that Chicory's compiled machine lets a host function's exception reach the
caller of `export.apply` with its message intact, rather than wrapping it in one whose message is
generic. If it wraps, `GuestInstance.call` reads the cause chain, as the gRPC client's
`UNAVAILABLE` reading does.

## R3. `request` is `ClientLogic.request`, and the host writes the metadata

**Decision**: the import parses 025's `ServiceRequest`, replaces its `metadata` with the call
site's — the metadata the host itself sent with the export call — and awaits 025's
`ClientLogic.request`, answering the `ServiceReply` bytes. Before the service has started it
answers `ServiceReply.error(UNAVAILABLE)`, as `invoke` does (`HostImports.scala:70-74`), though no
permitted export runs before then.

**Rationale**: 025 designed the message for this ("a refusal or a fault is in the reply, never a
gRPC status, so that a module's import can carry it the same way", `protocol.md`). Everything the
spec asks of the call — the certificate, the directory, the identity check, a callee's refusal
arriving as its status and body, an unresolvable name answered and not hung (FR-001, FR-009) — is
`HttpServiceClients` behind that one function, and so are the span and the count (FR-011): 025's
`ClientLogic.request` "runs … inside the caller and the trace the metadata names". For a process
the metadata is the SDK's to forward, because the sidecar cannot know which handler is asking. For
a module the host knows, so it does not ask: a module built without the crate is still traced
under its step and counted from its handler, and a module cannot attribute its call to a handler
that did not make it.

`invoke`, `query` and the rest go on trusting the guest's metadata, as they do today. Making them
read the call site is the same change and is not this feature's.

**Alternatives considered**: Passing the guest's metadata through — FR-011 then holds only for a
module built with the crate, and 025's rule that an entity's handler is refused by metadata would
be the only check a bare module could dodge by sending none (R1 closes that anyway).

## R4. A call the runtime has abandoned makes no further request

**Decision**: `request` traps when the call site's instance is already marked broken.

**Rationale**: `Deadline.run` answers the caller with a fault when a call outlasts its deadline,
marks the instance broken and leaves the thread running, because a guest cannot be interrupted
(`InstancePools.scala:14-37`). Until now what such a thread could still do was call a component.
With `request` it can call another service: a consumer whose first call took eleven seconds has
already been answered with a fault and will be delivered its change again, and the abandoned
thread would go on to make its second call beside the redelivery's. The check costs one volatile
read. It does not interrupt anything: the call in flight still runs to the client's timeout
(spec, "What this feature is not"); it stops the next one.

**Alternatives considered**: Leaving it, as for `invoke` — a component call is at least inside
the service; a call to another service is an effect somebody else keeps. Applying the check to
every import — right, and a change to what five existing imports do for an abandoned call; it
belongs with the change that makes them read the call site (R3).

## R5. `request` waits as long as the service client does; a handler's deadline is what it was

**Decision**: the import awaits `ClientLogic.request` for `ankka.service-client.timeout` plus the
client's five-second connect timeout plus one second, read once from the configuration `Main`
already holds. No deadline of any export changes.

**Rationale**: Clarified: `request` carries no timeout of its own. 025's client answers
`UNANSWERED` in the reply when its timeout passes (`scala-api.md`, "Timeout"), so the import's own
wait exists only so that a client that failed to answer at all is a trap and not a thread parked
for ever; it must be longer than the client's or it would turn every slow callee into a trap.

The deadlines of the exports are `ankka.ask-timeout` (ten seconds, `reference.conf:22`) for a
consumer, a timed action, a plan, a guardrail and a result check, the body timeout for a route,
and ten minutes for a step and a tool (`WasmConversation.scala:79`). So with the defaults a step's
or a tool's slow call ends as *unanswered* at thirty seconds and the handler decides, while a
consumer's ends as an abandoned call at ten: the consumer is answered with a fault, the change is
delivered again, and the first attempt's thread is released within thirty seconds of starting
(and makes no further request, R4). That is the spec's "handled as any over-long module call is"
(FR-010), it is what a process's consumer gets from the same two settings, and the limitations
page says which setting to lower.

**Alternatives considered**: Raising the consumer's deadline to the client's timeout — it would
change how long a stuck module holds a projection for every module, to suit the ones that call
out.

## R6. `now` is an import of no arguments, and the time moves during a call

**Decision**: `now() -> i64` answers epoch milliseconds from one clock, `HostImports.clock`, which
`WasmConversation` also stamps `ankka.now` from. It reads no call site and is offered from every
export, `discover` included.

**Rationale**: FR-002, FR-008. `WasmConversation` already takes the clock as a parameter for its
tests (`WasmConversation.scala:69`); moving it to `HostImports` gives the import and the metadata
one source, so a test that fixes the clock fixes both. The import takes nothing from linear
memory, so it cannot fault.

One thing changes for a developer and the SDK reference says it: `Context::now()` was the time the
runtime made the call, the same however often it was read; it becomes the time it is read. Two
reads in one handler can differ, and a command that puts the time in an event should read it once.

## R7. `ankka.now` is not on every call today, and FR-003 needs it to be

**Decision**: `WasmConversation` stamps `ankka.now` on the four requests that carry metadata
unstamped: `run_step`, `invoke_tool`, `check_guardrail` and `check_task_result`.

**Rationale**: `WASM-ABI.md` says the host sets it "on every request that carries `Metadata`"
(`:65`), and the crate's `Context::now()` panics in a module when it is absent
(`context.rs:187`). `stamped` is applied to a command, a view, a consumer, a timed action, a plan
and a route (`WasmConversation.scala:164, 280, 287, 295, 303, 337`) and not to a step
(`:233`), a tool (`:317`), a guardrail (`:331`) or a result check (`:363`). Read as written, a
step in a module that asks for the time traps today. After this feature a module built with the
new crate no longer reads the entry, but FR-003 keeps it for a module built with the old one, and
"still reads the time" has to be true in a step as well.

**Verify first**: that a step's `ctx.now()` traps on this base. If the entry reaches a step by a
path this reading missed, the stamp is redundant and harmless, and the case that asserts the entry
on every export stays as the guard.

## R8. `random` fills a buffer the guest owns, from a secure source, 65,536 bytes at most

**Decision**: `random(ptr: i32, len: i32)` writes `len` bytes from one `java.security.SecureRandom`
at `ptr` and answers nothing. A length below zero or above 65,536, or a buffer that does not lie
within the instance's memory, traps naming the import. No setting seeds it. It reads no call site.

**Rationale**: FR-002, FR-008; clarified as never seeded. The guest allocates, so the host calls
no export and the import cannot re-enter the module. The bound is the Web Crypto API's quota for
the same call, which every `getrandom`-shaped guest library already chunks for; without one the
host allocates whatever a guest names, up to its memory limit. A trap on a bad buffer is the
existing rule for a call that leaves memory in doubt.

**Alternatives considered**: Answering bytes the guest then frees, in the shape of the other
imports — an allocation and a call back into the guest to deliver sixteen bytes.

## R9. Each new import is linked only by the code that calls it

**Decision**: in `abi/imports.rs`, `request`, `now` and `random` are three `extern` blocks of
their own, reached only through `call_request`, `now()` and `random()`; none is named in the
shared `call`. `ModuleLoader.Imports` gains the three names; `WasmHostSuite` already holds that
set equal to what `HostImports.values` provides.

**Rationale**: A module imports a function only if something it links calls it, and an import
named in the shared `match` is imported by every module (the secret store's lesson,
`imports.rs:57-63`). So a module that calls no other service, reads no time and asks for no
random bytes imports nothing new and runs on a runtime from before this feature.

A module that does read the time through the new crate imports `now`, and a runtime that predates
the import refuses it at load, naming it (`ModuleLoader`, "the runtime does not provide"). That is
the platform's usual order — the runtime is upgraded before what runs on it — stated in the SDK
reference with the version. It cannot be softened: imports are resolved when the module is
loaded, so a fallback to metadata inside `now()` would still link the import.

**Verify first**: `wasm-objdump -j Import` on the example built without and with a use of each:
the three names appear only with their callers.

## R10. The crate's client is 025's SDK contract in Rust, withheld where secrets are

**Decision**: [contracts/rust-api.md](contracts/rust-api.md). `Context::services()` answers
`Option<Services>`: `Some` where `Context::secrets()` is `Some` today, plus a view's exclusion
kept. `Services::service(name)` and `service_in(project, name)` give a `ServiceClient` with `get`,
`get_text`, `post`, `put`, `delete` and the raw `request`, synchronous like every call the crate
makes. Errors are `ServiceError`'s five cases, named as 025 names them.

**Rationale**: 025's `sdk-apis.md` ends "Rust gains nothing … until the import is added"; this is
that addition, and a Rust developer should find the names a Python or TypeScript one does. The
crate already scopes the secret store with one flag set by the component kinds that may have it
(`context.rs:125-142`), which are the kinds FR-004 permits; the same flag's meaning, widened, is
the guest library's earlier refusal with the better message that the spec allows. The runtime's
rule does not lean on it: `abi::imports::call_request` is public, a command handler can call it,
and that is the module "built without the crate's check" the fifth scenario of
`handlers-that-may-not-call.feature` needs.

## R11. Natively the crate reads the machine's clock and the system's randomness, and a test fixes both

**Decision**: outside `wasm32`, `now()` is `SystemTime::now()` and `random()` is `getrandom`,
added as a dependency for `cfg(not(target_arch = "wasm32"))` only. `NativeHost` gains `now` and
`random` with those as defaults, and `request` reaches it through `call_request`; the unit test
kit gains `ScriptedServices`, `fixed_clock` and `fixed_random` built on it.

**Rationale**: FR-012. Rust's standard library has no source of randomness, and a module's
dependency graph must not gain one: the target-specific table keeps `getrandom` out of every
`wasm32` build, which the packaging step's `cargo build --target wasm32-unknown-unknown` of the
crate alone already proves on every CI run. The runtime is never seeded (clarified); a native test
never reaches the runtime, so the test kit is where determinism lives.

## R12. The protocol takes a minor of its own, and no message changes

**Decision**: the protocol version becomes the minor after 025's (`main` is at 1.7 with 024, so
025 lands above what its contract says, and this is the next). `protocol/README.md`,
`Conversation.Version`, `Compatibility` and the crate's `PROTOCOL_VERSION` say it. No `.proto`
file changes: `request` carries 025's two messages, and `now` and `random` carry none.

**Rationale**: Imports have always been dated in protocol minors (`WASM-ABI.md`: "since 1.6"), and
the spec dates the withdrawal of `ankka.now` the same way. Without a minor, "`request` since
025's version" would be false of any runtime released between the two features. The README is
part of each SDK's copy of `protocol/`, so the Python and TypeScript copies are refreshed; neither
SDK's own declared version has to move, since an SDK older than its runtime is the supported
direction.

## R13. The host's rule is tested with hand-written guests, the crate with the reference module

**Decision**: three levels.

- `WasmImportsSuite` (new, `sidecar`, no cargo): guests written as text and compiled by the wabt
  the build already carries (`WasmHostSuite.wat`, `:74`). One guest exports every ABI function,
  each calling `request` with a canned `ServiceRequest` and answering its reply; another calls
  `now` and `random`. Driven through `GuestInstance.call` with a call site per export, against a
  stand-in for the client that records what it is asked: every permitted export answers the
  stand-in's reply, every other traps before the stand-in is called, and the list of exports is
  read from `ModuleLoader`'s own, so a new export is in the table without the test being edited.
  This is also the module "built without an SDK". The stand-in needs a seam: `ClientLogic` is a
  final class over a running service, so the import calls a one-method trait that `ClientLogic`
  extends, and the suite starts no service and no container.
- The Rust reference module, where cargo is: 025's `service.*` conformance cases with the module
  target's skip removed; and in `WasmHostSuite`'s end-to-end case, a consumer's call, the trap
  from an entity command reached through the public `abi::imports`, the state after it, a step's
  time between two readings of the clock, and two differing fills.
- `SidecarClusterSuite`: the wasm service calls the route 025's cluster case made, admitted by
  name (SC-001).

**Rationale**: Each scenario of `features/wasm/` ends as a case named for it. The host's rule
is the part that must not depend on the guest (FR-007), so it is tested with guests that have no
library; those need no toolchain and run in the Scala job, where a cargo-dependent case would
skip. SC-004, a module built before the feature, is held three ways that do not need a megabyte
of old binary in the tree: the two prebuilt spike guests in `sidecar/src/test/resources/wasm/`
still load and pass; a case asserts `ankka.now` on the metadata of every export that carries any
(R7); and the reference reads the entry directly, which is all the old crate's `now()` did.

**Checks that could pass while false, and what is used instead**: a rule that refused every
export would pass every test of the refusal, so the permitted exports are shown to proceed in the
same suite, and the two lists together must be the whole ABI. "No request reached the service" is
read from the stand-in's or the scripted service's own record, not from the absence of an error. "The
next command succeeds with its state" asserts the state's contents, since a fresh empty state also
succeeds. "Two fills differ" is on sixteen bytes; a fill that wrote nothing would leave two equal
buffers of the guest's initial bytes, so the guest pre-fills each with a different constant and
the case also asserts neither kept it. "The span is under the step" asserts the span's parent id.

## R14. What is taken from 025, to be read again after the rebase

The plan names these as 025's contract gives them. The first task after 025 is on `main` reads
each against the code and corrects this plan's names where they moved.

| Taken from 025 | Used for |
|---|---|
| `ServiceRequest`, `ServiceReply`, `ServiceFailure` in `client.proto`, and the protocol version it lands at | the import's messages; R12 |
| `ClientLogic.request` over `AnkkaService.services`, its refusal by `ankka-caller`, its span and count | R3 |
| `ankka.service-client.timeout` (on the 025 branch already, `reference.conf`) | R5 |
| `ScriptedService` (`testkit`), `ankka.local-services` | the loopback callee of R13 |
| `POST /conformance/service-call` and the `service.*` cases, skipped for a module | R13 |
| the cluster case's callee route that admits by name | SC-001 |
| the documentation's page on calling another service | the module's door, R15 |
| `GLOSSARY.md`: `SDK`, and `nested` among the everyday words | this branch proposes the same; one copy survives the rebase |

### As built

025 landed on `main` as `9d63f950`, and every name held.

| Taken from 025 | As built |
|---|---|
| the messages | `ServiceRequest`, `ServiceReply`, `ServiceFailure` in `client.proto`, as the contract gave them |
| the client call | `ClientLogic.request(ServiceRequest): Future[ServiceReply]`; it refuses by `ankka-caller` and records the span and count itself, and refuses a declared protocol below 1.8, so the crate's `PROTOCOL_VERSION` had to move for a module to call at all |
| the timeout | `HttpServiceClients.TimeoutKey`, `ankka.service-client.timeout`; `Settings.serviceClientTimeout` reads it for the import's wait |
| the scripted service | `testkit`'s `ScriptedService.start()`, with `answer`, `failNext`, `delay`, `requests`, `clear`; every `ConformanceTarget` has one as `scripted`, and the module target was already told where it is |
| the conformance cases | eight `service.*` cases behind `onlyWhereServiceCalls()`, which is gone; they pass against the module in both shapes |
| the cluster callee | the Scala sample as `carts`, whose `/callers/orders-alone` admits `orders`; a route beside it admits the module's service |
| the documentation page | `docs/build/calling-services.md` |
| the version | 1.8; feature 028 then took 1.9 on `main`, so this feature is 1.10 |
| the glossary | `SDK` and `guardrail` were settled on `main`; this branch proposes `random bytes` and `interrupted` |

## R15. The documentation says it where a module's reach is already described

**Decision**: `protocol/WASM-ABI.md` and `docs/reference/wasm-abi.md` gain the three rows, a
table of which exports may call `request`, and the changed sentence about the time;
`docs/reference/rust-sdk.md` gains calling another service, the time and random bytes, with the
example's consumer as an included sample; `docs/reference/limitations.md` keeps "a module cannot
be interrupted", adds what that means for a call to another service and which setting bounds it,
and loses nothing else; `docs/concepts/polyglot.md` stops saying a module has no clock; 025's
page on calling another service gains the module. No page is added.

**Rationale**: FR-013, and the three scenarios of `features/documentation/modules.feature`. The
replay rules are restated beside the imports because they are where a developer will break them:
the time in an event handler comes from the event, and an id made from `random` in a command goes
into the event.

## R16. What building it changed

The four claims marked "verify first", as they turned out:

- **R1, the thread.** An import runs on the thread that called the export: the permitted-path
  cases pass, and a second thread started from inside an import sees no call site.
- **R2, the exception.** Chicory's compiled machine lets a host function's exception reach the
  caller of the export with its message whole. `GuestInstance.call` needed no change to read it.
- **R7, `ankka.now`.** Confirmed: with the stamp taken off a step again, the case that drives
  every entry point of the conversation fails for `ankka1_run_step`, naming it.
- **R9, what links what.** The example's module imports `now` and neither `request` nor `random`;
  the conformance reference imports all three; `getrandom` is in no `wasm32` dependency graph.

And what the plan did not foresee:

- **The reference's component list is pinned.** `discovery.lists-every-component` holds every
  SDK's reference to exactly `ConformanceReference.ComponentIds`, so an entity, a consumer and a
  workflow could not simply be added to Rust's. They are registered only when the module's
  `config` answers `ANKKA_CONFORMANCE_CALLS`, which the host suite's target and the cluster
  suite's descriptor set and the conformance run does not.
- **The cluster case reuses the wasm service the suite already deploys**, `rust-cart`, with the
  sample's new route `/callers/rust-cart-alone`, rather than deploying the reference a second
  time as another service: one more runtime and one more Postgres on a node that already answers
  in seconds.
- **The test stand-ins are scopes, not methods of `NativeHost`.** A test kit installs a
  `NativeHost` of its own for component calls, which would replace one that also answered the
  clock; `ScriptedServices::run`, `with_clock` and `with_random` set thread-locals beside it.
- **`Request::headers()`** was added to the crate's endpoint request: the conformance route sends
  on every `X-Conformance-*` header, and the request offered only one header by name.
- **The living features moved.** `main` gained `features/wasm/` while this was being planned, so
  the three files written under `features/modules/` are there instead, and the scenario about a
  module that asks for something the platform does not offer is a row of the existing outline in
  `features/wasm/loading.feature`.
- **Every build of the example writes one file.** The host suite builds the example and the
  conformance reference, so each is copied out before the next build replaces it.
