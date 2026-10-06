# Feature Specification: WebAssembly Request and Clock — Three Imports a Module Lacks

**Feature Branch**: `030-wasm-request-and-clock`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "A WebAssembly module gets three more imports from the runtime.
`request` makes an outbound call to another service through the runtime's service client, with the
service's certificate, taking the same request shape the sidecar protocol gains for process-hosted
services. It is permitted where the host already runs the module on the blocking pool, a workflow
step, a consumer, a timed action, an agent tool, a guardrail, a result check and an HTTP route, and
trapped from an entity command or event handler, where a blocking call would hold a pinned
instance; the refusal is the runtime's, by call site, not the guest library's. `now` answers the
runtime's clock and `random` fills a buffer. The Rust crate exposes all three. Out of scope:
interrupting a module that runs past its timeout, streaming, sockets, WASI, and any import that
reaches the file system."

## Context

A module reaches nothing but the runtime. The `ankka1` import module that `HostImports` in
`sidecar/wasm` builds on Chicory offers `invoke`, `send`, `invoke_stream`, `query`, `schedule`,
`cancel`, `config` and `log`, and every one but `log` delegates to `ClientLogic`, the code the
gRPC `Client` service runs for a process. No WASI is wired, so a module has no clock, no
randomness and no sockets. The Rust crate's `Context::now()` reads the `ankka.now` metadata the
runtime sets on every call and panics in a module when it is absent; its `Instant`, `Duration`,
`LocalDate` and `LocalDateTime` in `codec/time.rs` carry the note "No clock". A module that needs
an id generates none.

That was the right first shape: it made the module's reach a short list, and it is why a trapped
instance is discarded and replaced with nothing lost. It stops at the first service that must
call another. The domain plan's wallet, called over HTTP by every other service, has no way to be
called from a module; a consumer in a module that must tell another service what happened cannot;
and every TL capability that eitheror's function packs relied on was an HTTP call or a time
comparison, so a function pack as a module needs exactly these three imports and nothing else.

How a module is hosted decides where an outbound call may be made. Commands to a stateful
component run on `CommandPool`, where an instance is pinned to its key and reused, and a step or
anything else that may wait runs on `BlockingPool`, a fresh instance per call, so a step blocked in
an import never holds an instance a command needs. An outbound call is a wait of unknown length.
Made from a pinned instance, it holds every command to that key behind it, and because a module
cannot be interrupted, the hold lasts until the callee answers. Made from the blocking pool, it
holds nothing anyone else needs.

Four decisions shape this feature.

- **`request` is the service client, not a socket.** It takes the `Request` message that
  025-polyglot-service-client adds to the sidecar protocol, a target service by name and optional
  project, method, path, headers and body, and answers with status, headers and body. The
  runtime serves it with `HttpServiceClients`, so the certificate, the directory lookup and the
  identity check are the ones every Scala service already uses, and a module arrives at a callee
  as `ankka://<project>/<service>` exactly as a process does.
- **Where it may be called is the runtime's rule.** The host knows which export it is calling and
  on which pool. `request` from an export the host runs on the blocking pool proceeds; from a
  command or event handler export on the command pool it traps, the instance is discarded and
  replaced, and the caller is answered with a fault naming the import and the call site. A guest
  library may refuse earlier for a better message, but the runtime does not trust it to.
- **`now` and `random` are imports, not metadata.** `now` answers the runtime's clock in epoch
  milliseconds; `random` fills a buffer of the guest's choosing. Metadata on every call was the
  right place for the time when there was no import module to put it in; it is a copy on every
  message that an import makes unnecessary, and `random` has no metadata shape at all. The
  `ankka.now` metadata stays for one protocol minor version so a crate built before this feature
  still reads it.
- **No WASI.** The three imports are the whole of what a service component needs; a file system
  and sockets are what makes a module able to reach past the runtime, and a module's value is that
  it cannot.

