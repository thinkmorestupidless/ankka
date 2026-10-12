# Contract: the operator

Settings, the resources it renders, the actions, the reads, and what it writes back.

## Settings (`operator/.../Settings.scala`, read from the environment after a system property)

| Variable | Property | Default | Refused when |
|---|---|---|---|
| `ANKKA_BACKUP_TARGET` | `ankka.backup.target` | `none` | `object-store` with no `ANKKA_OBJECT_STORE_ADMIN_URL`; `gcs` until 044 |
| `ANKKA_BACKUP_RETENTION_DAYS` | `ankka.backup.retention-days` | `30` | not a positive integer |
| `ANKKA_BACKUP_SCHEDULE` | `ankka.backup.schedule` | `0 0 0 * * *` | not six fields |
| `ANKKA_BACKUP_COPY_REQUIRED` | `ankka.backup.copy-required` | `off` | not `on`/`off` |
| `ANKKA_REHEARSAL_TTL_HOURS` | `ankka.rehearsal.ttl-hours` | `24` | not a positive integer |

`BackupSettings(target, retentionDays, schedule, copyRequired, rehearsalTtl)`; `toString` is plain
(no secret in it). Declared as `name -> default` pairs, never a bare literal vector.

## The resource fields read

- `AnkkaProject.spec.database`, `.backups.credentialGeneration`, `.restores`, `.rehearsals`
- `AnkkaService.spec.databaseCluster`

Both declared in `ankkaproject.yaml` / `ankkaservice.yaml`; `CrdSchemaSuite` holds them to the
case classes in both directions.

## Rendered, per project with a target (`ProjectReconciler`, every pass)

| Object | Name | Namespace | Notes |
|---|---|---|---|
| bucket (store) | `platform.backups.<project>` | | `EnsureBucket` |
| key (store) + Secret | `ankka-db-backups` | project | `EnsureBackupCredential(ns, secret, bucket, readOnly = false, generation)` |
| `ObjectStore` | `ankka-backups` | project | `EnsureObjectStore`; `retentionPolicy` from the project's override or the floor |
| `Cluster` | `ankka-db` | project | gains `plugins`, `postgresql.parameters.archive_timeout`, `instances`, `postgresql.synchronous` |
| `ScheduledBackup` | `ankka-db-base` | project | `EnsureScheduledBackup`; `immediate`, owner `cluster` |
| `NetworkPolicy` | `ankka-db-database-<cluster>` | project | one per cluster the project has |

Per restore entry: `Cluster <name>` with `bootstrap.recovery` (research R12), its policy, and once
`Verified` a `Database` and `DatabaseRole` per service of the project against it; once `InUse`, the
`plugins` block, `archive_timeout` and `ScheduledBackup <name>-base` with `serverName: <name>`.

Per rehearsal entry: the namespace's authority objects, `ankka-db-backups` (read only, key
`platform.backups.<project>.rehearsal`), `ObjectStore ankka-backups`, `Cluster <name>` annotated
`ankka.thinkmorestupidless.com/expires-at`. Then `DeleteCluster(ns, name)` when the verification
is reported or the expiry passes: the one deleting action, refused by the API server anywhere but a
rehearsal namespace.

Per service (`Rendering`): `ANKKA_DB_HOST=<cluster>-rw` and `ANKKA_DB_LINE=<cluster>` as literals
on the platform containers that read the database and on `ankka-schema`; the `<cluster>-ca` volume;
`Database`/`DatabaseRole` against `<cluster>`. With `databaseCluster` absent, every object is
byte-for-byte what it is today (`RenderingUnchangedSuite`).

## Actions added

`EnsureObjectStore`, `EnsureScheduledBackup`, `EnsureBackupCredential`, `EnsureRoleBinding` (exists),
`DeleteCluster`. Each `describe`s itself without a secret. `EnsureCluster` is unchanged.

## Reads added (`Executor`)

| Read | Of | For |
|---|---|---|
| `observeObjectStore(ns, name)` | `ObjectStore.status.serverRecoveryWindow` | last base backup, first restorable, last failed |
| `observeCluster(ns, name)` | `Cluster.status`: conditions, phase, `currentPrimary`, `readyInstances`, `instanceNames`, `timelineID` | archive failing, instances, primary, restore phase |
| `lastBackup(ns, cluster)` | the newest `Backup` of the cluster | the failure's message |
| `query(ns, pod, database, sql)` | `psql` by `exec` in the primary's `postgres` container | lag, replication state, verification, presence (research R11) |

`DatabaseQueries` holds the SQL as pure values with parsers; no SQL is built anywhere else.

## Status written

`AnkkaProject.status` as [data-model.md](../data-model.md) lays it out, by `SetProjectStatus` only
when it changed. The `AnkkaService.status.database.cluster` names the cluster the service is on.

## Grant (ClusterRole `ankka-operator`)

Adds `scheduledbackups`, `backups` (`postgresql.cnpg.io`) and `objectstores`
(`barmancloud.cnpg.io`): `get, list, watch, create, patch`; `pods/exec`: `create`. Adds nothing to
`secrets` (still `create, patch`) and no `delete`. ClusterRole `ankka-operator-rehearsal`: `delete`
on `clusters`, bound per rehearsal namespace by the control plane. `OperatorClusterSuite` asserts,
with the operator's token: `delete cluster` in a project namespace 403, in a rehearsal namespace
allowed; `get secret ankka-db-backups` 403.

## Informers

`Cluster` (postgresql.cnpg.io) and `Backup` in ankka-managed namespaces requeue the project of
their namespace; absent types are logged and skipped, as `AnkkaProject` is today.
