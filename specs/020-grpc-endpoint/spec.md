# Feature Specification: gRPC Endpoints — A Second Way Into a Service

**Feature Branch**: `020-grpc-endpoint`

**Created**: 2026-10-02

**Status**: Draft

**Input**: User description: "Add a gRPC endpoint as a new component type, beside the existing HTTP
endpoint. A developer building a Scala service on ankka defines a service's API in a .proto file and
implements it as a gRPC endpoint: the outermost layer of a service, translating gRPC calls into
component calls, exactly as an HTTP endpoint translates HTTP requests. It is registered explicitly
with the service like every other component, and a service may have HTTP endpoints, gRPC endpoints
or both. Scope of this first version: Scala services only; unary and server-streaming methods; the
same access-control vocabulary as an HTTP endpoint, per endpoint and per method; mutual TLS and the
caller read from the client certificate in a cluster; a component's modelled rejection reaches the
caller as the corresponding gRPC status; a service declares in its descriptor that it serves gRPC
and the platform gives it an address other services can reach, admits only platform workloads and
holds the instance un-ready until it can answer; a Scala service can call another service's gRPC
endpoint as itself; a rolling replacement or a scale-out must not strand callers; gRPC calls appear
in traces and metrics; the testkit can test one; documentation. Out of scope, recorded as
limitations: exposing a gRPC endpoint through the gateway, gRPC-web, server reflection, the local
console invoking gRPC methods, non-Scala SDKs."

*The input is kept as it was given. Clarification changed three things it says: gRPC is reachable
through the gateway, every kind of method is served, and reflection is available to a service
that opts in. The Clarifications section is what holds.*

## Context

A service on ankka has one way in from outside itself: an HTTP endpoint, on one port, speaking
HTTP/1.1 with JSON bodies. That is the right front door for a browser and for a person with `curl`.
It is a poor fit for the other kind of caller a service has, which is another service: there the
two sides want a contract both are generated from, typed messages, and an answer that can arrive a
part at a time without inventing a framing for it. Teams that already describe their APIs in
`.proto` files cannot bring them to ankka at all today; the limitations page says so in two places
("One port, HTTP only" and "no gRPC or HTTP/2 to services").

This feature adds a second kind of endpoint. A gRPC endpoint does the same job an HTTP endpoint
does, in the same place: it is the outermost layer of a service, it holds no state, and it turns
what a caller asked for into calls to the service's components. What differs is the contract. The
API is written first, as a service definition in a `.proto` file, and the endpoint implements the
methods that definition names. Everything else about an endpoint is deliberately the same, so that
a developer who knows one knows the other:

- it is handed to the service explicitly, and one that was not is not served;
- it must say who may call it, in the vocabulary HTTP endpoints already use;
- in a cluster the caller is who the client certificate says it is, and nothing the call says about
  itself is trusted; on a developer's machine there are no certificates and every caller is local;
- a component's deliberate "no" reaches the caller as a refusal the caller can act on, and is not
  recorded as a fault.

A deployed service that serves gRPC is reached in the two ways one that serves HTTP is. Other
services of the installation reach it at an address inside the cluster. When it is exposed, callers
outside the cluster reach it at the hostname it already has: the one hostname answers HTTP requests
and gRPC calls alike, and each goes to the endpoints of its kind. A second hostname for gRPC was
considered and declined. An exposed service has one name, and a second would bring a second set of
rules for what names may collide.

One decision bounds this first version.

- **Scala only.** A Python, TypeScript or Rust service cannot declare a gRPC endpoint yet. Hosting
  one for a process or a module means the runtime serving methods it holds no generated code for,
  a new part of the sidecar protocol, and work in three SDKs; that is a feature of its own.

Every kind of method a service definition can have is served: one request and one answer, a stream
of answers, a stream of requests, and both at once. A stream in either direction moves no faster
than the side reading it, so neither a slow caller nor a slow handler makes the service hold what
has not been read.

A service must also be able to call one. Inside a cluster every connection is mutual TLS, so a
service cannot simply open a connection of its own to another service's gRPC endpoint:
it has to present the identity the platform issued it and check the identity of what answered. The
feature therefore includes the calling side — the counterpart, for gRPC, of the service client that
Scala services already call each other's HTTP endpoints with.

## Clarifications

### Session 2026-10-02

- Q: Should this version route gRPC from outside the cluster through the gateway? → A: Yes. An
  exposed service's gRPC endpoints are reachable at its existing hostname, which answers HTTP
  requests and gRPC calls alike.
- Q: Which kinds of method does a gRPC endpoint serve? → A: All four: one request and one answer, a
  stream of answers, a stream of requests, and a stream in both directions at once.
- Q: Which words do the features use for who may call an endpoint, and for who called? → A: "ACL",
  the established word, and not a new one for it; and "calling workload", because "caller" is given
  another sense by the service topology feature's glossary. Every other term the glossary proposed
  is settled as drafted.
- Q: Which gRPC status does a component's `Conflict` refusal reach the caller as? → A: Failed
  precondition: the current state does not permit the operation, and retrying unchanged will not
  help. Aborted (which invites a retry) and already exists were declined.
- Q: Does a service answer server reflection? → A: Only when it opts in, and then wherever it is
  reached — on a developer's machine, inside the cluster and at its hostname — under an ACL of
  its own that the developer must state.

### Session 2026-10-02 (tasks)

- **The features are arranged so that a suite can run a directory whole.** `GherkinSuite` runs
  every feature under the directory it is given, and a step with no definition fails. The features
  one process can run are under `features/grpc/`; those that need a cluster are under
  `features/grpc-deployed/`, and their scenarios are tests named after them, as are the local
  console's and the documentation's. No scenario changed in the move.

