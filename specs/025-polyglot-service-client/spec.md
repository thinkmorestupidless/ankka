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
- **The API is the Scala API's shape, in each language's idiom.** `client.services(name)` or
  `client.services(project, name)` answers a client with `get`, `post`, `put`, `delete` and
  `request`, and the same three errors. A developer who has read the Scala page knows the Python
  one.
- **Nothing is added that the Scala client does not already do.** No retries, no redirects, no
  connection pooling policy, no streaming body. Those would be new behaviour for Scala too, and a
  feature that adds them to one door and not the others would make the doors differ.

This feature is not a gateway between projects, which is the callee's ACL's business; not a way
to call something that is not an ankka service, which `request` to an arbitrary address would be
and is refused; and not the wasm import, which has its own blocking-pool rules and is feature 030.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A Python service calls a Scala service and is admitted by name (Priority: P1)

A developer writes a payment adapter in Python. When a provider's webhook arrives, the adapter must
tell the merchant service, written in Scala, that a payment was captured. The merchant service's
outcome route admits only the adapter, by name, through `allowCallers`. The adapter calls
`client.services("merchant").post("/internal/v1/payments/p1/capture", body)` and the merchant
service sees `ankka://payments/psp-gateway` as the caller and admits it. Nothing else can call that
route.

**Why this priority**: This is the capability. A process-hosted service that can be called but
cannot call is half a service, and every polyglot integration adapter needs this before it needs
anything else.

**Independent Test**: In the k3s suite, deploy a Python service and a Scala service in one project;
the Scala endpoint admits only the Python service by name. Drive the Python service to make the
call and assert the Scala service answered 200 and recorded the caller as the Python service. Call
the same route from the gateway and assert 403.

**Acceptance Scenarios**:

1. **Given** a process-hosted Python service and a Scala service whose route admits only the
   Python service by project and name, **When** the Python service calls that route through its
   service client, **Then** the call is admitted, the reply reaches the Python handler with its
   status, headers and body, and the Scala service's caller for that request is the Python
   service's identity.
2. **Given** the same route, **When** a request arrives from the gateway, **Then** it is refused
   with 403, which shows the admission in scenario 1 came from the certificate and not from the
   route admitting everyone.
3. **Given** a TypeScript service in place of the Python one, **When** it makes the same call,
   **Then** scenarios 1 and 2 hold for it.
4. **Given** a call to a service name that does not exist in the project, **When** it is made,
   **Then** the handler receives the unresolvable error naming the service, within the client's
   timeout, and no connection is attempted.
5. **Given** a callee whose certificate names a different service than the one asked for,
   **When** the call is made, **Then** it fails with the identity mismatch error before any
   request body is sent.
6. **Given** a callee that answers with a refusal from its own ACL, **When** the call is made,
   **Then** the handler receives the status and body the callee sent, not an error from the client.
7. **Given** the app container of a process-hosted service, **When** its filesystem and
   environment are inspected, **Then** it holds no certificate and no key, as before this feature.

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

1. **Given** a workflow whose step calls another service through the step's context, **When** the
   workflow runs, **Then** the call is made with the service's identity and the step proceeds with
   the reply.
2. **Given** an agent tool that calls another service through the agent's context, **When** the
   model invokes the tool, **Then** the call carries the agent's service identity and the tool's
   result is the reply.
3. **Given** a consumer that forwards each change to another service, **When** a change is
   delivered, **Then** the call is made before the consumer's effect is recorded, so a failed call
   redelivers the change.
4. **Given** a timed action that calls another service, **When** its timer fires, **Then** the call
   is made with the service's identity.
5. **Given** a call made from a step, **When** the service's traces are read, **Then** the outbound
   call appears as a span under the step's span.
6. **Given** a service started with no service directory and no local registry, **When** a step
   calls another service, **Then** the error says no services are configured, as the endpoint's
   client already does.

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

1. **Given** the conformance suite, **When** it runs against the in-process Scala reference,
   **Then** a case makes a call through the sidecar's `Request` RPC to a scripted target and asserts
   the method, path, headers and body the target received and the status and body returned.
2. **Given** the Python conformance target, **When** the suite runs, **Then** the same case passes.
3. **Given** the TypeScript conformance target, **When** the suite runs, **Then** the same case
   passes.
4. **Given** an SDK declaring a protocol minor version below the one that introduced `Request`,
   **When** its process calls `Request`, **Then** the sidecar answers that the RPC is not in the
   declared version rather than serving it.
