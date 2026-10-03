# Feature Specification: Service-Level Identity — Verifying Tokens from Issuers the Platform Does Not Own

**Feature Branch**: `022-service-identity`

**Created**: 2026-10-03

**Status**: Draft

**Input**: User description: "Service-level identity (core feature C1). A deployed service's HTTP
endpoints need to authenticate the service's own users — people with tokens from an identity
provider the platform did not install and does not administer — and today only the control plane
can verify a token at all. Move the control plane's JWKS-backed verifier into a published module so
a Scala endpoint declares `val acl: Acl = Oidc.authenticate()` in one line, configure it per
service from the descriptor's environment under the `ANKKA_AUTH_` prefix, accept several issuers
per service, and give the sidecar the same verifier so a route declared `AUTHENTICATED` in Python,
TypeScript or Rust works instead of answering 503. Add the claims map the protocol's principal
lacks. Out of scope: provisioning a realm, a client or a user for a service's end users; the
platform verifies tokens and issues none. Also out of scope: changing how the control plane's own
callers or deploy tokens are authenticated, and any change to caller identity from certificates."

## Context

An ankka endpoint states an access control list, and one of its five forms is
`Acl.Authenticate(decide)`: a function from the request context to an `AuthDecision`, which is
`Allow` with a `Principal`, `Unauthenticated` with a challenge, `Forbidden` with a reason, or
`Unavailable` with a reason (`modules/http/src/main/scala/com/thinkmorestupidless/ankka/http/HttpEndpoint.scala`).
`HttpServer.admit` turns those into a request carrying the principal, a 401 with
`WWW-Authenticate: Bearer`, a 403, or a 503 with `Retry-After: 5`. A handler reads `principal`
and gets subject, name, email, verification, roles and a claims map. All of that is real and
tested. What `ankka-http` never does is build a principal. It has no idea what a token is; the
service author supplies the whole verifier as the function.

The platform has one such verifier, and it is deliberately out of reach. `TokenVerifier` in
`controlplane/src/main/scala/com/thinkmorestupidless/ankka/controlplane/auth/` verifies a bearer
token offline against the issuer's published keys with nimbus: asymmetric algorithms only, issuer,
audience, expiry and not-before with skew, a required subject, Keycloak's `typ: Bearer`. Its key
source caches for five minutes, fetches no more than every thirty seconds, tolerates an outage for
an hour, and can trust a single CA for the keys endpoint. `AuthConfig` beside it holds the issuer,
keys URL, audience, client id, realm hint, skew and CA, with the issuer derived from the
installation's base domain when `ANKKA_AUTH_ISSUER` is not set. `ControlPlaneAcl.oidc` turns the
verifier into an `Acl`. Nimbus is a dependency of `controlplane` alone, and `build.sbt` says why:
deliberately not in any published module. The reasoning was sound when the only thing with users
was the control plane.

A service with users of its own has nothing. The limitations page states it: the platform
authenticates its operators, not a service's users, and a deployed service's endpoints are protected
only by the ACL their author wrote. For a Scala service that means copying the verifier, or writing
one. For every other language it is worse than that. The sidecar protocol's `Endpoint.Acl` has an
`AUTHENTICATED` form, the Python SDK offers `Acl.AUTHENTICATED` and the TypeScript SDK
`"authenticated"`, both carry a `principal` on the request context, and the sidecar maps the form
to an `Acl.Authenticate` whose decision is always `Unavailable("no authenticator is configured on
this sidecar")` (`sidecar/src/main/scala/com/thinkmorestupidless/ankka/sidecar/RemoteEndpoint.scala`).
Every such route answers 503 today, in every language, and nothing refuses the service at start
for declaring one. The protocol's `Principal` message also carries no claims map where the Scala
`Principal` does, so a process could not read a custom claim even if the sidecar verified something.

Four decisions shape this feature.

