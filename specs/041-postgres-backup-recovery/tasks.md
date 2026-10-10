# Tasks: Backup and Recovery — Point-in-Time Restore for Every Database the Platform Provisions

**Input**: Design documents from `/specs/041-postgres-backup-recovery/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: included, and first. This repository's rule is that each acceptance scenario ends as a
test that fails without the feature, and the plan's "verify first" list turns each fact read from
documentation into a spike or a test before the code that relies on it. The scenarios are in
`features/databases/` (six files) and `features/object-storage/durability.feature`; where a task
says "case", it means a `test(...)` in the named suite, named for the scenario or the rule it holds.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1 (every project database is archived, and a failure is seen), US2 (an owner
  restores a project to a moment and switches a service), US3 (a restore says what it could not
  rewind, and re-publication is safe), US4 (a project asks for replicas and survives losing its
  primary), US5 (a restore is rehearsed without touching the project), US6 (the control plane's
  database is restored), US7 (objects on Garage survive the loss of a node)

Paths are repository-relative. Abbreviations, each followed by
`…/com/thinkmorestupidless/ankka/<module>` where it is a Scala tree: `CRD`/`CRDT` =
`crd/src/{main,test}/scala/…/crd`; `CORE` = `modules/core/src/main/scala/…/core`; `RT`/`RTT` =
`modules/runtime/src/{main,test}/scala/…/runtime`; `TK`/`TKT` =
`modules/testkit/src/{main,test}/scala/…/testkit`; `TEL` = `modules/telemetry-otlp/src/main/scala/…/telemetry`;
`API`/`APIT` = `controlplane-api/src/{main,test}/scala/…/controlplane/api`; `CP`/`CPT` =
`controlplane/src/{main,test}/scala/…/controlplane`; `OP`/`OPT` =
`operator/src/{main,test}/scala/…/operator`; `CLI`/`CLIT` = `cli/src/{main,test}/scala/…/cli`;
`KUST` = `kustomization`; `DDL` = `kustomization/components/postgres/ddl`; `CON` = `console/package`;
`DOCS` = `docs`; `FEAT` = `features/databases`. "R*n*" is a section of `research.md`; "S*n*" a row
of its *Verify first, gathered* table; a contract is named by its file under `contracts/`.

The branch `041-postgres-backup-recovery-impl` exists, in the worktree
`.claude/worktrees/041-postgres-backup-recovery-impl`. Work there. Every `sbt` command below takes
`-Dankka.cluster.tests=off` unless the task names a k3s suite or a spike; a k3s run belongs under
`caffeinate -i`, and the user runs them through the `cluster` workflow in CI
(`gh workflow run cluster --ref <branch> -f suite=<Name>`). sbt's project ids are `crd`, `core`,
`runtime`, `testkit`, `telemetryOtlp`, `controlPlaneApi`, `controlPlane`, `operator`, `cli`.

---

## Phase 1: Setup — the plugin installs, and the four facts the rendering rests on are shown true

**Purpose**: R1, R7, R11, R12 and R14 each rest on a fact read from documentation. A spike is a
throwaway suite that runs only under `-Dankka.spikes=on`, as `GatewayGrpcSpike` does; each ends by
writing what it found under a new heading, *Verified during implementation*, at the end of
`research.md`. If one is false, stop and change the decision it holds before going on.

- [X] T001 Create `KUST/components/cnpg-barman/kustomization.yaml` (a `Component`) whose one resource is `https://github.com/cloudnative-pg/plugin-barman-cloud/releases/download/v0.15.1/manifest.yaml`, with a comment saying why it is pinned, as `KUST/components/cnpg/kustomization.yaml` does for CNPG. List it in `KUST/overlays/local/kustomization.yaml` and `KUST/overlays/cloud/kustomization.yaml` after `cnpg`. Add to `KUST/deploy-local.sh` a wait for `deploy/barman-cloud` in `cnpg-system` beside the CNPG wait. Confirm `kubectl kustomize KUST/overlays/local` renders.
- [X] T002 Write `OPT/BackupStack.scala`: `install(k3s, client)` applies CNPG 1.30.0 as `OperatorClusterSuite` does, then the plugin manifest URL of T001 with the node's `kubectl`, then waits for `cnpg-system/barman-cloud` rolled out and the `objectstores.barmancloud.cnpg.io` CRD established. Share it with `controlPlane`'s tests as `ObjectStoreStack` is (`test->test`). Also `backupSettings(target = "object-store", retentionDays = 30, …)` returning the operator `Settings` and a map for the control plane's config.
- [X] T003 Spike S1 and S2 in `OPT/BackupSpike.scala` on k3s with `BackupStack`, `PkiStack` and `ObjectStoreStack`: create a project namespace, the project authority and a `Cluster ankka-db` exactly as `CnpgRendering.projectCluster` renders it, wait for it healthy and record the pod's `status.startTime`. Create a bucket `platform.backups.spike` and a key by the Garage admin API, the Secret `ankka-db-backups` (`ACCESS_KEY_ID`, `ACCESS_SECRET_KEY`, `REGION`), an `ObjectStore ankka-backups` per R7, then server-side apply the cluster again with `plugins` and `postgresql.parameters.archive_timeout: "60s"`. Assert: the pod's `startTime` is unchanged after the cluster is healthy again (S1); a `Backup` with `method: plugin` completes and `ObjectStore.status.serverRecoveryWindow["ankka-db"].lastSuccessfulBackupTime` is set; `client.pods().inNamespace(ns).withName("ankka-db-1").inContainer("postgres").exec("psql","-U","postgres","-tAc","select 1")` answers `1` (S2); and a pod with `envFrom` a Secret holding `ANKKA_DB_HOST=a` and `env` `ANKKA_DB_HOST=b` reports `b` from inside (S2). Record whether the Garage network policy had to admit `cnpg.io/cluster` pods (R6).
- [X] T004 Spike S3 in `OPT/BackupSpike.scala`: with two `Database`s and `DatabaseRole`s in the spike's cluster and a row written in each, note the time, write another row in each, then create `Cluster ankka-db-rspike` with `bootstrap.recovery` per R12 (`database: postgres`, `owner: postgres`, no `secret`, `recoveryTarget.targetTime` at the noted time in UTC). Assert: it reaches `Cluster in healthy state`; `select datname from pg_database` lists both databases and no `app`; `\du` lists both roles and `ankka_tls`; each database holds the first row and not the second; a `targetTime` after the archive's last commit leaves the cluster failed with a `phaseReason` naming recovery. Record the exact `targetTime` format accepted and how long recovery took.

**Checkpoint**: four findings are written into `research.md`. Nothing else has changed.

---

## Phase 2: Foundational — names, fields, settings, the DDL, the models, the reads

**Purpose**: what every story reads. Nothing here archives anything yet.

**⚠️ CRITICAL**: a field on `AnkkaProjectSpec`, `AnkkaProjectStatus` or `AnkkaServiceSpec` that its yaml does not declare passes every offline suite and is refused by a real API server on every projection. T008 extends the one suite that sees it; show it failing before trusting it.

### Names

- [X] T005 [P] Write `CRDT/BackupNamesSuite.scala` per R4, red: `Buckets.backup("shop") == "platform.backups.shop"`; `Buckets.platformBackup("controlplane") == "platform.backups-controlplane"`; `Buckets.backupProblems("x" * 47)` has one problem naming 46 and `("x" * 46)` none; `Names.restoreName(Instant)` is `ankka-db-r202610081012` and `Names.rehearsalName` is `ankka-db-x…`; `Names.rehearsalNamespace("shop") == "ankka-shop-rehearsal"`; `Names.problems("shop-rehearsal")` has one problem. Add to `APIT/ProjectIdsSuite` (or the suite that holds `ProjectIds.problems`) that `shop-rehearsal` and a 47-character id are refused naming the reason.
- [X] T006 Add `backup`, `platformBackup`, `backupProblems`, `BackupSecret = "ankka-db-backups"` to `CRD/Buckets.scala`; `restoreName`, `rehearsalName`, `rehearsalNamespace`, the `-rehearsal` rule to `CRD/Names.scala`; the two refusals to `ProjectIds.problems` in `API/descriptors.scala`. `crd` must still depend on nothing of ankka's. T005 green.

