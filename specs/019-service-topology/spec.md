# Feature Specification: Service Topology — What a Service Runs and How It Connects

**Feature Branch**: `019-service-topology`

**Created**: 2026-10-01

**Status**: Draft

**Input**: User description: "Service topology in the console: visualise the components a service
runs (entities, views, consumers, workflows, agents, endpoints, timers, topics) and how they
connect. Two kinds of edge. (1) Declared edges, known at registration from descriptors in every
language: a view's or consumer's source (entity events, key-value state changes, topic), a
consumer's produces-to topic, endpoint routes — already carried by the Scala descriptors and the
polyglot discovery message (discovery.proto Source, ConsumerDetail.produces_to). (2) Observed call
edges: who calls whom through the ComponentClient. These are code inside handlers and cannot be
declared without a programming-model change, so the runtime counts caller→callee pairs (component
and handler, both bounded/interned — never entity ids) at the one chokepoint every cross-component
call passes, CallTransport, with counts by outcome (Ok/Refused/Failed/TimedOut) and latency, over
the same fixed window semantics as the recorder; an edge never exercised is absent and the console
must say the call graph is observed, not complete. Scope phase 1: the local console (ankka local
console) gains a Topology view fed by the existing loopback ObservabilityEndpoint's inventory
extended with declared edges and observed call counts. Phase 2: the installation's console shows
the same for a deployed service, served on the management port (ObservabilityRoute) and fetched by
the control plane on behalf of an authorised member — no new privilege for the console, control
plane remains the only authority. Out of scope: static analysis of handler code; a build-time
manifest baked into the image (rejected because it can describe a different build than the one
running); declared-and-enforced `calls` on descriptors (a separate decision). Edges must never be
guessed: an orphan span's call with an unknown caller is shown as unknown, not attributed to a
plausible caller."

## Context

A developer looking at an ankka service today can see its parts but not its shape. The local
console's Components tab lists what a service registered: each component's kind, id and declared
queries, and the HTTP routes it serves. A trace shows the path one request took. Nothing answers the
question a newcomer to a service asks first, and that its author asks after six months away: *what
is connected to what?* Which views are fed by the cart's events, which consumer publishes to which
topic, which endpoint or workflow calls the cart, and which agent's tools reach which entities.

The connections fall into two kinds, and the difference between them is the main design decision
of this feature:

- **Declared connections** are part of a component's definition, so they are known at the moment
  it registers, before it handles anything. A view or consumer names its source: an entity's events,
  a key-value entity's state changes, or a topic. A consumer that publishes names its topic. An
  endpoint serves its routes. These hold in every language the platform hosts, because a Python,
  TypeScript or Rust service declares the same facts to the runtime when it starts. They are exact
  and complete: if a source exists, it is declared.
- **Calls** happen inside handler code. An endpoint, a workflow step, a consumer, a timed action or
  an agent's tool calls another component through the component client. Nothing in a component's
  definition says whom it will call, and the target can be chosen at run time. The only honest way
  to know a call happens is to watch it happen. Every cross-component call passes one place in the
  runtime, so the runtime can count each caller-to-callee pair there, with how each call ended and
  how long it took.

Watched calls give a picture with a known kind of gap. A call that has not happened in the current
window is absent, even if the code would make it on the next request. The console therefore labels
calls as *observed* and never presents them as the complete set. This follows the observability
rule already in the platform: a visible hole is better than a picture that reads correctly and
describes something that did not happen. For the same reason, a call whose caller cannot be known,
because the work ran on a thread that carried no context, is drawn from an *unknown caller*. It is
never attached to the nearest plausible component.

Three other approaches were considered and are deliberately not taken:

- **Reading handler code** (static analysis of each language) would need a separate analyser per
  language, and would still miss any call whose target is chosen at run time.
- **A manifest written at build time and stored with the image** describes the build that wrote it,
  which need not be the build that is running. A running service describing itself cannot be wrong
  in that way.
- **Components declaring whom they call**, with the runtime refusing an undeclared call, would make
  calls as exact as sources. It changes the programming model for every language, so it is a
  separate decision.

The feature has two phases. The first gives the local console a Topology view of every service on
the developer's machine. The second shows the same view for a deployed service in the installation's
console, which today shows a deployed service's status, history and logs but nothing of its inside.