### Session 2026-10-02 (analysis)

These corrections came out of the cross-artifact analysis.

- **An answer's size is the caller's limit (FR-014).** A service refuses a request that is too
  large. It cannot usefully refuse its own answer, and a caller's client already does.
- **A caller that vanishes is noticed within a minute (FR-051).** The edge case said "a bounded
  time" and nothing provided one. Through the gateway the bound is the gateway's.
- **The gRPC client is handed over, not found (FR-028).** No component is given a client to other
  services today, for HTTP either; the service creates one and passes it to what needs it.
- **A runtime too old to serve gRPC is refused when the descriptor is applied (FR-052).**
- **FR-042 no longer restates FR-023**, which now says it holds for an exposed service too.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Serve a gRPC API from a service (Priority: P1)

A developer writes a service definition in a `.proto` file — a cart service with a method that
returns a cart and one that adds an item — and implements it as a gRPC endpoint that calls the cart
entity. They hand the endpoint to the service beside its other components and run the service on
their machine. A gRPC client built from the same `.proto` file calls the method and gets the cart
back. When the cart entity refuses an item, the client receives the
matching gRPC status and the entity's message, not a generic failure. The same service can go on
serving its HTTP endpoints; the two do not interfere. The developer writes a test that starts the
whole service on a throwaway database and calls the endpoint through a real client.

A mistake in the definition of the service is found when it starts, with every problem named: a
method of the `.proto` service the endpoint does not implement, two endpoints implementing the same
`.proto` service.

**Why this priority**: It is the feature. With nothing else built, a developer can define, serve,
call and test a gRPC API on their own machine.

**Independent Test**: Add a gRPC endpoint to the shopping cart sample, start it locally, and call
"get cart" and "add item" from a generated client; add an item the cart refuses and confirm the
client sees the documented status and the cart's message; run the sample's test suite, which calls
the endpoint through the test kit on an ephemeral port.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/grpc/serving.feature`: a call to a method is answered by the handler the endpoint declares for it
- added `features/grpc/serving.feature`: a service serves its HTTP endpoints and its gRPC endpoints at once
- added `features/grpc/serving.feature`: a gRPC endpoint that is not registered with the service is not served
- added `features/grpc/serving.feature`: a service whose gRPC endpoint leaves a method without a handler does not start, and says which
- added `features/grpc/serving.feature`: a service with two gRPC endpoints for the same service definition does not start
- added `features/grpc/serving.feature`: a call to a method no endpoint serves ends as unimplemented
- added `features/grpc/statuses.feature`: a refusal ends the call with its status and its message
- added `features/grpc/statuses.feature`: a handler that fails ends the call as an internal error that says nothing of the failure
- added `features/grpc/statuses.feature`: a request the endpoint cannot read ends the call as an invalid argument, and no handler runs
- added `features/grpc/testing.feature`: a test calls a gRPC endpoint of a whole service through the test kit

---

### User Story 2 - Decide who may call a gRPC endpoint (Priority: P1)

The developer must say who may call the endpoint before it can be served, exactly as for an HTTP
endpoint: nobody, anybody, named callers (a named service, any service of this project, this service
itself, the internet), or an authenticator that reads the call and establishes who is calling. One
method can answer to a different rule from the rest of its endpoint — a read open to the project
beside a write open to one named service. A handler can read which workload called it, the
principal an authenticator established, and the metadata the caller sent.

A caller the rule does not admit is refused with a gRPC status that tells it which kind of "no" it
was: it must authenticate, it is authenticated and may not, or the endpoint cannot tell right now.
The handler does not run. A closed endpoint answers the same refusal for every method name, so it
does not disclose which methods exist.

**Why this priority**: An endpoint without this cannot be deployed. It is separate from story 1 only
because story 1 can be demonstrated with an endpoint open to all.

**Independent Test**: Give the sample's gRPC endpoint an authenticator on one method and "deny all"
on another; call each with and without a credential and confirm the three kinds of refusal and that
no handler ran for a refused call.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/grpc/access.feature`: a gRPC endpoint must state who may call it
- added `features/grpc/access.feature`: an endpoint that denies all refuses every call, and no handler runs
- added `features/grpc/access.feature`: a method's own ACL replaces its endpoint's for that method
- added `features/grpc/access.feature`: a method that states no ACL answers to its endpoint's
- added `features/grpc/access.feature`: an authenticator's answer decides the call and which refusal ends it
- added `features/grpc/access.feature`: a handler reads the principal the authenticator established
- added `features/grpc/access.feature`: a handler reads the metadata sent with the call
- added `features/grpc/access.feature`: an endpoint that admits a named service admits a call from that service
- added `features/grpc/access.feature`: an endpoint that admits a named service refuses a call from any other service
- added `features/grpc/access.feature`: a closed endpoint refuses a call to a method it does not have as it refuses one it has
- added `features/grpc/access.feature`: on a developer's machine every calling workload is the local caller

---

### User Story 3 - Deploy a service that serves gRPC (Priority: P2)

The developer adds to the service's descriptor that it serves gRPC and deploys it. The platform
gives the service an address for gRPC inside the cluster, beside its HTTP address if it has one.
Connections to it are mutual TLS with the certificates the platform already issues the service; the
caller a handler and an ACL see is the one the client certificate names. Only workloads of
the installation can connect to the gRPC address at all. An instance is not ready, and receives no
calls, until it can answer gRPC.

