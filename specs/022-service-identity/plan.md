# Implementation Plan: Service-Level Identity — Verifying Tokens from Issuers the Platform Does Not Own

**Branch**: `022-service-identity` | **Date**: 2026-10-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/022-service-identity/spec.md`

## Summary

A service with users of its own can verify their tokens. The control plane's JWKS-backed verifier
moves down into a new published module, `ankka-auth-oidc`, and becomes `Acl.Authenticate` built in
one line from a configuration the service reads from its environment: a named set of issuers,
each with its own keys URL, audience, optional CA and optional `typ` check (R1, R2, R3). The
sidecar carries the same module and configures it from the same variables, so a route declared
`AUTHENTICATED` in Python, TypeScript or Rust is verified by the sidecar and handed a principal,
and a sidecar hosting such a route with no issuer configured refuses to start in discovery's own
report (R4, R8). The protocol's `Principal` gains the claims map and the issuer's name, a minor
version (R5). The control plane builds the module's configuration from its own `AuthConfig` and
keeps every variable and every behaviour it has, with its `typ: Bearer` check switched on for the
installation's issuer (R6). The operator routes the `ANKKA_AUTH_` prefix to the sidecar container
and never to the app container, which the wasm `config` import already withholds (R7).

Technically: one new sbt project `modules/auth-oidc` depending on `http` and nimbus; the control
plane and the sidecar depend on it; `Principal` in `http` gains an `issuer` field with a default;
no DDL, no new component, no new CRD field. The named set is parsed by one function the module,
the sidecar and the docs agree on (R3). Verification keeps the control plane's properties:
asymmetric algorithms only, keys cached, fetches rate-limited, outages tolerated, no fetch at
start, no I/O on the request path once keys are held (R2, FR-013).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`auth-oidc`, `http`, `sidecar`, `controlplane`,
`controlplane-api`, `operator`); Python 3.12, TypeScript on Node 22 and 24, Rust (the three
SDKs' `Principal` types and their conformance references); protobuf (`endpoint.proto`)

**Primary Dependencies**: `nimbus-jose-jwt` 10.9.1, moved from `controlplane` to the new module
and nowhere else (FR-005). Nothing else added. `pekko-http` is already `http`'s.

**Storage**: none. No journal, no table, no DDL, no CRD field.

**Testing**: munit. Offline suites in `auth-oidc` against an in-process issuer on loopback port 0
(the shape of the control plane's `TestIdentity`, moved); `http`'s router suites unchanged;
sidecar `ConformanceSuite` cases run against the Scala reference in-process and each SDK's
process, with a test issuer the suite runs; a discovery refusal case in `sidecar`; operator
rendering suites for the prefix; the control plane's existing suites unchanged, which is the
proof of FR-004. No k3s suite is required; one optional case in `SidecarClusterSuite` is listed
in the quickstart.

**Target Platform**: wherever a service runs: locally, in a cluster, as a process beside a
sidecar, as a module in the runtime.

**Project Type**: platform library (`ankka-auth-oidc`), the sidecar, the protocol and three SDKs,
the operator's rendering, docs

**Performance Goals**: verification adds no network call to a request once an issuer's keys are
held (SC-005); a token for an unlisted issuer or a symmetric algorithm is refused before any key
lookup; the verifier's own benchmark (`VerificationOverheadBenchmark`) is unchanged in what it
measures.

**Constraints**: `ankka-http` gains no dependency; the control plane's `ANKKA_AUTH_ISSUER`,
`ANKKA_AUTH_JWKS_URL` and `ANKKA_AUTH_JWKS_CA` keep their meaning; the protocol change is additive;
`-Wunused` clean; no suite binds a fixed port; no token, key or CA appears in a log or an error.

**Scale/Scope**: 1 new module with 4 main files and 3 suites; `http` 1 file changed; `sidecar` 4
files changed, 1 suite added, conformance cases added; `protocol` 1 message changed; 3 SDKs, 2
files each plus their conformance references; `controlplane` 4 files changed, 2 deleted, 1 test
helper moved; `controlplane-api` and `operator` 1 line each plus suite pins; `build.sbt`,
`CLAUDE.md`; 6 docs pages changed.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Module dependency direction | pass | `auth-oidc` depends on `http`; `http` does not learn about tokens. `controlplane` and `sidecar` depend on `auth-oidc`. No published module other than `auth-oidc` carries nimbus (R1) |
| Where two ends share a wire they share one implementation | pass | one verifier, used by the control plane and every service; the control plane's own copy is deleted (R6) |
| The `RuntimeExtension` seam; no classpath scanning | pass | nothing registers itself; a Scala endpoint builds its ACL explicitly, the sidecar builds one from configuration it is given |
| Wire names are a versioning boundary | pass | the protocol change adds two optional fields and bumps the minor; an older process reads a principal without them (R5) |
| Anything a consumer must see is a direct dependency of the published module | pass | nimbus is a direct `libraryDependencies` entry of `auth-oidc` |
| A refusal is not a failure | pass | `Rejected` is 401, `Unavailable` is 503; neither is an exception on the request path (R2) |
| No secret value in a journal, a log or an error | pass | tokens are never echoed; challenge reasons are quoted and truncated as today; a CA is a path, never its contents in a message |
| `Acl.Authenticate` runs on the server's dispatcher and may not do I/O | pass with the control plane's exception | the key source fetches on the first verify and on an unknown key id, rate-limited, exactly as the control plane does today (FR-013 names this); not widened |
| Reserved and routed variables declared once | partial | `ANKKA_AUTH_` is added to the two `SidecarEnvPrefixes` lists and is already in `HostImports.ReservedPrefixes`; unifying the three lists is 023-secret-store's work (R7) |
| A test must be able to fail | pass | every scenario in the three features fails today: the sidecar answers 503, the module does not exist, the protocol has no claims |
| A test never binds a fixed port | pass | the test issuer and every server in the suites bind loopback port 0 |
| Docs: pages stand alone, generated facts described, a page in nav and a skill | pass | R10; no new page, so nav and skills are unchanged |
| The sidecar reports every discovery problem at once | pass | the authenticated-route check is one more line in `Discovery.validate`'s problems (R8) |

**Violations to justify**: none. The one `partial` is scope, not a violation: the third copy of
the prefix list is unchanged because it already holds the prefix.

## Project Structure

### Documentation (this feature)

```text
specs/022-service-identity/
├── plan.md              # this file
├── research.md          # R1–R11: decisions with file-level evidence
├── data-model.md        # issuer configuration, the named set's grammar, principal, verification, protocol
├── quickstart.md        # the validation runs: module → http → sidecar → SDKs → operator → control plane → docs
├── contracts/
│   ├── scala-api.md         # the module's public surface and the http change
│   ├── environment.md       # the ANKKA_AUTH_ variables, their grammar and routing
│   └── protocol.md          # the endpoint.proto change, the SDK types, the conformance cases
└── tasks.md             # /speckit-tasks output (not created here)
```

### Source Code (repository root)

```text
modules/auth-oidc/                                   # NEW sbt project `authOidc`, artifact ankka-auth-oidc
├── src/main/scala/…/auth/oidc/
│   ├── Issuer.scala                                 # Issuer, OidcConfig, OidcConfig.fromEnv (the named set), problems
│   ├── OidcVerifier.scala                           # Verification, OidcVerifier (one processor and key source per issuer), key source builder, retriever with CA
│   ├── Oidc.scala                                   # Oidc.authenticate(config): Acl; Oidc.verifier; the bearer extraction, challenge quoting
│   └── Principals.scala                             # claims → Principal (roles from realm_access or roles; name; email; claims; issuer)
└── src/test/scala/…/auth/oidc/
    ├── TestIssuer.scala                             # moved from controlplane's TestIdentity: key, JWKS on loopback, tokens to order, outage, rotation
    ├── OidcConfigSuite.scala                        # the named set: parsing, defaults, every refusal
    ├── OidcVerifierSuite.scala                      # TokenVerifierSuite ported + several issuers + typ switch + unlisted issuer fetches nothing
    └── OidcAclSuite.scala                           # through HttpServer.at("127.0.0.1", 0): 200/401/503, challenge, principal on the handler

