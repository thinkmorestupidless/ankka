# Implementation Plan: Personal Data Erasure — Crypto-Shredding Per Data Subject

**Branch**: `042-personal-data-erasure-impl` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/042-personal-data-erasure/spec.md`

## Summary

A service types the fields that are about a person as `Personal[A]`, and the codec in `core`
encrypts each under a key per data subject per project, writing one fixed envelope that every store
holds as ordinary JSON; the runtime, the sidecar and the journal never look inside. The key lives in
a new platform component, the keyring — an ankka application in `keyring/`, deployed as the control
plane is, two instances, its own database, wrapped keys, admitting callers by certificate and by
040's grants — and every service instance holds one WebSocket to it that carries fetches, destroyed
notices, apply orders and completions. An erasure is a request on the control plane with a
not-before date, an owner's override and a certificate; a sweeper writes the erasure log to the
control plane's journal and to a platform bucket, then asks the keyring to destroy the key, and the
keyring drives each service through its duties: drop the key, redact view rows by one SQL statement
over the envelope's grammar, remove lookup tokens, stop the subject's sessions and instances, run the
service's erasure handler (which erases the subject's objects through a minimal S3 client the
runtime gains). A service applies the keyring's log on every start and is not ready until it has, so
a restored database comes back redacted; the keyring replays both log copies before it answers. The
four SDKs get the same type, three client rpcs, one process callback and three module imports at
protocol 1.15. Six dependencies are unbuilt; each is a named seam (research, first section).
[research.md](research.md) holds the twenty-six decisions; [data-model.md](data-model.md) the shapes;
[contracts/](contracts/) what a developer, a member, an SDK, the operator and specs 040/041 see.

## Technical Context

**Language/Version**: Scala 3 on Apache Pekko (the platform, the keyring, the control plane, the
CLI, the operator); Python 3.12 (`sdks/python`), TypeScript under Node 22/24 type stripping
(`sdks/typescript`), Rust for `wasm32-unknown-unknown` (`sdks/rust`); protobuf through ScalaPB,
grpcio, protoc-gen-es and prost at protocol 1.15.

**Primary Dependencies**: jsoniter-scala (the codec, `inline given`), the JDK's `javax.crypto`
(AES-256-GCM, HMAC-SHA-256, as `SecretCipher` already uses), the JDK's `java.net.http` WebSocket and
HTTP clients (the channel, the S3 client — no dependency added to `runtime`), Pekko distributed
pub-sub (the keyring's fan-out), the http module's socket routes, `auth-oidc` (the machine route),
fabric8 (the operator's platform bucket), CNPG (the keyring's database), kustomize.

**Storage**: the keyring's own Postgres (a CNPG `Cluster` of its own; a second compose container
locally), never a project's; one new DDL file for services, `50-erasure-postgres.sql`
(`ankka_erasures_applied`), named in the seven lists; the control plane's journal (the erasure
request, the log's first copy, the project's history) and the platform bucket
`ankka-platform-erasure-log` (the second copy, one object per erasure); view row tables rewritten in
place by redaction.

**Testing**: munit through `AnkkaTestKit` with a shared in-memory keyring (`kit.erase`,
`kit.keyringOutage`, `kit.assertNoPersonalValue`, `kit.snapshotDatabase`/`restoreDatabase`), the
entity test kits under a scope; `GherkinSuite` over `features/erasure/` — `personal-fields`,
`erasing`, `holds`, `agents`, `objects`, `restores` (offline, by template copy) and `other-projects`
(offline, grants given to the test kit) in `testkit`'s and `controlplane`'s tests; `languages` through
each SDK's conformance reference and `protocol/fixtures/personal/`; `asking` blocked on 040;
`ErasureClusterFeatures` on k3s (the keyring component, a real channel, a real redaction, Garage)
run by the `cluster` workflow; `PersonalReplayBenchmark` for SC-007; `PersonalLeakSuite` for SC-009.

**Target Platform**: Kubernetes through the operator and `kustomization/components/keyring`;
`docker compose` locally with `keyring` and `keyring-db`; the test kit.

**Project Type**: a published-library change (`core`, `sdk`, `runtime`, `agent`, `testkit`), a new
platform image (`keyring/`), a control plane and CLI feature, an operator change, three SDKs and the
protocol, and a kustomize component.

**Performance Goals**: a cached key costs one AES-GCM beside the journal write; one fetch per subject
per entity recovery, so a 1,000-event recovery is at most 1.3× (SC-007, measured by the benchmark);
every instance reads a destroyed subject as erased within 60 seconds of the destroy (SC-004); a held
request applies within 15 minutes of its date (SC-006); redaction is a background scan per view per
erasure.

**Constraints**: no change for a service with no personal field and no keyring configured; nothing
outside the codec parses the envelope; no key, no personal value and no plaintext in the journal, a
log line, a span, the recorder or the local console; the control plane's identity reads no key; the
keyring's database is no project's and in no project's backup; the operator keeps render/execute and
no `get` on Secrets; the control plane writes both log copies before it asks for a destroy; the
sidecar keeps the credential and the channel, the process neither; `Personal` encodes outside a
service as `Unavailable`, never as plaintext; protocol minor bump only.

**Scale/Scope**: eight user stories; roughly 26 decisions, 8 contracts, one new sbt project and
image, one new compose pair, one new kustomize component, one DDL file, three rpcs, one process
service, three imports, four SDKs; US5's cross-project half, US8, the k3s restore proof, GCS finality
and the wrapping key are blocked tasks named for 040, 041, 039 and 044.

## Constitution Check

`.specify/memory/constitution.md` is the unfilled template; the gates are the project's own rules in
`CLAUDE.md` and `.claude/rules/`:

| Rule | How this plan keeps it |
|---|---|
| Effects are inert data; the runtime interprets them | `Personal` is a value; its codec does I/O only inside a scope the runtime sets (R4); the erasure handler returns an outcome. |
| Module dependency direction; `core` has no Pekko | `Personal`, `PersonalScope` and `KeyringHandle` are in `core` with the JDK only; the channel, the cache and the S3 client are `runtime`'s; the keyring app depends on `runtime`, `http`, `auth-oidc` and never the reverse (R1, R7). |
| No classpath scanning; wire names declared separately | Every new handler, route and rpc is declared (R11, R21, R22); the erasure handler is registered on the builder (R18). |
| `RuntimeExtension` seam | `ErasureRuntime` and `KeyringReplay` are extensions with `readiness` (R16, R17). |
| Work handed to another thread cannot see a thread-local | The scope is set on the thread that serializes, at every site the runtime does so, and fails closed elsewhere (R4). |
| Which variables are the platform's is said once | `ANKKA_KEYRING_URL` joins `PlatformVariables` (runtime-read, operator-rendered, descriptor-refused); `ANKKA_S3_` moves to shared, in the one file (R15, R24). |
| DDL: one canonical copy, seven lists, additive | `50-erasure-postgres.sql` is added, named in all seven, and adds one table (R16). |
| No secret value in the journal; write the cluster first | The keyring's journal holds wrapped keys only; the control plane's holds no key and no value; the log is written to both copies before the destroy (R9, R23). |
| The operator has no `get` on Secrets | The platform bucket's credential is create-only, 409 is success (R23). |
| A behaviour an older runtime must refuse is a new call | Three rpcs, one service and three imports at 1.15; no field on an old message (R11). |
| `-D` properties forwarded to forked tests | No new switch; the existing `ankka.cluster.tests` and `ankka.conformance.*` are reused. |
| Fixed ports in tests | The keyring under test binds `127.0.0.1:0`; the in-memory keyring binds nothing. |
| A check that could pass while the thing is false | `assertNoPersonalValue` scans every column in every encoding; the leak suite scans the captured log; the restore cases assert the readiness gate held *and* the rows read erased; the benchmark measures a real recovery. |
| Could pass while false: the conformance filter | SC-008's cases carry a leading wildcard in every SDK's runner. |
| Docs: a page stands alone; reference generated | `docs/platform/erasure.md` is new; the CLI and routes pages regenerate; the object storage page says the variables now reach the platform container. |

No gate fails. Re-checked after Phase 1: unchanged.

## Project Structure

### Documentation (this feature)

```text
specs/042-personal-data-erasure/
├── plan.md                       # This file
├── research.md                   # R1–R26
├── data-model.md                 # Personal, the envelope, the keyring's entities, the control plane's, the channel, protocol 1.15
├── quickstart.md                 # Offline suites, the conformance runs, k3s, by hand on compose
├── contracts/
│   ├── personal-envelope.md      # The envelope grammar, AAD, the fixtures every SDK reads
│   ├── sdk-api.md                # Personal in Scala, Python, TypeScript and Rust; the handler; erase; lookup tokens
│   ├── keyring-channel.md        # The WebSocket between a service and the keyring
│   ├── keyring-api.md            # The keyring's routes, admission, status, the machine route
│   ├── protocol-1.15.md          # The sidecar's rpcs, the process service, the module imports and export
│   ├── control-plane-erasures.md # Routes, wire types, the CLI, the certificate, the log route
│   ├── installation.md           # The keyring component, compose, the operator's renderings, variables, DDL
│   └── grants.md                 # What 042 needs 040 to render, and what 041 and 039 read from 042
└── tasks.md                      # /speckit-tasks
```

### Source Code (repository root)

```text
modules/core/src/main/scala/com/thinkmorestupidless/ankka/core/
├── personal/Personal.scala       # Personal, DataSubject, the codec (R1, R2, R3)
├── personal/PersonalScope.scala  # the thread-local scope, KeyringHandle, LookupTokens (R4, R6)
├── Serializer.scala              # Serializer.json sets the manifest in scope (R3)
└── PlatformVariables.scala       # ANKKA_KEYRING_URL; ANKKA_S3_ shared (R15, R24)
modules/core/src/test/…/PersonalCodecSuite.scala, PersonalFixturesSuite.scala
protocol/fixtures/personal/       # envelopes under a fixed key, every SDK reads them
modules/sdk/src/main/scala/com/thinkmorestupidless/ankka/sdk/
├── Erasure.scala                 # ErasureHandler, ErasureContext, ObjectErasure (R18)
├── contexts.scala                # lookupToken on the contexts that have a client
└── agent/… AgentCalls.withSubject, TaskBuilder.withSubject (R19)
modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/
├── erasure/KeyringClient.scala   # the channel over the JDK WebSocket (R8)
├── erasure/KeyCache.scala        # the bounds (R5)
├── erasure/ErasureRuntime.scala  # the extension: log on start, apply, duties, readiness (R16)
├── erasure/ViewRedaction.scala   # the one SQL statement per view (R14)
├── erasure/ObjectErasure.scala   # SigV4, ListObjectVersions, DeleteObjects (R15)
├── erasure/AppliedErasures.scala # ankka_erasures_applied
├── GrantReader.scala             # the seam 040 fills (R12)
├── EventSourcedEntityHost.scala, KeyValueEntityHost.scala, WorkflowHost.scala,
│   ProjectionRuntime.scala, ProjectionSupport.scala, TopicHandlers.scala, ViewClient.scala,
│   KeyedViewHandlers.scala, ShardingTransport.scala   # PersonalScope.within at each site (R4)
├── QueryCheck.scala              # refuses a query over a personal field's data (R6)
├── Ankka.scala                   # keyring on AnkkaService; withErasureHandler; the extension
└── ObserveServer.scala           # appliedUpTo and finished-at on the observe document (R16)
modules/http/…/HttpServer.scala   # scope around request and response bodies (R4)
modules/agent/…/SessionMemoryEntity.scala, memory.scala, PromptReplay.scala, compaction.scala,
  autonomous/TaskEntity.scala, InstanceEntity.scala, AutonomousAgentHost.scala,
  AgentRuntime.scala (ankka-subject-index)   # R19
