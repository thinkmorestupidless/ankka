# Feature Specification: WebAssembly Hosting

**Feature Branch**: `design/wasm-hosting`

**Created**: 2026-09-28

**Status**: Draft

**Input**: User description: "A WebAssembly hosting mode for ankka, from the design treatment at
docs/design/wasm-hosting.md and the spike it records: the sidecar image loads a WebAssembly module
in-process through Chicory instead of speaking gRPC to a developer's process, with a versioned
exported-function ABI over the protocol's own messages, both a stateless and a stateful guest shape
chosen per component, a Rust PDK as the first guest (Go second, out of scope here), a
`hosting: "wasm"` descriptor value rendered by the operator as one container with the module
delivered by an image volume, conformance through the existing suite with a module target, and the
documentation, template and release plumbing a language SDK gets."

## Context

ankka hosts a service written in a language other than Scala through the sidecar: the platform's
runtime runs beside the developer's process and speaks a protocol to it over the pod's loopback. The
sidecar owns everything durable and distributed; the process owns the decisions. Feature 009 built
that model, feature 013 proved a second language could join it without the platform changing, and
between them they wrote down what compatible means: the protocol in `protocol/`, the encoding in
`protocol/ENCODING.md` with its fixtures, and the conformance suite that drives a reference service
from the runtime's side and names each behaviour it checks.

This feature adds a third way for the developer's code to be hosted. Instead of a second process, the
developer's code is a WebAssembly module, and the runtime loads it into its own process and calls it
directly. The runtime still owns everything it owned before; the module still decides what each
command, event, step, row, tool and route does. What changes is that there is no second container,
no hop across the loopback, and no port for the developer's code to listen on. What the module can
touch is exactly what the runtime hands it.

Three things from the design treatment and its spike shape this specification:

- **The boundary is cheap; the guest's codec is not.** Calling into a compiled module and back costs
  microseconds. What a guest pays is decoding and encoding its own values, and that varies by an order
  of magnitude between languages. So the way a module holds its state is chosen per component: a
  *stateless* guest is handed its state with every call and returns the new one, which is simplest and
  right for a fast codec; a *stateful* guest is handed its state once when an instance is loaded and
  keeps it between calls, which is right for a slow one. The runtime holds the encoded state in both
  cases, so a module that crashes loses nothing.
- **A language is a guest library, not a platform change.** The runtime never learns which language
  produced a module. The first guest library is for Rust, whose toolchain and serialisation make it the
  cheapest proof; the second, for Go, is a separate feature that will reuse everything here. The spike's
  Go guest already passes the same correctness case as the Rust one, so the ABI's language neutrality is
  not a hope.
- **The platform's promises hold unchanged.** A WebAssembly-hosted service is deployed with the same
  descriptor, reaches `Ready` the same way, scales, restarts, pauses and is exposed with the same
  commands and status words, and its journal is readable by the same service written in any other
  supported language.

The audience is teams whose services are already in Rust, or who want a language with no runtime of
its own inside a durable, event sourced platform, and who accept a module they cannot attach a
debugger to in exchange for a single container and a call that costs less than a network hop. The
technical shape behind this specification, and the measurements it rests on, are in
`docs/design/wasm-hosting.md`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An entity in Rust, hosted in-process (Priority: P1)

A Rust developer writes an event sourced entity and an HTTP endpoint using the Rust guest library.
They declare the entity's state, events, commands and replies as ordinary Rust types with the
library's derivations, its command and query handlers with their wire names, and how an event folds
into state. They build one WebAssembly module from their crate, start the runtime image with that
module and a database using the one command the documentation gives, and command the entity over
HTTP. Its state survives the runtime restarting, and it lives in the same journal records a Scala,
Python or TypeScript service writes.

**Why this priority**: Until one entity in a module can be discovered, commanded, persisted, recovered
and replayed by the runtime, nothing else in this feature can be tested. It proves the two hard parts,
the ABI across linear memory and the guest's codec against the platform's encoding, before anything is
built on them.

