# Implementation Plan: Secret Store Backends — Google Secret Manager Beside Postgres

**Branch**: `038-secret-store-backends-impl` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/038-secret-store-backends/spec.md`, clarified on
2026-10-08 (five questions, recorded in the spec), and the living features under `features/secrets/`.

## Summary

Put secret storage behind the seam that already exists (`SecretStore` in `sdk`, implemented by
`DatabaseSecretStore` in `runtime`) and add a second implementation, `SecretManagerStore`, that
speaks Google Secret Manager's REST API over the JDK HTTP client with the pod's Workload Identity
token — no cloud library. The backend is one installation setting, `ANKKA_SECRET_BACKEND`, that the
operator gives every service; a service's code, descriptor and SDK calls do not change, and the
sidecar's `GetSecret`/`PutSecret`/`DeleteSecret` reach the new store unchanged. Every `get`, `put`
and `delete` on either backend first writes a **read record** to the control plane over the
service's own certificate and is refused when the record is not acknowledged; the control plane
keeps the records in a CNPG cluster of their own, lists them to an owner, and sweeps them by
retention. Project secrets on the Secret Manager backend are versions the control plane adds and
cannot read; their sync into Kubernetes Secrets, a service's IAM grant and the audit-logging status
are the cloud provider's (spec 044), which the operator asks through 044's `CloudResource` and holds
rollouts on. Moving an installation from Postgres is a phased setting (`copy`, `check`, `remove`)
each service performs when it starts and reports in its status. The test kit gains an in-process
Secret Manager fake that enforces the same grants, so every suite stays offline.

**Nothing of 044 exists in the tree yet.** The work therefore splits into **Group A** — everything
that stands without a cloud provider: the seam, the Secret Manager store and its fake, the read
record end to end, the move phases, the control plane's writer, the settings, the docs — and
**Group B** — what asks 044 for something: the `secret-access` and `secret-sync` requests, the
rollout hold, the Workload Identity principal, the audit-logging status, the project secret move.
Group B is written against 044's contract as this plan amends it, and starts when 044's
`CloudResource`, operator request writing and fake provider are on `main`.

## Technical Context

**Language/Version**: Scala 3 on Apache Pekko (the repository's toolchain); `-Werror`, `-Wunused`.
**Primary Dependencies**: none added to any published module. `runtime` gains a REST client over
`java.net.http.HttpClient` (already used by `HttpServiceClients`) and jsoniter codecs (already
present). `http` gains one `CallerMatcher` case. `controlplane` gains a second r2dbc pool through the
existing `Database` class. The operator gains nothing (044's fabric8 use is 044's).
**Storage**: the service's Postgres (`ankka_secrets`, unchanged) or Google Secret Manager; the read
record in a CNPG cluster of its own (`ankka-secret-reads-db`, table `secret_reads`), apart from the
control plane's journal and every service's database.
**Testing**: munit through `AnkkaTestKit` against `SharedPostgres` and the new `FakeSecretManager`;
`GherkinSuite` over `features/secrets/{backend,secret-manager,read-record,moving,grants}.feature`
where one suite can run a file whole; the sidecar `ConformanceSuite` `secret.*` cases on both
backends; operator rendering suites (golden, unchanged, identity); one k3s suite per concern on the
`cluster` workflow, never on a pull request; GKE proof is `ankka-gcp`'s nightly, outside this
repository.
**Target Platform**: the runtime everywhere a service runs (JVM embedded, sidecar process, WASM
module); the Secret Manager backend on GKE with Workload Identity Federation only.
**Project Type**: platform (library modules + control plane + operator + kustomization).
**Performance Goals**: SC-007 — a value put on one instance is read on another within one second;
SC-006 — the secret store suites stay within +10% of today's time; a `get` on Secret Manager is one
record post plus one access call, no cache.
**Constraints**: fail-closed records (no value without a record); no secret value in any journal,
log, action, status or event; no cloud library; the operator holds no `get` on Secrets and no Google
credential; rendering unchanged for an installation that sets nothing (`RenderingUnchangedSuite`);
DDL additive within a supported range; every new test switch forwarded in `Test / javaOptions`.
**Scale/Scope**: one installation, hundreds of services, a service secret per outbound credential;
reads bounded by Secret Manager's per-project access quota (a sizing fact, documented, not cached).

## Constitution Check

*GATE: `.specify/memory/constitution.md` is the unfilled template, so the gates are CLAUDE.md's
standing rules. Checked before research and again after design.*

| Rule | Status |
|---|---|
| Effects are inert data; the runtime interprets them | n/a — no new effect; the store is a blocking client as today |
| Module dependency direction (`core → sdk → runtime → {http, agent} → testkit`; `crd` depends on nothing; the operator on `crd` only) | holds — the store is in `runtime`; the fake in `testkit`; the matcher in `http`; the status field in `crd`; the operator reads 044's resource through 044's code |
| Platform variables declared once in `core`'s `PlatformVariables`; the operator compiles that file | holds — six names added there and nowhere else; `PlatformDeclarationSuite` pins it |
| No secret value in the control plane's journal, logs, actions or events | holds — the record carries name and outcome; the move compares digests; actions carry ids and prefixes |
| The operator has no `get` on Secrets and never needs one | holds — nothing added reads a Secret |
| Wire names are versioning boundaries, declared apart from method names | holds — new commands/routes get names of their own |
| A new DDL file is named in seven lists | n/a by design — the record's DDL is a separate database with its own ConfigMap, not the service schema |
| Tests fail without the feature; every `eventually` waits for what it asserts | planned per suite in quickstart.md |
| 044 FR-014: no cloud client library | holds, with the exception amended into 044 (research R1) |

**Post-design re-check**: no violation. The one judgement call — a cloud-specific store in
`runtime` — is the spec's own clarified decision and is now written into 044 FR-014.

## Project Structure

### Documentation (this feature)

```text
specs/038-secret-store-backends/
├── plan.md              # this file
├── research.md          # R1–R12: the decisions and the Google facts they rest on
├── data-model.md        # records, ids, settings, phases, statuses, the fake's model
├── quickstart.md        # how to prove it: offline suites, k3s, GKE
├── contracts/
│   ├── control-plane-api.md   # POST /secret-reads, GET /projects/{id}/secret-reads, the CLI
│   ├── secret-manager.md      # the REST subset the store and the fake implement, ids, errors
│   ├── platform-settings.md   # every variable: who sets it, who reads it, default
│   └── cloud-provider.md      # what 038 asks of 044: the two kinds, the hold, the seeding clause
└── tasks.md             # /speckit-tasks output (not created here)
```

### Source Code (repository root)

```text
modules/core/src/main/scala/.../core/
└── PlatformVariables.scala          # + SECRET_BACKEND, SECRET_MOVE, SECRET_RECORDS_URL,
                                     #   SECRET_VERSIONS_KEPT, CLOUD_PROVIDER, CLOUD_ACCOUNT