modules/http/src/main/scala/…/http/HttpEndpoint.scala   # Principal gains issuer: Option[String] = None

protocol/src/main/protobuf/ankka/protocol/v1/endpoint.proto   # Principal: map<string,string> claims = 6; optional string issuer = 7
protocol/README.md, docs/reference/sidecar-protocol.md         # version 1.5
modules/runtime/src/main/scala/…/runtime/remote/Conversation.scala   # WireProtocol.Version = "1.5"

sidecar/src/main/scala/…/sidecar/
├── Main.scala                                       # build(…, auth: Option[OidcConfig]); run reads OidcConfig.fromEnv, refuses a malformed set
├── Discovery.scala                                  # validate(spec, authConfigured): an AUTHENTICATED endpoint or route with no issuer is a problem
├── RemoteEndpoint.scala                             # aclOf takes the authenticated Acl; Translate carries claims and issuer
└── Translate.scala                                  # toPrincipal with claims and issuer
sidecar/src/test/scala/…/sidecar/
├── DiscoveryAuthSuite.scala                         # NEW: refusal names the route and the variable, beside another problem
└── conformance/{ConformanceSuite,ConformanceTarget,ConformanceReference}.scala   # http.auth-* cases; a test issuer per run; /private/me

sdks/python/src/ankka/{context.py,server.py,service.py}          # Principal.claims, .issuer; PROTOCOL_VERSION "1.5"
sdks/python/examples/shopping_cart/conformance.py                 # /private/me
sdks/typescript/src/{context.ts,server.ts,spec.ts}                 # same
sdks/typescript/examples/shopping-cart/conformance.ts
sdks/rust/ankka/src/{components/endpoint.rs,service.rs}            # same
sdks/rust/examples/shopping-cart/src/…                             # conformance feature's private route