**Independent Test**: Write the shopping cart's entity and endpoint in Rust. Build the module, start
the runtime image with it beside the bundled Postgres, add items over HTTP, restart the runtime, read
the cart back. Run the encoding fixtures through the library's default codec. Run the conformance
suite's discovery and event sourced behaviours against the module.

**Acceptance Scenarios**:

1. **Given** a module built from an entity written against the library and a runtime started with
   it, **When** a command is sent over the endpoint's route, **Then** the handler runs inside the
   module, the events it names are persisted by the runtime, and the reply is computed from the state
   after those events.
2. **Given** an entity with persisted events, **When** the runtime is restarted and the entity is
   commanded again, **Then** the module is given the recovered snapshot and each event after it to
   fold, and the command runs against the recovered state.
3. **Given** a query handler, **When** it is written to return a persisting effect, **Then** the
   crate does not compile, and the error names the rule.
4. **Given** every encoding fixture published with the protocol, **When** the library's default codec
   decodes and re-encodes each one, **Then** every value matches and every byte matches, and a fixture
   the codec cannot handle is a failure, not a skip.
5. **Given** a journal written by the Scala shopping cart, **When** the Rust cart is pointed at the
   same database, **Then** it recovers the same carts with the same state, and the reverse.
6. **Given** a handler that panics, **When** the command runs, **Then** the command fails, nothing is
   persisted, the entity's state is unchanged, and the runtime keeps serving every other entity and
   the next command to this one.
7. **Given** a module with no valid ABI (a missing export, an export prefix the runtime does not
   speak, or a file that is not a module), **When** the runtime starts, **Then** it refuses to start
   and names what was missing or which ABI version was offered and which it speaks.

---

### User Story 2 - Tested without and with the host (Priority: P2)

The developer tests their components with the test runner they already have. A unit testkit runs one
component with no runtime, no database and no WebAssembly at all, natively, in milliseconds, and
still carries every value through the component's own codecs, so a type the codec cannot express
fails there. An integration testkit starts Postgres and the real runtime image with the developer's
module in Docker, and can replace the runtime against the same database so a test proves durability
rather than caching.

**Why this priority**: The developer has given up the debugger for deployed code; the unit testkit,
where their code runs natively under their own debugger, is what they have instead, and the
integration testkit is the proof that what passed natively behaves the same inside the module. Both
are also what the shopping cart example and every later story test themselves with.

**Independent Test**: The shopping cart's unit tests pass with no Docker running and no module built;
its integration tests build the module, start the runtime with it, exercise every route, restart the
runtime and read the state back. Neither testkit depends on which test runner the developer chose.

**Acceptance Scenarios**:

1. **Given** an entity and the unit testkit, **When** a command is sent, **Then** the test sees the
   events, the new state, the retention and the reply or refusal as values, and the state carries
   forward to the next command.
2. **Given** a component whose state contains a value its declared types cannot encode in the
   platform's encoding, **When** a unit test drives it, **Then** the test fails on the encoding, not on
   first deployment.
3. **Given** the integration testkit, **When** a test starts it, **Then** Postgres and the runtime are
   running with the platform's own schema, the developer's module is loaded, and the runtime reports
   ready before the test body runs.
4. **Given** a running integration testkit, **When** the test restarts the runtime, **Then** a new
   runtime loads the same module against the same database and every entity is recovered from the
   journal.
5. **Given** a workflow with a pause, **When** the unit testkit runs it to its end, **Then** the test
   follows the transitions, stops at the pause, and can resume it.

---

### User Story 3 - Every component kind, both guest shapes, proven compatible (Priority: P3)

The developer builds the rest of a service in Rust: key value entities, views, consumers, timed
actions, workflows whose steps call other components through the library's client, and an agent whose
tools and guardrails run inside the module while the runtime runs the loop. They choose, per
component, whether the module holds that component's state between calls or is handed it every time.
A library author runs the platform's conformance suite against the library's reference module and it
passes, in both shapes, so the library is compatible with every runtime that speaks its ABI version.

