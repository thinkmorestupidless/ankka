# Feature Specification: Go SDK

**Feature Branch**: `059-go-sdk`

**Created**: 2026-10-23

**Status**: Draft

**Input**: User description: "Support for Go as a language for writing ankka services: a Go SDK
beside the Python, TypeScript and Rust ones"

## Context

ankka hosts a service written in a language other than Scala in one of two ways. A **process** runs
beside the platform's own program, the sidecar, and the two speak a gRPC protocol over the pod's
loopback; the sidecar owns everything durable and distributed and the process owns the decisions.
A **module** is a WebAssembly build of the service that the runtime loads into its own process and
speaks the same messages to across linear memory. Feature 009 built the process model and shipped
Python; feature 013 proved a second language, TypeScript, could join it without the platform
changing; feature 016 added the module route and shipped Rust as its first guest. Between them they
wrote down what compatible means: the protocol in `protocol/`, the encoding in
`protocol/ENCODING.md` with its fixtures, and the conformance suite that drives a reference service
from the sidecar's side and names each of the behaviours it checks. The platform never learns which
language is on the other end. A new language is a project in `sdks/`, not a platform change.

This feature adds Go: the fourth language SDK, and the third hosted as a process. The audience is
teams whose backend services are already Go, and the people who run infrastructure in it: they want
one static binary in a near-empty image, goroutines and a `context` for anything that waits, errors
returned rather than thrown, the standard toolchain and its debugger, and no code generated from
their own code. They are not the agents audience that chose Python nor the Node audience of
TypeScript; they are used to building large systems from small, explicit parts, and the component
model's structure, which other audiences have to be sold, is what they would expect a framework to
impose. A service written with this SDK must read like ordinary Go. Because a Go developer's first
contact is a getting-started page, the template or a coding agent holding an ankka skill, the
documentation is part of the feature, not an appendix to it.

Two things about the language shape the work:

- **Go has distinct integer and floating types, no sum types and no compile-time derivation.** The
  first is a gift: an integer and a double are told apart by the developer's own types, and a 64-bit
  integer is carried without loss by nature. The other two are the cost: a value's codec is built
  from the developer's struct types and their tags at registration, a sum type is an interface whose
  cases the developer registers under their discriminators, and the standard library's conventions
  for JSON are followed only where they agree with the platform's encoding. Where they do not (every
  field is written, an absent optional is `null`, a missing required field is a refusal rather than a
  zero value, a double that is whole is written as a double, bytes and dates have their own
  renderings) the SDK's codec decides, and a type the codec cannot render is refused at registration
  and in the component test kit, never rendered differently.
- **Go's idiom is a process, and its type system is enough for the guarantees that matter.** A
  query handler is declared to return a read-only effect, so a query that would persist does not
  compile, as in Scala and TypeScript. Generics give typed handlers, a typed client and typed test
  kits without code generation or reflection over the developer's handlers. A handler returns an
  effect and an error; a panic inside a handler is recovered into that request's failure and never
  stops the process. A route can stream its reply and a socket route can hold a conversation, as a
  process may and a module may not.

**Go is hosted as a process, not as a module.** Feature 016 named a Go guest library as its
successor, and the spike behind it showed the ABI is language-neutral. Three things decide
otherwise for the SDK itself: Go reaches the module format only through a second compiler with a
partial standard library, which would make the SDK a dialect of the language rather than the
language; that same spike measured the Go guest's codec an order of magnitude slower than Rust's,
which removes most of what skipping the loopback hop gains; and the audience wants the standard
toolchain, the debugger, streaming routes and sockets, which a process keeps and a module gives up.
A module build of the same component model remains possible later, as the module route was designed
to allow, and is out of scope here.

The platform's promises hold unchanged. A Go service is deployed with the same descriptor, reaches
`Ready` the same way, scales, restarts, pauses and is exposed with the same commands and status
words, and its journal is readable by the same service written in any other supported language.
The conformance suite, which has grown from feature 009's cases to every behaviour the current
protocol version carries (sockets, calls to other services, secrets, client keys and MCP endpoints,
the storage client, recurring timers, workflow sources, streamed view queries, awaited workflows,
approvals, grants and replication), is the definition the SDK is held to, whole.

## Clarifications

### Session 2026-10-23

- Q: How is a Go service hosted: as a process over the sidecar protocol, as a WebAssembly guest, or both? → A: As a process over the sidecar protocol, as Python and TypeScript are. A module build stays a later feature.
- Q: How does a developer declare the shape of a value that crosses the protocol? → A: With ordinary struct types and the SDK's tags, read by reflection once at registration; a sum type's cases are registered under their discriminators; a type the codec cannot render is refused at registration and in the component test kit. No schema values written beside the types, no generated code.
- Q: Where is the SDK published, which decides its import path and how a release reaches it? → A: In a repository of its own, `thinkmorestupidless/ankka-go`, produced from `sdks/go` by the release as the template, the action and the formula are, and tagged at the release's version; the Go package registry serves it from there under that repository's path. Not a nested module of this repository, and no vanity path.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An entity in Go (Priority: P1)

A Go developer writes an event sourced entity and an HTTP endpoint in Go, using the SDK. They
declare the entity's state, events, commands and replies as ordinary Go struct types with the SDK's
tags, register the event cases under their discriminators, declare the command and query handlers
with their wire names, and say how an event folds into state. They start the sidecar and a database
with the one command the documentation gives, run their service with the Go toolchain they already
have and no code generation, and command the entity over HTTP. Its state survives the sidecar
restarting, and it lives in the same journal records a Scala, Python, TypeScript or Rust service
writes.