### The resources

- [X] T007 [P] In `CRDT/AnkkaProjectCodecSuite.scala` (create it beside the service's) add, red: a project resource with no `database`, `backups`, `restores` or `rehearsals` decodes with the defaults in `data-model.md`; each round-trips; a status with every block round-trips and an empty one is omitted, not null. In `CRDT/AnkkaServiceCodecSuite.scala`: `databaseCluster` absent decodes `None` and is omitted. In `OPT/CrdSchemaSuite.scala` add cases comparing `spec.properties.{database,backups,restores,rehearsals}` and every status block (`backups.lines`, `database`, `clusters`, `restores`, `rehearsals`, each item's properties) with the case classes **in both directions**, and `ankkaservice.yaml`'s `databaseCluster`.
- [X] T008 Add the fields to `CRD/AnkkaProject.scala` (`ProjectDatabaseSpec`, `ProjectBackupsSpec`, `RestoreEntry`, `RehearsalEntry`; status `BackupsStatus`, `LineStatus`, `ProjectDatabaseStatus`, `ClusterStatus`, `RestoreStatus`, `ServiceVerification`, `RehearsalStatus`) and `databaseCluster: Option[String] = None` to `CRD/AnkkaService.scala`, with `@JsonDeserialize(contentAs = classOf[java.lang.Long])` on every `Option[Long]` (the Jackson trap in `kubernetes.md`). Declare all of it in `KUST/components/crd/ankkaproject.yaml` and `ankkaservice.yaml`. T007 green. Then show `CrdSchemaSuite` failing twice and restore each: remove `restores` from the yaml; remove `highestSequence` from inside a verification.

### Settings

