# Implementation Plan: Personal Data Erasure

**Branch**: `042-personal-data-erasure-design` | **Date**: 2026-11-04 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/042-personal-data-erasure/spec.md`

## Summary

A personal field is a type, `Personal`, in all seven SDKs. Its codec writes the personal envelope:
the data subject in the clear beside AES-256-GCM ciphertext under the subject's key, bound to the
subject, the project and the manifest. The key comes from a keyring handle the runtime installs, so
the runtime, the sidecar and every store keep moving bytes. Erasure destroys the key in the
**keyring**, a new standalone platform component with its own database. It is not an ankka
application, because a journal cannot forget. Erasure is first written to an erasure log kept in
the control plane's database and in a platform bucket. Services are told over the channel they
fetch keys on. Each service then redacts its rows, drops its lookup tokens, ends its agent sessions,
runs its erasure handler and acks. The control plane owns the erasure request: holds, overrides,
the sweep, the certificate, the routes, the CLI, the console and the MCP tools. Erasure is an
installation setting, off by default. A replicated entity's followers reach the home's keyring
through a relay SPI that ankka-replication implements. Restores replay the log before anything
answers.

Technically (research R1 to R25):

- **The codec and the keyring handle**: `Personal` and its companion codec in `core` (R1). The
  manifest reaches the codec through a scoped encoding context (R2). `Keyring.current` is the one
  global handle (R3).
- **Recovery behaviour**: entity hosts restart with backoff on `KeyringUnavailable` (R4). A key miss
  blocks the entity's thread, bounded by a deadline (R5).
- **Process SDKs**: they fetch keys before their synchronous codecs run (R6). Protocol 1.23 adds
  `GetSubjectKey`, a process-started `SubjectNotices`, `AckSubjectNotice` and `AskErasure` (R7). The
  erasure handler is a served component, as MCP is (R8). A module seals and opens in the host (R9).
- **The keyring**: a standalone component with its own database (R10). Keys are wrapped twice
  (R11).
- **Views**: they know their personal fields from the row type (R12). Lookup tokens are a column
  (R13), and redaction finds a subject's rows by jsonpath (R14).
- **Completion and the handler**: completion is acked to the keyring and read by the control plane
  (R15). The handler runs on one instance per service, and `storage.erase` deletes every version
  (R16).
- **Platform**: the replication relay SPI (R17); the keyring as a `wrapping-key` requester (R18);
  the setting and the component (R19); a local keyring in the service's database (R20).
- **Control plane and surfaces**: erasure requests are their own control plane entity (R21). The
  erasure log has two copies and is applied before anything answers (R22).
- **Agents and telemetry**: agents wrap what they persist (R23); telemetry drops envelopes (R24).
- **The console and the MCP tools** (R25).

Planning found six things the spec did not know. Each is decided in research and written back as a
planning amendment below.

## Technical Context

**Language/Version**: Scala 3.9 on JDK 21 (`core`, `sdk`, `runtime`, `http`, `grpc`, `agent`,
`testkit`, `sidecar`, `crd`, `controlplane-api`, `controlplane`, `operator`, `cli`, and a new
`keyring`); Java 21 and Kotlin (`sdk-java`, `testkit-java`, Kotlin extensions); Python ≥ 3.12;
TypeScript on Node ≥ 22.22 (`sdks/typescript`, `console/package`); Go (`sdks/go`); Rust 1.88 for
`wasm32-unknown-unknown`; protobuf; YAML (kustomize, CRD schema); Gherkin.

**Primary Dependencies**: one added, `cryptography` (≥ 44, < 47) in the Python SDK, because the
standard library has no AES (R6a). Everywhere else: `javax.crypto` on the JVM, `node:crypto`, Go's
`crypto/aes` and `crypto/cipher`, and the host import for Rust modules. The keyring uses grpc-java
and r2dbc-postgres already on the classpath.

**Storage**: the keyring's own CNPG cluster (tables in `data-model.md`); the control plane's
`erasure_log` table and the `ErasureRequestEntity` journal and view; the `platform.erasure-log`
bucket; a `lookup` column on views with lookup fields; `ankka_keyring` in the service's database
locally. No new file in `kustomization/components/postgres/ddl/`: every new table is made by code,
as `secret-reads`' is.

**Testing**: munit everywhere in Scala; `ErasureFeatures` (`GherkinSuite`, in-process, two kits for
projects and installations); `ErasureClusterFeatures` and `ErasureRestoreClusterFeatures` (k3s, in
the nightly matrix unlisted); `PersonalLeakSuite`; `PersonalRecoveryBenchmark`; `keyring/test`
against Postgres by testcontainers; the four conformance references and `ConformanceSuite`'s
`personal.*`/`erasure.*`; pytest, vitest, go test, cargo test against the shared fixtures; the
console's unit and Playwright tests; `EventCompatibilitySuite` for the control plane's and agents'
new events; `RenderingUnchangedSuite`; `RemoteOverlaySuite`; the docs build; the features check.

**Target Platform**: an installation with erasure on (k3s locally through `components/keyring`;
Google Cloud with `ankka-gcp` for the wrapping key); a laptop with `sbt run` or `ankka local`; the
test kits.

**Project Type**: an SDK surface in seven languages; the runtime, the agent module, the sidecar
and the Wasm ABI; a new platform component; the descriptor, resource, operator, control plane, CLI,
console and MCP server; documentation.

**Performance Goals**: recovery of 1,000 events of one subject ≤ 1.3× without personal fields
(SC-007); a destroyed notice reaches every instance within 60 s (SC-004); a held request applied
within 15 min of its date (SC-006); a cached key costs one AES-GCM operation per field.

**Constraints**: nothing outside a codec parses an envelope (FR-006); no key, plaintext value or
lookup key in a log, span, status, event or the control plane's database; the control plane cannot
read a key; nothing rendered for a service without `personal` differs; a stored journal and every
existing control plane event decode unchanged; warning-free; serialized tests; no fixed ports;
`PlatformVariables.scala` standard library only; `ReplicationTransport`'s signature unchanged.

**Scale/Scope**: one new sbt project (`keyring`, about 15 files); about 30 new and 60 changed Scala
files across fourteen modules; about 6 new and 15 changed files per process SDK; 4 new Java and 2
Kotlin files; one proto service pair, five client rpcs, two imports and one export; one kustomize
component; about 8 console files; 2 new docs pages and about 10 changed; 12 feature files and `facts.feature`, 63
scenarios.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md`, `ankka-brain/principles.md` and the rule files state, as earlier plans read them:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; one thing performs them | pass | handlers still return effects; the codec runs where serialization already runs; the operator's new actions are descriptions `Fabric8Executor` performs |
| Explicit registration, never scanning | pass | the erasure handler is registered on the service; a view's personal fields come from its declared row type (R12); the relay is a ServiceLoader SPI like the transport's (R17) |
| Module dependency direction | pass | `Personal` and the handle trait in `core` (no Pekko); the client and duties in `runtime`; `keyring` depends on `grpc` (so on `http` and `runtime` as libraries) and is a leaf nothing depends on; the operator gains nothing beyond `crd` |
| The wire name is separate from the method name | pass | new rpcs and imports have their own names; `Personal`'s method names are each language's |
| A field on the resource needs the schema | pass | `spec.personal`, `CrdSchemaSuite` both ways |
| Never render what must not roll | pass | `RenderingUnchangedSuite` for services without `personal` |
| A credential is written where the pod reads it and never read back | pass | the root key in the keyring's own secret store; the control plane's identity refused a key (FR-018) |
| Nothing the platform does may destroy data | **departure** | destroying a key is this feature's purpose; it is done only on an applied erasure request, logged first in two places, and never by any other path (Complexity Tracking) |
| Stored forms stay readable both ways | pass | a journal without envelopes decodes unchanged; new agent and control plane fields `NON_ABSENT`; fixtures pinned, never regenerated |
| Which variables are the platform's is said once | pass | `ANKKA_ERASURE`, `ANKKA_KEYRING_INSTANCES`, `ANKKA_KEYRING_ADDRESS` in `PlatformVariables` |
| A behaviour an older runtime must refuse is a new rpc or import | pass | 1.23's rpcs, `Erasure` service, `keyring_*` imports; each SDK refuses personal fields under 1.22 |
| Could this check pass while the thing it checks is false? | pass | `assertNoPersonalValue` is proven red by a bypassed codec; the leak suite interpolates whole events; the sidecar's no-inspection case feeds bytes it could not parse; the restore cases assert readiness is *held*, not just the end state |
| Each acceptance scenario ends as a test that fails without the feature | pass | 63 scenarios: in-process by `ErasureFeatures`, cluster by the two k3s suites, language cases by the SDK suites and conformance (named in `ranElsewhere`), the Google-only object case by ankka-gcp (`ranOutside`) |
| Be your own customer / nothing civic or casino-specific in the platform | pass | the platform knows subjects, not players; holds are dates the domain gives |
| Docs: pages stand alone, samples from tested code, limitations honest | pass | `contracts/platform.md`'s docs list; limitations say what erasure is not |
| Every tracked file claimed by a CI path filter | pass | `keyring/` is a new top-level directory: `.github/ci-coverage.py` and the `build` job's filter gain it |
| Every port a workload has is mutual TLS | pass | the keyring's gRPC port; the gateway's `Decrypt` route is the gateway's TLS with a machine token |

