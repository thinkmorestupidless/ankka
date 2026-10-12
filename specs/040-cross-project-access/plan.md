# Implementation Plan: Cross-Project Access

**Branch**: `040-cross-project-access-impl` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/040-cross-project-access/spec.md`

## Summary

A project grants one route, one gRPC method, one topic right or its erasure right to one grantee —
a service of another project or a machine registered on an organization — as data on the `Project`
entity, with a seven-state lifecycle that is pending across organizations until an owner there
accepts it, mirrored to the grantee side by one consumer, and ended by the grantor, the grantee or
the deletion of the grantee. Grants reach running services through the project ConfigMap every pod
already mounts, re-read by modification time, and a new matcher `Callers.granted` admits by route or
method; topic grants become literal ACL entries on the grantee's `KafkaUser`. Machines get a client
id and secret from the control plane, exchange them for a fifteen-minute RS256 token at
`POST /oauth/token`, and arrive at routes as `Caller.Machine`; the token is verified in `http` with
the JDK alone against a JWKS the control plane serves from a key it keeps in a Secret it creates.
An installation that opts in exposes its broker through Strimzi's `tlsroute` listener, routed by SNI
through the one Gateway on port 9094 and authenticated by the same token over SASL `OAUTHBEARER`,
with a `KafkaUser` and byte rates per machine. Protocol 1.15 carries the matcher, the caller and a
topic's project to every SDK. [research.md](research.md) holds the twenty-five decisions,
[data-model.md](data-model.md) the shapes, [contracts/](contracts/) what a member, a developer, a
partner and the operator see.

## Technical Context

**Language/Version**: Scala 3.9.0 on JDK 21 (the platform); Python 3.12, TypeScript on Node 24,
Rust (the SDKs); TypeScript/React Router 8 (the console).

**Primary Dependencies**: Pekko 1.7 and pekko-http (the runtime; the JWKS fetch uses its client);
fabric8 7.9 (operator, control plane; `gatewayapi.v1.TLSRoute` for a status read); Strimzi 1.2.0's
`tlsroute` listener and `oauth` authentication, Kafka 4.3.1; Envoy Gateway v1.9.1 with Gateway
API v1.6.1 (`TLSRoute` v1, standard channel); cert-manager for the listener's certificate; nimbus
stays in `auth-oidc` only — `modules/http` verifies machine tokens with `java.security`. No
dependency is added.

**Storage**: Postgres (the control plane's journal: grants, received records, machines; no secret
value, `EventCompatibilitySuite` asserts); a Secret the control plane creates for its signing keys;
the per-project `ankka-project` ConfigMap gains `grants.json`; a cluster-scoped `AnkkaMachine`
resource per machine; `KafkaUser`s in `ankka-broker`.

**Testing**: munit — `http` suites for the verifier, the matcher, the file reader, the refused span
and stream closing; `AnkkaTestKit` with `grants = …` and `asCaller`; the in-memory broker for a
cross-project source; control plane entity, consumer and endpoint suites; the operator's goldens
(`RenderingGoldenSuite`, `RenderingUnchangedSuite`, a new `BrokerGrantsRenderingSuite` and
`MachineRenderingSuite`, `CrdSchemaSuite`); `ConformanceSuite` with six new cases and the three
SDKs' conformance runs; `GherkinSuite` over the six living features — two offline
(`CrossProjectAcceptanceFeatures`, `CrossProjectListingFeatures`) and four on k3s under
`ankka.cluster.tests` (`CrossProjectRouteGrantsFeatures`, `CrossProjectTopicGrantsFeatures`,
`CrossProjectMachinesFeatures`, `CrossProjectMachineTopicsFeatures`), each a plain `class` so
`cluster-suites.py` lists it; `RemoteOverlaySuite`, `ReservedProjectIdsSuite`,
`PlatformDeclarationSuite`, `ControlPlaneRoutesReferenceSuite`, `CliReferenceSuite`,
`ControlPlaneFixturesSuite`, the console's Playwright suite and `parity.ts`.

**Target Platform**: Kubernetes (k3s in tests, kind locally, the cloud overlay); a partner's
machine anywhere with an OAuth client and an Apache Kafka client ≥ 3.1.

**Project Type**: platform: libraries, images, a CLI, a console, manifests.

**Performance Goals**: SC-001/SC-004/SC-013: a grant, a revocation and a stream closing observed
within two minutes (R25's budget: kubelet sync ≤ ~90 s + a 10 s re-read); SC-010: a machine held to
its byte rate while a service's throughput is unchanged (Kafka's per-user quotas, measured in the
k3s suite); the token route answers from a per-node bucket with no I/O beyond one entity query.

**Constraints**: the default is closed and a pending grant opens nothing — structurally, since only
accepted grants are projected to the cluster; no credential in the journal, a snapshot, a view, an
event or a log (the secret is digested as deploy tokens are; the signing key lives only in a Secret
and memory); the control plane's RBAC stays `secrets: create, patch`; no service rolls on upgrade
(the grants ride the mount feature 037 put on every pod; the only rendering change is one env
entry); the operator reads no Secret and writes nothing outside the project namespaces, the
broker's namespace and the cluster-scoped `AnkkaMachine` status; `http` gains no dependency; the
broker is exposed only by an opt-in component; every k3s suite binds the gateway's node ports and
the broker suite one fixed host port (9094).

**Scale/Scope**: a project with tens of grants and an organization with tens of machines; a
`grants.json` of a few KiB re-read every 10 s; one JWKS of two keys. The change touches core, sdk,
runtime, http, grpc, auth-oidc, testkit, sidecar, protocol and the three SDKs, crd, operator,
controlplane-api, controlplane, cli, console, the shopping cart sample and its API, five
kustomization components and both overlays, `kind.yaml`, the docs, the glossary's settled terms and
the six living features.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; the gates are the project's own rules
in `CLAUDE.md` and `.claude/rules/`:

| Rule | How this plan keeps it |
|---|---|
| Reconciliation is split; the operator is not an ankka application and cannot reach the control plane | The control plane projects grants onto `AnkkaProject` and machines onto `AnkkaMachine`; the operator renders ConfigMaps and `KafkaUser`s from them and reads nothing else (R7, R13, R16). |
| `Action` values are inert; `Fabric8Executor` performs them | `EnsureKafkaUser` and `EnsureProjectConfig` are reused; `grantsNaming` is a read on the executor like `projectBrokers` (R16). |
| Pure deciders and renderers | `Rendering.render` and `StrimziRendering.user` take the grants as inputs; `MachineRendering` is pure; `GrantEffect.of` (the "in effect, or why") is a pure function over the grant, the status and the topology (R19). |
| No secret value in the journal; write the cluster first | The machine secret is minted and digested as a deploy token is; the signing key is a Secret the control plane creates before it signs; `EventCompatibilitySuite` asserts the absence (R10, R13). |
| Cross-entity checks live in endpoints, never handlers | Acceptance checks the grantee organization's membership and the grant's record in `OrganizationEndpoint`; a grantee project's existence in `ProjectEndpoint` (R5). |
| One writer per record; a consumer derives the rest | The granting `Project` is the only command-written record; `GrantMirror`, `MachineLifecycleTrigger` and `ProjectTrigger` derive the copy, the lapses and the projection (R4, R6). |
| A `Consumer` runs on one node and cannot back a per-request check; `Acl.Authenticate` does no I/O | The token route is an endpoint on a virtual thread (one entity query); machine-token verification in `http` is synchronous over a cached JWKS fetched off the dispatcher (R9, R10). |
| `runtime`/`http` must not depend on `auth-oidc`; nimbus lives there only | `MachineTokens` is JDK-only in `http`; `auth-oidc` reuses it (R9). |
| Which variables are the platform's is said once | `ANKKA_MACHINE_*` and `ANKKA_PROJECT_GRANTS` are in `PlatformVariables`; `PlatformDeclarationSuite` refuses a second list. |
| Reserved project ids are held together | `machine` joins `ProjectId.Reserved` and `Names.ReservedProjectIds`; `ReservedProjectIdsSuite` holds both. |
| A repin that changes an object is a service rolling on upgrade, and is never accepted | No new volume: `grants.json` rides the `ankka-project` mount; the fixtures gain one env entry (R7). |
| Every port is mutual TLS | One written exception beside Garage's: the control plane's `keys` port serves public keys with server TLS only, because the broker holds no client certificate (R10); `limitations.md` says so. |
| Forked tests do not inherit `-D` | No new switch; the k3s suites read `ankka.cluster.tests` as today. |
| A protocol minor adds optional fields; an older SDK is accepted | 1.15 adds a oneof case, a message and two optional strings; a `granted` matcher under an older runtime fails closed (`NamedService("", "")`), and SDKs gate on the version as sockets did (R18). |
| A wire name is a versioning boundary | New command names only (`make-grant` …); nothing renamed; `ProjectTopicsTrigger`'s component id is kept while the class grows (R6). |
| A new route is documented, exercised by the console, and faked | Every route in `contracts/` has a hand-written section, a fake, and a Playwright case (R20). |
| A page stands alone; samples come from tested code; generated reference | `docs/platform/cross-project-access.md` includes regions from the sample's `WalletEndpoint`; `just docs-reference` regenerates the tables. |
| Living features | Six features exist; each scenario ends as a test that fails without the feature (the suite per feature is named above and in R22). |
| Only a tag publishes; placeholders are `0.0.0` | Nothing new is published; the sample image is rebuilt by the existing jobs. |
| Ask of every check: could it pass while the thing is false | The k3s route scenario asserts a 403 *before* the grant and no pod restart *after*; the broker scenarios use `BrokerProbe` with the service's certificate, not the SDK; the machine-topics scenario runs the client from the host outside every policy. |

No gate fails. Re-checked after Phase 1: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/040-cross-project-access/
├── plan.md
├── research.md          # R1–R25
├── data-model.md        # grantee, target, grant, received grant, machine, token, keys, the two CRDs, grants.json, runtime values, wire types, protocol 1.15
├── quickstart.md        # the proofs, offline, on k3s and by hand
├── contracts/
│   ├── grants.md                      # the routes and CLI on both sides; refusals; the record
│   ├── machines.md                    # registration, the token route, what a route sees, the platform variables
│   ├── runtime-and-sdks.md            # Callers.granted in four SDKs, the grants file, cross-project topics, 1.15
│   ├── operator.md                    # grants.json, the KafkaUser entries, the machine user, settings, RBAC, statuses
│   ├── broker-external.md             # the component, Strimzi's listener, a partner's client properties, checks
│   └── service-status-and-listing.md  # services get, the listings, the topology flag, the console
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
modules/core/…/core/PlatformVariables.scala          # ANKKA_MACHINE_*, ANKKA_PROJECT_GRANTS
modules/sdk/…/sdk/
├── ChangeSource.scala                 # TopicOptions.project, Publication.project, fromTopic(project, …)
└── Consumer.scala                     # produceTo(project, name)
modules/http/…/http/
├── Caller.scala                       # Caller.Machine; CallerMatcher.Granted; admits(caller, self, target); Callers.granted
├── Grants.scala                       # GrantTarget, GrantEntry, Grants (trait, none, of)
├── MachineTokens.scala                # RS256 JWS verify, JWKS cache, issuer/audience/typ/exp rules
├── HttpServer.scala                   # Caller.Machine from a bearer at the gateway; target into admit; refused span; OpenStreams; a grants listener
└── Sockets.scala                      # OpenSocket keeps its context and target; CloseReason.Revoked
modules/runtime/…/runtime/
├── GrantsFile.scala                   # the mtime re-read of grants.json, filtered to this service
├── Kafka.scala, DeclaredConnections.scala, ProjectionRuntime.scala   # a topic's project: qualified name, topology edge, no declaration check
├── ObservabilityDocuments.scala       # TopologyHandler.grantable
└── remote/{Conversation,RemoteDescriptors}.scala   # RemoteCaller.Machine; RemoteSource.Topic.project; 1.15
modules/grpc/…/grpc/Admission.scala    # target = the method's full name; Granted
modules/auth-oidc/…/oidc/Oidc.scala    # a machine token yields a Principal
modules/testkit/…/testkit/AnkkaTestKit.scala   # start(grants = …); asCaller(Caller.Machine)
protocol/src/main/protobuf/ankka/protocol/v1/{discovery,endpoint}.proto, protocol/README.md   # 1.15
sidecar/…/sidecar/{Discovery,RemoteEndpoint,Translate}.scala; conformance/{ConformanceSuite,ConformanceReference}.scala
sdks/{python,typescript,rust}/          # Callers.granted, MachineCaller, project= on topics; version 1.15; conformance references
crd/…/crd/{AnkkaProject,AnkkaService,AnkkaMachine}.scala; kustomization/components/crd/{ankkaproject,ankkaservice,ankkamachine}.yaml
operator/…/operator/
├── ProjectReconciler.scala, ProjectConfig.scala   # grants.json
├── ServiceReconciler.scala, Executor.scala        # grantsNaming; status.grants
├── StrimziRendering.scala, strimzi/KafkaUserResource.scala   # topic entries per grant; optional authentication; quotas
├── MachineReconciler.scala, MachineRendering.scala           # the machine user
├── Operator.scala, Settings.scala                 # the AnkkaMachine informer and queue; requeue by grantee; ANKKA_MACHINE_* settings
└── Rendering.scala                                # ANKKA_PROJECT_GRANTS and ANKKA_MACHINE_* on platform containers
kustomization/components/{operator/operator.yaml, controlplane/{controlplane-rbac,deployment,service,zero-trust}.yaml}   # RBAC; the keys port and the machine-keys mount
kustomization/components/broker-external/           # new: the listener patch, the certificate, the Gateway and EnvoyProxy patches, the settings
kustomization/components/gateway/envoyproxy.yaml, kustomization/kind.yaml   # 30094
kustomization/overlays/{local,cloud}/               # the ClusterIssuer move (local); the component and PUBLIC_ISSUER (cloud); replacements
controlplane-api/…/api/{descriptors,Grants,Compatibility}.scala   # Grantee, GrantTarget, GrantRules, the wire types, ProjectId.Reserved, 1.15
controlplane/…/controlplane/
├── domain/{model,events}.scala        # Grant, ReceivedGrant, Machine; the events
├── application/{ProjectEntity,OrganizationEntity,MachineEntity,MachineRows,GrantMirror,MachineLifecycleTrigger,ProjectTrigger}.scala
├── auth/{MachineKeys,MachineSecrets,TokenBucket}.scala
├── api/{ProjectEndpoint,OrganizationEndpoint,MachineTokenEndpoint,GrantEffect,ServiceEndpoint}.scala
├── deploy/{ProjectProjection,ServiceProjector,AnkkaServiceClient,Fabric8AnkkaServiceClient,StatusIngest}.scala   # grants on AnkkaProject; AnkkaMachine writes; status.grants
└── ControlPlane.scala                 # the new entity, view, consumers, endpoint, keys port
cli/…/cli/{Main,ControlPlaneClient,Output,GrantsCommand,MachinesCommand}.scala
console/package/src/{client/{schemas,control-plane}.ts, routes/{project,organization,machines}.tsx, extensions/types.ts, context.ts, testing/fake-control-plane.ts}; console/e2e/tests/{grants,machines}.spec.ts
samples/shopping-cart/…/{WalletEndpoint,WalletEntity,AffiliatesEndpoint,Main}.scala; samples/shopping-cart-api/…/wallet.proto
features/cross-project/*.feature (existing); GLOSSARY.md (settled)
docs/{platform/cross-project-access.md (new), build/{http-endpoints,calling-services,topics,grpc-endpoints}.md, platform/{broker,identity,networking}.md,
      reference/{control-plane-api,cli,limitations,scala-sdk,python-sdk,typescript-sdk,rust-sdk}.md}; mkdocs.yml; tools/docs/skill/{ankka-endpoints,ankka-platform,ankka-views-consumers}/SKILL.md
.claude/rules/{control-plane,kubernetes,runtime,messaging,sidecar}.md
```

