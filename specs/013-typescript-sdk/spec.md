# Feature Specification: TypeScript SDK

**Feature Branch**: `013-typescript-sdk`

**Created**: 2026-09-25

**Status**: Draft

**Input**: User description: "A TypeScript SDK for Node.js developers, per the design treatment in
docs/design/typescript-sdk.md: an SDK in sdks/typescript that hosts ankka services written in
TypeScript through the existing sidecar protocol, with a schema-based codec matching
protocol/ENCODING.md, unit and integration testkits, the shopping cart and conformance reference
examples, npm publishing from a tag, CI and release jobs mirroring the Python SDK's, and
documentation and a skill on the Python trail. The platform does not change."

## Context

ankka hosts a service written in a language other than Scala through the sidecar: the platform's
runtime runs beside the developer's process and speaks a gRPC protocol to it. The sidecar owns
everything durable and distributed; the process owns the decisions. Feature 009 built that model,
shipped one SDK for it (Python), and wrote down what any later SDK must satisfy: the protocol in
`protocol/`, the encoding in `protocol/ENCODING.md` with its fixtures, and the conformance suite that
drives a reference service from the sidecar's side and names each behaviour it checks. The platform
never learns which language is on the other end. A new language is therefore a project in `sdks/`,
not a platform change, and this feature is the first to test that claim.

The audience is developers who use Node.js for their backend work. Not the agents audience that
chose Python: people who already run services on Node and would take a framework that lets them
build larger systems in a highly structured way, in code they can read and understand, inside a
paradigm most of them have not met (entities, events, effects as values, workflows as durable step
machines). That audience decides more of the design than the protocol does. A service written with
this SDK must read like ordinary modern Node; the structure the component model imposes is the
product, so the SDK enforces it rather than suggesting it, at compile time wherever the language
allows; and because a Node developer's first contact is a getting-started page or a coding agent
holding an ankka skill, the documentation is part of the feature, not an appendix to it.

Two things about the language shape the work. TypeScript erases its types, so every value that
crosses the protocol needs a schema declared at runtime, and ankka's encoding makes that schema do
more than a validation library does: it must distinguish an integer from a double where JavaScript
has one number type, carry a 64-bit integer without loss where JavaScript's cannot, and render every
value exactly as the Scala codecs do. In return, TypeScript's type system gives back what Python
could not: a query that cannot persist becomes a compile error, as it is in Scala.

The technical shape behind this specification is in `docs/design/typescript-sdk.md`.

The design treatment for feature 009 quoted the lineage's warning about SDKs that never earned
their upkeep. This feature is where that cost starts: a protocol change now touches three SDKs and
every shared skill gains a third differences section. The audience above is the reason on record,
and the conformance suite is what keeps the cost bounded.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An entity in TypeScript (Priority: P1)

A Node developer writes an event sourced entity and an HTTP endpoint in TypeScript, using the SDK.
They declare the entity's state and events as schemas, its command and query handlers with their
wire names, and how an event folds into state. They start the sidecar and a database with the one
command the documentation gives, run their process with the Node they already have and no build
step, and command the entity over HTTP. Its state survives the sidecar restarting, and it lives in
the same journal records a Scala or Python service writes.

**Why this priority**: Until one entity written in TypeScript can be commanded, persisted, recovered
and replayed through the sidecar, nothing else in this feature can be tested. It also proves the two
hard parts, the codec and the per-instance conversation, before anything is built on them.

**Independent Test**: Write the shopping cart's entity and endpoint in TypeScript. Start the sidecar
beside it with the bundled Postgres, add items over HTTP, restart the sidecar, read the cart back.
Run the encoding fixtures through the SDK's default codec. Run the conformance suite's event sourced
and discovery behaviours against it.

**Acceptance Scenarios**:

1. **Given** an entity written against the SDK and a sidecar started beside it, **When** a command
   is sent over the endpoint's route, **Then** the handler runs in the developer's process, the
   events it names are persisted by the sidecar, and the reply is computed from the state after
   those events.
2. **Given** an entity with persisted events, **When** the sidecar is restarted and the entity is
   commanded again, **Then** the process is given the recovered snapshot and the events after it,
   folds them, and the command runs against the recovered state.
3. **Given** a query handler, **When** it is written to return a persisting effect, **Then** the
   program does not compile, and the error names the rule.
4. **Given** every encoding fixture published with the protocol, **When** the SDK's default codec
   decodes and re-encodes each one, **Then** every value matches and every byte matches, and a
   fixture the codec cannot handle is a failure, not a skip.
