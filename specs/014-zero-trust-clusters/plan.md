# Implementation Plan: Zero Trust in the Service Clusters

**Branch**: `014-zero-trust-clusters` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/014-zero-trust-clusters/spec.md`

## Summary

Every connection inside an ankka installation becomes mutual TLS with a platform-issued identity,
rotated every eight hours, and every port is policed by the network: cluster remoting and
management between a service's own pods only; HTTP from the gateway and from identified workloads,
with the caller's project and service on every request so an ACL can name who may call; the
database over verified TLS with a client certificate and no password. The operator renders all
of it from what it already knows about a service, so a descriptor and a service author change
nothing; the platform's own workloads get the same treatment; the one transition from plain to
TLS is performed by the operator, once, as the only non-rolling deployment.

Technically: Pekko's shipped rotating-keys remoting engine (R1); Pekko Management over HTTPS
requiring a client certificate with readiness moved to a plain `probe` port (R2); Pekko HTTP's
engine-per-connection server context with the peer certificate read from the session header and
mapped to a `Caller` (R3, R6); r2dbc-postgresql's client-certificate options through the
persistence plugin's own customizer hook, wrapped so a renewal reaches the next connection (R4);
cert-manager with three self-signed root authorities and trust-manager to place the service CA
where the gateway can read it (R5, R7); Gateway API `BackendTLSPolicy` plus an Envoy Gateway
client certificate (R7); three `NetworkPolicy` shapes on CNIs that all enforce them (R8); and a
`ServiceClient` that resolves a callee's port by SRV in-cluster and by the console's registry on a
laptop (R11).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (runtime, http, operator, control plane, sidecar);
Python ≥ 3.12 and TypeScript on Node ≥ 22.22 (SDKs)

**Primary Dependencies**: Pekko 1.7.0 (`pekko-remote` rotating-keys TLS, `pekko-pki` PEM
reading, `pekko-discovery` DNS/SRV), Pekko HTTP 1.4.0, Pekko Management 1.2.1, pekko-persistence-r2dbc
1.2.0 over r2dbc-postgresql 1.1.2, fabric8; platform: cert-manager v1.21.2 (present),
**trust-manager (new component)**, Envoy Gateway v1.9.1 with Gateway API `BackendTLSPolicy`
v1alpha3, CloudNativePG (client CA, `DatabaseRole.disablePassword`, `pg_hba`); test-only:
BouncyCastle `bcpkix-jdk18on` to mint certificates (R13)

**Storage**: Postgres via CNPG (unchanged schema — **no DDL change**; the credential Secret's shape
changes, the journal's does not)

**Testing**: munit offline suites in `http`, `runtime`, `operator`, `controlplane-api`; sidecar
`ConformanceSuite` on three targets; k3s suites (`ZeroTrustClusterSuite` new,
`MultiNodeClusterSuite`, `OperatorClusterSuite`, `EndToEndClusterSuite` extended); `RemoteOverlaySuite`
for the components; `deploy-local.sh` on kind ≥ 0.24

**Target Platform**: Kubernetes ≥ 1.32 (already required by Envoy Gateway's CRDs) with a CNI that
enforces `NetworkPolicy` — k3s (default), kind ≥ 0.24, GKE Dataplane V2 (the production clusters,
overlay in `ankka-deployments`)

**Project Type**: platform (operator + control plane + runtime library + three SDKs + kustomize)

**Performance Goals**: median request latency through gateway → entity → journal → reply within
10% of the plain-HTTP path on the same harness (SC-006); a rotation with zero failed requests
(SC-003); rolling replacement under load loses nothing (SC-004)

**Constraints**: no service mesh; the operator never reads a private key; no secret in the
journal; no `subPath` mounts (rotation); the Deployment `spec.selector` unchanged; RSA keys and
root-issued certificates for remoting (Pekko's engine); readiness must keep working without a
client certificate; `Test / parallelExecution := false` stays

**Scale/Scope**: 3 certificates + 2 policies + (exposed) 1 backend TLS policy per service; 1
issuer + 3 certificates + 1 policy per project; ~40 files across 9 modules, 3 SDKs, 5 kustomize
components, 14 docs pages

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution. Each is checked against this design:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | `Action` values remain inert descriptions; `Fabric8Executor` alone performs the seven new ones (`contracts/rendering.md`) |
| Module dependency direction (`core → sdk → runtime → {http, agent} → testkit`; `crd → operator`; `controlplane-api` on `core` only) | pass | `ServiceClient` trait in `sdk`, implementation in `http`; `RotatingTls` in `runtime`; `Caller`/`AllowCallers` in `http`; `MinimumRuntime` in `controlplane-api`; `operator` still depends on `crd` and fabric8 only |
| No classpath scanning; explicit registration | pass | nothing new is discovered; the probe listener and TLS are `RuntimeExtension`/`ClusterFormation` behaviour selected by the overlay |
| Wire names are a versioning boundary | pass | new proto fields are additive with new numbers; the protocol minor increments; `CALLERS = 3` appends to the enum |
| Cluster formation is an overlay, chosen by where the process runs | pass | every TLS and probe setting is in `ankka-cluster-kubernetes.conf` or programmatic under `formation = bootstrap`; `reference.conf` and the local overlay are byte-for-byte unchanged in behaviour |
| No secret value in the control plane's journal | pass | nothing here touches the journal; the operator's credential Secret drops the one secret it held |
| The operator cannot reach into the control plane; write the cluster first | pass | the operator gains kinds, not access to control-plane state; the transition is an operator-side act reported as status |
| `dependencyOverrides` never reaches a POM; the HTTP family pinned | pass | no new Pekko HTTP artifact; `pekko-pki` and `pekko-discovery` are already direct dependencies of `runtime`; `bcpkix` is `Test` scope only |
| Tests are real: k3s for the API server, testkit for the whole service | pass | the schema-accepted CNPG fields, RBAC verbs and policy enforcement are asserted against k3s; TLS itself offline with minted certificates |
| Docs: every page stands alone; samples from tested code; generated tables covered | pass | R15 lists the pages; every snippet is a `docs:start` region; new env vars and ports enter the generated tables with prose |

**Violations to justify**: none against these principles. Additions that widen the platform's
surface are listed under *Complexity Tracking*.

## Project Structure

### Documentation (this feature)

```text
specs/014-zero-trust-clusters/
├── plan.md              # this file
├── research.md          # R1–R16: decisions with the jar- and doc-level evidence
├── data-model.md        # identities, authorities, Caller/ACL, protocol, rendered objects, config, status
├── quickstart.md        # the validation runs, offline → k3s → local platform → docs
├── contracts/
│   ├── acl-and-caller.md    # the SDK-facing API in Scala, Python, TypeScript; conformance cases
│   ├── service-client.md    # ServiceClient API and resolution rules
│   ├── rendering.md         # what the operator renders and the installation provides; RBAC
│   └── configuration.md     # env vars, config keys, ports, refusals
└── tasks.md             # /speckit-tasks output (not created here)
```

### Source Code (repository root)

```text
modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/
├── RotatingTls.scala                 # NEW (R12): PEM → SSLContext, mtime reload, server/client engines
├── ProbeEndpoint.scala               # NEW (R2): plain GET /ready on 7627 via HealthChecks
├── ClusterFormation.scala            # management HTTPS (Need), default client HTTPS context before bootstrap
├── Database.scala                    # ankka's ConnectionFactory when ANKKA_DB_SSL_* set (R4)
├── DatabaseTls.scala                 # NEW: r2dbc options + delegating factory that reloads on mtime
├── ServiceRegistration.scala         # local resolution helper for ServiceClient (read side)
├── Ankka.scala / AnkkaService        # `services: ServiceClients`
└── resources/{reference.conf, ankka-cluster-kubernetes.conf}
modules/runtime/src/test/…/TestPki.scala                    # NEW (R13), shared test->test
modules/sdk/src/main/scala/…/sdk/ServiceClient.scala        # NEW: trait, ServiceClients, errors
modules/http/src/main/scala/…/http/
├── Caller.scala                      # NEW: Caller, CallerMatcher, Callers, URI SAN parsing
├── HttpEndpoint.scala                # Acl.AllowCallers, `caller`
├── RequestContext.scala              # caller: Caller
├── HttpServer.scala                  # httpsServer(() => engine), Tls-Session-Info → Caller, local token, admit(AllowCallers)
├── PekkoServiceClient.scala          # NEW (R11): SRV/registry resolution, mTLS client
└── EndpointClients.scala             # services
modules/http/src/test/…/{TlsServerSuite, CallerAclSuite, RotatingTlsSuite, ServiceClientSuite}.scala   # NEW
modules/testkit/src/main/…/AnkkaTestKit.scala               # httpAs(caller)
protocol/src/main/protobuf/ankka/protocol/v1/{endpoint,discovery}.proto
sidecar/src/main/scala/…/sidecar/{RemoteEndpoint, GrpcConversation, Main}.scala   # caller across the wire; ANKKA_LOCAL_CALLER_TOKEN
sidecar/src/test/…/conformance/ConformanceSuite.scala        # ep.caller-* (+ ConformanceReference)
sdks/python/src/ankka/{endpoint,context,server}.py           # Acl class, Callers, request.caller
sdks/typescript/src/{endpoint,routes,context}.ts
operator/src/main/scala/…/operator/
├── Rendering.scala                   # certificates, policies, BackendTLSPolicy, mounts, probe port, transport label, ANKKA_NAMESPACE_PREFIX
├── CnpgRendering.scala               # database CA/issuer/replication cert, Cluster fields, database policy, role without password
├── cnpg/PostgresCluster.scala        # certificates, postgresql.pg_hba, managed.roles
├── cnpg/PostgresDatabaseRole.scala   # disablePassword, inRoles
├── certmanager/{Certificate,Issuer}.scala      # NEW generic-resource models
├── gateway/BackendTlsPolicy.scala    # NEW
├── Action.scala, Executor.scala      # 7 new actions
├── Transition.scala                  # NEW (R9): detect, delete, wait
├── ServiceReconciler.scala           # transition before apply; Deploying detail
├── LifecycleRules.scala, ClusterSnapshot.scala   # readiness Event in the Failed detail (R10)
├── Labels.scala                      # TransportKey/TransportTls
└── Passwords.scala                   # DELETED
operator/src/main/resources/ankka/install/operator.yaml → kustomization/components/operator/operator.yaml   # RBAC
controlplane-api/src/main/scala/…/api/Compatibility.scala   # MinimumRuntime
controlplane/src/main/scala/…/controlplane/auth/{AuthConfig, TokenVerifier}.scala   # ANKKA_AUTH_JWKS_CA
controlplane/src/test/…/ZeroTrustClusterSuite.scala         # NEW (k3s)
kustomization/components/{pki, trust-manager}/              # NEW
kustomization/components/{gateway, controlplane, keycloak, cnpg}/   # client cert, certificates, policies, tlsSecret
kustomization/overlays/{local, cloud}/kustomization.yaml    # include pki + trust-manager
kustomization/deploy-local.sh                               # enforcement probe
docs/…                                                      # R15
```

**Structure Decision**: no new module. The one architectural addition is `RotatingTls` in
`runtime` used by four callers, mirroring `EventSourcedEffect.materialise`'s "one function, no
disagreement" rule. The `crd` module is untouched: no new field on `AnkkaServiceSpec`, so
`CrdSchemaSuite` and `ankkaservice.yaml` are unchanged — the feature is entirely a property of how a
resource is *rendered*.

## Design by story

### Story 1 — cluster traffic (P1): R1, R2, R5, R8, R9, R12

1. `RotatingTls` + `TestPki` first; `RotatingTlsSuite` proves reload and both engine kinds.
2. Overlay: transport `tls-tcp`, engine provider, mount point. Nothing in code for remoting.
3. `ClusterFormation`: under `bootstrap`, build the cluster context from
   `ankka.tls.cluster-directory`, start management with `withHttpsConnectionContext` (Need), call
   `Http().setDefaultClientHttpsContext` with the client context **before** `ClusterBootstrap.start()`.
   `ProbeEndpoint` starts on 7627 when `ankka.probe.enabled`.
4. Operator: cluster `Certificate`, mount, ports (`probe`), readiness on `probe`, transport label in
   the template and the contact-point selector, `<service>-cluster` policy; `pki` component.
5. `Transition`: detection + `DeleteDeployment` + bounded wait + status detail.
6. **Spike (first task in this story)**: a two-pod k3s run proving bootstrap probes are `https` and
   mutual, since R2 infers the probe scheme from bytecode. If the scheme is not derived from the
   self contact point, the fallback is a `ClusterBootstrapSettings`-level override; the spike
   decides before any rendering is written.

### Story 2 — caller identity (P1): R3, R6, R7, R8

1. `Caller.scala`, `RequestContext.caller`, `Acl.AllowCallers`, `admit` in the router; local token
   in `HttpServer`; `CallerAclSuite` + `TlsServerSuite` with minted certificates.
2. `HttpServer` TLS binding from `ankka.http.tls`; `tls-session-info-header` in the overlay.
3. Protocol fields; sidecar maps `Caller` both ways and reads the discovery matchers; Python and
   TypeScript `Acl`/`Callers`/`request.caller`; `ConformanceReference` + five cases.
4. Operator: service `Certificate`, mount, `<service>-http` policy, `BackendTLSPolicy` with the
   route; gateway component's client certificate and `EnvoyProxy.backendTLS`; trust-manager
   component and `Bundle`.
5. `AnkkaTestKit.httpAs`; sidecar honours `ANKKA_LOCAL_CALLER_TOKEN`; Python/TypeScript integration
   testkits expose an equivalent.

### Story 3 — `ServiceClient` (P2): R11

`sdk` trait; `PekkoServiceClient` in `http` (pekko-discovery DNS SRV, registry fallback);
`EndpointClients.services`, `AnkkaService.services`; `ANKKA_NAMESPACE_PREFIX` rendered and refused
in descriptors; `ServiceClientSuite` offline against two `HttpServer.at("127.0.0.1", 0)` instances
with minted certificates; k3s coverage in `ZeroTrustClusterSuite` step 4.

### Story 4 — database (P2): R4, R5, R8

1. `DatabaseTls` + `Database.apply` path when `ANKKA_DB_SSL_MODE` is set; unit test against a
   testcontainers Postgres configured for TLS with `cert` auth (the container takes `ssl=on` and a
   custom `pg_hba` — minted with `TestPki`).
2. `CnpgRendering`: project CA/issuer/replication certificate, `Cluster` fields, `ankka_tls` group
   role and `pg_hba` rule, `DatabaseRole` without password, credential Secret without password,
   database `Certificate`, `<cluster>-database` policy; delete `Passwords`.
3. Migration of a pre-feature role: `disablePassword: true` on next reconcile plus `inRoles`; the
   `pg_hba` rule matches only group members, so an old-image service keeps logging in with its
   password until its own deploy (R5's rationale, spec scenario 6).
4. k3s: `pg_stat_ssl`/`pg_stat_activity` assertion; the OperatorClusterSuite RBAC negatives.

### Story 5 — the platform's own workloads (P3): R14

Component changes only, plus `ANKKA_AUTH_JWKS_CA` in `AuthConfig`/`TokenVerifier`;
`EndToEndClusterSuite` asserts the control plane's remoting is refused from another namespace and
that `ankka` commands work through the gateway; `RemoteOverlaySuite` renders both overlays with
the new components.

### Story 6 — documentation (P3): R15

After the code, `just docs-sync`, the generated tables, the fourteen pages, the skills.

### Cross-cutting

- **Version**: 0.8.0; `MinimumRuntime`; protocol minor bump; `Compatibility` suite.
- **Old-image detail** (R10): `podProblems` reads Events; `events: get, list` in RBAC.
- **Benchmark** (SC-006): extend `VerificationOverheadBenchmark` with a TLS on/off pair over the
  same path and write the numbers into the networking page.
- **Local deploy** (R8): the enforcement probe in `deploy-local.sh`; `install-local.md` says kind
  ≥ 0.24.

## Risks and how the plan retires them

| Risk | Retired by |
|---|---|
| Bootstrap probes plain HTTP against an HTTPS management port | Story 1's spike, before rendering |
| `EnvoyProxy.backendTLS.clientCertificateRef` needs the Secret in Envoy's own namespace | Story 2's first k3s run with `GatewayStack`; fallback is a `ReferenceGrant` or placing the Certificate in `envoy-gateway-system` |
| CNPG rejects `pg_hba` with `clientcert=verify-full` or `managed.roles` beside `DatabaseRole` | `OperatorClusterSuite` applies the rendered `Cluster` to a real CNPG before story 4 proceeds |
| trust-manager's rendered manifest drifts from the chart | the component pins a version and `RemoteOverlaySuite` renders it; upgrades are a deliberate re-render |
| Kubelet probe blocked by a policy on some CNI | 7627 rule allows every source; SC-003 asserts readiness under policy on k3s |
| A renewal races the r2dbc pool | the delegating factory rebuilds on mtime; the k3s renewal case runs under load |
| The transition wait exceeds the bound (a pod stuck terminating) | `Transition` reports `Deploying` with the detail and retries next reconcile rather than applying beside a live old pod; the transport label on the selector prevents a deadlock if it ever does |
| Python/TypeScript testkits cannot impersonate a caller | `ANKKA_LOCAL_CALLER_TOKEN` to the sidecar container, set by the testkit that starts it |

## Complexity Tracking

| Addition | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| trust-manager as a new installation component | the gateway's `BackendTLSPolicy` needs the service CA as a ConfigMap in each project namespace, and no ankka process may read the CA's Secret | the operator copying `ca.crt` would hold a private key in memory (FR-006); ClusterTrustBundle still needs a writer and is beta |
| A fourth container port, `probe` (7627) | the kubelet cannot present a client certificate, and TLS client auth is per listener | `Want` client auth on management leaves bootstrap and cluster routes reachable without identity, against FR-010 |
| `events: get, list` on the operator's ClusterRole | the only source of the kubelet's own probe-failure text for FR-031's detail | inferring "old image" from the absence of a port is a guess presented as a diagnosis |
| BouncyCastle in test scope | minting certificates with arbitrary SANs and lifetimes in-process | checked-in fixtures expire and cannot express rotation; LibreSSL's EC trap; the JDK has no public signer |
| `ankka_tls` group role + `pg_hba` group rule | lets a pre-feature role keep its password until its own next deploy, so one project's migration is per service | a cluster-wide `cert` rule for all users breaks every not-yet-redeployed service the moment the operator upgrades |

## Constitution Check (post-design)

Re-evaluated after Phase 1: unchanged, all pass. The design added no module, no dependency
direction, no descriptor field, no journal event and no DDL. The one published-artifact change is
`ankka-sdk` gaining `ServiceClient` (a trait over `core`'s codec type) and `ankka-http` its
implementation, both inside the existing direction.

## Not in this feature (from the spec's Out of Scope, restated for the tasks)

No egress policy; no mesh; no Python/TypeScript outbound client; no certificate authentication for
the control plane's own database (follow-up); no root-CA rotation; no Kafka TLS; no realm for a
service's users; no gateway authentication.
