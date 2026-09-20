# Feature Specification: Seeing What a Service Is Doing

**Feature Branch**: `007-observability-console`

**Created**: 2026-09-19

**Status**: Draft

**Input**: User description: "the observability issue - let's also take in to account that akka (who we are copying) provide a local console (https://doc.akka.io/getting-started/author-your-first-service.html#_explore_the_local_console) that it would be helpful to imitate"

## Context

The platform can now be used by someone outside this repository: they expand a template, resolve
the published libraries, build an image, deploy it through the CLI, expose it and call it by
hostname. What it cannot do is tell them what their service is *doing*.

The whole diagnostic surface today is `ankka services get`, which returns a lifecycle word and a
detail string. There is no way to see a request, a handler, a persisted event, an agent's
reasoning or a log line. Searching the repository for metrics, tracing, or any observability
vocabulary returns nothing — in the code, in `README.md`, or in `CLAUDE.md`. It is not in
`README.md`'s "Not implemented" list either, which is otherwise a careful account of what is
missing. This is the one significant gap that has never been examined rather than deliberately
deferred.

Two things make it more pressing than a generic "add metrics" chore:

- **The runtime already sees everything.** Effects are inert data and the runtime interprets
  them, so every component invocation already passes through one place. The information exists;
  nothing captures it.
- **Agent cost is computed and thrown away.** `AgentLoop` and `AnthropicProvider` already count
  tokens per model call. For a platform whose purpose is agentic AI, that is the single number a
  developer most wants to see, and it currently reaches nobody.

Akka, whose component model this reimplements, ships a **local console**: `akka local console`
serves a web UI that lists running services, browses their components, invokes an endpoint from
the browser, inspects entity and session-memory state, and shows a request's trace with
per-component timings — in their example revealing that 99.9% of a request was spent waiting on
the model rather than in the framework. Imitating it is the explicit goal of this feature.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See a local service, and what happens inside a request (Priority: P1)

A developer has expanded the template and is running their service on their own machine. They
start a console with one command and open it in a browser. It lists every ankka service running
on the machine with its state and address. They pick theirs, see its registered components
grouped by kind — entities, views, workflows, endpoints, agents — and click into the HTTP
endpoint. Rather than switching to `curl`, they fill in the path, query parameters and body in the
browser and send the request. The response comes back, and beside it a trace: which components
were invoked, in what order, how long each took, and where the time actually went. When the
service has an agent, they can open a session and read its memory — the conversation as the
platform stored it — and see the tokens and cost that session has consumed.

**Why this priority**: It is the developer's inner loop, it needs no cluster, no credentials and
no deployment, and it is the part Akka has that this platform does not. It converts the platform
from something that runs a service into something that explains it. Every later story is a
variation on data this story establishes.

**Independent Test**: Run the shopping cart sample locally, start the console, and complete a
whole diagnosis without a terminal: find the service, invoke `POST /carts/{id}/items`, read the
response, open the trace, and see the entity's persisted state change.

**Acceptance Scenarios**:

1. **Given** no ankka service is running, **When** the developer starts the console, **Then** it
   opens and reports that no services were found, rather than failing.
2. **Given** the console is open and a service is running, **When** a second service starts,
   **Then** it appears in the console without the console being restarted.
3. **Given** a service with an HTTP endpoint, **When** the developer invokes a route from the
   console, **Then** the request reaches the service exactly as an external caller's would, and
   the response — status, headers and body — is shown.
4. **Given** a request that spans an endpoint, an entity and a view, **When** the developer opens
   its trace, **Then** every component involved is listed in call order with its own duration, and
   the time unaccounted for by the platform is visible rather than hidden.
5. **Given** an agent that has held a conversation, **When** the developer opens that session,
   **Then** the stored messages are shown, together with the tokens consumed and the cost.
6. **Given** a handler that returned an error, **When** the developer opens its trace, **Then**
   the failure and the component that produced it are identified.
7. **Given** an endpoint whose ACL denies the caller, **When** it is invoked from the console,
   **Then** it is refused exactly as it would be for any other caller — the console is not a way
   around an ACL.

---

### User Story 2 - Read a deployed service's logs (Priority: P2)