- **The verifier moves down, and there is one copy.** A new published module, `ankka-auth-oidc`,
  depends on `ankka-http` and nimbus and holds the verifier, its key source and its `Acl` adapter.
  The control plane switches to it, so the rule that the two ends of a wire share one implementation
  holds here as it does for the descriptor codec. A service that wants no nimbus depends on
  `ankka-http` alone and pays nothing.
- **Configuration is the service's, in the environment.** A service names its issuers as
  `ANKKA_AUTH_ISSUERS` and companions in the descriptor's `env`, and the module reads them. The
  `ANKKA_AUTH_` prefix is already one the wasm `config` import withholds from a module
  (`sidecar/.../wasm/HostImports.scala`, `ReservedPrefixes`), which is the right instinct: the
  verifier is the runtime's, and the guest should not see a keys URL it cannot use. A service can
  serve several issuers, because one service may front an operator realm and a customer realm;
  the module accepts a list, each with its own keys URL, audience and optional CA.
- **The sidecar gets the same verifier.** It reads the same variables and configures the same
  module, so `AUTHENTICATED` works for Python, TypeScript and Rust with no SDK change beyond the
  claims map. A sidecar that hosts an `AUTHENTICATED` route with no issuers configured refuses to
  start, naming the route, which is how every other discovery problem is reported, instead of
  serving 503s forever.
- **Nothing is provisioned.** The platform's Keycloak is for the platform's operators. A project's
  end users have a realm the installation or the service administers, and this feature verifies
  what that realm signs. Issuing, registering and administering users stay outside.

What this feature is not: a change to callers, which come from certificates and already work; a
realm for a service's users; a role model for services beyond what the token's roles claim carry;
a session, a cookie or a login flow, which belong to the application.

## Clarifications

### Session 2026-10-03

- Q: How does a service list several issuers in its environment? → A: A named set: `ANKKA_AUTH_ISSUERS` names each issuer, and `ANKKA_AUTH_<NAME>_ISSUER`, `_JWKS_URL`, `_AUDIENCE` and optional `_CA` configure each by name.
- Q: Does `ANKKA_AUTH_*` reach a process-hosted service's app container? → A: No. It is routed to the sidecar only, as the model key and the database variables are.
- Q: Is Keycloak's `typ: Bearer` check required by default? → A: No. It is off by default and switched on per issuer; the control plane switches it on for the installation's issuer.
- Q: Are the glossary's proposed terms accepted as written? → A: Yes, all eighteen, with the refused synonyms IdP, realm, JWT and bearer token.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A Scala endpoint verifies its users' tokens in one line (Priority: P1)

A developer building a service with users of its own has an identity provider that signs tokens
for them. They add the module, set `ANKKA_AUTH_ISSUERS` in the descriptor to that provider's
issuer with its keys URL and audience, and declare `val acl: Acl = Oidc.authenticate()` on
the endpoint. Requests with a valid token reach the handler with the subject, roles and claims;
requests without one are challenged; requests with a bad one are refused.

**Why this priority**: This is the capability. The money path in the domain plan cannot serve a
player's balance to the player without it.