5. **Given** a journal written by the Scala shopping cart, **When** the TypeScript cart is pointed at
   the same database, **Then** it recovers the same carts with the same state, and the reverse.
6. **Given** a handler that throws, **When** the command runs, **Then** the command fails, nothing is
   persisted, the entity's state is unchanged, and the process keeps serving.

---

### User Story 2 - Tested without and with the sidecar (Priority: P2)

The developer tests their components under the test runner they already have. A unit testkit runs
one component with no sidecar, no database and no network, in milliseconds, and still carries every
value through the component's own codecs, so a shape the codec cannot express fails there. An
integration testkit starts Postgres and the real sidecar image in Docker, serves the developer's
components to it, and can replace the sidecar against the same database so a test proves durability
rather than caching.

**Why this priority**: The audience is being asked to adopt an unfamiliar paradigm; a testkit that
lets them see an effect as a value, and a second one that proves the whole thing against the real
runtime, is how they learn to trust it. Both are also what the shopping cart port and every later
story test themselves with.

**Independent Test**: The shopping cart's unit tests pass with no Docker running; its integration
tests start the sidecar, exercise every route, restart the sidecar and read the state back. Neither
testkit depends on which test runner the developer chose.

**Acceptance Scenarios**:

1. **Given** an entity and the unit testkit, **When** a command is sent, **Then** the test sees the
   events, the new state, the retention and the reply or refusal as values, and the state carries
   forward to the next command.
2. **Given** a component whose state contains a value its declared schema cannot encode, **When** a
   unit test drives it, **Then** the test fails on the encoding, not on first deployment.
3. **Given** the integration testkit, **When** a test starts it, **Then** Postgres and the sidecar are
   running with the platform's own schema, the developer's components are served to the sidecar, and
   the sidecar reports healthy before the test body runs.
4. **Given** a running integration testkit, **When** the test restarts the sidecar, **Then** a new
   sidecar runs against the same database and every entity is recovered from the journal.
5. **Given** a workflow with a pause, **When** the unit testkit runs it to its end, **Then** the test
   follows the transitions, stops at the pause, and can resume it.

---

### User Story 3 - Every component kind, proven compatible (Priority: P3)

The developer builds the rest of a service in TypeScript: key value entities, views, consumers,
timed actions, workflows whose steps call other components through the SDK's client, and an agent
whose tools run in their process while the sidecar runs the loop. An SDK author runs the platform's
conformance suite against the SDK's reference service and it passes, so the SDK is compatible with
every sidecar that speaks its protocol version.

**Why this priority**: The audience wants to build larger systems, and a platform that hosts
entities in TypeScript but workflows only in Scala or Python is not that. The conformance suite is
the platform's definition of compatible, and its reference service needs every kind, so this story
is where the feature becomes an SDK rather than a demonstration.

**Independent Test**: The reference service in TypeScript declares the same components, wire names
and routes as the Scala reference. The conformance suite runs against it and passes every behaviour.
Break one behaviour deliberately and the suite names it.

**Acceptance Scenarios**:

1. **Given** a key value entity, a view, a consumer and a timed action in TypeScript, **When** they
   are driven through the sidecar, **Then** each behaves as the conformance suite requires: state is
   updated and read, rows are updated and deleted on the source's events and deletion, messages are
   consumed and produced, and a timed action fires and is retried on failure.
2. **Given** a workflow in TypeScript, **When** it is started, **Then** each step runs in the
   developer's process, transitions and pauses are honoured, a step that throws is retried and failed
   over as the workflow declared, and a query arriving while a step runs is answered from the state
   before the step without disturbing the step.
3. **Given** a workflow step that calls an entity through the SDK's client, **When** the call is
   made, **Then** it is routed by the sidecar to the target and the reply is decoded into the type
   the caller named.
4. **Given** an agent declared with one tool and one guardrail, **When** a message is sent to a
   session, **Then** the sidecar runs the loop, the tool runs in the developer's process with the
   model's arguments, the guardrail is consulted, and the session survives the process restarting.
5. **Given** the conformance suite and the TypeScript reference service, **When** the suite runs,
   **Then** every behaviour passes; **When** one behaviour is deliberately broken, **Then** the suite
   names that behaviour and no other.
6. **Given** a process that declares a handler wire name twice, or an endpoint without an access
   rule, **When** the service is assembled, **Then** it refuses to start and names every problem at
   once.

---

### User Story 4 - Installed from the registry, deployed to the platform (Priority: P4)