A service whose descriptor says nothing about gRPC is deployed exactly as it is today, and nothing
about a running one changes when the platform is upgraded to a version with this feature. A
descriptor that asks for something the platform cannot do is refused when it is applied, saying
why: gRPC for a service that is not a Scala service, or a gRPC port that is the HTTP port.

**Why this priority**: It is what makes story 1 more than a local demonstration, and it is the part
of the feature that touches every layer of the platform. Stories 1 and 2 are complete without it.

**Independent Test**: Deploy the sample with gRPC declared into a test cluster; from another
deployed service call its gRPC address and get an answer; from a workload with no platform identity
confirm the connection is refused; deploy a service that declares gRPC and serves none and confirm
it is reported as not ready, with a reason.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/grpc-deployed/deployed.feature`: a service that declares gRPC has a gRPC address other services reach
- added `features/grpc-deployed/deployed.feature`: a service that does not declare gRPC is deployed as it was before
- added `features/grpc-deployed/deployed.feature`: a service may serve gRPC and no HTTP
- added `features/grpc-deployed/deployed.feature`: a deployed gRPC endpoint reads its calling workload from the certificate
- added `features/grpc-deployed/deployed.feature`: a call that says it is from another service is not believed
- added `features/grpc-deployed/deployed.feature`: a workload that is not of the installation cannot connect to a gRPC address
- added `features/grpc-deployed/deployed.feature`: an instance that cannot yet answer a gRPC call is not ready
- added `features/grpc-deployed/deployed.feature`: a service that declares gRPC and serves none is reported as failed, with the reason
- added `features/grpc-deployed/deployed.feature`: a descriptor that declares gRPC for a service that is not embedded is refused
- added `features/grpc-deployed/deployed.feature`: a descriptor whose gRPC port is its HTTP port is refused
- added `features/grpc-deployed/deployed.feature`: a descriptor that sets the platform's gRPC port variable itself is refused

---

### User Story 4 - Reach a gRPC endpoint from outside the cluster (Priority: P2)

A member exposes a service that declares gRPC. The hostname the service is given is the one it
would have had anyway, and it now answers both kinds of traffic: an HTTP request goes to the
service's HTTP endpoints as before, and a gRPC call made by a client outside the cluster goes to
its gRPC endpoints. The connection from outside is TLS with the hostname's certificate, as for
HTTP. Nothing a developer writes differs between a method called from inside the cluster and one
called from outside.

Every call from outside the cluster comes from the gateway, whichever hostname it arrived at, so an
ACL that admits only named services refuses it and one that admits the gateway admits it.
Telling one outside caller from another is an authenticator's job, from what the call carries. A
service that declares gRPC and is not exposed is not reachable from outside at all, and exposing a
service that declares no gRPC does exactly what it does today.

**Why this priority**: It is what lets a client that is not a service of the installation — a
mobile app's backend, a partner's system, a developer's own machine — use the API. Stories 1 to 3
are complete without it.

**Independent Test**: Deploy the sample with gRPC declared and expose it; from outside the cluster,
with only the hostname and the installation's certificate authority, call a method and get an
answer, then send an HTTP request to the same hostname and get an answer; unexpose it and confirm
neither is answered.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/grpc-deployed/exposed.feature`: a call to an exposed service's hostname is answered, and its calling workload is the gateway
- added `features/grpc-deployed/exposed.feature`: a request to a route at the hostname of a service that serves gRPC is answered by its HTTP endpoint
- added `features/grpc-deployed/exposed.feature`: an endpoint that admits only a named service refuses a call from outside the cluster
- added `features/grpc-deployed/exposed.feature`: an endpoint that admits the gateway admits a call from outside the cluster
- added `features/grpc-deployed/exposed.feature`: a service that declares gRPC and is not exposed answers no call from outside the cluster
- added `features/grpc-deployed/exposed.feature`: an exposed service that does not declare gRPC is exposed as it was before
- added `features/grpc-deployed/exposed.feature`: an exposed service that serves gRPC and no HTTP answers a gRPC call at its hostname
- added `features/grpc-deployed/exposed.feature`: a stream reaches a developer outside the cluster a part at a time
- added `features/grpc-deployed/exposed.feature`: a method that takes a stream and answers with a stream is called from outside the cluster
- added `features/grpc-deployed/exposed.feature`: a member is shown why an exposed service's gRPC cannot be reached at its hostname

---

### User Story 5 - Call another service's gRPC endpoint (Priority: P2)

A developer's service needs something from another service that serves gRPC. From a handler — an
endpoint, a workflow step, a consumer, an agent's tool — it asks the platform for a client to that
service by name (and by project, when the service is in another one), and calls its methods with
the code generated from the other service's `.proto` file. In a cluster the call presents this
service's own identity, so the other service's ACL sees who is calling, and the call is
only ever sent to a workload whose certificate names the service that was asked for. On a
developer's machine the other service is found the way the HTTP service client finds it.

When the other service cannot be found, does not serve gRPC, or is not the service it claims to
be, the developer is told which — not left to read a transport error.

**Why this priority**: Inside a cluster there is no other way for one service to call another's
gRPC endpoint. It is listed after stories 3 and 4 because it needs something deployed to call.