modules/testkit/src/main/…/InMemoryKeyring.scala, AnkkaTestKit.scala (erase, keyringOutage,
  assertNoPersonalValue, snapshot/restore), SharedPostgres.scala (template copy, DDL list),
  EventSourcedTestKit.scala, KeyValueEntityTestKit.scala   # R20
modules/testkit/src/test/…/erasure/ErasureSteps.scala + one class per feature file,
  PersonalReplayBenchmark.scala, PersonalLeakSuite.scala
keyring/                          # new sbt project, image ankka-keyring (R7)
├── src/main/scala/…/keyring/Keyring.scala, Main.scala, ProjectKeyEntity.scala,
│   SubjectKeyEntity.scala, ErasureEntity.scala, ErasureRows.scala, KeyringEndpoint.scala,
│   Channel.scala, Admission.scala, Wrapping.scala, RootKeySource.scala, KeyringReplay.scala
└── src/test/…/KeyringSuite.scala, AdmissionSuite.scala, ReplaySuite.scala, ChannelSuite.scala
kustomization/components/keyring/ # deployment, cluster, certificates, zero-trust, route, kustomization
kustomization/components/postgres/ddl/50-erasure-postgres.sql
kustomization/overlays/{local,cloud}/kustomization.yaml   # the component listed
controlplane-api/…/descriptors.scala   # ErasureRequest wire types, problems, Wire codecs (R22)
controlplane/src/main/scala/…/controlplane/
├── application/ErasureEntity.scala, ErasureRows.scala, ErasureLogEntity.scala
├── api/ErasureEndpoint.scala, ProjectEndpoint.scala (history)
├── deploy/ErasureSweeper.scala, KeyringClient.scala, ErasureLogBucket.scala (R21, R23)
├── domain/model.scala, events.scala  # Project.history, ErasureRefused
└── ControlPlane.scala            # components, endpoints, the sweeper
controlplane/src/test/…/ErasureSuite.scala, erasure/*Features.scala (holds, erasing, asking[blocked]),
  ErasureClusterFeatures.scala (k3s), ControlPlaneRoutesReferenceSuite
cli/src/main/scala/…/cli/Main.scala, ErasuresCommand.scala, Output.scala, mcp/AnkkaTools.scala
cli/src/main/templates/common-service/docker-compose.yml, ankka.g8/src/main/g8/docker-compose.yml
operator/src/main/scala/…/operator/Rendering.scala (ANKKA_KEYRING_URL, ANKKA_S3_ on the platform
  container), Action.scala (EnsurePlatformBucket), Executor.scala, CnpgRendering.scala (SchemaFiles)
crd/…/AnkkaService.scala          # no field: the keyring URL is an operator setting
protocol/src/main/protobuf/ankka/protocol/v1/client.proto, erasure.proto, discovery.proto, wasm.proto
protocol/README.md, protocol/WASM-ABI.md
sidecar/src/main/scala/…/sidecar/ClientLogic.scala, ClientService.scala, GrpcConversation.scala,
  Discovery.scala, wasm/HostImports.scala, wasm/ModuleLoader.scala, wasm/WasmConversation.scala
sidecar/src/test/…/conformance/ConformanceSuite.scala (personal.*), WasmHostSuite, WasmImportsSuite
sdks/python/src/ankka/personal.py, codec.py, server.py, client.py, erasure.py, examples/…/conformance.py
sdks/typescript/src/personal.ts, schema.ts, json.ts, server.ts, client.ts, examples/…/conformance.ts
sdks/rust/ankka/src/personal.rs, abi/imports.rs, abi/exports.rs, erasure.rs, examples/shopping-cart
docker-compose.yml                # keyring, keyring-db (R24)
docs/platform/erasure.md, docs/platform/object-storage.md, docs/reference/{cli,control-plane-api,
  limitations,glossary,configuration}.md, mkdocs.yml, tools/docs/skill/*/SKILL.md