- [X] T009 [P] Write `OPT/BackupSettingsSuite.scala` per `contracts/operator.md`, red: unset is `target = None`, retention 30, schedule `0 0 0 * * *`, copy not required, ttl 24 h; `object-store` with no `ANKKA_OBJECT_STORE_ADMIN_URL` throws naming both variables; `gcs` throws naming 044; `ANKKA_BACKUP_SCHEDULE=0 0 * * *` (five fields) throws; each property overrides its variable. `toString` of the settings holds no token (the store's is beside it).
- [X] T010 Add `OP/BackupSettings.scala` and read it in `OP/Settings.fromEnvironment` as `objectStore()` is read, declaring the five as `name -> default` pairs (`PlatformDeclarationSuite` refuses a bare literal vector). T009 green; `sbt 'controlPlane/testOnly *PlatformDeclarationSuite'` green.
- [X] T011 [P] Add to `controlplane/src/main/resources/reference.conf` an `ankka.backups` block: `target = "none"`, `retention-days = 30`, `copy-required = off`, each with its `${?ANKKA_BACKUP_…}` override, and `CP/BackupConfig.scala` reading it (`BackupConfig(target, retentionDays, copyRequired)`), with a case in `CPT/ConfigSuite` (or the suite that reads `AuthConfig`) that the defaults are those and the variables override.
- [X] T012 Create `KUST/components/backups/` per `contracts/installation.md`: `kustomization.yaml` (a `Component`) with two strategic merge patches, `operator-patch.yaml` on Deployment `ankka-operator` container `ankka-operator` and `controlplane-patch.yaml` on the control plane's Deployment and container, each adding the five variables (the local overlay's values: `object-store`, 30, the schedule, `off`, 24). List it in both overlays after `garage`; in the cloud overlay mark the retention and schedule `SET`. Extend `CPT/RemoteOverlaySuite.scala`: both overlays set each of the five exactly once on each of the two containers, asserted by shape (the container's `env` list, not a string anywhere), and the patched container names exist (the strategic-merge trap).

### The reserved suffix

- [X] T013 In `APIT/ReservedSecretNamesSuite.scala` add, red: `ProjectSecrets.problems("ankka-db-backups")` and `problems("x-backups")` each refuse, and the suite's "every Secret the operator renders is refused" case covers `Buckets.BackupSecret`. Add `"-backups"` to `ProjectSecrets.ReservedSuffixes` in `API/descriptors.scala`. Green. *Done differently:* `ankka-db-backups` is already refused by the platform's `ankka-` prefix, and a `-backups` suffix would refuse a member's `nightly-backups` for nothing; `BackupNamesAgreeSuite` and `ReservedSecretNamesSuite` pin the prefix covering it.

### The DDL

- [X] T014 Write `DDL/50-recovery-postgres.sql` per R16: `ankka_history_lines(line_id TEXT PRIMARY KEY, started_at TIMESTAMPTZ NOT NULL DEFAULT now())` and `ankka_restore_marker(restored_at TIMESTAMPTZ NOT NULL, target_time TIMESTAMPTZ NOT NULL, released_at TIMESTAMPTZ, released_by TEXT)`, every statement `IF NOT EXISTS`, with a header comment naming the feature. Name it in all seven lists: `TK/SharedPostgres.DdlResources`, `OP/CnpgRendering.SchemaFiles`, `OPT/SchemaResourceSuite`, `OPT/CnpgRenderingSuite`, `sidecar/src/test/…/SidecarClusterSuite.deployPostgres`, `KUST/components/postgres/kustomization.yaml`, `KUST/components/postgres/cluster.yaml`. Run `sbt 'operator/testOnly *SchemaResourceSuite *CnpgRenderingSuite' 'testkit/testOnly *SharedPostgresSuite'` and show `SchemaResourceSuite` red with one list missing before finishing.

### The CNPG and plugin models

- [X] T015 [P] Write `OPT/cnpg/CnpgModelsSuite.scala`, red: `ClusterSpec` with `plugins`, `externalClusters`, `bootstrap.recovery` (`source`, `recoveryTarget.targetTime`, `database`, `owner`, `secret`), `postgresql.parameters`, `postgresql.synchronous` serialises to exactly the YAML of R7, R12 and R18 (as JSON through `AnkkaSerialization`) and omits every absent field; `ClusterStatus` decodes `conditions`, `currentPrimary`, `timelineID`, `phase`, `phaseReason`, `instanceNames` from a fixture copied from a real cluster (`operator/src/test/resources/cnpg/cluster-status.json`); `ObjectStore` spec and `status.serverRecoveryWindow` round-trip; `ScheduledBackup` serialises R7's; `Backup` status decodes `phase`, `stoppedAt`, `error`.
- [X] T016 Extend `OP/cnpg/PostgresCluster.scala` (`PluginConfiguration`, `ExternalCluster`, `RecoverySpec`, `RecoveryTarget`, `SynchronousSpec`, `parameters: Option[Map[String,String]]`, the status fields) and add `OP/cnpg/ObjectStore.scala` (`@Group("barmancloud.cnpg.io") @Version("v1")`), `OP/cnpg/ScheduledBackup.scala`, `OP/cnpg/Backup.scala`, each a partial model with the file's "server-side apply owns only what it sends" comment. T015 green.

### The reads and the SQL

- [X] T017 [P] Write `OPT/DatabaseQueriesSuite.scala` per R11, red: `DatabaseQueries.lag.sql` is the `pg_stat_archiver` statement and `parse(Vector(Vector("12.5","3","2026-…")))` gives `ArchiveState(lagSeconds = 12.5, failedCount = 3, lastFailed = Some(…))`; `replication.parse` gives sync states by name; `verification(target).sql` names the five tables and `max(seq_nr)`, `max(db_timestamp)` and the `ankka_secrets … updated_at > $1` clause with the target as a literal UTC timestamp, and `parse` gives a `ServiceVerification`; `presence.parse` lists database names. No SQL text appears in any other file (a grep case over `OP/`).
- [X] T018 Add `OP/DatabaseQueries.scala` with the three statements as values and their parsers. T017 green.
- [X] T019 Add to the `Executor` trait in `OP/Executor.scala`: `observeObjectStore(ns, name): Option[RecoveryWindow]`, `observeCluster(ns, name): Option[ClusterObservation]` (phase, reason, conditions by type, primary, ready, instances, timeline), `lastBackup(ns, cluster): Option[BackupObservation]`, `query(ns, pod, database, sql): Vector[Vector[String]]`; implement them in `Fabric8Executor` with `ifTypeExists` for the plugin's type (a cluster without it reads as "no object store") and `exec` in container `postgres` for `query`, parsing `psql -tA`'s tab-separated output. The test `Executor` doubles in `OPT/` answer from maps.
- [X] T020 Add the operator's grant in `KUST/components/operator/operator.yaml` per `contracts/operator.md` (`scheduledbackups`, `backups`, `objectstores`: get/list/watch/create/patch; `pods/exec`: create) and the second ClusterRole `ankka-operator-rehearsal` with `delete` on `clusters` and nothing else, with a comment saying where it is bound and why it is separate (R15). Add to `KUST/components/garage/zero-trust.yaml` the ingress on 3900 from pods labelled `cnpg.io/cluster` in ankka-managed namespaces and from `ankka-controlplane` (R6), asserted by shape in `RemoteOverlaySuite`.
- [ ] T021 In `OPT/OperatorClusterSuite.scala` case 20 add, with the operator's own token: `delete` of a `Cluster` in `ankka-<project>` is 403; `get` of Secret `ankka-db-backups` is 403; `create` of `pods/exec` on `ankka-db-1` is allowed; `get` of an `ObjectStore` is allowed. (k3s; run once here, again in US5.)

**Checkpoint**: `sbt crd/test core/test controlPlaneApi/test 'operator/testOnly *Suite' controlPlane/test` green. Nothing a running installation renders has changed: `RenderingUnchangedSuite` is untouched.

---

## Phase 3: User Story 1 — every project database is archived continuously, and a failure is seen (Priority: P1) 🎯 MVP

**Goal**: with a target named, every project database and the control plane's archive and take a
base backup; the project's status says when, how far back, how far behind; a failure is on the
status and a metric within five minutes.

**Independent Test**: `BackupsClusterFeatures` runs `FEAT/backups.feature` whole on k3s: deploy a
cart, assert a completed base backup and a lag under the bound; deny the key on the bucket and
assert the status and the metric report the failure within five minutes; allow it and assert both
clear.

### Tests for User Story 1 (first, and red)

- [X] T022 [P] [US1] In `OPT/CnpgRenderingSuite.scala` add cases per `contracts/operator.md`, red: with a target, `projectCluster` carries the `plugins` block with `serverName: ankka-db`, `archive_timeout: 60s`; `objectStore(ns, project, retentionDays)` is R7's with `retentionPolicy: "30d"` and a project override of 45 giving `"45d"`; `scheduledBackup(ns, cluster, schedule)` is R7's; without a target none of them is rendered and `projectCluster` is byte-for-byte today's. Extend `OPT/RenderingGoldenSuite` with a `backups` golden file and `OPT/RenderingUnchangedSuite` with a `with-target` variant of each fixture whose only difference from the base is the `Cluster` object (assert the Deployment identical).
- [X] T023 [P] [US1] Write `OPT/BackupStatusSuite.scala`, red: `BackupStatus.of(target, window, cluster, lastBackup, archive)` per R9 gives `phase: BackingUp` with `lastBaseBackup`, `firstRestorable`, `lastRestorable = now - lag`, `archiveLagSeconds`; `Failing` with the condition's message when `ContinuousArchiving` is `False`; `Failing` with the last `Backup`'s error when `lastFailedBackupTime > lastSuccessfulBackupTime`; `NotBackedUp` with "no backup target" when the target is none; and, with `copyRequired`, `copiedAt` set only when the copy is newer than the last base backup.
- [X] T024 [P] [US1] Write `OPT/StorageCredentialSuite` cases for the generalised `ensure(bucket, secretName, permission, entries)`, red: a read-write key for `platform.backups.shop` into `ankka-db-backups` with the three entries; a read-only key (`read = true, write = false, owner = false`) for the rehearsal; the 034 table unchanged for a service's.
- [X] T025 [P] [US1] Write `RTT/GaugesSuite.scala`, red: `Gauges.set("ankka.backups.failing", Map("ankka.project" -> "shop"), 1.0)` then `Gauges.snapshot` lists it; a second `set` replaces; `remove` drops; no library import in `RT/Gauges.scala`. And in `modules/telemetry-otlp`'s `MetricsSuite` (beside the counters' case): a gauge set is exported to the fake collector as an observable gauge with the attribute.
- [X] T026 [P] [US1] Write `CPT/BackupMetricsSuite.scala`, red: given two project statuses (one failing, lag 12 s; one healthy; one whose latest rehearsal failed), `BackupMetrics.publish` sets the five gauges of R10 with `ankka.project` (`ankka.rehearsals.failing` included), and a project that disappears has its gauges removed.
- [X] T027 [P] [US1] Write `CPT/ProjectStatusSuite.scala` and `APIT/ProjectStatusCodecSuite.scala`, red: `ProjectStatus` from an `AnkkaProjectStatus` fixture plus the entity's setting, with the phrases `backing up`, `failing: <reason>`, `not backed up: no backup target`; the wire round-trips and matches `console/package/fixtures/control-plane/project-status.json` written by `ControlPlaneFixturesSuite`.
- [ ] T028 [US1] Write `CPT/BackupsClusterFeatures.scala` (a `GherkinSuite("../features/databases/backups.feature")` with `LogCapturing`) and `CPT/BackupClusterSteps.scala` with the steps the seven scenarios need, red (the suite compiles and every step is pending). The first scenario's "archives every write" step writes to the cart continuously for two minutes (the loop T061 uses) before reading the lag, so SC-002's bound is measured under load. The last scenario (SC-009) reads `platform.backups.shop` with the service's own `ANKKA_S3_*` credential through the AWS SDK and asserts `ListObjects`, `GetObject` and `DeleteObject` are each refused. Add two plain cases beside the scenarios: lowering the `ObjectStore`'s `retentionPolicy` to `1d` after two base backups keeps `firstRecoverabilityPoint` before the new window's edge until a newer base backup completes (R7's verify-first); and `GET /installation` says `encryption: none` with the reason on Garage (SC-010). The failure step denies the bucket's key with `DenyBucketKey` through Garage's admin API and times the status (`GET /projects/{id}/status`) and the metric (a `FakeCollector` as `telemetryOtlp`'s tests use, receiving the control plane's export) until both say failing, asserting under 300 s; the clearing step allows it again and times the clear. The control plane scenario reads `GET /installation`. The "existed before" scenario starts the operator with `target = None`, deploys, restarts it with `object-store`, and asserts the pod's `startTime` unchanged and the event still read.

### Implementation for User Story 1