modules/sdk/src/main/scala/.../sdk/
└── SecretStore.scala                # scaladoc generalised: "the installation's backend"; no API change

modules/http/src/main/scala/.../http/
└── Caller.scala                     # + CallerMatcher.AnyService; Callers.anyService

modules/runtime/src/main/scala/.../runtime/
├── Ankka.scala                      # host(): choose backend, wrap in RecordingSecretStore, register SecretMove
├── DatabaseSecretStore.scala        # timeout from ankka.secrets.timeout
├── ExtensionsReadiness.scala        # + a reason per not-ready extension; ProbeEndpoint puts it in the 503 body
├── ObservabilityDocuments.scala     # + secretStore section (backend, move phase, per-name outcome)
├── secrets/
│   ├── SecretBackend.scala          # the setting, parsed; Postgres | SecretManager
│   ├── DerivedIds.scala             # R2: prefix, tail escape, digest form, the name annotation
│   ├── SecretManager.scala          # the REST client (create, addVersion, access, delete, list, destroy, disable)
│   ├── AccessTokens.scala           # metadata-server tokens with refresh; fixed token for tests
│   ├── SecretManagerStore.scala     # SecretStore over the client; kept-count pruning; error mapping (R11)
│   ├── ReadRecord.scala             # the record value and its codec (shared with the control plane via core? no — see note)
│   ├── RecordingSecretStore.scala   # the wrapper: build the record from Trace/CallOrigin, post, then delegate
│   ├── ReadRecorder.scala           # trait; ControlPlaneRecorder (HTTPS, fail-closed); LocalRecorder (log)
│   └── SecretMove.scala             # RuntimeExtension: copy | check | remove; CopyCheck digests
└── resources/reference.conf         # ankka.secrets.{backend,timeout,record-timeout,records-url,move,versions-kept,
                                     #   secret-manager.{endpoint,token,identity}}, ankka.cloud.{provider,account,location}

modules/core/src/main/scala/.../core/
└── secrets/ReadRecord.scala         # the record's wire shape lives in core so runtime and controlplane-api share it

