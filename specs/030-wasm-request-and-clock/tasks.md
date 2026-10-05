# Tasks: WebAssembly Request and Clock — Three Imports a Module Lacks

**Input**: Design documents from `/specs/030-wasm-request-and-clock/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" claims each become a test
before the code that relies on them. Where a task says "case", it means a `test(...)` in the named
suite (or a `#[test]` in the crate). A case that proves a scenario of `features/` is named with
that scenario's name, so the two can be found from each other.

**⚠️ Nothing below is started until 025-polyglot-service-client is on `main`.** That is the
user's decision, recorded in the spec's clarifications. Phase 1 is the check that it is, and the
reading of what it built.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (a module calls another service as itself), US2 (a command cannot wait on the
  network), US3 (a module has the time and a source of randomness), US4 (the imports are
  documented and the Rust crate carries them)

Paths are repository-relative. Abbreviations: `SC`/`SCT` =
`sidecar/src/{main,test}/scala/com/thinkmorestupidless/ankka/sidecar`; `W` = `SC/wasm`; `CONF` =
`SCT/conformance`; `CR` = `sdks/rust/ankka/src`; `CRT` = `sdks/rust/ankka/tests`; `EX` =
`sdks/rust/examples/shopping-cart/src`; `FEAT` = `features/wasm`; `DOCS` = `docs`. "R*n*" is a
section of `research.md`; a contract is named by its file under `contracts/`. `N` is the protocol
version this feature lands at, settled in T002: **1.10**, 025 having landed at 1.8 and, after the rebase, 028 at 1.9.

The branch `030-wasm-request-and-clock` exists, in the worktree
`.claude/worktrees/030-wasm-request-and-clock`, cut from `main` at `d169c990`. The feature files,
the glossary's two proposed terms, the spec and the plan documents are written there.

**As built**: 025 landed on `main` as `9d63f950` and the work below was done on top of it. Where
building it departed from a task's wording, `research.md` R16 says how and why: the reference's
extra components are in `EX/service_calls.rs` and registered only under `ANKKA_CONFORMANCE_CALLS`;
the features are under `features/wasm/`; the test stand-ins are `ScriptedServices::run`,
`with_clock` and `with_random`; the suite is `SCT/wasm/WasmImportsSuite.scala`; and the cluster
case reuses the suite's `rust-cart` with the sample's `/callers/rust-cart-alone`.

The phases are in the order the work depends on, which is not quite the stories' priority order:
User Story 2's rule is built before User Story 1's call, because the import must refuse before it
may proceed. User Story 3 depends on Phase 2 alone and may be built beside either.

---

## Phase 1: Setup — the base, read again

**Purpose**: this plan was written against 025's contract, not its code. Hold the two together
before anything is built on either.

- [X] T001 Confirm 025 is on `main`: `git fetch origin main`, then `git show origin/main:protocol/src/main/protobuf/ankka/protocol/v1/client.proto | grep -n 'rpc Request'` prints a line and `git show origin/main:sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/ClientLogic.scala | grep -n 'def request'` prints another. If either prints nothing, stop here and say so. Otherwise `git rebase origin/main`. The expected conflicts are `GLOSSARY.md` and `.specify/feature.json`: in the glossary keep 025's `### SDK` under its own section and delete this branch's copy under `## Modules`, and keep one `nested` among the everyday words; `feature.json` names `specs/030-wasm-request-and-clock`. Run `just features` and confirm it reports nothing for `features/wasm/`, `features/documentation/modules.feature` or this spec.
- [X] T002 Hold R14's table in `specs/030-wasm-request-and-clock/research.md` against the code, adding a third column, "As built", with the file and the name for each row: (a) the messages' names and fields in `client.proto`; (b) `ClientLogic.request`'s signature, and whether it refuses by `ankka-caller` and records the span and count itself; (c) the timeout's configuration key and where `HttpServiceClients` reads it; (d) the test kit's scripted service: how it is started, scripted and read; (e) the conformance route, the `service.*` cases, where they skip a module target and how a target is told where the scripted service is; (f) in `SCT/SidecarClusterSuite.scala`, the callee's service name, the route that admits by name and the name it admits; (g) the documentation page on calling another service; (h) the protocol version 025 landed at. Set `N` to the minor after (h) and replace `N` throughout `contracts/`. Where a name differs from what `plan.md`, `data-model.md`, `contracts/` or this file assumed, correct it there, in this task, before any code.
- [X] T003 Record the baseline the feature must not disturb: `sbt -Dankka.cluster.tests=off 'sidecar/testOnly *WasmHostSuite *ClientSecretsSuite'` and `cd sdks/rust && cargo test --workspace && ./conformance.sh`. Note for the pull request description which cases exist, that they pass, and that the `service.*` conformance cases report as skipped for the module target in both shapes.