## Clarifications

### Session 2026-10-01 (planning)

These corrections were made during planning; the reasons are in `research.md`.

- **How a call ended is seen from two viewpoints (R2).** A handler that throws sends no reply. The
  callee knows it failed, and the caller only sees that no answer came. Each call is therefore
  counted as **handled** by the callee (`Ok`, `Refused`, `Failed`, with durations) and, when no
  answer came, as **unanswered** (`TimedOut`, or `Undelivered` when the handler never ran). The two
  groups are shown side by side and never summed. FR-007, SC-003 and the scenarios
  "a handler that fails is counted as failed where it ran and as timed out where it was called" and
  "a call its caller stopped waiting for is counted as timed out, and as handled when the handler finishes"
  say so.
- **The phase 2 network path is a dedicated port (R7).** Each workload gains port 7628 `observe`.
  It uses mutual TLS that admits only the control plane's identity, has a network policy that admits
  only the control plane's pods, and serves the topology and the inventory, nothing else.
- **Calls to another service are keyed by service and HTTP method only (R3).** The path a call names
  is filled in, so it is unbounded, and it is never recorded.

### Session 2026-10-02 (analysis)

These corrections came out of the cross-artifact analysis.

- **Every call lands somewhere.** A call the platform answers without running a handler (the method
  is not declared, or the host was stopping), and a call that reached no host at all, is counted as
  unanswered `Undelivered`. A call naming a method the component does not declare is counted under
  the handler name `(undeclared)`. Counts are attempts: a call retried three times is three attempts.
- **Service names are bounded by admission, not by trust.** A service name can come from request
  input. A process shows at most a fixed number of other services by name (32 by default) and counts
  calls to any further one under a single *other services* node.
- **Both ends of a call are validated.** A caller or a callee is recorded by name only when it names
  a registered component and a handler that component declares.
- **Only a called service is linked (FR-019).** A declared external source names a component, not a
  service, and two local services may register the same component id, so linking it would be a
  guess. A called service is linked only when exactly one local service has that name. That name
  is the one the runtime itself finds a local service by, so a match is the service the call
  reached.
- **Looking at a service does not change its topology.** The local console's own reads of an
  entity or a session are not counted as calls.
- **Every component kind that runs user code records spans (FR-027).** Workflows, agents,
  autonomous agents and consumers recorded none, so they could not be named as callers. They now
  appear in traces and metrics as entities and endpoints do.
- **Traces that cross from one service to another are out of scope.** A call to another service is
  counted, and the receiving service starts its own trace.
- **SC-002 and SC-005 are restated as what is measured**: the conformance service in every language
  and the shopping cart sample for SC-002; ten thousand distinct entity ids through the real call
  path for SC-005.
- **The Topology tab refreshes every 2 seconds**, so SC-006's 3 seconds holds in the worst case.

### Session 2026-10-02 (living features)

The acceptance scenarios were moved out of this spec into Gherkin features under `features/`, in
the words of the root `GLOSSARY.md`, and each user story now names its scenarios. Three things
changed in the move:

- **Answered edge cases became scenarios**, named under the story they belong to. The Edge Cases
  section points at each one.
- **Two scenarios of User Story 4 became one.** A member reading a deployed service's topology, and
  that member being given no credential, are one situation and one action, so they are one scenario
  with two outcomes.
- **The features speak of declared connections and observed calls.** The topology document and the
  consoles draw them as *declared edges* and *call edges* between *nodes*; those are words for the
  drawing, and the requirements and contracts keep them where they describe the drawing.

### Session 2026-10-02 (decisions on the features)

- **The features are run, not transcribed.** A feature file that one suite can run whole is run by
  `GherkinSuite`, each scenario as written: declared connections, observed calls, calls to other
  services and traces by the test kit's suites, and deployed services by the control plane's. A
  scenario with no step definition fails, so none goes untested. Three files cannot be run that
  way, and their scenarios are tests named after them: reading a topology (rules that live in each
  console), every language (one row of each outline per SDK, in the conformance suite) and the
  documentation.
- **CI gates on the checker.** A `features` job runs `speckit-bdd check` with the extension's own
  config on every pull request that touches a feature, the glossary, a spec or that config, and
  fails when it read no spec or no scenario.
