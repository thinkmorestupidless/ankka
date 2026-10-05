# Feature Specification: Polyglot Service Client — Calling Another Service as Yourself from Any Language and Any Component

**Feature Branch**: `025-polyglot-service-client`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "A Python or TypeScript service cannot call another ankka service as
itself: only a Scala HTTP endpoint has a service client that presents the service's certificate.
Add a `Request` RPC to the sidecar's `Client` service that makes an outbound HTTP call to a named
service through the runtime's existing service client, so the certificate, the directory lookup and
the identity check are shared and unchanged; expose it in the Python and TypeScript SDKs in the
shape of the Scala API; and give the Scala agent, workflow, consumer and timed-action contexts the
`ServiceClients` that today only an endpoint receives. Out of scope: the WebAssembly import (feature
030), retries, redirects, streaming bodies, and any change to how a callee decides who it admits."

## Context

A Scala service can already call another service as itself. `ServiceClient` and `ServiceClients`
in `modules/sdk/src/main/scala/com/thinkmorestupidless/ankka/sdk/ServiceClient.scala` offer `get`,
`getText`, `post`, `put`, `delete` and a raw `request(method, path, body, contentType, headers)` to
a service by name, or by project and name, with three errors: `ServiceCallFailed`,
`ServiceUnresolvable` and `ServiceIdentityMismatch`. The runtime's implementation,
`modules/runtime/.../runtime/HttpServiceClients.scala`, resolves
`<name>.<prefix>-<project>.svc.cluster.local` and reads the port from the `_http._tcp` SRV record
when the cluster's service directory is configured, connects over mutual TLS through `RotatingTls`
with the certificate cert-manager issues, and refuses the handshake when the callee's certificate
does not carry `ankka://<project>/<name>`. Locally it reads `ankka.local-services.<name>` or the
local console's registry over plain HTTP. Calls block, which costs nothing on a virtual thread, and
carry no retries and no redirects.

That client reaches user code in exactly one place. `HttpServer.scala` builds an `EndpointClients`
whose `services` field holds it, and only an HTTP endpoint receives an `EndpointClients`. The agent,
workflow, consumer and timed-action contexts in `modules/sdk` and `modules/agent` have no
`services` at all. A workflow step that must call a payment provider's facade in another service,
or an agent tool that must read another service's view, has no way to do so with the service's
identity: it either goes without, or opens its own plain HTTP connection that the callee sees as
nobody.

For a process-hosted service the gap is total. The sidecar protocol's `Client` service in
`protocol/src/main/protobuf/ankka/protocol/v1/client.proto` has `Invoke`, `InvokeStream`, `Query`,
`Schedule` and `Cancel`, every one addressed to a component in the same service by kind, component
id and entity id. There is no RPC that reaches another service. The Python and TypeScript SDKs
(`sdks/python/src/ankka/client.py`, `sdks/typescript/src/client.ts`) offer only a `ComponentClient`
for calls, views and timers; `httpx` and `fetch` appear only in their testkits. The inbound side is
complete, since `endpoint.proto`'s `HttpRequest.caller` tells a process whether the gateway, a named
service or a local caller is at the other end. The limitations page says it plainly: "Python and
TypeScript services cannot call another service as themselves. They can be called, and read the
caller; only a Scala service has a service client that presents its certificate."

The consequence for any system of more than one service is that its non-Scala services cannot
participate in the caller-identity model the platform is built on. A callee's `Acl.AllowCallers`
decides from the certificate, and a caller that has no certificate to present can only be admitted
by `AllowAll`, which is the same as being reachable from the internet. Every integration adapter
written in Python or TypeScript, which is the language such adapters are usually written in, is
therefore forced to be either an endpoint only or a caller without an identity.

Four decisions shape this feature.

- **One client, three doors.** The runtime's `HttpServiceClients` is the only implementation. The
  sidecar's new `Request` RPC calls it; the Scala contexts carry it; the wasm import in feature 030
  will call it. There is no second resolver, no second TLS configuration and no second identity
  check to drift from the first.