- [X] T029 [US1] Render per project in `OP/CnpgRendering.scala` (`objectStore`, `scheduledBackup`, the `plugins` and `archive_timeout` on `projectCluster` when `settings.backups.target.isDefined`) and in `OP/ProjectReconciler.scala`: `EnsureBucket(Buckets.backup(project))`, `EnsureBackupCredential(ns, Buckets.BackupSecret, bucket, readOnly = false, generation)`, `EnsureObjectStore`, `EnsureScheduledBackup` — new `Action`s in `OP/Action.scala` with `describe`, performed in `OP/Executor.scala` by server-side apply and `StorageCredential.ensure`. T022, T024 green; `RenderingUnchangedSuite` green.
- [X] T030 [US1] Add `OP/BackupStatus.scala` and fill `AnkkaProjectStatus.backups` and `.database` in `OP/ProjectReconciler.scala` from `observeObjectStore`, `observeCluster`, `lastBackup` and `query(lag)` on `currentPrimary`; `SetProjectStatus` only when changed. T023 green.
- [X] T031 [US1] In `OP/Operator.scala` add informers on `Cluster` (postgresql.cnpg.io) and `Backup` in ankka-managed namespaces that enqueue the namespace's `AnkkaProject` on add, update and delete, logging and skipping when the type is absent as the `AnkkaProject` informer does. Add to `OPT/OperatorSuite` (the in-memory one) a case that a cluster status change enqueues the project once.
- [X] T032 [P] [US1] Add `RT/Gauges.scala` (a `ConcurrentHashMap` keyed by name and attributes, `set`, `remove`, `snapshot`) and in `TEL/Metrics.scala` an observable gauge per distinct name over `Gauges.snapshot`, registered at build. T025 green.
- [X] T033 [P] [US1] Add `CP/BackupMetrics.scala` (the five gauges, the rehearsal one from the status's latest rehearsal outcome) and call it from `ServiceProjector` wherever an `AnkkaProject` status is observed (the watch's `onProjectStatus` and the sweep). T026 green.
- [X] T034 [US1] Add the `ProjectStatus` types to `API/descriptors.scala` and `Wire`, `GET /projects/{id}/status` to `CP/api/ProjectEndpoint.scala` (member; reads `projectStatus` live from the resource and the entity's setting), `InstallationStatus` and `GET /installation` to a new `CP/api/InstallationEndpoint.scala` (any authenticated user; from `BackupConfig` and, for the control plane's line, a read of `ObjectStore`/`Cluster` in `ankka-controlplane` through a new `AnkkaServiceClient.platformBackupStatus`, guarded so a missing grant reads as "unknown"), registered in `CP/ControlPlane.endpoints`; `InstallationStatus.encryption` is `none: Garage holds no key the archiver can send; the installation's volume encryption protects a backup` on `object-store`. `Service.databasePhrase` for a supplied database becomes `supplied; its owner's to back up` (FR-008), with its case in `CPT/ServiceModelSuite`. T027 green.
- [X] T035 [US1] CLI: `ankka projects status -p <project>` and `ankka status` in `CLI/Main.scala`, `CLI/ControlPlaneClient.scala`, `CLI/Output.scala` (one line per line of history; "nothing is backed up" when the target is none), with cases in `CLIT/OutputSuite` from the fixtures; `CLI/mcp/AnkkaTools.scala` gains both.
- [X] T036 [US1] The control plane's own database: in `OP/Operator.scala` a platform reconcile at start and on every resync that, with a target, ensures `platform.backups-controlplane` and offers `ankka-controlplane-db-backups` in `ankka-controlplane` by `create` (R21; a case in `OPT/OperatorSuite` with the executor double, and one in `OPT/StorageCredentialSuite`); in `KUST/components/postgres/` add `objectstore.yaml` naming that Secret, the `plugins` block and `archive_timeout` on `cluster.yaml`, `scheduledbackup.yaml`, and a Role + RoleBinding letting the control plane's ServiceAccount `get` `clusters` and `objectstores` there. Extend `RemoteOverlaySuite`.
- [ ] T037 [US1] Grant the control plane in `KUST/components/controlplane/controlplane-rbac.yaml` nothing new yet beyond T036's Role; confirm `ControlPlaneClusterSuite`'s RBAC case still passes. Then run T028 on k3s until every scenario of `backups.feature` is green, and record the two measured times in `research.md` under *Verified during implementation*.

**Checkpoint**: User Story 1 is independently testable: a project is archived, its status and the
metric say so, and a failure is seen within five minutes.

---

## Phase 4: User Story 2 — an owner restores a project to a moment and switches a service to it (Priority: P1)

**Goal**: a restore is a second cluster beside the first, verified per service; a switch rolls one
service onto it; the left cluster is kept; switching back is the same action.

**Independent Test**: `RestoringClusterFeatures` runs `FEAT/restoring.feature` whole: two carts,
writes on both sides of the moment, a restore holding A and not B while the live cluster holds
both; switch one, assert it reads A only and the other both; switch back.

### Tests for User Story 2 (first, and red)

- [X] T038 [P] [US2] Write `OPT/RestoreRenderingSuite.scala`, red: `RestoreRendering.cluster(ns, entry, settings)` is R12's YAML with `recoveryTarget.targetTime` in UTC RFC 3339, `database: postgres`, `owner: postgres`, no `plugins`; `policy(ns, cluster)` selects `cnpg.io/cluster=<cluster>`; once `InUse`, `cluster` gains the `plugins` block with `serverName: <restore>` and a `scheduledBackup` for it; `databaseFor(service, cluster)` and `roleFor` reference `ClusterRef(cluster)`.
- [X] T039 [P] [US2] In `OPT/RenderingSuite` (the service rendering) add, red: with `databaseCluster = Some("ankka-db-r1")` every platform container that reads the database and `ankka-schema` carry `env` `ANKKA_DB_HOST=ankka-db-r1-rw` and `ANKKA_DB_LINE=ankka-db-r1`, the CA volume is `ankka-db-r1-ca`, the `Database`/`DatabaseRole` name that cluster, and the `<service>-db` Secret is the same object as before; with it absent the rendered objects equal today's (`RenderingUnchangedSuite` untouched). With `databaseCluster = None` on the platform, `ANKKA_DB_LINE=ankka-db` is rendered (every platform service has a line).
- [X] T040 [P] [US2] In `CPT/ProjectEntitySuite.scala` add, red: `request-restore` journals `ProjectRestoreRequested(name, line, moment, actor, at)` and a second while one has no outcome is refused "a restore of shop is in progress: <name>"; `observe-restore(Verified, reachedAt)` journals `ProjectRestoreCompleted` once and a repeat is refused; `Failed` journals `ProjectRestoreFailed(reason)`; the history holds `restore-requested`, `restore-completed`; `Project.liveCluster` is `ankka-db` with no switch and the one cluster every service is on. In `CPT/ServiceEntitySuite`: `switch-database(cluster)` journals `ServiceSwitched(cluster, from, actor, at)` with `generation + 1` and a history entry `switched` whose detail names both clusters; `desiredState.databaseCluster` follows. In `CPT/EventCompatibilitySuite`: every new event's JSON without the new fields decodes; the pre-feature fixtures replay.
- [X] T041 [P] [US2] In `CPT/ProjectEndpointSuite` (the fast harness) add, red: `POST /projects/{id}/restores` by a member is 403 naming the owner role; by an owner with a moment before `firstRestorable` is 400 naming both bounds; with services on two clusters and no `line` is 400 naming both; `GET /projects/{id}/restores/{name}` returns the report from a resource status fixture. In `CPT/ServiceEndpointSuite`: `POST /services/{p}/{n}/switch` by a member is 403; to a cluster that is not a `Verified`/`InUse` restore nor one the service left is 400; to a restore whose verification says `present = false` for the service is 400 naming the moment; a valid one returns the status with `databaseCluster` and the history entry; one during a rollout is accepted (202) and the status says `switch pending: rollout in progress`.
- [ ] T042 [US2] Write `CPT/RestoringClusterFeatures.scala` over `FEAT/restoring.feature` with its steps in `BackupClusterSteps`, red. The "unchanged" step compares `pg_dump --data-only` of the live cluster before and after through `exec` (SC-004); the "on the restore" step reads the pod's `ANKKA_DB_HOST` and `pg_stat_activity` on the restore's primary for the service's role; the "line of its own" step waits for a `Backup` on the restore and reads `serverRecoveryWindow[<restore>]`; the "never removes" and "age" steps read `GET /projects/{id}/status`; the documentation step asserts `DOCS/operate/recovery.md` contains the `kubectl delete cluster` line.