**Why this priority**: Until one entity written in Go can be commanded, persisted, recovered and
replayed through the sidecar, nothing else in this feature can be tested. It proves the two hard
parts, the codec against the platform's encoding and the per-instance conversation, before anything
is built on them.

**Independent Test**: Write the shopping cart's entity and endpoint in Go. Start the sidecar beside
it with the bundled Postgres, add items over HTTP, restart the sidecar, read the cart back. Run the
encoding fixtures through the SDK's default codec. Run the conformance suite's event sourced and
discovery behaviours against it.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- changed `features/polyglot/entities.feature`: a command runs its handler in the developer's code and records the events it names
- changed `features/polyglot/entities.feature`: a refused command records nothing and its caller is told the refusal
- changed `features/polyglot/entities.feature`: a handler that fails records nothing and the service goes on serving
- changed `features/polyglot/entities.feature`: a restarted entity is recovered through the developer's code before its next command
- changed `features/polyglot/entities.feature`: a snapshot is the developer's code's own, and recovery reads only the events after it
- changed `features/polyglot/entities.feature`: a service written in one language recovers what a service written in another recorded
- changed `features/polyglot/entities.feature`: a query that would record an event is refused before the service is built
- changed `features/polyglot/encoding.feature`: every fixture is read and written again exactly by an SDK
- changed `features/polyglot/starting.feature`: a service is not ready until its process has declared its components
- changed `features/polyglot/starting.feature`: a service whose code declares what the platform cannot host does not start
- changed `features/polyglot/starting.feature`: a service whose code declares several problems is told every one at once
- changed `features/polyglot/starting.feature`: a service whose code speaks a protocol version the platform does not is refused, naming both

---

### User Story 2 - Tested without and with the sidecar (Priority: P2)

The developer tests their components with the test runner Go ships with. A component test kit runs
one component with no sidecar, no database and no network, in milliseconds, and still carries every
value through the component's own codecs, so a type the codec cannot render fails there. A test kit
starts Postgres and the real sidecar image in Docker, serves the developer's components to it, and
can replace the sidecar against the same database so a test proves durability rather than caching.

**Why this priority**: The audience trusts what it can test with `go test` and nothing else
installed. A component test kit that shows an effect as a value, and a test kit that proves the
whole thing against the real runtime, are how they come to trust the paradigm. Both are also what
the shopping cart port and every later story test themselves with.

**Independent Test**: The shopping cart's unit tests pass with no Docker running; its integration
tests start the sidecar, exercise every route, restart the sidecar and read the state back. Both run
under the standard test command with no third-party runner.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- changed `features/polyglot/testing.feature`: the component test kit shows a command's effect as values
- changed `features/polyglot/testing.feature`: a value the encoding cannot write fails in the component test kit
- changed `features/polyglot/testing.feature`: the component test kit follows a workflow to where it waits for a command
- changed `features/polyglot/testing.feature`: the test kit is ready before the test begins
- changed `features/polyglot/testing.feature`: the test kit restarts the platform's own program on the same database
- changed `features/workflow-sources/languages.feature`: the component test kit hands a view a workflow change in every language
- changed `features/graph-deltas/testing.feature`: a test reads back the elements a consumer published for a change
- changed `features/graph-deltas/testing.feature`: a test reads back every message a consumer published and the key each named

---

### User Story 3 - Every component kind and every behaviour of the protocol, proven compatible (Priority: P3)

The developer builds the rest of a service in Go: key value entities, views of one or several
sources including workflows, consumers that publish, timed actions and recurring timers, workflows
whose steps call other components through the SDK's client and whose callers can wait for them to
end, agents whose tools and guardrails run in their process while the sidecar runs the loop,
autonomous agents, an MCP endpoint with client keys, socket routes, calls to other services, the
secret store and the storage client. An SDK author runs the platform's conformance suite against
the SDK's reference service and it passes every case, so the SDK is compatible with every sidecar
that speaks its protocol version.

**Why this priority**: A platform that hosts entities in Go but workflows, sockets or the storage
client only in other languages is not a Go SDK. The conformance suite is the platform's definition
of compatible and its reference needs every kind and every behaviour, so this story is where the
feature becomes an SDK rather than a demonstration. Every living feature that lists the languages a
behaviour holds in gains Go here, so the platform's own definition of each behaviour is the Go
SDK's too.