**Why this priority**: A platform that hosts entities in a module but workflows only elsewhere is not
a hosting mode. The conformance suite is the platform's definition of compatible, and its reference
needs every kind, so this story is where the feature becomes a hosting mode rather than a
demonstration. The two shapes are both here because the suite is the only place a divergence between
them would be caught.

**Independent Test**: The reference module in Rust declares the same components, wire names and
routes as the Scala reference. The conformance suite runs against it, once with every stateful
component declared stateless and once declared stateful, and passes every behaviour both times. Break
one behaviour deliberately and the suite names it.

**Acceptance Scenarios**:

1. **Given** a key value entity, a view, a consumer and a timed action in the module, **When** they
   are driven through the runtime, **Then** each behaves as the conformance suite requires: state is
   updated and read, rows are updated and deleted on the source's events and deletion, messages are
   consumed and produced, and a timed action fires and is retried on failure.
2. **Given** a workflow in the module, **When** it is started, **Then** each step runs inside the
   module, transitions and pauses are honoured, a step that panics is retried and failed over as the
   workflow declared, and a query arriving while a step runs is answered from the state before the
   step without disturbing the step.
3. **Given** a workflow step that calls an entity through the library's client, **When** the call is
   made, **Then** the runtime routes it to the target and the reply is decoded into the type the caller
   named, and while the step waits no other component's command is delayed by it.
4. **Given** an agent declared with one tool and one guardrail, **When** a message is sent to a
   session, **Then** the runtime runs the loop, the tool runs inside the module with the model's
   arguments, the guardrail is consulted, and the session survives the runtime restarting.
5. **Given** a component declared stateful, **When** its instance is loaded, commanded several times,
   passivated and commanded again, **Then** the module is handed the state once per load, the
   commands between see the state the previous command left, and after passivation the module holds
   nothing for that instance.
6. **Given** a component declared stateful whose module crashes mid-command, **When** the next
   command arrives, **Then** the runtime loads a fresh instance from the state it holds, and the
   command runs against the state before the crash.
7. **Given** the conformance suite and the reference module, **When** the suite runs in each shape,
   **Then** every behaviour passes; **When** one behaviour is deliberately broken, **Then** the suite
   names that behaviour and no other.
8. **Given** a module that declares a handler wire name twice, an endpoint without an access rule, or
   a streaming route, **When** discovery runs, **Then** the runtime refuses to start and names every
   problem at once.

---

### User Story 4 - Deployed to the platform as one container (Priority: P4)

The developer packages their module as an image whose only job is to hand over the module, writes a
descriptor that says the image is a WebAssembly module, and deploys it to a local ankka installation.
The service runs as a single container, the platform's own runtime with the module delivered into it
once at start, and reaches `Ready`, scales, restarts, pauses and is exposed exactly as any other service. The
descriptor's environment reaches the module only where the platform allows, and the model's key and
the database credential never do.

**Why this priority**: This is the story that makes the hosting mode a platform feature rather than a
local trick, and the one where the operator's rendering, the descriptor's rules, the readiness probe
and the module's delivery are tested against a real cluster. It is after the conformance story because a
deployed module that is not conformant proves nothing.

**Independent Test**: Build the shopping cart example's module image, apply its descriptor to the
local installation, and command the cart through the platform's gateway. Scale it, restart it, pause
and resume it, expose it. Apply a descriptor whose image hands over no module and read what the
service reports.

**Acceptance Scenarios**:

1. **Given** the shopping cart example's module image and a descriptor naming it as a WebAssembly
   module, **When** applied to the local installation, **Then** the service runs as one container,
   reaches `Ready`, reports the library's name and version in discovery, and the cart is commanded
   through the platform's gateway.
2. **Given** a deployed WebAssembly service, **When** it is scaled, restarted, paused, resumed and
   exposed, **Then** each behaves exactly as for a Scala service, with the same commands and the same
   status words, and a rolling replacement refuses no request.
3. **Given** a descriptor for a WebAssembly module, **When** it sets a variable reserved for the
   runtime, names the runtime's image, or opens a port for the module, **Then** it is refused before
   any resource is written, naming the field.
4. **Given** a descriptor whose variables include a model's key and a database's credentials,
   **When** the service runs, **Then** the runtime holds them and the module can read neither.