- **Every term is settled.** The glossary has no proposed term. Each word new to this feature was
  already decided in this spec's earlier sessions; the glossary now also says what `topology`,
  `observed call`, `window`, `handled`, `unanswered`, `undelivered` and `platform component` do
  not mean, and which words are refused for them (`edge`, `graph`, `orphan`, `unreachable`).
- **A route is data in a step, not mechanics.** A step may quote a route, as
  `"POST /carts/{cartId}/items"`, because a route is how the topology names what an endpoint's call
  came from. No step says how a request is sent.
- **`platform` is a reserved project id.** The platform's own workloads carry identities under
  `ankka://platform/…`, and port 7628 admits one of them. No tenant project may take that id.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See a local service's components and their declared connections (Priority: P1)

A developer runs a service on their machine and opens the local console. A Topology view for that
service draws every component it registered, grouped and marked by kind: event-sourced entities,
key-value entities, views, consumers, workflows, timed actions, agents, autonomous agents and HTTP
endpoints. Topics the service reads or writes appear as their own nodes. Declared connections are
drawn: an entity's events feeding each view and consumer subscribed to them, a topic feeding its
consumers and views, a consumer publishing to its topic. Choosing a component shows its kind, its
handlers and its declared connections in both directions.

**Why this priority**: It needs nothing to happen in the service first, it is exact, and it answers
"what is this service made of and what feeds what" immediately. It is a working feature with no
other story built.

**Independent Test**: Start the shopping cart sample locally, open the local console, and confirm
the topology shows the cart entity, each view and consumer fed by it with an edge from the entity,
and the endpoint, all before any request has been sent.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/topology/declared-connections.feature`: a view is connected to the event sourced entity whose events it reads
- added `features/topology/declared-connections.feature`: a consumer is connected to the topic it reads and to the topic it publishes to
- added `features/topology/declared-connections.feature`: a view of a key value entity's state is connected as a state subscription
- added `features/topology/languages.feature`: a service declares the same connections in every language
- added `features/topology/declared-connections.feature`: a service that has handled nothing shows every declared connection and no observed call
- added `features/topology/declared-connections.feature`: a source that is not one of the service's components is shown as outside the service
- added `features/topology/declared-connections.feature`: a service with only an endpoint shows the endpoint and its routes
- added `features/topology/declared-connections.feature`: a service with no endpoint shows none
- added `features/topology/reading.feature`: platform components are left out until a developer asks for them
- added `features/topology/reading.feature`: a developer who asks for platform components is shown them, marked as the platform's
- added `features/topology/reading.feature`: focusing on a component shows it and what it is connected to, and nothing else
- added `features/topology/reading.feature`: reading one kind of component leaves the other kinds out

---

### User Story 2 - See who calls whom, as observed (Priority: P1)

The developer sends a few requests to the service and looks at the topology again. Call edges now
appear alongside the declared ones, drawn differently: from the endpoint to the entity it called,
from a workflow to each entity its steps called, from an agent to the components its tools reached.
Each call edge carries how many calls its callee handled as `Ok`, `Refused` or `Failed` in the
current window, how long they took, and how many went unanswered. Choosing an edge shows the counts per handler pair,
for example `POST /{cartId}/items` → `shopping-cart#add-item`. The view states plainly that call
edges are observed in the current window and that a call not yet made is not shown.

**Why this priority**: "Who calls whom" is the half of the question the user asked that nothing
declared can answer. Together with story 1 it makes up the minimum useful feature.