modules/testkit/src/main/scala/.../testkit/
├── AnkkaTestKit.scala               # + secretBackend parameter; recordedReads
├── FakeSecretManager.scala          # R10: loopback REST fake enforcing the grants; unreachable/failNext/snapshot
├── SecretBackend.scala              # test-side helper building the kit settings for the fake
└── RecordedReads.scala              # what LocalRecorder hands a test

controlplane-api/src/main/scala/.../api/
└── descriptors.scala                # + SecretReadRecord wire type, SecretReadsPage; Wire codecs

controlplane/src/main/scala/.../controlplane/
├── ControlPlane.scala               # choose ProjectSecretWriter by backend; register the record store and sweeper
├── api/SecretReadsEndpoint.scala    # POST /secret-reads (AnyService caller), GET /projects/{id}/secret-reads (owner)
├── api/PlatformEndpoint.scala       # GET /platform: the installation's status (044 FR-019 extends it)
├── secrets/ReadRecordStore.scala    # trait; PostgresReadRecordStore (second pool); InMemory for suites
├── secrets/ReadRecordSweeper.scala  # ClusterSingleton, daily, by retention
├── secrets/SecretManagerProjectSecretWriter.scala   # setEntries → create+addVersion; removeEntry → disable
├── secrets/SecretRecordsConfig.scala                # ANKKA_SECRET_RECORD_RETENTION, the pool's config path
└── deploy/StatusIngest.scala        # fold status.secretStore into detail (Group B)

cli/src/main/scala/.../cli/
├── Main.scala                       # projects secret-reads list [--service] [--name] [--from] [--to]
├── ControlPlaneClient.scala         # listSecretReads
└── Output.scala                     # secretReads(rows, format)

crd/src/main/scala/.../crd/
└── AnkkaService.scala               # + secretsRemoved on the spec (Group A); SecretStoreStatus (phase, detail) on the status (Group B)
kustomization/components/crd/ankkaservice.yaml       # + status.secretStore (CrdSchemaSuite)

