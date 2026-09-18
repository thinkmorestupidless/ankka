---
description: "Task list for Platform-Provisioned Databases"
---

# Tasks: Platform-Provisioned Databases

**Input**: Design documents from `specs/002-cnpg-database-provisioning/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/)

**Tests**: Included and not optional, for the same reason as feature 001 — FR-031 (offline-testable
provisioning rules) and FR-038 (verified against a real cluster) require them directly, and the
project's stated testing model is two levels, both real.

**Organization**: Grouped by user story so each is independently testable. As with feature 001,
"independent" means testable and demonstrable on its own, not implementable in complete isolation —
every story extends the same `Rendering`/`Executor`/`LifecycleRules` files feature 001 created.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story the task serves (US1–US4)
- Paths are repository-relative

## Path Conventions

No new sbt modules. All new code lands in the existing `operator` and `controlplane` projects, plus
new kustomize components.

```
operator/src/main/scala/nakka/operator/cnpg/         NEW package — partial CNPG models
operator/src/main/scala/nakka/operator/              MODIFIED — Rendering, Action, Executor, LifecycleRules
controlplane/src/main/scala/nakka/controlplane/deploy/   MODIFIED — ServiceProjection
crd/src/main/scala/nakka/crd/NakkaService.scala      MODIFIED — provisionDatabase, DatabaseStatus
kustomization/components/cnpg/                        NEW — the CNPG operator install
kustomization/components/postgres/                    REPLACED — Deployment+Secret+Service → CNPG Cluster
```

---

## Phase 1: Setup

**Purpose**: Get CNPG's CRDs into the build and the cluster before anything renders against them.

- [X] T001 Create `kustomization/components/cnpg/kustomization.yaml` referencing the pinned CNPG 1.30.0 release manifest by URL (see [contracts/rbac-and-install.md](./contracts/rbac-and-install.md)); add it to `kustomization/overlays/local/kustomization.yaml`'s `components` list, ordered before `operator` and `controlplane`
- [X] T002 [P] Add `cnpg-controller-manager` rollout-wait to `kustomization/deploy-local.sh`, after installing the new component and before applying anything that references a CNPG kind
- [X] T003 [P] `operator/src/main/scala/nakka/operator/cnpg/CnpgDefinitions.scala`: group/version (`postgresql.cnpg.io/v1`) constants and shared serialization config, mirroring `nakka.crd.NakkaServiceDefinition`'s shape

**Checkpoint**: `./kustomization/deploy-local.sh` installs CNPG and waits for it; `kubectl get crd | grep cnpg` shows all eleven CRDs.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The partial CNPG models, the contract additions, and the pure decision/rendering
functions every story depends on. No wiring into the reconcile loop yet.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Partial CNPG models

- [X] T004 [P] `operator/src/main/scala/nakka/operator/cnpg/PostgresCluster.scala`: partial `CustomResource[ClusterSpec, ClusterStatus]` per [contracts/cnpg-resources.md](./contracts/cnpg-resources.md) — `instances`, `storage.size`, optional `bootstrap.initdb`; status exposes `readyInstances`
- [X] T005 [P] `operator/src/main/scala/nakka/operator/cnpg/PostgresDatabase.scala`: partial model — `name`, `owner`, `cluster.name`, `databaseReclaimPolicy`; status exposes `applied`, `message`
- [X] T006 [P] `operator/src/main/scala/nakka/operator/cnpg/PostgresDatabaseRole.scala`: partial model — `name`, `cluster.name`, `login`, `passwordSecret.name`, `databaseRoleReclaimPolicy`; status exposes `applied`, `message`
- [X] T007 [P] `operator/src/test/scala/nakka/operator/cnpg/CnpgModelsSuite.scala`: round-trip test for each of the three models (mirrors `NakkaServiceCodecSuite` from feature 001) — an absent optional field is omitted, not written as null, so server-side apply never claims it

### Contract additions

- [X] T008 [P] Add `provisionDatabase: Boolean = true` to `NakkaServiceSpec` in `crd/src/main/scala/nakka/crd/NakkaService.scala`
- [X] T009 Add `DatabaseStatus` case class and `database: Option[DatabaseStatus]` on `NakkaServiceStatus` in the same file (not parallel with T008 — same file)
- [X] T010 [P] `crd/src/test/scala/nakka/crd/NakkaServiceCodecSuite.scala`: extend for `provisionDatabase` and `DatabaseStatus` round-tripping, including that a status with no `database` field decodes to `None` (compatibility with resources written before this feature)
- [X] T011 [P] Add `database: Option[String] = None` to `ServiceStatus` in `controlplane-api/src/main/scala/nakka/controlplane/api/descriptors.scala`

### The schema, delivered without duplication

- [X] T012 Create the directory symlink `operator/src/main/resources/nakka/ddl` → `../../../../../modules/runtime/src/main/resources/nakka/ddl` (verified working in research R6 — compute the relative path with `python3 -c "import os; print(os.path.relpath(...))"` rather than counting by eye, per the mistake recorded in feature 001's implementation notes)
- [X] T013 `operator/src/test/scala/nakka/operator/SchemaResourceSuite.scala`: assert all three `.sql` files are readable via `getClass.getResourceAsStream("/nakka/ddl/...")`, proving the symlink survives `sbt Compile/copyResources`

### Pure provisioning logic

- [X] T014 `operator/src/main/scala/nakka/operator/Passwords.scala`: `generate(): String` using `SecureRandom`, ≥32 alphanumeric characters (no ambiguous quoting)
- [X] T015 `operator/src/main/scala/nakka/operator/cnpg/DatabaseObservation.scala`: the pure value type — cluster readiness, and the existence/`applied`/`message` of the database, role and credential secret (data-model.md §3)
- [X] T016 `operator/src/main/scala/nakka/operator/Provisioning.scala`: `decide(spec, observed, config): Either[Vector[String], ProvisioningPlan]` — all 10 rules from [contracts/provisioning-rules.md](./contracts/provisioning-rules.md), including the transient-vs-terminal split (rules 7/8) and recovered-vs-provisioned (rules 9/10)
- [X] T017 Extend `operator/src/main/scala/nakka/operator/Action.scala` with `EnsureCluster`, `EnsureCredentials` (create-only-if-absent, documented as the one non-idempotent-by-construction action), `EnsureDatabaseRole`, `EnsureDatabase`, `EnsureSchemaConfig`

### Operator scaffolding for CNPG I/O

- [X] T018 Extend `operator/src/main/scala/nakka/operator/Executor.scala`: interpret the five new `Action` cases against a `KubernetesClient`; `EnsureCredentials` checks existence before writing, everything else is server-side apply with the operator's field manager
- [X] T019 ~~`FakeCnpgTarget.scala`~~ — **not needed, and not a gap.** `FakeDeploymentTarget` never
  existed in feature 001 (checked: only `FakeNakkaServiceClient`, on the control plane side, does).
  `Provisioning.decide(spec, observed: DatabaseObservation)` takes the observation as a plain,
  hand-constructible value rather than through an interface — the same shape `LifecycleRules.observe`
  already uses for `ClusterSnapshot`. `ProvisioningSuite` constructs `DatabaseObservation` values
  directly; a fake would be an indirection with nothing behind it.

**Checkpoint**: The contract, the models, and the pure decision logic exist. Nothing is wired into a reconcile pass yet.

---

## Phase 3: User Story 1 - A deployed service gets its own database (Priority: P1) 🎯 MVP

**Goal**: A descriptor with no database configuration produces a service running against its own,
newly provisioned database.

**Independent Test**: Apply such a descriptor against a cluster with CNPG installed; the service
reaches `Ready`; restarting it recovers a previously persisted event.

### Tests for User Story 1

- [X] T020 [P] [US1] `operator/src/test/scala/nakka/operator/ProvisioningSuite.scala`: rules 1–6, 9, 10 from the contract — no cluster yet, cluster not ready, secret absent, role/database absent, steady state decides `NothingToDo`, and idempotence (`NothingToDo` performs zero writes against the fake)
- [X] T021 [P] [US1] `operator/src/test/scala/nakka/operator/CnpgRenderingSuite.scala`: `Cluster`/`Database`/`DatabaseRole` render with exactly the fields in [contracts/cnpg-resources.md](./contracts/cnpg-resources.md); rendering is deterministic; a hyphenated service name (e.g. `my-cart`) renders unchanged in every name field (research R10 — no normalisation)

### Implementation for User Story 1

- [X] T022 [US1] `operator/src/main/scala/nakka/operator/CnpgRendering.scala`: pure functions building a `PostgresCluster`, `PostgresDatabase`, `PostgresDatabaseRole` and credential `Secret` from a `NakkaServiceSpec` and project config, per [contracts/cnpg-resources.md](./contracts/cnpg-resources.md) — `retain` reclaim policies on both CNPG objects (FR-024, forward reference to US4 but cheap to set now)
- [X] T023 [US1] Wire `Provisioning.decide` + `CnpgRendering` into `operator/src/main/scala/nakka/operator/Rendering.scala`: on the provisioned path, emit the CNPG actions ahead of the Deployment action; the Deployment gets `envFrom` the credential secret
- [X] T024 [US1] Extend `operator/src/main/scala/nakka/operator/LifecycleRules.scala`: a new `Waiting`-for-database status distinct from the existing rollout-in-progress state, reported when `Provisioning.decide` returns a plan that is not yet `NothingToDo`
- [X] T025 [US1] Extend `operator/src/main/scala/nakka/operator/ServiceReconciler.scala` (or equivalent reconcile entry point) to read `DatabaseObservation` before rendering, and to feed `Provisioning.decide`'s result into both the rendered actions and the reported `DatabaseStatus`
- [X] T026 [US1] `controlplane/src/main/scala/nakka/controlplane/deploy/ServiceProjection.scala`: set `provisionDatabase = true` by default (the escape-hatch rule itself is US4's job; this task only wires the field through)

### Integration and cluster tests for User Story 1

- [X] T027 [US1] `operator/src/test/scala/nakka/operator/OperatorClusterSuite.scala`: extend `beforeAll` to install CNPG (from the shipped manifest, per T001) and wait for its controller; add a case asserting a project's `Cluster` is created lazily on first service and reaches ready
- [X] T028 [US1] Extend `OperatorClusterSuite`: a service with no database configuration reaches `Ready`; assert its `Database` and `DatabaseRole` exist and `psql` as that role against that database succeeds
- [X] T029 [US1] Extend `OperatorClusterSuite`: **the transient window is survived, not reported as failure** — poll status throughout provisioning and assert `Failed` is never observed, even while CNPG's per-cluster secret allowlist has not caught up (research R5) or the role has not yet landed (research R4)
- [X] T030 [US1] `controlplane/src/test/scala/nakka/controlplane/EndToEndClusterSuite.scala`: extend to install CNPG; the headline case — a descriptor with zero database configuration deploys through the real CLI and reaches `Ready`

**Checkpoint**: A descriptor with no database configuration produces a running service on its own database. This is the MVP.

---

## Phase 4: User Story 2 - One service cannot damage another's data (Priority: P1)

**Goal**: Two services in one project each get an isolated database — including that neither can
`CONNECT` to the other's, which Postgres does not refuse by default.

**Independent Test**: Deploy two services in one project; attempt cross-service access with each
one's credentials and confirm it is refused, including a bare `CONNECT` attempt.

### Tests for User Story 2

- [X] T031 [P] [US2] `operator/src/test/scala/nakka/operator/SchemaInitSuite.scala`: the init container is rendered on the provisioned path with the three steps from [contracts/schema-init.md](./contracts/schema-init.md) — wait, apply schema, `REVOKE CONNECT ON DATABASE ... FROM PUBLIC` — and is **not** rendered on the escape-hatch path (forward reference to US4's flag, testable now since the field already exists from T008)

### Implementation for User Story 2

- [X] T032 [US2] `operator/src/main/scala/nakka/operator/SchemaInit.scala`: pure function rendering the init container spec (image, `envFrom` the credential secret, volume mount, the shell script from the contract) from a `NakkaServiceSpec`
- [X] T033 [US2] `operator/src/main/scala/nakka/operator/CnpgRendering.scala`: render the `nakka-schema` ConfigMap per project namespace, sourced from the classpath resources at `/nakka/ddl/*.sql` (via T012's symlink) — one ConfigMap shared by every service in the project, since the schema does not vary by service
- [X] T034 [US2] Wire the init container and its ConfigMap volume into the Deployment rendering in `operator/src/main/scala/nakka/operator/Rendering.scala`, ordered before the service's own container; ensure the pod template's checksum/annotation changes when the schema ConfigMap's content changes, so a schema update actually restarts pods (called out explicitly in [contracts/schema-init.md](./contracts/schema-init.md) as the easy thing to miss)

### Integration and cluster tests for User Story 2

- [X] T035 [US2] Extend `OperatorClusterSuite`: deploy two services in one project; assert each has its own `Database`/`DatabaseRole`/credential secret, and that the tables one creates are invisible to the other via `SELECT`
- [X] T036 [US2] Extend `OperatorClusterSuite`: **the CONNECT grant is measured, not assumed** — using service A's generated credentials, attempt to connect to service B's database and assert it is refused (`permission denied for database ... User does not have CONNECT privilege`), proving the `REVOKE` step in T032 actually ran
- [X] T037 [US2] Extend `OperatorClusterSuite`: both services' timer tables exist independently and surviving each other's presence is provable at the schema level (assert `nakka_timers` exists in each service's own database, distinct rows, not a shared table) — this is the concrete case that motivated the whole feature (SC-005)

**Checkpoint**: Two services in one project are provably isolated, including the CONNECT grant most implementations would miss.

---

## Phase 5: User Story 3 - The control plane runs on a managed database (Priority: P2)

**Goal**: The control plane's own Postgres becomes a CNPG `Cluster` with durable storage and an
auto-generated credential secret, replacing the hand-rolled Deployment from feature 001.

**Independent Test**: Deploy the platform from scratch; the control plane reaches `Ready` against a
CNPG-managed database; restarting that database's pod does not lose recorded desired state.

### Tests for User Story 3

- [X] T038 [P] [US3] `operator/src/test/scala/nakka/operator/cnpg/PostgresClusterBootstrapSuite.scala` (or fold into `CnpgModelsSuite`): rendering a `Cluster` with `bootstrap.initdb.database`/`.owner` set produces the expected object shape, distinct from a project cluster (which omits `bootstrap` entirely)
  - Folded into `CnpgRenderingSuite` (`CnpgModelsSuite` already covered the raw `ClusterSpec` shape) as "the control plane cluster carries bootstrap.initdb, unlike a project cluster", exercising `CnpgRendering.controlPlaneCluster` directly.

### Implementation for User Story 3

- [X] T039 [US3] Replace `kustomization/components/postgres/deployment.yaml`, `service.yaml` and `secret.yaml` with a single `cluster.yaml`: a CNPG `Cluster` named e.g. `nakka-controlplane-db` in the `nakka-controlplane` namespace, `bootstrap.initdb.database: nakka`, `.owner: nakka`, `storage.size` per [contracts/cnpg-resources.md](./contracts/cnpg-resources.md)
- [X] T040 [US3] Update `kustomization/components/postgres/kustomization.yaml` to reference `cluster.yaml` in place of the removed manifests
- [X] T041 [US3] Update `kustomization/components/controlplane/deployment.yaml`: map the CNPG-generated `{cluster}-app` secret's keys to `NAKKA_DB_*` one-for-one per the table in [contracts/credentials.md](./contracts/credentials.md) (`host`→`NAKKA_DB_HOST`, `port`→`NAKKA_DB_PORT`, `dbname`→`NAKKA_DB_NAME`, `username`→`NAKKA_DB_USER`, `password`→`NAKKA_DB_PASSWORD`) — replacing the old `nakka-postgres-credentials` secret reference
- [X] T042 [US3] Update the `wait-for-postgres` init container already present on the control plane's Deployment (from feature 001) to target the new CNPG service name (`{cluster}-rw`) instead of the old hand-rolled `postgres` Service
  - Targets the CNPG-generated secret's own `host`/`port`/`username` keys rather than a hardcoded service name — equivalent, and one less name to keep in sync.
- [X] T043 [US3] Update `kustomization/deploy-local.sh`: remove the old `nakka-postgres-init` ConfigMap generation step (the DDL is now applied by the control plane's existing runtime startup path, not a Postgres `docker-entrypoint-initdb.d` mount — CNPG's image does not support that mount point the way the old hand-rolled image did) and add a wait for the control plane's `Cluster` to report `readyInstances: 1` before waiting on the control plane Deployment itself
  - Deviation from the task's assumed approach: the DDL is applied via CNPG's `bootstrap.initdb.postInitApplicationSQLRefs`, referencing a regenerated `nakka-controlplane-schema` ConfigMap (same single-copy DDL, plus one generated `99-grants.sql` key — see the note in `cluster.yaml`), not by the control plane's own runtime startup path. Found empirically: CNPG runs `postInitApplicationSQLRefs` as the `postgres` superuser, so without the trailing GRANT the `nakka` role could create its own view tables but not touch the DDL's own tables — every write timed out with "permission denied for table projection_management". Verified end-to-end against the real kind cluster: `organizations create`/`list` through the CLI, and data survives deleting the database pod.

### Integration and cluster tests for User Story 3

- [X] T044 [US3] Extend `EndToEndClusterSuite`: the control plane's own database is a CNPG `Cluster`; assert it exists and is ready before the control plane's own readiness is asserted
- [X] T045 [US3] Extend `EndToEndClusterSuite`: apply several services, delete the control plane database's pod (not the `Cluster`), wait for CNPG to recreate it, and assert every previously-applied service is still listed — the recorded desired state survived (FR-019, SC-007)
  - Not literally addable to `EndToEndClusterSuite`: that suite runs the control plane in-process via `NakkaTestKit`, against `NakkaTestKit`'s own testcontainers Postgres — deliberately decoupled from the k3s container's CNPG install, unchanged by this feature (feature 001's existing split). The control plane there never talks to a CNPG `Cluster` at all, so there is nothing to assert or delete. Making it do so would mean deploying the control plane itself as a k3s workload inside the test, duplicating what `deploy-local.sh` + the kustomize manifests already do.
  - Verified the underlying capability directly against the real `kind-nakka` cluster instead: recreated `nakka-controlplane-db` from scratch (proving bootstrap + the T043 grants fix together), created an organization through the CLI (`organizations create acme` → succeeded, `organizations list` → returned it), then deleted the database pod (not the `Cluster`) and confirmed `organizations list` still returned the same data once CNPG recreated the pod — the PVC-backed durability FR-019/SC-007 describe, proven end-to-end.

**Checkpoint**: The control plane runs on durable, CNPG-managed storage. Restarting its database pod no longer loses anything.

---

## Phase 6: User Story 4 - Credentials are generated, never authored (Priority: P2)

**Goal**: No password is ever written by a person; the escape hatch for a caller-supplied database
is explicit, tested, and honestly documented as not enforcing isolation.

**Independent Test**: Deploy a service and grep every descriptor, manifest and repository file for
its password — it appears in none of them. Deploy a second service with its own `NAKKA_DB_*` env
vars and confirm the platform provisions nothing for it.

### Tests for User Story 4

- [X] T046 [P] [US4] Extend `ProvisioningSuite`: rule 1 (escape hatch) — `provisionDatabase = false` decides `Supplied` and renders no CNPG actions, regardless of what `DatabaseObservation` contains
  - Already present from earlier implementation work: "rule 1: the escape hatch decides Supplied regardless of what is observed".
- [X] T047 [P] [US4] Extend `SchemaInitSuite`: no init container and no schema ConfigMap are rendered on the `Supplied` path
  - Already present: "Rendering: the escape hatch renders no init container and no database envFrom".
- [X] T048 [P] [US4] `controlplane/src/test/scala/nakka/controlplane/ServiceProjectionSuite.scala`: `provisionDatabase` is `false` exactly when the descriptor's `env` declares any variable whose name starts `NAKKA_DB_`, `true` otherwise, including the boundary case of a descriptor declaring an unrelated env var

### Implementation for User Story 4

- [X] T049 [US4] `controlplane/src/main/scala/nakka/controlplane/deploy/ServiceProjection.scala`: implement the escape-hatch rule from [research R11](./research.md) — inspect the descriptor's `env` names, not values, and set `provisionDatabase` accordingly
- [X] T050 [US4] `controlplane-api/src/main/scala/nakka/controlplane/api/descriptors.scala` and `cli/src/main/scala/nakka/cli/Output.scala`: surface `ServiceStatus.database` (from T011) in `nakka services get`, one short phrase per phase (`provisioned`, `supplied`, `recovered existing data`, `waiting for database`)
  - Threaded the operator's reported phase end to end: `nakka.crd.DatabaseStatus.phase` → `StatusIngest` → `ServiceObservation`/`ServiceEvent.ServiceObserved` → `Service.database` → `Service.toStatus` (via `Service.databasePhrase`) → `ServiceStatus.database` → `Output.service`'s field list.
- [X] T051 [US4] Ensure `Provisioning.decide`'s reported phase (T016) and `LifecycleRules`' `DatabaseStatus` (T024) agree on wording for `Supplied`, so the CR's status and the CLI's phrase are the same concept surfaced twice, not two independently-invented vocabularies
  - Already held by construction: `LifecycleRules.databaseStatus` sets `phase = plan.reportedPhase` directly, never restating it — see the comment at `LifecycleRules.scala:102`. `Service.databasePhrase` (T050) is a pure lookup keyed on exactly those tokens, so the CLI's phrase can only reword the CR's phase, never disagree with it.

### Integration and cluster tests for User Story 4

- [X] T052 [US4] Extend `EndToEndClusterSuite`: a descriptor carrying `NAKKA_DB_*` env vars deploys successfully, provisions no CNPG objects (assert none exist), and `nakka services get` reports the supplied phase
- [X] T053 [US4] Extend `EndToEndClusterSuite` or add a dedicated case: grep the deployed service's rendered Deployment, its `NakkaService` status, and the operator's logs for the literal generated password value — assert it appears in none of them (SC-006)
  - The operator runs in-process in this suite, so "its logs" are captured with a logback `ListAppender` attached to the root logger for the suite's lifetime, rather than grepped from a separate container's stdout. Verified against a real k3s cluster.

**Checkpoint**: Both provisioning paths exist, are tested, and are honestly distinguished in every place an operator looks.

---

## Phase 7: User Story 5 - Nothing is ever destroyed (Cross-cutting: FR-023–FR-027)

**Note**: `spec.md` does not number this as a prioritised user journey — FR-023 through FR-027 and
SC-014/SC-015 are cross-cutting guarantees that apply to every story above, not a distinct feature
a user asks for. It is given a story label and its own phase here because the checklist format
requires one for any phase with independent tests and a checkpoint, and because "nothing destroys
a database" needs its own cluster verification distinct from any single story's happy path.

**Goal**: Deleting a service or a project never destroys a database. Re-applying a previously
deleted service's name recovers its data, visibly.

**Independent Test**: Delete a service with committed data, confirm its database still exists,
re-apply the same name, confirm the data is back and the status says so.

### Implementation

- [X] T054 [P] [US5] Update `kustomization/components/operator/operator.yaml`: grant the operator's ClusterRole `get, list, watch, create, patch` on `clusters.postgresql.cnpg.io`, `databases.postgresql.cnpg.io`, `databaseroles.postgresql.cnpg.io` — **no `delete`**; grant `get, create, patch` on `secrets` — **no `list`, no `delete`** (per [contracts/rbac-and-install.md](./contracts/rbac-and-install.md))
- [X] T055 [US5] Set `databaseReclaimPolicy: retain` and `databaseRoleReclaimPolicy: retain` on the rendered `Database` and `DatabaseRole` in `CnpgRendering.scala` (confirm this was actually done in T022; this task is the explicit check-and-fix if it was deferred)
- [X] T056 [US5] Extend `Provisioning.decide` (rule 9 from the contract): detect and report `Recovered` when a service is re-applied and its `Database` already existed before this reconcile pass created its credential secret

### Tests

- [X] T057 [P] [US5] Extend `ProvisioningSuite`: rule 9 vs. rule 10 — a service whose database already existed decides `Recovered`, not `Provisioned`
- [X] T058 [US5] Extend `OperatorClusterSuite`: delete a service after writing data to its database; assert the `Database` and its data survive; re-apply the same service name; assert the data is back and the reported `DatabaseStatus.recovered` is `true`
  - Test 19: writes a marker row via `psqlAs`, deletes the `with-db-2` `NakkaService`, confirms the `Database` and the row both survive, re-applies the same name, and asserts `recovered == true` and the row is unchanged. Verified against a real k3s cluster.
- [X] T059 [US5] Extend `OperatorClusterSuite`: **the withheld verb is structural, not just unused** — using the operator's own ServiceAccount, attempt to `kubectl delete database` directly and assert it is refused by RBAC, not merely never called by nakka's own code (mirrors feature 001's equivalent test for `nakkaservices: update`)
  - Feature 001 never actually built this — its own tasks.md records it as a known, deferred coverage gap ("closing it properly means standing up a client bound to the ServiceAccount + RBAC inside the test itself"). Built it here: test 20 applies the RBAC objects from the shipped `operator.yaml` (extracted by kind, not the Deployment), mints a real token for `nakka-operator`'s ServiceAccount via `kubectl create token` (exec'd in the k3s container), builds a second `KubernetesClient` authenticated with only that token, and asserts a `Database` delete attempt gets a 403 from the API server itself. Verified against a real k3s cluster.
- [X] T060 [US5] Extend `EndToEndClusterSuite`: delete a project's only service, confirm the project's `Cluster` and the service's `Database` both remain (FR-024), consistent with "retain always" — no automatic cleanup exists anywhere in this feature

**Checkpoint**: No sequence of platform operations can destroy a database. Verified by trying, not by absence of a delete button.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: The documentation this feature invalidates or extends, and the checks that keep the
design honest.

- [X] T061 [P] Correct `CLAUDE.md`'s claim that the operator has no RBAC verb on `secrets` — state precisely what changed (`get`, `create`, `patch` added; `list` and `delete` still withheld) and why, per [contracts/rbac-and-install.md](./contracts/rbac-and-install.md)'s explicit "still true / no longer true / unchanged in practice" breakdown
  - The top-level `CLAUDE.md` itself never carried this claim; it lived in `kustomization/components/operator/operator.yaml`'s own RBAC comment (verified by grep before editing either), and in two Scala doc comments (`LifecycleRules.problemDetail`, `crd.EnvEntry`) fixed earlier during implementation. Rewrote the `operator.yaml` comment with the same still/no-longer/unchanged breakdown as the contract.
- [X] T062 [P] Rewrite `README.md`'s "Databases are yours to provide" gap: provisioning now exists by default; the bring-your-own escape hatch remains for an explicit reason; state plainly that one-database-per-service is enforced on the provisioned path only (FR-019)
- [X] T063 [P] Remove `README.md`'s note (if present) about the control plane's Postgres using `emptyDir` — it is a real PVC via CNPG now
  - No such note existed (verified by grep); nothing to remove.
- [X] T064 [P] Add the CNPG install step and the new `Cluster`/`Database`/`DatabaseRole` kinds to `CLAUDE.md`'s "Traps that have already cost debugging time", specifically: the transient `forbidden` secret-allowlist window (research R5), the role-must-exist-before-database ordering (research R4), and that `Database`/`DatabaseRole` cannot reference a `Cluster` outside their own namespace with no error surfaced when they do (research R2)
  - Also added the `postInitApplicationSQLRefs`-runs-as-superuser trap (T043's real bug) and corrected the stale "k3s suites never exercise the shipped RBAC at all" line now that T059 built one that does.
- [X] T065 [P] Update `CLAUDE.md`'s `sbt test` comment and `kustomization/deploy-local.sh`'s printed walkthrough to mention CNPG installation and the longer cluster-suite runtime (research: CNPG controller ~25s, a 1-instance `Cluster` ~20–60s more)
  - `deploy-local.sh` already echoes `"==> installing CloudNativePG"` as its own step (added during core implementation), so the walkthrough a developer sees while running it already mentions this; the final "Deployed. Try:" block needed no change since CNPG has already finished by the time it prints. Rewrote CLAUDE.md's `sbt test` comment and the "Deploying locally" section, both of which predated CNPG.
- [X] T066 Tag or otherwise ensure the CNPG-dependent additions to both cluster suites remain behind the existing `-Dnakka.cluster.tests=off` switch from feature 001 — no new switch needed, just confirm the extended `beforeAll` blocks still respect `munitIgnore`
  - Confirmed: both `OperatorClusterSuite` and `EndToEndClusterSuite` guard their entire `beforeAll` (CRD, CNPG install, operator/control-plane startup) behind `if !munitIgnore then ...`, with `munitIgnore` reading the same `-Dnakka.cluster.tests` property feature 001 introduced.
- [X] T067 Run `sbt scalafmtAll scalafmtSbt` and confirm `sbt compile` is warning-free under `-Wunused`
- [X] T068 Confirm the seam holds: `grep -rl "postgresql.cnpg.io" controlplane/src cli/src crd/src` returns nothing — CNPG is the operator's business alone
- [X] T069 Confirm `crd` still depends on no nakka module and `cli` still depends on `controlplane-api` alone (unchanged by this feature, but worth the same explicit check feature 001 made a habit of)
- [X] T070 Work through the reviewer's checklist in [quickstart.md](./quickstart.md) in full, including the Tier 5 manual walkthrough and the `\dt` check that the schema actually landed in a service's database
  - Tiers 1-4: ran every named suite directly, plus a full `sbt test` across all modules (0 failed, 0 errors). Tier 1 confirmed to need no Docker (sub-second runs).
  - Tier 5: ran `./kustomization/deploy-local.sh` from a clean `nakka-controlplane-db`/`nakka-db` state on the real `kind-nakka` cluster (not incremental patches) — CNPG install, both images, the CRD, the control plane's own database, and the operator all came up in one pass. Created an org and project and applied a `cart` service with no database configuration through the real CLI: reached `Ready` with no `Failed` flicker, `status.database.phase == "Provisioned"`, and `\dt` against `cart`'s own database showed all 7 DDL tables owned by `cart` (the per-service path was never subject to the control-plane-only superuser-ownership bug, confirmed rather than assumed). Reused the project's existing `Cluster` correctly alongside two services from earlier manual debugging.
  - Reviewer's checklist bullets (`sbt compile`/scalafmt clean, the `postgresql.cnpg.io` seam grep, `crd`/`cli` dependencies, the DDL symlink, `docker-compose.yml` untouched, the operator's ClusterRole verbs, `CLAUDE.md` and `README.md` corrected): each individually verified while completing T061-T069 above.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup — **blocks every user story**
- **User Stories (Phases 3–7)**: all depend on Foundational; US1 first, since it is the MVP and
  every later story extends the files it creates
- **Polish (Phase 8)**: depends on the stories being delivered

### User Story Dependencies

- **US1 (P1)**: after Foundational. Creates `CnpgRendering.scala`, wires `Provisioning` into
  `Rendering`/`LifecycleRules`/`ServiceReconciler` for the first time. Every later story edits
  these same files.
- **US2 (P1)**: after US1. The init container's credential `envFrom` and the `Cluster`/`Database`
  it waits for do not exist until US1 lands. Genuinely sequential, not just conventionally ordered.
- **US3 (P2)**: after Foundational only — it touches kustomize manifests and the control plane's
  static Deployment, none of which US1/US2 change. Could run in parallel with US1/US2 by a second
  person, and is the one story in this feature that is actually independent of the others.
- **US4 (P2)**: after US1 (needs `Provisioning.decide` and the CR field to exist) and benefits from
  US2 existing (so the "no init container on this path" assertion has an init container to be
  absent). Practically sequential after US1, safely built alongside US2.
- **US5 / Phase 7 (retain/recover)**: after US1 (needs provisioning to exist before "don't destroy
  it" means anything) and after US4 (rule 9's `Recovered` phase is defined relative to the escape
  hatch existing as rule 1). Last of the story phases for exactly that reason.

**Honest summary, as in feature 001**: these are independently testable and demonstrable, not
independently implementable. US3 is the exception — it is genuinely parallel-safe with the others.

### Within Each Story

- Pure unit tests alongside the pure functions they cover
- Pure logic (`Provisioning`, `CnpgRendering`, `SchemaInit`) before the reconcile wiring that calls it
- Wiring before integration tests
- Integration tests (fake) before cluster tests (k3s + CNPG)

### Parallel Opportunities

- Setup: T002, T003
- Foundational: T004/T005/T006 (three CNPG models, different files); T007 alongside them once all
  three exist; T008/T011 (different files/modules); T010 once T008/T009 land; T012/T013 independent
  of the model tasks; T014/T015/T019 independent of each other
- US1: T020/T021 (different test files)
- US2: T031 alone (single new test file)
- US3: T038 alone; T039–T043 are a sequential chain within one deploy path but the whole story can
  run in parallel with US1/US2/US4 by a second contributor
- US4: T046/T047/T048 (three different test files)
- US5: T054 alone; T057 alongside T056
- Polish: T061/T062/T063/T064/T065 (five different documentation files)

---

## Parallel Example: Phase 2 Foundational

```bash
# Three CNPG models, three files:
Task: "T004 PostgresCluster.scala"
Task: "T005 PostgresDatabase.scala"
Task: "T006 PostgresDatabaseRole.scala"

# Contract additions, different modules:
Task: "T008 provisionDatabase on NakkaServiceSpec (crd)"
Task: "T011 database field on ServiceStatus (controlplane-api)"

# Independent of the models and of each other:
Task: "T012 the DDL symlink"
Task: "T014 Passwords.scala"
Task: "T015 DatabaseObservation.scala"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup — CNPG installs and is waited for
2. Phase 2 Foundational — contract, models, pure provisioning logic
3. Phase 3 US1 — a descriptor with no database configuration produces a running, provisioned service
4. **STOP and VALIDATE**: `sbt 'controlPlane/testOnly *EndToEndClusterSuite'`. Note this is the MVP
   for *this* feature, not for the platform — until US2 lands, two services in one project share
   isolation only at the table level, and CONNECT is still open between them. Ship US1 alone only
   as an internal milestone, not as "database provisioning is done".

### Incremental Delivery

1. Setup + Foundational → the contract and pure logic exist
2. + US1 → a service gets its own database (MVP milestone, not a safe stopping point — see above)
3. + US2 → **the actual safe stopping point**: isolation is real, including CONNECT
4. + US3 → the control plane's own data survives a database restart (parallelisable with 2–3)
5. + US4 → the escape hatch exists and is honestly distinguished
6. + US5 → nothing can ever be destroyed, verified by trying

### Sequencing Risks

- **Do not treat US1's checkpoint as shippable on its own.** Unlike feature 001, where each story
  was a genuine incremental improvement, US1 without US2 automates the creation of exactly the
  shared-database exposure this feature exists to close (open `CONNECT` between services). The
  spec's own P1/P1 pairing (not P1/P2) says this explicitly; the task breakdown preserves it by
  making US2 come immediately after US1, before US3 or US4.
- **T029 and T036 are the sleepers.** Skipping "assert `Failed` is never observed during the
  transient window" is easy and the bug it guards is the single most misleading failure mode this
  feature can produce — a permanent-looking authorization error on every first deploy that fixes
  itself in under a minute. Skipping the CONNECT-refusal assertion in T036 is easy and the property
  it guards (isolation) is the entire second half of this feature's purpose.
- **US3's kustomize changes delete files** (`postgres/deployment.yaml`, `service.yaml`,
  `secret.yaml`). Do this as one clean commit, not an incremental edit, so a partial apply never
  leaves both the old hand-rolled Postgres and a new CNPG `Cluster` competing for the same
  Deployment name.

---

## Notes

- `[P]` means different files with no incomplete dependencies
- Every task names its file path or manifest; no task should require reading another to know where the code goes
- Commit after each task or logical group
- Stop at any checkpoint to validate the story independently
- The cluster suites now install CNPG in addition to k3s itself; both need Docker and the network
  reach to fetch CNPG's manifest (the one component in this feature not vendored, per
  [contracts/rbac-and-install.md](./contracts/rbac-and-install.md))