**Independent Test**: Deploy two services, one serving gRPC with a rule that admits only the other
by name; call from the admitted service and get an answer; call from a third service and be
refused; point the client at a service name that does not exist and read an error that names it.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/grpc/calling.feature`: a service calls a gRPC endpoint of another service of its project by name
- added `features/grpc/calling.feature`: a service calls a gRPC endpoint of a service of another project by project and name
- added `features/grpc/calling.feature`: a call is not sent to a workload that is not the service asked for
- added `features/grpc/calling.feature`: a call to a service that cannot be found fails, naming the service
- added `features/grpc/calling.feature`: a call to a service that does not serve gRPC fails, saying so
- added `features/grpc/calling.feature`: a refusal by the called service reaches the calling handler as that refusal
- added `features/grpc/calling.feature`: a service calls a method of another service that takes a stream and answers with a stream
- added `features/grpc/calling.feature`: on a developer's machine a service calls a gRPC endpoint of another service running there

---

### User Story 6 - Stream in either direction (Priority: P2)

A method of the `.proto` service returns a stream: the changes to a cart as they happen, the tokens
of an agent's reply. The developer's handler returns the stream and the platform delivers each
part to the caller as it is produced, no faster than the caller reads. When the caller goes away,
the stream stops being produced. A stream that ends in a refusal ends with that refusal's status
after the parts already sent.

A method may equally take a stream: the items of an import, the turns of a conversation. The
handler reads each part as the caller sends it, no faster than it chooses to, and answers once when
it has read enough, or with a stream of its own while requests are still arriving. A handler is
told when the caller went away without finishing, and may end the call early with a refusal
without reading the rest.

**Why this priority**: Streaming is one of the two reasons to choose gRPC over HTTP, and the
platform already streams an agent's reply. Methods with one request and one answer are complete
without it.

**Independent Test**: Add to the sample a method that streams an agent's reply, one that takes a
stream of items and answers with a count, and one that answers each part it is sent; call each,
then disconnect mid-stream in each direction and confirm the service stops producing and the
handler is told.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/grpc/streaming.feature`: a method that answers with a stream delivers each part as it is produced
- added `features/grpc/streaming.feature`: a stream is produced no faster than it is read
- added `features/grpc/streaming.feature`: a stream stops being produced when the developer who called goes away
- added `features/grpc/streaming.feature`: a stream that ends in a refusal ends the call with that refusal's status
- added `features/grpc/streaming.feature`: a call the ACL refuses ends before any part is sent
- added `features/grpc/request-streams.feature`: a handler for a method that takes a stream reads each part as it is sent
- added `features/grpc/request-streams.feature`: a handler answers each part of a stream as it arrives
- added `features/grpc/request-streams.feature`: a stream is sent no faster than the handler reads it
- added `features/grpc/request-streams.feature`: a handler is told when the developer who called goes away before ending the stream
- added `features/grpc/request-streams.feature`: a handler that refuses before the stream ends ends the call with that refusal's status
- added `features/grpc/request-streams.feature`: a part the endpoint cannot read ends the call as an invalid argument

---

### User Story 7 - Replace and add instances without stranding callers (Priority: P3)

A member deploys a new version of a service that other services are calling over gRPC. Callers
hold connections open for a long time, so the two things that go wrong with long-lived connections
must not: a caller connected to an instance that is being replaced is not refused, and when the
service is given more instances the new ones come to take their share of calls, without every
caller being restarted.

**Why this priority**: Nothing in stories 1 to 6 is wrong without it, on the day of the first
deploy. It is what makes the second deploy uneventful, and it is cheap to lose and hard to notice.

**Independent Test**: With one service calling another steadily over gRPC, roll the called service
and count refused calls (none); then raise its instance count and confirm that within the stated
time each new instance has answered calls.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/grpc-deployed/replacement.feature`: no call is refused while a service's instances are replaced one at a time
- added `features/grpc-deployed/replacement.feature`: an instance added to a service comes to answer calls from a service that was already calling
- added `features/grpc-deployed/replacement.feature`: no call from outside the cluster is refused while an exposed service's instances are replaced
- added `features/grpc-deployed/replacement.feature`: a stream on an instance that is stopping is given time to finish, then ends as unavailable

---

### User Story 8 - See gRPC calls in traces and metrics (Priority: P3)

A developer looking at the local console's traces sees a gRPC call as they see an HTTP request: the
root of a trace, named by its service and method, with every component call it caused beneath it.
A call the endpoint or a component refused is shown as refused, not as failed. The service's
metrics count gRPC calls by method and by how they ended.

**Why this priority**: The calls work without it. Without it they are invisible, and the first
question after "it is slow" has no answer.

**Independent Test**: Call a gRPC method that calls an entity, and read the trace: one root named
for the method, the entity's span beneath it; call a method that is refused and confirm the trace
shows a refusal.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/grpc/traces.feature`: a gRPC call is the root of the trace of everything it caused
- added `features/grpc/traces.feature`: a refused gRPC call is recorded as refused, not as failed
- added `features/grpc/traces.feature`: a failed gRPC call is recorded as failed
- added `features/grpc/traces.feature`: a call answered with a stream is one call in the trace
- added `features/local-console/grpc.feature`: the local console lists a service's gRPC methods and does not offer to call them

---

### User Story 9 - Let a tool ask a service what it serves (Priority: P3)

A developer wants to explore a service with a standard gRPC tool, without handing it the `.proto`
file: list the service definitions, list their methods, see what a request looks like, make a
call. They opt the service into reflection and, because reflection is one more thing that can be
called, state who may ask, in the same words as any endpoint's ACL. From then on a tool that the
ACL admits is answered wherever the service is reached: on the developer's machine, at its gRPC
address, and at its hostname when it is exposed.

A service that has not opted in answers no reflection. Opting in is a decision to describe the
whole service: reflection lists the methods of every gRPC endpoint, including one whose own ACL
admits nobody, to whoever reflection's ACL admits. It changes nothing about who may call them.