### Implementation for User Story 2

- [X] T043 [US2] Domain: `Project`'s `restores`, `history`, `liveCluster`, the events and commands in `CP/domain/{model,events}.scala` and `CP/application/ProjectEntity.scala` (`request-restore`, `observe-restore`, `restores`, `history`); `Service.databaseCluster`, `switch-database`, `ServiceSwitched` in `ServiceEntity.scala`. T040 green.
- [X] T044 [US2] Projection: `CP/deploy/ProjectProjection.spec` carries `restores`; rename `ProjectTopicsTrigger` to `CP/application/ProjectProjectionTrigger.scala` reacting to the restore and settings events too; `ServiceProjection` writes `databaseCluster`. Add `CP/deploy/ProjectStatusIngest.scala`: on an `AnkkaProject` status (watch and sweep — the sweep gains `projectStatus` reads for every project with a restore or rehearsal in progress), for each restore whose phase became `Verified` or `Failed`, send `observe-restore` to the entity. Register a watch on `AnkkaProject` in `ServiceProjector.start` beside the service watch.
- [X] T045 [US2] Operator: `OP/RestoreRendering.scala` and its use in `OP/ProjectReconciler.scala`: for each `restores` entry render the cluster and policy; when `observeCluster` says healthy, run `DatabaseQueries.presence` then `verification` per service database of the project (the project's services are the `AnkkaService`s in the namespace) through `query`, write `restores[].phase = Verified` with the services' verifications and `reachedAt = max(db_timestamp)`; `Failed` with `phaseReason` after `Setting up primary` exceeds 30 min or the phase is a failure; once any `AnkkaService` in the namespace names the cluster, render its `Database`/`DatabaseRole` per service, the `plugins` block and `ScheduledBackup`, and write `InUse`. Fill `status.clusters` (live, restore, left with `leftAt` = the last switch away, `since`). T038 green.
- [X] T046 [US2] Operator: the service's `databaseCluster` in `OP/Rendering.scala` (the literals, the CA volume, the `Database`/`DatabaseRole` cluster) and `OP/ServiceReconciler.decideDatabasePlan` observing that cluster; `LifecycleRules.databaseStatus` names it. T039 green; `RenderingUnchangedSuite` green with no fixture changed.
- [X] T047 [US2] Routes and wire: `RestoreRequest`, `RestoreView`, `SwitchRequest`, `ServiceVerification`, `ClusterStatus`, `ProjectHistoryEntry` in `API/descriptors.scala` and `Wire`; `POST/GET /projects/{id}/restores[/{name}]`, `GET /projects/{id}/history` in `ProjectEndpoint` (owner through `organizationOf` + `requireOwner`; the window from the live status; "in progress" from the entity; the line default from `liveCluster`); `POST /services/{p}/{n}/switch` in `ServiceEndpoint` (owner; the restore's verification read live; a switch during a rollout is accepted and journaled; `ServiceProjector.projectOne` projects the previous `databaseCluster` while the resource's reported lifecycle is `UpdateInProgress` for the generation before the switch, and the next status event re-projects with the new one — R14; a case in `CPT/ServiceProjectorSuite` with a fake client showing the old cluster held, then applied). `ServiceStatus.databaseCluster` and `switchable`. T041 green.
- [X] T048 [US2] CLI: `projects restore`, `projects restores [list|get]`, `projects history`, `services switch --to` in `CLI/Main.scala`, the client and `Output` (the report as one row per service, the divergence under the heading "newer than the restore point"; the moment parsed as RFC 3339 with a zone or refused locally). `CLIT` cases from fixtures.
- [ ] T049 [US2] Run T042 on k3s until every scenario of `restoring.feature` is green. Record the restore's wall-clock for a small cluster in `research.md`.

**Checkpoint**: a restore, a verification, a switch, a switch back; the left cluster listed.

---

## Phase 5: User Story 3 — a restore says what it could not rewind, and re-publication is safe (Priority: P1)

**Goal**: the restore lists topics and groups newer than the restore point; a re-published event
carries the id it carried; a new event after a restore never carries an old one.

**Independent Test**: `AfterRestoreClusterFeatures` runs `FEAT/after-a-restore.feature` whole
with Strimzi: events 1–10, a restore after 5, the report naming the topic and the group, ids
compared through a plain Kafka consumer reading `ce-id`.

### Tests for User Story 3 (first, and red)

- [X] T050 [P] [US3] Write `TKT/HistoryLinesSuite.scala` against `SharedPostgres`, red: starting a service with `ankka.database.line = "ankka-db"` writes one row and a second start writes none; `"ankka-db-r1"` writes a second row with the database's `now()`; `HistoryLines.lineOf(timestamp)` is the latest row with `started_at ≤ timestamp` and the first row for anything earlier; absent configuration gives line `""` and no row.
- [X] T051 [P] [US3] Write `TKT/MessageIdSuite.scala`, red: a consumer publishing from an entity's events under line `A` gives `ce-id` `A/<pid>/3` for event 3 (read from the in-memory broker's message metadata); after inserting line `B` with `started_at` after event 5's `db_timestamp`, event 3 published again carries `A/<pid>/3` and a new event 6 carries `B/<pid>/6`; a `ProduceAll` of two carries `/0` and `/1`; a handler that declares `ce-id` keeps it; a durable-state source gives `<line>/<pid>/<revision>`; a topic-sourced consumer's publication keeps a random id. In `RTT/KafkaSuite` add that `KafkaPublisher` writes the declared id as the `ce-id` header.
- [X] T052 [P] [US3] Write `RTT/DivergenceSuite.scala`, red: against Kafka in a container (as `KafkaSuite` starts one), a service with one publication and one group: `Divergence.since(t)` after five messages before `t` and three after gives the topic with 3 on its partition and the group ahead when its committed offset passes `t`'s; against the in-memory broker an empty answer; `GET /divergence?since=` on the observe port answers it as JSON.
- [ ] T053 [US3] Write `CPT/AfterRestoreClusterFeatures.scala` over `FEAT/after-a-restore.feature` with `BrokerStack`, red. Its last scenario (a replica promoted with no restore) needs replicas rendered, T063: until then it is the one scenario of the file marked pending, and T058 is not done before T063. The id steps read `ce-id` with a plain `KafkaConsumer` under the suite's own certificate; the counting view is the sample's or a fixture service built in the suite's image (decide by what `BrokerClusterFeatures` already deploys and reuse it); the "does nothing to the broker" step compares `describeTopics`, end offsets and every group's offsets before and after a restore and a switch.

### Implementation for User Story 3

