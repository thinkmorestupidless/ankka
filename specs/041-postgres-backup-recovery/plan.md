# Implementation Plan: Backup and Recovery — Point-in-Time Restore for Every Database the Platform Provisions

**Branch**: `041-postgres-backup-recovery-impl` | **Date**: 2026-10-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/041-postgres-backup-recovery/spec.md`

## Summary

Every project database the operator provisions, and the control plane's own, archives its writes
continuously and takes a daily base backup into a bucket per project in the installation's object
store that no service's credential reaches. An owner restores a project to a moment into a second
cluster beside the first, reads a per-service verification, and switches one service at a time by
a rolling update; the cluster a service leaves is kept. A restore reports what it cannot rewind: the
topics and consumer groups ahead of the restore point, and the names of secrets changed since. A
message published from a journal event carries an id made of its line of history, so an event
published again after a restore carries the id it carried. A project may ask for replicas with
automatic failover and synchronous replication. Rehearsals restore into a namespace of their own,
where the operator alone may delete, and are timed and kept. The control plane's own restore is a
kustomization and a marker row that holds projection until a platform administrator releases it.
On Garage an installation may run three nodes and copy every bucket to a secondary store hourly.

Technically: the archiver is CloudNativePG's Barman Cloud plugin, an installation component (R1);
the operator renders an `ObjectStore`, a `plugins` block and a `ScheduledBackup` per archiving
cluster (R7), a restore as a second `Cluster` bootstrapped by recovery (R12), and reads inside a
database by `psql` in its own pod (R11). The switch is a literal `ANKKA_DB_HOST` on the pod
template, so it rolls the service by itself and the write-once credential Secret stays untouched
(R14). The line of history is a row per line with the moment it began, and the id of an event is
the latest line not after the event's own timestamp (R16). The project owns settings, restores and
rehearsals; the service owns which cluster it is on, through its generation and history as a
rollback does (R13). Each service answers the control plane about its own topics and groups over
the observe port (R17). The control plane exports the backup metrics from the status the operator
reports, through a gauge registry the runtime gains (R10).

Planning found four things the spec did not have, and the spec is amended for each:

- **The archiver cannot encrypt for Garage.** Barman Cloud encrypts with SSE-S3 or SSE-KMS only;
  Garage offers SSE-C only. On Garage the platform encrypts no backup, and says so in the
  limitations; on Google Cloud Storage the bucket is encrypted under 044's installation key. FR-003b
  and SC-010 are narrowed (R2). This revises the clarify session's answer, which named SSE-C.
- **The line of history is by time, not by sequence.** One row per line with the moment it began
  is equivalent to the per-entity boundary the spec described and costs one comparison; FR-020 and
  the entity are reworded (R16).
- **A project id may not end in `-rehearsal`**, and one longer than 46 characters has no backup
  bucket (R4).
- **A restored cluster archives nothing until a service is switched to it**, which is what FR-016
  implies and CNPG's empty-archive check requires (R12).

And two that change the work without changing the spec: none of 038, 039, 042 or 044 exists in code,
so every "through 044" is Garage today with a named seam; and the Garage network policy as written
refuses the database's pods, so the first backup would have failed forever (R6).

## Technical Context

**Language/Version**: Scala 3 on JDK 21 (`core`, `runtime`, `telemetry-otlp`, `crd`,
`controlplane-api`, `controlplane`, `operator`, `cli`); TypeScript on Node ≥ 22 (`console/package`);
YAML (kustomize); SQL (one DDL file); Gherkin

**Primary Dependencies**: none added to any main classpath. The operator reads CNPG and plugin
resources through the fabric8 client it has and runs `psql` by `exec`; the runtime answers
divergence with the Kafka client it has. Two third-party manifests: the Barman Cloud plugin
`v0.15.1` and the `rclone/rclone` image at a pinned tag. CNPG stays at 1.30.0 and Garage at v2.3.0.

**Storage**: Garage buckets `platform.backups.*` for archives and base backups; a second CNPG
`Cluster` per restore and per rehearsal; `AnkkaProjectSpec` gains four fields and `AnkkaProjectStatus`
five blocks; `AnkkaServiceSpec` gains one field; one DDL file `50-recovery-postgres.sql` with two
tables, named in the seven lists; the control plane's journal gains eight project events and one
service event, every field defaulted.

**Testing**: munit offline suites in six modules; `testkit` suites against a real Postgres for the
line and the ids; seven `GherkinSuite`s on k3s, one per feature file; `OperatorClusterSuite`
extended for the grant; the console's unit, fixtures and Playwright tests; the docs build; the
features check. Three spikes under `-Dankka.spikes=on` before the rendering is written.

**Target Platform**: an installation's cluster — kind locally, any Kubernetes ≥ 1.32 otherwise,
with CNPG ≥ 1.26 and cert-manager. A service on a developer's machine is backed up by nobody and
its line is `""`.

**Project Type**: a platform's operator, control plane, runtime, CLI and console; four installation
components and a recovery kustomization; documentation

**Performance Goals**: archive lag under 60 s under the shopping cart's steady load (SC-002, by
`archive_timeout`); a backup failure on the status and the metric within 5 min (SC-003, by a
cluster informer); writes resume within 60 s of a lost primary (SC-006, by pool validation and
retries); a 50 GiB rehearsal verified in under an hour on the reference installation (SC-007, by
`lz4` and parallel jobs, measured by hand). A reconcile of a project without a target makes no new
read.

**Constraints**: no secret in an action, a status, a log line, the journal or a wire type; the
operator's only ankka dependency stays `crd`; no `delete` verb in the operator's ClusterRole; no
read of a Secret anywhere; the `<service>-db` Secret never rewritten; nothing rendered for a
service whose `databaseCluster` is absent differs from today, so upgrading rolls no pod; `kubectl
apply -k` remains the whole deploy; nothing the platform does destroys a database, a bucket or an
object, except a rehearsal's cluster in its own namespace; a stored journal decodes unchanged;
warning-free; `Test / parallelExecution := false` stays; no suite binds a fixed port or names an
image by a literal tag

**Scale/Scope**: about 14 new Scala source files and 30 changed across seven modules; about 20 new
suites and 18 changed; 4 new kustomize components and 1 recovery kustomization, about 26 files; 2
overlays changed; 1 DDL file; 4 console source files and their tests; 1 new docs page and about 8
changed; 7 feature files (written), 46 scenarios

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is the unfilled template, so the gate is the principles
`CLAUDE.md` and the rule files state as the codebase's constitution:

| Principle | Status | How the design honours it |
|---|---|---|
| Effects are inert data; one thing performs them | pass | five new `Action`s describe; `Fabric8Executor` performs; a query is a read on `Executor`, never an action (R8, R11) |
| Where two parties must agree on a derivation, there is one | pass | `Buckets.backup`, the cluster and namespace names in `crd` (R4); the line id is the cluster's name both render and read (R16) |
| Module dependency direction | pass | `crd` depends on nothing; the operator on `crd`; `runtime` gains `Gauges` with no library, `telemetry-otlp` reads it (R10); `controlplane-api` on `core` |
| The operator cannot reach into the control plane and depends on as little as possible | pass | no library; CNPG and the plugin through fabric8; `psql` by exec (R11) |
| A field on the resource needs the schema | pass | both resources' fields declared, `CrdSchemaSuite` both ways |
| Never render what must not roll | pass | a service with `databaseCluster` absent renders byte-for-byte as today; the switch is the one intended roll (R14); `plugins` on a cluster rolls no pod (S1) |
| No secret value in the control plane's journal | pass | the journal holds names, moments, counts of messages, never a key or a value (R13) |
| A credential is written where the pod reads it and never read back | pass | `create`, `patch` on re-issue; the rehearsal's is minted, not copied (R5, R15) |
| Nothing the platform does may destroy data | **departure** | a rehearsal's cluster is deleted, in a namespace where the grant allows it and nowhere else; justified below (R15) |
| Stored forms stay readable both ways | pass | every new event field defaulted, pinned by `EventCompatibilitySuite` |
| An overlay that only works from the script is not an overlay | pass | four components and a recovery kustomization, each applied by `kubectl apply -k`; the layout is a Job (R20) |
| Every port a workload has is mutual TLS | unchanged departure | the store's ports stay plain HTTP (034 R17); barman reaches Garage the same way |
| A strategic merge patch names a container that exists | pass | the `backups` component's two patches are asserted by shape in `RemoteOverlaySuite` |
| Which variables are the platform's is said once | pass | the five settings are the operator's and the control plane's, declared as pairs; nothing new is given to a service but `ANKKA_DB_LINE`, which joins `RuntimeOnlyPrefixes`' `ANKKA_DB_` already |
| Tests are serialised; no fixed port; no literal image tag | pass | nothing in `build.sbt`'s test settings changes; the plugin and rclone are pinned third-party tags |
| Could this check pass while the thing it checks is false? | pass | R24 names, per scenario, what is asserted and why a weaker assertion would pass falsely |
| Each acceptance scenario ends as a test that fails without the feature | pass | 46 scenarios in seven feature files, each run whole by a `GherkinSuite`; the one exception, SC-007, is a documented measurement |
| A new DDL file is named in seven lists | pass | `50-recovery-postgres.sql` (R16); `SchemaResourceSuite` fails on a miss |
| The schema is additive within a supported range | pass | two new tables, no column changed |
| Docs: pages stand alone, generated reference regenerated, new pages in nav and the skill | pass | R25 |
| Every tracked file claimed by a CI path filter | pass | `kustomization/**`, `features/**`, `docs/**` are claimed; `kustomization/recovery/` is under the same filter |

**Violations to justify**: one, the rehearsal deletion.

**Post-design re-check**: unchanged. The contracts add no dependency to a main classpath and no
verb to the operator's ClusterRole beyond the reads and `pods/exec`; the one `delete` lives in a
ClusterRole bound only where a rehearsal runs.

## Project Structure

### Documentation (this feature)

```text
specs/041-postgres-backup-recovery/
├── plan.md              # this file
├── research.md          # R1–R25: decisions with file-level evidence; eleven things to verify first
├── data-model.md        # buckets, keys, settings, the resources' fields, the line, the marker, what is journaled
├── quickstart.md        # the validation runs: pure → a real Postgres → spikes → k3s → console, docs → by hand
├── contracts/
│   ├── project-and-service.md   # the routes, the wire types, the CLI, the observe port's answer
│   ├── operator.md              # settings, resource fields, rendered objects, actions, reads, status, grant
│   └── installation.md          # components, overlays, the recovery kustomization, by hand
└── tasks.md             # /speckit-tasks, not created here
```

The acceptance scenarios are in `features/databases/` (six files) and
`features/object-storage/durability.feature`, in the words of `GLOSSARY.md`.

### Source Code (repository root)

```text
crd/…/crd/Buckets.scala                                   # backup(project), platformBackup(store); the 46-character bound
crd/…/crd/Names.scala, AnkkaProject.scala, AnkkaService.scala   # the rehearsal suffix; spec and status fields
kustomization/components/crd/ankkaproject.yaml, ankkaservice.yaml

kustomization/components/postgres/ddl/50-recovery-postgres.sql   # new: ankka_history_lines, ankka_restore_marker
modules/runtime/…/runtime/HistoryLines.scala              # new: the line row at start; lineOf(timestamp)
modules/runtime/…/runtime/MessageIds.scala                # new: the id from line, persistence id, sequence
modules/runtime/…/runtime/ProjectionSupport.scala, ProjectionRuntime.scala, remote/RemoteProjection.scala, Kafka.scala
modules/runtime/…/runtime/Divergence.scala                # new: the observe port's answer
modules/runtime/…/runtime/Gauges.scala                    # new: the registry telemetry reads
modules/runtime/…/runtime/DatabaseTls.scala, src/main/resources/reference.conf   # keepalive; validation-query, acquire-retry
modules/telemetry-otlp/…/telemetry/Metrics.scala          # observable gauges over Gauges
modules/core/…/core/PlatformVariables.scala               # nothing new: ANKKA_DB_LINE is under ANKKA_DB_

controlplane-api/…/api/descriptors.scala                  # the types in contracts/project-and-service.md; "-backups" reserved
controlplane/…/controlplane/domain/{model,events}.scala   # Project's settings, restores, rehearsals, history; Service's cluster
controlplane/…/controlplane/application/ProjectEntity.scala, ServiceEntity.scala, ProjectProjectionTrigger.scala
controlplane/…/controlplane/api/ProjectEndpoint.scala, ServiceEndpoint.scala, InstallationEndpoint.scala (new)
controlplane/…/controlplane/deploy/ProjectProjection.scala, ServiceProjection.scala, ServiceProjector.scala,
                                   ProjectStatusIngest.scala (new), RestoreHold.scala (new), DivergenceClient.scala (new)
controlplane/…/controlplane/BackupMetrics.scala           # new: the four gauges from project statuses
controlplane/src/main/resources/reference.conf             # the five settings
kustomization/components/controlplane/controlplane-rbac.yaml

operator/…/operator/Settings.scala, BackupSettings.scala (new)
operator/…/operator/cnpg/PostgresCluster.scala, ObjectStore.scala (new), ScheduledBackup.scala (new), Backup.scala (new)
operator/…/operator/CnpgRendering.scala, RestoreRendering.scala (new), RehearsalRendering.scala (new)
operator/…/operator/DatabaseQueries.scala                 # new: the SQL and its parsers
operator/…/operator/BackupStatus.scala                    # new: the project's status from the reads
operator/…/operator/Action.scala, Executor.scala, ProjectReconciler.scala, ServiceReconciler.scala, Rendering.scala, Operator.scala
operator/…/operator/StorageCredential.scala               # generalised: bucket, Secret, permission
kustomization/components/operator/operator.yaml           # the grant; ankka-operator-rehearsal

kustomization/components/cnpg-barman/                     # new: the plugin at v0.15.1
kustomization/components/backups/                         # new: the two patches
kustomization/components/garage/zero-trust.yaml           # cnpg.io/cluster pods admitted
kustomization/components/garage-replicated/               # new: three nodes, the layout Job
kustomization/components/garage-copy/                     # new: the CronJob, its Secret, the status ConfigMap, the Roles
kustomization/components/postgres/                        # the control plane's ObjectStore, plugins, ScheduledBackup, Role
kustomization/recovery/controlplane/                      # new: the procedure's kustomization and its archive/ step
kustomization/overlays/local/, cloud/                     # the components listed; the cloud overlay's SET values

cli/…/cli/Main.scala, Output.scala, ControlPlaneClient.scala, mcp/AnkkaTools.scala

console/package/src/client/schemas.ts, routes/project.tsx, routes/service.tsx, testing/fake-control-plane.ts
console/package/fixtures/control-plane/, console/e2e/tests/

operator/src/test/…/BackupSpike.scala, BackupStack.scala (new); OperatorClusterSuite.scala
controlplane/src/test/…/BackupClusterSteps.scala (new) and the seven *ClusterFeatures suites
modules/testkit/src/test/…/HistoryLinesSuite.scala, MessageIdSuite.scala (new)

docs/platform/databases.md, object-storage.md; docs/operate/recovery.md (new); docs/reference/limitations.md,
control-plane-api.md, cli.md; mkdocs.yml; tools/docs/skill/ankka-platform/SKILL.md
```

**Structure Decision**: no module, image this build makes or published artifact is added. Each
piece goes where its kind lives: names beside `Buckets`, rendering beside `CnpgRendering`, the SQL
in one operator file, the line beside the journal's adapters in `runtime`, the project's events in
the project's entity, the components beside `garage` and `postgres`, the recovery kustomization in
a directory of its own because it is applied by hand and never by an overlay.

## Order of work

Cut by user story, tests before the code they hold. Slices 1 to 3 need no cluster; the spikes
come first because R7, R11, R12 and R14 rest on them.

0. **Spikes** (S1, S2, S3 as `BackupSpike`): a running cluster gains the plugin without rolling;
   `exec` runs `psql`; `env` beats `envFrom`; recovery with `database: postgres` creates nothing.
1. **Names, settings, fields, the DDL** (FR-001, FR-003, FR-004's floor, FR-026's shape).
   `Buckets`, `Names`, both resources' fields and schemas, `BackupSettings`, the five settings on
   the control plane, the reserved suffix, `50-recovery-postgres.sql` in seven lists. All offline.
2. **Archiving rendered** (FR-002, FR-002a, FR-003a, FR-005, FR-007). The models, `ObjectStore`,
   `plugins`, `ScheduledBackup`, the credential, `DatabaseQueries`, `BackupStatus`, the project
   status, the cluster informer; golden and unchanged fixtures. Then the components `cnpg-barman`,
   `backups`, the Garage policy, both overlays, `RemoteOverlaySuite`, `BackupStack`.
3. **The line and the id** (FR-019, FR-020, FR-020a). `HistoryLines`, `MessageIds`, the two
   publish paths and the in-memory one, `ANKKA_DB_LINE` rendered. `testkit` suites.
4. **US1 on k3s** (`BackupsClusterFeatures`), with the metric (FR-006: `Gauges`, the module, the
   control plane's `BackupMetrics`).
5. **Restore, verify, switch** (FR-009 to FR-017). The project's events and routes, the service's
   switch, `RestoreRendering`, the verification, the per-cluster policy and `Database`s, the
   rendering of the literal host, the project status's clusters, the history. US2 on k3s.
6. **What a restore cannot rewind** (FR-018, FR-021, FR-022). `Divergence` on the observe port,
   `DivergenceClient`, the journaled completion. US3 on k3s with Strimzi.
7. **Replicas** (FR-026 to FR-029). `instances`, `synchronous`, the pool settings, the status's
   instances. US4 on k3s (S4 first).
8. **Rehearsals** (FR-023 to FR-025). The namespace, the RoleBinding and `bind`, the read-only
   credential, `RehearsalRendering`, `DeleteCluster`, the expiry, the schedule, the kept reports.
   US5 and `OperatorClusterSuite`'s grant cases.
9. **The control plane's restore** (FR-030, FR-031). `RestoreHold`, the held projector, the
   installation routes, the recovery kustomization, the `postgres` component's archiving. US6.
10. **Garage** (FR-032 to FR-035). `garage-replicated`, the layout Job, `garage-copy`, the status
    ConfigMap, the installation status and `ankka status`, "backed up" under the copy requirement.
    US7 (S5 first).
11. **The console, the CLI's reference, the documentation** (FR-036), then the whole build.

Slices 3 and 4 do not depend on each other; 6 depends on 5; 7 on 2; 8 and 9 on 5; 10 on 2.

## Complexity Tracking

One principle is departed from, in the first row. The others are places the plan departs from the
spec's wording or widens the platform's surface, each with the simpler thing that was rejected.

| Departure | Why needed | Simpler alternative rejected because |
|---|---|---|
| The operator deletes a rehearsal's cluster | a rehearsal that is never removed is a database per rehearsal forever, and the spec's time to live needs a deleter | asking a platform administrator to remove every rehearsal makes a daily rehearsal a daily chore; a `delete` in the ClusterRole would reach every project database, so it lives in a second ClusterRole bound per rehearsal namespace (R15) |
| No encryption of backups on Garage (FR-003b narrowed) | the archiver has SSE-S3 and SSE-KMS; Garage has SSE-C | a wrapping plugin or an S3 proxy puts a maintained component in every backup's path; the installation can encrypt its volumes, which covers every bucket (R2) |
| The line of history is by time (FR-020 reworded) | a per-entity boundary is a row per entity at every switch | the moment a line began and the event's own timestamp decide the same thing with one row and one comparison (R16) |
| `pods/exec` in the operator's grant | the lag, the verification and the changed secret names are inside the database, where no resource carries them | a Job needs a role with read on every service's tables, which the certificate model has no such role for; the metrics port is behind the database's policy (R11) |
| The host is a literal on the pod template, not the Secret | the Secret is written once and never read or rewritten | patching the Secret reaches no running pod and is a read in effect; the literal is what rolls the service, which the clarify session chose (R14) |
| A restored cluster archives only once in use | CNPG refuses a cluster whose archive is not empty, and FR-016 says a line begins at the switch | archiving from creation writes a line per restore and per rehearsal that nothing restores from (R12) |
| The control plane exports the operator's numbers | the operator has no exporter and will not gain a library | CNPG's exporter is a port the installation must scrape itself, unlabelled by project (R10) |
| Each service answers about its own topics and groups | only the service holds a broker credential and knows its publications | a broker credential on the control plane is a new identity with Describe on every topic (R17) |
| The copy job holds Garage's admin token | a key per bucket is Garage's model, and the copy must read every bucket | the operator allowing a copy key on every bucket it makes couples two components; the job is in `garage-system` with the token already there (R20) |
| A project id may not end `-rehearsal` and is at most 46 characters | the rehearsal namespace and the bucket name are derived from it | a separate map from project to namespace is a second derivation (R4) |
| The control plane gains `bind` on one ClusterRole | it must grant the operator `delete` in a namespace without holding `delete` | holding `delete` itself on every cluster is the power the design exists to avoid (R15) |

**Operational consequences** to announce in the release:

- An installation that adds the `backups` component with `ANKKA_BACKUP_TARGET=object-store` sees
  every project's `Cluster` gain the plugin at its next reconcile and take a base backup at once;
  no service pod rolls, but each project database's own pod restarts once (about 20 seconds), so
  every project's services lose their database for that long.
- The cloud overlay replaces `garage` with `garage-replicated` and lists `garage-copy`; both need a
  Secret created out of band, and a one-node Garage's data must be migrated to the three-node layout
  by the installation (documented; not automatic).
- A project secret named `…-backups` can no longer be set.
- The operator's ClusterRole gains reads of three resource types and `pods/exec`; a second
  ClusterRole `ankka-operator-rehearsal` appears; the control plane's grant gains `list` on
  namespaces, `rolebindings` and `bind`.
- A new DDL file: a service's database created before it gains the two tables on its next start
  (the schema init container runs every file); a local compose database created before it does not
  (`initdb.d` runs once).
- Message ids of events published from a journal change from random to derived on the first
  deploy after the upgrade; a consumer that deduplicated by the random id sees every event as new
  once.