5. **Given** an image that hands over no module, or exits with an error doing so, **When** a
   WebAssembly service naming it is applied, **Then** the service's status reports the delivery's
   failure rather than a pod that silently never starts.
6. **Given** a deployed service whose module has a valid ABI but crashes on discovery, **When** it is
   applied, **Then** the service reports `Failed` with the runtime's reason, and a later apply with a
   fixed image recovers it.

---

### User Story 5 - Published, scaffolded and taught (Priority: P5)

A Rust developer adds the guest library to their project from the public crate registry at the version
of the platform they deploy to, or starts a new project with the CLI's initialiser for Rust, which
renders a crate, a compose file that starts the runtime with the built module, a workflow, and the
skills a coding agent uses. They follow the getting-started page from an empty directory to a running
service and read the reference page for the kind they need next. A release of this repository
publishes the library from a tag, and the version the library reports in discovery is the version on
the registry.

**Why this priority**: A library a Rust developer cannot add from the registry does not exist to them,
and the audience is meeting a paradigm the documentation has to teach. It is last because every page
includes samples from code the earlier stories write.

**Independent Test**: In a fresh directory, run the CLI's initialiser for Rust, build the module, start
the compose file, command the entity. Add the library from the registry to another project and build.
Tag a release and find the crate on the registry at that version. Build the documentation with the
new pages.

**Acceptance Scenarios**:

1. **Given** an empty project, **When** the developer adds the library from the registry, **Then** the
   crate builds for the WebAssembly target and declares every dependency it needs.
2. **Given** the CLI's initialiser for Rust, **When** it renders a project, **Then** the project builds
   a module, its compose file starts the runtime of the CLI's own version with that module, and its
   tests and type checks pass, insisting nothing skipped.
3. **Given** a release tag, **When** the release workflow runs, **Then** the crate is published at the
   tag's version, after and only after the platform's own artifacts are, with no credential stored in
   the repository.
4. **Given** the continuous integration workflow, **When** a commit changes the protocol without
   refreshing the library's copy of it, **Then** the build fails on that commit.
5. **Given** the getting-started page for Rust, **When** a developer with a Rust toolchain and Docker
   follows it from an empty directory, **Then** they command an entity through a local runtime with no
   JVM.
6. **Given** the Rust skill and each shared skill, **When** a coding agent is asked for a Rust
   component of any kind, **Then** the skill it loads states the rules that differ from the other
   languages and the code it writes compiles and passes the unit testkit.
7. **Given** the documentation build, **When** a page's included sample drifts from its source or a
   new page is not in the navigation and a skill, **Then** the build fails.

---

### Edge Cases

- A module that never returns from a call (a loop). The runtime does not interrupt a module, so the
  call's instance is abandoned after the command timeout and replaced, at the cost of one carrier
  thread until the process restarts; the runtime's readiness is unaffected while other instances
  serve, the affected command times out for its caller as any slow handler does, and the limitation
  is documented as one the platform does not protect against in this feature.
- A module that exhausts its memory during a call. The call fails as a fault, the instance is
  discarded, the state the runtime holds is untouched, and the next call gets a fresh instance.
- A stateless component whose state grows large. Each call carries the whole state both ways; the
  cost is linear in its size and the documentation says so, and the stateful shape is the answer for
  such a component.
- A component declared stateful in a service with many loaded instances. Each loaded instance holds
  its state in the module's memory until passivation, so memory grows with loaded instances; the
  runtime's passivation applies unchanged, and the stateless shape is the answer for a component with
  very many instances and small state.
- A workflow step or a tool that waits a long time on another component. It holds one instance from
  the pool reserved for such work, and never one an entity command needs; if that pool is exhausted,
  further steps wait for an instance, and a command is never queued behind a step.
- A module built for a newer ABI than the runtime speaks, or an older one the runtime has dropped. The
  runtime refuses at discovery, naming both versions; the platform reports the service as unable to
  start with that reason.
- A module built by a guest library whose protocol version is newer than the runtime's. The existing
  protocol version rule applies: same major, minor no higher than the runtime's.