The developer adds the SDK to their project from the public package registry at the version of the
platform they deploy to. They build an image of their process alone, write a descriptor that says
the image is a process speaking the protocol, and deploy it to a local ankka installation. It
reaches `Ready`, scales, restarts and is exposed exactly as any other service. A release of this
repository publishes the SDK from a tag, and the version the package reports to the sidecar is the
version on the registry.

**Why this priority**: A framework a Node developer cannot `npm install` does not exist to them.
This story is the point at which the platform's promise, "a project, not a platform change", is
tested end to end with a deployed service and no code changed on the platform.

**Independent Test**: In a fresh directory, install the package from the registry, build the
shopping cart's image from the example, apply its descriptor to the local installation, and command
the cart through the platform's gateway. Tag a release and find the package on the registry at that
version.

**Acceptance Scenarios**:

1. **Given** an empty project, **When** the developer installs the SDK from the registry, **Then**
   the package imports, carries its own protocol stubs, and declares every dependency it needs.
2. **Given** the shopping cart example's image and descriptor, **When** applied to the local
   installation, **Then** the service reaches `Ready`, the sidecar reports the SDK's name and version
   in discovery, and the cart is commanded through the platform's gateway.
3. **Given** a release tag, **When** the release workflow runs, **Then** the package is published at
   the tag's version, after and only after the platform's own artifacts are, with no credential
   stored in the repository.
4. **Given** the continuous integration workflow, **When** a commit changes the protocol without
   refreshing the SDK's copy of it, **Then** the build fails on that commit.

---

### User Story 5 - The paradigm, taught (Priority: P5)

A Node developer who has never met an event sourced entity follows the getting-started page from an
empty directory to a running service, then reads the reference page for the kind they need next. A
coding agent working in their project holds an ankka skill for TypeScript and produces components
that compile, pass their unit tests and pass through the sidecar. Every code sample they read is
taken from code the repository tests.

**Why this priority**: The audience is defined by not knowing this paradigm. The SDK's structure is
only a benefit if it is explained, and in this repository the documentation and the skills are the
explanation. It is last because every page includes samples from code the earlier stories write.

**Independent Test**: The documentation builds with the new pages, every included sample is current,
and a reader with Node and Docker installed and no JVM reaches a commanded entity in the time the
page promises. Every shared skill that says how Python differs also says how TypeScript differs.

**Acceptance Scenarios**:

1. **Given** the getting-started page for TypeScript, **When** a developer with Node and Docker
   follows it from an empty directory, **Then** they command an entity through a local sidecar with
   no JVM and no compiler configuration.
2. **Given** the reference page, **When** a developer looks up any component kind, the client, the
   testkits or how a service runs, **Then** the page states it, with a sample included from tested
   code.
3. **Given** the TypeScript skill and each shared skill, **When** a coding agent is asked for a
   TypeScript component of any kind, **Then** the skill it loads states the rules that differ from
   Scala and Python and the code it writes compiles and passes the unit testkit.
4. **Given** the documentation build, **When** a page's included sample drifts from its source or a
   new page is not in the navigation and a skill, **Then** the build fails.

---

### Edge Cases

- A record holds a 64-bit integer larger than 2⁵³. The SDK must carry it without loss in both
  directions; a value that silently loses precision is a corrupted journal.
- A double whose value is a whole number. The Scala codecs write `1.0`; the SDK must write the same
  bytes, and must write an integer field's `1` as `1`. The schema, not the value, decides which.
- An instant with nine fractional digits. The SDK must read it and write it back with the same
  digits.
- A stored record carries a field the schema does not know. Reading ignores it. A stored record is
  missing a required field, or names a sum type case the schema does not know. Reading refuses it,
  and the failure is reported as recovery failing for that entity, never as a crash of the process.
- A query handler written to persist. Refused at compile time by the handler's declared type;
  refused again at registration for a caller who bypassed the type checker; refused a third time by
  the sidecar, which accepts no events from a read-only handler.
- An endpoint with no access rule. The program does not compile, and registration refuses it.
- The process is started on a Node older than the SDK supports. It must fail at startup with a
  message naming the version it needs, not with a syntax error from a construct that version lacks.
- A handler's promise rejects, or an error escapes asynchronously. Both become the command's
  failure; neither may take the process down.
- The stream for a loaded instance ends. The process cannot tell passivation from the sidecar going
  away and must release the instance's state in both cases.
- A command arrives for a workflow while a step is running. It is answered from the state before the
  step; the step's new state applies when the step replies. A second step requested while one runs
  is refused.