**Violations to justify**: one, in *Complexity Tracking*.

**Post-design re-check**: unchanged after Phase 1.

## Project Structure

### Documentation (this feature)

```text
specs/042-personal-data-erasure/
├── plan.md                          # this file
├── research.md                      # R1–R25
├── data-model.md                    # SDK values, envelope, keyring store, erasure request, log, certificate, settings
├── quickstart.md                    # the validation runs: codec → in-process → benchmark → conformance → offline → k3s → docs
├── contracts/
│   ├── sdk.md                       # Personal in seven languages, errors, the handler, storage.erase, agents, views, the test kit
│   ├── keyring-and-protocol.md      # the keyring's gRPC service, startup, the runtime client, protocol 1.23, the Wasm ABI, fixtures
│   ├── control-plane-and-cli.md     # routes, facts, descriptor refusal, CLI, console, MCP tools
│   └── platform.md                  # the component, the operator, restores, documentation
└── tasks.md                         # /speckit-tasks
```

Acceptance scenarios: `features/erasure/` (twelve files) and `features/installation/facts.feature`.

### Source Code (repository root)

```text
modules/core/…/core/Personal.scala                   # new: Personal, DataSubject, the codec, PersonalScope
modules/core/…/core/Keyring.scala                    # new: the handle trait, unavailable, current
modules/core/…/core/Serializer.scala                 # binds PersonalScope around encode/decode
modules/core/…/core/PlatformVariables.scala          # ANKKA_ERASURE, ANKKA_KEYRING_INSTANCES, ANKKA_KEYRING_ADDRESS
modules/sdk/…/sdk/ErasureHandler.scala, Erasures.scala, contexts.scala, DeclaredQuery.scala, PersonalFields.scala (new)
modules/runtime/…/runtime/keyring/{KeyringClient,KeyringHandle,KeyCache,ErasureDuties,LocalKeyring,ViewRedaction}.scala (new)
modules/runtime/…/runtime/replication/KeyringRelay.scala (new)
modules/runtime/…/runtime/{Ankka,EventSourcedEntityHost,KeyValueEntityHost,WorkflowHost,QueryCheck,ViewStore,ProjectionSupport,Recorder,ExtensionsReadiness}.scala
modules/runtime/…/runtime/storage/{S3Bucket,DirectoryBucket,MemoryBucket,ObjectStorages}.scala   # erase
modules/runtime/src/main/resources/reference.conf    # ankka.keyring
modules/agent/…/agent/{SessionMemoryEntity,memory,AgentRuntime}.scala, autonomous/{InstanceEntity,TaskEntity,AutonomousAgentHost,Approvals}.scala
modules/testkit/…/testkit/{AnkkaTestKit,InMemoryKeyring (new),PersonalAssertions (new),TestKeyringRelay (new)}.scala
modules/testkit/src/test/…/{ErasureFeatures,PersonalLeakSuite,PersonalRecoveryBenchmark,KeyringOutageSuite,MultiProjectKeyringSuite}.scala
modules/telemetry-otlp/…/RecordedSpanData.scala
modules/sdk-java/…/javasdk/{Personal,DataSubject,ErasureHandler}.java, codec/{Plan,Planner,JsonWriter,JsonReader,Schemas}.java
modules/sdk-kotlin/…/Personal.kt
modules/testkit-java/…/AnkkaTestKit.java

keyring/                                             # new sbt project and image
  src/main/protobuf/ankka/keyring/v1/keyring.proto
  src/main/scala/…/keyring/{Main,KeyringServer,KeyStore,RootKey,Wrapping,Admission,Notices,ErasureLogReplay,DecryptRoute}.scala
  src/test/scala/…/keyring/*Suite.scala

protocol/src/main/protobuf/ankka/protocol/v1/{client,discovery,erasure}.proto   # 1.23
protocol/fixtures/personal/{subjects,envelopes}.json (new); protocol/WASM-ABI.md; protocol/README.md; protocol/ENCODING.md
sidecar/…/sidecar/{ClientLogic,ClientService,Discovery,GrpcConversation}.scala, wasm/{HostImports,ModuleLoader,CallSite,WasmConversation}.scala
modules/runtime/…/runtime/remote/Conversation.scala  # invokeErasure; WireProtocol.Version 1.23
sidecar/src/test/…/conformance/{ConformanceSuite,ConformanceReference}.scala

sdks/python/src/ankka/{personal,keyring,erasure}.py (new), codec.py, server.py, storage.py, testkit/*
sdks/typescript/src/{personal,keyring,erasure}.ts (new), codec.ts, schema.ts, json.ts, storage.ts, server/erasure.ts (new), testkit/*
sdks/go/{personal,keyring,erasure}.go (new), plan.go, json_write.go, json_read.go, schema.go, storage*.go, internal/server/erasure.go (new), testkit/*
sdks/rust/ankka/src/{personal,erasure}.rs (new), abi/imports.rs, codec/mod.rs, storage.rs, testkit/*
sdks/{python,typescript,go,rust}: protocol copies; examples' conformance

crd/…/crd/{AnkkaService,CloudResource}.scala         # personal; Requester.Platform
kustomization/components/crd/ankkaservice.yaml
kustomization/components/keyring/ (new)              # deployment, CNPG cluster, certificate, network policy, patches
kustomization/overlays/{local,cloud}/…               # the setting, replacements
operator/…/operator/{Rendering,ZeroTrust,PlatformBuckets,CloudRequests,Settings}.scala
controlplane-api/…/api/{descriptors,Erasures (new)}.scala
controlplane/…/controlplane/erasure/{ErasureRequestEntity,ErasureRequestsView,ErasureSweep,ErasureLog,KeyringAdmin}.scala (new)
controlplane/…/controlplane/api/{ErasureEndpoint (new),InstallationEndpoint,ServiceEndpoint}.scala
controlplane/…/controlplane/deploy/RestoreHold.scala, ControlPlane.scala
controlplane/src/test/…/{ErasureClusterFeatures,ErasureRestoreClusterFeatures}.scala (new)
cli/…/cli/{Main,ControlPlaneClient,Output}.scala, mcp/AnkkaTools.scala, local/LocalErasures.scala (new)
console/package/src/{routes/project.tsx,routes/erasure.tsx (new),client/control-plane.ts,client/schemas.ts,testing/fake-control-plane.ts}, fixtures/control-plane/

build.sbt                                            # keyring project; images for cluster tests
.github/ci-coverage.py, .github/workflows/ci.yml     # the keyring filter
docs/build/personal-data.md (new), docs/platform/erasure.md (new), and the pages in contracts/platform.md
tools/docs/skill/*/SKILL.md                          # the five skills
```