**Why this priority**: Everything works without it for a caller that holds the `.proto` file. It
is what makes the first five minutes with someone else's service pleasant, and what a later
feature would need for the local console to call a method.

**Independent Test**: Run the sample without reflection and confirm a standard tool is told the
service does not answer it; opt in with an ACL that allows all, list the methods and call one with
no `.proto` file; deploy and expose it with an ACL that admits one named service and confirm a
tool at the hostname is refused and told nothing.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/grpc/reflection.feature`: a service that has not opted into reflection answers none
- added `features/grpc/reflection.feature`: a service that opts into reflection tells a tool its service definitions and their methods
- added `features/grpc/reflection.feature`: a service that opts into reflection must state who may ask
- added `features/grpc/reflection.feature`: a tool that reflection's ACL does not admit is refused and told nothing
- added `features/grpc/reflection.feature`: reflection lists a method whose endpoint denies all, and the method still refuses every call
- added `features/grpc-deployed/exposed.feature`: an exposed service that opts into reflection answers a tool outside the cluster

---

### User Story 10 - Read how to build one, and what it does not do (Priority: P3)

A developer finds a page in the documentation that takes them from a `.proto` file to a tested
gRPC endpoint, with samples taken from tested code. The descriptor's reference says how a service
declares gRPC. The networking page says what address a service's gRPC has and who can connect to
it. The limitations page stops saying the platform has no gRPC, and says instead exactly what this
version leaves out.

**Why this priority**: A feature nobody can find is not shipped, and a limitation nobody was told
about is a bug report.

**Independent Test**: Build the documentation; follow the gRPC page from an empty project to a
passing test; search the limitations page for each thing this spec puts out of scope.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/documentation/grpc.feature`: the documentation describes building and testing a gRPC endpoint
- added `features/documentation/grpc.feature`: the documentation says what gRPC does not do

---

### Edge Cases

Each of these is answered, and is a scenario named under the story it belongs to unless it says
otherwise.

- **A method of the service definition has no handler.** The service does not start, and names the
  method (story 1).
- **A handler answers before the caller has finished sending.** The call ends with the handler's
  answer or refusal, and the parts not yet read are not read (story 6).
- **A caller goes away while sending a stream.** The handler reads what arrived and is told the
  stream ended unfinished (story 6).
- **One part of a stream of requests cannot be decoded.** Invalid argument; the call ends
  (story 6).
- **A call names a service definition or a method that nothing serves.** It is answered as
  unimplemented (story 1) — unless the endpoint is closed to the caller, in which case the caller
  is refused as for any other method (story 2).
- **A request cannot be decoded.** Invalid argument; no handler runs (story 1).
- **A handler throws something that is not a refusal.** Internal error, with no detail
  of the failure sent to the caller; the detail is in the service's log (story 1).
- **A caller's deadline passes before the handler answers.** The caller sees its deadline exceeded.
  What the handler had already asked of other components is not undone; this is the same as an HTTP
  caller that stops waiting. *(A requirement, FR-013; not a scenario of its own.)*
- **A request larger than the platform's limit.** Refused as too large, with the limit in the
  documentation. How large an answer a caller accepts is the caller's own limit. *(FR-014.)*
- **A service registers a gRPC endpoint and its descriptor does not declare gRPC.** The instance
  serves gRPC and nothing can reach it: it has no address and no workload is admitted to the port.
  The documentation says so. The platform cannot know what a service registered before it runs.
  *(FR-023.)*
- **A descriptor declares gRPC and the service serves none.** Never ready; reported as failed with
  the reason when the rollout's deadline passes (story 3).
- **The gRPC port is already in use on a developer's machine.** The service does not start, and
  names the port. Tests use a port the system picks. *(FR-005.)*
- **The service is exposed and declares gRPC.** Its hostname answers gRPC calls and HTTP requests
  alike (story 4).
- **The service is exposed and declares no gRPC.** Exposure is exactly as it was; a gRPC call to
  its hostname reaches no handler (story 4).
- **The service declares gRPC and is not exposed.** Nothing outside the cluster reaches it
  (story 4).
- **The gateway has not accepted what routes gRPC to the service.** The member is shown that, with
  the reason, as for HTTP (story 4).
- **A web page calls a gRPC endpoint directly.** Not served; a limitation. *(FR-039.)*
- **A tool asks a service that has not opted into reflection what it serves.** Unimplemented, as
  for any method nothing serves (story 9).
- **Reflection is opted into and a closed endpoint exists.** Its methods are listed to whoever
  reflection's ACL admits, and still refuse every call (story 9). The documentation says that
  opting in describes the whole service.
- **A caller sends a header naming itself as another service.** In a cluster it is ignored; the
  certificate decides (story 3).
- **The called service's certificate names a different service.** The call is not sent (story 5).
- **A caller is connected to an instance being stopped.** It is told to reconnect before the
  instance stops answering, and calls in progress are given time to finish (story 7).
- **A stream's caller disappears without saying so.** The service asks an idle connection whether
  it is still there, and stops producing within a minute of getting no answer. Through the gateway,
  the gateway decides when the caller has gone. *(FR-051.)*

## Requirements *(mandatory)*

### Functional Requirements

**Defining and serving**

- **FR-001**: A developer MUST be able to implement a service definition written in a `.proto` file
  as a gRPC endpoint of a Scala service, each method of the definition answered by a handler the
  endpoint declares for it.