- An endpoint route declared as streaming. Refused at discovery, naming the route, since a module
  cannot return a stream; an agent's token stream is unaffected because the runtime produces it.
- A module whose discovery answer is invalid (an unknown component kind, a handler with no name). The
  runtime reports every problem at once, as it does for a process.
- The image named by the descriptor hands over no module, hands over two, or exits with an error.
  The delivery fails before the runtime starts, the operator reports the condition it observes, and
  the service's status shows it; the runtime container never sees a missing module as a discovery
  failure.
- A developer builds the module for the wrong target (one that imports facilities the runtime does not
  provide). The runtime refuses at load, naming the first unsatisfied import.
- Two components in one module, one stateless and one stateful. Each is handled in its own shape;
  discovery declares the shape per component.
- The runtime restarts while a stateful instance holds state the journal has not caught up with. It
  cannot: every event is persisted before the reply, and the runtime holds the encoded state after
  each call, so a restart recovers from the journal exactly as for a process-hosted entity.

## Requirements *(mandatory)*

### Functional Requirements

**The host**

- **FR-001**: The runtime image MUST be able to start in a mode where it loads one WebAssembly module
  from a path instead of connecting to a developer's process, chosen by configuration, and MUST host
  every component kind and endpoint the module declares with the same runtime code that hosts a
  process-hosted service.
- **FR-002**: The runtime MUST read discovery from the module, validate it with the same rules as a
  process's discovery, report every problem at once, and refuse to start on any.
- **FR-003**: The runtime MUST compile a module once at start, before discovery, and MUST NOT
  interpret it; a module the compiler cannot compile is a refusal at start naming the reason.
- **FR-004**: A fault inside the module (a panic, a trap, exhausted memory) during any call MUST be
  reported as a fault, never a refusal, MUST leave the state the runtime holds unchanged, MUST discard
  that instance, and MUST NOT affect any other instance or the runtime's readiness.
- **FR-005**: The runtime MUST run calls that may wait on other components (workflow steps, tools,
  guardrails, endpoints, consumers, timed actions) on instances separate from those serving entity
  and workflow commands, so that waiting never delays a command.
- **FR-006**: A call from the module back into the runtime (invoking a component, querying a view,
  scheduling or cancelling a timer, logging) MUST run on the calling thread without holding any
  runtime-wide lock, so that a waiting call occupies only its own instance.
- **FR-007**: The runtime MUST expose nothing to the module beyond the calls this feature defines: no
  filesystem, no network, no clock, no environment, and no variable the platform reserves.

**The ABI**

- **FR-008**: The functions a module exports and the functions it may import MUST be written down as a
  versioned contract in the protocol artifact, carrying the major version in every name so that a
  module built for a version the runtime does not speak is refused by name.
- **FR-009**: Every value crossing the boundary MUST be a message of the protocol, either an existing
  one or one defined by the ABI's own envelope file, which adds messages and changes none, so that
  the ABI adds no shape a guest library has to learn beyond the protocol it already carries.
- **FR-010**: The contract MUST define the memory convention (how the runtime writes a request into the
  module and reads a reply out, and who frees what) so that a guest library in any language can
  implement it from the document alone.
- **FR-011**: The contract MUST define both guest shapes: a stateless guest handed its state with every
  call and returning the new state with every reply, and a stateful guest handed its state once per
  loaded instance and told when the instance is passivated. Discovery MUST declare the shape per
  component.
- **FR-012**: In both shapes the runtime MUST hold each loaded instance's encoded state, so that a
  module fault or replacement loses nothing and the journal is never behind the module.
- **FR-013**: A module MUST NOT be able to declare a streaming endpoint route; discovery MUST refuse
  one naming the route. Agent token streams are produced by the runtime and are unaffected.

**The Rust guest library**

- **FR-014**: The library MUST give developers the component model in Rust's idiom: components
  declared with a stable component id, handlers declared with wire names separate from method names,
  effects as values, and queries that can only return a read-only effect, refused at compile time
  otherwise.
