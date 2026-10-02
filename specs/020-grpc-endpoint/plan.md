# Implementation Plan: gRPC Endpoints — A Second Way Into a Service

**Branch**: `020-grpc-endpoint` | **Date**: 2026-10-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/020-grpc-endpoint/spec.md`

## Summary

A second kind of endpoint. A Scala service implements a `.proto` service definition as a
`GrpcEndpoint`, registers it, and serves all four kinds of method under the ACL vocabulary HTTP
endpoints already use. Deployed, a service that declares gRPC gets an address other services
reach, its one hostname carries gRPC beside HTTP when it is exposed, and another Scala service
calls it as itself. Reflection is opt-in, under an ACL of its own.

Technically: a new published module, `modules/grpc`, serves with grpc-java on its own port and
leaves the HTTP server untouched (R1). It is written against grpc-java's descriptors, so ScalaPB
is the developer's build's and not a dependency of the library (R3); grpc-java moves to 1.84.0 for
the whole build (R2). Handlers are declared against generated method descriptors and block on
virtual threads; streams are bridged with grpc-java's raw call API so neither side holds what the
other has not read (R5, R6). Refusals become statuses through one table used in both directions,
and requests are parsed by ankka so a malformed one is an invalid argument (R7). The ACL, the
caller and the principal are `ankka-http`'s own types, so one authenticator serves both kinds
(R14). Mutual TLS is grpc-java's credentials API over two rotating managers `RotatingTls` gains
(R4).

On the platform a descriptor gains `grpc` and `grpcPort` (R10), the resource one field, and the
operator renders, only when it is set, a container port, a second Service port, a headless
Service, a network policy and — when exposed — one more rule on the existing `HTTPRoute` that
matches gRPC by its content type (R8, R11). No `GRPCRoute`, no new grant. The platform's client
balances per call over the headless Service and the server ages connections out, which is what
makes replacement and scale-out uneventful (R12). The client is a `Channel` from an extension the
developer hands to whatever needs it (R13).

Three decisions rest on behaviour read from source and not yet observed here. Each has a spike
that runs first and a named fallback: the dependency bump (R2), TLS with a custom key manager
under grpc-java's shaded Netty (R4), and gRPC through Envoy Gateway on a header rule (R8).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `sdk`, `runtime`, `http`, the new `grpc`,
`controlplane-api`, `crd`, `operator`, `controlplane`, the sample); YAML for the resource's
schema; JavaScript for one change to the local console's page
(`cli/src/main/resources/console/app.js`)

**Primary Dependencies**: grpc-java 1.84.0 — `grpc-netty-shaded`, `grpc-stub`, `grpc-protobuf`,
`grpc-services` — declared directly by `ankka-grpc`; Pekko Streams (`Source`) from `runtime`;
`ankka-http` for `Acl`, `Caller`, `Principal`, `EndpointClients`. ScalaPB compiler plugin 0.11.20
in this build only, for `protocol`, a fixtures project and the sample. fabric8 for rendering, as
today. Nothing added to `core`, `sdk`, `runtime` or `http`.

**Storage**: none. No entity, event, table or DDL. Two fields with defaults on the descriptor,
which is carried by an existing journaled event and must still replay.

**Testing**: munit. Offline in `grpc` (which takes `testkit % Test`): the feature files for one
service run whole through `GherkinSuite`, with `testPki` certificates where a scenario says
"deployed"; unit suites for validation, statuses, flow control. Rendering suites in `operator`,
with a golden case for a service without gRPC. `controlplane-api` suites for the descriptor.
One k3s suite, `GrpcClusterSuite`, in `controlplane`, named for the scenarios under
`features/grpc-deployed/`. Three spikes under `-Dankka.spikes=on`; one benchmark
under `-Dankka.benchmarks=on`.

**Target Platform**: wherever a Scala ankka service runs; Kubernetes ≥ 1.32 with the
installation's Envoy Gateway v1.9.1, cert-manager and a network plugin that enforces policy

**Project Type**: platform library (a new published module), platform services (control plane,
operator), a sample, docs, one console asset

**Performance Goals**: a unary call reaching one entity no slower at the median than the same call
over HTTP (SC-007); 100,000 parts in either direction to a slow reader with memory flat (SC-008);
no refused call out of 1,000 through a rolling replacement at 20 calls a second (SC-004); every
instance answering within five minutes of a scale-out (SC-005)

**Constraints**: a service that declares no gRPC renders byte-identically and is not restarted
(FR-023, SC-006); `runtime` does not depend on `grpc`, `http` learns nothing about gRPC, `operator`
depends on `crd` only; no new grant for the operator; the sidecar protocol and the three SDKs
unchanged; no suite binds a fixed port; `Test / parallelExecution := false` stays; `-Wunused`
clean outside generated code; nothing interned into the recorder but declared method names

**Scale/Scope**: about 14 new Scala files in `grpc`'s main sources and 12 suites; 9 files changed
across `core`, `sdk`, `runtime`, `http`; 5 in `controlplane-api` and `controlplane`; 4 in `crd` and
`operator` plus the schema; 2 new sbt projects for generated code; the sample gains a `.proto`,
an endpoint and a suite; 1 new docs page and 14 changed; 1 console script; `ci.yml`

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` states as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; the runtime interprets | pass | an endpoint builds no effect of its own; it calls components through the clients it is given, as an HTTP endpoint does |
| Module dependency direction | pass | `core → sdk → runtime → http → grpc`. `runtime` gains a member on `RuntimeExtension` and never names the module (R16). `crd` still depends on nothing; `operator` on `crd` only |
| No classpath scanning; explicit registration | pass | `GrpcServer.of(clients => Endpoint(…))`; an endpoint not handed over is not served (FR-002) |
| Wire names are a versioning boundary | pass | the wire name is the `.proto`'s method name, separate from any Scala method name by construction (R5) |
| `RuntimeExtension` seam | pass | `GrpcServer` and `GrpcClients` are extensions; neither is known to `runtime` |
| Virtual threads make blocking free | pass | handlers run on `AnkkaExecutors.virtual`; the call's context is a thread-local set on the handler's thread, as `RequestScope` is (R14) |
| A trace belongs on the thread doing the work | pass | the call's span is opened on the handler's thread (R17) |
| A refusal is not a failure | pass | `Refused` for an ACL's no and a handler's `CommandError`; `Failed` only for a fault (R17) |
| Never intern anything unbounded | pass | only declared `<service definition>/<method>` names; an undeclared method records nothing (R17) |
| Cluster formation and zero trust are overlay properties | pass | TLS follows `ankka.http.tls.enabled`, which only the Kubernetes overlay sets; locally every caller is `Caller.Local` (R4, R14) |
| The caller is read from the certificate, never the request | pass | from the TLS session's peer certificate through `Caller.fromCertificate` (R14) |
| The generation and anything defaulted stay off the pod template; no change to what an unchanged service renders | pass | everything new is conditional on `grpcPort`; a golden rendering case holds it (R11, R18) |
| A field on the resource is declared in `ankkaservice.yaml` | pass | `grpcPort` is, and `CrdSchemaSuite` compares both ways (R11) |
| The operator holds only the grants it uses | pass | one `HTTPRoute`, no `GRPCRoute`; RBAC unchanged (R8) |
| No secret in a journal, a log or an error | pass | nothing is stored; statuses carry a refusal's message and never a fault's (R7) |
| Anything a consumer must see is a direct dependency of the published module | pass | `ankka-grpc` declares the four grpc-java artifacts itself (R3) |
| A test never binds a fixed port | pass | `GrpcServer.at("127.0.0.1", 0)` in every suite (R18) |
| Could this check pass while the thing it checks is false? | pass | each cluster case asserts a refusal by the API server or the network, not an unused path; the golden case compares objects, not strings; quickstart names a break to try |
| Docs: pages stand alone, samples from tested code, new pages in nav and a skill, reference facts described | pass | R20 |
| Specs since 019 keep scenarios in living features | pass | 88 scenarios under `features/`, the checker clean; suites run them or are named for them (R18) |