- Two components declare the same id; two handlers on one component declare the same wire name; two
  endpoints declare the same prefix. The service refuses to start and reports every problem
  together.
- The sidecar refuses discovery. The process logs every problem the sidecar reported, once, so the
  reason is in the developer's own log.
- The process is asked to bind an interface other than loopback. Refused, except the any-address
  binding the integration testkit needs to be reached from a container.
- A tool is declared with no description. Refused at registration, since the model cannot choose a
  tool it cannot read.
- The developer's project is CommonJS. The package is a modern module, and a CommonJS project on a
  supported Node can still `require` it.

## Requirements *(mandatory)*

### Functional Requirements

**The component model**

- **FR-001**: The SDK MUST let a developer declare every component kind the platform hosts: event
  sourced entity, key value entity, view, consumer, timed action, workflow, agent, and HTTP
  endpoint, with the same model the Scala and Python SDKs express: a stable component id, handlers
  whose wire names are declared separately from their method names, and handlers that return
  descriptions of what should happen rather than performing it.
- **FR-002**: A query handler MUST be unable to persist. The SDK MUST make this a compile-time error
  through the type the handler is declared to return, and MUST refuse at registration a handler
  that reaches it any other way.
- **FR-003**: Every effect the Scala SDK offers for a kind MUST be expressible: for entities,
  persist with a reply computed from the state after the events, reply, refuse with an error code,
  no reply, delete, expire; for workflows, update state, transition, pause with a timeout, end,
  fail, and declared timeouts and recovery; for views and consumers, update, delete, ignore,
  produce; for agents, system and user messages, context, memory, model, tools, guardrails and a
  plain or structured reply.
- **FR-004**: An HTTP endpoint MUST declare an access rule with no default, MUST be able to state a
  different rule on one route, and MUST give a handler typed path parameters, a decoded body, the
  query parameters, headers and, when authenticated, the caller's principal. A route MUST be able to
  stream a reply.
- **FR-005**: Registration MUST be explicit. The SDK MUST collect every problem with a service's
  declaration (duplicate ids, duplicate wire names, shared prefixes, missing schemas, a read-only
  handler that is not, an agent tool with no description) and refuse to start naming all of them at
  once.
- **FR-006**: The SDK MUST provide a client through which a handler calls entities, workflows and
  agents by component id and wire name, queries views, and schedules and cancels timers, with the
  reply decoded into the type the caller names and a refusal surfaced as an error carrying its code.
- **FR-007**: The SDK MUST use no language feature that a supported Node cannot execute directly
  from source, and MUST require no decorators, no reflection metadata, no code generation from the
  developer's own code and no dependency container, so a service reads as ordinary code and runs
  with no build step.

**The encoding**

- **FR-008**: The SDK MUST provide a way to declare the shape of every value that crosses the
  protocol (records, sum types with a discriminator, enumerations, optional values, sequences,
  string-keyed maps, integers, 64-bit integers, doubles, booleans, strings, instants, durations,
  dates and bytes, including recursive shapes), and MUST derive the static type from that
  declaration so the shape is written once.
- **FR-009**: The SDK's default codec MUST produce and accept exactly the encoding in
  `protocol/ENCODING.md` for every shape it lists, including the text and binary payloads for
  top-level primitives, and MUST pass every fixture published with the protocol both ways, with a
  fixture it cannot handle failing rather than skipping.
- **FR-010**: The codec MUST distinguish an integer from a double by declaration, MUST carry a
  64-bit integer without loss, and MUST render doubles, instants and durations byte for byte as the
  Scala codecs do.
- **FR-011**: A shape declaration MUST be able to yield the JSON Schema an agent tool's input needs,
  so a tool's input is declared once.
- **FR-012**: A developer MUST be able to supply a codec of their own for a type; portability is
  then their contract, as in every other SDK.

**Serving the protocol**

- **FR-013**: The SDK MUST implement every service in the protocol except the sidecar's callback
  service, on loopback at the process port the platform names, MUST dial the callback service at
  the sidecar address the platform names, and MUST refuse to bind any other interface except the
  any-address binding the integration testkit requires.
- **FR-014**: The SDK MUST answer discovery with the protocol version it speaks, its own name and
  version, and every registered component and endpoint with the details each kind requires, and
  MUST log every problem the sidecar reports back.
- **FR-015**: For each loaded stateful instance the SDK MUST hold the state for the life of the
  conversation, fold the recovered snapshot and events before the first command, handle commands
  strictly in order, compute each reply from the state after the reply's events, and release the
  state when the conversation ends for any reason.