**Independent Test**: With the shopping cart sample running, send one add-item request and one
checkout, and confirm a call edge appears from the endpoint to the cart for each handler with a
count of one, an outcome of `Ok`, and a duration. Then send a command the cart refuses and confirm
the edge's refused count rises and its failed count does not.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/topology/observed-calls.feature`: a call from an endpoint to an entity is attributed to the route and the handler
- added `features/topology/observed-calls.feature`: a refusal is counted as refused and not as failed
- added `features/topology/observed-calls.feature`: a handler that fails is counted as failed where it ran and as timed out where it was called
- added `features/topology/observed-calls.feature`: a call its caller stopped waiting for is counted as timed out, and as handled when the handler finishes
- added `features/topology/observed-calls.feature`: each call a workflow step makes is attributed to that step
- added `features/topology/observed-calls.feature`: a call made outside any handler comes from the unknown caller
- added `features/topology/observed-calls.feature`: a call that was not made in the window is not shown
- added `features/topology/observed-calls.feature`: calls to many entity ids are one observed call, and no entity id is shown
- added `features/topology/languages.feature`: a call is attributed to its caller in every language
- added `features/topology/observed-calls.feature`: a call to a handler the component does not declare is counted as undelivered
- added `features/topology/observed-calls.feature`: a call that reaches no instance is counted as undelivered
- added `features/topology/observed-calls.feature`: a stream is counted once, when it ends
- added `features/topology/observed-calls.feature`: a timer's call is counted when the timed action runs, not when the timer is set
- added `features/topology/observed-calls.feature`: an observed call leaves the topology when its last call leaves the window
- added `features/topology/observed-calls.feature`: a restarted service counts its observed calls from the restart
- added `features/topology/observed-calls.feature`: reading an entity through the local console is not a call
- added `features/topology/observed-calls.feature`: the unknown caller is one caller however many calls it made
- added `features/observability/traces.feature`: a workflow step is in the trace of the request that ran it
- added `features/observability/traces.feature`: an agent is in the trace of the request it answered
- added `features/observability/traces.feature`: a consumer is in the trace of the event it handled

---

### User Story 3 - See calls to other services (Priority: P2)

A service's handler calls a component of another ankka service. The topology shows the other
service as a node outside this one, with a call edge to the component and handler called and the
same counts and outcomes as an internal call. In the local console, when the other service is also
running locally, choosing that node opens the other service's topology.

**Why this priority**: A real application is several services, and the boundary between them is
where the questions are. It is not needed to understand a single service.

**Independent Test**: Run two local services, one of which calls the other, send a request that
crosses the boundary, and confirm the caller's topology shows the other service as an external node
with a counted call edge to the called component.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/topology/other-services.feature`: a call to another service is shown as a call to a service outside this one
- added `features/topology/reading.feature`: a called service running on the same machine under one name is opened from its caller's topology
- added `features/topology/reading.feature`: a called service that is not running on the developer's machine is marked as not running here
- added `features/topology/other-services.feature`: services beyond the limit are counted together as other services

---

### User Story 4 - See a deployed service's topology in the installation's console (Priority: P2)

A member of a project opens a deployed service's page in the installation's console and chooses its
topology. They see the same view as the local console: the components, their declared connections
and the calls observed across the service's running instances, combined. The view says how many
instances contributed and when the counts were read. A member who may not see the service sees
nothing of its topology, exactly as for its status.

**Why this priority**: The installation's console shows nothing of a deployed service's inside
today, and this is the first part of it. It depends on the same records as stories 1 and 2, and
adds a path to them that the control plane authorises.

**Independent Test**: Deploy the shopping cart sample with two instances to a local cluster, send
requests, open the service's page in the installation's console, and confirm the topology shows the
same components and declared edges as the local console, call counts summed across both instances,
and "2 of 2 instances".

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/topology/deployed-services.feature`: a member of a project reads the topology of a service deployed in it, and is given no credential
- added `features/topology/deployed-services.feature`: observed calls are added together across a service's instances
- added `features/topology/deployed-services.feature`: an instance that does not answer leaves the topology partial
- added `features/topology/deployed-services.feature`: an instance too old to report its topology is named as unsupported
- added `features/topology/deployed-services.feature`: a person who is not a member of the project is refused as if the service did not exist
- added `features/topology/deployed-services.feature`: a component that only some instances have is shown with the instances that have it

---

### User Story 5 - The topology is documented and the limitation is updated (Priority: P3)

The documentation for the local console and for observability describes the Topology view: the two
kinds of edge, what "observed" means and the gap it implies, the unknown caller, and the window the
counts cover. The limitations page and the observability page no longer say the installation's
console shows nothing of a deployed service's inside, once story 4 ships.

**Why this priority**: The feature is unusable if its readers take observed calls to be complete.
The docs are where that is said once and properly.