What this feature is not: it is not a way to interrupt a module, so a `request` that never
returns still holds its blocking-pool instance until the callee answers or the client's own
timeout fires; it is not streaming; and it is not a route for a module to accept a connection.

## Clarifications

### Session 2026-10-04

- Q: Should `request` carry a timeout of its own, below the component call's? → A: No. It is the
  message a process sends, which 025's contract gives no timeout: the wait is the service's
  `ankka.service-client.timeout`, thirty seconds unless the service sets it. That setting is
  therefore also the longest an abandoned call holds its instance, whatever the deadline of the
  handler that made it.
- Q: Should `random` be seeded so a test can make it deterministic? → A: Never. The runtime always
  answers from a secure source and has no setting that fixes it. A native Rust test never reaches
  the import; the crate's test kit lets it fix the bytes there.
- Q: 025 has not landed, and the message and the client call this feature is built on exist only
  in its contract. In what order is the work done? → A: The plan and the tasks are written now;
  nothing is implemented until 025 is on `main` and this branch is rebased onto it.
- Q (raised by reading the host): a view's handler runs on the blocking pool, and FR-005 says it
  traps. Is the rule the pool or the export? → A: The export. The pool is why the permitted
  exports are safe, and it is not the test: `request` proceeds from `run_step`, `consumer`,
  `timed_action`, `invoke_tool`, `check_guardrail`, `check_task_result`, `http` and `plan`, and
  traps from every other export, so an export added later traps until it is listed.
- Q (raised by reading the host): an agent's `plan` export is in neither FR-004 nor FR-005. → A:
  Permitted. It runs on a fresh instance, and 025 lets a process's agent handler make the call.
- Q (raised by reading 025): which protocol version carries the imports? → A: The one after
  025's. `main` reached 1.7 with replayable topic sources, so 025 lands at a later version than
  its contract says, and this feature takes the next.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A module calls another service as itself (Priority: P1)

A developer writing a consumer in Rust publishes nothing to a topic; instead, for each event, the
consumer calls the wallet service's internal route. The call goes through `request`; the wallet's
endpoint admits the consumer's service by name and refuses a request from anywhere else. A
workflow step in the same module calls a payment gateway's route the same way, and an agent tool
reads a balance.

**Why this priority**: This is the capability; without it a module cannot take part in a system
of services at all.

**Independent Test**: In `WasmHostSuite`, a module built from the Rust example whose consumer
calls a scripted target through `request`: assert the request the target saw and the reply the
consumer received. In the k3s suite, a module calls a Scala service whose route admits it by name.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/wasm/calling-services.feature`: a module's consumer is admitted by name by a route that admits only its service
- added `features/wasm/calling-services.feature`: the answer of the service called reaches a module's handler as the service made it
- added `features/wasm/calling-services.feature`: a refusal by the service called reaches a module's handler as that refusal
- added `features/wasm/calling-services.feature`: a module's call to a service that cannot be found fails, naming the service, and is not sent
- added `features/wasm/calling-services.feature`: a module calls another service from every handler that may wait
- added `features/wasm/calling-services.feature`: a call made from a module's step is nested under the step in the trace
- added `features/wasm/calling-services.feature`: a module's handler that waits for another service longer than the platform waits for the handler fails

---

### User Story 2 - A command cannot wait on the network (Priority: P1)

A developer, by mistake, calls `request` from an entity's command handler. The call traps, the
instance is discarded and replaced, the command is answered with a fault that names the import
and says a command handler may not make one, and the next command to the same key succeeds on a
fresh instance with its state intact.

**Why this priority**: It is the rule that keeps the first story safe; without it one mistaken
handler stalls every command to its key for as long as a callee takes to answer.

**Independent Test**: In `WasmHostSuite`, a module whose entity command calls `request`: send the
command, assert the fault and its message, then send a second command and assert it succeeds and
the state is what the first commands left.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/wasm/handlers-that-may-not-call.feature`: a command in a module that calls another service fails before anything is sent
- added `features/wasm/handlers-that-may-not-call.feature`: an entity whose command failed by calling another service keeps its state
- added `features/wasm/handlers-that-may-not-call.feature`: an event sourced entity in a module that calls another service while reading its events fails
- added `features/wasm/handlers-that-may-not-call.feature`: a view in a module that calls another service fails the event it was reading
- added `features/wasm/handlers-that-may-not-call.feature`: the platform stops a command's call whatever the module was built with