- **FR-002**: A gRPC endpoint MUST be served only when it has been handed to the service explicitly,
  in the same way as an HTTP endpoint, and MUST receive the same clients an HTTP endpoint receives
  for calling components, reading views and calling other services.
- **FR-003**: A service MUST be able to serve HTTP endpoints, gRPC endpoints, or both at once.
- **FR-004**: A service MUST refuse to start, reporting every problem at once, when a gRPC endpoint
  leaves a method of its service definition without a handler, or shares its service definition
  with another endpoint.
- **FR-005**: On a developer's machine a service MUST serve gRPC without TLS on a port of its own,
  distinct from its HTTP port, which the developer can set; a test MUST be able to ask for a port
  the system picks; a port already in use MUST stop the service from starting, naming the port.
- **FR-006**: Handlers MUST run so that a blocking call to a component is as free as it is in an
  HTTP handler, and the call's context (caller, principal, metadata) MUST be readable on the
  handler's own thread.

**Statuses**

- **FR-007**: A component's refusal MUST reach the caller as a fixed gRPC status with the
  refusal's message: bad request as invalid argument, unauthorized as unauthenticated, forbidden
  as permission denied, not found as not found, conflict as failed precondition, timeout as deadline
  exceeded, unavailable as unavailable, internal as internal.
- **FR-008**: A handler's failure that is not a refusal MUST be answered as an internal
  error that discloses nothing of the failure, and MUST be written to the service's log.
- **FR-009**: A call naming a service definition or a method that no endpoint serves MUST be
  answered as unimplemented, subject to FR-018.
- **FR-010**: Every refusal and every failure MUST be a gRPC status. No answer to a gRPC call is an
  HTTP-style status with a JSON body.

**Streams**

- **FR-011**: A handler MUST be able to answer with a stream. Parts MUST be delivered as they are
  produced and no faster than the caller reads; a stream MUST stop being produced when its caller
  cancels or disconnects; a stream that ends in a refusal MUST end with that refusal's status.
- **FR-012**: A handler MUST be able to take a stream of requests, answering once or with a stream.
  It MUST be given each part as the caller sends it; the caller MUST be held to sending no faster
  than the handler reads; the handler MUST be able to answer while requests are still arriving, and
  to end the call early with an answer or a refusal; and it MUST be told when the caller cancelled
  or disconnected before finishing. A part that cannot be decoded ends the call as an invalid
  argument.
- **FR-013**: A caller's deadline MUST be honoured towards the caller: when it passes, the caller is
  answered as deadline exceeded. The platform does not undo what the handler already did.
- **FR-014**: A request larger than a documented limit MUST be refused as too large, and no handler
  runs. The size of an answer is limited by the caller's own client, not by the service.
- **FR-051**: A stream whose caller has gone without cancelling MUST stop being produced within a
  documented time, at most one minute, when the caller was connected to the service directly:
  another service, or a client on a developer's machine. For a caller outside the cluster the
  gateway decides when a connection is gone, and the documentation says so.

**Who may call**

- **FR-015**: A gRPC endpoint MUST state who may call it, with the rules an HTTP endpoint has: deny
  all, allow all, named callers, a predicate over the call, and an authenticator that establishes a
  principal. There MUST be no default.
- **FR-016**: A method MUST be able to state a rule of its own, which replaces the endpoint's for
  that method alone.
- **FR-017**: An authenticator's three ways of saying no MUST reach the caller as three statuses:
  unauthenticated (with what to present), permission denied, and unavailable. A refused call MUST
  NOT run its handler.
- **FR-018**: A call to a method an endpoint does not have MUST be judged by that endpoint's rule
  before it is answered as unimplemented, so a closed endpoint does not disclose its methods.
- **FR-019**: A handler MUST be able to read the workload that called it, the principal an
  authenticator established, and the call's metadata. An authenticator and a predicate MUST see the
  call's method and metadata as an HTTP one sees a request's path and headers, so that one
  authenticator can serve both kinds of endpoint.
- **FR-020**: In a cluster the caller MUST be read from the client certificate's platform identity
  and from nothing the call carries. A connection that presents no certificate of the installation
  MUST NOT reach any handler or any ACL. On a developer's machine every caller is the local
  caller, and the service says once at startup that named callers are not enforced there.

**Deployed**

- **FR-021**: A descriptor MUST be able to declare that a service serves gRPC, and on which port,
  with a documented default. Declaring nothing means no gRPC.
- **FR-022**: For a service that declares gRPC the platform MUST provide an address inside the
  cluster by which other services of the installation reach it; MUST serve it with the service's
  platform-issued certificate, requiring a client certificate; MUST admit to its port only workloads
  of the installation; and MUST NOT count an instance ready until it can answer gRPC calls.
- **FR-023**: For a service that does not declare gRPC the platform MUST render nothing new: no
  port, no address, no admission. A service deployed before this feature MUST NOT have its
  instances restarted, or any object the platform renders for it changed, by an upgrade of the
  platform to a version with this feature. This holds whether or not the service is exposed.
- **FR-024**: A service MUST be able to declare gRPC and no HTTP.
- **FR-025**: The platform MUST refuse, when a descriptor is applied and naming the reason, a
  descriptor that declares gRPC for a service hosted as a process or a module, one whose gRPC port
  equals its HTTP port, and one that sets the platform's own gRPC port variable.
- **FR-052**: The platform MUST refuse a descriptor that declares gRPC together with a runtime
  version older than the first that serves gRPC, naming both versions. Such a runtime ignores the
  declaration, and its instances would never be ready.