**Structure Decision**: everything lands where its kind already lives. The one new home is
`kustomization/components/broker-external/`, an opt-in component like `garage`; the one new
resource kind is `AnkkaMachine`, beside `AnkkaProject` in `crd/`.

## Order of work

Each step is offline before the k3s features run; a story's living feature is its proof.

1. **The grant on the project, and the record on both sides** (R3–R6): `Grantee`, `GrantTarget`,
   `GrantRules` and the wire types in `controlplane-api`; `Project.grants` with the lifecycle; the
   events and `EventCompatibilitySuite`; `received` on `Project` and `Organization`; `GrantMirror`;
   `MachineEntity`, `MachineRows`, `MachineLifecycleTrigger`, lapse on `ProjectDeleted`; `machine`
   reserved; the routes on both endpoints with the owner checks; CLI `grants` and `machines`
   (registration and listing; the token route comes in step 4); fixtures; generated pages.
   `features/cross-project/acceptance.feature` offline (`CrossProjectAcceptanceFeatures`), with
   "the credential may read" answered by the operator's pure rendering of step 3's shapes
   (`ranElsewhere` until step 3 lands, then in the suite).
2. **Grants reach the cluster and the service** (R7, R8, R23): `AnkkaProjectSpec.grants` and the
   schema; `ProjectTrigger`; `grants.json` in `ProjectConfig`; `ANKKA_PROJECT_GRANTS` on the
   platform containers (the one-line repin); `status.grants`; `GrantsFile`; `Grants`,
   `GrantTarget`, `CallerMatcher.Granted`, `admits` with a target, `Callers.granted`; the HTTP and
   gRPC admission; the refused span; `OpenStreams`, the socket's context, `CloseReason.Revoked`
   and the grants listener; the topology's `grantable`; `AnkkaTestKit.start(grants = …)`. The
   sample's `WalletEndpoint` and `WalletService` (R21). `features/cross-project/route-grants.feature`
   on k3s.