operator/src/main/scala/.../operator/
├── Settings.scala                   # secretBackend, secretMove, secretRecordsUrl, versionsKept, cloudProvider, cloudAccount
├── Rendering.scala                  # secretsEnv: literals on the platform container, only when set
├── ServiceReconciler.scala          # Group B: hold ApplyDeployment on secret-access / secret-sync status
└── (044's CloudResource rendering)  # Group B: secret-access with prefixes; secret-sync from AnkkaProject.spec.secrets

kustomization/
├── components/secret-reads/         # CNPG Cluster ankka-secret-reads-db, schema ConfigMap (secret_reads + grants)
├── components/controlplane/deployment.yaml   # env placeholders: ANKKA_SECRET_*, ANKKA_SECRET_RECORDS_DB_* from -app Secret
├── components/operator/operator.yaml         # env placeholders: ANKKA_SECRET_BACKEND, _MOVE, _VERSIONS_KEPT, ANKKA_CLOUD_*
├── overlays/local/{platform-configmap,kustomization}.yaml   # keys + replacements; secret-reads component
└── overlays/cloud/{platform-configmap,kustomization}.yaml   # same

sidecar/src/test/scala/.../conformance/ConformanceSuite.scala   # secret.* under -Dankka.conformance.secrets=secret-manager
docs/platform/secrets.md, docs/build/secrets.md, docs/reference/configuration.md,
docs/reference/control-plane-api.md, docs/reference/cli.md, docs/reference/limitations.md,
docs/platform/install-cloud.md
```

**Structure Decision**: no new sbt module. The store, the client, the ids, the recorder and the
move are a `secrets` package in `runtime`, because the sidecar image must carry them and because
`runtime` already owns `DatabaseSecretStore`. The record's wire shape goes in `core` so
`controlplane-api` (which depends on `core` alone) can expose it to the CLI without redefining it.
The fake is `testkit` main code so three modules' tests and a developer's can use it.

## Design, by concern

Each item names the spec requirement it answers and the research entry it rests on.

### 1. The seam and the backend setting (FR-001–FR-004; R6)

- `SecretBackend.parse(config)`: `""`/`postgres` → `Postgres`; `secret-manager` → `SecretManager`;
  anything else refuses start naming the value.
- `Ankka.host`: build the backend store; wrap in `RecordingSecretStore(underlying, recorder,
  identity, hosting)`; register `SecretMove` when the move phase is set. `StepScope.stepsOnly`
  wraps the result as today.
- `ServiceBuilder.hosting(Hosting)`: `embedded` by default; the sidecar's `Main` sets `process` or
  `module`, so the record's `hosting` is right without the runtime guessing.
- The control plane: `ControlPlane.builder` reads `SecretBackend` too and passes
  `SecretManagerProjectSecretWriter` instead of the projector when it is `SecretManager`; with
  `ANKKA_CLOUD_PROVIDER=none` and backend `secret-manager` it refuses to start naming `gcp`
  (044 FR-012).
- Both backends run one scenario set: `SecretStoreSuite` (testkit tests) becomes an abstract
  `SecretStoreBehaviours` with two concrete suites, Postgres and Secret Manager fake; the fake suite
  also asserts `ankka_secrets` is empty.

### 2. The Secret Manager store (FR-005–FR-008; R1, R2, R8, R11)

- `SecretManager` client: `createSecret(id, annotations, replication)`, `addVersion(id, bytes)`,
  `accessLatest(id)` → `Option[(version, bytes)]`, `deleteSecret(id)`, `listEnabledVersions(id)`,
  `destroyVersion(id, n)`, `disableVersion(id, n)`. Replication: `automatic` unless
  `ankka.secrets.secret-manager.locations` names regions (`userManaged`); the KMS key from
  `ANKKA_CLOUD_KMS_KEY` is passed through when set (044 declares it; the runtime reads it only here).
- `SecretManagerStore.put`: rules → `addVersion`; on 404 `createSecret` then `addVersion`; then
  prune to the kept count. `get`: rules → `accessLatest`. `delete`: rules → `deleteSecret`, 404 is
  success.
- Error mapping per R11; every call bounded by `ankka.secrets.timeout`.
- `DerivedIds.service(project, service, name)`, `DerivedIds.projectEntry(project, secret, entry)`,
  `DerivedIds.servicePrefix`, `DerivedIds.projectPrefix`; a property test for injectivity and
  length; the digest form past 255.

### 3. The read record (FR-013, FR-013a, FR-013b; R4, R5)

- `ReadRecord(name, project, service, hosting, operation: get|put|delete, outcome:
  read|none|refused|unavailable|written|removed, at, traceId, spanId, component, componentKind,
  latestSkipped)` in `core`; the codec omits absent optionals.
- `RecordingSecretStore`: builds the record from `Trace.currentContext` (trace and span ids) and
  `Trace.currentOrigin` (component; its kind from `ComponentRegistry`), calls the backend, then
  writes one record carrying the outcome **before** the value is returned. A backend failure is
  recorded as `unavailable`/`refused` and then rethrown; a record post that fails turns any outcome
  into `Unavailable` and the value is dropped.
- `ControlPlaneRecorder`: `POST <ankka.secrets.records-url>/secret-reads`, the service's
  certificate, requiring `ankka://platform/controlplane`; 2xx within `ankka.secrets.record-timeout`
  or `Unavailable`. The URL's default is set by the Kubernetes cluster overlay
  (`ankka-cluster-kubernetes.conf`) and overridden by `ANKKA_SECRET_RECORDS_URL`; the operator
  renders nothing for it. `LocalRecorder` when the URL is unset (the local overlay): an info line
  `secret-read name=… outcome=…` and `RecordedReads` in the test kit.
- `GET /platform` (`PlatformEndpoint`): the installation's status for any authenticated principal —
  `secretBackend`, `cloudProvider`, `cloudAccount`, `cloudLocation`, `secretRecordRetention`,
  `auditLog: on|off|unknown` (`unknown` until 044's provider reports it); never the KMS key. CLI
  `ankka platform status`. 044 FR-019 extends this route.
- Control plane: `POST /secret-reads` with `Acl.allowCallers(Callers.anyService)`; the body's
  `project`/`service` must equal `caller`'s or `403`; `Caller.Local` admitted (local and tests).
  `PostgresReadRecordStore.insert` over the second pool; `GET /projects/{id}/secret-reads` owner-only,
  newest first, `limit` ≤ 1000, `from`/`to` instants, `service`/`name` exact.
- `ReadRecordSweeper`: singleton, daily, `DELETE … WHERE at < now() - retention`.
- The record's database: `components/secret-reads` (CNPG `Cluster`, 1 instance, its own schema
  ConfigMap, grants literal), both overlays; the control plane's `ANKKA_SECRET_RECORDS_DB_{HOST,
  PORT,NAME,USER,PASSWORD}` from `ankka-secret-reads-db-app`. 041 will name this cluster's backup.

### 4. The move (FR-017–FR-021; R7)

- `SecretMove(phase, database, key, store, report)`; runs once at start on a virtual thread;
  `readiness` is `Some(() => done)` for `copy`, `Some(() => true)` otherwise; the probe body names
  the reason while not ready (`ExtensionsReadiness.reasons`).
- `CopyCheck.run`: for each row, digest of the decrypted value vs digest of `accessLatest`; result
  `Map[name, equal|different|missing-in-secret-manager|missing-in-database]`; never a value.
- `remove`: `CopyCheck` first; all equal → `DELETE FROM ankka_secrets`; else leave rows.
- The observe document gains `secretStore: {backend, move: {phase, outcome, names: [{name,
  state}]}, keyRead: Boolean}`; the control plane folds it into `services get` under `secretStore`.
- Switching back (FR-021): the setting is the ConfigMap's, so the refusal is the operator's at
  render time. The control plane learns `Removed` from the observe document (`StatusIngest`),
  records it on the `Service` entity (`SecretsMoved` event, a name and a time, no value) and
  projects it as **desired state**, `AnkkaServiceSpec.secretsRemoved: true` — the control plane
  owns the spec and holds no write on the status. The operator's `Rendering.render` answers
  `Left("secrets of <service> live only in Secret Manager")` for `settings.secretBackend ==
  Postgres && spec.secretsRemoved`, and the service is `Failed` with that detail, naming the
  service. `status.secretStore` carries the provider's phase only.

### 5. Project secrets (FR-015–FR-016b; R9)

- `SecretManagerProjectSecretWriter` (Group A): control plane identity token from the metadata
  server; the fake accepts `fake:controlplane`.
- `AnkkaProject.spec.secrets: Map[name, Vector[entry]]` and `spec.secretsGeneration: Long`,
  projected by `ProjectTopicsTrigger` from the `Project` entity (Group A: the resource field and
  projection; schema and `CrdSchemaSuite`).
- The `secret-sync` request, the hold and the seeding are 044's and Group B.

### 6. Identity and grants (FR-009–FR-012; R3)

- Group A: `DerivedIds` prefixes; the fake's grant rule; the documentation of the roles.
- Group B: the operator renders `secret-access` (`principal` from the `identity` request's output,
  `own: [servicePrefix]`, `read: [projectPrefix]`) and holds `ApplyDeployment` until `Ready` for
  the current generation; status folded as `secretStore.phase = Waiting|Ready|Failed` with the
  provider's detail; the control plane's own grant is the installation's (documented, applied by
  the provider from a `secret-access` the control plane's manifest asks for).

### 7. Local and test (FR-022, FR-023; R10)

- `FakeSecretManager` and `SecretBackend.secretManager(fake)` for the kit; `-Dankka.conformance.secrets`
  forwarded in `Test / javaOptions`.
- No suite reaches a network: the fake binds loopback; `AccessTokens.fixed`.

### 8. Documentation (FR-024)

- `docs/platform/secrets.md`: "Choosing a backend", "Secret Manager", "The read record", "Moving an
  installation" (phases, the check, switching back), the Google prerequisites and the provider's
  power; `docs/build/secrets.md`: the record and the dependency on the record's keeper, the quota
  note; `reference/configuration.md` tables (generated); `control-plane-api.md` and `cli.md`
  (generated rows + hand-written sections); `limitations.md`: no mountable project secret, no
  per-secret kept count, Secret Manager on GKE only, the record not shown in the console.

## Group B: the 044 dependency, stated

| 038 needs | From 044 | Until then |
|---|---|---|
| `secret-access` request with prefixes; `Ready` for the generation | FR-001–FR-005 (amended), FR-018 | the fake enforces the same rule offline; on GKE the grant is made by hand or by `ankka-gcp` |
| `secret-sync` request with entry generation and the seeding clause | FR-005, FR-010 (amended) | project secrets on Secret Manager are written but reach no pod; documented as Group B |
| the hold on `ApplyDeployment` | FR-004 (acknowledgement bound) | no hold; a service deployed before its grant reads `Internal` naming the grant (edge case) |
| the identity principal | `identity` kind output | — |
| Data Access logging status | a provider status | `services get` says `unknown` |
| the control plane's grant | the control plane's own `secret-access` | applied by hand on the first GKE installation |

## Complexity Tracking

| Judgement | Why | Simpler alternative rejected because |
|---|---|---|
| A cloud-specific REST client in `runtime` | the sidecar image must carry the store; the spec's clarified decision | a Google library brings gax/grpc/protobuf 4 into every service and clashes with ScalaPB 3; a contrib module cannot reach the sidecar |
| A second CNPG cluster for the record | 041 backs it up as a store of its own; a per-read event in the journal is forever | a table in the control plane's database is one backup and one restore with the journal, which 041 and the spec both refuse |
| A route a service calls on the control plane | the record must leave the service over its identity, fail-closed | a direct database write gives every service a platform credential; an outbox is the service's to delete |
