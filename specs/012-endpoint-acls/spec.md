# Feature Specification: Endpoint ACLs

**Feature Branch**: `012-endpoint-acls`

**Created**: 2026-09-25

**Status**: Draft

**Input**: User description: "i'd like to understand how close we are to emulating akka's ACL
implementation — https://doc.akka.io/sdk/access-control.html … yes, let's write this up"

## Context

ankka's access control is one value per endpoint. `HttpEndpoint` declares an abstract `def acl:
Acl`, the server applies it to every request that reaches that prefix, and the four constructors
— `DenyAll`, `AllowAll`, `AllowIf(predicate)`, `Authenticate(decide)` — cover deny, allow, a
caller-supplied check and a caller-supplied authenticator. On `Authenticate` the resulting
`Principal` is placed on the request context and the handler reads it as `principal`.

Compared with Akka's `@Acl`, the mechanism is equivalent and in one respect better: Akka's ACL has
a single refusal, while ankka's `AuthDecision` distinguishes "log in" (401, with a
`WWW-Authenticate` challenge), "you may not" (403) and "the verifier cannot answer right now" (503,
with `Retry-After`). A CLI that prints *forbidden* to someone whose login merely expired is the
failure that distinction exists to prevent.

Two gaps are real, and they are of different kinds.

**The first is a gap in the SDK, and this feature closes it.** Akka lets a method carry its own
`@Acl`, completely overriding the class's. ankka has no such thing, so an endpoint with five public
routes and one administrative route must be split into two endpoint classes with two prefixes, or
must abandon the ACL and re-check inside every handler. Neither is what the platform should ask
for. The same gap exists in the Python SDK, which additionally *defaults* its endpoint ACL to
`ALLOW_ALL` — quietly abandoning the stance the Scala SDK enforces at the type level, that an
unstated ACL is a decision nobody made.

**The second is a gap in the platform, and this feature only writes it down.** Most of Akka's ACL
vocabulary names *who is calling*: `INTERNET`, a specific deployed service, `*` for any service,
`SELF`, `BACKOFFICE`. Those principals are trustworthy because Akka terminates mutual TLS and
guarantees the identity cannot be spoofed. ankka has no mesh, no mTLS and no service-to-service
invocation; a service's in-cluster address is reachable from every namespace on plain TCP, and
nothing at the gateway does more than route. `Acl.AllowIf` says as much in its own documentation:
ankka ships no "named service" principal because a check against a client-settable header would be
security theatre. That reasoning is correct and stays. What is missing is that a reader comparing
ankka with Akka cannot currently find it stated as a platform limitation — it lives in a source
comment. Naming an absent guarantee is worth more than implying it.

The distinction matters for what comes after. Closing the first gap is an afternoon in
`modules/http`. Closing the second is a service mesh, workload identity and a network policy, and
it is a feature of its own with its own evidence.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - One endpoint, two audiences (Priority: P1)

A developer has a `/carts` endpoint. Reading and updating a cart is public; a `DELETE
/carts/{cartId}` that purges a cart is for support staff only. They declare the endpoint's ACL as
the public one, and state a different ACL on the one route that needs it, without splitting the
endpoint or moving the check into the handler.

**Why this priority**: It is the whole of the SDK-side gap, it is what Akka users will look for
first, and every other story in this feature is either a smaller version of it or documentation
about it.

**Independent Test**: Declare one endpoint whose ACL is `AllowAll` with a single route under an
`Authenticate`, and drive it over HTTP: the public routes answer without a credential and the
protected route answers 401 with a challenge.

**Acceptance Scenarios**:

1. **Given** an endpoint whose ACL is `AllowAll` and one of whose routes states `Authenticate`,
   **When** a request with no credential reaches that route, **Then** the answer is 401 with a
   `WWW-Authenticate: Bearer` challenge, and a request to any other route of the same endpoint is
   served.
2. **Given** an endpoint whose ACL is `DenyAll` and one of whose routes states `AllowAll`,
   **When** a request reaches that route, **Then** it is served — a route's ACL replaces the
   endpoint's rather than adding to it.
3. **Given** an endpoint whose ACL is `DenyAll`, **When** a request reaches a path that matches no
   route of that endpoint, **Then** the answer is 403, not 404: a closed endpoint discloses nothing
   about which of its paths exist.
4. **Given** a route under an `Authenticate` on an endpoint whose own ACL does not authenticate,
   **When** the handler reads `principal`, **Then** it gets the principal that route's ACL
   established.
5. **Given** an endpoint that declares no route-level ACL anywhere, **When** any request reaches
   it, **Then** its behaviour is byte-for-byte what it was before this feature.

---

### User Story 2 - A Python endpoint states its ACL too (Priority: P2)

A developer writing a service in Python declares an endpoint. The SDK requires them to say who may
call it, as the Scala SDK does, rather than opening it to the internet because they did not think
about it.

**Why this priority**: It is a one-line default today that contradicts a documented platform
stance, and it is the kind of default that is discovered in production. It is separable from
US1 and is the smallest change in the feature.

**Independent Test**: Register a Python endpoint class that declares no `acl` and observe that
registration fails, naming the class, before the service serves anything.

**Acceptance Scenarios**:

1. **Given** a Python `Endpoint` subclass with no `acl` class attribute, **When** it is registered,
   **Then** registration raises an error naming the class and saying that an ACL must be declared.
2. **Given** a Python `Endpoint` subclass that declares `acl = Acl.ALLOW_ALL`, **When** it is
   registered, **Then** it serves exactly as it does today.

---

### User Story 3 - The absent guarantee is written down (Priority: P3)

Someone evaluating ankka against Akka, or deciding how to protect a service, reads the
documentation and finds stated plainly that ankka has no platform-established caller identity:
no mTLS, no internet-versus-internal distinction, no named-service principal, and why.

**Why this priority**: It changes no code and prevents the most expensive misunderstanding this
comparison can produce — assuming `Acl.AllowIf` can be handed a trustworthy caller identity.

**Independent Test**: Read `docs/reference/limitations.md` and `docs/reference/akka-divergences.md`
and find the gap named, with the reasoning; `just docs` passes.

**Acceptance Scenarios**:

1. **Given** the limitations page, **When** a reader looks under networking and security, **Then**
   they find that ankka establishes no caller identity of its own, that `Acl.AllowIf` is given only
   what the request carries, and that a header naming a calling service is not evidence of
   anything.
2. **Given** the Akka divergences page, **When** a reader looks for ACLs, **Then** they find which
   of Akka's principals ankka does not have and why.

---

### User Story 4 - A route in another language states its ACL (Priority: P4)

A developer writing a Python service gets the same per-route ACL their Scala colleague has: a
decorator takes an ACL, the sidecar applies it, and a route that says nothing inherits the
endpoint's.

**Why this priority**: Parity between the two SDKs is worth keeping, and the protocol is the right
place to hold the field. It is last because it costs a protocol addition, a sidecar mapping and a
conformance case, and because a Python service can reach US1's outcome today by declaring a second
endpoint.

**Independent Test**: Run the conformance suite against the Python SDK with an endpoint whose ACL
is `ALLOW_ALL` and one of whose routes is `DENY_ALL`, and confirm the route is refused while its
siblings are served.

**Acceptance Scenarios**:

1. **Given** a discovery message whose route carries no ACL, **When** the sidecar serves it,
   **Then** the endpoint's ACL applies, exactly as before this feature.
2. **Given** a discovery message whose route carries an ACL, **When** the sidecar serves it,
   **Then** that ACL applies to that route alone.

---

### Edge Cases

- **A route ACL declared twice, or scopes nested.** The innermost declaration wins, and that is
  stated rather than left to discovery.
- **An endpoint every one of whose routes is closed.** The startup warning that today fires on
  `acl = DenyAll` must fire when nothing on the endpoint is reachable, and must not fire on a
  `DenyAll` endpoint that opens a route.
- **`principal` on a route with no authenticating ACL.** It must still fail loudly, and the message
  must name the route rather than blaming the endpoint's ACL, which may now be a different one.
- **A streaming (SSE) route.** It takes a route ACL on the same terms as any other route; an ACL
  that refuses must refuse before the connection is held open.
- **`_ankka/health`.** Stays exempt from every ACL, unchanged.
- **A Python service that already ships without an `acl`.** It stops starting. This is a breaking
  change and is stated as one.

## Requirements *(mandatory)*

### Functional Requirements

**Per-route ACLs, Scala SDK**

- **FR-001**: An `HttpEndpoint` MUST be able to declare an ACL that applies to a subset of its
  routes, without splitting the endpoint or changing its prefix.
- **FR-002**: A route's ACL MUST completely replace the endpoint's for that route, never combine
  with it — the rule Akka states for a method-level `@Acl`.
- **FR-003**: A route that declares no ACL MUST use the endpoint's, so an endpoint written before
  this feature behaves identically.
- **FR-004**: Where route-ACL declarations nest, the innermost MUST apply, and the SDK's
  documentation MUST say so.
- **FR-005**: The server MUST match the route before deciding admission, and MUST apply the matched
  route's effective ACL.
- **FR-006**: When no route of the matched endpoint matches the path or method, the server MUST
  apply the **endpoint's** ACL before answering 404 or 405, so that a refused endpoint discloses
  nothing about which of its paths exist.
- **FR-007**: A principal established by a route's ACL MUST be on the request context for that
  route's handler, on the handler's own thread, as an endpoint-level ACL's principal is today.
- **FR-008**: Asking for `principal` where the effective ACL does not authenticate MUST throw, and
  the message MUST name the route.
- **FR-009**: The startup warning about an unreachable endpoint MUST be based on every route's
  effective ACL, not on the endpoint's ACL alone.
- **FR-010**: Route ACLs MUST apply to streaming routes on the same terms, refusing before the
  response stream is opened.

**Python SDK**

- **FR-011**: The Python SDK MUST NOT default an endpoint's ACL. An endpoint that declares none
  MUST fail at registration, naming the class, rather than serving.
- **FR-012**: The failure MUST occur at registration — before the service accepts a request — not
  on the first request to that endpoint.
- **FR-013**: The Python sample and every Python endpoint in the repository MUST declare an ACL
  explicitly.

**Protocol and sidecar** *(US4)*

- **FR-014**: The discovery protocol's route message MUST carry an optional ACL, where absent means
  "the endpoint's", so a process built against the previous protocol is unaffected.
- **FR-015**: The sidecar MUST apply a route's declared ACL to that route alone, and the endpoint's
  where a route declares none.
- **FR-016**: The Python SDK's route decorators MUST accept an ACL.
- **FR-017**: The conformance suite MUST cover a route ACL that differs from its endpoint's, so the
  Scala reference and any process implementation are held to the same behaviour.

**Documentation**

- **FR-018**: The HTTP endpoints page MUST document per-route ACLs, with the sample taken from
  tested code through the `docs:start` / `docs:end` mechanism.
- **FR-019**: The limitations page MUST state, under networking and security, that ankka
  establishes no caller identity: there is no mTLS between services, no internet-versus-internal
  distinction, and no named-service principal, and that an ACL predicate sees only what the request
  carries.
- **FR-020**: The Akka divergences page MUST say which of Akka's ACL principals ankka does not have
  and why, and MUST be updated where it describes ACLs as a per-endpoint decision.
- **FR-021**: The Python SDK reference MUST state that an endpoint's ACL is required, and the
  change MUST be identified as breaking.
- **FR-022**: The rendered skills MUST be regenerated so their copies of these pages do not drift.

### Key Entities

- **Route ACL**: an optional `Acl` carried by a route, alongside its method, template and handler.
  Absent means the endpoint's. It is the only new piece of data in the feature.
- **Effective ACL**: the ACL actually applied to a request — the matched route's if it has one,
  otherwise the endpoint's; the endpoint's when no route matched.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An endpoint serving both public and authenticated routes is expressible in one
  endpoint class with one prefix, and is demonstrated by a test that drives both over HTTP.
- **SC-002**: A request to a path that does not exist under a `DenyAll` endpoint answers 403,
  matching the behaviour before this feature — no path is disclosed that was not disclosed before.
- **SC-003**: Every existing ACL test passes unchanged, with no edit to any endpoint that declares
  no route ACL.
- **SC-004**: A Python endpoint with no declared ACL fails at registration with a message naming
  the class, and no request is ever served by it.
- **SC-005**: `just docs` passes, and the limitations page names the absence of platform-
  established caller identity.
- **SC-006**: `sbt -Dankka.cluster.tests=off test` and the Python SDK's `pytest`, `mypy` and
  `conformance` all pass.

## Assumptions

- ACL evaluation stays on the server's own thread, before dispatch, as it is today; nothing about
  the virtual-thread rule for `RequestContext` changes.
- The refusal codes stay as they are: 403 for a refusing `DenyAll` or `AllowIf`, and whatever
  `AuthDecision` says for an `Authenticate`.
- Endpoint selection stays "the longest matching prefix", so an open endpoint can still sit beside
  an authenticated one at a longer prefix.
- No component other than an HTTP endpoint gains an ACL; entities, views, workflows, consumers,
  timers and agents remain reachable only in-process through `ComponentClient`.
- The Python SDK's change to a required ACL is acceptable as a breaking change at this stage of the
  project, and is released as one.

## Out of Scope

- **Caller identity from the platform.** No mTLS, no workload identity, no network policy, and
  therefore none of Akka's `INTERNET`, named-service, `SELF` or `BACKOFFICE` principals. This
  feature documents the absence; it does not build the mechanism. It is the larger of the two gaps
  and deserves its own feature, with a cluster to prove it against.
- **A configurable deny code.** Akka's `denyCode` lets a refusal answer 404 instead of 403 to hide
  a resource's existence. It is a small change and a real parity item, but ankka's refusal codes are
  currently a property of `AuthDecision` rather than of the ACL, and adding a second place that
  decides a status invites the two disagreeing. Left out deliberately, not overlooked.
- **Disabling ACLs in local development, and impersonation headers.** Akka needs both because its
  principals come from the platform and cannot be produced on a laptop. ankka's ACLs are ordinary
  functions a test or a local run can supply directly, so the switch would guard against nothing.
- **A backoffice proxy.** There is no `akka services proxy` equivalent, and building one is a
  platform feature that depends on caller identity existing first.
- **Showing each route's ACL in the local console.** The console lists routes as a description, not
  a door, and lists no ACLs today; a per-route ACL does not make that listing more wrong. Worth
  doing, separately.
- **ACLs on non-endpoint components.**