A developer has deployed a service and it is not behaving. They ask the CLI for its logs and get
them: recent output by default, or a live stream while they reproduce the problem. They never
need cluster credentials, a `kubectl` context, or knowledge of which pod is which — the same
token and URL that let them deploy the service let them read what it printed. When a service runs
several instances, the output identifies which one produced each line, and they can ask for one
instance in particular. When a service has crashed and restarted, they can read what the previous
container said before it died, which is usually where the answer is.

**Why this priority**: It is the second question anyone asks and the first one that cannot be
answered locally. Today the only recourse is `kubectl logs`, which requires credentials the CLI
deliberately does not carry and defeats the abstraction the platform exists to provide. It is
second rather than first because it needs a cluster to demonstrate, while P1 needs nothing.

**Independent Test**: Deploy the sample to the local cluster, make a request that logs, and
retrieve that line through the CLI alone — with no kubeconfig on the path — then restart the
service and read the previous instance's output.

**Acceptance Scenarios**:

1. **Given** a running service, **When** the developer asks for its logs, **Then** recent output
   is printed and the command exits.
2. **Given** a running service, **When** the developer asks to follow its logs, **Then** new lines
   appear as they are produced until interrupted.
3. **Given** a service with several instances, **When** logs are requested, **Then** each line
   identifies its instance, and a single instance can be selected.
4. **Given** a service whose container has restarted, **When** the previous container's logs are
   requested, **Then** the output from before the restart is returned.
5. **Given** a paused service with no running instance, **When** logs are requested, **Then** the
   CLI says so plainly rather than hanging or returning an empty success.
6. **Given** a service in another project, **When** logs are requested without rights to that
   project, **Then** the request is refused by the same rules that govern every other command.
7. **Given** the console is not running and has never been started, **When** logs are requested,
   **Then** they are returned — the two surfaces are independent.

---

### User Story 3 - See cost and behaviour of a deployed service (Priority: P3)

A developer whose service is deployed wants the same numbers the console showed them locally:
how many requests, how long they took, how often they failed, and — for agents — how many tokens
and how much money. The platform exposes these in the form a monitoring system expects, so an
installation that already runs a metrics stack can scrape them, chart them and alert on them
without the platform having to build charts or alerts of its own.

**Why this priority**: It is the production counterpart of P1 and the thing that makes agent cost
controllable rather than merely visible after the fact. It is third because it depends on the
instrumentation P1 establishes, and because an installation without a monitoring stack gains
nothing from it until one exists.

**Independent Test**: Deploy a service, drive traffic at it, and confirm that request counts,
durations, failures and agent token totals are retrievable from the running instance in a
standard scrapeable form, with values that match what was driven.

**Acceptance Scenarios**:

1. **Given** a deployed service handling requests, **When** its metrics are read, **Then**
   request counts and durations are present, broken down by component and handler.
2. **Given** a deployed service with an agent, **When** its metrics are read, **Then** token
   counts and cost are present, attributable to a model.
3. **Given** a service that has just started and served nothing, **When** its metrics are read,
   **Then** it responds with zeroed series rather than an error.

---

### Edge Cases

- **A request that fans out to another component.** `ComponentClient` calls another entity, which
  may call a third. The trace must show the nesting rather than a flat list, or the timings mislead.
- **Work handed to another thread.** The platform's request context is a thread-local, sound
  because one request owns one virtual thread. Work given to a different thread cannot see it, so
  a trace may legitimately lose correlation — that must be visible as a gap, never as a silent
  reattribution to the wrong request.
- **Recording outgrows memory.** Traces are kept in memory and a busy service produces them
  faster than anyone reads them. The oldest must be discarded on a bound, and the console must be
  honest that it is showing a window rather than a history.
- **A streaming response.** An agent endpoint streams tokens over SSE. The invoke panel must show
  a stream as it arrives rather than waiting for an end that may be far away, and its trace must
  record when the first token arrived as well as the last.
- **A model call that failed.** Token accounting must attribute a failed or partial call rather
  than dropping it, or cost silently under-reports exactly when something is going wrong.
- **A service that exits while the console is open.** The console must drop it rather than
  showing a dead entry that errors on click.
- **Two services, one machine.** The dashboard lists several; ports must not collide and one
  service's data must never be attributed to another.
- **Sensitive content.** Traces, entity state and session memory contain whatever the application
  put there — prompts, personal data, credentials passed as arguments. What is shown locally and
  what leaves the cluster are different risks and must be treated differently.