**Independent Test**: The documentation build passes, and the pages describing the topology mention
both kinds of edge, the observed-not-complete rule and the unknown caller.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/documentation/topology.feature`: the documentation of the local console says that observed calls are not every call
- added `features/documentation/topology.feature`: the documentation says that the console shows a deployed service's topology

---

### Edge Cases

Each answered case is a scenario, named here and under its user story. A case with no scenario is
stated in full.

- **A service with no components besides an endpoint.** `features/topology/declared-connections.feature`: a service with only an endpoint shows
  the endpoint and its routes.
- **A service with `"http": false`.** `features/topology/declared-connections.feature`: a service with no endpoint shows none. Calls the service
  makes are still counted.
- **Platform components.** `features/topology/reading.feature`: platform components are left out until a developer asks for them;
  a developer who asks for platform components is shown them, marked as the platform's. The
  platform's components are session memory, task and agent-instance records, the task cascade
  consumer and the session compactor.
- **A view or consumer sourced from an entity of another service.** `features/topology/declared-connections.feature`: a source that is not one
  of the service's components is shown as outside the service.
- **A streaming call** (an agent's token stream, a server-sent event stream). `features/topology/observed-calls.feature`: a stream is
  counted once, when it ends. Its duration is the stream's lifetime.
- **A call that fails before reaching the target.** `features/topology/observed-calls.feature`: a call that reaches no instance is counted
  as undelivered. It creates no node for a component that is not registered.
- **A call naming a method the component does not declare.** `features/topology/observed-calls.feature`: a call to a handler the component
  does not declare is counted as undelivered. The undeclared name itself is never recorded.
- **A component kind that recorded no spans before.** `features/observability/traces.feature`: a workflow step is in the trace of the
  request that ran it; an agent is in the trace of the request it answered; a consumer is in the
  trace of the event it handled. A request through one of them now produces more spans, so the
  recorder's fixed window covers fewer requests than it did, and time an endpoint used to show as
  unattributed while an agent waited on a model is now the agent span's unattributed time.
- **The console reading an entity or a session.** `features/topology/observed-calls.feature`: reading an entity through the local console
  is not a call.
- **A service that calls more distinct services than the limit.** `features/topology/other-services.feature`: services beyond the limit
  are counted together as other services.
- **A timed action calling its target, and a timer firing.** `features/topology/observed-calls.feature`: a timer's call is counted when
  the timed action runs, not when the timer is set.
- **The window rolls over.** `features/topology/observed-calls.feature`: an observed call leaves the topology when its last call leaves
  the window.
- **A service restarts.** `features/topology/observed-calls.feature`: a restarted service counts its observed calls from the restart.
- **A very large service.** `features/topology/reading.feature`: focusing on a component shows it and what it is connected to, and
  nothing else; reading one kind of component leaves the other kinds out.
- **An instance whose runtime predates this feature.** `features/topology/deployed-services.feature`: an instance too old to report its
  topology is named as unsupported.
- **An unknown caller with many calls.** `features/topology/observed-calls.feature`: the unknown caller is one caller however many calls
  it made. It is never split into guesses.
- **Two components with the same id in different services.** No scenario: there is no view that
  combines two services, so the case cannot arise in one topology. Each node is identified by its
  service and component, so an external node never merges with a local one of the same id.

## Requirements *(mandatory)*

### Functional Requirements

**Inventory and declared connections**

- **FR-001**: Each running service MUST describe its own topology: every registered component with
  its kind, id and handler names, and every declared connection.
- **FR-002**: Declared connections MUST include each view's and consumer's source (an entity's
  events, a key-value entity's state changes, or a topic, distinguished from one another), each
  consumer's destination topic, and each endpoint's routes.
- **FR-003**: Declared connections MUST be derived only from what components declare to the runtime
  when they register. They MUST NOT be inferred from handler code, from traffic, or from naming
  conventions.
- **FR-004**: Services written in every language the platform hosts (Scala in-process; Python,
  TypeScript and Rust through the sidecar or as a WebAssembly module) MUST describe the same
  components and declared connections for equivalent definitions.
- **FR-005**: A declared source that is not a component of the same service MUST appear as an
  external node, and MUST NOT be omitted.

**Observed calls**

- **FR-006**: The runtime MUST count every call made through the component client from one component
  to another, keyed by caller component, caller handler (or route, for an endpoint), callee component
  and callee handler.
- **FR-007**: Each counted pair MUST record the calls its callee **handled**, by `Ok`, `Refused`
  and `Failed`, with their durations summarised at least as the median, a high percentile and the
  maximum. It MUST also record the calls that went **unanswered**, by `TimedOut` and `Undelivered`
  (the handler never ran). The two groups MUST be presented as separate viewpoints and never summed
  into one total.
- **FR-008**: A refused call (a handler deciding to say no) MUST be counted as `Refused` and MUST NOT
  be counted or displayed as a failure.
- **FR-009**: When the caller of a call cannot be determined, the call MUST be counted from an
  *unknown caller* and MUST NOT be attributed to any registered component or handler.
- **FR-010**: Entity ids, session ids, request paths with their parameters filled in, and any other
  unbounded value MUST NOT be a key or a value of the call counts. The memory the counts use MUST be
  bounded by the number of registered components and handlers, not by traffic or uptime.
- **FR-011**: Counts MUST cover a recent window with fixed memory, in line with the span recorder's
  window. The service MUST report what the window currently covers (its time span and number of
  calls).
- **FR-012**: Counting a call MUST add no more than a negligible cost to it, measured against a real
  service call (request in, component, journal, reply), consistent with the cost of recording a span.
- **FR-013**: Calls to a component of another service MUST be counted with the other service named,
  and shown as calls to an external node.
- **FR-027**: Every component kind that runs user code MUST record its invocations as spans:
  a workflow's commands and steps, an agent's interactions, an autonomous agent's iterations and
  operations, and a consumer's messages, as entities, endpoints, views and timed actions already do.
  They MUST appear in traces and in the invocation metrics. The span names and metric labels that
  existed before this feature MUST NOT change.

**Local console (phase 1)**

- **FR-014**: The local console MUST offer a Topology view for each service it lists, drawing
  components as nodes marked by kind, topics as nodes, declared connections as edges, and observed
  calls as visibly different edges.
- **FR-015**: The view MUST state, wherever call edges are shown, that they are observed in the
  current window and are not a complete list of the calls the code can make.
- **FR-016**: Choosing a node MUST show its kind, its handlers, its declared connections in both
  directions and its observed calls in both directions. Choosing a call edge MUST show its counts per
  handler pair, by outcome, with durations.
- **FR-017**: The view MUST update as new calls are observed without the developer reloading the
  page, at least as often as the console's existing live views.
- **FR-018**: The view MUST let the developer filter by component kind, focus on one component and
  its neighbours, and show or hide platform components (hidden by default).
- **FR-019**: When another service named by a call is also running locally, the console MUST link
  to that service's topology. A link is offered only when exactly one listed service has that name.
  An external source names a component, not a service, and is never linked.
- **FR-020**: The topology MUST be read-only. Nothing in it changes a service's state or sends a
  request to a component. Reading a service through the local console (an entity query, a session)
  MUST NOT be counted as a call and MUST NOT change the topology.

**Installation's console (phase 2)**

- **FR-021**: A member allowed to see a deployed service MUST be able to see its topology in the
  installation's console. Anyone else MUST be refused exactly as for the service's status, revealing
  nothing about its existence.
- **FR-022**: The topology of a deployed service MUST be read from its running instances and served
  to the console only by the control plane, acting on the member's request. The browser MUST NOT
  receive any credential for the service, its instances or the cluster.
- **FR-023**: Observed calls MUST be combined across the instances that answered. The view MUST say
  how many instances contributed out of how many are running and when they were read, and MUST mark
  the topology partial when any instance did not answer, naming those instances.
- **FR-024**: When instances disagree about components or declared connections (for example during
  a rolling update), the view MUST show the difference and MUST NOT merge it silently.
- **FR-025**: The control plane MUST be able to answer the topology route through the same
  command-line client every other route has, so the information is available without a browser.

**Documentation**

- **FR-026**: The documentation MUST describe the Topology view, both kinds of edge, the
  observed-not-complete rule, the unknown caller and the counting window. Once phase 2 ships, the
  observability and limitations pages MUST be updated to match.

### Key Entities *(each a term in the project glossary)*

- **Topology**: what one service, or one instance of it, says about itself at a moment: its
  components and topics, its declared connections, its observed calls, the window the observations
  cover, and (for a deployed service) which instances contributed.
- **Component**: a component of this service, with its kind, its id, its handlers and whether it is
  a platform component. The topology also shows topics, components and services outside this one,
  and the unknown caller. The topology document and the consoles draw all of these as *nodes*.
- **Declared connection**: a connection a component declared at registration: an event
  subscription, a state subscription, a topic subscription or a topic publication. Exact and
  complete. Drawn as a *declared edge*.
- **Observed call**: a caller and what it called, at handler granularity, with how many calls were
  handled (ok, refused, failed), how many went unanswered (timed out, undelivered), how long they
  took, and whether they were streams. Observed, so possibly incomplete. Drawn as a *call edge*.
- **Window**: the period and number of calls the observed calls currently cover, and whether that
  is limited by the service's start.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer who has never seen the shopping cart sample can, from its topology alone
  and within two minutes, name every component that consumes the cart's events and every component
  that calls the cart.
- **SC-002**: For the conformance service in every language (Scala, Python, TypeScript, and Rust
  as a WebAssembly module) and for the shopping cart sample, the declared edges in the topology are
  exactly the declared sources and destinations: none missing and none extra.
- **SC-003**: After a scripted set of requests to a sample, every observed call edge's handled
  counts by outcome, and its unanswered counts by outcome, each equal exactly what the script caused
  for that pair. No edge appears that the
  script did not cause, and no entity id appears anywhere in the topology.
- **SC-004**: Counting calls adds less than 1% to the duration of a real service call, measured
  end to end through HTTP, a component and the journal.
- **SC-005**: The memory the counts use does not depend on how many distinct entity ids have been
  called: after ten thousand calls to ten thousand distinct ids, the counts hold exactly the entries
  they held after the first call.
- **SC-006**: The local topology reflects a newly made call within 3 seconds.
- **SC-007**: For a deployed service of three instances, the installation's console shows its
  topology within 5 seconds of being asked, with counts equal to the sum of the instances' own.
- **SC-008**: Every scenario this spec names is run, and fails when the behaviour it describes is
  removed. A feature file one suite can run whole is run as written, each scenario a test; a
  scenario no such suite can reach is a test named after it, and something fails when a scenario
  has no test.

## Assumptions

- The local console's existing discovery of services, its loopback observability endpoint and its
  live-update mechanism are reused. The topology is a new view in the same console, not a new tool.
- The span recorder's window semantics (fixed memory, oldest overwritten) are the model for the call
  counts. The exact window shape (by time, by count, or both) is a planning decision, constrained by
  FR-010 and FR-011.
- Every cross-component call passes one place in the runtime, so counting there sees all of them.
  Calls from a sidecar-hosted or WebAssembly service enter the runtime there too. Whether the calling
  component of such a call can always be determined is a planning question; FR-009 settles what
  happens when it cannot.
- Platform components are hidden by default because they are the platform's implementation, not the
  developer's design, but they are never removed from the data.
- In phase 2, the control plane needs a permitted network path to each instance's observability
  records. The zero-trust overlay (mutual TLS on every port, network policies naming who may connect)
  does not allow that path today, so phase 2 includes granting it as narrowly as possible: the
  control plane reads topology and nothing else, and no workload gains a new way to reach another.
- Phase 2 combines instances by reading each one when asked. Nothing is stored, and the topology
  shown is as current as the read.
- A deployed service's traces, sessions and entity state remain out of the installation's console.
  Only the topology is added.

## Out of Scope

- Static analysis of handler code, in any language, to find calls before they happen.
- A manifest written at build or publish time and stored with an image or a descriptor.
- Components declaring whom they call, and the runtime refusing undeclared calls. That is a separate
  decision about the programming model.
- History. Counts cover the current window and disappear on restart; there is no stored record.
- Topology across a whole project or installation drawn as one graph. Each service's topology links
  to the others it names, but there is no combined view.
- Traces, sessions or entity state of a deployed service in the installation's console.
- Exporting the topology to an external monitoring or diagramming system.
- Traces that cross from one service to another. A call to another service is counted; the receiving
  service starts its own trace.