- **FR-015**: The library's default codec MUST produce and accept the platform's encoding exactly, from
  the developer's ordinary Rust types through derivation, and MUST pass every published encoding
  fixture in both directions with no skips.
- **FR-016**: The library MUST implement the ABI contract for every component kind and both guest
  shapes, MUST declare the protocol version and the library's name and version in discovery, and MUST
  let a developer choose a component's shape with a stateless default.
- **FR-017**: The library MUST provide the runtime's client to handlers, tools, steps and endpoints
  through the ABI's imports, with replies decoded into the type the caller names.
- **FR-018**: The library MUST provide a unit testkit that runs one component natively with no runtime
  and no module, exposing effects as values and round-tripping every value through the component's
  codecs, and an integration testkit that builds or takes a module, starts Postgres and the runtime
  image with it in Docker under any test runner, and can restart the runtime against the same database.
- **FR-019**: The library MUST carry its own copy of the protocol artifact and generate from it, and
  continuous integration MUST fail when the copy differs from the original.
- **FR-020**: The library MUST ship with a reference module declaring the same components, wire names
  and routes as the Scala conformance reference, and a shopping cart example with unit and integration
  tests.

**Conformance**

- **FR-021**: The conformance suite MUST accept a module as a target and drive it through the runtime's
  own host, so that a module's compatibility is proven by the same cases as a process's.
- **FR-022**: The suite MUST run the reference module in both guest shapes and pass in both, and MUST
  be able to name a deliberately broken behaviour in either.
- **FR-023**: The conformance suite's cases and the encoding fixtures MUST NOT be altered to make a
  module pass; a case a module cannot pass is a defect in the host or the library. Adding the module
  target to the suite's harness is not altering its cases.

**The descriptor and the operator**

- **FR-024**: The service descriptor MUST accept a hosting value naming a WebAssembly module, and the
  descriptor's validation MUST refuse, naming the field, a WebAssembly descriptor that sets a variable
  reserved for the runtime, names the runtime's image, or declares an HTTP port for the module.
- **FR-025**: The operator MUST render a WebAssembly service as one running container, the runtime
  image, carrying every port, probe, cluster variable and credential a Scala service's container
  carries, with the module delivered into it once at start by the descriptor's image and the module's
  path handed to the runtime. The delivery MUST work on every cluster the platform runs on today, with
  no dependency on the node's container runtime beyond running an image.
- **FR-026**: The module MUST be able to read, through the runtime, only those descriptor variables
  that are not reserved. The model's and the database's variables, the cluster's variables and the
  runtime's own settings MUST never be readable from the module, whether or not they are in the
  runtime container's environment.
- **FR-027**: A WebAssembly service MUST scale, restart, pause, resume, expose and roll with no
  request refused, exactly as any service, with no new command and no new status word.
- **FR-028**: The custom resource's schema MUST declare the new hosting value, and the schema suite
  MUST prove it.
- **FR-029**: A module image that fails to deliver its module MUST be reported in the service's
  status as that failure, and the documentation MUST state the contract a module image satisfies.

**Local development, the template and publishing**

- **FR-030**: The compose file rendered for a Rust project MUST start Postgres and the runtime image
  with the project's built module, at the runtime version matching the library, and the runtime's
  schema MUST come out of the image as it does for the other languages.
- **FR-031**: The CLI's initialiser MUST render a Rust project: a crate depending on the library at the
  CLI's version, a build for the WebAssembly target, the compose file, a workflow, and the rendered
  skills, and the template suite MUST render it through the real command and run the project's own
  checks and tests, insisting nothing skipped.
- **FR-032**: The library MUST be published to the public crate registry from a release tag, after the
  platform's own artifacts, with no publishing credential stored in the repository, and the version in
  the tree MUST be a placeholder rewritten by the release job, as every other published artifact's is.
- **FR-033**: Continuous integration MUST build the library for the WebAssembly target, run its tests,
  the encoding fixtures, the template suite for Rust and the conformance suite against the runtime
  image built from the same commit.

**Documentation**