**Checkpoint**: the branch sits on a `main` that has 025; every name this plan takes from 025 has been read in code; the suites this feature changes are known green.

---

## Phase 2: Foundational — the version, and the host knows which export it is running

**Purpose**: the protocol minor every import is dated by, and the call site `request` will read.

**⚠️ CRITICAL**: blocks every user story. It changes no behaviour: nothing reads the call site
until Phase 3.

- [X] T004 [P] Write the version `N` once in each place that states it: `protocol/README.md` (the version line, and a sentence that `N` added three imports for a module and changed no message), `modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/remote/Conversation.scala` (`Version`), `controlplane-api/src/main/scala/com/thinkmorestupidless/ankka/controlplane/api/Compatibility.scala` (the constant and the comment listing what each minor added), and `CR/service.rs` (`PROTOCOL_VERSION`). Refresh the three copies with each SDK's own script (`sdks/python/scripts/proto.py`, `npm run proto` in `sdks/typescript`, `sdks/rust/scripts/proto.sh`); the Python and TypeScript SDKs' own declared versions stay as they are (R12). Run `sbt 'sidecar/testOnly *ProtocolSuite' 'controlPlaneApi/testOnly *CompatibilitySuite'` and whichever other suite pins the number, found with `grep -rn '"1\.' --include='*Suite.scala' sidecar controlplane-api modules/runtime`.
- [X] T005 Create `SCT/WasmImportsSuite.scala` (`munit.FunSuite with LogCapturing`), with the helpers the later phases reuse: `guest(wat: String): LoadedModule`, compiling text as `WasmHostSuite.wat` does and loading it through `ModuleLoader.load`; and `site(export, component = "c", handler = None, metadata = Metadata.empty)(instance)`. Write the probe guest: it exports `memory`, a bump `ankka1_alloc`, a no-op `ankka1_free`, and every other name of `ModuleLoader.Exports` as `(func (param i32 i32) (result i64))` that calls the import `ankka1.log` with level 2 and an empty text and answers `0`. Build its instances with an `ImportValues` of the suite's own whose `log` records `CallSite.current` (the way `WasmHostSuite` substitutes an import at `:346`), since `log` is an import the loader admits. Cases, failing until T007: during an export the recorded site is the one `call` was given; after the call returns `CallSite.current` is `None`; after an export that traps (a second guest whose export is `unreachable`) it is `None`; a thread other than the calling one sees `None` while the export runs; and `CallSite.Permitted.subsetOf(ModuleLoader.Exports)`.
- [X] T006 Create `W/CallSite.scala` per `data-model.md` "The call site": the case class, the thread-local behind `CallSite.current` and `private[wasm] CallSite.within(site)(body)`, `Permitted`, `mayRequest: Either[String, Unit]` with the messages of `contracts/wasm-imports.md` "The trap", and `final class ImportRefused(val importName: String, message: String) extends RuntimeException(message)`.
- [X] T007 In `W/GuestInstance.scala`, change `call` to `call(function: String, request: Array[Byte], site: GuestInstance => CallSite)` (or the shape T005 settled on), running the export inside `CallSite.within`, cleared in a `finally`. There is no overload without a site: a caller that states none would be a caller the rule cannot see. Update every caller: `W/WasmConversation.scala` (`ask`, `close`), `W/WasmDiscovery.scala`, `SCT/WasmHostSuite.scala`, `SCT/WasmHostSpike.scala`, and `CONF/ConformanceTarget.scala` where it calls an instance directly. T005 passes.
- [X] T008 In `W/WasmConversation.scala`, give every `ask` its call site: `handle` the component and `cmd.name`; `fold` the component; `run_step` the component and the step; `close` the component; `view`, `consumer` and `timed_action` the component (and the handler's name where the request carries one); `plan` the component; `invoke_tool` the component and the tool; `check_guardrail` the component and the guardrail; `check_task_result` the component and the task type; `http` the method and path. Each carries the `Metadata` that call sends the guest. Add to `WasmImportsSuite` one case per entry point of `WasmConversation` against the probe guest, asserting the export, component and handler recorded.
- [X] T009 Run `sbt -Dankka.cluster.tests=off 'sidecar/testOnly *WasmImportsSuite *WasmHostSuite'` and `cd sdks/rust && ./conformance.sh`: green, with the same cases as T003's baseline.

**Checkpoint**: every call into a module states what it is; nothing behaves differently.

---

## Phase 3: User Story 2 — A command cannot wait on the network (Priority: P1)

**Goal**: `request` exists, and the runtime refuses it from every export but the permitted eight,
before anything is sent, whatever the module was built with.

**Independent Test**: `sbt 'sidecar/testOnly *WasmImportsSuite'` — for every export of the ABI, a
hand-written guest's `request` either reaches the stand-in for the client or traps with nothing
recorded; and in `WasmHostSuite`'s end-to-end case a Rust entity's command that calls out is
answered with a fault and the next command finds the entity's state.

### Tests first

- [X] T010 [US2] In `SCT/WasmImportsSuite.scala` add the stand-in `RecordingCalls`, implementing the seam of T015 (a function from 025's request message to a `Future` of its reply), which records each request and answers what the case scripted; and the forwarding guest, built per case by `forwarding(request)`: like the probe guest, except that every export calls `ankka1.request` on the bytes of `request`, held in a data segment, and answers the import's packed reply as its own. Cases, failing until T015: (a) "every export that is not permitted traps before anything is sent" — for each name in `ModuleLoader.Exports -- CallSite.Permitted` less `alloc` and `free`, `call` answers `Left`, the fault's message holds `request` and the export's own words from `contracts/wasm-imports.md`, the instance is broken, and `RecordingCalls` recorded nothing; (b) "every permitted export proceeds" — for each name in `CallSite.Permitted`, `call` answers the scripted reply's bytes and one request was recorded; (c) the two sets together are every export but `alloc` and `free`, so an export added to the ABI fails here until it is put in one; (d) the export called through Chicory directly, with no call site set, throws `ImportRefused`; (e) "the platform stops a command's call whatever the module was built with", which is (a) for `ankka1_handle`, named for the scenario; (f) "a call the runtime has abandoned makes no further request" — a guest whose `ankka1_consumer` calls `request` twice, a stand-in whose answer to the first marks the instance broken before answering: `call` answers `Left` naming the abandoned call and exactly one request was recorded.
- [X] T011 [US2] In `SCT/WasmImportsSuite.scala`, the same forwarding guest through a `WasmConversation` (with `shapeOf` answering `Shape.Stateless`), cases named for the scenarios of `FEAT/handlers-that-may-not-call.feature` and failing until T015: "a command in a module that calls another service fails before anything is sent", once each for an event sourced entity, a key value entity and a workflow — `command` answers `Left(ProcessFailure)` with `ErrorCode.Internal`, a message holding `request`, the component and the command's name, and nothing recorded; "an event sourced entity in a module that calls another service while reading its events fails" — `event(1, …)` then `command`: the same, with `reads an event` in the message, and a second command fails the same way (the event was put back); "a view in a module that calls another service fails the event it was reading" — `handleView` fails with a `CommandError` holding `from the view`, nothing recorded.
- [X] T012 [US2] In `SCT/WasmHostSuite.scala`, in the Rust reference's end-to-end case (which skips without cargo, as now), add "an entity whose command failed by calling another service keeps its state": `ask` twice to one `service-asks` entity through the reference's routes, then `ask-in-command`, answered with a fault whose message holds `request` and `ask-in-command`; then a third `ask`, answered ok, and the entity's state read back holds all three asks. The scripted service of T002(d) recorded no request from the command. Fails until T014 and T016 (the route does not exist).

### Implementation

- [X] T013 [P] [US2] In `CR/abi/imports.rs`: add `Import::Request`; `pub fn call_request(request: &[u8]) -> Vec<u8>` over an `extern` block of its own that names `request` alone, commented as the secret store's block is; make the shared `call` panic for `Import::Request` as it does for the secret variants. Natively, `call_request` goes to the installed `NativeHost`'s `call` with `Import::Request`, and with none answers 025's reply with `error(UNAVAILABLE)` and the existing `NO_RUNTIME` text. Add a `#[test]` in `CRT/services.rs` (new) that a native host receives the request's bytes and its answer comes back.
- [X] T014 [US2] In `EX/conformance.rs`, under the `conformance` feature, add `service-asks` per `contracts/rust-api.md` "The example": the entity with `ask`, `answer` and `ask-in-command` (which calls `ankka::abi::imports::call_request` with a request for the service its payload names, and so never returns in a module), and the routes that send each command and read the state. Register it where the reference registers its components. `cargo build -p shopping-cart --release --target wasm32-unknown-unknown --features conformance` from `sdks/rust`.
- [X] T015 [US2] The import. In `SC/ClientLogic.scala` add the seam `trait ServiceCalls` with the one method 025's `request` already is, and have `ClientLogic` extend it. In `W/ModuleLoader.scala` add `request` to `Imports`. In `W/HostImports.scala`: a constructor parameter `serviceTimeout: FiniteDuration`; `bind` keeps the client as the `ServiceCalls` too, and `private[sidecar] def bindCalls(calls: ServiceCalls)` sets that alone, for the suite; `request` added to `values`, which (1) reads `CallSite.current`, and throws `ImportRefused("request", …)` when there is none or `mayRequest` is `Left`, before reading the request's bytes, (2) answers 025's reply with `error(UNAVAILABLE)` when nothing is bound, (3) parses the request, replaces its metadata with the call site's, and awaits the call for `serviceTimeout`. In `SC/Main.scala` pass the client's timeout plus its connect timeout plus a second (R5), and update the other constructions of `HostImports` (`CONF/ConformanceTarget.scala`, `SCT/WasmHostSuite.scala`, `SCT/ClientSecretsSuite.scala`, `SCT/WasmHostSpike.scala`).
- [X] T016 [US2] Run `sbt -Dankka.cluster.tests=off 'sidecar/testOnly *WasmImportsSuite *WasmHostSuite'`. Read the fault's message in T010(a)'s output: if Chicory wrapped the exception and the message is its own, make `GuestInstance.call` read the cause chain for an `ImportRefused` (R2, verify first). Then break the rule once to see it fail: add `ankka1_handle` to `CallSite.Permitted`, run again, confirm T010(a), T010(e), T011 and T012 go red with a recorded request, and restore it.

**Checkpoint**: the import refuses where it must and proceeds where it may, proven with guests
that link no library; a Rust entity that calls out loses nothing.

---

## Phase 4: User Story 1 — A module calls another service as itself (Priority: P1) 🎯 MVP

**Goal**: a handler of a module that may wait calls another service through the crate, is
admitted by name, and is given the answer, the refusal or the failure as 025 gives a process.

**Independent Test**: `cd sdks/rust && ANKKA_CONFORMANCE_ONLY='*service.*' ./conformance.sh` runs
025's cases against the module, none skipped; `WasmHostSuite`'s end-to-end case shows a consumer's
call in the scripted service's record and its answer back in the module.

### Tests first

- [X] T017 [P] [US1] In `SCT/WasmImportsSuite.scala`, with the forwarding guest and `RecordingCalls`, cases named for the scenarios of `FEAT/calling-services.feature` and failing until T015 is complete for them (most pass once it is; each is run red first by scripting the stand-in before the import exists): "the answer of the service called reaches a module's handler as the service made it" — a scripted `response` with a status, a content type, two headers and a body comes back byte for byte; "a refusal by the service called reaches a module's handler as that refusal" — a 403 `response` comes back as that, and the instance is not broken; "a module's call to a service that cannot be found fails, naming the service, and is not sent" — a scripted `failure(UNRESOLVABLE)` comes back as that, the instance not broken; "a module calls another service from every handler that may wait" — T010(b), named for the scenario; the host writes the metadata — the guest's request carries a forged `ankka-caller` and no trace, the call site's metadata carries a caller, a trace id and a span id, and the recorded request holds the call site's three and not the forged one; before `bind`, the reply is `error(UNAVAILABLE)`; a stand-in that never answers and a `serviceTimeout` of 200 ms: `call` answers `Left` holding `request`; "a module's handler that waits for another service longer than the platform waits for the handler fails" — through `BlockingPool.withFresh` with a 200 ms deadline and a stand-in that answers after a second: `Left` holding `no reply from the module within`.
- [X] T018 [P] [US1] In `CRT/services.rs`, natively, failing until T021 and T022: each typed helper sends the method, path, JSON body and content type it should, read from `ScriptedServices::requests()`; `request` answers a `ServiceResponse` for a 200 and for a 403; `get` answers `CallFailed` with the status and body for a 403; each of 025's three failure reasons becomes the error of its name, holding the service; a reply's `error` becomes `Refused` with the code; a reply with no case set is `Refused` with `Internal`; a body of 4,000,001 bytes is `Refused` naming the limit with nothing recorded; `with_headers` reaches the request; `service_in` sets the project and `target()`; a call to an unscripted service panics naming it (`#[should_panic(expected = …)]`); and `Context::services()` is `Some` for an endpoint, a step, a consumer, a timed action and an agent's context and `None` for an entity's, a view's and a workflow command's, one assertion per kind.
- [X] T019 [US1] In `CONF`, remove the module target's skip from 025's `service.*` cases (T002(e)) and make the module target's runtime know the scripted service as a process target's does. Run `cd sdks/rust && ANKKA_CONFORMANCE_ONLY='*service.*' ./conformance.sh`: the cases now run and fail, the reference having no such route. Read the count of cases run in both shapes; it must not be zero.
- [X] T020 [US1] In `SCT/WasmHostSuite.scala`'s end-to-end case, with the test kit's scripted service started and named to the service as `wallet` (T002(d)), failing until T023: a consumer's call — `ask {service: "wallet", path: "/internal/credits"}` to a `service-asks` entity, then, retried until the entity's state holds an answer, the answer is the status and body the scripted service was told to give, and the scripted service recorded exactly one request with the method, path and body the relay sends; "a call made from a module's step is nested under the step in the trace" — the reference's workflow whose step calls `wallet` is run, and in the service's recorded spans the call's span has the step's span id as its parent.

### Implementation

- [X] T021 [US1] Create `CR/services.rs` per `contracts/rust-api.md` "The client": `Services`, `ServiceClient`, `RequestOptions`, `ServiceResponse`, `ServiceError` with `std::error::Error` and `From<ServiceError> for CommandError`, all over `abi::imports::call_request`, the request built from 025's generated message and carrying the context's metadata. In `CR/context.rs` add the `services` flag beside `secrets`, `with_services()`, and `services()`. Set it wherever `with_secrets()` is called today, found with `grep -rn 'with_secrets' sdks/rust/ankka/src`. Export the types from `CR/lib.rs` and `CR/prelude.rs`. Module documentation in the manner of `CR/secrets.rs`'s, saying where it is offered and that a module that never calls another service imports nothing for it.
- [X] T022 [US1] In `CR/testkit/unit.rs` add `ScriptedServices` per `contracts/rust-api.md` "Natively, and in a test", a `NativeHost` for `Import::Request` that composes with the kit's existing host for component calls. T018 passes: `cd sdks/rust && cargo test -p ankka`.
- [X] T023 [US1] In `EX/conformance.rs`, under the feature: the route `POST /conformance/service-call` as 025's conformance contract states it (T002(e)), answering the JSON record of what the client returned or raised; `service-relay`, the consumer over `service-asks`'s events, whose call is the region `// docs:start service-call` … `// docs:end service-call`; and a workflow with one step that calls the service its input names. Register them. `cargo clippy --workspace --all-targets --features ankka/testkit -- -D warnings`.
- [X] T024 [US1] Run `cd sdks/rust && ./conformance.sh` (both shapes; every `service.*` case run and green, the count read) and `sbt -Dankka.cluster.tests=off 'sidecar/testOnly *WasmHostSuite'` (T020 green). Then `wasm-objdump -j Import -x sdks/rust/target/wasm32-unknown-unknown/release/shopping_cart.wasm | grep -c request` is `1` for the build with the feature, and `0` for `cargo build -p shopping-cart --release --target wasm32-unknown-unknown` without it (R9).

### A real cluster

- [X] T025 [US1] SC-001. In `samples/shopping-cart/src/main/scala/shoppingcart/api/CallersEndpoint.scala`, beside the route 025 added that admits one service by name (T002(f)) and outside the `docs` markers, add `withAcl(Acl.allowCallers(Callers.service("rewards"))) { get("/rewards-alone")(() => s"admitted: ${describe(caller)}") }`, with cases for it in the sample's `CallersSuite` as 025's route has. In `SCT/SidecarClusterSuite.scala`, deploy the Rust reference's image a second time as the service `rewards` in the suite's project, wait for `Ready`, and add "a module's consumer is admitted by name by a route that admits only its service": `InPod.curl` to the `rewards` pod sends `ask {service: <the callee>, path: "/callers/rewards-alone"}` to a `service-asks` entity; retried until the state holds an answer, it is 200 and names the `rewards` service and the project; and `InPod.curl` from the suite's first wasm service's pod straight to the callee's `/callers/rewards-alone` is 403. Run `caffeinate -i sbt 'sidecar/testOnly *SidecarClusterSuite'`. Break it once: admit `Callers.service("somebody-else")`, rebuild, confirm the recorded answer is a 403, restore.

**Checkpoint**: a module calls another service from a consumer, a step and a route, proven
against a scripted service, against 025's conformance cases and by name on a cluster.

---

## Phase 5: User Story 3 — A module has the time and a source of randomness (Priority: P2)

**Goal**: `now` and `random` are imports, offered from every export; `Context::now()` reads the
import; a module built before the feature still reads the time.

**Independent Test**: `sbt 'sidecar/testOnly *WasmImportsSuite'` — a hand-written guest reads the
value the test fixed the clock to, from every export, and two sixteen-byte fills differ and
replace what was there. Depends on Phase 2 only.

### Tests first

- [X] T026 [P] [US3] In `SCT/WasmImportsSuite.scala`, a guest whose every export calls `ankka1.now` and answers the eight bytes of the result, and a guest whose `ankka1_handle` pre-fills two sixteen-byte buffers with `0xAA` and `0xBB`, calls `ankka1.random` on each and answers the thirty-two bytes. Cases named for the scenarios of `FEAT/time-and-random-bytes.feature`, failing until T030: "a module reads the time from every handler" — with `HostImports`'s clock fixed to a value no machine's clock has, every export of `ModuleLoader.Exports` less `alloc` and `free` answers it, `handle` and `discover` among them; "the time a module's step is told is the platform's, read while the step runs" — with the real clock, the value from `ankka1_run_step` lies between two readings the case takes either side of the call; "a module is given random bytes that differ each time it asks" — neither half holds its constant and the halves differ; `random` with a length of 65,537, of -1, and of a buffer running past the end of memory each trap with a message holding `random`, and a length of 0 is answered; "a module that asks the platform for something it does not offer does not start" — a guest importing `ankka1.teleport` is refused by `ModuleLoader.load` with a problem naming `teleport`. `WasmHostSuite`'s existing "the imports a module may name are exactly the ones the runtime provides" stays as the guard on the two lists.
- [X] T027 [P] [US3] In `SCT/WasmImportsSuite.scala`, "a module built before a module could ask for the time still reads the time" (R7, verify first): a guest whose every export scans its request for the bytes `ankka.now` and is `unreachable` when they are absent, driven through every entry point of `WasmConversation` that sends metadata — a command, a step, a view, a consumer, a timed action, a plan, a tool, a guardrail, a result check and a route. No answer is a fault holding `the module failed in`. Before T030 the step, the tool, the guardrail and the result check fail; if they pass, R7's reading was wrong: say so in `research.md` and keep the case.
- [X] T028 [P] [US3] In `CRT/` (a new `clock.rs`, or beside the context's tests), natively, failing until T031 and T032: with `fixed_clock(t)`, a context whose metadata says `ankka.now` is some other time answers `t` from `now()`; without it, `now()` lies between two readings of `SystemTime`; with `fixed_random(bytes)`, `ctx.random` fills a buffer with them, repeating; without it, two sixteen-byte fills differ; a 100,000-byte fill is filled throughout.
- [X] T029 [US3] In `SCT/WasmHostSuite.scala`'s end-to-end case, failing until T033: the reference's step that reads `ctx.now()` answers a time between two readings the case takes of the service's clock; a command that reads it is answered ok; the command that fills two buffers answers two that differ.

### Implementation

- [X] T030 [US3] In `W/HostImports.scala`: a constructor parameter `clock: () => Long = () => System.currentTimeMillis()`; one `java.security.SecureRandom`; `now` (no parameters, one `i64`) and `random` (two `i32`, no result) added to `values`, `random` refusing with an exception naming the import for a length below 0 or above 65,536 or a buffer outside memory (R8). In `W/ModuleLoader.scala` add `now` and `random` to `Imports`. In `W/WasmConversation.scala`, drop the `now` parameter in favour of `imports.clock`, and stamp `ankka.now` on the metadata of `runStep`, `invokeTool`, `checkGuardrail` and `checkTaskResult` (R7). Update the constructions the dropped parameter breaks. T026 and T027 pass.
- [X] T031 [P] [US3] In `CR/abi/imports.rs`: `pub fn now() -> i64` and `pub fn random(buf: &mut [u8])`, each over an `extern` block of its own, `random` asking for at most 65,536 bytes a call; `NativeHost` gains `now` and `random` with the machine's clock and `getrandom` as defaults, and the native `now()`/`random()` use the installed host's or those defaults. In `sdks/rust/ankka/Cargo.toml` add `getrandom` under `[target.'cfg(not(target_arch = "wasm32"))'.dependencies]`. Confirm `cargo tree -p ankka --target wasm32-unknown-unknown | grep -c getrandom` is `0`.
- [X] T032 [US3] In `CR/context.rs`, make `now()` read `abi::imports::now()` and no metadata, with the doc comment saying it is the time it is read, and add `random(&self, buf: &mut [u8])`. In `CR/codec/time.rs` replace the "No clock" note. In `CR/testkit/unit.rs` add `fixed_clock` and `fixed_random`. T028 passes.
- [X] T033 [US3] In `EX/conformance.rs`, under the feature: a workflow step, a command of `service-asks` and a route that each answer `ctx.now()`, and a command that answers two sixteen-byte fills. `cd sdks/rust && cargo test --workspace && ./conformance.sh`, and `sbt -Dankka.cluster.tests=off 'sidecar/testOnly *WasmImportsSuite *WasmHostSuite'`: T029 green, and the two prebuilt spike guests' cases unchanged. Then `wasm-objdump -j Import -x` of the example built without the feature lists `now` and neither `request` nor `random`.

**Checkpoint**: a module reads the time and random bytes from anywhere; an older module is handed
`ankka.now` on every call, the four that lacked it included.

---

## Phase 6: User Story 4 — The imports are documented (Priority: P3)

**Goal**: the three scenarios of `features/documentation/modules.feature` hold of the pages.

**Independent Test**: `just docs-sync && just docs && just features`, and the three scenarios read
against the pages they name.

- [X] T034 [P] [US4] `protocol/WASM-ABI.md` per `contracts/wasm-imports.md`: the three rows in the imports table; the section "Where `request` may be called" with its table and the abandoned call; the trap's messages under "Faults and refusals"; the paragraph on metadata now saying a module reads the time through `now`, that `ankka.now` is still set on every request through `N` and the minor after it, and that it is the present under replay; the note beside `random` that an id made in a command goes in the event. Then `sdks/rust/scripts/proto.sh`, so the crate's copy matches.
- [X] T035 [P] [US4] `DOCS/reference/wasm-abi.md`: the same facts, as a page that stands alone (no feature numbers, no "above"); and `DOCS/reference/sidecar-protocol.md` where it lists what each protocol version added, by `just docs-sync` if that block is generated.
- [X] T036 [P] [US4] `DOCS/reference/rust-sdk.md`: a section on calling another service, with `<!-- include: sdks/rust/examples/shopping-cart/src/conformance.rs#service-call -->`, the errors, where `services()` is `None`, and the test kit's `ScriptedServices`; the time (`ctx.now()` reads the runtime's clock when it is called; natively the machine's clock; `fixed_clock`); random bytes; the sentence near line 42 that says a module has no clock; and that a module which reads the time needs a runtime that speaks `N`. `just docs-sync`.
- [X] T037 [P] [US4] `DOCS/reference/limitations.md`: under "A module cannot be interrupted", that a call to another service is not ended before the service answers or `ankka.service-client.timeout` passes, that a handler whose own deadline is shorter is answered with a fault first, and which setting to lower; nothing says a module cannot read the time. `DOCS/concepts/polyglot.md` near line 113: a module reaches nothing but the runtime, which now gives it the time, random bytes and calls to other services. The page on calling another service (T002(g)): a module among the doors, and the handlers it may call from. Then `grep -rn -i 'no clock' docs sdks/rust/ankka/src cli/src/main/templates/rust` and correct what is left.
- [X] T038 [US4] `just docs` and `just features`, both green. Read each scenario of `features/documentation/modules.feature` against the page it names and note in the pull request description the sentence that satisfies each step. In `CLAUDE.md`, in "A service can be a WebAssembly module the runtime loads", add the three imports, the call site and why the rule is a list of exports; and under the traps, whatever T016 and T027 found.

**Checkpoint**: the pages say what the runtime does.

---

## Phase 7: The whole build

- [X] T039 `sbt scalafmtCheckAll compile` (warning-free), then `caffeinate -i sbt -Dankka.cluster.tests=off test`; `cd sdks/rust && cargo fmt --all --check && cargo clippy --workspace --all-targets --features ankka/testkit -- -D warnings && cargo test --workspace && cargo test -p shopping-cart --features slow && ./conformance.sh`; `cd sdks/python && uv run pytest -q` and `cd sdks/typescript && npm test`, for the refreshed copies; `python3 .github/ci-coverage.py`.
- [X] T040 `caffeinate -i sbt 'sidecar/testOnly *SidecarClusterSuite'` with cluster tests on, awake throughout; read every case's duration before believing a failure.
- [ ] T041 In `specs/030-wasm-request-and-clock/spec.md` set the status; in this file tick what is done; list in the pull request description each scenario of `features/wasm/` beside the case named for it and the suite it is in, the four "verify first" claims with what each turned out to be, and the checks that were broken once to watch them fail (T016, T025).

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1** depends on 025 being on `main`, and blocks everything.
- **Phase 2** blocks every story.
- **Phase 3 (US2)** depends on Phase 2.
- **Phase 4 (US1)** depends on Phase 3: its import is Phase 3's, and its reference module's
  entity is T014's.
- **Phase 5 (US3)** depends on Phase 2 only, and touches `HostImports.scala`, `ModuleLoader.scala`
  and `imports.rs` as Phases 3 and 4 do: beside them it is a second branch to merge, not a
  parallel edit of one tree.
- **Phase 6 (US4)** depends on Phases 3 to 5: it documents what they built.
- **Phase 7** is last.

### Within a story

Tests are written and seen to fail before the code that makes them pass. In the crate, the
import's plumbing (`imports.rs`) before what calls it. The reference module before the suites that
build it.

### What is released together

Phases 3 and 4 are one pull request: an import that refuses in the right places is not on `main`
without the call it guards. Phase 5 may merge alone, before or after. The version of T004 is
written once, whichever merges first.

### Parallel opportunities

- T004 beside T005 to T008.
- T013 beside T010 to T012; T017 beside T018.
- T026, T027 and T028 together; T031 beside T030.
- T034 to T037 together.

### Parallel example: User Story 3

```text
T026  WasmImportsSuite: the clock from every export, the fills, the bounds
T027  WasmImportsSuite: ankka.now on every entry point
T028  the crate, natively: fixed_clock, fixed_random, the defaults
```

---

## Implementation Strategy

### MVP

Phases 1 to 4: a module calls another service from the handlers that may wait and is stopped in
the ones that may not. That is the capability the spec exists for, and it cannot be had in two
halves.

### Incremental delivery

1. Phases 1 and 2: the base read again, the version, the call site. Nothing behaves differently.
2. Phases 3 and 4, one pull request: `request`, its rule, the crate's client, conformance, the
   cluster.
3. Phase 5: the time and random bytes; `Context::now()` changes meaning here, and the SDK
   reference says so in the same pull request (T036's part on the time moves with it).
4. Phase 6, then Phase 7.

---

## Notes

- A case that needs cargo skips without it, as `WasmHostSuite`'s end-to-end case does today, and
  CI's `sdk-rust` job is where it runs. Everything the runtime's own rule rests on is in
  `WasmImportsSuite`, which needs neither cargo nor Docker and runs in the Scala job.
- `given` and `export` are keywords in Scala 3: the suite's helpers say `function`, `site`,
  `guest`.
- Every `cargo build` of a module from `sdks/rust` says `--target wasm32-unknown-unknown`.
- A negative assertion ("nothing was sent") is read from the stand-in's or the scripted service's
  own record, never from the absence of an error.
- munit's `--` filter matches the full name, suite included: `'*service.*'`, with the leading
  wildcard, or the run is green having run nothing.