- **FR-016**: For a workflow the SDK MUST keep one command and one step in flight at once, answer a
  command that arrives mid-step from the state before the step, apply the step's new state when the
  step replies, and refuse a second concurrent step.
- **FR-017**: A handler that throws, or whose promise rejects, MUST become that request's failure on
  the protocol with the instance's state unchanged, and MUST never terminate the process.
- **FR-018**: The SDK MUST refuse to start on a Node older than the version it supports, naming the
  version required.

**Testkits**

- **FR-019**: The SDK MUST provide a unit testkit that drives one component of any kind with no
  sidecar, database or network, exposes each effect's result as values, carries the state forward
  between calls, and round-trips every input, event, state, row and reply through the component's
  declared codecs. For agents it MUST run the loop in-process against a scripted model that fails
  when the script runs out.
- **FR-020**: The SDK MUST provide an integration testkit that starts Postgres with the platform's
  own schema and the real sidecar image, serves the test's components to it, waits for the sidecar
  to report healthy, offers the service's HTTP surface and client to the test, can replace the
  sidecar against the same database, and cleans up after itself including when startup fails.
- **FR-021**: Neither testkit MAY depend on a particular test runner.

**Examples and conformance**

- **FR-022**: The SDK MUST ship the shopping cart sample ported to TypeScript, with the same
  components, wire names, routes and stored shapes as the Scala and Python carts, its own unit and
  integration tests, an image build, and a descriptor.
- **FR-023**: The SDK MUST ship a reference service declaring the same components, wire names and
  routes as the Scala conformance reference, and a command that serves it and runs the platform's
  conformance suite against it, exiting with the suite's status.
- **FR-024**: The SDK MUST carry a copy of the protocol artifact so it builds alone, and continuous
  integration MUST fail when the copy differs from the original.
- **FR-025**: A test MUST prove that a journal written by the Scala shopping cart is recovered by
  the TypeScript cart with identical state, and the reverse.

**Packaging and release**

- **FR-026**: The SDK MUST be published to the public package registry from a release tag of this
  repository, at the tag's version, after the platform's own artifacts, with no publishing credential
  stored in the repository, and MUST report that same version to the sidecar in discovery.
- **FR-027**: The version in the tree MUST be a placeholder rewritten by the release job, as the
  template, plugin, formula and Python SDK do; nothing in the build may write to a tracked file.
- **FR-028**: The published package MUST carry its generated protocol stubs and declare every
  dependency, proven by installing the packed artifact into an empty directory and importing it,
  on every commit and before every publish.
- **FR-029**: Continuous integration MUST run the SDK's typecheck, tests, packaging proof and the
  conformance suite against the sidecar image built from the same commit.

**Documentation**

- **FR-030**: The documentation MUST gain a getting-started page and a reference page for
  TypeScript, following the Python pages' structure, with every sample included from tested code.
- **FR-031**: A TypeScript skill MUST exist beside the Python one, and every shared skill that has a
  Python differences section MUST gain a TypeScript one.
- **FR-032**: Every page, skill and tool description that names the supported languages (including the
  CLI's MCP tool descriptions and the site's own description) MUST name TypeScript, and the contributing
  page on adding a language SDK MUST present the two SDKs as its examples.

**What must not change**

- **FR-033**: No file in the sidecar, the runtime, the operator, the control plane, the CRD or the
  protocol MAY change for this feature. A behaviour the TypeScript SDK needs that the platform
  lacks is a defect in feature 009, filed and fixed separately, not a change smuggled in here.
- **FR-034**: The conformance suite and the encoding fixtures define compatibility and MUST NOT be
  altered to make the SDK pass. A case the SDK cannot pass is a defect in the SDK.
- **FR-035**: Every existing test MUST pass unchanged, and the Scala and Python SDKs MUST be
  unaffected except where a shared documentation page or skill gains a TypeScript section.

### Key Entities

- **Shape declaration**: the runtime description of a value's structure, from which the SDK derives
  both the static type and the codec. Declared once per state, event, command, reply and row.
- **Codec**: a manifest, a content type and an encode and decode pair for one shape. The default
  codec for a declared shape produces the platform's encoding exactly.
- **Component declaration**: a class carrying a component id, its codecs, its handler table with
  wire names, and the kind's methods: fold, view change, consumer message, workflow steps, agent
  tools and guardrails.
- **Effect**: an inert description of what should happen for one request, built by a handler and
  reduced by the sidecar. Read-only effects are a distinct type.