**Independent Test**: Against a test issuer the suite runs itself, signing tokens with a key it
holds and serving its keys over HTTP: assert each decision below through a running test service
on an ephemeral port, with no network beyond loopback.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/service-identity/verifying.feature`: a request with a verified token reaches the handler with its principal
- added `features/service-identity/verifying.feature`: a claim the platform does not define reaches the handler by name
- added `features/service-identity/verifying.feature`: a request with no token is challenged
- added `features/service-identity/verifying.feature`: a token that does not verify is challenged
- added `features/service-identity/verifying.feature`: a token from an issuer the service does not list is challenged
- added `features/service-identity/verifying.feature`: a token signed with a shared secret is challenged without its issuer being asked for keys
- added `features/service-identity/verifying.feature`: keys already fetched go on verifying tokens while the issuer cannot be reached
- added `features/service-identity/verifying.feature`: a request is answered unavailable once the tolerance has passed
- added `features/service-identity/verifying.feature`: a service starts without waiting for its issuers' keys
- added `features/service-identity/verifying.feature`: a request is answered unavailable while an issuer's keys have never been fetched
- added `features/service-identity/verifying.feature`: a request is answered unavailable rather than waiting when the keys are slow to arrive
- added `features/service-identity/verifying.feature`: a handler reads the principal only under an ACL that asks for a token
- added `features/service-identity/verifying.feature`: a token attached to a call that an ACL admits by caller is ignored

---

### User Story 2 - A Python, TypeScript or Rust route declared authenticated works (Priority: P1)

A developer writing a Python service declares `acl = Acl.AUTHENTICATED` on a route, as the SDK
has always let them, and sets the same `ANKKA_AUTH_ISSUERS` in the descriptor. The sidecar
verifies the token and hands the route the principal, including custom claims. A service that
declares the ACL and configures no issuer fails to start with the route's name in the message.

**Why this priority**: Today this route answers 503 in every language, with no error at start,
which is a documented SDK feature that cannot work. Two of the domain plan's services are
process-hosted and face users.

**Independent Test**: The conformance suite's caller cases gain authenticated-route cases run
against the Scala reference in-process and against each SDK's process; and a sidecar started
with an `AUTHENTICATED` route and no issuers is asserted to exit, naming the route.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/service-identity/languages.feature`: an authenticated route admits a verified token in every language
- added `features/service-identity/languages.feature`: an authenticated route challenges a token that does not verify in every language
- added `features/service-identity/languages.feature`: a claim reaches the handler by name in every language
- added `features/service-identity/languages.feature`: a service with an authenticated route and no issuer listed does not start
- added `features/service-identity/languages.feature`: a service written in Scala with an authenticated route and no issuer listed does not start
- added `features/service-identity/languages.feature`: the missing issuer is reported together with every other problem found in the service
- added `features/service-identity/languages.feature`: a module is not shown its issuer settings

---

### User Story 3 - One service serves two issuers (Priority: P2)

A service fronts an operator realm and a customer realm. Its descriptor lists both issuers. A
token from either is verified against that issuer's keys and audience, and the principal says
which issuer it came from, so a handler can treat an operator and a customer differently.

**Why this priority**: The domain plan's player-facing services serve a brand realm per brand and
an operator realm, from one service. One issuer per service would mean one service per realm.

**Independent Test**: Two test issuers with distinct keys; tokens from each are admitted and a
token signed by the first's key claiming the second's issuer is refused.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/service-identity/issuers.feature`: a token is verified against the keys and the audience of the issuer that signed it
- added `features/service-identity/issuers.feature`: a token that names one issuer and is signed with another's keys is challenged
- added `features/service-identity/issuers.feature`: a token for one issuer's audience is not accepted from the other issuer
- added `features/service-identity/issuers.feature`: one issuer that cannot be reached does not stop the other's tokens being admitted
- added `features/service-identity/issuers.feature`: an issuer listed twice stops the service starting
- added `features/service-identity/issuers.feature`: an issuer with a setting missing stops the service starting
- added `features/service-identity/issuers.feature`: a token's type is checked only for an issuer that asks for it

---

### User Story 4 - The control plane uses the published verifier (Priority: P2)

Nothing changes for an operator of the platform. The control plane verifies tokens exactly as
before, through the published module, and the one copy of the verifier is the one every service
uses.

**Why this priority**: Two verifiers that drift are the control plane's own lesson about wire
formats, applied to tokens.

**Independent Test**: The control plane's existing authentication suites pass unchanged with
`controlplane/auth/TokenVerifier` deleted and the module in its place; `KeycloakRealmSuite`'s
assertions on every claim still hold.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/control-plane/signing-in.feature`: a member with a verified token is admitted by the control plane
- added `features/control-plane/signing-in.feature`: a request to the control plane with no token is challenged
- added `features/control-plane/signing-in.feature`: a request to the control plane with an expired token is challenged
- added `features/control-plane/signing-in.feature`: a machine with a deploy token is admitted by the control plane as a member
- added `features/control-plane/signing-in.feature`: the control plane answers unavailable once its issuer's keys cannot be fetched and the tolerance has passed