**Violations to justify**: none against these principles. Additions that widen the platform's
surface are under *Complexity Tracking*.

**Re-check after design**: unchanged. The design added no dependency to a module below `grpc` and
no object to what an existing service renders.

## Project Structure

### Documentation (this feature)

```text
specs/020-grpc-endpoint/
├── plan.md              # this file
├── research.md          # R1–R20: decisions with file-level evidence; what is read and not yet observed
├── data-model.md        # declared values, a call and how it ends, the two fields of desired state, errors
├── quickstart.md        # the validation runs: spikes → offline → sample → rendering → cluster → kind → docs
├── contracts/
│   ├── scala-api.md                     # endpoint, streams, statuses, server, clients, testing
│   ├── descriptor-and-configuration.md  # descriptor fields and refusals, the resource, what a process reads
│   └── rendering.md                     # every object the operator renders for a service with grpcPort
└── tasks.md             # /speckit-tasks output (not created here)
```

### Source Code (repository root)

```text
modules/grpc/                                   # NEW — published as ankka-grpc
├── src/main/resources/reference.conf           # ankka.grpc.* (contract descriptor-and-configuration)
├── src/main/scala/com/thinkmorestupidless/ankka/grpc/
│   ├── GrpcEndpoint.scala                      # declarations, withAcl, the call's context
│   ├── GrpcServer.scala                        # the extension: validate, bind, TLS, readiness, shutdown
│   ├── Binding.scala                           # descriptor rebuilt with byte marshallers; ServerCallHandlers
│   ├── Streams.scala                           # Requests, CallCancelled, the Source ⇄ call bridges
│   ├── Admission.scala                         # caller from the session, ACL → status, fallback registry
│   ├── GrpcStatus.scala                        # ErrorCode ⇄ Status, one table
│   ├── CallMetadata.scala
│   ├── Reflection.scala
│   ├── GrpcClients.scala                       # the extension: resolve, credentials, round robin, refusals
│   └── GrpcChannels.scala                      # plaintext channels for tests
└── src/test/scala/…                            # GrpcFeatures (GherkinSuite), unit suites, GrpcTlsSpike, GrpcBenchmark

modules/grpc-fixtures/                          # NEW — publish/skip; ScalaPB code for the module's tests
└── src/main/protobuf/…

modules/core/…/CommandError.scala               # + CommandError.from
modules/sdk/…/ServiceClient.scala               # + ServiceServesNoGrpc
modules/runtime/…/
├── Ankka.scala                                 # + RuntimeExtension.grpcAddress; the declared-but-unserved check
├── RotatingTls.scala                           # + keyManager, trustManager, trustManagerRequiring
├── ObservabilityEndpoint.scala                 # + "grpc" address per instance
└── ServiceRegistration.scala                   # + grpcAddressOf
modules/http/…/
├── RequestContext.scala, Caller.scala          # private[http] → private[ankka], no behaviour
└── HttpServer.scala                            # failure handling through CommandError.from

controlplane-api/…/descriptors.scala            # grpc, grpcPort, five problems
controlplane/…/deploy/ServiceProjection.scala   # grpcPort = resolvedGrpcPort
controlplane/…/api/ExposureRules.scala          # neither HTTP nor gRPC: nothing to expose
controlplane/src/test/…/GrpcClusterSuite.scala  # NEW — k3s; GatewayGrpcSpike beside it

crd/…/AnkkaService.scala                        # grpcPort
kustomization/components/crd/ankkaservice.yaml  # grpcPort
operator/…/Rendering.scala, ZeroTrust.scala, Action.scala, Executor.scala, Names.scala
                                                # contract rendering.md

samples/shopping-cart-api/                      # NEW — publish/skip; the sample's .proto and generated code
samples/shopping-cart/…/api/CartGrpcEndpoint.scala   # NEW, with docs:start/end regions
samples/shopping-cart/src/main/scala/Main.scala      # + GrpcServer

cli/src/main/resources/console/app.js           # a GRPC row is listed and not invocable
project/Dependencies.scala, project/plugins.sbt, build.sbt
.github/workflows/ci.yml                        # features/** and GLOSSARY.md claimed
docs/build/grpc-endpoints.md                    # NEW; 14 pages changed (R20); mkdocs.yml; a skill's pages
features/, GLOSSARY.md                          # written by /speckit-specify and /speckit-clarify
```

