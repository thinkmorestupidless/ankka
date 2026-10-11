# Research: Backup and Recovery

Decisions for [plan.md](plan.md), each with what in the repository, in CloudNativePG, in the Barman
Cloud plugin or in Garage it rests on. File references are to this branch's base (`00cdbfbd`).
"Verify first" marks a claim read from code or documentation and not yet run; the task that touches
it starts with a test or a spike that would show it false. The words are the glossary's: a **project
database** is backed up as an **archive** and **base backups** in a **backup bucket**; a **restore**
is a new project database beside the current one; a **switch** moves one service; a **line of
history** is what a message id names.

None of the specs this one depends on (038, 039, 042, 043, 044) has a plan or any code. Every seam
they describe — the cloud provider's `CloudResource`, the installation key, the keyring, the erasure
log, the read record — is specified only. This plan therefore builds backups whole on Garage, where
the operator already makes buckets and mints credentials (034), and leaves each later feature a
named place to plug in rather than a dependency on it. Where the spec says "through 044" the plan
says what Garage does today and what 044 replaces.

## R1. The archiver is the Barman Cloud plugin, an installation component beside CNPG

**Decision**: `kustomization/components/cnpg-barman/` applies the plugin's release manifest, pinned
at `v0.15.1` (2026-09-30), into `cnpg-system` beside the operator the `cnpg` component installs at
1.30.0. The plugin needs CloudNativePG 1.26 or newer and cert-manager, which every overlay has. Both
overlays list it. The manifest creates the `objectstores.barmancloud.cnpg.io` CRD, the `barman-cloud`
Deployment and Service, its own ServiceAccount, Roles and ClusterRoles, and two cert-manager
Certificates under a self-signed Issuer. The grant is the plugin's own; the operator's ClusterRole
gains what rendering needs (R8) and no delete.

**Rationale**: CNPG's in-tree `barmanObjectStore` is deprecated since 1.26 and is removed in 1.31;
the pin here is 1.30. The plugin is the one path that will still exist at the next CNPG upgrade.
Its `ObjectStore` resource is namespaced and carries the credential as a Secret reference, which is
the shape 034's credential already has (a Secret the operator creates and never reads).

**Alternatives considered**: the in-tree `spec.backup.barmanObjectStore` — rejected: one CNPG minor
from removal, and a migration to the plugin later is a change to every cluster's spec. Volume
snapshots — rejected: a snapshot is a moment, not a window, and kind has no snapshot class. pgBackRest
through a sidecar — rejected: nothing in CNPG knows it; every status would be hand-rolled.

**Verify first**: the v0.15.1 manifest applies to k3s v1.35.1 beside CNPG 1.30.0 and reports Ready;
the k3s stack helper `BarmanStack.install` is written first and used by every backup suite.

## R2. Encryption: the store's, under a key the installation holds — and on Garage there is none

**Decision**: the plugin's `wal.encryption` and `data.encryption` take `AES256` (SSE-S3) or
`aws:kms`; the plugin's own `EncryptionType` has no other value. Garage implements server-side
encryption only with customer-provided keys (SSE-C, since Garage 1.0) and neither SSE-S3 nor
SSE-KMS. The two never meet, so **on Garage the platform encrypts no backup**: the bucket is
protected by its credential (SC-009) and by whatever encryption the installation's volumes have,
which is the installation's, and `docs/reference/limitations.md` says so. On Google Cloud Storage
(039, 044) the bucket is made with the installation's key (`kmsKey` on 044's `bucket` request), so a
backup there is encrypted at rest under a key no service reaches with nothing done by the archiver.

The spec is amended: FR-003b and SC-010 hold on Google Cloud Storage; on Garage the requirement is
stated as a limitation, and the "backup key" entity is 044's installation key. The clarify session's
answer named SSE-C on Garage; planning found the archiver cannot send one.

**Rationale**: the alternative is to put something between the archiver and Garage. A wrapping
plugin (a fork of plugin-barman-cloud that adds `x-amz-server-side-encryption-customer-*` headers)
makes this repository the maintainer of a CNPG-I plugin. An S3 proxy in `garage-system` that adds
the SSE-C headers is a second component in every request path of every backup and restore, holding
the key, and every S3 client that reaches Garage directly bypasses it. Client-side encryption inside
barman does not exist. An installation that must have encrypted backups and cannot use Google Cloud
Storage encrypts its Garage volumes, which protects every bucket, not only backups.

**Alternatives considered**: the three above; and keeping FR-003b as written, which would ship no
backups until a plugin exists.

## R3. What the installation says: five settings, one component, both the operator and the control plane

**Decision**: `kustomization/components/backups/` is a kustomize `Component` with two patches, on
the operator's Deployment and the control plane's, adding:

| Variable | Default | Read by |
|---|---|---|
| `ANKKA_BACKUP_TARGET` | `none` | both. `object-store` names the installation's store (`ANKKA_OBJECT_STORE_*`, 034); `gcs` is 044's value, refused until it lands |
| `ANKKA_BACKUP_RETENTION_DAYS` | `30` | both: the operator renders it, the control plane refuses a project override below it (FR-004) |
| `ANKKA_BACKUP_SCHEDULE` | `0 0 0 * * *` | operator: the `ScheduledBackup`'s six-field cron |
| `ANKKA_BACKUP_COPY_REQUIRED` | `off` | both (FR-033a) |
| `ANKKA_REHEARSAL_TTL_HOURS` | `24` | operator (FR-023a) |

The component is listed by both overlays; an overlay without it has `ANKKA_BACKUP_TARGET` unset,
which is `none`, and renders exactly what it renders today (`RenderingGoldenSuite`,
`RenderingUnchangedSuite`). With `object-store` and no `ANKKA_OBJECT_STORE_ADMIN_URL`, the operator
refuses to start naming both, as `Settings.objectStore()` refuses a half-set store.

**Rationale**: the operator renders, so it must know; the control plane answers "nothing is backed
up" and validates the retention floor, so it must know too. Two deployments reading one component's
patches is how `ANKKA_BASE_DOMAIN` already reaches both. These are operator and control plane
settings, not variables given to a service, so they live in each process's `Settings`
(`operator/.../Settings.scala:118-192`, `controlplane/.../reference.conf`) and not in
`PlatformVariables`; `PlatformDeclarationSuite` refuses a bare `Vector("ANKKA_…")` in the operator,
so the names are `name -> default` pairs as `BrokerSettings.Variables` are. `RemoteOverlaySuite`
asserts both overlays set the five once, on both containers, by shape.

## R4. Names: the backup bucket, its credential Secret, the clusters, the rehearsal namespace

**Decision**, all in `crd` beside `Buckets` and `Hostnames` so the control plane and the operator
derive the same strings:

| Thing | Name | Why it cannot collide |
|---|---|---|
| a project's backup bucket | `platform.backups.<project>` | a service bucket is `<project>.<service>` with `platform` a reserved project id (`descriptors.scala:128`), so no service's bucket starts `platform.` |
| the control plane's | `platform.backups-controlplane` | same; the read record's and the keyring's follow when 038 and 042 land |
| the credential Secret | `ankka-db-backups` in the project's namespace | `-backups` joins `ProjectSecrets.ReservedSuffixes` (`descriptors.scala:1596`), held by `ReservedSecretNamesSuite` |
| the `ObjectStore` resource | `ankka-backups` | one per namespace |
| the project database | `ankka-db`, as today | the first line of history |
| a restore | `ankka-db-r<yyyymmddhhmm>` | the request's time; a second restore in the same minute is refused as "in progress" anyway (FR-010) |
| a rehearsal's database | `ankka-db-x<yyyymmddhhmm>` | in `ankka-<project>-rehearsal` |
| the rehearsal namespace | `ankka-<project>-rehearsal` | a project id may end in `-rehearsal`: `Names.namespace` refuses a project id ending `-rehearsal` from now on, as it reserves `platform` |
| a line of history | the cluster's name | `serverName` in the archive; `ANKKA_DB_LINE` on the service |

A bucket name is at most 63 characters, so a project id over 46 characters has no backup bucket;
its status says so. No project id that long exists in any suite, and the control plane refuses one
at creation from now on (`ProjectIds.problems`).

**Rationale**: 034 derived the bucket's name in `crd` so no writer of the resource can point a
service at another's bucket (its R5); a backup bucket is derived for the same reason and in the
same file. The rehearsal namespace reservation is the same move as reserving `platform`.

## R5. The credential on Garage: minted by the operator as 034 mints a service's; 044 replaces it

**Decision**: `StorageCredential.ensure` (`operator/.../StorageCredential.scala`) is generalised
over the bucket's name, the Secret's name and the permission, and used three times:

- `platform.backups.<project>` → key named the same, **read and write, not owner**, into
  `ankka-db-backups` in the project's namespace with entries `ACCESS_KEY_ID`, `ACCESS_SECRET_KEY`
  and `REGION` (barman's `s3Credentials` are three Secret references);
- the same bucket → key `platform.backups.<project>.rehearsal`, **read only**, into
  `ankka-db-backups` in the rehearsal namespace (R15), so a rehearsal cannot delete an archive;
- `platform.backups-controlplane` → read and write, into `ankka-controlplane`.

Re-issue (FR-003a) is `POST /projects/{id}/backups/credential` → the control plane raises
`AnkkaProject.spec.backups.credentialGeneration`; the operator mints a new key, merge-patches the
Secret (the `patch` verb it holds; `StorageCredential`'s `Replaced` branch), and deletes the old key
after the project database's instances have been rolled by CNPG's own reload of the Secret. 044's
`credentialGeneration` on a `bucket-credential` request is the same counter with the provider doing
the minting; the field's name is 044's so the two are one field.

**Rationale**: Garage's `write` permission includes delete, which retention needs; `owner` would let
the archiver change the bucket's settings, which nothing needs. Nothing is read back from anywhere
(034 R6). Barman reads the credential through CNPG's instance sidecar, which mounts the Secret by
reference, so the operator never sees the key after the `create`.

**Verify first**: the plugin's sidecar reloads a changed `s3Credentials` Secret without a restart
(the plugin reconciles its Role when the `ObjectStore` spec changes, v0.12.0 notes; a Secret's
content is read per command invocation by barman). If it does not, the re-issue rolls the cluster.

## R6. Garage must admit the database's pods, and the backup bucket is a platform bucket

**Decision**: the `garage` component's policy admits port 3900 from pods labelled
`cnpg.io/cluster` in ankka-managed namespaces and from `ankka-controlplane`, in addition to
ankka-managed pods (`kustomization/components/garage/zero-trust.yaml`). The backup bucket is made by
`EnsureBucket`, as a service's is, by the project reconciler rather than a service's; it has no
`HTTPRoute`, no `ReferenceGrant` and no `ANKKA_S3_*` rendered for anyone (044 FR-007's words). The
object storage documentation's "a key per bucket, a bucket per service" gains the sentence that the
platform's own buckets start `platform.`.

**Rationale**: the policy as written was found to refuse CNPG instance pods, which carry
`cnpg.io/cluster` and not `app.kubernetes.io/managed-by: ankka`; a backup that could never upload
would be reported failing within five minutes (FR-006) and never succeed.

## R7. What is rendered for a backed-up project database

**Decision**: with a target, `CnpgRendering.projectCluster` renders, and `Rendering.databaseActions`
ensures on every pass as it ensures the `Cluster` today:

```yaml
# ObjectStore ankka-backups (barmancloud.cnpg.io/v1), in the project's namespace
spec:
  retentionPolicy: "30d"                       # the installation's, or the project's longer one
  configuration:
    destinationPath: s3://platform.backups.<project>/
    endpointURL: http://garage.garage-system.svc.cluster.local:3900   # ANKKA_OBJECT_STORE_ENDPOINT
    s3Credentials:
      accessKeyId:     {name: ankka-db-backups, key: ACCESS_KEY_ID}
      secretAccessKey: {name: ankka-db-backups, key: ACCESS_SECRET_KEY}
      region:          {name: ankka-db-backups, key: REGION}
    wal:  {compression: lz4, maxParallel: 2}
    data: {compression: lz4, jobs: 2}
# on Cluster ankka-db
spec:
  plugins:
    - name: barman-cloud.cloudnative-pg.io
      isWALArchiver: true
      parameters: {barmanObjectName: ankka-backups, serverName: ankka-db}
  postgresql:
    parameters: {archive_timeout: "60s"}
# ScheduledBackup ankka-db-base
spec:
  schedule: "0 0 0 * * *"
  immediate: true
  backupOwnerReference: cluster
  method: plugin
  pluginConfiguration: {name: barman-cloud.cloudnative-pg.io}
  cluster: {name: ankka-db}
```

`serverName` is the line of history (R4); the `ObjectStore` resource's own `serverName` is left
empty, as the plugin requires. `archive_timeout: 60s` is what makes SC-002 (archive lag under 60 s)
true at low write rates: Postgres archives a segment when it fills or when the timeout passes, and
CNPG's default is five minutes. `lz4` is the fastest compression both `wal` and `data` allow, which
SC-007 (50 GiB verified in an hour) wants. The `ScheduledBackup` is owned by the cluster so it goes
with it, and `immediate: true` is what makes "a first base backup" happen on the day the target is
named (US1's second scenario) and not at midnight.

A project that existed before the target was named gets these at its next reconcile. *Corrected by
spike S1:* the plugin's sidecar is injected into the instance pods, so adding `plugins` restarts each
database instance once (about 20 seconds for one instance). No service pod is replaced, and no
acknowledged write is lost: a command in flight during the restart fails and is retried by its
caller, as any database restart is. FR-007's "without its services being redeployed" holds; the
release note says each project database restarts once when backups are turned on. `RenderingUnchangedSuite`'s fixtures are repinned for the
`Cluster` object and no Deployment: a repin that changed a Deployment would be a service rolling on
upgrade and is not accepted; this repin changes the `Cluster` only, and only when a target is set,
so the fixtures gain a second variant rather than changing the first.

**Verify first**: adding `plugins` and `postgresql.parameters.archive_timeout` to a running
`Cluster` restarts no pod (CNPG reloads parameters that do not need a restart; `archive_timeout`
is `sighup`). Spike S1 applies it to a running cluster and asserts the pod's `startTime` unchanged.

## R8. The operator's model and grant

**Decision**: `ClusterSpec` (`operator/.../cnpg/PostgresCluster.scala`) gains `plugins`,
`externalClusters`, `bootstrap.recovery`, `postgresql.parameters`, `postgresql.synchronous`;
`ClusterStatus` gains `conditions`, `currentPrimary`, `timelineID`, `phase`, `phaseReason`,
`instanceNames`. Three new resource models: `ObjectStore` (`barmancloud.cnpg.io/v1`, spec and its
`serverRecoveryWindow` status), `ScheduledBackup` and `Backup` (read for the last one's phase and
`stoppedAt`). The ClusterRole gains:

```yaml
- apiGroups: ["postgresql.cnpg.io"]
  resources: ["scheduledbackups", "backups"]
  verbs: ["get", "list", "watch", "create", "patch"]
- apiGroups: ["barmancloud.cnpg.io"]
  resources: ["objectstores"]
  verbs: ["get", "list", "watch", "create", "patch"]
- apiGroups: [""]
  resources: ["pods/exec"]
  verbs: ["create"]                     # R11: psql in the database's own pod
```

and no `delete` anywhere (R15 for the one place a delete exists). `OperatorClusterSuite`'s case 20,
which mints the operator's token and asserts a withheld verb is refused by the API server, gains
`delete` on `clusters` in a project namespace and `get` on `ankka-db-backups`.

**Rationale**: every field is a partial model under server-side apply, which owns only what it
sends (the file's own rule). The `Backup` read is how the operator learns a base backup completed
without the deprecated `status.lastSuccessfulBackup`, which CNPG does not set for plugins; the
plugin's `ObjectStore.status.serverRecoveryWindow[serverName]` gives `firstRecoverabilityPoint`,
`lastSuccessfulBackupTime` and `lastFailedBackupTime`, keyed by line.

## R9. What a project's status says, and how the operator learns it

**Decision**: `AnkkaProjectStatus` gains a `backups` block per line and a `database` block; the
project reconciler fills them from four reads and one query:

| Fact | Source |
|---|---|
| backed up at all | the target setting; the `ObjectStore` present |
| last base backup, earliest restorable moment | `ObjectStore.status.serverRecoveryWindow[line]` |
| latest restorable moment | the archive's `last_archived_time` (the query below) |
| archive failing, and why | the `Cluster`'s `ContinuousArchiving` condition: `status: "False"` with its message |
| base backup failing | `lastFailedBackupTime` newer than `lastSuccessfulBackupTime`, with the last `Backup`'s error |
| archive lag | `now() - last_archived_time` from `pg_stat_archiver`, read by R11's exec on the primary |
| instances ready, which is primary, writes waiting | `Cluster.status.readyInstances`, `currentPrimary`; `pg_stat_replication` sync state against `synchronous_standby_names` |

Each `AnkkaProject` reconcile makes these reads; an informer on CNPG `Cluster`s in ankka-managed
namespaces requeues the project on every change of a cluster's status or conditions, as the project
informer requeues the namespace's services today (`Operator.scala:106-122`). That, not the
five-minute resync, is what makes "within five minutes" (FR-006, SC-003) a bound met with margin:
CNPG updates the condition on its own reconcile of the instance, within a minute of
`pg_stat_archiver` reporting a failure.

**Rationale**: the operator reads CNPG objects through fabric8 already (`observeDatabase`); none
of this is a new kind of access. The lag is the one number no resource carries (CNPG's exporter
has `cnpg_pg_stat_archiver_*`, on a metrics port the database's network policy admits only from
`cnpg-system`), hence R11.

**Verify first**: the `ContinuousArchiving` condition's `reason` strings
(`ContinuousArchivingSuccess`, `ContinuousArchivingFailing` in CNPG's source as read, not run)
and that the condition goes `False` within a minute of the bucket refusing writes. The backups
suite's failure case measures it; the bound asserted is five minutes.

## R10. The metric: the control plane exports what the operator reports

**Decision**: `runtime` gains `Gauges`, a registry of named double-valued gauges with string
attributes and no library, beside `Recorder`; `telemetry-otlp` registers an observable gauge for
each on the `ankka` meter, as it registers the counters over `Recorder.totals` today
(`Metrics.scala:62-105`). The control plane, an ankka application that exports already
(`build.sbt:484-493`), sets from each project's status it observes:

| Metric | Attributes | Value |
|---|---|---|
| `ankka.backups.failing` | `ankka.project` | 1 while the status says failing, else 0 |
| `ankka.backups.archive_lag_seconds` | `ankka.project` | the lag |
| `ankka.backups.last_base_backup_age_seconds` | `ankka.project` | now minus the last completed base backup |
| `ankka.backups.copy_failing` | none | 1 while the secondary copy's last run failed (R20) |
| `ankka.rehearsals.failing` | `ankka.project` | 1 while the project's latest rehearsal failed or was not removed (FR-025) |

The control plane learns a project's status from the `AnkkaProject` watch and the sweep (R13), so a
failure the operator reports is a metric within the next export interval (10 s).

**Rationale**: the operator exports nothing and depends on nothing (its ClusterRole, logback and
fabric8), and will not gain OpenTelemetry for a gauge; the control plane holds every project's
status already, and 026's only exporter is the module the control plane loads. CNPG's own
Prometheus exporter stays available to an installation that scrapes it, but the platform promises
the metric through its own export, labelled by project, which is what an alert is written against.

## R11. Reading inside the database: `psql` in the primary's pod, for lag and for verification

**Decision**: the operator runs SQL against a project database the way `kubectl cnpg psql` does:
an `exec` into the primary pod's `postgres` container running `psql -U postgres -tA -d <database>
-c <sql>` over the unix socket, where the `postgres` role authenticates by peer. Three statements,
each a pure function in `operator/.../DatabaseQueries.scala` with a unit test on its text and its
parsing:

- **lag** (on `postgres`): `select extract(epoch from now() - last_archived_time), failed_count,
  last_failed_time from pg_stat_archiver` and `select sync_state, application_name from
  pg_stat_replication`;
- **verification** (per service database, after a restore): row counts of `event_journal`,
  `durable_state`, `projection_timestamp_offset_store`, `projection_offset_store`, `ankka_timers`,
  `max(seq_nr)` and `max(db_timestamp)` from `event_journal`, and `select name from ankka_secrets
  where updated_at > $target` for FR-022, names only;
- **presence** (on `postgres`): `select datname from pg_database`, which is what tells "a service's
  database is absent at that moment" (the first edge case) from an empty one.

The executor's `Executor.query(ns, pod, database, sql): Vector[Vector[String]]` is the one place an
exec is made; `Action` values carry none of it (a query reads, an action writes). The verb is
`pods/exec` `create` (R8).

**Rationale**: the alternatives each failed a rule. A Job with the platform's `postgres` image
needs a credential for a role present in the restored database with read on every service's tables,
which no role has (each service owns its own, by certificate). The metrics port through the API
server's pod proxy crosses the database's network policy from an address the policy cannot name.
A CNPG `Database` resource runs no arbitrary SQL. `pods/exec` into a database's pod is a power the
operator's namespace-wide `create` on Deployments already amounts to, and it is the mechanism
CNPG's own `kubectl` plugin uses.

**Verify first**: fabric8's `pods().inNamespace().withName().inContainer("postgres").exec(...)`
works through the k3s container's API server (websocket upgrade through the exposed port). Spike S2.

## R12. A restore is a second `Cluster`, bootstrapped from the line's archive

**Decision**: a restore request on the project (R13) becomes an entry in
`AnkkaProject.spec.restores`; the project reconciler renders for each:

```yaml
# Cluster ankka-db-r202610081012, in the project's namespace
spec:
  instances: 1                                    # a restore starts alone; the project's replicas follow when it is in use
  storage: {size: <the project database's>}
  certificates: {clientCASecret: ankka-db-client-ca, replicationTLSSecret: ankka-db-replication}
  postgresql: {pg_hba: [<CertificateRule>]}
  managed: {roles: [{name: ankka_tls, login: false}]}
  bootstrap:
    recovery:
      source: line
      recoveryTarget: {targetTime: "2026-10-08T09:20:00Z"}
      database: postgres
      owner: postgres
  externalClusters:
    - name: line
      plugin:
        name: barman-cloud.cloudnative-pg.io
        parameters: {barmanObjectName: ankka-backups, serverName: <the line restored>}
```

together with a `NetworkPolicy` per cluster (today's names `ankka-db` in its selector), a
`Database` and `DatabaseRole` per service of the project against the new cluster (both are found
already present in the restored data and reported as such; the `Database` is what the operator
observes, so it exists for every cluster), and nothing that archives: no `plugins`, no
`ScheduledBackup`. The restore is `Verified` when the cluster reports `Cluster in healthy state`
and R11's verification has run for every service; `Failed` with the cluster's `phaseReason` when
it is `Setting up primary` for longer than the bound or its phase says failed.

The line restored is named by the request, or defaults: when every service of the project is on one
cluster, that cluster's line; when they are on two, the request must name one and is refused
without. A moment is refused unless `firstRecoverabilityPoint ≤ moment ≤ last_archived_time` of
that line (FR-010), with both in the refusal.

**Rationale**: `bootstrap.recovery` with `externalClusters[].plugin` is the plugin's own recovery
shape; `targetTime` takes RFC 3339 and must carry a zone, so the platform writes UTC. `database:
postgres, owner: postgres` is what makes CNPG's post-recovery step create nothing: the defaults
(`app`/`app`) would add an `app` database and role to every restore. Recovery reads the source
line's archive and writes nothing to it; a restored cluster that archives nothing cannot trip
CNPG's empty-archive check or write a line nobody switched to (FR-016). The restore's data holds
every service's role with its password-less, certificate-authenticated login and the `ankka_tls`
group, so every service's client certificate is accepted by it unchanged (the spec's assumption,
confirmed: roles are in the base backup).

**Verify first**: `bootstrap.recovery.database: postgres` with `owner: postgres` and no `secret`
is accepted and changes nothing (CNPG "updates the user's password from the secret when owner
matches" — with no secret, nothing). Spike S3 restores a two-database cluster and lists its
databases and roles. Also: `recoveryTarget.targetTime` later than the archive's last commit fails
recovery ("If no transaction occurs after the target time, recovery fails") — so the platform
refuses a moment after `last_archived_time` rather than trying.

## R13. The control plane: the project owns restores and settings; a service owns which cluster it is on

**Decision**:

- `Project` (`controlplane/.../domain/model.scala:327-396`) gains `database: DatabaseSetting`
  (replicas, synchronous, retentionDays, rehearsalSchedule), `restores: Map[name, Restore]`,
  `rehearsals: Vector[RehearsalReport]` (kept, last 100), `history: Vector[ProjectHistoryEntry]`
  (last 100) and events `ProjectDatabaseSettingsSet`, `ProjectRestoreRequested`,
  `ProjectRestoreCompleted(name, reachedAt, divergence, changedSecrets)`, `ProjectRestoreFailed`,
  `ProjectRehearsalRequested`, `ProjectRehearsalCompleted(report)`, `ProjectBackupCredentialReissued`,
  each with `actor` and `at`. `ProjectTopicsTrigger` becomes `ProjectProjectionTrigger`, reacting to
  these too, and `ProjectProjection.spec` carries them onto `AnkkaProject.spec`.
- A **`ProjectStatusIngest`**, the project's `StatusIngest`: the projector watches `AnkkaProject`
  status and, when a restore's phase becomes `Verified` or `Failed` or a rehearsal ends, sends the
  observation to the entity, which journals the completion once (the fold refuses a second). The
  per-service verification (row counts, highest sequence, reached moment, changed secret names) is
  read live from the resource's status by `GET /projects/{id}/restores/{name}`, as topic phases are
  read live today; the journal holds the completion, the divergence (R17) and the report's summary,
  so the audit survives the cluster's status.
- `Service` gains `databaseCluster: Option[String]` (None is `ankka-db`) and the command
  `switch-database` → `ServiceSwitched(cluster, from, actor, at)` with `generation + 1` and a history
  entry of kind `switched`. `ServiceProjection` writes it as `AnkkaService.spec.databaseCluster`.
- Routes (all under the organization's checks; `organizationOf(projectId)` then `requireOwner` for
  the owner-only ones, since `Authorization.project` is member-only):

| Route | Who | Does |
|---|---|---|
| `PUT /projects/{id}/database` | member | replicas, synchronous, retention override (≥ floor), rehearsal schedule |
| `GET /projects/{id}/status` | member | the project's status: backups per line, database, clusters and which service is on each, restores, rehearsals |
| `POST /projects/{id}/restores` | **owner** | `{moment, line?}`; refused outside the window naming it; refused while one is in progress |
| `GET /projects/{id}/restores`, `/{name}` | member | the list; one with its report |
| `POST /services/{p}/{n}/switch` | **owner** | `{cluster}`; refused unless the restore is `Verified`/`InUse` and holds the service's database, or the cluster is one the service left |
| `POST /projects/{id}/rehearsals` | member | `{moment}` |
| `GET /projects/{id}/rehearsals` | member | the kept reports |
| `POST /projects/{id}/backups/credential` | member | re-issue (R5) |
| `GET /projects/{id}/history` | member | the project's audit |

CLI: `ankka projects status`, `projects database set --replicas N [--synchronous] [--retention-days N]
[--rehearse DAILY]`, `projects restore <moment> [--line L]`, `projects restores [list|get]`,
`projects rehearse <moment>`, `projects rehearsals`, `projects history`, `services switch <name>
--to <cluster>`, `projects backups reissue-credential`. Every route gets its hand-written section on
`docs/reference/control-plane-api.md` or `ControlPlaneRoutesReferenceSuite` fails.

**Rationale**: a restore is the cluster's, so it is the project's; a switch rolls one service, so it
is the service's desired state and goes through the generation and the history that every change
to a service goes through (033's rollback is the model: an ordinary event with one more field).
Cross-entity checks (the restore holds this service's database; the project's services are on one
cluster) live in the endpoint, which reads the project's live status, never in a handler. The
journal records no row count, so a verification's size never reaches the remoting frame.

## R14. The switch: a literal host on the pod template, and the credential Secret untouched

**Decision**: `Rendering` reads `spec.databaseCluster` and renders on every platform container
that reads the database, and on the schema init container, the literal `ANKKA_DB_HOST=<cluster>-rw`
in `env`, which Kubernetes applies over the same key from `envFrom`; the server CA volume mounts
`<cluster>-ca`; the `Database`, `DatabaseRole` and the observation use that cluster; `ANKKA_DB_LINE`
is the cluster's name (R16). The `<service>-db` Secret keeps saying `ankka-db-rw` and is never
patched. A changed pod template is a rolling update, which is the whole mechanism of "the switch
rolls the service" (clarify Q2). Switching back is the field set to `ankka-db`, the same rendering.
A switch asked for while a rollout is in progress is journaled at once, and `ServiceProjector.projectOne`
projects the previous `databaseCluster` until the resource's reported lifecycle is no longer
`UpdateInProgress` for the generation before the switch; the next status event re-projects with the new
cluster, which is the second roll the spec's edge case describes. The entity never refuses the switch for
this reason.

Once a restore has a service switched to it, the project reconciler renders its `plugins`,
`archive_timeout` and `ScheduledBackup` with `serverName` the restore's name (FR-016); the archive
of a new line begins with a base backup at once (`immediate: true`). The restore's phase becomes
`InUse`; it is never removed.

**Rationale**: the Secret is created once and never rewritten (`Executor.scala:379-392`, with
reasons); a host written into it would reach no running service, and a `patch` of it is a read of
it in effect (the secrets rule file's note on `patch`). A literal on the template is what already
carries every `ANKKA_DB_SSL_*` variable (`ZeroTrust.Database.Environment`). The `-rw` Service of a
restored cluster is CNPG's; the platform derives nothing new.

**Verify first**: `env` beats `envFrom` for the same key — Kubernetes documents it; spike S2's
pod asserts `ANKKA_DB_HOST` as the literal from inside.

## R15. Rehearsals: a namespace per project where the operator may delete, and a read-only credential

**Decision**: a rehearsal (`POST /projects/{id}/rehearsals`, or the project's schedule) becomes
an entry in `AnkkaProject.spec.rehearsals`; the control plane first ensures the namespace
`ankka-<project>-rehearsal` (labelled as a project namespace is, plus `ankka.…/rehearsal-of:
<project>`) and a `RoleBinding` in it binding the operator's ServiceAccount to the ClusterRole
`ankka-operator-rehearsal`, which holds exactly `delete` on `clusters.postgresql.cnpg.io`. The
control plane's grant gains `rolebindings` `create, patch` and `bind` on that one ClusterRole by
`resourceNames`, which is what lets it grant a verb it does not hold. The operator renders in that
namespace: the project authority's Issuer and Certificates (so the cluster's `certificates` resolve
there), the read-only backup credential (R5), an `ObjectStore` naming the project's bucket, and the
rehearsal `Cluster`, which is R12's restore under the name `ankka-db-x<time>`, annotated with its
expiry. It runs R11's verification, writes the report (moment, per service, elapsed, outcome) onto
the project's status, then deletes the cluster. The project reconciler also deletes any rehearsal
cluster past its expiry on every pass, and reports one it found past expiry as "not removed when
the rehearsal ended". A scheduled rehearsal is the operator's: the reconciler starts one when the
schedule is due and the last report is older than the period.

**Rationale**: a ClusterRole cannot say "delete, but only in namespaces of this form"; a
RoleBinding in each rehearsal namespace can, and `OperatorClusterSuite` proves both halves with the
operator's own token (delete refused in `ankka-shop`, allowed in `ankka-shop-rehearsal`). The
credential cannot be copied from the project's namespace (the operator reads no Secret) and should
not be: a read-only key is what "a rehearsal changes nothing of the backups" means at the store.
Deleting a CNPG `Cluster` deletes its PVCs, which CNPG owns.

**Verify first**: a `RoleBinding` to a ClusterRole, created by a subject holding `bind` on it by
`resourceNames`, is accepted by RBAC's escalation check without the subject holding `delete` itself.
Kubernetes documents `bind`; the suite proves it with the control plane's own ServiceAccount token.

## R16. The line of history: a row per line, a message id from the line and the event

**Decision**: the line of history is **by time, not by sequence**. A new DDL file,
`50-recovery-postgres.sql` (named in the seven lists), creates:

```sql
CREATE TABLE IF NOT EXISTS ankka_history_lines (
  line_id TEXT PRIMARY KEY, started_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS ankka_restore_marker (
  restored_at TIMESTAMPTZ NOT NULL, target_time TIMESTAMPTZ NOT NULL,
  released_at TIMESTAMPTZ, released_by TEXT);              -- R19, the control plane's only
```

`HistoryLines`, a `RuntimeExtension` in `runtime` registered by `ServiceBuilder` for every service
with a database: at start it reads `ANKKA_DB_LINE` (the cluster the operator put the service on;
absent locally, where the line is `""`), and if that line has no row, inserts it with the
database's `now()`. The line of an event is **the latest line whose `started_at` is not after
the event's `db_timestamp`**, which the handler has on the envelope (`EventEnvelope.timestamp`,
`ProjectionRuntime.scala:1285-1303`; `RemoteProjection.scala:438-443`). The message id is
`<line>/<persistenceId>/<seqNr>` (and `/<i>` for the i-th of a `ProduceAll`), set as `ce-id` on the
effect's metadata by `ProjectionSupport.applyConsumer` before `publishAll` or the single `publish`,
unless the handler declared one; `CloudEvents.headers` keeps a declared id already
(`Kafka.scala:47-64`). A durable-state source uses the revision; a topic source keeps the random
id. The in-memory publisher stamps the same id, so a test sees it.

Why time is equivalent to the spec's per-entity sequence, and simpler: a restore to `T` holds only
events with `db_timestamp ≤ T`; a switch at `S > T` starts line `B` at `S`; every event
re-published from the restored journal has a timestamp before `S` and so belongs to `A`, the line
it was first published under, and carries the id it carried; every event persisted after `S`
belongs to `B`, and no event of `A` ever had a timestamp after `S` *in this database* — the live
cluster's writes between `T` and `S` are in the left cluster, not here. Both timestamps come from
the one Postgres clock. A promotion writes no row, so a failover changes no id (FR-020). Switching
back to the left cluster finds `ankka-db` already its latest line, and nothing is written. The spec
is amended: FR-020 and the `line of history` entity say "the moment the line began", and the
per-entity boundary table goes.

**Rationale**: a per-entity boundary needs a row for every entity in the journal at the switch,
read on every publish or held in memory for every entity; a timestamp needs one row per line and
one comparison. `EventEnvelope.timestamp` is the journal's `db_timestamp`, which is what the
projection's own offset store already orders by (`projection_timestamp_offset_store`).

**Verify first**: `EventEnvelope.timestamp` for `eventsBySlices` under pekko-persistence-r2dbc
1.2.0 is the row's `db_timestamp` in epoch millis (it is the timestamp offset's); the message id
suite publishes with a known `db_timestamp` and asserts the line.

## R17. What a restore cannot rewind: each service asks the broker about itself

**Decision**: the observe port (7628, `ObserveServer`) gains `GET /divergence?since=<time>`,
answered by the runtime from its own Kafka client: for each topic the service publishes to, per
partition, `endOffset - offsetForTimes(since)` (the number of messages newer than the moment); for
each of its consumer groups, whether `committed > offsetForTimes(since)`. The control plane, on a
restore's completion, asks every service of the project that has a ready instance, records the
union in `ProjectRestoreCompleted.divergence`, and lists services it could not ask. A switch asks
the same of the switched service and records it on the switch's history entry. The platform
publishes, deletes and moves nothing (FR-021): both calls are reads.

**Rationale**: the control plane has no broker credential and should gain none; the operator has
no Kafka client and will not. Each service's `KafkaUser` has `Describe` on its project's topics and
`Read` on its groups (`kubernetes.md`), which `offsetsForTimes`, `endOffsets` and
`listConsumerGroupOffsets` need and no more. A service with no ready instance cannot be asked, and
the report says so rather than guessing.

**Verify first**: `AdminClient.listConsumerGroupOffsets` under the service's ACL (Read on the group
implies Describe). The after-a-restore suite runs it against Strimzi.

## R18. Replicas: `instances = replicas + 1`, quorum of one, `required`, and a pool that notices

**Decision**: `DatabaseSetting.replicas` (0 as shipped) renders `instances: replicas + 1` on the
project database (and on a restore in use); `synchronous: true` renders

```yaml
postgresql:
  synchronous: {method: any, number: 1, dataDurability: required}
```

so a write waits for one replica and, with none healthy, waits (the spec's edge case; `preferred`
would carry on and lose it). Lowering replicas keeps the primary: CNPG scales down replicas only.
`runtime`'s `reference.conf` sets on `connection-factory`: `validation-query = "SELECT 1"`,
`acquire-retry = 20` (the plugin's own advice: "the same number of retries as max-size"), and
`DatabaseTls`'s customizer adds `TCP_KEEPALIVE = true` and a `connectTimeout` of 3 s, so a
connection to a primary that vanished without a FIN is found dead on acquire rather than held for
the TCP timeout. Nothing else changes: entities and projections already retry on failure, and the
`-rw` Service moves to the promoted replica on CNPG's own promotion.

**Rationale**: `validation-query` is empty today and `acquire-retry` is 1, so after a failover
every pooled connection is handed out dead once (the plugin's comment says exactly this). A remote
validation is one round trip per acquire; measured against a journal write it is a small fraction,
and the k3s replicas suite is where SC-006's sixty seconds is proved. A multi-host r2dbc URL is
the other road and is wrong here: the `-rw` Service already is the one address.

**Verify first**: with these settings, deleting the primary's pod and PVC on a three-instance
cluster, a shopping cart writing continuously resumes within 60 s and loses no acknowledged write.
The replicas suite's third scenario measures it. Spike S4 is this scenario alone, before the suite.

## R19. The control plane's own restore: an overlay, a marker, a held projector

**Decision**: `kustomization/recovery/controlplane/` is a kustomization a platform administrator
applies by hand, documented step by step in `docs/operate/recovery.md`: it scales the control plane
to zero, creates `Cluster ankka-controlplane-db-r<time>` by R12's recovery from
`platform.backups-controlplane`'s line at the `targetTime` written into it (a placeholder marked
`SET`, as the cloud overlay's), patches the control plane's Deployment to read the new cluster's
`-app` Secret, and runs a Job `ankka-controlplane-restore-marker` (the `postgres` image the schema
init uses) that inserts the marker row:

```sql
insert into ankka_restore_marker (restored_at, target_time) values (now(), '<targetTime>');
```

`RestoreHold`, a `RuntimeExtension` of the control plane registered first, reads the marker at
start. With one unreleased, the `ServiceProjector` **writes nothing**: `projectOne` and
`projectTopics` compute the projection and compare it to the resource in the cluster instead of
applying it, and the sweep lists namespaces labelled as the platform's whose project its database
does not know (the grant gains `list` on namespaces). `GET /installation/restore` (any member)
shows the marker, the differing services (desired generation and image against the cluster's), the
differing topics, the unknown namespaces, and 042's reconciliation result once 042 exists (a
`RestoreHold.reconcilers` seam, empty now). `POST /installation/restore/release` (platform admin)
sets `released_at` and `released_by` on the row and resumes projection; the journal of the
`Project`s and `Service`s is untouched, so the release is recorded where the hold is. The restored
cluster archives under its own line once released (the overlay's second step adds the `plugins`
block); the old cluster is kept.

**Rationale**: there is no control plane to ask, so the procedure is manifests, and an overlay is
the one shape every installation can apply (`kubectl apply -k` is the whole deploy). The marker is
in the database because the restored database is what the control plane has; a marker in the
cluster (an annotation) would need a grant the control plane does not have. The projector is the
one writer of desired state (`ServiceProjector` implements every writer trait), so holding it holds
everything.

**Verify first**: CNPG's `-app` Secret of a recovered cluster holds the owner's password from
`bootstrap.recovery.secret` when given; the overlay names the existing `ankka-controlplane-db-app`
Secret as the recovery `secret`, so the control plane's credential is unchanged. The control plane
restore suite (US6) runs the overlay as written against k3s, and the DDL's `ankka_restore_marker`
exists in a database bootstrapped from a backup because the control plane's DDL is in the backup.

## R20. Garage: three nodes as an installation choice, a secondary store by `rclone`, and the local platform unchanged

**Decision**: the `garage` component gains a `replicated` variant,
`kustomization/components/garage-replicated/`, which the cloud overlay lists in place of `garage`:
the same StatefulSet at `replicas: 3` without `--single-node`, `replication_factor = 3`, required
pod anti-affinity across nodes, and a Job `garage-layout` that, through the admin API on the first
pod, connects the three nodes (`ConnectClusterNodes`), stages a role per node with zone = the pod's
ordinal and the volume's capacity (`UpdateClusterLayout`), and applies it at `version + 1`
(`ApplyClusterLayout`) — idempotent, since a node with a role is skipped and a layout with nothing
staged is not applied. The local overlay keeps `garage` as it is (`--single-node`, one volume).

A secondary store is `kustomization/components/garage-copy/`: a CronJob at the installation's
interval (hourly as shipped), image `rclone/rclone` pinned, holding the admin token: each run lists
every bucket (`ListBuckets`), allows its own key `garage-copy` read-only on each (`AllowBucketKey`;
a flag set false leaves a permission unchanged, so a later `owner` grant by a service is untouched),
then `rclone sync garage:<bucket> secondary:<bucket>` per bucket, which deletes in the destination
what the source no longer holds (SC-011), and ends by patching the ConfigMap `garage-copy-status`
in `garage-system` with the completion time, the buckets copied and the objects deleted, or the
failure. The secondary's endpoint and credential are a Secret the installation creates out of band
(the cloud overlay deletes the development one, as it deletes Garage's). The control plane reads
that ConfigMap (a Role in `garage-system`: `configmaps` `get` by name) for the installation's status
and the copy metric (R10), and computes "backed up" under `ANKKA_BACKUP_COPY_REQUIRED` as "the last
completed copy is newer than the project's last base backup".

**Rationale**: Garage has no mirroring; `rclone sync` is the one widely used tool whose semantics
are exactly "the copy never holds what the source lacks". Per-bucket keys are Garage's model, and
the copy's key is allowed on each bucket by the job itself so the operator changes nothing. The
layout is the one imperative step Garage has, and a Job is how a kustomization carries one; the
local platform has none because `--single-node` needs none (034 R2).

**Verify first**: a `replication_factor = 3` cluster of three pods on one k3s node, with zones per
pod, holds each object on all three (`GetClusterLayout`, and reading the object after deleting one
pod and its PVC); `rclone sync` against Garage needs `--s3-upload-cutoff`/checksum settings as the
AWS SDK did (the trailing-checksum trap in `kubernetes.md`). Spike S5.

## R21. Installation status, and the two platform databases that do not exist yet

**Decision**: `GET /installation` (any authenticated user) and `ankka status` report: the backup
target (`none`, `object-store`), the retention floor, whether a copy outside the failure domain is
required, the secondary store's last completed copy and last failure (R20), whether backups share
the cluster's failure domain (Garage with no secondary store, FR-034), and the control plane's own
database: its line's last base backup and earliest restorable moment, read by the control plane
from the `ObjectStore` and `Cluster` in its own namespace through a Role there (`objectstores`,
`clusters` `get`). The read record (038) and the keyring (042) are backed up the same way when they
exist: each is a CNPG cluster in `ankka-controlplane` with an `ObjectStore` to a bucket of its own
(`platform.backups-read-record`, `platform.backups-keyring`), and the `postgres` component is where
their manifests go. The hooks are named here and built then.

Who mints the control plane's backup credential: **the operator, once at start.** It holds the store's
administrator token and `create` on Secrets in every namespace; the control plane holds neither. On
start with a target, `Operator` runs a platform reconcile that ensures `platform.backups-controlplane`
and offers `ankka-controlplane-db-backups` in `ankka-controlplane` by `create` (409 is success), exactly
as a project's is ensured, and repeats it on every resync so a Secret deleted by hand is replaced. The
`postgres` component's `ObjectStore` names that Secret; a cluster applied before the operator has run
waits on it, which CNPG reports as the sidecar not starting, and the installation's status shows the
control plane's line as not backed up until then.

## R22. Rolling the project reconciler's reads into the sweep, and the "in progress" lock

**Decision**: the project reconciler runs on its own queue already (`Operator.scala:44-46`); the
CNPG cluster informer (R9) and the `Backup` informer requeue it. A restore in progress is the
restore entry whose phase is not terminal; the control plane refuses a second from the entity's
state (`Project.restoreInProgress`), and the operator renders every entry it is given, so two
restores cannot race the operator either way. The rehearsal namespace's expiry sweep is the same
reconcile.

## R23. The console

**Decision**: the project page gains a "Database" section (backups per line, instances, clusters
and which service is on each, the restores with their reports, rehearsals) and the actions behind
the same routes; the service page gains the cluster it is on and "switch". Fixtures come from
`ControlPlaneFixturesSuite` as every wire type's do; the fake control plane learns the new routes.
The Playwright suite gains one journey: restore, read the report, switch one service, read the
status naming two clusters.

## R24. Where each scenario is tested

Every acceptance scenario is a `GherkinSuite` running one feature file whole on k3s, in
`controlplane`'s tests, modelled on `ObjectStorageClusterFeatures`, with a shared
`BackupClusterSteps` and a stack of CNPG 1.30.0, the plugin (R1), Garage (`ObjectStoreStack`) and,
for one suite, Strimzi (`BrokerStack`):

| Feature file | Suite | Needs | Notes |
|---|---|---|---|
| `databases/backups.feature` | `BackupsClusterFeatures` | Garage, plugin | the failure case makes Garage refuse writes by denying the key on the bucket, and times the status and the metric (the control plane's `/metrics` export is read from a fake collector as `telemetry-otlp`'s tests do) |
| `databases/restoring.feature` | `RestoringClusterFeatures` | Garage, plugin | two carts; writes on both sides of the moment (SC-001, SC-004); the owner/member outline runs offline too |
| `databases/after-a-restore.feature` | `AfterRestoreClusterFeatures` | + Strimzi | the ids and the counting view; the longest suite |
| `databases/replicas.feature` | `ReplicasClusterFeatures` | plugin | three instances on one node; deletes the primary's pod and PVC (SC-006) |
| `databases/rehearsals.feature` | `RehearsalsClusterFeatures` | Garage, plugin | the grant scenario mints the operator's token |
| `databases/control-plane-restore.feature` | `ControlPlaneRestoreClusterFeatures` | Garage, plugin | runs `kustomization/recovery/controlplane` as written |
| `object-storage/durability.feature` | `DurabilityClusterFeatures` | garage-replicated, a second Garage as the secondary | the local-platform scenario asserts the overlay offline |

Offline, before any cluster: `CrdSchemaSuite` (both resources' new fields), `CnpgRenderingSuite`
(every object above, golden), `RenderingUnchangedSuite` (no Deployment changes; a `Cluster` variant
with a target), `RestoreRenderingSuite`, `DatabaseQueriesSuite` (SQL text and parsing),
`BackupSettingsSuite`, `BackupNamesSuite` (`crd`), `ProjectEntity`'s and `ServiceEntity`'s suites
(refusals: window, in progress, not an owner, retention below floor, switch to a cluster without
the service's database), `EventCompatibilitySuite` (every new event field defaulted; a pre-feature
journal replays), `HistoryLinesSuite` and `MessageIdSuite` (testkit, a real Postgres: the line by
time, the same id twice, a new id after a line, a `ProduceAll` index), `DivergenceSuite` (the
runtime's answer against the in-memory broker and against Kafka in a container), `PoolRecoverySuite`
(a connection killed under the pool, `SELECT 1` on acquire), `ReservedSecretNamesSuite`,
`RemoteOverlaySuite` (both overlays set the five settings; the cloud overlay lists `garage-replicated`
and `garage-copy` and deletes the secondary's Secret; the local overlay lists `garage`),
`ControlPlaneRoutesReferenceSuite`, `CliReferenceSuite`, the console's unit and Playwright tests, the
docs build and the features check.

Checks that could pass while false, and what makes each fail: the backup failure scenario asserts
the status *changes* to failing after the key is denied and *clears* after it is restored, not that
a word is present; the switch scenario asserts the pod's `ANKKA_DB_HOST` and the restored cluster's
`pg_stat_activity` showing the service's role connected, not the resource's field; the id scenario
compares the `ce-id` header read from the topic by a plain Kafka consumer; the grant scenario asks
the API server with the operator's own token; the copy scenario asserts the object absent from the
secondary by listing it, after a copy whose status ConfigMap's time moved.

SC-007 (50 GiB verified in under an hour) is not a suite: it is a procedure in
`docs/operate/recovery.md` run on the reference installation, and the rehearsal's report records the
number.

## R25. Documentation

`docs/platform/databases.md` gains "Backups" (what, where, retention, the floor, replicas, the
status), "Restoring a project" (a restore, verification, the switch, switching back, what a restore
does not rewind, the message id and its limit, the kept cluster) and "Rehearsing"; its "Bring your
own database" paragraph drops "managed backups" as a reason. `docs/operate/recovery.md` is new: the
control plane's procedure step by step, reclaiming a left or abandoned cluster by hand (`kubectl
delete cluster` as a platform administrator, and what it deletes), re-issuing a backup credential,
the secondary store, and SC-007's measurement. `docs/platform/object-storage.md` gains the platform's
buckets and the three-node and secondary-store components. `docs/reference/limitations.md` drops
"one instance" and states what remains (FR-036) and R2's "no encryption on Garage". The CLI and
route reference pages are regenerated. The rendered skill picks up the new pages through
`just docs-sync`.

## Verify first, gathered

| Id | What | Where it is run |
|---|---|---|
| S1 | adding `plugins` and `archive_timeout` to a running `Cluster` rolls no pod | spike under `-Dankka.spikes=on`, `operator/testOnly *BackupSpike` |
| S2 | fabric8 `exec` through the k3s container; `env` beats `envFrom` | the same spike |
| S3 | recovery with `database: postgres` creates nothing; roles and both databases present | the same spike, second case |
| S4 | three instances, `required`, primary pod and PVC deleted: writes resume within 60 s | `ReplicasClusterFeatures`, written first |
| S5 | Garage at `replication_factor = 3` on one node; `rclone sync` against Garage | `DurabilityClusterFeatures`, written first |
| R5 | the plugin reads a re-issued credential without a restart | the backups suite's re-issue case |
| R9 | the `ContinuousArchiving` condition's reasons and its latency | the backups suite's failure case |
| R15 | `bind` by `resourceNames` passes the escalation check | the rehearsals suite's grant case |
| R16 | `EventEnvelope.timestamp` is `db_timestamp` | `MessageIdSuite` |
| R17 | `listConsumerGroupOffsets` under the service's ACL | `AfterRestoreClusterFeatures` |
| R19 | a recovered cluster's `-app` Secret and the marker's DDL | `ControlPlaneRestoreClusterFeatures` |
| R7 | lowering `retentionPolicy` keeps the latest base backup before the new window's edge, so no moment inside the window is lost until a newer base backup completes (barman's recovery-window retention) | the backups suite's retention case, on an `ObjectStore` with two base backups a minute apart and a window of one minute |
| SC-009 | a service's storage credential is refused by the backup bucket | the backups suite's last scenario |

## Verified during implementation

Run with `sbt -Dankka.spikes=on 'operator/testOnly *BackupSpike'` on 2026-10-09, k3s v1.35.1, CNPG 1.30.0, plugin v0.15.1, Garage v2.3.0.

- **S1, false as written.** Adding `plugins` and `archive_timeout` to a running cluster restarted its pod once (start time moved by 18 to 22 seconds across two runs). Archiving then worked: `ContinuousArchiving` went `True` with reason `ContinuousArchivingSuccess` within a minute, and a plugin `Backup` completed within 20 seconds, filling `serverRecoveryWindow["ankka-db"]` with `firstRecoverabilityPoint` and `lastSuccessfulBackupTime`. R7 is corrected.
- **S1, the policy.** The Garage policy needed the `cnpg.io/cluster` rule of R6; the spike ran with it.
- **S1, images.** The plugin and CNPG images (ghcr.io) pull inside the k3s container on the development Mac, though Docker clients there are refused by ghcr.io.
- **S2, true.** `exec` of `psql -U postgres -tA` in container `postgres` of an instance pod answers; a literal `env` value beats the same key from `envFrom`.
- **S3, true.** A cluster of two databases, recovered with `database: postgres`, `owner: postgres` and no `secret` to a moment between two writes, held both databases, both roles and `ankka_tls`, and exactly the source's databases (the source's `app` is CNPG's default, made because a project database has no `bootstrap`). Recovery took 120 seconds for a near-empty cluster.
- **S3, a moment after the archive.** A target an hour ahead left the cluster in `Setting up primary` (`Creating primary instance …`) after four minutes, neither healthy nor failed. So the platform refuses a moment after the archive's last commit before asking (FR-010), and a restore still setting up after 30 minutes is reported `Failed` with the phase reason (R12).

### Where the implementation departs from the plan

- **A restore gets no `Database` or `DatabaseRole` objects.** Its roles and databases come with the restored data, and CNPG objects of the same names on a second cluster in one namespace collide. A switched service's resource names the cluster it is on and nothing else.
- **A switch during a rollout is not held.** Kubernetes replaces an in-progress rollout with the newer template, so the switch's generation simply supersedes it; there is no `switch pending` state, and the switch route answers 200 at once (T041's 202 case is not tested).
- **A restore still setting up after 30 minutes is not yet reported `Failed`.** The operator reports `Restoring` until the cluster is healthy; the time-based failure of R12 is open work.
- **The retention check of "verify first" cannot run in a suite.** Retention is counted in days, so a suite cannot watch a base backup expire; the rendering of `retentionPolicy` is tested offline instead.
- **What a restore cannot rewind is asked as the restore is read, not journaled when it is verified.**
  The broker keeps growing after the moment, so a number kept from verification would be out of date
  when read. `GET /projects/{id}/restores/{name}` asks each service present in the restore over the
  observe port; a switch records no divergence of its own.
- **A restore and a rehearsal not healthy within `restoreTimeout` (30 minutes) are reported `Failed`**,
  which closes the gap the first implementation left; the rehearsal suite runs with three minutes.
- **The control plane's restore points the Deployment at the restored cluster by hand or by an
  installation's own overlay.** A kustomization cannot patch a Deployment it does not contain, so
  `deployment-patch.yaml` is a strategic merge patch for an installation's overlay, and the procedure
  gives `kubectl set env` for one deployed by hand. The recovery kustomization holds the cluster and
  the marker Job.
- **A release sets `released_at` and `released_by` on the marker rather than deleting it**, so the
  release is recorded where the hold was.
- **`garage-replicated` changes the `garage` component rather than replacing it.** The StatefulSet's
  governing Service is not headless and cannot be made so on a running installation, so the variant
  patches replicas, layout and replication factor, and adds a headless `garage-peers` whose name
  answers every pod's address for the layout Job. If every pod restarts at once, the Job is run again.
- **The console reads every new route**, which its parity check requires: the project page's database
  section, rehearsals and history, and the front page's installation and hold.
- **Not run on k3s in this implementation**: the backups, restoring, replicas, rehearsals, after-a-restore,
  control plane restore and durability suites. The backups suite's first local run failed in every
  scenario because CNPG's operator was killed by its own health check on an overloaded node (see
  `kubernetes.md`); the suites are left for the `cluster` workflow.
- **`GET /installation` is shared with feature 044**, which added the route on `main` for the
  installation's version and cloud. The backups are its `backups` field (`Installation.backups`, an
  `InstallationStatus`) rather than the whole answer; `ankka status` prints that field, and
  `ankka installation` with no subcommand prints the installation, beside `ankka installation restore`.