---

### Edge Cases

- **A token with no subject.** Refused, as the control plane refuses it; a principal without a
  subject is not a principal.
- **A keys endpoint reachable only with a private CA.** Each issuer may name a CA, as the control
  plane's `jwksCa` does; without one the JVM's trust store applies.
- **An issuer listed twice.** Refused at start, naming it.
- **A token that verifies but carries no roles.** Admitted with an empty role set; roles are the
  token's to claim and the handler's to check.
- **A route under an `AllowCallers` ACL with a bearer token attached.** The token is ignored; the
  caller comes from the certificate as today.
- **Clock skew.** The same tolerance the control plane applies, configurable per issuer.
- **The keys endpoint answers slowly.** A fetch has a timeout; a request that needed a fetch and
  did not get one in time is 503, not a hang.
- **A local run with no TLS.** The verifier works identically; the issuer is whatever the
  developer's compose runs, and `Caller.Local` is unchanged.
- **A service with the variables set and no `Authenticate` route.** The verifier is configured
  and never asked; nothing is fetched at start.

## Requirements *(mandatory)*

### Functional Requirements

**The module**

- **FR-001**: A published module `ankka-auth-oidc` MUST provide a token verifier that validates
  a bearer token offline against an issuer's published keys, checking signature under an
  asymmetric algorithm, issuer, audience, expiry and not-before with configurable skew, and a
  present subject.
- **FR-002**: The module MUST provide an `Acl` built from that verifier, so that a Scala endpoint
  declares authentication without writing a decision function.
- **FR-003**: The verifier's key source MUST cache keys, bound how often it fetches, and keep
  serving cached keys through an outage for a configurable tolerance, with the control plane's
  current values as defaults.
- **FR-004**: The control plane MUST use the module and hold no verifier of its own.
- **FR-005**: nimbus MUST be a dependency of the module and of no other published artifact.

**Configuration**

- **FR-006**: A service MUST be able to list one or more issuers as a named set in the
  descriptor's `env`: `ANKKA_AUTH_ISSUERS` holds the names, comma separated, each
  `[A-Za-z][A-Za-z0-9-]*`; and for each name, with the name upper-cased and `-` as `_`,
  `ANKKA_AUTH_<NAME>_ISSUER`, `ANKKA_AUTH_<NAME>_JWKS_URL` and `ANKKA_AUTH_<NAME>_AUDIENCE`
  (required), `ANKKA_AUTH_<NAME>_CA` (a path to a PEM bundle), `ANKKA_AUTH_<NAME>_TYP` (FR-008a)
  and `ANKKA_AUTH_<NAME>_CLOCK_SKEW` (default 60 seconds). `ANKKA_AUTH_REALM` (default `ankka`)
  names the realm in a challenge. The parser reads only the variables of listed names and the two
  set-level names; every other `ANKKA_AUTH_*` variable, including the control plane's own
  `ANKKA_AUTH_ISSUER`, `ANKKA_AUTH_JWKS_URL` and `ANKKA_AUTH_JWKS_CA`, is ignored, so a shell that
  carries both sets runs either. A name listed twice, a name that is not a valid identifier, or a
  required variable missing for a listed name MUST refuse the service's start naming the name and
  the variable, every problem at once.
- **FR-007**: The `ANKKA_AUTH_` prefix MUST be withheld from a wasm module's `config` import, as
  it is today, MUST be routed to the sidecar container for a process-hosted service, and MUST NOT
  reach the app container, as the model key and the database variables do not.