**Structure Decision**: one new top-level sbt project, `keyring`, because the keyring is a separate
image with its own identity and database. It is not an ankka application (R10). Everything else
goes beside its precedent: `Personal` beside `Serializer`, the handle beside `SecretStore`, the
client under `runtime/keyring/`, the relay beside the replication transport, the rpcs beside the
secret ones, the imports beside `storage`, the request entity beside the project's grants, and the
component beside `secret-reads` and `models`.

## Order of work

Cut by user story, tests before the code they hold. Slices 1 to 6 need no cluster.

1. **The codec and the handle** (FR-001 to FR-006, FR-013, FR-029, story 1 in Scala). `Personal`,
   `DataSubject`, the envelope fixtures, `PersonalScope`, `Keyring`, `InMemoryKeyring`,
   `KeyringHandle` over an in-memory store, `assertNoPersonalValue`, `PersonalLeakSuite`.
   `personal-fields.feature` is green in-process.
2. **Erasure in one project, in-process** (FR-009 to FR-013, FR-015, FR-016, FR-020, FR-022,
   FR-023, stories 2 and 4). The runtime's `KeyringClient`, cache and notices, `ErasureDuties`, view
   redaction and lookup tokens (R12 to R14), supervision (R4), `LocalKeyring`, the handler on its
   singleton, `ErasureRequestEntity` and the sweep against the in-memory keyring, the routes, the
   certificate, `PersonalRecoveryBenchmark`. `erasing.feature` and `holds.feature` are green.