3. **Topic grants on the broker** (R16, R17): `KafkaUserSpec` with optional authentication and
   quotas; `StrimziRendering.user` with granted entries; `Executor.grantsNaming`; the requeue by
   grantee; `TopicOptions.project` / `Publication.project` through the runtime, the topology and
   `crossProjectTopics` on the status; `AnkkaMachine` (CRD, schema, control plane writes, RBAC),
   `MachineReconciler` and `MachineRendering` with byte rates and the settings in
   `PlatformVariables`; the in-memory broker's cross-project case; the sample's cross-project
   knobs. `features/cross-project/topic-grants.feature` on k3s (`BrokerProbe` holding the grantee's
   certificate).
4. **Machines and their tokens** (R9–R12): `MachineSecrets` (mint and digest as deploy tokens),
   `MachineKeys` (the Secret, the mount, the mtime re-read, rotation), the recurring timed action
   and the admin route, `TokenBucket`, `MachineTokenEndpoint` (token, JWKS, discovery) on the HTTP
   port and the `keys` port; the control plane manifest (port 7629, the optional mount, the network
   policy); `MachineTokens` in `http`, `Caller.Machine` at the gateway, the principal in
   `auth-oidc`; `ANKKA_MACHINE_*` rendered by the operator from its settings; the machine listing's
   `tokenUrl` and `brokerBootstrap`. `features/cross-project/machines.feature` on k3s (`curl` from
   the host through the gateway, `ControlPlaneClusterSuite`'s shape).
5. **The listing** (R19, R24): `GrantEffect.of`; `GET /projects/{id}/grants` with `effect`; the
   received listings with the topic's settings; `decrypt` and `erasure` in every record and in
   `grants.json`. `features/cross-project/listing.feature` offline (`CrossProjectListingFeatures`
   against a scripted `TopologyReader` and statuses).
6. **The broker exposed** (R1, R2, R14, R15): the local overlay's `ClusterIssuer` move and
   `deploy-local.sh`; `components/broker-external`; `envoyproxy.yaml` and `kind.yaml`;
   `ANKKA_BROKER_EXTERNAL_BOOTSTRAP` on the operator and the control plane; `RemoteOverlaySuite`;
   `BrokerStack.installExternal` for the suites. `features/cross-project/machine-topics.feature`
   on k3s with the Kafka client from the host (`CrossProjectMachineTopicsFeatures`, base domain
   `127.0.0.1.sslip.io`, host port 9094).
7. **Protocol 1.15 and the SDKs** (R18): the proto fields, the version in six places, the sidecar's
   gating and translation, the Python, TypeScript and Rust declarations and callers, the
   conformance cases and each reference endpoint, the SDK template suites.
8. **The console** (R20): schemas, client methods, the grants cards on the project page, the
   offered grants on the organization page, the machines page, operations, the fake, the Playwright
   cases; `parity.ts` green.
9. **Pages, rules and the glossary**: `docs/platform/cross-project-access.md`; every page named in
   the structure; `limitations.md` drops the topic sentence and gains the `keys` port; the skills;
   `just docs-reference`; the rule files' new traps; the release notes line about `machine` as a
   reserved id.

## Complexity Tracking

| Choice | Why the simpler one does not do |
|---|---|
| A machine is an entity, a view *and* a cluster-scoped resource | The token route needs the digest (entity), the listing needs rows (view), and the operator needs byte rates without reading the control plane (resource). A grant entry carrying the byte rates would go stale the moment an owner changed them. |
| A JDK-only JWS verifier in `http` beside nimbus in `auth-oidc` | `http` cannot depend on `auth-oidc`, and a service with no users must turn a machine token into a caller with no descriptor change. The verifier is RS256 only and a hundred lines; `auth-oidc` reuses it rather than verifying twice. |
| A `keys` port with server TLS only | The broker's OAuth listener fetches a JWKS over HTTPS and holds no client certificate; the gateway's public address is unreachable from a pod on kind. Plain HTTP would let a forged JWKS mint machines. |
| The signing key in a Secret the control plane creates and mounts | The control plane may `create` and `patch` Secrets but never `get` one, and its manifest has no secret key for the secret store. The kubelet handing the key back as a file is the one read it is allowed. |
| Strimzi's `tlsroute` listener instead of the spec's `cluster-ip` plus hand-rendered routes | Strimzi renders the bootstrap and per-node routes and follows a resize; the operator would otherwise need Gateway API RBAC and a node-pool watcher to do the same thing worse. |
| The local overlay's CA becomes a `ClusterIssuer` | A namespaced `Issuer` in `ankka-gateway` cannot issue the broker's certificate in `ankka-broker`, and a partner must trust the same root the gateway's certificate chains to. |
| `grants.json` in the project ConfigMap rather than a volume of its own | A new volume on every pod rolls every service on upgrade, which the rendering rule forbids; the mount from feature 037 is already everywhere. |