---

### User Story 3 - A module has the time and a source of randomness (Priority: P2)

A developer's step compares a deadline with the current time through `Context::now()`, which now
reads the `now` import; a command generates an id for a new record from `random`. A module built
with the previous crate, which read the time from metadata, still runs.

**Why this priority**: Time comparisons were the second thing every TL function did, and a record
that needs a fresh id has had no honest way to make one.

**Independent Test**: In `WasmHostSuite`, a module that reads `now` from a step and from a
command and `random` from a command: assert `now` lies within the call's start and end on the
runtime's clock, and that two `random` fills differ.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/wasm/time-and-random-bytes.feature`: the time a module's step is told is the platform's, read while the step runs
- added `features/wasm/time-and-random-bytes.feature`: a module reads the time from every handler
- added `features/wasm/time-and-random-bytes.feature`: a module is given random bytes that differ each time it asks
- added `features/wasm/time-and-random-bytes.feature`: a module built before a module could ask for the time still reads the time
- changed `features/wasm/loading.feature`: a module the platform cannot load is refused, saying why

---

### User Story 4 - The imports are documented and the Rust crate carries them (Priority: P3)

A developer reads the WebAssembly ABI reference and finds the three imports with their call-site
rule; the Rust SDK reference shows `request`, `now` and `random` on the context; the limitations
page no longer says a module has no clock, and still says one cannot be interrupted.

**Why this priority**: The feature is usable without it by someone reading `WASM-ABI.md`, and by
nobody else.