- [X] T054 [US3] Add `RT/HistoryLines.scala` (a `RuntimeExtension` registered by `ServiceBuilder.host` for every service with a database, reading `ankka.database.line` ← `${?ANKKA_DB_LINE}` in `reference.conf`, writing the row in `start`, holding the sorted lines in memory and re-reading them when a line is unknown) and `RT/MessageIds.scala` (`idFor(line, persistenceId, seq, index)`). T050 green. `ANKKA_DB_LINE` needs no declaration: it is under `ANKKA_DB_` in `PlatformVariables.RuntimeOnlyPrefixes`; add a `PlatformVariablesSuite` case saying so.
- [X] T055 [US3] Set the id in `RT/ProjectionSupport.applyConsumer` (a new parameter `eventId: Option[String]`, computed by `ConsumerEventHandler.process` and `ConsumerStateHandler` in `RT/ProjectionRuntime.scala` and by `RemoteConsumerEventHandler` in `RT/remote/RemoteProjection.scala` from `envelope.timestamp` and `HistoryLines.lineOf`, `None` from the topic handlers), on the single `publish` and on each `Outgoing` of `publishAll` with its index, unless the metadata declares `ce-id`; stamp it in `InMemoryPublisher` as `KafkaPublisher` does. T051 green; every existing `runtime`, `testkit` and `sidecar` suite green (the testkit's `ConsumerTestKit.applyConsumer` passes `None`).
- [X] T056 [US3] Add `RT/Divergence.scala` (over `KafkaConnection`'s consumer and `AdminClient`: `offsetsForTimes`, `endOffsets`, `listConsumerGroupOffsets` for the service's groups from `ConsumerGroups.name`) and the observe route in `RT/ObserveServer.scala`. T052 green.
- [X] T057 [US3] Control plane: `CP/deploy/DivergenceClient.scala` (asks each service with a ready instance over the observe port as `InstanceTopologies` reads topologies, unions the answers, lists `notAsked`); `ProjectStatusIngest` calls it when a restore becomes `Verified` and sends the divergence and the changed secret names (from the verification) with `observe-restore`; `ServiceEndpoint`'s switch asks it for the switched service and journals it on `ServiceSwitched.divergence`. `RestoreView.divergence` with FR-020a's `note`. Unit cases in `CPT/DivergenceClientSuite` with a fake observe server.
- [ ] T058 [US3] Run `CPT/AfterRestoreClusterFeatures.scala` (T053) on k3s until every scenario of `FEAT/after-a-restore.feature` is green.

**Checkpoint**: the ids, the listing, and the stated limit of the id.

---

## Phase 6: User Story 4 — a project asks for replicas and survives losing its primary (Priority: P2)

**Goal**: replicas with automatic failover; synchronous replication loses no acknowledged write;
services reconnect without a redeploy.

**Independent Test**: `ReplicasClusterFeatures` runs `FEAT/replicas.feature`: two replicas,
synchronous, a cart writing continuously, the primary's pod and PVC deleted, writes resume within
60 s with nothing acknowledged lost.

### Tests for User Story 4 (first, and red)

- [X] T059 [P] [US4] In `OPT/CnpgRenderingSuite` add, red: `replicas = 2` renders `instances: 3`; `synchronous = true` renders R18's block; `replicas = 0, synchronous = true` is refused by `DatabaseSetting.problems` in `APIT/DatabaseSettingSuite` (also: `replicas` over 4, `retentionDays` below the floor naming it, `rehearsalSchedule` not `daily`/`weekly`); a restore `InUse` gets the project's replicas too.
- [X] T060 [P] [US4] Write `RTT/PoolRecoverySuite.scala` against a Postgres container, red: with `validation-query = "SELECT 1"` and `acquire-retry = 20`, after `pg_terminate_backend` of every pooled connection the next `Database.withConnection` succeeds without an error reaching the caller; with the plugin's defaults it fails once (the case that shows the setting matters).
- [ ] T061 [US4] Write `CPT/ReplicasClusterFeatures.scala` over `FEAT/replicas.feature`, red. The loss step deletes the primary's pod and its PVC with the suite's admin client; the writing step is a loop against the cart that records every acknowledged write's id and the time of the first failure and the first success after it; the assertion is every acknowledged id present afterwards and the gap under 60 s (SC-006).

### Implementation for User Story 4

- [X] T062 [US4] `DatabaseSetting` and `PUT /projects/{id}/database` (member; the floor from `BackupConfig`) in `API/descriptors.scala`, `ProjectEndpoint`, `ProjectEntity` (`set-database`, `ProjectDatabaseSettingsSet`, history `database-set`), `ProjectProjection`; the CLI's `projects database set`. T059's refusals green.
- [X] T063 [US4] Render `instances` and `synchronous` in `OP/CnpgRendering.projectCluster` and `RestoreRendering` (in use) from `AnkkaProject.spec.database`; fill `status.database` (ready, instances, primary, `writesWaitingOn` from `DatabaseQueries.replication` when synchronous and no replica is `sync`). T059 green.
- [X] T064 [US4] Set `validation-query = "SELECT 1"` and `acquire-retry = 20` on `pekko.persistence.r2dbc.connection-factory` in `modules/runtime/src/main/resources/reference.conf` with a comment naming R18, and `TCP_KEEPALIVE` and `CONNECT_TIMEOUT` in `RT/DatabaseTls.scala`'s customizer. T060 green; `runtime/test` green.
- [ ] T065 [US4] Run T061 on k3s until every scenario of `replicas.feature` is green; record the measured gap in `research.md`.

**Checkpoint**: a lost primary is survived; the setting is the project's.

---

## Phase 7: User Story 5 — a restore is rehearsed without touching the project (Priority: P2)

**Goal**: a rehearsal restores into a namespace of its own, verifies, times, reports and removes;
the operator may delete there and nowhere else; a time to live reclaims a leftover.

**Independent Test**: `RehearsalsClusterFeatures` runs `FEAT/rehearsals.feature`; the grant
scenario asks the API server with the operator's token.

### Tests for User Story 5 (first, and red)

- [X] T066 [P] [US5] Write `OPT/RehearsalRenderingSuite.scala`, red: the rehearsal cluster is `RestoreRendering.cluster` under `ankka-db-x…` in `ankka-shop-rehearsal` with the expiry annotation at `now + ttl`; the namespace's authority objects, `ankka-db-backups` (read only), `ObjectStore` are rendered; `expired(cluster, now)` is true past the annotation; `DeleteCluster` describes itself.
- [X] T067 [P] [US5] In `CPT/ProjectEntitySuite` add, red: `request-rehearsal` journals and `observe-rehearsal(report)` journals `ProjectRehearsalCompleted` once with `elapsedSeconds`, the kept list holds the last 100, `rehearsals` lists them; a daily schedule is a setting. In `CPT/ProjectEndpointSuite`: `POST /projects/{id}/rehearsals` by a member is 202; `GET` lists.
- [X] T068 [P] [US5] Write `CPT/RehearsalNamespaceSuite.scala` (fast harness with a fake client), red: requesting a rehearsal ensures the namespace with the platform's labels and `rehearsal-of`, and a `RoleBinding ankka-operator-rehearsal` to the ClusterRole with the operator's ServiceAccount, before the entry is journaled (write the cluster first, then the journal).
- [ ] T069 [US5] Write `CPT/RehearsalsClusterFeatures.scala` over `FEAT/rehearsals.feature`, red. The grant scenario mints the operator's token and asserts `delete cluster` 403 in `ankka-shop` and allowed in `ankka-shop-rehearsal` (add the same two cases to `OperatorClusterSuite` case 20, T021); the "failed to remove" scenario sets the operator's `ttl` to a minute and blocks the delete once through a test hook on the executor; the "every day" scenario sets the schedule and advances the reconciler's clock (a `Clock` on `ProjectReconciler`).

### Implementation for User Story 5

- [X] T070 [US5] Domain and routes: `rehearsals`, `request-rehearsal`, `observe-rehearsal`, `ProjectRehearsalRequested/Completed`, the history kinds; `RehearsalRequest`, `RehearsalView`; `POST/GET /projects/{id}/rehearsals`; `ProjectProjection` carries `rehearsals`; `ProjectStatusIngest` observes a rehearsal's outcome and drops the entry from the spec once journaled. T067 green.
- [X] T071 [US5] The namespace and the binding in `CP/deploy/ServiceProjector` (`ensureRehearsalNamespace(projectId)`: `ensureNamespace` with the labels, then a `RoleBinding` by server-side apply) and the grant in `KUST/components/controlplane/controlplane-rbac.yaml`: `rolebindings` `create, patch`; `clusterroles` `bind` with `resourceNames: [ankka-operator-rehearsal]`, with the comment explaining escalation. T068 green; `RemoteOverlaySuite` asserts the binding's ClusterRole exists in the operator component.
- [X] T072 [US5] Operator: `OP/RehearsalRendering.scala` and the rehearsal path in `ProjectReconciler`: render, wait healthy, verify (T045's code against the rehearsal namespace's pod), write `rehearsals[]` with `elapsedSeconds` = healthy-and-verified minus the cluster's `creationTimestamp`, then `DeleteCluster`; on every pass delete any rehearsal cluster past its expiry and report `NotRemoved` for one found past it; start a scheduled rehearsal when due. The read-only credential through `StorageCredential.ensure(readOnly = true)`. T066 green.
- [X] T073 [US5] CLI `projects rehearse`, `projects rehearsals`, `projects database set --rehearse` in `CLI/Main.scala` and `CLI/ControlPlaneClient.scala`; `CLI/Output.scala` for the report. Run T069 and T021 on k3s until green.

**Checkpoint**: rehearsals, timed and kept; the one delete, scoped.

---

## Phase 8: User Story 6 — the control plane's database is restored (Priority: P2)

**Goal**: a documented, tested procedure; a marker; a held control plane that lists what differs
and changes nothing until released.

**Independent Test**: `ControlPlaneRestoreClusterFeatures` runs `FEAT/control-plane-restore.feature`
with the recovery kustomization applied as written.

### Tests for User Story 6 (first, and red)

- [X] T074 [P] [US6] Write `CPT/RestoreHoldSuite.scala` (fast harness), red: with a marker row unreleased, `ServiceProjector.projectOne` applies nothing and the fake client's objects are unchanged; `RestoreHold.differences` lists a service whose desired generation and image differ from the resource's, a declared topic absent from the `AnkkaProject`, and a labelled namespace with no known project; `release(by)` sets `released_at`, `released_by` and the next `projectOne` applies; without a marker nothing is held; `readiness` is true while held (the control plane serves, it only does not project).
- [X] T075 [P] [US6] Write `KUST/recovery/controlplane/` per `contracts/installation.md` (`kustomization.yaml`, `cluster.yaml`, `deployment-patch.yaml`, `marker-job.yaml`, and `archive/` with the plugin block and `ScheduledBackup`), every installation value marked `SET`. Add to `RemoteOverlaySuite` a case that renders it with the placeholders replaced and asserts: the cluster's `bootstrap.recovery.secret` names `ankka-controlplane-db-app`; the Job's command inserts into `ankka_restore_marker` with the target; the patch names the control plane's container.
- [ ] T076 [US6] Write `CPT/ControlPlaneRestoreClusterFeatures.scala` over `FEAT/control-plane-restore.feature`, red: deploy the control plane's manifests (as `ControlPlaneClusterSuite` does) with the `postgres` component's archiving (T036), apply a cart at generation 1, note the time, apply generation 2 and create a project `lab`, then run the recovery kustomization with the node's `kubectl` exactly as the documentation says, and assert through `GET /installation/restore` and the cluster. The erasure-log scenario asserts the listing's `reconciled` is empty and the step is marked pending-until-042 in the steps file with the scenario still run (its "brought up to date … how many" asserts zero gained).

### Implementation for User Story 6

- [X] T077 [US6] `CP/deploy/RestoreHold.scala` (a `RuntimeExtension` registered first in `ControlPlane.builder`: reads the marker through `runtime`'s `Database` at `start`, `held`, `release`, `differences` over the projector's reads, a `reconcilers: Vector[() => String]` seam for 042); the hold checked in `ServiceProjector.projectOne`, `projectTopics`, `setSuspended` and the sweep; `namespaces` `list` added to the control plane's grant and `AnkkaServiceClient.listPlatformNamespaces`. T074 green.
- [X] T078 [US6] `RestoreHoldStatus`, `ServiceDifference` in `API/descriptors.scala`; `GET /installation/restore` (member) and `POST /installation/restore/release` (platform admin, `requireAdmin`) in `InstallationEndpoint`; CLI `ankka installation restore [--release]`.
- [X] T079 [US6] Write `DOCS/operate/recovery.md`'s control plane procedure section and run T076 on k3s until green.

**Checkpoint**: the control plane restores without re-projecting an old world.

---

## Phase 9: User Story 7 — objects on Garage survive the loss of a node (Priority: P3)

**Goal**: three Garage nodes as an installation choice; a secondary store mirrored hourly with
deletions; "backed up" under a copy requirement; the local platform unchanged.

**Independent Test**: `DurabilityClusterFeatures` runs `FEAT/../object-storage/durability.feature`:
three pods, one deleted with its PVC, every object still read; a second Garage as the secondary,
a copy, a deletion mirrored.

### Tests for User Story 7 (first, and red)

- [X] T080 [P] [US7] Create `KUST/components/garage-replicated/` (the StatefulSet at `replicas: 3`, `replication_factor = 3`, required `podAntiAffinity` on `kubernetes.io/hostname`, the Service and policy from `garage`, and `layout-job.yaml`: a Job with the Garage image running a script against the admin API on `garage-0`: `ConnectClusterNodes` for the three pods' ids read from each pod's `/v2/GetNodeInfo` through the headless Service, `UpdateClusterLayout` for every node without a role with zone = the pod's name and capacity from the claim, `ApplyClusterLayout` at `version + 1` only when something is staged) and `KUST/components/garage-copy/` (the CronJob with `rclone/rclone:<pinned>`, the script: list buckets, allow `garage-copy` read on each, `rclone sync` per bucket, patch `garage-copy-status`; the Secret `garage-secondary` with a development value; the ConfigMap; the two Roles). In the cloud overlay list both in place of `garage` and `$patch: delete` the development Secret. Extend `RemoteOverlaySuite`: the cloud overlay lists `garage-replicated` and `garage-copy` and not `garage`; the local overlay the reverse (this is the "local platform keeps Garage on one machine" scenario, run offline); the secondary Secret absent remotely.
- [X] T081 [P] [US7] In `CPT/InstallationStatusSuite` (fast harness) add, red: `InstallationStatus.sharesFailureDomain` is true with target `object-store` and no copy status; `secondaryStore` is read from the ConfigMap's keys (`lastCompleted`, `lastFailed`, `deletedObjects`); with `copyRequired`, a project's `backedUp` is false until `copiedAt` is newer than `lastBaseBackup` and the detail says "no copy outside the failure domain".
- [ ] T082 [US7] Write `CPT/DurabilityClusterFeatures.scala` over `features/object-storage/durability.feature`, red (S5 first: its first scenario alone). The secondary is a second one-node Garage StatefulSet `garage-secondary` the suite applies; the copy step triggers the CronJob (`kubectl create job --from`) and waits for the ConfigMap's `lastCompleted` to move; the deletion step lists the secondary bucket with the AWS SDK.

### Implementation for User Story 7

- [X] T083 [US7] The control plane reads `garage-copy-status` (`CP/deploy/AnkkaServiceClient.scala`'s `copyStatus`, a Role in `garage-system` for `configmaps` `get` by name, in the `garage-copy` component) into `InstallationStatus` and `BackupMetrics.copy_failing`; `ProjectStatus.backedUp` under `copyRequired`; the operator fills `copiedAt` from the same ConfigMap read (`Executor.copyStatus`). T081 green.
- [ ] T084 [US7] Run `CPT/DurabilityClusterFeatures.scala` (T082) on k3s until every scenario is green; record the measured copy time for the suite's buckets in `specs/041-postgres-backup-recovery/research.md`.

**Checkpoint**: a Garage installation can lose a node, and loses at most an hour to losing the cluster.

---

## Phase 10: Polish & cross-cutting

- [X] T085 [P] Console: `CON/src/client/schemas.ts` (the new wire types from the regenerated fixtures), `CON/src/routes/project.tsx` (a "Database" section: backups per line, instances, clusters and their services, restores with the report, rehearsals, the restore and rehearse actions), `CON/src/routes/service.tsx` (the cluster and "switch"), `CON/src/testing/fake-control-plane.ts` (every new route), unit tests, and one Playwright journey in `console/e2e/tests/restore.spec.ts`: restore, read the report, switch one service, the status names two clusters. `just test-console`.
- [X] T086 [P] Documentation: `DOCS/platform/databases.md` (Backups, Restoring a project, Rehearsing; the "Bring your own database" paragraph), `DOCS/operate/recovery.md` (the rest: reclaiming a cluster, re-issuing a credential, the secondary store, SC-007's measurement with its date once run), `DOCS/platform/object-storage.md` (the platform's buckets, the three-node and copy components, R2's note), `DOCS/reference/limitations.md` (drop "one instance"; add FR-036's list and "no encryption of backups on Garage"), `mkdocs.yml` nav, `tools/docs/skill/ankka-platform/SKILL.md`; then `just docs-sync` and `just docs`.
- [X] T087 [P] Regenerate the reference pages: `just docs-reference` (`ControlPlaneRoutesReferenceSuite` fails until every new route has its hand-written section in `DOCS/reference/control-plane-api.md`; write them), `CliReferenceSuite` for `DOCS/reference/cli.md`.
- [X] T088 [P] Re-issue (FR-003a): `POST /projects/{id}/backups/credential` → `ProjectBackupCredentialReissued`, `backups.credentialGeneration` on the resource, the operator's `EnsureBackupCredential` minting and patching on a higher generation and deleting the old key after the cluster's next reconcile; CLI `projects backups reissue-credential`; a case in `BackupsClusterFeatures` that the old key is refused by Garage after the new one archives (R5's verify-first).
- [X] T089 Confirm `.github/cluster-suites.py` lists the seven new `*ClusterFeatures` classes with no edit, and `.github/ci-coverage.py` claims `kustomization/recovery/**` (extend the `build` filter if not). Run `python3 .github/ci-coverage.py` and `python3 .github/cluster-suites.py`.
- [X] T090 Add every trap learned to the rule files: `kubernetes.md` (the plugin, exec, the empty-archive check, `env` over `envFrom`, the Garage policy and CNPG pods, `bind`), `messaging.md` (the message id and its limit), `runtime.md` (the pool settings, `HistoryLines`), `control-plane.md` (the hold, owner-only project routes), `secrets.md` (`-backups`). Each in the file for its area, not `CLAUDE.md`.
- [ ] T091 `just features`, `sbt scalafmtAll scalafmtSbt`, then `sbt -Dankka.cluster.tests=off buildAll`; then the seven k3s suites through the `cluster` workflow on the branch. Fix what fails. Record the *Verified during implementation* findings in `research.md` and update the plan's "Operational consequences" with anything learned.

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1** first: T003 and T004 decide R7, R11, R12 and R14; T002 is what every k3s suite installs.
- **Phase 2** after Phase 1; its sections are independent of each other except T008 (needs T006's names) and T019 (needs T016's models).
- **Phase 3 (US1)** after Phase 2. The MVP.
- **Phase 4 (US2)** after US1 (the status, the credential, the archive it restores from).
- **Phase 5 (US3)** after US2 (a restore to publish from); T050, T051, T054, T055 (the line and the id) need only Phase 2 and can start beside US1. T058 (the whole file green) also needs T063 for its promotion scenario.
- **Phase 6 (US4)** after Phase 2 and T034's route; independent of US2 and US3.
- **Phase 7 (US5)** after US2 (the restore rendering and verification it reuses).
- **Phase 8 (US6)** after US1 (T036's archiving of the control plane's database); independent of US2 to US5.
- **Phase 9 (US7)** after Phase 2 and T034; independent of the rest.
- **Phase 10** last, except T085 and T086 which can start once the routes they describe exist.

### Within a phase

Tests before the code they hold; a spike before the decision it tests. In each story: rendering
and domain before routes, routes before the CLI, everything before the k3s run.

### Parallel opportunities

- Phase 2: T005, T007, T009, T011, T013, T014, T015, T017 are eight files in six modules with no shared state.
- US1: T022 to T027 are six test files; T032 and T033 after them, beside T029 to T031.
- US3's runtime half (T050, T051, T054, T055) beside US1's operator half.
- US4, US6 and US7 beside each other once US1 is in.
- Phase 10: T085, T086, T087, T088 are four trees.

### Parallel example: after Phase 2, three people

```
A: T022 → T029 → T030 → T031 → T034 → T037  (US1, operator and control plane)
B: T050 → T051 → T054 → T055                (US3's line and id, no cluster needed)
C: T025 → T032 → T026 → T033 → T035         (the metric, the CLI)
then A: T042 → T043 … T049 (US2); B: T052 → T056 → T057 → T058 (US3's rest); C: T059 → T065 (US4)
```

## Implementation Strategy

1. Phase 1 and 2, then US1 to the k3s suite: an installation that archives and tells the truth
   about it is shippable alone, and is the gap the first real-money user named first.
2. US2 and US3 together: a restore nobody can switch to is not recovery, and a switch without the
   listing forks the system of record silently.
3. US4 and US5 in either order; US6; US7 last, since a Google Cloud installation never needs it.
4. Polish throughout: the docs page for each story is written when its route lands, not at the end.

Each story's k3s suite is the proof it is done; a story is not reported complete until its suite
has run green on the `cluster` workflow.

## Notes

- 91 tasks: 4 setup, 17 foundational, 16 US1, 12 US2, 9 US3, 7 US4, 8 US5, 6 US6, 5 US7, 7 polish.
- A `[P]` task names its own files; two `[P]` tasks never touch one file.
- Every "red" is shown red before it is made green, and every k3s step asserts the thing the
  scenario names, not a word in a status (R24).
- Nothing deletes a database but T072's `DeleteCluster` in a rehearsal namespace.

## Implementation status (2026-10-09)

Written and passing offline: every task but the ones below. Written and not yet run on k3s, left for
the `cluster` workflow: T021 (in `OperatorClusterSuite` case 20), T028 `BackupsClusterFeatures`,
T042 `RestoringClusterFeatures`, T053 `AfterRestoreClusterFeatures`, T061 `ReplicasClusterFeatures`,
T069 `RehearsalsClusterFeatures`, T076 `ControlPlaneRestoreClusterFeatures`, T082
`DurabilityClusterFeatures`; and so T037, T049, T058, T065, T084 and T091's k3s half. The first local
run of the backups suite failed in every scenario on CNPG's operator, starved on the test node; the
backup stack now gives it room (see `.claude/rules/kubernetes.md`).