- **FR-034**: The documentation MUST gain a getting-started page and a reference page for Rust, a
  concept page or section explaining the hosting mode, its two guest shapes and when to choose each,
  and a reference for the ABI contract, with every sample included from tested code.
- **FR-035**: A Rust skill MUST exist beside the Python and TypeScript ones, and every shared skill
  that has language differences sections MUST gain a Rust one.
- **FR-036**: Every page, skill and tool description that names the supported languages MUST name
  Rust, and the contributing page on adding a language MUST describe the guest library route beside
  the SDK route.
- **FR-037**: The limitations page MUST state what the hosting mode does not do: no interruption of a
  running module, no streaming routes from a module, no debugger attachment to a deployed module, and
  no delivery of a module other than by an image that copies it into place.

**What must not change**

- **FR-038**: The protocol's messages, the encoding, the fixtures and the conformance cases MUST NOT
  change. The gRPC path for a process-hosted service MUST behave exactly as before, and the Python and
  TypeScript SDKs MUST be unaffected except where a shared page or skill gains a Rust section.
- **FR-039**: The runtime's hosts (entities, workflows, projections, timers, agents, endpoints) MUST
  NOT gain a WebAssembly-specific branch: the module is reached through the same seam a process is.
- **FR-040**: Every existing test MUST pass unchanged.

### Key Entities

- **Module**: one WebAssembly file holding the developer's whole service, built from their crate,
  loaded by the runtime at start.
- **ABI contract**: the versioned list of functions a module exports and may import, the memory
  convention between them, and the messages each carries. Part of the protocol artifact.
- **Guest shape**: per component, whether the module is handed its state on every call (stateless) or
  once per loaded instance (stateful). Declared in discovery.
- **Instance**: one loaded copy of the module serving one call at a time. The runtime keeps a pool for
  commands and a separate pool for work that may wait.
- **Guest library**: the per-language library giving developers the component model, the codec, the ABI
  implementation and the testkits. This feature's is for Rust.
- **Codec**: the derivation from a developer's types to the platform's encoding and back, measured by
  the published fixtures.
- **Unit testkit**: runs one component natively with no runtime, exposing effects as values.
- **Integration testkit**: starts Postgres and the runtime image with a module in Docker under any
  test runner.
- **Reference module**: the Rust declaration of the components, wire names and routes the conformance
  suite drives, in both shapes.
- **Hosting value**: the descriptor field that says a service's image is a Scala node, a process
  speaking the protocol, or a WebAssembly module.
- **Module delivery**: the way the module reaches the runtime's container in a cluster: the
  descriptor's image run once, handing the module over into a volume the runtime reads.
- **Crate**: the published artifact, versioned as the tag that published it, carrying its own copy of
  the protocol.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every encoding fixture published with the protocol passes through the library's default
  codec in both directions with 0 skipped and 0 byte differences.
- **SC-002**: The conformance suite passes against the reference module with 0 failures in the
  stateless shape and 0 in the stateful shape, and one deliberately broken behaviour is named by the
  suite as exactly that behaviour.
- **SC-003**: A command handled by a module completes, measured from the runtime's side, in less than
  half the time the same command takes through a separate process on the same machine, for a state of
  one kilobyte.
- **SC-004**: A module that crashes during a command loses 0 events and 0 state: the next command to
  the same entity sees exactly the state before the crash, and every other entity is unaffected.
- **SC-005**: Sixty-four handlers waiting concurrently on other components hold the runtime for no
  longer than one and a half times the longest single wait, and no entity command queued behind
  them is delayed by their waiting.
- **SC-006**: The shopping cart example passes its own integration tests through the runtime, and a
  journal written by the Scala cart is recovered by the Rust cart with 0 differences in state, and the
  reverse.
- **SC-007**: A developer with a Rust toolchain and Docker installed and no JVM goes from an empty
  directory to an entity commanded through a local runtime in under fifteen minutes following the
  documentation.
- **SC-008**: A persisting query, an unstated endpoint access rule, a duplicate wire name and a
  streaming route are each refused before the service serves a request, with a message naming the
  rule; the first is refused by the compiler.