**Independent Test**: The reference service in Go declares the same components, wire names and
routes as the Scala reference. The conformance suite runs against it and passes every behaviour.
Break one behaviour deliberately and the suite names it and no other.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- changed `features/polyglot/components.feature`: a key value entity's state is kept and recovered after a restart
- changed `features/polyglot/components.feature`: a view's rows follow its source's events and are removed with its source
- changed `features/polyglot/components.feature`: a consumer is given each event at least once and in the order its entity recorded them
- changed `features/polyglot/components.feature`: a consumer publishes the messages its handler produces
- changed `features/polyglot/components.feature`: a timer set by a handler runs its timed action, and again after a failure
- changed `features/polyglot/components.feature`: a timer due while the process is not running runs once it is
- changed `features/polyglot/components.feature`: a workflow's steps run in the developer's code and each step is recorded
- changed `features/polyglot/components.feature`: a step that fails is retried and then failed over as the workflow declared
- changed `features/polyglot/components.feature`: a command to a workflow during a step is answered from the state before the step
- changed `features/polyglot/components.feature`: a call through the SDK's client reaches its component wherever it runs
- changed `features/polyglot/components.feature`: a call made by a handler is nested under that handler in the trace
- changed `features/polyglot/agents.feature`: a tool runs in the developer's code with the model's arguments
- changed `features/polyglot/agents.feature`: a guardrail is consulted in the developer's code
- changed `features/polyglot/agents.feature`: an agent's reply reaches its caller as a stream while the model produces it
- changed `features/polyglot/agents.feature`: a session survives a restart of the developer's code
- changed `features/polyglot/agents.feature`: a tool that fails is told to the model and the loop goes on
- changed `features/polyglot/conformance.feature`: the conformance suite passes against the reference service in every language
- changed `features/polyglot/conformance.feature`: an SDK's MCP endpoint answers as the Scala reference does
- changed `features/polyglot/conformance.feature`: a behaviour broken in an SDK is named and no other
- changed `features/agents/languages.feature`: a tool that requires approval waits for a decision in every language
- changed `features/agents/languages.feature`: an approved tool call runs once after a restart in every language
- changed `features/agents/languages.feature`: an autonomous agent's wait for a decision uses none of the task's budget in every language
- changed `features/agents/languages.feature`: a tool's call to another service is admitted in every language
- changed `features/agents/languages.feature`: an MCP server's tools are offered to the model in every language
- changed `features/agents/languages.feature`: a result guardrail keeps an MCP server's result from the model in every language
- changed `features/agents/model-providers.feature`: an agent naming the model provider openai is answered by it in every language
- changed `features/autonomous-agents/languages.feature`: an endpoint of a process watches an agent instance's notifications
- changed `features/awaiting-workflows/languages.feature`: a handler sends a command and is answered with the state in every language
- changed `features/awaiting-workflows/languages.feature`: a handler that waits for a workflow that fails is answered with the failure in every language
- changed `features/endpoint-acls/route-acls.feature`: a route that states no ACL answers to its endpoint's, in every language
- changed `features/endpoint-acls/route-acls.feature`: a route's own ACL applies to that route alone, in every language
- changed `features/endpoint-acls/stating-an-acl.feature`: an endpoint that states no ACL is refused at registration
- changed `features/endpoint-acls/stating-an-acl.feature`: an endpoint that states it allows all serves everyone
- changed `features/erasure/languages.feature`: an SDK writes the same personal envelope and reads it back into its own personal type
- changed `features/graph-deltas/store.feature`: the same consumer written in another language fills the store the same way
- changed `features/mcp-endpoints/languages.feature`: a Python, TypeScript or Rust service declares an MCP endpoint and the platform answers the protocol for it
- changed `features/mcp-endpoints/languages.feature`: a service's own code is asked to run a tool with typed arguments and the principal, and never sees the credential
- changed `features/mcp-endpoints/languages.feature`: a service's own code issues, lists and revokes client keys through its client
- changed `features/replication/languages.feature`: an entity in every language is told where a change was written and whether it is at the home
- changed `features/secrets/languages.feature`: a service secret is kept and read in every language
- changed `features/secrets/languages.feature`: the database holds a service secret only encrypted in every language
- changed `features/secrets/languages.feature`: a service secret that was never kept is read as none in every language
- changed `features/secrets/languages.feature`: keeping a service secret again replaces its value in every language
- changed `features/secrets/read-record.feature`: a read through a process or a module is recorded without a component
- changed `features/secrets/secret-key.feature`: the process of a deployed service is not given the secret key
- changed `features/secrets/secret-manager.feature`: a service in every language passes every behaviour of the secret store on the Secret Manager backend
- changed `features/service-calls/calling.feature`: a service in every language is admitted by name by a route that admits only it
- changed `features/service-calls/calling.feature`: a refusal by the service called reaches the calling handler as that refusal
- changed `features/service-calls/calling.feature`: a call to a service that cannot be found fails, naming the service, and is not sent
- changed `features/service-calls/calling.feature`: a call is not sent to a workload that is not the service asked for
- changed `features/service-calls/calling.feature`: on a developer's machine a service in every language calls another service running there
- changed `features/service-calls/sdks.feature`: the conformance suite's call to another service passes for every SDK
- changed `features/service-calls/sdks.feature`: an SDK's type checks accept a call to another service
- changed `features/service-identity/languages.feature`: an authenticated route admits a verified token in every language
- changed `features/service-identity/languages.feature`: an authenticated route challenges a token that does not verify in every language
- changed `features/service-identity/languages.feature`: a claim reaches the handler by name in every language
- changed `features/service-identity/languages.feature`: a service with an authenticated route and no issuer listed does not start
- changed `features/sockets/languages.feature`: frames cross a socket both ways in every language
- changed `features/sockets/languages.feature`: the handler of an authenticated socket route is told the principal in every language
- changed `features/sockets/languages.feature`: a request to open a socket with no token is challenged in every language
- changed `features/sockets/languages.feature`: a socket is closed when its handler finishes in every language
- changed `features/sockets/languages.feature`: a service does not declare a socket route to a platform older than socket routes
- changed `features/storage-client/languages.feature`: an object is kept and read through the storage client in every language
- changed `features/storage-client/languages.feature`: the storage client lists, tells what an object is and deletes in every language
- changed `features/storage-client/languages.feature`: no object passes through the platform's own program
- changed `features/storage-client/languages.feature`: every SDK refuses the same object names for the same reasons
- changed `features/storage-client/languages.feature`: the storage client in every language keeps its objects in a directory on a developer's machine
- changed `features/timers/languages.feature`: a recurring timer fires once for each period in every language
- changed `features/timers/languages.feature`: a recurring timer set again keeps its next due time in every language
- changed `features/timers/languages.feature`: a cancelled recurring timer does not fire again in every language
- changed `features/timers/languages.feature`: a handler is told the due time of the timer that ran it in every language
- changed `features/topics/start-position.feature`: a consumer reading a topic must declare its start position
- changed `features/topics/start-position.feature`: a start position is honoured in every language a service is written in
- changed `features/topology/languages.feature`: a service declares the same connections in every language
- changed `features/topology/languages.feature`: a call is attributed to its caller in every language
- changed `features/view-streams/languages.feature`: every row is given as a stream in every language that can read one
- changed `features/view-streams/languages.feature`: a watcher is given the rows now and then a row that comes to match in every language that can read one
- changed `features/views/languages.feature`: a recursive query answers with the same rows in every language
- changed `features/views/languages.feature`: a service whose view declares a statement that reads another table does not start in every language
- changed `features/views/languages.feature`: a view of several sources reads every one of them in every language
- changed `features/views/languages.feature`: a view finds the rows an event is about by asking a query of its own in every language
- changed `features/views/languages.feature`: the topology shows a view connected to each of its sources in every language
- changed `features/workflow-sources/languages.feature`: a view reads a workflow in every language
- changed `features/workflow-sources/languages.feature`: a consumer is handed a failed workflow's standing in every language
- changed `features/workflow-sources/languages.feature`: the topology shows a view connected to the workflow it reads in every language
- changed `features/zero-trust/callers.feature`: a service in any language is told the same calling workload