- **FR-026**: A service that declares gRPC and never serves it MUST be reported as failed, with a
  reason that says so, by the same deadline as a service that declares HTTP and never serves it.
- **FR-027**: Exposing a service that declares gRPC MUST make its gRPC endpoints reachable from
  outside the cluster at the service's existing hostname, over TLS with the hostname's certificate.
  The one hostname MUST answer HTTP requests and gRPC calls alike, each served by the endpoints of
  its kind. A service that declares gRPC and is not exposed MUST NOT be reachable from outside the
  cluster.

**Exposed**

- **FR-041**: Every call from outside the cluster MUST have the gateway as its calling workload,
  whichever hostname it arrived at. An ACL that admits the gateway admits it; one that
  admits only named services refuses it. Telling outside callers apart is an authenticator's work,
  from the call's metadata.
- **FR-042**: Exposing a service that does not declare gRPC MUST do exactly what it does today.
- **FR-043**: A stream MUST reach a caller outside the cluster a part at a time, as it does one
  inside. Any limit the gateway puts on how long a call may last or stay idle MUST be documented.
- **FR-044**: When the gateway has not accepted, or cannot resolve, what routes gRPC to an exposed
  service, the member MUST be shown that with the reason, as for HTTP today.
- **FR-045**: While an exposed service's instances are replaced one at a time, a call from outside
  the cluster MUST NOT be refused because an instance stopped.
- **FR-046**: Routing gRPC to an exposed service MUST NOT require the platform to know the service's
  service definitions or methods. The platform knows only what the descriptor declares.

**Reflection**

- **FR-047**: A service MUST be able to opt into reflection: answering a standard gRPC tool that
  asks which service definitions it serves, which methods they have, and what their requests and
  answers look like. A service that has not opted in MUST answer such a call as unimplemented.
- **FR-048**: Opting into reflection MUST require stating an ACL for it, in the vocabulary of
  FR-015 and with no default. A tool the ACL does not admit MUST be refused as any call is, and
  told nothing of what the service serves.
- **FR-049**: Reflection MUST describe every service definition the service's gRPC endpoints
  implement, whatever those endpoints' own ACLs, and MUST NOT change who may call them. FR-018
  holds for a service that has not opted in, and towards every caller reflection's ACL refuses.
- **FR-050**: Reflection MUST be answered wherever the service's gRPC is: on a developer's machine,
  at its gRPC address, and at its hostname when it is exposed, with the calling workload
  established as for any call.

**Calling**

- **FR-028**: A Scala service MUST be able to call a gRPC endpoint of another service of the
  installation by the service's name, or by project and name, using code generated from that
  service's `.proto` file. The client it calls through is created once by the service and handed
  to whichever of its components need it, as everything a component uses is; a component is not
  given one unasked.
- **FR-029**: In a cluster such a call MUST present the calling service's own identity, and MUST be
  sent only to a workload whose certificate names the service asked for; otherwise it fails before
  any request is sent.
- **FR-030**: A call that cannot be made MUST fail saying which of these it was: the service cannot
  be found, the service does not serve gRPC, or the workload that answered is not that service.
- **FR-031**: On a developer's machine the called service MUST be found as the HTTP service client
  finds one: where the developer configured it, otherwise where the running service announced
  itself.
- **FR-032**: A refusal or a failure from the called service MUST reach the calling handler as the
  same kind of refusal, so that a handler that lets it pass answers its own caller correctly.

**Replacing and adding instances**

- **FR-033**: While a service's instances are replaced one at a time, a call from the platform's
  gRPC client MUST NOT be refused because the instance it was connected to stopped.
- **FR-034**: After instances are added to a service, callers that were already connected MUST come
  to send calls to the new instances within a documented time, at most five minutes, without
  being restarted.
- **FR-035**: A stopping instance MUST give calls in progress, streams included, the same time to
  finish that an HTTP request is given, and then end them as unavailable.

**Observed, tested, documented**

- **FR-036**: A gRPC call MUST be recorded as the root of a trace, named by its service definition
  and method, with the component calls it caused beneath it, and MUST be recorded as ok, refused or
  failed by how it ended: a refusal by an ACL or a component is refused, not failed. Names
  recorded MUST be bounded by what the service declared.
- **FR-037**: The local console MUST list a service's gRPC methods beside its HTTP routes, and MUST
  NOT offer to call them.
- **FR-038**: A test MUST be able to start a whole service that has gRPC endpoints with the test
  kit, on a throwaway database and a port the system picks, and call them through a client
  generated from the `.proto` file. What it calls with comes with the gRPC library; a service that
  tests no gRPC carries none of it.
- **FR-039**: The documentation MUST gain a page on building and testing a gRPC endpoint, with
  samples from tested code; MUST describe the descriptor's gRPC declaration, the gRPC address and
  who may connect to it, how an exposed service's gRPC is called from outside the cluster, and how
  a service opts into reflection and what that discloses; and MUST state as limitations: Scala
  only, no gRPC-web (a web page cannot call a gRPC endpoint directly), and no calling a gRPC method
  from the local console. Statements that a service has one port and no gRPC MUST be corrected.
- **FR-040**: A new Scala service made with `ankka init` MUST build and test unchanged; adding a
  gRPC endpoint to it MUST need only what the documentation page states.

### Key Entities *(each a term in the project glossary)*

- **gRPC endpoint**: An endpoint that turns gRPC calls into calls to the service's components. It
  implements one service definition and states who may call it.
- **HTTP endpoint**: What "endpoint" has meant until now: an endpoint that turns HTTP requests into
  calls to components. "Endpoint" alone now means either.
