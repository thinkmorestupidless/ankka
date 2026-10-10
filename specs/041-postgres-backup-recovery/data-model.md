# Data Model: Backup and Recovery

What exists, who writes it, and what each state means. Decisions are in
[research.md](research.md); the wire and the rendered objects are in [contracts/](contracts/).

## In the object store

### Backup bucket

| | |
|---|---|
| Name | `platform.backups.<projectId>` (`Buckets.backup`); `platform.backups-controlplane` for the control plane |
| Made by | the operator, when a project with a target is first reconciled; the control plane's by the `postgres` component's reconciler path |
| Holds | one prefix per line of history (`serverName`): `base/` and `wals/` as barman lays them out |
| Reachable by | the bucket's own keys (below); never a service's key; no route |
| Deleted by | never the platform. A deleted project's bucket stays, like its database |

### Backup keys

| | |
|---|---|
| `platform.backups.<project>` | read and write, not owner; held by `ankka-db-backups` in the project's namespace; replaced on re-issue (`credentialGeneration`), the old key deleted after the new one is in use |
| `platform.backups.<project>.rehearsal` | read only; held by `ankka-db-backups` in the rehearsal namespace |
| `garage-copy` | read on every bucket, allowed by the copy job itself (R20); in `garage-system` |
| `platform.backups-controlplane` | read and write; minted by the operator's platform reconcile at start (R21) into `ankka-controlplane-db-backups` in `ankka-controlplane` |

## In the cluster

### Settings (the `backups` component, on the operator and the control plane)

`ANKKA_BACKUP_TARGET` (`none` | `object-store`), `ANKKA_BACKUP_RETENTION_DAYS` (30),
`ANKKA_BACKUP_SCHEDULE` (`0 0 0 * * *`), `ANKKA_BACKUP_COPY_REQUIRED` (`off`),
`ANKKA_REHEARSAL_TTL_HOURS` (24). Defaults when unset. See [contracts/installation.md](contracts/installation.md).

### Credential Secret `ankka-db-backups`

| | |
|---|---|
| Namespace | the project's; the rehearsal namespace holds one of its own |
| Entries | `ACCESS_KEY_ID`, `ACCESS_SECRET_KEY`, `REGION` |
| Written | once by `create`; merge-patched on a re-issue, the one case |
| Read by | CNPG's instance sidecar for barman. Never the operator, never the control plane, never a service (`-backups` is a reserved suffix; no `secretKeyRef` may name it) |

### `ObjectStore ankka-backups` (barmancloud.cnpg.io/v1)

One per namespace holding a backed-up cluster. `retentionPolicy` is the project's window
(`<days>d`); `status.serverRecoveryWindow[<line>]` is where the operator reads `firstRecoverabilityPoint`,
`lastSuccessfulBackupTime` and `lastFailedBackupTime` per line.

### Clusters of a project

| Cluster | Name | Archives | Phase on the project |
|---|---|---|---|
| the project database | `ankka-db` | yes, line `ankka-db` | `live`, or `left` once every service has moved off it |
| a restore | `ankka-db-r<yyyymmddhhmm>` | once `InUse`, line = its name | `Restoring` → `Verified` → `InUse`; or `Failed`; never removed by the platform |
| a rehearsal's database | `ankka-db-x<yyyymmddhhmm>` in `ankka-<project>-rehearsal` | never | removed by the operator when the rehearsal ends or its expiry passes |

A `ScheduledBackup <cluster>-base` exists for every archiving cluster, owned by it. A
`NetworkPolicy ankka-db-database-<cluster>` per cluster. A `Database` and `DatabaseRole` per service
per cluster that service may be switched to (rendered on the restore when verified).

### `AnkkaProject.spec` (written by the control plane)

```
database:   { replicas: 0, synchronous: false, retentionDays: null, rehearsalSchedule: null }
backups:    { credentialGeneration: 0 }
restores:   [ { name, line, targetTime, requestedAt } ]            # every restore ever requested, in order
rehearsals: [ { name, line, targetTime, requestedAt } ]            # pending and recent; the operator removes the database, the control plane drops the entry once reported
```

### `AnkkaProject.status` (written by the operator)