**Structure Decision**: one new library module below the samples and above `http`, two small
projects that exist only to hold generated code away from `-Wunused`, and edits in place
everywhere else. Nothing is added to the sidecar, the protocol, the SDKs, the installation's
console or the gateway's manifests.

### Delivery order

The feature is large, and its stories are independent enough to land in slices. Each slice leaves
`main` releasable, and nothing in a later slice changes what an earlier one shipped.

| Slice | Stories | Contains | Proved by |
|---|---|---|---|
| 0 | — | the dependency bump; the two other spikes | quickstart 0 |
| 1 | 1, 2 | the module: unary methods, ACLs, statuses, the test helper; the sample's endpoint | quickstart 1–3 |
| 2 | 6, 9, 8 | streams in both directions; reflection; traces and the local console's row | quickstart 1–2 |
| 3 | 3 | descriptor, resource, rendering, readiness, the declared-but-unserved check | quickstart 4, part of 5 |
| 4 | 5, 7 | `GrpcClients`; the headless Service; connection age and shutdown | quickstart 5 |
| 5 | 4 | the route's second rule; exposure rules; calls from outside | quickstart 5–6 |
| 6 | 10 | the docs page and the changed pages; limitations corrected | quickstart 8 |

Slice 1 alone is a working feature on a developer's machine. Documentation for each slice's
behaviour is written with it; slice 6 is the page as a whole and the pass over limitations.