- **The sidecar makes the call, not the process.** A process-hosted service's certificate lives on
  the sidecar, and the app container is deliberately given none. The process hands the sidecar a
  request and receives a reply; it never sees a key. That is the same division the whole polyglot
  design rests on: the process decides, the runtime does what needs credentials.
- **The API is the Scala API's shape, in each language's idiom.** A component that may call
  another service has `services`, and naming a service, or a project and a service, answers a
  client with `get`, `post`, `put`, `delete` and `request`, and the same errors: the three the Scala client has, and a fourth this feature adds
  to every door for a call nothing answered. A developer who has read the Scala page knows the Python
  one.
- **Nothing is added that the Scala client does not already do.** No retries, no redirects, no
  connection pooling policy, no streaming body. Those would be new behaviour for Scala too, and a
  feature that adds them to one door and not the others would make the doors differ.

This feature is not a gateway between projects, which is the callee's ACL's business; not a way
to call something that is not an ankka service, which `request` to an arbitrary address would be
and is refused; and not the wasm import, which has its own blocking-pool rules and is feature 030.

## Clarifications

### Session 2026-10-04

- Q: Does a call to another service through `Request` work on a developer's machine? → A: Yes,
  exactly as the Scala client does: `ankka.local-services.<name>` first, then the local console's
  registry. No new mechanism; a sidecar in a container is told addresses through that setting.
- Q: How long does a call to another service wait for its answer? → A: One setting on the service
  (`ankka.service-client.timeout`, default the present 30 seconds), read by the runtime in a Scala
  service and in the sidecar alike. No per-call parameter in any language.
- Q: What happens to a call to another service that the platform cannot tie to a handler? → A: It
  is allowed and counted from the unknown caller. Only a call the platform can tie to an entity's
  handler is refused.
- Q: Are the glossary terms the features needed right? → A: Yes: `model`, `SDK` (refusing
  "library"), `conformance suite`, `scripted service` and `skill` stand as defined. "Header" is
  not made a term; the features describe what a call says without it.
- Q: (implementation, V1) The JDK's client sends a `GET` or `HEAD` once more when its connection
  closes before any answer, and no setting turns that off. Accept it? → A: Yes. A request that may
  change something is sent at most once; a `GET` or `HEAD` at most twice, and the documentation
  says so.
- Q: What is a handler told when the other service never answers (a refused connection, a
  timeout)? → A: A fourth named error in all three languages, `ServiceUnanswered(service,
  reason)`. `ServiceCallFailed` keeps meaning that the service answered with a status other than
  2xx. The Scala client wraps the JDK's exceptions it lets through today.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A Python service calls a Scala service and is admitted by name (Priority: P1)

A developer writes a payment adapter in Python. When a provider's webhook arrives, the adapter must
tell the merchant service, written in Scala, that a payment was captured. The merchant service's
outcome route admits only the adapter, by name, through `allowCallers`. The adapter calls
`self.services("merchant").post("/internal/v1/payments/p1/capture", body)` and the merchant
service sees `ankka://payments/psp-gateway` as the caller and admits it. Nothing else can call that
route.

**Why this priority**: This is the capability. A process-hosted service that can be called but
cannot call is half a service, and every polyglot integration adapter needs this before it needs
anything else.

**Independent Test**: In the k3s suite, deploy a Python service and a Scala service in one project;
the Scala endpoint admits only the Python service by name. Drive the Python service to make the
call and assert the Scala service answered 200 and recorded the caller as the Python service. Call
the same route as another service and assert 403. In the k3s suite that has a gateway, where the
same Scala service runs, assert the route admits the Scala service it names and refuses a request
through the gateway (research R15).

**Acceptance Scenarios**:

- added `features/service-calls/calling.feature`: a service in every language is admitted by name by a route that admits only it
- added `features/service-calls/calling.feature`: a route that admits only one service by name refuses the gateway
- added `features/service-calls/calling.feature`: a refusal by the service called reaches the calling handler as that refusal
- added `features/service-calls/calling.feature`: a call to a service that cannot be found fails, naming the service, and is not sent
- added `features/service-calls/calling.feature`: a call is not sent to a workload that is not the service asked for
- added `features/service-calls/calling.feature`: a request that may change something is sent at most once when no answer comes
- added `features/service-calls/calling.feature`: on a developer's machine a service in every language calls another service running there
- added `features/service-calls/calling.feature`: a call that is not answered within the time its service is set to wait is unanswered
- added `features/service-calls/process.feature`: the process of a service that calls other services holds no certificate
- added `features/service-calls/process.feature`: nothing the process says in a call makes it come from another service
- added `features/service-calls/process.feature`: two calls a process makes at once are both answered without waiting for each other
- added `features/service-calls/process.feature`: an entity's handler in a process may not call another service
- added `features/service-calls/process.feature`: a call a process makes outside any handler is made and counted from the unknown caller

---

### User Story 2 - A Scala workflow step, agent tool, consumer or timed action calls another service (Priority: P1)

A developer writing a withdrawal workflow in Scala has a step that must ask the PSP gateway service
to initiate a payout. Today only an endpoint can reach another service; the step cannot. After this
feature the workflow's context carries `services`, and the step writes
`context.services("psp-gateway").post(...)` exactly as an endpoint would. The same is true of an
agent tool that reads another service's data, a consumer that forwards a fact to another service,
and a timed action that polls one.

**Why this priority**: The Scala half of the gap blocks the same domain stage as the Python half.
Cross-service workflows are the main reason to call another service at all.

**Independent Test**: In the testkit with a local service registry, register a second service on an
ephemeral port, run a workflow whose step calls it, and assert the step's transition recorded the
reply. Repeat from an agent tool, a consumer and a timed action.

**Acceptance Scenarios**:

- added `features/service-calls/components.feature`: a workflow's step calls another service and goes on with the answer
- added `features/service-calls/components.feature`: an agent's tool calls another service and answers the model with what it was given
- added `features/service-calls/components.feature`: a consumer calls another service before the change it handles is done with
- added `features/service-calls/components.feature`: a timed action calls another service when its timer fires
- added `features/service-calls/components.feature`: a workflow calls another service in a step and not in a command
- added `features/service-calls/components.feature`: a call made from a step is nested under the step in the trace
- added `features/service-calls/components.feature`: a step of a service that knows of no other services is told so
- added `features/service-calls/components.feature`: a service on a developer's machine calls a service that has stopped and the call is unanswered

---

### User Story 3 - The protocol and the SDKs agree, proven by the conformance suite (Priority: P2)

A maintainer adds the `Request` RPC to the protocol. The conformance suite, which every SDK must
pass, gains a case that makes an outbound call through the sidecar to a scripted target and asserts
the request the target saw and the reply the handler received. A future SDK cannot claim to be
complete without it.

**Why this priority**: Without a conformance case the two existing SDKs can each get the shape
slightly wrong, and the next SDK will. The case is cheap and it is how the protocol is kept honest.

**Independent Test**: Run `sbt 'sidecar/testOnly *ConformanceSuite'` in process and against each
SDK's conformance target; the new case passes for all three and fails for an SDK built from the
previous protocol version.

**Acceptance Scenarios**:

- added `features/service-calls/sdks.feature`: the conformance suite's call to another service passes for every SDK
- added `features/service-calls/process.feature`: a process made for a protocol version before calls to other services is not served one
- added `features/service-calls/sdks.feature`: an SDK's type checks accept a call to another service

---

### User Story 4 - The limitation is withdrawn and the guide shows every door (Priority: P3)

A developer reading the documentation for calling another service finds one page that shows the
same call in Scala, Python and TypeScript, from an endpoint and from a workflow step, and the
limitations page no longer says that Python and TypeScript services cannot call as themselves.

**Why this priority**: The feature works without it for someone reading the source, and for nobody
else.

**Independent Test**: The documentation build passes; every sample on the page is included from
tested code; the limitations page has no sentence claiming the gap.

**Acceptance Scenarios**:

- added `features/documentation/service-calls.feature`: one page shows a call to another service in every language
- added `features/documentation/service-calls.feature`: the documentation does not say that a service in some languages cannot call another as itself
- added `features/documentation/service-calls.feature`: every page about calling another service is listed and carried by a skill