controlplane/src/main/scala/…/controlplane/
├── auth/AuthConfig.scala                            # unchanged fields; + toOidc: OidcConfig (one issuer "ankka", typ Bearer, realm hint)
├── auth/TokenVerifier.scala                         # DELETED
├── auth/Principals.scala                            # keeps PlatformAdmin, isPlatformAdmin, display; from(claims) delegates to the module
├── api/ControlPlaneAcl.scala                        # oidc(config) = Oidc.authenticate(config.toOidc); composite unchanged
└── ControlPlane.scala                               # aclFor through the module
controlplane/src/test/scala/…/controlplane/
├── TestIdentity.scala                               # thin: wraps the module's TestIssuer, keeps MutableClock
└── TokenVerifierSuite.scala                         # DELETED (ported to OidcVerifierSuite); every other suite unchanged

controlplane-api/src/main/scala/…/api/descriptors.scala   # SidecarEnvPrefixes += "ANKKA_AUTH_"
operator/src/main/scala/…/operator/Rendering.scala        # SidecarEnvPrefixes += "ANKKA_AUTH_"
operator/src/test/scala/…/operator/ProcessHostingRenderingSuite.scala   # ANKKA_AUTH_X on the node, not the app

build.sbt                                            # authOidc project; controlPlane and sidecar depend on it; root aggregate
project/Dependencies.scala                           # nimbus comment
CLAUDE.md                                            # eight published modules; the module's purpose
docs/build/http-endpoints.md, docs/platform/identity.md, docs/reference/configuration.md,
docs/reference/limitations.md, docs/reference/scala-sdk.md, docs/reference/sidecar-protocol.md
```

**Structure Decision**: a new module beside `http` rather than a package inside it, because the
one thing the feature must not do is put nimbus on every service's classpath (FR-005), and the
sidecar and control plane gain the dependency explicitly. Test helpers that mint tokens live in
the module's test sources and are shared with the control plane through a `test->test`
dependency, so there is one test issuer as there is one verifier.

## Complexity Tracking

| Addition | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| An eighth published module | FR-005: nimbus on no service that does not want it | a package in `ankka-http` puts nimbus on every service; a copy in the sidecar leaves Scala services with nothing |
| A protocol minor version | FR-010: claims and issuer on the principal | reusing `name` or `roles` for claims would be a lie an SDK could not read back |
| A named set of variables rather than one | clarification: readable, each value plain, a CA a path | one JSON variable is unreadable in a descriptor and in the console |