- **An entity that has never been created.** Asking the console for its state must report absence,
  not fabricate an empty state that looks real.
- **Logs larger than anyone wants.** A service that has logged for a week cannot be returned in
  full; a default window and an explicit way to widen it are required.

## Requirements *(mandatory)*

### Functional Requirements

**Instrumentation — the data everything else reads**

- **FR-001**: The runtime MUST record every component invocation it interprets, capturing the
  component, the handler's wire name, when it started, how long it took, and whether it succeeded.
- **FR-002**: Invocations belonging to one inbound request MUST be linked to that request and to
  their calling invocation, so that nesting and order are recoverable.
- **FR-003**: Recording MUST NOT change the observable behaviour of any handler: the same inputs
  produce the same effects, replies and persisted events whether or not anyone is watching.
- **FR-004**: Recorded data MUST be held in memory, bounded, and discarded oldest-first when the
  bound is reached. It does not survive a restart and is not persisted anywhere.
- **FR-005**: Instrumentation MUST be always on, with no configuration required to enable it. An
  off-switch is explicitly future work, and the cost of always-on is bounded by SC-003.
- **FR-006**: Where a model is called, the runtime MUST record the tokens consumed and the cost
  attributable to that call, including for calls that fail part-way.
- **FR-007**: A service MUST expose its own recorded data, its component inventory and its
  identity over its existing management surface, so that reading it needs no new port and no new
  credential.

**The local console (P1)**

- **FR-008**: A developer MUST be able to start the console with a single command and be told the
  address to open.
- **FR-009**: The console MUST list every ankka service running on the developer's machine, with
  each service's name, state and address, and MUST reflect services starting and stopping while it
  is open.
- **FR-010**: The console MUST show a service's registered components grouped by kind.
- **FR-011**: The console MUST let a developer invoke an HTTP endpoint from the browser, supplying
  path parameters, query parameters, headers and a body, and MUST show the full response.
- **FR-012**: An endpoint invoked from the console MUST be subject to the same ACL as any other
  caller. The console MUST NOT provide a way to reach a handler that an external caller could not.
- **FR-013**: The console MUST show the trace of a request: every component invoked, in call
  order and nesting, each with its own duration, and the portion of elapsed time the platform
  cannot account for shown as such.
- **FR-014**: The console MUST show the current state of an entity identified by id, by running a
  handler the component itself declared as a **query**, and MUST refuse to run a command.
  *Corrected during implementation, twice.* It first required a plain report "when no such entity
  exists": the platform has no such state — an id that has never been used answers with
  `emptyState`, which is the defined answer rather than a missing one, so the requirement asked for
  a distinction nothing could truthfully draw. It then left open *which* handlers a read-only
  console may call; serving the component's own declared queries is the answer, because `query`
  accepts only a `ReadOnlyEffect` and so "this cannot persist" is already enforced by the compiler.
  The console reads nothing a component did not publish about itself — in particular it does not
  read the journal or durable-state tables directly, which would be a larger grant than inspection
  needs.
- **FR-015**: The console MUST show an agent session's stored memory as the platform holds it.
- **FR-016**: The console MUST show tokens consumed and cost, per agent session and in total for
  the service.
- **FR-017**: The console MUST make clear that it shows a bounded recent window rather than a
  complete history.
- **FR-018**: The console MUST serve only the developer's own machine.

**Deployed services (P2, P3)**

- **FR-019**: A developer MUST be able to retrieve a deployed service's recent log output through
  the CLI, in one command, using only the configuration that already lets them deploy it.
- **FR-020**: The CLI MUST be able to follow a deployed service's logs as a live stream until
  interrupted.
- **FR-021**: Log output MUST identify which instance produced each line where a service runs
  more than one, and MUST allow a single instance to be selected.
- **FR-022**: The CLI MUST be able to return the output of a service's previous container after a
  restart.
- **FR-023**: Retrieving logs MUST NOT require the platform to weaken the separation that keeps
  the control plane unable to create or alter a workload. Which component holds the right to read
  a pod's output, and how that right is scoped to the requester's project, is a design decision
  for the plan, not an assumption of this spec.
- **FR-024**: Log retrieval MUST work with the console never having been started; the console and
  the CLI are independent surfaces over the same platform.
- **FR-025**: A request for logs from a service with no running instance MUST say so explicitly
  rather than returning empty output or hanging.