- **FR-008**: A principal MUST carry the name of the issuer that verified it, as listed in
  `ANKKA_AUTH_ISSUERS`.
- **FR-008a**: The check that a token's `typ` header is `Bearer` MUST be off by default and
  switchable on per issuer with `ANKKA_AUTH_<NAME>_TYP=Bearer`; the control plane MUST switch it
  on for the installation's issuer so its behaviour is unchanged.

**Every language**

- **FR-009**: The sidecar MUST configure the same verifier from the same variables and apply it
  to every route declared `AUTHENTICATED`, handing the route a principal with subject, name,
  email, verification, roles and claims.
- **FR-010**: The protocol's `Principal` MUST gain a claims map, and the Python, TypeScript and
  Rust SDKs MUST expose it.
- **FR-011**: A sidecar hosting an endpoint or a route declared `AUTHENTICATED` with no issuer
  configured MUST refuse to start, in the same report as other discovery problems, naming the
  variable and the endpoint for an endpoint-wide ACL or the route for a route-level one. A Scala
  endpoint built with `Oidc.authenticate()` and no issuer configured MUST fail the service's start
  naming the variable; the endpoint's class appears in the failure's origin, which is as near to
  the route as construction allows.

**Decisions**

- **FR-012**: A missing token MUST be answered 401 with a bearer challenge; an invalid, expired,
  wrong-audience, wrong-issuer or symmetrically signed token MUST be answered 401; a token that
  cannot be verified because keys are unavailable MUST be answered 503 with `Retry-After`.
- **FR-013**: Verification MUST NOT block the service's start and MUST NOT perform I/O on the
  server's dispatcher beyond what the control plane's verifier does today.

**Documentation**

- **FR-014**: The HTTP endpoints guide MUST show the one-line form in Scala and the declaration in
  each other language; the identity page MUST state that the platform verifies and does not
  issue; the configuration reference MUST list every `ANKKA_AUTH_` variable; the limitations
  entry saying the platform authenticates only its operators MUST be rewritten to say what is now
  true.

### Key Entities

- **Issuer configuration**: a name, an issuer string, a keys URL, an audience, an optional CA,
  an optional required `typ`, a skew; the set as a whole carries the challenge's realm.
- **Principal**: subject, name, email, email verified, roles, claims, and the issuer that verified
  it; the first six already exist in Scala and all but claims in the protocol.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A Scala service authenticates its users with one declared ACL and one dependency,
  and no verifier code of its own.
- **SC-002**: A route declared authenticated in Python, TypeScript or Rust admits a valid token
  and refuses an invalid one; before this feature every such route answered 503.
- **SC-003**: A sidecar with an authenticated route and no issuer fails at start within the
  existing discovery timeout, naming the route.
- **SC-004**: The control plane's authentication behaviour is unchanged, measured by its existing
  suites, and the repository holds one verifier.
- **SC-005**: A keys endpoint outage after a successful fetch costs no refused request for the
  tolerance period.

## Assumptions

- Issuers publish keys at a JWKS URL over HTTP, as Keycloak does; a token format other than a
  JWT is out of scope.
- A service's issuer configuration is static for a generation. Changing it is a new apply.
- The audience is required, as the control plane requires it; an issuer with no audience check
  is not supported.
- The sidecar image carries the module; its size cost is accepted.
- The 021 web hosting proxy, if it needs token verification, will take it from this module; that
  is not decided here.

## Dependencies

- Gates stage 1 of the domain plan, the money path, with 026-telemetry-export.
- Depends on nothing in this series.
- 025-polyglot-service-client, 028-websocket-routes and 029-agent-approvals-and-mcp assume a
  principal is available on a process-hosted route, which this provides.

## Open Questions

- Whether the control plane's `platform-admin` convention, a realm role that grants
  administrative actions, should be documented as a pattern for services or left to each.