.claude/rules/erasure.md, CLAUDE.md (the table), .github/ci-coverage.py, build.sbt
```

**Structure Decision**: everything lands where its kind already lives; the one new home is
`keyring/`, an image project beside `controlplane/`, and the one new kustomize component sits beside
`console`. The service-side logic is `runtime/erasure/`, one package, so a reader finds the channel,
the cache, the duties and the S3 client together.

## Order of work

Each step is offline before the k3s features run; a story's living feature is its proof. Steps 1–3
are the foundation every story needs; the rest follow the spec's priorities.

1. **The type, the codec and the scope** (R1–R4, R6): `Personal`, the envelope, the fixtures suite,
   `PersonalScope`, `KeyringHandle`, `Serializer.json`'s manifest; the runtime's scope at every
   serialization site; `InMemoryKeyring` and the kits' scope; `QueryCheck`'s refusal.
   `features/erasure/personal-fields.feature` scenarios 1–5, 7 and 8.
2. **The keyring** (R7, R9, R10, R17): the project, the entities, wrapping, the endpoint, the
   channel route, admission by certificate, `KeyringReplay` with one copy; `KeyringSuite` in-process
   through the test kit. The kustomize component and compose come in step 8.
3. **The channel, the cache and the extension** (R5, R8, R16): `KeyringClient`, `KeyCache`,
   `ErasureRuntime` against an in-process keyring; `ankka_erasures_applied` and the DDL file in the
   seven lists; `personal-fields.feature` scenario 6 (one key, whichever instance) and the outage
   edge case; `PersonalReplayBenchmark` (SC-007).
4. **Erasing: duties and the control plane** (R14, R18, R21–R23): `ViewRedaction`, the handler,
   the agent index and marks (R19), `ErasureEntity`, `ErasureRows`, `ErasureEndpoint`, the sweeper,
   the log entity and bucket writer, the wire types, the CLI, the reference pages.
   `erasing.feature` whole, `holds.feature` whole, `agents.feature` whole (US2, US4, US6).
5. **Restores, offline** (R17, R20): `snapshotDatabase`/`restoreDatabase`, the readiness gate, the
   keyring's two-copy replay with a fake bucket; `restores.feature` whole (US3, Case A and B), the
   k3s half a blocked task for 041.
6. **Objects** (R15): `ObjectErasure` against Garage in testcontainers, the operator's shared
   `ANKKA_S3_` rendering, the platform bucket; `objects.feature` scenarios 1 (one version), 3 and 4;
   scenarios 1 (every version) and 2 blocked for 039 (US7).
7. **Other projects and machines, offline** (R12, R13): `GrantReader`, admission by grant, the
   decrypt route under `TestIssuer`; `other-projects.feature` whole through the test kit's grants
   (US5); the k3s proof, the file reader and `asking.feature` (US8) blocked for 040.
8. **The SDKs and the protocol** (R11): 1.15 in every version site; `SubjectKeys`, `LookupToken`,
   `EraseObjects`, `Erasure.Handle`, the imports and export; the sidecar's mirror; `Personal` in
   Python, TypeScript and Rust with the fixtures; each conformance reference's `personal.*` cases;
   `languages.feature` (US1's last scenario, SC-008).
9. **Installation** (R7, R24): `kustomization/components/keyring`, the overlays, the operator's
   `ANKKA_KEYRING_URL`, compose's `keyring` and `keyring-db`, the samples (a personal field on the
   shopping cart's customer) and the templates; `ErasureClusterFeatures` on k3s: the channel, a
   destroy, a redaction and `erase` on Garage, one service rolled during an erasure.
10. **Polish** (R25): `PersonalLeakSuite`, `docs/platform/erasure.md`, the object storage page, the
    limitations page (the subject id residual, Garage's plain HTTP unchanged, the k3s restore proof
    pending), the glossary page, the skill lists, `.claude/rules/erasure.md`, `ci-coverage.py`,
    `cluster-suites.py`'s pickup of the new suite, `just features`, `just docs`, `sbt buildAll`.

## Not in this feature (from the spec, restated for the tasks)

Physical deletion of events or records; a classifier for unmarked data; erasure across
installations; re-encryption of events written before the feature; subject-key rotation; the
domain's retention rules; a key leaving the installation; the restore itself (041); grants, machine
registration and tokens (040); GCS versions and the soft-delete window (039); the wrapping key (044).

## Complexity Tracking

| Choice | Why the simpler one does not do |
|---|---|
| A thread-local scope set at every serialization site, not one global handle | A global cannot tell two kits in one JVM apart, and the test for two projects in one installation needs exactly that; the scope fails closed where a global would decrypt under the wrong project. |
| A new platform application and image (`keyring/`) | The control plane's identity must not be able to read a key, and the keyring's database must be no project's and in no project's backup; a table in the control plane would be both. |
| A WebSocket per instance rather than SSE plus POST | "Could not confirm receipt within 60 seconds" needs an ack, and an ack needs a duplex channel; two channels would reconnect out of step. |
| Redaction by `regexp_replace` over a fixed envelope grammar | A row's payload is text with no subject column; decoding every row needs the row's current codec and a JSON tree library `core` does not have; one statement per view redacts rows whose type the service has since changed. |
| A minimal S3 client in `runtime` | The AWS SDK is on the test classpath only and is one published library's weight for one call; a process has no client the sidecar can lend it. |
| Two compose services for the keyring locally | The keyring's database must not be a project's; a second database inside compose's Postgres needs an init script every template would copy. |