3. **The keyring** (FR-017 to FR-019, FR-021's keyring half, R10, R11, R22). The `keyring` project:
   store, wrapping, root key on Postgres, admission, notices, destroy and vacuum, tombstones, log
   replay from two copies. `keyring/test`. The runtime client is then pointed at it in
   `ErasureFeatures`.
4. **The JVM languages** (FR-001, story 1's Java and Kotlin outline). Record codec `Plan` case,
   `Personal` in Java and Kotlin, the handler and the kits.
5. **Process SDKs and modules** (FR-001, FR-003, FR-010, R6 to R9, SC-008). Protocol 1.23, the
   sidecar's rpcs and `Erasure` service, Python, TypeScript and Go `Personal`, prefetch, cache,
   handler, kits; the Rust crate and the two imports; the fixtures; conformance.
   `languages.feature` is green.
6. **Agents, objects, other projects, filing by a service, the replication relay** (FR-008,
   FR-018, FR-024 to FR-027, FR-031, FR-032, stories 5 to 8). Agents' wrapping and stopping;
   `storage.erase` in five stores; the decrypt grant admission and `Decrypt`; `AskErasure`;
   `KeyringRelay` with the test relay over `LocalInstallations`. `agents`, `objects` (Garage),
   `other-projects`, `asking` and `replication` are green in-process.
7. **Surfaces** (FR-007, FR-033, R19, R25). The setting, `InstallationErasure`, the descriptor
   refusal, the CLI, `ankka local erasures`, the MCP tools, the console section and Playwright.
   `places.feature` and the `facts.feature` changes are green.
8. **The platform on k3s** (FR-017's installation half, FR-021, R18, R19, R22). `components/keyring`,
   the operator's rendering and bucket, the restore hold and replay, `ErasureClusterFeatures` and
   `ErasureRestoreClusterFeatures` through the `cluster` workflow; the wrapping-key requester with
   the scripted provider.
9. **Documentation** (two new pages, the changed ones, the skills, limitations), then `sbt buildAll`.

Slice 2 needs 1. Slice 3 is independent of 2 until its last step. Slices 4 and 5 need 1. Slice 6
needs 2 and 5. Slice 7 needs 2. Slice 8 needs 3 and 7.

## Complexity Tracking

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| The platform destroys data (a subject key) | it is what erasure is; GDPR Article 17 | there is no erasure without it; the destruction is guarded by an applied request, logged in two places before it happens, and reachable by no other path |
| A new top-level project and image, `keyring`, which is not an ankka application (R10) | a journal is append-only; a destroyed key in it is not destroyed | keys in the control plane put them in its backups and beside an identity that must not read them; KMS per subject is Google-only and one KMS key per person |
| A process-level global, `Keyring.current` (R3) | a codec is reached by derivation, not by a host | passing the keyring through `Serializer` changes every serializer's signature in seven languages |
| Entity hosts gain supervision (R4) | "recovery fails and is retried with backoff" has no mechanism today | letting the actor stop and wait for the next message gives no backoff, and a waiting caller times out with no reason |
| A thread-local encoding scope for the manifest (R2) | the manifest is associated data and a codec does not see it | dropping it lets an envelope move between payload types of one subject |
| `cryptography` added to the Python SDK (R6a) | the standard library has no AES | sealing through the sidecar puts plaintext in the sidecar and a round trip on every payload; the SDK already takes native wheels (`grpcio`) |
| A `lookup` column on views with lookup fields (R13) | tokens must be removable without re-encoding a row | tokens inside the payload are under the row's codec |

**Operational consequences** to announce in the release:

- Protocol 1.23. A process SDK that uses `Personal` or a handler is refused by an older runtime,
  and an older SDK runs unchanged on the new one.
- Erasure is off until an installation adds `components/keyring`. A descriptor with
  `"personal": true` is refused until then.
- On Google Cloud, `ankka-gcp` must fulfil a `wrapping-key` request from the platform requester.
- ankka-replication must implement `KeyringRelay` before a replicated component's personal fields
  can be read at a follower. Until it does, the follower's recovery fails and retries, naming the
  relay.
- Events written before a service declares personal fields stay as written (limitations).

## Planning amendments

Written into the spec under *Planning amendments*:

1. FR-004's manifest reaches the codec through a scoped encoding context (R2).
2. FR-003: Python, TypeScript and Go fetch keys before their synchronous codecs run (R6). The
   process's channel is the process-started `SubjectNotices` (R7).
3. The keyring-outage edge case: recovery is retried by a supervision this feature adds (R4).
4. The declared-query edge case: a view knows its personal fields from its row type, and a Rust
   view declares them by hand (R12).
5. FR-032: the follower's fetch goes through a `KeyringRelay` SPI that ankka-replication implements
   (R17).
6. FR-017: the keyring is a standalone component with its own database, not an ankka application
   (R10).