- **FR-026**: A deployed service MUST expose request counts, durations and failure counts by
  component and handler, and agent token and cost totals by model, in a form a standard monitoring
  system can collect without the platform providing one.

### Key Entities

- **Invocation**: one component handler running once — which component, which handler, when it
  began, how long it took, whether it succeeded, and which invocation called it.
- **Trace**: one inbound request and the tree of invocations it caused, with the elapsed time the
  platform can and cannot account for.
- **Model usage**: tokens in, tokens out and cost for a single model call, attributed to a
  session, an agent and a model — recorded even when the call failed.
- **Running service**: an ankka process on the developer's machine that the console can find —
  its name, its management address and whether it is still alive.
- **Component inventory**: what a service registered at startup, grouped by kind, which is what
  the console offers to browse and invoke.
- **Log line**: output from one instance of a deployed service, with the instance's identity and
  whether it came from the current container or a previous one.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer with a service running locally can reach a working console and invoke
  one of their own endpoints from it within 2 minutes, without reading anything beyond the command
  the platform printed.
- **SC-002**: For a request that calls an endpoint, an entity and a view, the trace accounts for
  at least 95% of the request's measured wall-clock time, and names the component responsible for
  the largest share.
- **SC-003**: A service's throughput and median latency with instrumentation always on are within
  5% of the same service and workload measured without it.
- **SC-004**: Memory held by recorded traces stops growing once the bound is reached: a service
  driven with 100,000 requests holds no more trace memory than one driven with 10,000.
- **SC-005**: An agent session's token count and cost shown in the console match the provider's
  own reported usage for the same conversation, within rounding.
- **SC-006**: A developer can retrieve a specific line their deployed service logged, in one CLI
  command, on a machine with no kubeconfig and no cluster credentials configured.
- **SC-007**: A service that starts after the console is open appears in it within 5 seconds, and
  one that exits disappears within 5 seconds.
- **SC-008**: Every existing suite still passes, and the samples still run unchanged: nothing
  about writing a component changes because it is now observed.

## Assumptions

These were settled with the user before writing, and are choices rather than discoveries:

- **The console is for local development only** *in what it delivers*. It serves services running
  on the developer's own machine. Deployed services are served by the CLI (P2) and by metrics (P3).
  `README.md`'s "No console — the CLI is the only client" gap stays open deliberately; a console
  over a deployed installation is a feature of its own and is not this one.
- **But the shapes it reads are deliberately not local-shaped.** A console over a deployed
  installation was weighed and deferred, not dismissed, so this feature pays the small cost of
  leaving the door open: the data the console consumes is defined once and independently of where
  it came from, and a service is modelled as having one *or more* instances even though a local
  service always has exactly one. The expensive half of a deployed console — one trace whose spans
  are split across several pods, each with its own bounded window — is out of scope here, and is
  the reason that console is its own feature rather than a flag on this one. What is in scope is
  not making it a rewrite.
- **Everything Akka's console shows, this one shows**: running services, component browser,
  endpoint invocation, entity and session-memory inspection, and request traces with per-component
  timings. Agent cost is added, because this platform computes it and Akka's example does not
  display it.
- **Trace data is ephemeral.** In memory, bounded, gone on restart. No storage engine, no
  retention policy, no query language.
- **Instrumentation is always on.** No sampling, no enable flag, for now. SC-003 is what keeps
  that honest; if it cannot be met, the decision is revisited rather than quietly sampled.
- **`ankka services logs` is part of this feature and runs independently of the console.** Neither
  requires the other to be present or running.
- **The console binds loopback and carries no authentication**, because it reaches only services
  on the same machine and holds no credential of its own. This is the reason it is local-only.
- **Metrics are exposed for collection, not pushed**, and the platform ships no dashboards,
  alerts or storage — an installation brings its own monitoring stack or gets nothing from P3.
- **The runtime is the instrumentation point**, because effects are inert data that the runtime
  interprets, so every invocation already passes through it. No component author writes
  instrumentation, and no existing component changes.
- **How the console finds running services** — registration by the runtime, a well-known
  location, or discovery — is a plan decision. The requirement is FR-009's behaviour, not a
  mechanism.
- **Cost figures depend on prices the platform does not own.** Token counts come from the
  provider; converting them to money needs a price the platform is told, and a wrong or missing
  price must show as unknown cost rather than a confidently wrong number.