- **service definition**: A named set of methods written in a `.proto` file, which a gRPC endpoint
  implements and a client is generated from.
- **method**: One thing a service definition lets a caller ask for. Its request is one message or a
  stream of them, and so is its answer.
- **status**: How a gRPC call ended, as the caller is told: ok, or one named kind of refusal or
  failure, with a message.
- **metadata**: The names and values a caller sends with a call beside its request, as headers
  accompany an HTTP request.
- **ACL**: What an endpoint or a method says about who may call it.
- **calling workload**: The workload a call came from, as the platform established it: a named
  service, the gateway, or the local caller.
- **principal**: Who a call came from, as an authenticator established it.
- **gRPC address**: Where a deployed service that declares gRPC is reached, inside the cluster, by
  other services.
- **hostname**: Where an exposed service answers from outside the cluster, now for gRPC calls as
  well as HTTP requests.
- **gateway**: What every request and call from outside the cluster passes through, and so the
  calling workload of each of them.
- **reflection**: A service's answer to a tool that asks what it serves. Given only by a service
  that opted in, to those its own ACL admits.
- **descriptor**: The document that states a service's desired state, which now also says whether
  the service serves gRPC and on which port.
- **gRPC client**: What a service asks the platform for in order to call another service's gRPC
  endpoint as itself.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer who has built an HTTP endpoint on ankka can follow the documentation from
  an empty `.proto` file to a passing test of a gRPC method in under 30 minutes, and adds no line
  to the service that the page did not state.
- **SC-002**: Every kind of refusal by a component, and every way an ACL says no, reaches a
  caller as the status the documentation gives for it: all eight of the first and all four of the
  second, with no case answered as unknown.
- **SC-003**: In a cluster, of calls made to a gRPC address by a workload with no certificate of the
  installation, none reaches a handler or an ACL.
- **SC-004**: While a three-instance service is replaced one instance at a time under a steady 20
  calls a second from another service, no call out of at least 1,000 is refused or fails.
- **SC-005**: After a service goes from one instance to three under steady calls from one already
  connected caller, every instance has answered calls within 5 minutes, and the caller was not
  restarted.
- **SC-006**: Upgrading an installation to a version with this feature restarts no instance of any
  service whose descriptor does not declare gRPC, exposed or not, and changes none of the objects
  rendered for it.
- **SC-007**: A unary gRPC call that calls one entity takes no longer, at the median, than the same
  call made through an HTTP endpoint of the same service on the same machine.
- **SC-008**: A stream of 100,000 parts is delivered whole in either direction — to a caller that
  reads slowly, and from a caller to a handler that reads slowly — without the service's memory
  growing with the number of parts not yet read.
- **SC-009**: Each thing this spec puts out of scope is stated on the limitations page, and no page
  of the documentation says that the platform serves no gRPC.
- **SC-010**: A client outside the cluster, given only an exposed service's hostname and the
  installation's certificate authority, calls a gRPC method and is answered; an HTTP request to the
  same hostname is answered in the same test; and after the service is unexposed neither is.
- **SC-011**: With reflection opted in, a standard gRPC tool given only the service's address lists
  its methods and calls one with no `.proto` file; with it not opted in, the same tool learns
  nothing about the service.

## Assumptions

- **One hostname serves both kinds.** How the gateway tells a gRPC call from an HTTP request at one
  hostname is a planning decision, bounded by FR-046: it cannot depend on knowing a service's
  methods.
- **From outside, gRPC is always over TLS.** The gateway offers no gRPC without it, as it offers no
  HTTP without it beyond a redirect. A local installation answers at the same hostname and port
  as it does for HTTP.
- **Scala only in this version.** Process-hosted (Python, TypeScript) and module-hosted (Rust)
  services neither declare a gRPC endpoint nor get a gRPC client. The sidecar protocol, the SDKs
  and the conformance suite are untouched.
- **A handler can answer with a status of its own choosing**, as an HTTP handler can choose its
  response's status, for the cases the fixed mapping does not cover.
- **The developer's build generates code from `.proto` files.** The platform documents the build
  setup and publishes what the generated code needs at run time; it does not ship a generator of
  its own. The messages of a service definition are the API's, and an endpoint translates between
  them and the service's own types as an HTTP endpoint translates JSON bodies.
- **The default gRPC port is fixed and documented**, and differs from HTTP's 9000. The port's
  number is a planning decision.
- **The limit on a message's size is the conventional 4 MB** unless planning finds a reason to
  differ, and is documented.
- **A deployed gRPC address is found by name**, as the HTTP address is, and its port is discovered
  rather than assumed, because the descriptor chooses it.
- **Calls between services are not joined into one trace.** A call to another service starts a new
  trace in the called service, as an HTTP call between services does today.
- **Any workload of the installation may connect** to a gRPC address, in any project, as for HTTP;
  whether a call is served is the ACL's decision. A project is not a network boundary for
  gRPC either.
- **The gateway is one caller**, for gRPC as for HTTP. The platform authenticates nobody at the
  gateway; an ACL that admits the gateway admits anyone who can reach the hostname.
- **Reflection is the standard one.** Existing gRPC tools use it unchanged; the platform invents no
  protocol of its own for describing a service.
- **The installation's console is unchanged** beyond showing what a descriptor declares. It lists no
  methods of a deployed service.
- **Stories 3, 4, 5 and 7 are proved against a real cluster**, with real certificates and real
  network policy, as the platform's existing zero-trust and multi-instance behaviour is. Nothing less can
  show a connection refused by the network or a certificate that names the wrong service.