**Independent Test**: The documentation build passes; the Rust example's consumer uses `request`
and is an included sample; `cargo test` in the workspace runs the native fallbacks.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/documentation/modules.feature`: the documentation lists what a module may ask the platform for and which handlers may call another service
- added `features/documentation/modules.feature`: the documentation of the Rust SDK says where a module's time comes from
- added `features/documentation/modules.feature`: the documentation's limitations say a module cannot be interrupted and not that it cannot read the time

---

### Edge Cases

- **A `request` from a tool while the agent loop's session context is gone.** Tools run after
  the handler has returned, as every tool does; the import needs nothing from the session.
- **A `request` whose body exceeds the module's memory.** The reply is written into linear memory
  as every reply is; a reply that does not fit traps with the memory error, and the instance is
  discarded.
- **A `request` to the module's own service.** Resolves and is admitted as `Callers.Self`
  would admit it; nothing special.
- **`request` and a redirect.** Not followed, as the Scala client does not follow one; the 3xx is
  the reply.
- **A callee that never answers.** The instance is held until the service client's own timeout,
  `ankka.service-client.timeout`, and no longer; `request` carries none of its own.
- **Two modules in one pod.** There is one module per service; the question does not arise.
- **`now` under replay.** The time is the runtime's clock at the call, never a recorded one; an
  event handler that needs the time of an event reads it from the event, as the designing guide
  already says.
- **`random` for a persisted id in a command.** The id must be in the event, or a replay produces
  a different one; the guide's rule, restated beside the import.

## Requirements *(mandatory)*

### Functional Requirements

**Imports**

- **FR-001**: The `ankka1` import module MUST offer `request`, taking the protocol's `Request`
  message and answering its reply, served through the runtime's service client with the
  service's certificate.
- **FR-002**: The `ankka1` import module MUST offer `now`, answering the runtime's clock in epoch
  milliseconds, and `random`, filling a guest buffer of a given length with random bytes from a
  secure source that no setting seeds.
- **FR-003**: The runtime MUST keep setting `ankka.now` metadata on every call for at least one
  protocol minor version after this feature, so a module built before it still reads the time.

**Call sites**

- **FR-004**: `request` MUST proceed from the exports that run a workflow step, a consumer, a
  timed action, an agent's handler, an agent tool, a guardrail, a result check and an HTTP route,
  each of which the host runs on the blocking pool.
- **FR-005**: `request` MUST trap from every other export, which is every export the host runs on
  the command pool or as a fold: an entity command handler, an event handler, a key value
  entity's command handler, a workflow command handler and a view handler, the last although it
  runs on the blocking pool; the trap MUST happen before any request is made. The rule is a list
  of permitted exports, so an export the host gains later traps until it is added.
- **FR-006**: The trap's fault MUST name the import and the handler, the trapped instance MUST be
  discarded and replaced, and the next call to the same key MUST succeed with the state the
  component had.
- **FR-007**: The rule in FR-004 and FR-005 MUST be enforced by the runtime from the export it
  called, independently of anything the guest library checks.
- **FR-008**: `now` and `random` MUST be permitted from every export.

**Behaviour**

- **FR-009**: A callee's refusal MUST reach the guest as the status and body the callee sent; a
  target that cannot be resolved MUST be answered with an error, never a hang.
- **FR-010**: A `request` made during a call that runs past the component's timeout MUST be
  handled as any over-long module call is: the caller answered with a fault, the instance
  discarded on return. `request` carries no timeout of its own: it waits the service client's
  `ankka.service-client.timeout` and is answered as unanswered when that passes.
- **FR-011**: A `request` MUST be recorded as a span under the handler that made it.

**Rust crate and documentation**

- **FR-012**: The Rust crate MUST expose `request`, `now` and `random` on the context, with
  `Context::now()` reading the import in a module and the system clock natively.
- **FR-013**: `protocol/WASM-ABI.md`, the ABI reference, the Rust SDK reference and the
  limitations page MUST describe the imports and the call-site rule, and the Rust example MUST
  use `request` in an included sample.

### Key Entities

- **Request**: the protocol's outbound call: target service and optional project, method, path,
  headers, body; its reply is status, headers and body.
- **Call site**: the export the host called and the pool it ran on, which decides whether
  `request` may proceed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A Rust consumer's `request` is admitted by a Scala service's caller list that names
  the module's service, in the k3s suite.
- **SC-002**: A `request` from an entity command traps before any request is made, and the next
  command to the key succeeds with the entity's state, in `WasmHostSuite`.
- **SC-003**: `now` read from a step lies within the call's recorded start and end, and two
  `random` fills differ, in `WasmHostSuite`.
- **SC-004**: A module built against the previous crate loads and runs unchanged.
- **SC-005**: The documentation build passes with every new sample included from tested code.

## Assumptions

- 025-polyglot-service-client lands first, so its `ServiceRequest` and `ServiceReply` exist in
  the protocol and `ClientLogic.request` already reaches `HttpServiceClients`. Nothing here is
  implemented before it is on `main`.
- The host can tell, at the moment an import is called, which export it is executing and on
  which pool; `WasmConversation` already chooses the pool per export.
- Chicory's import mechanism resolves imports by name only when a module names them, so adding
  three changes nothing for a module that uses none.
- The `.cargo/config.toml` rule holds: every build of a module from the workspace says
  `--target wasm32-unknown-unknown`.

## Dependencies

- 025-polyglot-service-client, for the `Request` message and reply shape.
- Needed only if TL stays as a sold feature in the domain plan; otherwise it has no stage to gate
  and is scheduled when a Rust service needs it.

## Open Questions

- When `ankka.now` metadata is withdrawn, is that a protocol minor or major change for a module?