5. **Given** the generated protocol stubs, **When** the TypeScript SDK's typecheck and the Python
   SDK's `mypy` run, **Then** both pass with the new message types.

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

1. **Given** the documentation, **When** a reader looks for calling another service, **Then** one
   page shows the call in each of Scala, Python and TypeScript, with the sample included from
   tested code.
2. **Given** the limitations page, **When** it is read, **Then** the sentence saying Python and
   TypeScript services cannot call another service as themselves is gone.
3. **Given** the documentation build, **When** it runs, **Then** every changed page is in the
   navigation and in at least one skill.

---

### Edge Cases

- **A call from inside an entity command handler.** The Scala entity context does not gain
  `services`. A blocking outbound call inside the single-writer path of an entity would hold every
  other command to that id for the duration; the rule that an entity handler performs no I/O
  stands. A process-hosted entity handler can technically call `Request`; the sidecar refuses it
  with an error naming the component kind, so the rule is the runtime's and not the SDK's.
- **A request to a path that is not under the callee's HTTP port.** There is one port per service;
  `Request` names a service and a path, never a host or a port.
- **A body larger than the callee accepts.** The callee's refusal is returned as its status and
  body; the client does not retry or truncate.
- **The callee is mid-replacement.** The connection is refused for up to a second during a rolling
  update; the client answers `ServiceCallFailed` and does not retry, as the Scala client does
  today. A caller that needs retry writes it, or relies on a consumer's redelivery.
- **The local console's registry names a service that has since stopped.** The local resolver
  answers an address nothing listens on; the error is `ServiceCallFailed`, not unresolvable.
- **Two calls in flight from one process.** Each is its own RPC on the sidecar's client port and
  they do not serialise behind one another.
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
  resolution, the TLS configuration and the identity check are the ones a Scala service uses.
- **FR-003**: The protocol's minor version MUST be raised, and a process declaring a lower minor
  version MUST be refused `Request` with an error naming the version, not served it.
- **FR-004**: A `Request` made from an entity command or event handler MUST be refused by the
  sidecar with an error naming the component kind.
- **FR-005**: The sidecar MUST strip or replace any request header the platform uses to carry a
  caller's identity before sending, so a process cannot impersonate another caller.

**SDKs**

- **FR-006**: The Python and TypeScript SDKs MUST expose a service client reachable from the
  component client, addressed by service name or by project and name, with `get`, `post`, `put`,
  `delete` and a raw request in the shape of the Scala API.
- **FR-007**: The three Scala error cases (unresolvable, identity mismatch, call failed) MUST have
  a distinguishable counterpart in each SDK.
- **FR-008**: The conformance suite MUST gain a case that exercises `Request` end to end against a
  scripted target, and every SDK's conformance target MUST pass it.

**Scala contexts**

- **FR-009**: The agent, workflow, consumer and timed-action contexts MUST carry a `ServiceClients`
  built from the same runtime client the endpoint receives.
- **FR-010**: The entity contexts MUST NOT carry a `ServiceClients`.
- **FR-011**: An outbound call made from a handler MUST be recorded as a span under the handler's
  span in the service's traces.

**Documentation**

- **FR-012**: The limitations page MUST no longer state that Python and TypeScript services cannot
  call another service as themselves, and one guide page MUST show the call in every language from
  tested samples.

### Key Entities

- **Request**: a protocol message naming a target service (name, optional project), a method, a
  path, headers and an optional body.
- **Reply**: a protocol message carrying a status, headers and a body, or one of the three error
  cases.
- **ServiceClients**: the per-service factory of service clients, now present on every
  non-entity component context.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A process-hosted service in Python or TypeScript can be admitted by name by another
  service's ACL, proven on a real cluster by one admitted call and one refused call from the
  gateway to the same route.
- **SC-002**: Every component kind except entities can make an outbound call with the service's
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

## Open Questions

- Whether `Request` should be offered in local mode, through the local console's registry as the
  Scala client is, so two Python services on one laptop can reach each other without a cluster.
  [NEEDS CLARIFICATION: the local registry is written by `AnkkaService` and read by
  `HttpServiceClients`; the sidecar runs locally only through the SDK testkits, which may not
  register.]
- Whether the per-call timeout should be a parameter of `Request` or the sidecar's single
  configured client timeout.