---

### Edge Cases

- **A call from inside an entity command handler.** The Scala entity context does not gain
  `services`. A blocking outbound call inside the single-writer path of an entity would hold every
  other command to that id for the duration; the rule that an entity handler performs no I/O
  stands. A process-hosted entity handler can technically call `Request`; the sidecar refuses it
  with an error naming the component kind, so the rule is the runtime's and not the SDK's.
- **A call made outside any handler.** A process, or a Scala service holding a client, may call
  another service when no handler is running. The call is made with the service's identity and
  counted from the unknown caller; the entity rule refuses only what the platform can tie to an
  entity's handler, because it protects an entity's other commands and is not a security boundary.
- **A request to a path that is not under the callee's HTTP port.** There is one port per service;
  `Request` names a service and a path, never a host or a port.
- **A body larger than the protocol carries.** Through the sidecar a request's or an answer's
  body over 4,000,000 bytes is refused, naming the limit; there is no stream in either direction.
- **A call from a workflow's command handler.** Refused, naming "a step", for the reason an
  entity's is: it would block the workflow's other commands.
- **A body larger than the callee accepts.** The callee's refusal is returned as its status and
  body; the client does not retry or truncate.
- **The callee is mid-replacement.** The connection is refused or closed for up to a second during
  a rolling update; the client answers `ServiceUnanswered`. A request that may change something
  (`POST`, `PUT`, `DELETE`, `PATCH`) is sent at most once. A `GET` or a `HEAD` whose connection
  closes before any part of an answer arrives may be sent once more, by the JDK's client, which
  HTTP allows for a safe method and which no setting turns off (research, V1). A caller that needs
  more retries writes them, or relies on a consumer's redelivery.
- **The local console's registry names a service that has since stopped.** The local resolver
  answers an address nothing listens on; the error is `ServiceUnanswered`, not unresolvable.
- **Two calls in flight from one process.** Each is its own RPC on the sidecar's client port and
  they do not serialise behind one another.
- **The callee does not answer in time.** The call ends in `ServiceUnanswered` once the service's
  `ankka.service-client.timeout` has passed, and is not retried.
- **Headers the process sets that the platform owns.** A `Host` header or any header the sidecar
  uses to carry the caller's identity is replaced by the sidecar before sending, so a process
  cannot claim to be someone else to a callee that trusts a header.

## Requirements *(mandatory)*

### Functional Requirements

**Protocol**

- **FR-001**: The sidecar protocol's `Client` service MUST gain a `Request` RPC taking a target
  service name, an optional project, a method, a path, headers and an optional body, and
  answering with a status, headers and a body.
- **FR-002**: `Request` MUST be served by the runtime's existing `HttpServiceClients`, so the
  resolution, the TLS configuration and the identity check are the ones a Scala service uses. That
  includes a developer's machine: `Request` resolves a service from `ankka.local-services.<name>`,
  then from the local console's registry, over plain HTTP, and no other local mechanism is added.
- **FR-013**: How long a call waits for its answer MUST be one setting of the calling service,
  `ankka.service-client.timeout`, defaulting to the present 30 seconds and read by the same
  `HttpServiceClients` in a Scala service and in the sidecar. `Request` and the three languages'
  APIs MUST NOT carry a per-call timeout. The time allowed to connect is unchanged.
- **FR-003**: The protocol's minor version MUST be raised, and a process declaring a lower minor
  version MUST be refused `Request` with an error naming the version, not served it.
- **FR-004**: A `Request` made from an entity command or event handler MUST be refused by the
  sidecar with an error naming the component kind. A `Request` the sidecar cannot tie to any
  handler (made outside one, at process start or from background work) MUST be served, and its
  call counted from the unknown caller.
- **FR-005**: The sidecar MUST strip or replace any request header the platform uses to carry a
  caller's identity before sending, so a process cannot impersonate another caller.