---

### User Story 4 - Fetched from the registry, started from the template, deployed to the platform (Priority: P4)

The developer adds the SDK to their project from the public Go package registry at the version of
the platform they deploy to, or starts a whole project from the template with `ankka init` and the
Go language. They build an image holding their one executable and nothing else, write a descriptor
that says the image is a process speaking the protocol, and deploy it to a local ankka
installation. It reaches `Ready`, scales, restarts and is exposed exactly as any other service. A
release of this repository produces the SDK into a repository of its own, tagged at the release's
version, the registry serves it from there, and the version a service reports to the sidecar is the
version it depends on.

**Why this priority**: A framework a Go developer cannot `go get` does not exist to them, and the
template is how the other languages' developers start today. This story is the point at which the
platform's promise, "a project, not a platform change", is tested end to end with a deployed
service and no code changed on the platform.

**Independent Test**: In a fresh directory, fetch the SDK from the registry, build the shopping
cart's image from the example, apply its descriptor to the local installation, and command the cart
through the platform's gateway. Start a project from the template and run its tests. Tag a release
and fetch the SDK at that version from the registry in an empty directory.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/go-sdk/installing.feature`: the Go SDK fetched from the package registry builds a service with nothing else added
- added `features/go-sdk/installing.feature`: a service's image holds its one executable and no language runtime
- changed `features/polyglot/template.feature`: a service started from the template passes its tests with none left out
- changed `features/polyglot/template.feature`: a service started from the template runs beside the platform's own program of the template's version
- changed `features/topics/groups.feature`: a project made from a template states its service's name
- changed `features/polyglot/deploying.feature`: a service hosted as a process runs beside the platform's own program and is ready only when both are
- changed `features/polyglot/deploying.feature`: a deployed service says which SDK its code was built with
- changed `features/polyglot/deploying.feature`: a deployed service runs the platform's own program at the installation's version
- changed `features/polyglot/deploying.feature`: a service written in another language scales into one cluster without replacing an instance
- changed `features/polyglot/deploying.feature`: a service written in another language is restarted one instance at a time and refuses no request
- changed `features/polyglot/deploying.feature`: a service written in another language is exposed, paused and resumed like any other service
- changed `features/polyglot/deploying.feature`: the trace of a deployed service attributes each handler's time to the component that did the work
- changed `features/polyglot/deploying.feature`: a process that stops is started again while its instance stays in the cluster
- changed `features/polyglot/releasing.feature`: a release publishes an SDK at its version after the platform's own
- changed `features/polyglot/releasing.feature`: a change to the protocol that an SDK's copy does not have fails the build
- added `features/polyglot/releasing.feature`: an SDK produced into a repository of its own is served by the package registry at the release's version

---

### User Story 5 - The paradigm, taught in Go (Priority: P5)

A Go developer who has never met an event sourced entity follows the getting-started page from an
empty directory to a running service, then reads the reference page for the kind they need next. A
coding agent working in their project holds an ankka skill for Go and produces components that
compile, pass their unit tests and pass through the sidecar. Every code sample they read is taken
from code the repository tests, and every page, skill and tool description that lists the languages
names Go.

**Why this priority**: The SDK's structure is only a benefit if it is explained, and in this
repository the documentation and the skills are the explanation. It is last because every page
includes samples from code the earlier stories write.

**Independent Test**: The documentation builds with the new pages, every included sample is
current, and a reader with Go and Docker installed and no JVM reaches a commanded entity in the time
the page promises. Every shared skill that says how Python, TypeScript and Rust differ also says how
Go differs.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- changed `features/polyglot/documentation.feature`: the first service in a language needs no JVM
- changed `features/polyglot/documentation.feature`: the documentation of a language states every kind of component with a tested sample
- changed `features/polyglot/documentation.feature`: a coding agent writing a component in a language is told how the language differs
- `features/polyglot/documentation.feature`: the documentation does not build when a page is out of step with what it is taken from or listed in
- changed `features/documentation/service-calls.feature`: one page shows a call to another service in every language
- `features/documentation/mcp-endpoints.feature`: the documentation describes declaring an MCP endpoint, its ACL and its client keys in every language
- changed `features/documentation/sockets.feature`: the documentation describes a socket route in every language that has one

---

### Edge Cases

- A record holds a 64-bit integer larger than 2⁵³. Go carries it natively; the codec must never
  pass a number through a floating type on the way, as the standard library's lenient decoding
  does.
- A double whose value is a whole number. A floating field writes `1.0` and an integer field writes
  `1`; the developer's type, not the value, decides, and the bytes match the Scala codecs'.
- An instant with nine fractional digits. Go's time type keeps them; the codec writes them back
  with the same digits, drops the monotonic reading and renders in UTC as the encoding does.
- A stored record is missing a required field. It is refused, not decoded as the field's zero
  value; the failure is reported as recovery failing for that entity, never as a crash of the
  process. A stored record carries a field the type does not know: reading ignores it. A stored sum
  type names a case that was never registered: refused the same way.
- A field is unexported, untagged, an embedded struct, a map with keys that are not strings, a
  channel, a function or an interface with no registered cases. The codec refuses the type at
  registration and in the component test kit, naming the field, rather than rendering it some other
  way or skipping it.
- A `[]byte` field inside a record, and a bytes value at the top level of a payload. The two have
  different renderings in the encoding and the codec must produce each.
- A query handler written to persist. It does not compile, because of the type the handler
  returns; registration refuses one that reaches it another way; the sidecar accepts no events from
  a read-only handler regardless.
- An endpoint with no access rule. The constructor takes the rule, so the program does not compile
  without one, and registration refuses it again.
- A handler panics. The panic is recovered into that request's failure with the instance's state
  unchanged, and the process goes on serving. A goroutine the developer started from a handler
  panics after the handler returned: the SDK cannot recover a panic in a goroutine it did not start,
  so the documentation says a handler's work stays inside the handler's context, and the test kits
  show the process dying when it does not.
- A handler ignores its context after the conversation it ran in has ended. The SDK has released
  the instance's state; anything the handler still does cannot reach it, and a reply it produces is
  dropped rather than applied to a later conversation.
- The stream for a loaded instance ends. The process cannot tell passivation from the sidecar
  going away and releases the instance's state in both cases.
- A command arrives for a workflow while a step is running. It is answered from the state before
  the step; the step's new state applies when the step replies. A second step requested while one
  runs is refused.
- Two components declare the same id; two handlers on one component declare the same wire name;
  two endpoints declare the same prefix; a tool is declared with no description. The service
  refuses to start and reports every problem together.
- The sidecar refuses discovery. The process logs every problem the sidecar reported, once, so the
  reason is in the developer's own log.
- The process is asked to bind an interface other than loopback. Refused, except the any-address
  binding the test kit needs to be reached from a container.
- The process is told to stop. It stops accepting new work, lets the handlers in flight finish
  within the time the platform gives, and exits; the platform's rolling restart depends on it.
- The service declares something an older sidecar would silently ignore (a socket route, a
  workflow source, a replicated entity, a call to another project's topic). The SDK refuses to
  start against that sidecar, naming the protocol version required, oldest reason first, as the other
  SDKs do.
- The developer's toolchain is older than the SDK supports. The build refuses it, naming the
  version the SDK needs, before anything runs.
- The developer builds against a checkout of the SDK rather than the registry. The version the
  service reports in discovery says so, rather than claiming a release.
- A released version of the SDK turns out to be broken. The registry serves every version it has
  ever served, so the release is marked withdrawn rather than removed, and the next version
  supersedes it.

## Requirements *(mandatory)*

### Functional Requirements

**The component model**

- **FR-001**: The SDK MUST let a developer declare every component kind the platform hosts: event
  sourced entity, key value entity, view (of one or several sources, including a workflow, and keyed
  views), consumer (including a graph consumer), timed action, workflow, agent, autonomous agent,
  HTTP endpoint and MCP endpoint, with the same model the Scala, Python and TypeScript SDKs express:
  a stable component id, handlers whose wire names are declared separately from their method names,
  and handlers that return descriptions of what should happen rather than performing it.
- **FR-002**: A query handler MUST be unable to persist. The SDK MUST make this a compile-time error
  through the type the handler is declared to return, and MUST refuse at registration a handler that
  reaches it any other way.
- **FR-003**: Every effect the Scala SDK offers for a kind MUST be expressible: for entities,
  persist with a reply computed from the state after the events, reply, refuse with an error code,
  no reply, delete, expire; for workflows, update state, transition, pause with a timeout, end,
  fail, and declared timeouts and recovery; for views and consumers, update, delete, ignore,
  produce one or several messages; for agents, system and user messages, context, memory, model or
  model provider, tools including an MCP server's and those requiring approval, guardrails, and a
  plain, streamed or structured reply.
- **FR-004**: An HTTP endpoint MUST declare an access rule with no default, MUST be able to state a
  different rule on one route, and MUST give a handler typed path parameters, a decoded body, the
  query parameters, headers and, when authenticated, the caller's principal and its claims by name.
  A route MUST be able to stream its reply, and an endpoint MUST be able to declare a socket route
  whose handler sends and receives frames for the life of the socket.
- **FR-005**: Registration MUST be explicit. The SDK MUST collect every problem with a service's
  declaration (duplicate ids, duplicate wire names, shared prefixes, a type the codec cannot
  render, a read-only handler that is not, an endpoint with no access rule, an agent tool with no
  description, a topic consumer with no start position, an authenticated route with no issuer) and
  refuse to start naming all of them at once.
- **FR-006**: The SDK MUST provide a client through which a handler calls entities, workflows and
  agents by component id and wire name, sends a workflow a command and waits for it to end,
  queries views once or as a stream, watches a view, schedules and cancels timers including
  recurring ones, calls another service by name, keeps and reads service secrets, issues, lists and
  revokes an MCP endpoint's client keys, and keeps, reads, lists, describes and deletes objects
  through the storage client; with every reply decoded into the type the caller names and a refusal
  surfaced as an error carrying its code.
- **FR-007**: Every handler and every call that may wait MUST take a context, so a wait is cancelled
  when its request is, and a handler's goroutines have a context to live inside.
- **FR-008**: The SDK MUST require no code generation from the developer's own code, no
  reflection over the developer's handlers, no dependency container and no build step beyond the
  standard toolchain's, so a service reads as ordinary Go and runs with the standard run command.
- **FR-009**: The SDK MUST give a handler what every other SDK's handler is given: the caller's
  identity and project, the installation a change was written at and whether the entity is at its
  home, the due time of the timer that ran it, and the service's configured variables; and MUST
  carry the platform's attribution and trace on every call it makes so a call is nested under the
  handler that made it.

**The encoding**

- **FR-010**: The SDK's default codec MUST render the developer's own struct types from their fields
  and tags for every shape the encoding lists (records, sum types with a discriminator, enumerations,
  optional values, sequences, string-keyed maps, integers, 64-bit integers, doubles, booleans,
  strings, instants, durations, dates and bytes, including recursive shapes, and the personal
  envelope for erasable values), with a sum type declared as an interface whose cases are registered
  under their discriminators.
- **FR-011**: The default codec MUST produce and accept exactly the encoding in
  `protocol/ENCODING.md` for every shape, including the text and binary payloads for top-level
  primitives, and MUST pass every fixture published with the protocol both ways, with a fixture it
  cannot handle failing rather than skipping.
- **FR-012**: The codec MUST tell an integer from a double by the developer's type, MUST carry a
  64-bit integer without loss, MUST write every field including empty collections and absent
  optionals, MUST refuse a missing required field rather than supply a zero value, and MUST render
  doubles, instants, durations and dates byte for byte as the Scala codecs do.
- **FR-013**: The codec MUST refuse at registration and in the component test kit any type it cannot
  render, naming the field, and MUST never render such a type some other way or skip it.
- **FR-014**: A declared tool input type MUST yield the JSON Schema the model is given, so a tool's
  input is declared once.
- **FR-015**: A developer MUST be able to supply a codec of their own for a type; portability is
  then their contract, as in every other SDK.

**Serving the protocol**

- **FR-016**: The SDK MUST implement every service in the protocol except the sidecar's callback
  service, on loopback at the process port the platform names, MUST dial the callback service at
  the sidecar address the platform names, and MUST refuse to bind any other interface except the
  any-address binding the test kit requires.
- **FR-017**: The SDK MUST answer discovery with the protocol version it speaks (the current one),
  its own name and version, and every registered component and endpoint with the details each kind
  requires, and MUST log every problem the sidecar reports back.
- **FR-018**: The SDK MUST refuse discovery from a sidecar whose protocol version is older than
  something the service declares requires, naming the version needed and the oldest unmet reason
  first, so an older runtime is told the oldest thing it lacks.
- **FR-019**: For each loaded stateful instance the SDK MUST hold the state for the life of the
  conversation, fold the recovered snapshot and events before the first command, handle commands
  strictly in order, compute each reply from the state after the reply's events, and release the
  state when the conversation ends for any reason.
- **FR-020**: For a workflow the SDK MUST keep one command and one step in flight at once, answer a
  command that arrives mid-step from the state before the step, apply the step's new state when the
  step replies, and refuse a second concurrent step.
- **FR-021**: A handler that returns an error or panics MUST become that request's failure on the
  protocol with the instance's state unchanged, and MUST never terminate the process; a refusal the
  handler gives on purpose MUST reach the caller as a refusal, not a failure.
- **FR-022**: The SDK MUST speak to the object store itself from the variables the platform gives
  it, so no object passes through the sidecar, and on a developer's machine MUST keep objects in a
  directory as the other process SDKs do.
- **FR-023**: On being told to stop, the process MUST stop accepting work, let handlers in flight
  finish within the time the platform gives, and exit.

**Test kits**

- **FR-024**: The SDK MUST provide a component test kit that drives one component of any kind with no
  sidecar, database or network, exposes each effect's result as values, carries the state forward
  between calls, follows a workflow to where it waits, hands a view a change from any of its sources,
  reads back what a consumer published, and round-trips every input, event, state, row and reply
  through the component's declared codecs. For agents it MUST run the loop in-process against a
  scripted model that fails when the script runs out.
- **FR-025**: The SDK MUST provide an integration test kit that starts Postgres with the platform's own schema
  and the real sidecar image, serves the test's components to it, waits for the sidecar to report
  healthy, offers the service's HTTP surface and client to the test, can replace the sidecar against
  the same database, and cleans up after itself including when startup fails.
- **FR-026**: Both test kits MUST run under the standard test command with no third-party runner.

**Examples and conformance**

- **FR-027**: The SDK MUST ship the shopping cart sample ported to Go, with the same components, wire
  names, routes and stored shapes as the Scala, Python, TypeScript and Rust carts, its own unit and
  integration tests, an image build that holds the one executable and no language runtime, and a
  descriptor.
- **FR-028**: The SDK MUST ship a reference service declaring the same components, wire names and
  routes as the Scala conformance reference, including its MCP endpoint, client key routes, socket
  routes, storage routes and call to another service, and a command that serves it and runs the
  platform's conformance suite against it, exiting with the suite's status and narrowed by the same
  variable the other SDKs honour.
- **FR-029**: The SDK MUST carry a copy of the protocol artifact so it builds alone, and continuous
  integration MUST fail when the copy differs from the original.
- **FR-030**: A test MUST prove that a journal written by the Scala shopping cart is recovered by
  the Go cart with identical state, and the reverse.
- **FR-031**: Every living feature whose examples list the languages a behaviour holds in MUST gain
  Go, and the test behind each MUST run against the Go reference or example where it runs against
  the TypeScript one.

**Packaging, template and release**

- **FR-032**: A release of this repository MUST produce the SDK into a repository of its own,
  `thinkmorestupidless/ankka-go`, from `sdks/go` alone and never edited there, tagged at the
  release's version after the platform's own artifacts, as the template, the action and the formula
  are produced today; the public Go package registry then serves it at that version under that
  repository's path. No tracked file MAY be rewritten by the build, and the only credential is the
  push the family's releases already hold.
- **FR-033**: The version a service reports to the sidecar in discovery MUST be the version of the
  SDK it depends on, and a service built against a checkout rather than a release MUST report a
  version that says so.
- **FR-034**: The SDK MUST state the oldest toolchain it supports so an older one refuses to build
  it, naming the version, and MUST build to a static executable with no C dependency.
- **FR-035**: A release MUST be proven before it is relied on: fetching the SDK at the tag's version
  into an empty directory and building a service from it, on every commit against the tree and
  after every release against the registry.
- **FR-036**: `ankka init` MUST offer Go as a language, writing a project with an entity, a view, an
  endpoint, tests at both levels, a compose file that starts Postgres and the sidecar at the
  template's version, an image build, the descriptor and continuous integration workflows, and the
  template's suite MUST run where the other languages' template suites run.
- **FR-037**: Continuous integration MUST run the SDK's vet, tests, packaging proof, template suite
  and the conformance suite against the sidecar image built from the same commit, and only when a
  pull request touches what the job reads.

**Documentation**

- **FR-038**: The documentation MUST gain a getting-started page and a reference page for Go,
  following the TypeScript pages' structure, with every sample included from tested code.
- **FR-039**: A Go skill MUST exist beside the Python, TypeScript and Rust ones, and every shared
  skill that has a differences section for those languages MUST gain a Go one.
- **FR-040**: Every page, skill and tool description that names the supported languages (the
  concepts page on other languages, the install page, the limitations page, the contributing page on
  adding a language SDK, the CLI's MCP tool descriptions, the skills' descriptions and the site's own
  description) MUST name Go, and every limitation that holds for the Python and TypeScript SDKs MUST
  say it holds for Go.

**What must not change**

- **FR-041**: No file in the sidecar, the runtime, the operator, the control plane, the CRD or the
  protocol MAY change for this feature, with one carve-out: a test that enumerates the supported
  languages (today the two documentation suites that pin a heading and a code fence list, and the
  CLI's `InitSuite`) gains Go and nothing else. A behaviour the Go SDK needs that the platform lacks
  is a defect in the feature that owns it, filed and fixed separately, not a change smuggled in here.
- **FR-042**: The conformance suite and the encoding fixtures define compatibility and MUST NOT be
  altered to make the SDK pass. A case the SDK cannot pass is a defect in the SDK.
- **FR-043**: Every existing test MUST pass, unchanged except for the language-enumerating tests
  FR-041 names, and the Scala, Python, TypeScript and Rust SDKs MUST be unaffected except where a
  shared documentation page, skill or living feature gains a Go section or row.

### Key Entities

- **Codec**: a manifest, a content type and an encode and decode pair for one type, built from the
  developer's struct type and its tags, or supplied by the developer. The default codec produces the
  platform's encoding exactly.
- **Case registration**: the developer's statement that a struct type is one case of a sum type,
  under which discriminator, so an interface-typed event, state or reply can be read back.
- **Component declaration**: a value carrying a component id, its codecs, its handler table with
  wire names, and the kind's functions: fold, view change, consumer message, workflow steps, agent
  tools and guardrails, socket handlers, MCP tools.
- **Effect**: an inert description of what should happen for one request, built by a handler and
  reduced by the sidecar. Read-only effects are a distinct type.
- **Service**: the explicit registry of components and endpoints, validated as a whole, that
  answers discovery and serves the protocol.
- **Client**: the handler's way to call other components, services, timers, secrets, client keys
  and the object store, scoped to the request's context so traces connect and waits are cancelled.
- **Component test kit**: runs one component with no sidecar and exposes its effects as values.
- **Integration test kit**: runs the developer's components against the real sidecar image and Postgres in
  Docker, under the standard test command.
- **Reference service**: the Go declaration of the components, wire names and routes the
  conformance suite drives.
- **Published SDK**: the SDK as the package registry serves it, at the version of the tag that
  released it, carrying its own protocol stubs.
- **Template**: the project `ankka init` writes for Go.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every encoding fixture published with the protocol passes through the SDK's default
  codec in both directions with 0 skipped and 0 byte differences.
- **SC-002**: The conformance suite passes against the Go reference service with 0 failures across
  every case it has today (the cases the suite itself excludes for a process target are reported as
  skipped, as they are for the other SDKs, and no other case is), and one deliberately broken
  behaviour is named by the suite as exactly
  that behaviour.
- **SC-003**: The shopping cart port passes its own integration tests through the sidecar, and a
  journal written by the Scala cart is recovered by the Go cart with 0 differences in state, and the
  reverse.
- **SC-004**: A developer with Go and Docker installed and no JVM goes from an empty directory to an
  entity commanded through a local sidecar in under fifteen minutes following the documentation,
  running their service with the standard run command and no generated code.
- **SC-005**: A persisting query, an unstated endpoint access rule, a duplicate wire name and a
  type the codec cannot render are each refused before the service starts, with a message naming the
  rule; the first two are refused by the compiler.
- **SC-006**: The shopping cart port deploys to the local installation and reaches `Ready`, scales,
  restarts and is exposed with the same commands and status words as any service, with 0 changes to
  the sidecar, runtime, operator, control plane, CRD or protocol.
- **SC-007**: A release tag makes the SDK fetchable from the registry at the tag's version with 0
  manual steps, and a service built from that version reports it in discovery.
- **SC-008**: A project started from the template with the Go language passes every one of its
  tests with 0 skipped, on a machine with Go and Docker alone.
- **SC-009**: The documentation builds with 0 problems with the new pages, the new skill and the Go
  sections in every shared skill that has a TypeScript one, and 100% of the code samples on the Go
  pages are included from tested code.
- **SC-010**: Every living feature that lists the languages a behaviour holds in lists Go, and the
  features check passes with 0 findings.
- **SC-011**: Every existing test in the repository passes, unchanged except for the
  language-enumerating tests FR-041 names.

## Assumptions

- **The SDK hosts a Go service as a process**, over the sidecar protocol, as the Python and
  TypeScript SDKs do, for the reasons in the context. A module build of the same component model
  is a later feature.
- **The language is Go built with its standard toolchain**, at a floor of the two most recent
  releases, which is the language's own support window; the floor is stated where the toolchain
  reads it so an older one refuses to build. No C dependency, so the executable is static and the
  image needs no runtime.
- **The SDK lives in this repository**, under `sdks/go`, for the reason the others do: the protocol,
  the conformance suite and the SDK move together, and a tag proves them consistent.
- **The SDK is served by the public Go package registry from a repository of its own**,
  `thinkmorestupidless/ankka-go`, which the release produces from `sdks/go` and tags at the
  release's version, the way `ankka.g8`, `ankka-action` and `homebrew-tap` are produced; nothing is
  edited there directly. The registry serves a library straight from its repository's tags, so there
  is nothing to upload; the only credential is the push the family's releases already hold. A
  version the registry has served is served forever; a broken one is marked withdrawn by the next
  release. The repository's path, not a vanity one, so the name needs no claiming.
- **The version the SDK reports comes from the build's own record of the dependency** when a
  service depends on a released version, which is how the version on the registry and the version in
  discovery are one without a placeholder in the tree; a checkout reports the development version
  `0.0.0`, the one every SDK of the family reports before a release and the one that makes the
  integration test kit choose the locally built sidecar image. How
  the examples inside this repository, which depend on the tree, report theirs is a planning decision.
- **The codec reads the developer's struct types and tags at registration**, following the
  standard library's tag conventions where the encoding agrees with them, and registers a sum
  type's cases explicitly, because Go has no way to enumerate an interface's implementations. A
  third-party serialisation library is not used: the encoding needs decisions none of them make.
- **The transport library is the standard one for gRPC in Go**, with stubs generated from the
  SDK's copy of the protocol by the SDK's own script, as the other SDKs do; confirming it carries
  the conversation's streams as the sidecar needs is a planning spike before the plan commits.
- **The protocol version is the current one, `1.22`.** The SDK declares it in discovery and the
  sidecar's version rules apply unchanged; the SDK gates each later-minor behaviour as the other
  SDKs do.
- **The conformance suite's reference is the full list of what the SDK must declare.** The Scala
  reference and the conformance contract in `specs/009-polyglot-runtimes/contracts` are the source;
  the TypeScript reference is the worked example of a process SDK's copy of it.
- **Every component kind and every behaviour of the current protocol ships in this one feature**,
  because the suite that defines compatible needs all of them, and the audience is building whole
  services.
- **The template follows the TypeScript and Rust templates**: an entity, a view, an endpoint, tests
  at both levels, compose, image, descriptor and workflows, and a suite under the existing template
  switch.
- **The documentation follows the TypeScript trail page for page**: getting started, reference, a
  skill, and differences sections. The docs tooling already accepts sample markers in any comment
  syntax and needs no change beyond `go` in its language vocabulary.
- **What implementation settled** (2026-10-23):
  - The floor is Go 1.26, both 1.26 and 1.27 in CI; the generators are a nested module of their own,
    because buf needs 1.26.7, so they never enter a service's graph.
  - The image is a static executable on a distroless base, about 26 MB, with no shell.
  - The SDK and its test kits are one module; a service that imports only the SDK downloads no
    container library (measured by the pack smoke).
  - The SDK a deployed service was built with is said in the process's own log, not by the platform:
    the platform shows no process SDK's name (research V6).
  - The personal envelope (spec 042) is not in this feature: no SDK outside Scala has it, and the
    limitations page says so.
  - The platform's code is unchanged. Its tests gained Go cases where a suite lists the languages
    (SidecarClusterSuite, the process rendering, the two documentation suites, the three carve-outs),
    and the Scala sample gained one route a Go caller is admitted by.
- **The limitations of the Python and TypeScript SDKs are the Go SDK's**: routes read their bodies
  whole and large objects go through signed URLs, a process's own log lines carry no trace,
  judgments and gRPC endpoints are Scala's, and a handler names a configured model provider rather
  than a model.

## Out of Scope

- A module build of a Go service (a Go guest for the WebAssembly route). The component model
  specified here is designed not to preclude it; the build, the second compiler and its conformance
  run are a later feature.
- Support for gRPC endpoints, judgments or a control plane client in Go, which no process SDK has.
- Porting the multi-agent planner sample. The reference service's agent and the shopping cart's
  assistant are the agent proof, as they are for the other languages.
- An adapter from a third-party serialisation library to the SDK's codec.
- A vanity import path for the SDK, and whatever a second major version of it would need.
- Any change to the protocol, the encoding, the fixtures, the conformance suite, the sidecar or the
  platform. A gap found in any of them is a separate defect against the feature that owns it.
