# Feature Specification: Polyglot Runtimes

**Feature Branch**: `009-polyglot-runtimes`

**Created**: 2026-09-22

**Status**: Draft

**Input**: User description: "can we adapt the current ankka to support polyglot runtimes? if
you take a look at cloudstate you see it uses a mechanism whereby the code which handles the
commands and emits events runs in containers and the akka cluster nodes are in sidecar
containers and the communication takes place using protobuf-encoded messages"

## Context

Every ankka service today is a Scala 3 program. The developer's components and the platform's
runtime are compiled into one JVM, and the only way to write an entity, a workflow or an agent
is to depend on the `ankka-sdk` jar. That is the right shape for the platform's own control
plane and for anyone already on the JVM, and it is a wall for everyone else.

[Cloudstate](https://github.com/cloudstateio/cloudstate) showed the other shape: the
developer's code runs in its own container in whatever language it was written in, and the
cluster node runs beside it as a sidecar. The sidecar owns everything stateful and everything
distributed: sharding, the journal, snapshots, projections, timers. The user's container owns
only the *decision*: given this command and this state, what should happen? The two talk over a
protobuf-encoded protocol on the pod's loopback interface.

ankka is closer to that shape than a single-JVM platform usually is, because of the decision it
was built around: a handler returns a description of what should happen and the runtime
interprets it. A command handler already receives bytes and hands back an effect that names
events, retention and a reply, and the runtime reduces that effect through one function whether
it is running for real or under the testkit. That effect *is* the message a sidecar protocol
carries. The runtime today obtains it by calling a Scala closure; this feature lets it obtain
the same effect by asking a process in another language.

Three things do not fall out of the existing design and are the substance of the work:

1. **Who owns the fold.** The runtime stores state and sequence numbers, but applying an event
   to state is the developer's code. In a sidecar model the developer's process must hold the
   entity's state in memory for as long as the sidecar has the entity loaded, and be kept in
   step with every event the sidecar persists. The protocol is therefore a conversation per
   loaded entity, not a request per command.
2. **Local development and testing.** The whole developer loop today assumes one process:
   `sbt run`, the testkit that boots a service against a throwaway database, and a debugger that
   sees everything. A developer in another language needs the sidecar as something they can run
   beside their code on a laptop, and a testkit that does the same thing under their language's
   test runner.
3. **A second, permanent surface to keep compatible.** Today the only compatibility promise is
   between a service's declared runtime version and the platform. The protocol adds a promise
   between every SDK ever shipped and every sidecar ever shipped, and it outlives any one of
   them.

The technical shape behind this specification is in `docs/design/polyglot-runtimes.md`.

The lineage this feature draws on ended by reversing itself: the platform that grew out of
Cloudstate moved its runtime back into the developer's process and shipped one SDK. The reasons
were the three above, plus SDKs that never earned their upkeep. This feature therefore commits
to *one* additional language, a conformance suite that any later SDK must pass, and no change to
the in-process Scala path, which remains the platform's fast path and the way its own control
plane is built.

## Clarifications

### Session 2026-09-23

- Q: Is all of this one feature, or is 009 the first slice with the rest as follow-on features? → A: One feature, five stories, tasks ordered P1 to P5; nothing ships until an SDK, the sidecar and the conformance suite agree.
- Q: Which language is the second SDK? → A: Python 3.12.
- Q: FR-011 names an HTTP surface that does not exist today; which shape should the sidecar's HTTP take? → A: Endpoints declared over the protocol: the process declares its routes in discovery and the sidecar serves them, forwarding each request over the protocol.
- Q: Keep SC-002's cross-language journal portability as a hard requirement? → A: Keep it as a general rule: every SDK's default JSON codec must match the Scala SDK's output for the same domain shape, under a specified mapping proven by a shared fixture suite.
- Q: Where does the Python SDK live? → A: In this repository, under `sdks/python`, so the protocol, the conformance suite and the SDK move together and a tag proves them consistent.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An entity in another language (Priority: P1)

A developer writes an event sourced entity in a language other than Scala, using an SDK for
that language. They declare its state, its events, its command handlers and how an event folds
into state, and they register it with a small program that listens for the sidecar. They start
the sidecar beside it on their laptop, send it a command over HTTP, and see the events in the
journal and the reply in their terminal. They stop both, start them again, and the entity's
state is what it was.

**Why this priority**: It is the whole feature in miniature. If one entity in one other
language can be commanded, persisted, recovered and replayed through the sidecar, the protocol,
the sidecar and the SDK all exist and agree. Everything else is more of the same.

**Independent Test**: Write the shopping cart's entity in the second language. Start the sidecar
and the cart beside each other. Add items, read the cart, restart both processes, read the cart
again and see every item. Prove the same journal is readable by the Scala shopping cart's
entity, and vice versa.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/polyglot/entities.feature`: a command runs its handler in the developer's code and records the events it names
- added `features/polyglot/entities.feature`: a refused command records nothing and its caller is told the refusal
- added `features/polyglot/entities.feature`: a restarted entity is recovered through the developer's code before its next command
- added `features/polyglot/entities.feature`: a snapshot is the developer's code's own, and recovery reads only the events after it
- added `features/polyglot/entities.feature`: a service written in one language recovers what a service written in another recorded
- added `features/polyglot/starting.feature`: a service is not ready until its process has declared its components
- added `features/polyglot/starting.feature`: a service whose code declares what the platform cannot host does not start
- added `features/polyglot/starting.feature`: a service whose code declares several problems is told every one at once

---

### User Story 2 - Deployed like any other service (Priority: P2)

A developer builds their service into an image that contains only their code and applies a
descriptor that says so. The platform runs the sidecar beside it. `ankka services get` shows the
service `Ready` when both are. Three instances form one cluster exactly as three instances of a
Scala service do, and a rolling update replaces them one at a time without refusing requests.
Nothing in the descriptor mentions the sidecar's image or version.

**Why this priority**: The platform's promise is that a service is deployed, scaled, exposed,
paused and observed the same way whatever it is written in. Without this the feature is a local
curiosity.

**Independent Test**: Apply a descriptor for the second language's shopping cart to the local
installation. See it become `Ready`, expose it, add items through the gateway, scale it to three,
restart it, and see every cart survive.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/polyglot/deploying.feature`: a service hosted as a process runs beside the platform's own program and is ready only when both are
- added `features/polyglot/deploying.feature`: a service written in another language scales into one cluster without replacing an instance
- added `features/polyglot/deploying.feature`: a service written in another language is restarted one instance at a time and refuses no request
- added `features/polyglot/deploying.feature`: a service written in another language is exposed, paused and resumed like any other service
- added `features/polyglot/deploying.feature`: the trace of a deployed service attributes each handler's time to the component that did the work
- added `features/polyglot/deploying.feature`: a process that stops is started again while its instance stays in the cluster
- `features/secrets/platform-settings.feature`: a descriptor may not give a variable the platform alone sets, however it gives it
- added `features/polyglot/deploying.feature`: a deployed service runs the platform's own program at the installation's version

---

### User Story 3 - Every component kind, not just entities (Priority: P3)

The developer adds a key value entity, a view, a consumer, a timed action and a workflow to
their service. The workflow's steps call other components through a client the SDK provides.
The view is queried through the sidecar. A timer scheduled by one of their handlers fires
into their code. All of it runs on the same sidecar, over the same protocol.

**Why this priority**: A platform that hosts entities in another language but workflows only in
Scala has a component model with an asterisk. It is third because each kind is one more
conversation on the protocol, and an entity proves the protocol.

**Independent Test**: Port the shopping cart sample completely, including its view and its
checkout workflow. Run the sample's own integration tests against the port through the
sidecar's HTTP surface.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/polyglot/components.feature`: a key value entity's state is kept and recovered after a restart
- added `features/polyglot/components.feature`: a view's rows follow its source's events and are removed with its source
- added `features/polyglot/components.feature`: a consumer is given each event at least once and in the order its entity recorded them
- added `features/polyglot/components.feature`: a workflow's steps run in the developer's code and each step is recorded
- added `features/polyglot/components.feature`: a step that fails is retried and then failed over as the workflow declared
- added `features/polyglot/components.feature`: a call through the SDK's client reaches its component wherever it runs
- added `features/polyglot/components.feature`: a call made by a handler is nested under that handler in the trace
- added `features/polyglot/components.feature`: a timer set by a handler runs its timed action, and again after a failure
- added `features/polyglot/components.feature`: a timer due while the process is not running runs once it is
- added `features/polyglot/starting.feature`: a service whose code declares what the platform cannot host does not start

---

### User Story 4 - Agents without an agent loop in every language (Priority: P4)

The developer declares an agent: its instructions, its tools and its model. The tools are
functions in their own code. The loop that calls the model, dispatches tools, keeps the session,
compacts it and counts tokens runs in the sidecar. Their process is asked to run a tool and
nothing else. Streaming replies reach the caller as they do from a Scala agent.

**Why this priority**: It is the strongest argument for the sidecar at all. An agent loop is the
part of the platform most expensive to reimplement per language and the part most likely to
change, and hosting it in the sidecar gives every language the same loop. It is fourth because
it is the largest single conversation on the protocol and depends on the client call path in P3.

**Independent Test**: Port the multi-agent planner's simplest agent to the second language,
with a scripted model in the sidecar's test configuration, and prove the tool is invoked in the
developer's process and the reply streams.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/polyglot/agents.feature`: a tool runs in the developer's code with the model's arguments
- added `features/polyglot/agents.feature`: an agent's reply reaches its caller as a stream while the model produces it
- added `features/polyglot/agents.feature`: a session survives a restart of the developer's code
- added `features/polyglot/agents.feature`: a tool that fails is told to the model and the loop goes on

---

### User Story 5 - A second SDK can prove itself (Priority: P5)

Someone writes an SDK for a third language. They run the platform's conformance suite against
it, which drives every conversation on the protocol through a reference service they wrote in
their language, and it tells them exactly which behaviours their SDK gets wrong. When it passes,
their SDK is compatible with every sidecar that speaks that protocol version, and the platform
did not need to know their language existed.

**Why this priority**: The cost of this feature is not the sidecar, it is every SDK forever. A
suite that defines "compatible" is what keeps that cost from landing on the platform. It is
last because it has one SDK to be tested against until P1 to P4 exist.

**Independent Test**: Run the conformance suite against the second language's SDK and see it
pass; deliberately break one behaviour in that SDK and see the suite name it.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/polyglot/conformance.feature`: the conformance suite names every behaviour it checks
- added `features/polyglot/starting.feature`: a service whose code speaks a protocol version the platform does not is refused, naming both
- added `features/polyglot/conformance.feature`: the conformance suite passes against the reference service in every language

---

### Edge Cases

- The developer's process restarts while an entity is loaded in the sidecar. The sidecar must
  re-establish the entity's conversation and replay the entity to the new process before the
  next command runs, and no command may run against state the process does not hold.
- The sidecar restarts while the developer's process is up. The developer's process must accept
  a fresh discovery and fresh conversations, and must discard every entity state it held.
- A handler in the developer's process never replies. The sidecar must fail the command with a
  timeout, must not persist anything, and must treat the conversation as broken rather than
  wait for it.
- A handler replies with events for an entity other than the one commanded, or with a reply for
  a command that was not sent. The sidecar must reject the reply, fail the command and close
  the conversation. The protocol is not trusted to be well-behaved; it is checked.
- Two commands for one entity arrive while the first is in flight. The sidecar already
  serializes commands per entity; the conversation must carry an identifier so a late reply is
  matched to its command and never to the next one.
- The developer's process holds an entity the sidecar has passivated. The process must be told,
  and must release the state, or a long-running service will hold every entity it ever saw.
- A snapshot produced by one SDK version cannot be read by the next. That is the developer's
  problem, the same as in Scala, but the sidecar must surface the failure as recovery failing
  for that entity, not as a crash.
- The developer's payload encoding is not one the sidecar's HTTP surface knows how to present.
  Payloads are opaque to the sidecar; it carries a content type and bytes and never inspects
  them.
- The pod's loopback is the only path between the two containers. Nothing in the protocol may
  be reachable from outside the pod, and a sidecar must refuse a connection from any other
  address.
- An in-process Scala service and a polyglot service share a cluster. They cannot: a service is
  one cluster, and a service is one hosting mode.

## Requirements *(mandatory)*

### Functional Requirements

**The protocol**

- **FR-001**: The platform MUST define a protocol between a sidecar and a developer's process,
  in protobuf, versioned independently of the platform, that carries every conversation needed
  to host each component kind: discovery, event sourced entities, key value entities, views,
  consumers, timed actions, workflows, agents, and calls from the developer's process to other
  components.
- **FR-002**: The protocol MUST carry the developer's own payloads (commands, replies, events,
  state) as opaque bytes with a content type, never as a structure the sidecar interprets, so
  that a journal written by a service in one language is readable by the same service in
  another, and so that no existing journal changes shape.
- **FR-003**: A command's reply on the protocol MUST express exactly what an in-process handler's
  effect expresses today, and nothing else: the events to persist, the retention to apply, and a
  reply or a refusal with an error code. There MUST be one interpretation of that reply, shared
  with the in-process runtime and the testkit, so the two hosting modes cannot disagree about
  what an effect means.
- **FR-004**: The conversation for a stateful component MUST be per loaded instance, MUST begin
  with the sidecar supplying the instance's recovered snapshot and the events after it, MUST
  keep the process's copy of the state in step with everything the sidecar persists, and MUST
  tell the process when the instance is unloaded.
- **FR-005**: Every request on a conversation MUST carry an identifier that its reply repeats,
  and the sidecar MUST reject a reply whose identifier does not match the request in flight.
- **FR-006**: The sidecar MUST validate every reply against the request it answers and fail the
  request, without persisting, when the reply is malformed, names the wrong instance, or arrives
  after the request timed out.
- **FR-007**: The developer's process MUST describe its components to the sidecar on startup
  (discovery) with the same information an in-process descriptor carries: component kind,
  component id, handler wire names, and, for stateful kinds, the snapshot interval. Registration
  stays explicit; the sidecar hosts exactly what it was told about.
- **FR-008**: Discovery MUST carry the SDK's protocol version, and the sidecar MUST refuse to
  start against a version it does not support, naming both versions.
- **FR-009**: The sidecar MUST NOT accept protocol connections from any address other than the
  pod's loopback interface.

**The sidecar**

- **FR-010**: The sidecar MUST be the platform's own runtime, packaged as an image of its own,
  started with no components of its own and obtaining its registry from discovery. It MUST NOT
  be a second runtime; every host, projection, timer and cluster behaviour is the existing one.
- **FR-011**: The developer's process MUST be able to declare HTTP endpoints (method, path
  template, whether the reply streams) in discovery, and the sidecar MUST serve them on the
  service's HTTP port, forwarding each request (path parameters, query parameters, headers, body)
  over the protocol and returning the reply, so that a polyglot service's HTTP surface is the
  platform's HTTP surface: the same port, readiness, exposure, ACL and tracing as an in-process
  endpoint. The process serves no HTTP of its own that the platform routes to.
- **FR-012**: The sidecar MUST host the agent loop, session memory, compaction, guardrails and
  token accounting for agents declared by the developer's process, asking that process only to
  run tools.
- **FR-013**: The sidecar MUST report itself not ready until discovery has completed, and MUST
  report itself not ready again while the developer's process is unreachable, so that a pod
  whose developer container is down receives no traffic while its sidecar keeps its cluster
  membership.
- **FR-014**: The sidecar MUST record the same observability as the in-process runtime: one span
  per handler invocation attributed to the component and handler, a refusal distinguished from a
  failure, and time spent waiting on the developer's process visible as such.
- **FR-015**: The sidecar MUST be runnable on a developer's machine beside their process, with
  the bundled Postgres, by one command, and its cluster formation in that mode MUST be the
  existing local overlay.

**The platform**

- **FR-016**: A service descriptor MUST be able to declare that its image is a developer's
  process rather than an ankka service, and the platform MUST run the sidecar beside it. The
  sidecar's image and version are the platform's choice; a descriptor MUST NOT be able to name
  either, and one that tries MUST be refused.
- **FR-017**: A polyglot service MUST be scaled, restarted, exposed, paused, resumed, suspended
  and observed exactly as a Scala service is, through the same commands, with the same status
  words.
- **FR-018**: The platform MUST provision a polyglot service's database exactly as it does for a
  Scala service, and the credentials MUST reach the sidecar and never the developer's container.
- **FR-019**: A rolling update of a polyglot service MUST replace pods one at a time with no
  refused requests, with the same surge, readiness and pre-stop behaviour the platform already
  renders.
- **FR-020**: The runtime compatibility check the control plane applies to a declared runtime
  version MUST apply to a declared protocol version for a polyglot service, with the same
  refusal path.

**The SDK**

- **FR-021**: The platform MUST ship one SDK for one language other than Scala, chosen for the
  audience most likely to write agents against the platform, that implements every component
  kind, a component client, and the discovery handshake.
- **FR-022**: That SDK MUST provide a unit-level testkit that runs a component's handlers
  against an in-memory state with no sidecar, and an integration testkit that starts the sidecar
  and a database under the language's own test runner.
- **FR-023**: That SDK's API MUST express the same model the Scala SDK expresses: handlers
  return descriptions of what should happen, wire names are declared separately from function
  names, and a query cannot persist.

**Conformance**

- **FR-024**: The platform MUST ship a conformance suite that drives an SDK's reference service
  through every conversation on the protocol and reports each behaviour by name, runnable
  against any SDK from the sidecar's side without knowledge of the SDK's language.
- **FR-025**: The conformance suite MUST also run against the Scala SDK in-process, so that it
  defines the component model's behaviour for both hosting modes and a divergence between them
  is a failing test.

**What must not change**

- **FR-026**: The in-process Scala path MUST be unchanged for existing services: no new
  dependency, no new process, no change in latency, and every existing test green.
- **FR-027**: No journal, snapshot, view row, offset or timer written before this feature MUST
  change shape, and the sidecar MUST read every one of them.
- **FR-028**: The platform MUST specify one JSON mapping for domain values — records, sum types
  with a discriminator, optional values, collections, numbers, times — that is exactly what the
  Scala SDK's default codecs produce today, and every SDK's default codec MUST produce and accept
  it, so that a journal, snapshot, view row or state written by a service in one language is read
  by the same service in another. A shared fixture suite (documents and their expected decoded
  values) MUST be published with the protocol and MUST pass in every SDK.

### Key Entities

- **Protocol**: the versioned set of protobuf messages and conversations between a sidecar and
  a developer's process. Owned by the platform, published as a file any language's tooling can
  generate from.
- **Sidecar**: the platform's runtime, started with no components of its own, hosting whatever
  the developer's process describes. One per pod of a polyglot service.
- **Developer's process**: the container holding the developer's code and their language's SDK.
  Stateless across restarts; holds entity state in memory only while the sidecar says an
  instance is loaded.
- **Discovery**: the first conversation, in which the developer's process describes its
  components and protocol version, and the sidecar accepts or refuses to host them.
- **Conversation**: one long-lived exchange per loaded stateful instance (or per stateless kind),
  carrying identified requests and replies, begun by the sidecar and closed by either side.
- **Service descriptor (changed)**: gains a way to declare that the image is a developer's
  process, and a declared protocol version alongside the existing declared runtime version.
- **Conformance suite**: the platform's definition of a compatible SDK, run from the sidecar's
  side against any SDK's reference service.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The shopping cart sample, ported to the second language, passes the sample's own
  integration tests through the sidecar with 0 changes to those tests' assertions.
- **SC-002**: A journal written by the Scala shopping cart is recovered by the ported cart with
  0 differences in state, and the reverse; and the shared JSON fixture suite passes in every SDK
  with 0 fixtures skipped.
- **SC-003**: One command against a polyglot entity, measured end to end the way the tracing
  overhead was measured, costs no more than twice what the same command costs in-process, and
  the difference is visible in the console as time waiting on the developer's process.
- **SC-004**: A polyglot service deployed to the local installation reaches `Ready`, scales
  1→3 with 0 existing pods replaced, and completes a restart with 0 refused requests out of a
  continuous stream of commands.
- **SC-005**: Every existing test passes unchanged, and an existing Scala service's image
  contains 0 new dependencies.
- **SC-006**: The conformance suite passes against both the Scala SDK and the second language's
  SDK, and a deliberately broken behaviour in either is named by the suite.
- **SC-007**: A developer in the second language goes from an empty directory to an entity
  commanded through a local sidecar in under fifteen minutes following the documentation, with
  no JVM installed.
- **SC-008**: 0 protocol connections are accepted from outside the pod, proven from another pod
  in the same namespace against a deployed service.
- **SC-009**: An agent declared in the second language with one tool completes a turn with the
  tool invoked in the developer's process, and its session survives that process restarting.

## Assumptions

- **The second language is Python 3.12**, for the audience writing agents. Its effect builders
  cannot make "a query cannot persist" a static guarantee the way a typed language can, so the
  SDK enforces it at registration and the conformance suite proves it; the platform's own rule
  (the sidecar refuses events from a read-only handler) holds regardless. The conformance suite
  exists so that a later SDK in another language is a project, not a platform change.
- **The protocol's transport is gRPC over loopback**, as Cloudstate's was: bidirectional streams
  are the natural shape for the per-instance conversation and every candidate language has a
  maintained implementation. The sidecar gains a gRPC dependency in `runtime`; the SDK-side
  jars gain nothing.
- **HTTP endpoints cross the protocol.** The process declares routes; the sidecar serves them
  and forwards each request. This puts path templates, query parameters, headers and streaming
  replies into the protocol for every SDK to implement, chosen so that a polyglot service has one
  HTTP surface with the platform's readiness, exposure and tracing rather than two ports with
  different rules.
- **A service is one hosting mode.** A descriptor is in-process or polyglot; nothing mixes
  Scala components and another language's components in one cluster.
- **Payloads are JSON under one specified mapping.** The Scala SDK's default codecs define the
  mapping; the platform writes it down and ships fixtures; every SDK's default codec matches it.
  A developer may still supply a custom codec, and then portability is their contract.
- **The sidecar is the runtime image, not a new build.** It is `ankka-runtime` plus a `main`
  that boots from discovery, published as an image alongside the operator and control plane.
- **The Python SDK lives in this repository**, under `sdks/python`, for the reason the Giter8
  template does: the protocol, the conformance suite and the SDK must move together, and a tag of
  this repository must be able to prove them consistent. Publishing it to a package index is a
  release-workflow job for a later feature.
- **Local development uses Docker for the sidecar**, the same way it already uses Docker for
  Postgres and Kafka. A native sidecar binary is out of scope.
- **The observability console needs no new concept.** A handler invocation over the protocol
  is one span; time waiting on the developer's process is unattributed time inside it, which the
  console already shows.

## Out of Scope

- A second non-Scala SDK. The conformance suite is how one would be built; building one is a
  feature of its own.
- Hosting the sidecar as a shared, multi-tenant runtime tier rather than one per pod. A service
  is one cluster and one database, and a shared tier would break both rules at once.
- Multi-region, cluster traffic encryption and autoscaling, which remain as listed in
  `README.md`'s "Not implemented" and are no different for a polyglot service.
- Changing the in-process Scala SDK's API. The Scala SDK is not reimplemented over the protocol;
  it keeps its direct path, and the conformance suite is what proves the two paths agree.
- A native, non-Docker sidecar binary for local development.
- Migrating an existing service between hosting modes in place. The journal is portable; the
  descriptor is re-applied.