## Complexity Tracking

No principle is violated. These widen the platform's surface and are recorded so the cost is a
decision, not a surprise:

| Addition | Why needed | Simpler alternative rejected because |
|---|---|---|
| An eighth published module, `ankka-grpc` | a service that serves no gRPC must not carry grpc-java | folding it into `ankka-http` puts a second Netty and four grpc-java artifacts in every service's image |
| grpc-java 1.46 → 1.84 across the build, ScalaPB plugin 0.11.11 → 0.11.20 | a network-facing server on a 2022 release; reflection v1 needs ≥ 1.66 | two grpc-java versions in one build: the sample's code generated for one and run on the other (R2) |
| A second port per service, and a second Service (headless) | per-call balancing for the platform's own callers; deterministic scale-out (SC-005) | a `ClusterIP` alone balances connections, and a short connection age makes the criterion a matter of chance (R12) |
| A rule matched by a regular expression on a header | one hostname for both protocols with no knowledge of a service's methods (FR-046) and no new grant | a `GRPCRoute` needs a per-definition match the platform cannot write, a new grant and a traffic policy; it is the fallback (R8) |
| `RuntimeExtension.grpcAddress`, and a startup check keyed on `ANKKA_GRPC_PORT` in `runtime` | local services find each other's gRPC; a member is told why a service that declares gRPC never became ready (FR-026) | reusing `boundAddress` lists a gRPC address as an HTTP one; a termination-message policy on every container would roll every pod (R16) |
| Two sbt projects that hold only generated code | generated code does not compile cleanly under `-Wunused:all` and `-source:3.7` | relaxing the flags for the sample relaxes them for its hand-written code too (R3) |
| Three visibility changes in `ankka-http` | the gRPC server must set the same request scope, read the same local caller and record the same kind of span | a parallel copy of each is the two-implementations shape the codebase avoids |

## Open risks carried into tasks

- **The three spikes** (R2, R4, R8). Each has a fallback that keeps the feature; the gateway's
  fallback costs a grant and a per-definition route match, which FR-046 would have to be reopened
  for.
- **The glossary and CI collide with `019-service-topology`** when the second branch merges (R19).
- **An existing fault, not this feature's**: server-sent-event streams through the gateway are
  very likely cut at 15 seconds today (R9). Fixing it changes what every exposed service renders,
  so it is a decision for its own change.