- **SC-009**: The shopping cart example deploys to the local installation as one container, reaches
  `Ready`, scales, restarts, pauses, resumes and is exposed with the same commands and status words as
  any service, and a rolling replacement refuses 0 requests.
- **SC-010**: A module built for an ABI version the runtime does not speak, or an image that hands
  over no module, is reported in the service's status with the reason within one reconciliation,
  never as a pod that silently never starts.
- **SC-011**: A release tag publishes the crate at the tag's version with no credential in the
  repository, and the library's version reported in discovery equals the version on the registry.
- **SC-012**: The Rust project the CLI renders builds, starts and passes its own tests with 0 skipped,
  against the runtime image of the CLI's own version.
- **SC-013**: No test outside this feature changes, and the process-hosted conformance targets pass
  exactly as before.

## Assumptions

- **The WebAssembly runtime is the pure-JVM one the treatment recommends and the spike measured**, with
  its compiler to JVM bytecode required. The spike showed the interpreter twenty to fifty times slower
  and the compiler's cost a few tens of milliseconds per module, once. The choice touches one class
  behind the runtime's existing seam and can be revisited without changing this specification.
- **The ABI is a core-module convention** (exported functions over linear memory, a pointer and length
  each way, a packed return), not the Component Model, because no JVM runtime runs components yet. Its
  names carry a major version so a Component Model world can replace it later under a new version.
- **Both guest shapes ship in this feature**, because the spike found a twentyfold difference between
  guests' codecs and the stateful shape is what makes a slow codec viable; the Rust default is
  stateless.
- **The runtime image is the sidecar image**, serving both hosting modes chosen by configuration, so the
  operator's one setting for that image serves both.
- **The module reaches a pod by its image running once as an init container** that copies the module
  into a volume the runtime container mounts. The treatment first chose an image volume; planning
  found the container runtime must support it too, which containerd does only from 2.3.2 and the k3s
  test image does not, so a delivery that works on every cluster today is the one specified, and the
  image volume is a later simplification that changes nothing on the runtime's side.
- **The Rust library lives in `sdks/rust`** beside the Python and TypeScript SDKs, follows their layout,
  and carries its copy of the protocol as they do; the crate's name on the registry is `ankka`, which
  the plan confirms is unclaimed before anything depends on it.
- **The library uses the language's standard derivation for the codec** and generates nothing from the
  developer's code; a type the derivation cannot render in the encoding is a compile error or a testkit
  failure, never a silent difference.
- **The Rust compose file builds the module on the developer's machine** and mounts the file; the
  runtime image needs no Rust toolchain and the module needs no image locally.
- **The CLI cannot host the runtime** (it is a native binary with no JVM), so local development is the
  compose file, as for Python and TypeScript, not a new CLI command.
- **The spike's Rust and Go guests and their measurements stay in the repository** as the benchmark
  the hosting mode is measured against; the Go guest is not a Go library and is not documented as one.
- **The protocol version is the current one, `1.0`.** The ABI is versioned separately, starting at 1.
- **Every component kind ships in this one feature**, because the suite that defines compatible needs
  all of them, and the audience is building whole services.
- **The documentation follows the TypeScript trail page for page**: getting started, reference, a
  skill, differences sections, plus the concept and ABI pages the mode itself needs.

## Out of Scope

- A Go guest library. It is the next feature and reuses the host, the ABI and the conformance target
  unchanged; the spike's Go guest is the evidence it can.
- Any other language, the Component Model or WIT, and WASI.
- Streaming endpoint routes from a module.
- Interrupting or metering a running module, and any multi-tenant use of the host: one service's
  module runs in that service's own runtime, as its process does today.
- Attaching a debugger to a deployed module, and source-level debugging through the host.
- A CLI command that hosts a module locally, and hot reloading a module without restarting the runtime.
- Delivering a module other than by its image handing it over: no developer image built on the
  runtime's, no download at start, and no image volume until every target cluster's container
  runtime supports one.
- Any change to the protocol's messages, the encoding, the fixtures or the conformance cases. A gap
  found in any of them is a separate defect against feature 009.