- **FR-015**: A request's body and an answer's body through `Request` MUST each be at most
  4,000,000 bytes, the bound of the protocol's own messages. An SDK MUST refuse a larger request
  before sending and the sidecar MUST answer a larger answer with an error naming the limit
  (added in planning, research R8).

**SDKs**

- **FR-006**: The Python and TypeScript SDKs MUST expose a service client to every component
  that may call another service, addressed by service name or by project and name, with `get`,
  `post`, `put`, `delete` and a raw request in the shape of the Scala API. It MUST NOT be a member
  of the component client, which an entity holds too; an entity and a view are offered none
  (amended in planning, research R11).
- **FR-007**: A call MUST end in one of four distinguishable errors in each of Scala, Python and
  TypeScript: unresolvable and identity mismatch, where nothing was sent; call failed
  (`ServiceCallFailed`), where the service answered with a status other than 2xx, carrying that
  status and body; and unanswered (`ServiceUnanswered`), where no answer arrived because the
  connection was refused or the timeout passed. The raw request returns the answer whatever its
  status; only the typed `get`, `post`, `put` and `delete` turn a non-2xx answer into call failed.
- **FR-014**: The Scala client MUST raise `ServiceUnanswered` where it lets the JDK's own
  exceptions through today, so the three languages name the same four errors.
- **FR-008**: The conformance suite MUST gain a case that exercises `Request` end to end against a
  scripted target, and every SDK's conformance target MUST pass it.

**Scala contexts**

- **FR-009**: The agent, workflow, consumer and timed-action contexts MUST carry a `ServiceClients`
  built from the same runtime client the endpoint receives.
- **FR-010**: The entity contexts MUST NOT carry a `ServiceClients`. A workflow's command
  handlers share its steps' context, so a workflow's client MUST refuse a call made outside a
  step, in every language and in the sidecar, as its secret store does (added in planning,
  research R2 and R6).
- **FR-011**: An outbound call made from a handler MUST be recorded as a span under the handler's
  span in the service's traces.

**Documentation**

- **FR-012**: The limitations page MUST no longer state that Python and TypeScript services cannot
  call another service as themselves, and one guide page MUST show the call in every language from
  tested samples.

### Key Entities

- **Request**: a protocol message naming a target service (name, optional project), a method, a
  path, headers and an optional body.
- **Reply**: a protocol message carrying a status, headers and a body, or one of the four error
  cases.
- **ServiceClients**: the per-service factory of service clients, now present on every
  non-entity component context.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A process-hosted service in Python or TypeScript can be admitted by name by another
  service's ACL, proven on a real cluster by one admitted call and one call from another service
  refused by the same route; and that route is proven, on a real cluster with a gateway, to
  refuse a request from the gateway.
- **SC-002**: Every component kind except entities and views can make an outbound call with the service's
  identity in Scala, Python and TypeScript, each covered by a test.
- **SC-003**: The conformance suite covers `Request`, and all three conformance targets pass it.
- **SC-004**: No credential reaches a process-hosted app container as a result of this feature,
  verified by inspecting the container.
- **SC-005**: The limitations page carries no claim that this gap exists.

## Assumptions

- The service directory and SRV lookup that `HttpServiceClients` performs today are correct and
  are not changed by this feature.
- A callee's ACL and the caller's certificate are the whole of authorization; this feature adds
  no token to outbound calls.
- A sidecar run in a container on a developer's machine cannot see the host's local registry; it
  is told another service's address through `ankka.local-services.<name>`. The generated compose
  files and the SDK testkits are not changed to wire services together.
- The sidecar's client port stays bound to loopback, so `Request` is reachable only from the
  process in the same pod.
- The Rust and wasm side is feature 030 and shares the `Request` message shape defined here.

## Dependencies

- Gates domain stage 2 (deposits): the PSP gateway and the engagement service are process-hosted
  and must call merchant, payouts and others as themselves.
- 030-wasm-request-and-clock reuses the `Request` message and the sidecar's handling of it.
- 029-agent-approvals-and-mcp depends on the agent context carrying `ServiceClients` (FR-009).
- 026-telemetry-export depends on the outbound span of FR-011 to propagate trace context on the
  same call.
- No dependency on any other spec in this set.