```
backups:
  target: none | object-store
  lines:
    - line: ankka-db
      cluster: ankka-db
      phase: NotBackedUp | BackingUp | Failing
      lastBaseBackup, firstRestorable, lastRestorable: time?
      archiveLagSeconds: number?
      failing: text?                      # the reason, from the condition or the last Backup
      copiedAt: time?                     # the secondary store's last complete copy newer than lastBaseBackup, when a copy is required
database:
  cluster: ankka-db                       # the live cluster (R12's default line)
  readyInstances, instances, primary, synchronous, writesWaitingOn: text?
clusters:
  - name, line, phase: live | restore | left, services: [names], leftAt: time?, since: time
restores:
  - name, line, targetTime, phase: Restoring | Verified | Failed | InUse, reachedAt: time?, detail: text?
    services: [ { name, present: bool, journalRows, stateRows, offsetRows, timerRows, highestSequence, changedSecrets: [names] } ]
rehearsals:
  - name, targetTime, outcome: Running | Completed | Failed | NotRemoved, startedAt, elapsedSeconds?, services: [as above], detail?
```

### `AnkkaService.spec.databaseCluster: Option[String]`

`None` is `ankka-db`. Rendered as the literal `ANKKA_DB_HOST=<cluster>-rw` and `ANKKA_DB_LINE=<cluster>`
on every platform container that reads the database and on the schema init container, the
`<cluster>-ca` volume, and the service's `Database`/`DatabaseRole` against that cluster. A change
is a rolling update.

### The rehearsal namespace `ankka-<project>-rehearsal`

Created by the control plane with the platform's labels and `rehearsal-of: <project>`; holds a
`RoleBinding` of the operator's ServiceAccount to ClusterRole `ankka-operator-rehearsal` (`delete`
on `clusters.postgresql.cnpg.io`, nothing else), the project authority's Issuer and Certificates,
`ankka-db-backups` (read only), `ObjectStore ankka-backups`, and at most one rehearsal cluster at a
time.

## In a service's database

### `ankka_history_lines`

| Column | Meaning |
|---|---|
| `line_id` | the cluster's name the service was on when the line began; `""` locally |
| `started_at` | the database's `now()` when the service first started on that cluster |

Written by `HistoryLines` at start when `ANKKA_DB_LINE` has no row. The line of an event is the
latest row whose `started_at ≤` the event's `db_timestamp`. A promotion writes nothing.

### Message id

`<line>/<persistenceId>/<seqNr>` for an event, `/<i>` appended for the i-th message of a
`ProduceAll`; `<line>/<persistenceId>/<revision>` for a durable-state change. Set as `ce-id` unless
the handler declared one. A topic-sourced consumer's publication keeps a random id.

## In the control plane's database

### `ankka_restore_marker` (the control plane's database only)

| Column | Meaning |
|---|---|
| `restored_at` | when the procedure's Job ran |
| `target_time` | the moment restored to |
| `released_at`, `released_by` | set by `POST /installation/restore/release`; `NULL` while held |

The control plane holds projection while a row has `released_at IS NULL`.

### `Project` (journal)

| Field | From event |
|---|---|
| `database: DatabaseSetting(replicas, synchronous, retentionDays, rehearsalSchedule, setBy, setAt)` | `ProjectDatabaseSettingsSet` |
| `backupCredentialGeneration: Int` | `ProjectBackupCredentialReissued` |
| `restores: Map[name, Restore(line, targetTime, requestedBy, requestedAt, outcome: Option[RestoreOutcome])]` | `ProjectRestoreRequested`; `ProjectRestoreCompleted(reachedAt, divergence, changedSecrets, notAsked)`; `ProjectRestoreFailed(reason)` |
| `rehearsals: Vector[RehearsalReport(name, targetTime, requestedBy, requestedAt, outcome, elapsedSeconds, services)]` (last 100) | `ProjectRehearsalRequested`, `ProjectRehearsalCompleted` |
| `history: Vector[ProjectHistoryEntry(kind, actor, at, detail)]` (last 100) | every event above |

`restoreInProgress` = a restore with no outcome. Every new event field has a default so a
pre-feature journal replays (`EventCompatibilitySuite`). No row count and no secret value is
journaled; the per-service verification is read live from the resource's status.

### `Service` (journal)

`databaseCluster: Option[String]` from `ServiceSwitched(cluster, from, divergence, actor, at)`,
which also increments `generation` and writes a history entry of kind `switched`.

### Divergence (journaled on a restore's completion and a switch)

```
topics:   [ { topic, partitions: [ { partition, newerThanRestorePoint: count } ] } ]
groups:   [ { group, service, aheadOfRestorePoint: bool } ]
notAsked: [ service names with no ready instance ]
```

## State transitions

```
Restore:    Restoring ──verified──▶ Verified ──first switch──▶ InUse
                └──────failed────▶ Failed
Rehearsal:  Running ──▶ Completed | Failed ; either ──not removed at end──▶ NotRemoved ──expiry──▶ (removed)
Cluster:    live ──every service switched off──▶ left ──a service switched back──▶ live
Marker:     held (released_at NULL) ──release──▶ released
```