- **Service**: the explicit registry of components and endpoints, validated as a whole, that
  answers discovery and serves the protocol.
- **Client**: the handler's way to call other components through the sidecar, scoped to the request
  it runs in so traces connect.
- **Unit testkit**: runs one component with no sidecar and exposes its effects as values.
- **Integration testkit**: runs the developer's components against the real sidecar image and
  Postgres in Docker, under any test runner.
- **Reference service**: the TypeScript declaration of the components, wire names and routes the
  conformance suite drives.
- **Package**: the published artifact, versioned as the tag that published it, carrying its own
  protocol stubs.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every encoding fixture published with the protocol passes through the SDK's default
  codec in both directions with 0 skipped and 0 byte differences.
- **SC-002**: The conformance suite passes against the TypeScript reference service with 0 failures,
  and one deliberately broken behaviour is named by the suite as exactly that behaviour.
- **SC-003**: The shopping cart port passes its own integration tests through the sidecar, and a
  journal written by the Scala cart is recovered by the TypeScript cart with 0 differences in state,
  and the reverse.
- **SC-004**: A developer with Node and Docker installed and no JVM goes from an empty directory to an
  entity commanded through a local sidecar in under fifteen minutes following the documentation,
  running their service with one command and no compiler configuration.
- **SC-005**: A persisting query, an unstated endpoint access rule and a duplicate wire name are each
  refused before the service starts, with a message naming the rule; the first two are refused by the
  type checker.
- **SC-006**: The shopping cart port deploys to the local installation and reaches `Ready`, scales,
  restarts and is exposed with the same commands and status words as any service, with 0 changes to
  the sidecar, runtime, operator, control plane, CRD or protocol.
- **SC-007**: A release tag publishes the package at the tag's version, and a service built from that
  package reports that version in discovery.
- **SC-008**: The documentation builds with 0 problems with the new pages, the new skill and the
  TypeScript sections in every shared skill that has a Python one, and 100% of the code samples on
  the TypeScript pages are included from tested code.
- **SC-009**: Every existing test in the repository passes unchanged.

## Assumptions

- **The language is TypeScript on Node.js**, the current long-term-support line and later, at a
  floor that runs TypeScript source directly, so the first-service experience has no build step. The
  package is a modern module; a CommonJS project on a supported Node can still require it. Browsers,
  Deno and Bun are not targets.
- **The SDK lives in this repository**, under `sdks/typescript`, for the reason the Python SDK does:
  the protocol, the conformance suite and the SDK move together, and a tag proves them consistent.
  The directory is named for the language the developer writes, not the runtime it targets.
- **The package is named `ankka` on the public registry**, mirroring the Python package, and both
  that name and a scoped alternative were unclaimed on 2026-09-25. Attaching trusted publishing may
  require the package to exist first; if so, the first publish is a one-time manual step, recorded.
- **The shape declaration is the SDK's own**, not a third-party validation library, because the
  platform's encoding needs decisions such a library does not make (integer versus double, lossless
  64-bit integers, exact rendering). An adapter for a popular library is possible later and is not
  part of this feature.
- **The transport library is a planning decision**, settled by a spike of the event sourced
  conversation against the real sidecar image before the plan commits. Both candidates speak the
  protocol the sidecar already speaks; nothing in this specification depends on which is chosen.
- **The protocol version is the current one, `1.0`.** The SDK declares it in discovery and the
  sidecar's version rules apply unchanged.
- **The conformance suite's reference is the full list of what the SDK must declare.** The Scala
  reference and the conformance contract in `specs/009-polyglot-runtimes/contracts` are the source;
  the Python reference is the worked example of an SDK's copy of it.
- **Every component kind ships in this one feature**, because the suite that defines compatible
  needs all of them, and the audience is building whole services.
- **The documentation follows the Python trail page for page**: getting started, reference, a skill,
  and differences sections. The docs tooling already accepts sample markers in any comment syntax and
  needs no change.

## Out of Scope

- A TypeScript variant of `ankka init`. The getting-started page starts from the package manager's
  own project initialisation, as the Python page does.
- Support for browsers, Deno or Bun, or a client for the control plane in TypeScript.
- An adapter from a third-party schema library to the SDK's shape declaration.
- Porting the multi-agent planner sample. The reference service's agent and the shopping cart's
  assistant are the agent proof, as they are for Python.
- Any change to the protocol, the encoding, the fixtures, the conformance suite, the sidecar or the
  platform. A gap found in any of them is a separate defect against feature 009.
