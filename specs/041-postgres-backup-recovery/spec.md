# Feature Specification: Backup and Recovery — Point-in-Time Restore for Every Database the Platform Provisions

**Feature Branch**: `041-postgres-backup-recovery`

**Created**: 2026-10-08

**Status**: Draft

**Input**: User description: "Back up and recover the databases the platform provisions. Each project's
Postgres and the control plane's own are archived continuously so a project can be restored to any moment
within a retention window; a project can ask for replicas with automatic failover; backups go to the
installation's object store in a bucket no service can reach; a member restores a project into a new
database, verifies it and then switches services to it one at a time, keeping the old one. Object storage on
Garage is covered too. Kafka is not backed up: its durability is replication (043). Restores must be
rehearsable, and a failed backup must be visible. The first user is a regulated real-money operator
(eitheror's casino and payments), whose ledgers live in these journals."

## Context

The operator provisions one CloudNativePG `Cluster` per project, `ankka-db` in `ankka-<project>`
(`CnpgRendering.projectCluster`), and a `Database`, a `DatabaseRole` and a credential Secret per service
inside it. The cluster is rendered with `instances = 1` and `settings.databaseStorageSize` (1Gi by default,
`ANKKA_OPERATOR_DATABASE_STORAGE_SIZE`). `ClusterSpec` in `operator/.../cnpg/PostgresCluster.scala` models
six of CNPG's fields — instances, storage, bootstrap, certificates, `postgresql.pg_hba` and managed roles —
and has nowhere to say anything about a backup. The control plane's own database is a separate CNPG
`Cluster`, `ankka-controlplane-db` in `ankka-controlplane`, rendered by the `postgres` component from
`kustomization/components/postgres/cluster.yaml` with `instances: 1` and `bootstrap.initdb`.

Nothing anywhere archives a write-ahead log, schedules a base backup or names an object store for one. A
repository-wide search finds no restore, point-in-time recovery or disaster-recovery design in any spec or
doc. Every store the platform runs is one instance: the project's Postgres, the control plane's Postgres,
the broker (one KRaft node, every replication factor 1) and the object store (one Garage node,
`replication_factor = 1`, a 10Gi volume). Losing a node's volume loses everything every service in that
project ever persisted — for a casino, every wallet's journal, which is the ledger.

What the platform does protect is deletion. The operator's grant on `postgresql.cnpg.io` has no `delete`
verb, the CNPG objects carry no owner reference and reclaim `retain`, and a service re-applied under a
deleted name reports `recovered existing data`. That guards against a person; it does nothing against a
disk, a node, a zone, a bad migration or a bug that wrote nonsense for an hour. `docs/platform/databases.md`
says so and sends anyone who needs "replicas or managed backups" to supply their own database through
`ANKKA_DB_*`, at which point the platform provisions nothing, issues nothing and backs up nothing.

Five decisions shape this feature.

- **Point-in-time recovery, not snapshots.** Every provisioned Postgres archives its write-ahead log
  continuously and takes a scheduled base backup, so a project can be restored to any moment within the
  retention window, not to last night. A journal is the system of record; losing a day of it is losing a
  day of money.
- **Backups belong to the platform, not the service.** They go to the installation's object store — Garage,
  or Google Cloud Storage once 039 lands — in one bucket per project that the platform creates and no
  service credential reaches. A compromised service can neither read its project's history nor delete it.
- **A restore makes a new database; it never overwrites one.** An owner restores a project to a moment into
  a new cluster beside the current one, the platform verifies it, and only a separate, explicit switch moves
  a service onto it. Postgres restores a cluster, so the restore is the whole project's; the switch is per
  service, because each service's database host is its own credential Secret already. A wallet that is fine
  stays where it is while a rewards service that is not moves to the restored cluster. The cluster a service
  leaves is kept, under the platform's rule that nothing is destroyed. A restore that turned out to be the
  wrong moment is undone by switching back. A restore is not a rollback (033): a rollback runs an earlier
  image against the current data; a restore runs the current image against earlier data.
- **Replicas are a project's choice.** The default stays a primary alone, which is right for development and
  pilots. A project may ask for replicas, with automatic failover, and may ask for synchronous replication for a
  money path, accepting the write latency it costs.
- **The platform reports what a restore cannot rewind; it does not rewind it.** A restore rewinds a
  service's journals and its projection offsets. Kafka is not restored, and other projects are not rewound.
  The platform records the restore point, gives a re-published event the id it had, and lists exactly which
  topics and consumer groups may disagree with the restored database. Topic-sourced views and consumers in
  this platform are at-least-once and do not deduplicate by id, so a re-published event is applied again by
  every one of them, in every project; only a consumer that deduplicates — an external one, or one keyed by
  024's subject — is protected by the id. What to do about the rest is the member's call.

What this feature is not: a backup of Kafka (043 makes the broker replicated); a backup of a database a
service supplies through `ANKKA_DB_*`; restoring one service to a different moment from the rest in the same
cluster (a restore is one cluster at one moment; what is per service is the switch); cross-region replication
of a live database; a major-version upgrade of Postgres; or a backup of the Kubernetes objects the platform
writes (namespaces, Secrets, certificates), which an installation's own cluster backup covers.

## Clarifications

### Session 2026-10-08

- Q: Which stores must backup and recovery cover? → A: Every project's database, the control plane's
  database, and object storage on Garage. Kafka is not backed up; its durability is replication, in 043.
  A database a service supplies through `ANKKA_DB_*` is its owner's to back up.
- Q: What recovery point should a project database support? → A: Point-in-time recovery: the write-ahead
  log is archived continuously and base backups are taken on a schedule, so a project can be restored to any
  moment within its retention window.
- Q: Should a project database run replicas with automatic failover? → A: Opt-in per project. The default
  stays one instance; a project may ask for more instances with automatic failover, and may ask for
  synchronous replication for money paths.
- Q: Where do backups go, and how long are they kept? → A: The installation's object store (Garage, or
  Google Cloud Storage per 039), in a bucket per project that the platform owns and services cannot reach.
  Retention is set per installation, 30 days as shipped, and a project may override it.
- Q: Who restores, and to what? → A: An owner restores a project to a point in time into a new project
  database, verifies it, then switches services to it. The old database is kept. The restore is the whole
  project's cluster; the switch is per service (review session below).
- Q: After a restore the journal and projection offsets rewind, but Kafka still holds messages produced from
  the lost events. What should a restore do? → A: Report the divergence and let the member decide. The
  restore records the restore point; publishers resume from their restored offsets and re-produce,
  idempotently by event id; the restore's status lists the project's topics that may hold messages newer
  than the restore point. The platform does not touch topics.

### Session 2026-10-08 (review)

- Q: Is the switch per project or per service? → A: Per service. A restore recreates the project's whole
  cluster at one moment, but each service's database host is its own credential Secret, so an owner moves one
  service at a time and leaves the rest on the live cluster. Two clusters then serve one project until every
  service has moved or the restored cluster is abandoned. The report is per service.
- Q: What is the "line of history" a message id names? → A: A value the platform writes at restore time,
  never Postgres's timeline id, which also changes on every failover promotion. It is kept in a
  restore-boundary table — the line's id and, per entity, the sequence at which the line began — added under
  the additive-schema rule. A message id derives from the line, the entity and the sequence. *Revised in
  planning (research R16):* the line is kept by the moment it began, and an event belongs to the latest line
  that began no later than the event's own time; the per-entity boundary is not needed.
- Q: Does the id make re-publication harmless? → A: No. Topic-sourced views and consumers are at-least-once
  and do not deduplicate by id, so re-published events re-apply everywhere and a counting consumer counts
  twice. The id helps only consumers that deduplicate. The member assesses the rest from the report.
- Q: How does a rehearsal remove its database when the operator may delete nothing? → A: Rehearsals run in
  a namespace per project, `ankka-<project>-rehearsal`, where the operator holds a delete on CNPG clusters
  and nothing else, and a rehearsal database has a time to live. A restored cluster in the project's
  namespace that was never switched to is listed with its age and reclaimed only by a platform administrator
  by hand.
- Q: What archives, and with what credential? → A: CloudNativePG's Barman Cloud plugin, an installation
  component with its own grant. Each project's backup bucket gets a credential minted for it — by the
  operator on Garage as 034 does, through 044-cloud-provider's platform-bucket request on Google Cloud
  Storage — into a Secret the project's database instances read. Rotation is a re-issue.
- Q: Are backups encrypted at rest? → A: Yes, under one key per installation that no service reaches, so a
  backup is unreadable without it. Revised in the clarify session below: the store encrypts, not the archiver.
- Q: Must a real-money installation copy its backups off the cluster? → A: An installation may require it:
  with the requirement set, a project is not "backed up" until a copy exists outside the failure domain. Off
  as shipped. The copy mirrors deletions, so a deleted object does not outlive its deletion in the copy.
- Q: How does a restored control plane know it was restored? → A: The procedure writes a marker row the
  control plane reads at start. Its difference listing includes namespaces in the cluster its database does
  not know, which are projects created after the restore point. It reconciles 042's erasure log from the
  log's bucket copy before the hold is released.
- Q: Which other stores are backed up here? → A: 038's read record and 042's keyring database, each as a
  store in its own right, the keyring's in a bucket of its own. After a restore, services replay 042's
  erasure log — only a service knows its view schema — and a switched service is not ready until it has.

### Session 2026-10-08 (clarify)

- Q: FR-003b has the archiver encrypt before upload, but the Barman Cloud plugin encrypts server-side only
  (SSE-S3 or SSE-KMS on S3, a KMS key on Google Cloud Storage). Which encryption is meant? → A: Server-side
  under a key the installation holds: SSE-C on Garage with a key from 038's secret store, 044's installation
  key on Google Cloud Storage. The bucket's credential alone reads nothing; the key is never in the bucket.
  *Revised in planning (research R2):* the archiver sends SSE-S3 or SSE-KMS only and Garage accepts SSE-C
  only, so on Garage the platform encrypts no backup, stated as a limitation; on Google Cloud Storage the
  bucket is encrypted under 044's installation key.
- Q: Does a switch roll the service, or does the service wait for its next deploy to move? → A: The switch
  starts a rolling update of the service at once; the service is on the new database when that update
  completes. "Next rollout" means the one the switch starts.
- Q: Does the project's database setting count instances or replicas? → A: Replicas, none by default: the
  glossary's and the features' word. "Two replicas" is a primary and two replicas; "0 instances" cannot be
  said.
- Q: How often is Garage copied to the secondary store as shipped? → A: Hourly. The copy interval is the
  recovery point for the loss of the whole cluster on a Garage installation.
- Q: May a project's retention override be shorter than the installation's? → A: No. The installation's
  retention is a floor: a project may keep its backups longer, never for less time, and a shorter value is
  refused naming the installation's.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Every project's database is archived continuously, and a failure is seen (Priority: P1)

An installation names where backups go. From then on every project's database, and the control plane's,
archives its write-ahead log continuously and takes a base backup on the installation's schedule, with no
change to any descriptor. A project's status says when its last base backup completed, how far back it can
be restored and how far behind its archive is — the archive's lag is the recovery point the project actually
has, and it is exported as a metric. When archiving stops — the bucket is unreachable, a
credential is refused, a volume fills — the project's status says so and a metric says so, before anyone
needs the backup.

**Why this priority**: Everything else in this feature restores from what this story writes. A backup that
silently stopped last Tuesday is discovered on the day it is needed, which is the worst day to discover it.

**Independent Test**: In a k3s suite with an object store and a backup target configured, deploy a service,
write to it, and assert the project's status reports a completed base backup and an archive lag under the
bound. Make the target refuse writes and assert the status and the metric both report the failure within the
bound, then restore the target and assert both clear.

**Acceptance Scenarios**:

- added `features/databases/backups.feature`: the first service of a project is deployed and its project database is backed up from then on
- added `features/databases/backups.feature`: a project that existed before the installation had a backup target is backed up without a redeploy
- added `features/databases/backups.feature`: a backup target that refuses writes is reported on the status and as a metric within 5 minutes
- added `features/databases/backups.feature`: the database of the control plane is backed up as a project database is
- added `features/databases/backups.feature`: a database a service declares of its own is not backed up by the platform
- added `features/databases/backups.feature`: an installation with no backup target deploys as before and says that nothing is backed up
- added `features/databases/backups.feature`: a storage credential of a service reaches no backup bucket

---

### User Story 2 - An owner restores a project to a moment and switches a service to it (Priority: P1)

A bad release of the rewards service wrote wrong balances for forty minutes; the wallet beside it is fine.
An owner of the organization restores the project to the minute before the release, into a new database
beside the current one. The platform reports the restore complete and verified: every service's database
present, the moment reached, and for each service the journal's highest sequence and its row counts. The
owner reviews it and switches the rewards service, and only it. The switch rolls rewards onto the restored
database then and there; the wallet keeps writing to the live one. The project now runs on two clusters, and its
status says so, until the owner moves the rest or abandons the restored cluster. The cluster a service left is
kept, and switching back is the same action.

**Why this priority**: This is what the backups are for. A backup nobody can restore from, or can only
restore by overwriting the evidence, is not recovery — and a restore that rewinds a healthy ledger to repair a
broken one beside it is an incident of its own.

**Independent Test**: In a k3s suite, deploy two services, write A to both, note the time, write B to both,
restore the project to the noted time, and assert the restored database holds A and not B for each while the
current database still holds both. Switch one service; assert it reads A only and the other still reads A and
B. Switch it back; assert it reads A and B.

**Acceptance Scenarios**:

- added `features/databases/restoring.feature`: a restore makes a new project database beside the current one and changes nothing in the current one
- added `features/databases/restoring.feature`: a restore to a moment that cannot be restored to is refused with the moments that can
- added `features/databases/restoring.feature`: a completed restore reports the moment it reached and what each service's database holds
- added `features/databases/restoring.feature`: switching one service to a restore moves that service and no other, and is recorded
- added `features/databases/restoring.feature`: a switched service is switched back to the project database it left, with every write that project database had
- added `features/databases/restoring.feature`: a member who is not an owner can neither restore nor switch
- added `features/databases/restoring.feature`: a second restore of a project is refused while one is in progress
- added `features/databases/restoring.feature`: a restore a service was switched to is archived as a line of history of its own
- added `features/databases/restoring.feature`: the status of a project whose services are on two project databases names each service's
- added `features/databases/restoring.feature`: a project database every service has left is listed as left, and the platform never removes it
- added `features/databases/restoring.feature`: a restore no service was switched to is listed with its age, and only a platform administrator removes it by hand

---

### User Story 3 - A restore says what it could not rewind, and re-publication is safe (Priority: P1)

After a restore the project's journals are back at the restore point, and so are the offsets of the
projections that publish from them. The broker is not: messages published from the lost events are still on
the topics, other projects may have consumed them, and the project's own Kafka consumer groups have committed
offsets past what the restored database reflects. The restore's report lists every topic the project
publishes to with the latest message newer than the restore point, and every consumer group of the project
whose committed position is newer than it. A publisher that re-publishes an event already published gives it
the same id; an event written after the restore never shares an id with one written before it. The report
also says what the id does not do: every topic-sourced view and consumer in the platform applies a
re-published event again, so a view that counts will count it twice.

**Why this priority**: Without this a restore silently forks the system of record: a deposit the brand's
wallet no longer has is still on the payments topic, and a downstream consumer that deduplicates by id would
drop a new event that happens to reuse an old sequence number. The member cannot decide what to do about a
divergence nobody showed them, or about a double count nobody warned them of.

**Independent Test**: In a k3s suite with the broker, deploy a service that publishes its events to a topic
and a consumer in the same project that counts them. Publish events 1–10, note the time after 5, restore to
it and switch. Assert the report names the topic and the consumer group; assert events re-published from the
restored offsets carry the ids they carried before; publish a new event and assert its id differs from event
6's though its sequence number is the same.

**Acceptance Scenarios**:

- added `features/databases/after-a-restore.feature`: a completed restore lists the topics and the groups that are newer than the restore point
- added `features/databases/after-a-restore.feature`: an event published again after a restore carries the message id it carried before
- added `features/databases/after-a-restore.feature`: an event recorded after a restore carries a message id no message published before it carried
- added `features/databases/after-a-restore.feature`: a restore and a switch do nothing to the broker but list it
- added `features/databases/after-a-restore.feature`: a view that counts messages counts an event published again a second time, and the restore says so
- added `features/databases/after-a-restore.feature`: a replica promoted with no restore keeps the line of history, and the message ids stay on it

---

### User Story 4 - A project asks for replicas and survives losing its primary (Priority: P2)

A project that carries money asks for two replicas of its database with synchronous replication. When the
node holding the primary is lost, a replica is promoted, the project's services reconnect, and no acknowledged
write is lost. Another project leaves the setting alone and keeps a primary alone.

**Why this priority**: Recovery restores what was lost; replicas mean it is not lost. For a wallet a restore
is the last resort, not the first, but recovery comes first because without it a replica faithfully copies
a bad write.

**Independent Test**: In a k3s suite with more than one node, set a project to two replicas with
synchronous replication, write continuously from a service, delete the primary's pod and its volume, and
assert a replica is promoted, writes resume within the bound, and every write the service was told succeeded
is present.

**Acceptance Scenarios**:

- added `features/databases/replicas.feature`: a member asks for replicas and the project database runs them
- added `features/databases/replicas.feature`: a lost primary is replaced by a promoted replica and the services go on writing without a redeploy
- added `features/databases/replicas.feature`: a synchronous project database loses no acknowledged write with its primary
- added `features/databases/replicas.feature`: a project that asks for no replicas runs a primary alone
- added `features/databases/replicas.feature`: lowering the number of replicas keeps the primary and everything recorded

---

### User Story 5 - A restore is rehearsed without touching the project (Priority: P2)

Once a month, and before any release that migrates a schema, a member rehearses a restore of the
payments project to an hour ago. The platform restores into a throwaway database no service connects to,
verifies it as a real restore is verified, reports how long it took, and removes it. The report is kept, so
the organization can show a regulator that recovery is tested, when, and how long it takes.

The throwaway database lives in a namespace of its own, `ankka-<project>-rehearsal`, because the operator
holds no delete on a database anywhere else, by design. In that namespace, and only there, it may delete a
cluster; and a rehearsal database that is not removed when its rehearsal ends is removed when its time to
live runs out.

**Why this priority**: An untested backup is a hypothesis. Rehearsal is how the time to recover becomes a
measured number rather than a guess, and it costs the project nothing.

**Independent Test**: In a k3s suite, request a rehearsal for a project with backups; assert a throwaway
database is created in the rehearsal namespace and verified, the report holds the moment, the per-service
verification and the elapsed time, the throwaway database is then removed, and the project's own database
and services saw no change. Assert the operator's grant allows a delete of a cluster in the rehearsal
namespace and refuses one in the project's.

**Acceptance Scenarios**:

- added `features/databases/rehearsals.feature`: a rehearsal restores into a project database of its own, checks it, times it and removes it
- added `features/databases/rehearsals.feature`: a rehearsal's report is kept on the project and listed
- added `features/databases/rehearsals.feature`: a project set to rehearse every day rehearses every day, and a failed rehearsal is reported as a backup failure is
- added `features/databases/rehearsals.feature`: a rehearsal changes nothing of the project's services, project database or backups
- added `features/databases/rehearsals.feature`: a rehearsal's project database the platform failed to remove is removed when its time to live passes
- added `features/databases/rehearsals.feature`: the operator may remove a project database made for a rehearsal and no other

---

### User Story 6 - The control plane's database is restored (Priority: P2)

The control plane's own database is lost. A platform administrator restores it to a moment with the
installation's tooling, since there is no control plane to ask; the procedure's last step writes a marker row
that tells the control plane, at its next start, that it is running on a restored database. The restored
control plane does not push its older picture of the world over the services running now: until a platform
administrator releases it, it projects nothing to the cluster, and it lists every service whose desired state
differs from what is running, and every namespace in the cluster its database has no project for — a project
created after the restore point. Before the release it brings its copy of 042's erasure log up to date from
the log's copy in the bucket, so an erasure filed after the restore point is not forgotten by the restore.

**Why this priority**: The control plane holds organizations, projects, members, topic declarations and
every service's desired state. Losing it does not stop running services, but it stops every change. A
restore that immediately re-projected an hour-old desired state would roll back every deploy made in that
hour, which is a second incident.

**Independent Test**: In a k3s suite, deploy service S at version 1, note the time, deploy version 2, then
restore the control plane's database to the noted time. Assert the control plane starts with projection held,
lists S as differing (1 desired, 2 running), and that S keeps running version 2 until the hold is released.

**Acceptance Scenarios**:

- added `features/databases/control-plane-restore.feature`: a platform administrator restores the database of the control plane and the control plane starts held
- added `features/databases/control-plane-restore.feature`: a held control plane lists what differs from what it recorded and changes none of it
- added `features/databases/control-plane-restore.feature`: a held control plane brings its erasure log up to date from the bucket before it can be released
- added `features/databases/control-plane-restore.feature`: releasing the projection resumes it, removes the restore marker and is recorded

---

### User Story 7 - Objects on Garage survive the loss of a node (Priority: P3)

An installation that keeps its objects on Garage rather than Google Cloud Storage runs Garage on three
nodes, each object on all three, and names a second store outside the cluster to which every bucket — the
services' and the backup buckets alike — is mirrored on a schedule, hourly as shipped: an object deleted in
Garage is deleted in the copy at the next run, so the copy never holds what the installation has erased. Losing one Garage node
loses nothing; losing the cluster loses at most one copy interval of objects, and none of the database archive
older than that. A real-money installation sets the requirement that a project is not "backed up" until its
backups have a copy outside the failure domain; with it set and no secondary store, every project's status
says it is not backed up.

**Why this priority**: For a Garage installation the database backups live in Garage. A single-node Garage
in the same cluster as the databases it protects shares their fate: a backup in the same failure domain is
not a backup. A real-money installation on Google Cloud uses Google Cloud Storage (039), whose durability is
Google's, so this matters for installations that cannot.

**Independent Test**: In a k3s suite with three Garage nodes and a secondary store, put objects in a
service's bucket, remove one Garage node with its volume, and assert every object is still read. Wait one
copy interval and assert the secondary store holds every object of every bucket, including the project's
backup bucket.

**Acceptance Scenarios**:

- added `features/object-storage/durability.feature`: an object written to a Garage of three machines is held on every one of them
- added `features/object-storage/durability.feature`: losing one machine of a Garage of three and its volume loses no object
- added `features/object-storage/durability.feature`: every bucket of the installation's Garage is copied to the secondary store, and the status says when
- added `features/object-storage/durability.feature`: an installation that keeps its backups on Garage with no secondary store says that they share the cluster's failure domain
- added `features/object-storage/durability.feature`: the local platform keeps Garage on one machine with no secondary store
- added `features/object-storage/durability.feature`: an object deleted from Garage is deleted from the secondary store at the next copy
- added `features/object-storage/durability.feature`: with a copy outside the failure domain required, a project is not backed up until its latest base backup has one

---

### Edge Cases

- A restore to a moment before a service existed: that service's database is restored empty or absent as it
  was then. The report says so, and a switch of that service is refused, so a service is never started on
  nothing; the other services can still be switched.
- A restore to a moment before a schema change the running image depends on: the platform restores the data
  as it was and the schema files the operator applies on every pass are additive (`kubernetes.md`, Schema), so
  the restored database is brought forward on first start. A service image older than the restored data is
  the member's to redeploy.
- A service added to the project after a restore is provisioned in the live cluster; a switch of it to the
  restored cluster is refused, since it has no database there, and says so.
- A service switched to a restored cluster is then switched back: the live cluster holds the writes it had
  before the switch and none made on the restored one; the report of the switch back says so, and lists the
  topics those writes published to, as a restore's report does.
- The retention window shortens — the installation lowers its floor, or a project drops an override that was
  longer: base backups and archive older than the new window are removed only after a newer base backup has
  completed, so the restorable window never has a gap.
- A project with backups is deleted from the control plane: its backup bucket is kept, like its database,
  and the platform never removes it.
- A restored database's secret store (023, 038's Postgres backend) holds every service secret as it was at
  the restore point. A credential rotated after that moment returns to its earlier value. The restore's
  report lists every service secret whose last change is newer than the restore point, by name, never value.
- A personal-data erasure (042) applied after the restore point: the per-subject key was destroyed outside
  the project's database, so the restored events stay unreadable; each service switched to the restored
  database replays the erasure log itself — only it knows its view schema — before it reports ready, so
  nothing recorded about the erased subject reappears in state or views.
- A switch while the service is mid-rollout: the switch is accepted and recorded at once; the platform
  applies it to the running service only when the rollout in progress has finished, and then rolls the
  service again.
- The backup key is lost (Google Cloud Storage): every backup is unreadable, and the installation's status
  says so as a backup failure; the key is 044's installation key, whose loss 044 covers. It is never in a
  backup bucket.
- The object store is lost along with the cluster: every backup is gone with it unless the installation's
  object store is outside the cluster (Google Cloud Storage) or Garage copies to a secondary store (User
  Story 7). The installation's status says which applies.
- A project with replicas asks for synchronous replication with only one healthy replica: writes wait
  for it, as synchronous replication means, and the status reports the replica that is holding writes.

## Requirements *(mandatory)*

### Functional Requirements

**Archiving and base backups**

- **FR-001**: An installation MUST be able to name a backup target: its object store (Garage, or Google
  Cloud Storage per 039), set once as an operator setting alongside `ANKKA_OBJECT_STORE_*`. With none named,
  nothing is archived and every project's status MUST say so.
- **FR-002**: With a target named, every project database the operator provisions, the control plane's
  database, 038's read record and 042's keyring database MUST archive their write-ahead logs continuously and
  take a base backup on a schedule the installation sets (daily as shipped). No descriptor field asks for it
  and none can turn it off.
- **FR-002a**: Archiving MUST be done by CloudNativePG's Barman Cloud plugin, installed as a component of the
  installation with a grant of its own; the operator's grant gains only what rendering the plugin's resources
  needs, and no delete.
- **FR-003**: The platform MUST keep each project's backups in one bucket per project that it creates in the
  backup target, named so no service's bucket can collide with it, reachable by the project's database
  instances and the platform and by no service credential. The control plane's backups, the read record's and
  the keyring's MUST each go to a bucket of their own.
- **FR-003a**: Each backup bucket MUST have a credential minted for it alone — by the operator on Garage, as
  034 mints a service's; through 044-cloud-provider's request for a platform bucket on Google Cloud Storage —
  written into a Secret the database instances read and nothing else does. A member MUST be able to re-issue
  it, and the old credential MUST stop working when the new one is in use.
- **FR-003b**: On Google Cloud Storage, every base backup and archived segment MUST be stored encrypted
  under the installation key 044 names, which encrypts every bucket the cloud provider makes, so a backup
  bucket's contents are unreadable with the bucket's credential alone; the key MUST never be written to a
  backup bucket. On Garage the platform encrypts no backup — the archiver offers only the encryption modes
  Garage lacks (research R2) — and `docs/reference/limitations.md` MUST say so, with the installation's
  volume encryption named as what protects a backup there.
- **FR-004**: Retention MUST be an installation setting (30 days as shipped) that is a floor: a project
  may set a longer window of its own, and a shorter one MUST be refused naming the installation's. The
  platform MUST remove base backups and archive older than the window only once a
  newer base backup has completed, so every moment inside the window stays restorable.
- **FR-005**: A project's status MUST report: whether it is backed up, the time of its last completed base
  backup, the earliest and latest moments it can be restored to, the archive's lag, and, when backups are
  failing, the reason. With the installation requiring an off-cluster copy, "backed up" is as FR-033a
  defines it.
- **FR-006**: A failure to archive or to complete a base backup MUST appear on the project's status within
  five minutes, and as a metric labelled with the project, exported through the installation's telemetry
  (026). The archive's lag MUST be exported as a metric too, as the recovery point the project actually has.
- **FR-007**: A project that existed before the target was named MUST begin archiving without its services
  being redeployed and without losing a write.
- **FR-008**: A service that supplies its own database through `ANKKA_DB_*` MUST NOT be backed up by the
  platform, and its status MUST say its database is its owner's to back up.

**Restore and switch**

- **FR-009**: An owner of the project's organization MUST be able to restore a project to a moment within its
  retention window. The restore MUST create a new database cluster in the project's namespace from the latest
  base backup before that moment and the archive up to it, and MUST NOT change the current database.
- **FR-010**: A restore to a moment outside the window, or in the future, MUST be refused with the earliest
  and latest moments that can be restored. A second restore of a project MUST be refused while one is in
  progress.
- **FR-011**: A completed restore MUST report the moment reached and, per service of the project: whether its
  database is present, the row counts of its journal, durable state, projection offset and timer tables, and
  the highest journal sequence it holds. A failed restore MUST report why.
- **FR-012**: A service switched to a restored database MUST replay 042's erasure log against its own tables
  before it reports ready, so that no subject erased after the restore point has state or view rows in it;
  the switch MUST NOT report the service ready until it has. The platform replays nothing on a service's
  behalf; only the service knows its view schema.
- **FR-013**: An owner MUST be able to switch one service of the project to a completed restore, and each
  service separately. A switch MUST start a rolling update of that service at once, and the service MUST be
  on the restored database when that update completes; no other service MUST move with it. A switch of a service that has no database in the restore MUST be refused,
  naming it.
- **FR-013a**: A project's status MUST name the cluster each service is on, and MUST say when its services
  are on more than one cluster.
- **FR-014**: The cluster a service leaves MUST be kept, listed on the project with when each service left
  it, and available as the target of a switch back. A restored cluster no service was switched to MUST be
  listed with its age. The platform MUST NOT delete either; reclaiming one is a platform administrator's
  action outside the platform, and the documentation MUST say how.
- **FR-015**: A restore and a switch MUST each be recorded in the project's audit with who did it, when, the
  service and the moment restored to.
- **FR-016**: A restored cluster with a service switched to it MUST be archived from then on as a new line of
  history; every earlier line MUST stay restorable for its retention.
- **FR-017**: A member who is not an owner MUST be refused a restore and a switch.

**What a restore cannot rewind**

- **FR-018**: A completed restore MUST list each topic the project's services publish to whose latest message
  is newer than the restore point, with the number of such messages per partition, and each consumer group
  of the project whose committed position is newer than the restore point.
- **FR-019**: A message published from a journal event MUST carry an id derived from the event's line of
  history, its entity and its sequence number, so that re-publishing the same event after a restore gives the
  same id.
- **FR-020**: The line of history MUST be a value the platform writes when a service first starts on a
  cluster, kept in a table in the service's database — the line's id and the moment it began — added under
  the additive-schema rule; an event belongs to the latest line that began no later than the event's own
  recorded time (research R16). It MUST NOT be Postgres's timeline, which changes on every failover
  promotion. An event persisted after a restore MUST therefore never share an id with an event persisted
  before it, even with the same entity and sequence number, and a failover MUST NOT change the ids of
  events published after it.
- **FR-020a**: The restore's report, and `docs/platform/databases.md`, MUST state that topic-sourced views and
  consumers in the platform apply a re-published event again, and that the id protects only a consumer that
  deduplicates by it.
- **FR-021**: A restore and a switch MUST NOT publish, delete or move anything on the broker. The listing in
  FR-018 is the platform's whole action on topics.
- **FR-022**: A completed restore MUST list, by name and never value, each service secret held in the
  project's databases whose last change is newer than the restore point.

**Rehearsal**

- **FR-023**: Any member of the project's organization MUST be able to request a rehearsal to a moment. The
  platform MUST restore into a database in the project's rehearsal namespace, `ankka-<project>-rehearsal`,
  that no service is switched to, verify it as FR-011 does, record the elapsed time, and then remove that
  database. The project's services, current database and backups MUST be unchanged by it.
- **FR-023a**: The operator's grant MUST allow a delete of a database cluster in a rehearsal namespace and
  nowhere else. A rehearsal database MUST carry a time to live (24 hours as shipped) after which it is removed
  whether or not its rehearsal ended cleanly, and a removal that did not happen at the rehearsal's end MUST be
  reported.
- **FR-024**: Every rehearsal's report MUST be kept on the project — who asked, when, the moment, the
  outcome, the verification and the elapsed time — and the reports MUST be listable.
- **FR-025**: A member MUST be able to give a project a rehearsal schedule; a scheduled rehearsal that fails
  MUST be reported as a backup failure is (FR-005, FR-006).

**Replicas**

- **FR-026**: A member MUST be able to set how many replicas a project's database runs, none by default, and
  to ask for synchronous replication. The setting MUST be held on the project, as its topic declarations are, not
  on any service's descriptor.
- **FR-027**: With at least one replica, the platform MUST fail over to a replica automatically when the
  primary is lost, and the project's services MUST resume without a redeploy: a service's connection pool
  MUST drop connections to the lost primary and reconnect to the promoted one on its own, within the bound
  SC-006 sets.
- **FR-028**: With synchronous replication, a write MUST NOT be acknowledged until at least one replica holds
  it.
- **FR-029**: The project's status MUST report how many of the primary and its replicas are ready, which is
  the primary, and, with synchronous replication, whether writes are waiting on a replica.

**The control plane's database**

- **FR-030**: The installation MUST document, and test, a procedure by which a platform administrator
  restores the control plane's database to a moment within its retention window. The procedure's last step
  MUST write a restore marker row that the control plane reads at start.
- **FR-031**: A control plane that finds the marker at start MUST hold projection to the cluster until a
  platform administrator releases it, and MUST list every service and every project resource whose desired
  state differs from what is in the cluster, and every project namespace in the cluster its database does not
  know, without changing any of them. Before the release is possible it MUST bring its copy of 042's erasure
  log up to date from the log's bucket copy and report how many entries it gained. The release MUST clear the
  marker and be recorded.

**Object storage on Garage**

- **FR-032**: The Garage component MUST support three nodes with each object on every node, as an
  installation choice; the local installation MUST stay one node.
- **FR-033**: An installation on Garage MUST be able to name a secondary store outside the cluster, any
  S3-compatible store, to which every bucket of the installation's Garage — services' and backups' alike —
  is mirrored on a schedule the installation sets, hourly as shipped: an object deleted from Garage MUST be deleted from the
  secondary store at the next run, and the copy MUST never accumulate what Garage no longer holds. The
  installation's status MUST report the time of the last complete copy, and a failed copy as FR-006 reports
  a backup failure.
- **FR-033a**: An installation MUST be able to require a copy outside the failure domain (off as shipped).
  With it required, a project MUST NOT be reported as backed up (FR-005) until a copy of its latest base
  backup exists outside the cluster, and an installation with the requirement and no secondary store MUST
  report every project as not backed up, with the reason.
- **FR-034**: An installation whose backups are kept in a Garage with no secondary store MUST say on its
  status that its backups share the cluster's failure domain.
- **FR-035**: On Google Cloud Storage the platform MUST NOT copy objects anywhere; durability is the
  provider's, and the bucket's location (039) decides its region.

**Documentation**

- **FR-036**: `docs/platform/databases.md` MUST say what is backed up, where, for how long, how to restore,
  switch and rehearse, how a platform administrator reclaims a left or abandoned cluster, how a restore differs
  from a rollback (033), and what a restore does not rewind; `docs/reference/limitations.md` MUST drop "one
  instance" as a limitation and state what remains: a restore is one cluster at one moment, Kafka not
  restored, re-published events applied again by in-platform consumers, Kubernetes objects not backed up,
  Postgres major upgrades not covered.

### Key Entities

- **Backup target**: where an installation's backups go; its object store, named once by the installation.
- **Backup bucket**: one per project, one for the control plane, one for the read record and one for the
  keyring, created by the platform, unreachable by any service, never deleted; each with a credential of its
  own, held in a Secret its database instances read.
- **Backup key**: on Google Cloud Storage, 044's installation key, which the store encrypts every backup
  under; never in a backup bucket. On Garage there is none.
- **Line of history**: one database's continuous archive since it was created or a restored cluster was
  first switched to; a value the platform writes, kept in a table with the moment the line began; every
  message id published from the line says which one.
- **Restore**: a request to recreate a project's cluster at a moment; holds the moment, who asked, the new
  cluster, its verification per service, the topic and consumer group divergence, the changed secret names,
  and its phase (`Restoring`, `Verified`, `Failed`, `InUse` once any service is switched to it).
- **Switch**: moving one service from one of its project's clusters to another by a rolling update the
  switch starts; recorded with who, when and the service; reversible.
- **Rehearsal**: a restore into a throwaway database in the project's rehearsal namespace, verified, timed
  and removed, with a time to live; its report is kept.
- **Restore marker**: the row the control plane's restore procedure writes, which holds projection until a
  platform administrator releases it.
- **Database setting** (per project): number of replicas, synchronous replication, retention override (never
  below the installation's), rehearsal schedule.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A project restored to a moment holds every write committed before it and none committed after
  it, shown in the k3s suite with writes on both sides of the moment.
- **SC-002**: With a backup target named, archive lag stays under 60 seconds under the shopping cart
  sample's steady load, so a total loss of the project's cluster loses at most a minute of writes.
- **SC-003**: A backup failure is on the project's status and its metric within 5 minutes of the target
  refusing writes, and clears within 5 minutes of the target recovering.
- **SC-004**: A restore and a switch leave the current database byte-for-byte unchanged until the switch,
  the left database still holds every write it had after it, and a service not switched sees no change at
  all while one beside it is switched.
- **SC-005**: A re-published event carries the id it carried before the restore, and a new event after the
  restore never carries an id any earlier message carried, shown with an entity whose sequence numbers
  overlap the lost ones; a failover promotion changes no id.
- **SC-006**: With two replicas and synchronous replication, deleting the primary's pod and volume loses
  no acknowledged write, and writes resume within 60 seconds without a redeploy.
- **SC-007**: A rehearsal of a project with 50 GiB of data completes, verified, in under 60 minutes on the
  reference cloud installation, and its report states the time taken.
- **SC-010**: On Google Cloud Storage, a backup bucket's encryption key is the installation's, shown by the
  bucket's configuration when 044 lands; on Garage the installation's status says `encryption: none` with the
  reason, asserted by the backups suite, and the limitation is in the documentation.
- **SC-011**: An object deleted from a Garage bucket is absent from the secondary store after the next copy.
- **SC-012**: On a Garage installation with a secondary store at the shipped interval, losing the whole
  cluster loses at most one hour of objects and of archive, shown in the k3s suite by a copy that completes
  within the hour of a write.
- **SC-008**: A control plane restored to before a deploy changes no running service until its projection is
  released.
- **SC-009**: No service credential can read, list or delete any object in a backup bucket, shown with each
  service's own credential in the k3s suite.

## Assumptions

- CloudNativePG archives to an object store through its Barman Cloud plugin (`barmancloud.cnpg.io`
  resources); CNPG's in-tree object store support is deprecated. Planning confirms the plugin against CNPG
  1.30. The plugin encrypts server-side with SSE-S3 or SSE-KMS only, and Garage offers SSE-C only, so on
  Garage nothing encrypts a backup (FR-003b); on Google Cloud Storage the bucket is encrypted by 044 before
  the plugin sees it. The plugin speaks S3 and Google Cloud Storage, so Garage and 039's buckets are both
  targets.
- The operator's partial `ClusterSpec` gains the fields this needs — the backup section, `instances` as one
  more than the project's replicas, synchronous replication — and a project's `AnkkaProject` resource carries the
  project's database setting, as it carries topic declarations.
- A restored cluster lives in the project's namespace under a new name beside `ankka-db`. A service's host is
  `ANKKA_DB_HOST` in its own credential Secret (`CnpgRendering.credentialSecret`), which is why the switch is
  per service and costs nothing structurally; the Secret is created once and never rewritten, so planning
  decides whether a switch gives the service a second Secret the rendered Deployment points at, or a stable
  name per service pointed at whichever cluster it is on.
- The r2dbc pool a service holds must notice a promoted primary; planning confirms the pool's validation and
  reconnect settings, since `kubernetes.md` records none today.
- CNPG major-version upgrades of Postgres are a separate concern and out of scope here.
- The restored cluster keeps the project's database authority (`ankka-db-client-ca`), which lives in the
  namespace, not the database, so every service's client certificate is accepted by it unchanged.
- The secret key that encrypts each service's secrets lives in a Kubernetes Secret, not the database, and is
  never replaced; a restored database's secrets are therefore readable by the same service. Losing the
  Kubernetes Secrets themselves is a cluster-backup concern, outside this feature, and the restore procedure
  says so.
- Message ids are CloudEvents `ce-id` headers. Today `CloudEvents.headers` gives each message a random id
  unless the publisher declares one, so FR-019 and FR-020 change what a projection that publishes from a
  journal declares; a message a component publishes by hand keeps whatever id it is given.
- Nothing in the platform deduplicates a topic message by `ce-id`: `TopicHandlers` delivers to views and
  consumers at least once. FR-020a states the consequence rather than changing it; deduplication by id in
  the platform's own consumers is a feature of its own if it is ever wanted.
- Kafka consumers in this platform commit their positions to the broker, not to Postgres, which is why a
  restore can leave a consumer group ahead of its database and why FR-018 lists them.
- "Owner" is the organization role that exists today; no new role is added.
- A project id may not end in `-rehearsal` (its rehearsal namespace is derived from it) and a project whose
  id is longer than 46 characters has no backup bucket, since a bucket name is at most 63; the control plane
  refuses both at creation from now on (research R4).
- A restored cluster archives nothing until a service is switched to it: CNPG refuses a cluster whose
  archive is not empty, and FR-016 says a line begins at the switch (research R12).

## Dependencies

- **039-gcs-object-storage**: for Google Cloud Storage as a backup target. Garage serves until then.
- **044-cloud-provider**: mints the credential for a platform-owned backup bucket on Google Cloud Storage
  (FR-003a), and its installation key is the backup key there (FR-003b); on Garage the operator mints the
  credential as 034 does and the key is 038's.
- **042-personal-data-erasure**: FR-012 has each switched service replay its erasure log; FR-031 has the
  control plane reconcile the log from its bucket copy; FR-002 backs up the keyring's database. Until 042
  lands there is no log and no keyring, and those clauses are vacuous.
- **043-topic-retention**: Kafka's durability, by replication, is there, not here.
- **026-telemetry-export**: the backup failure and archive lag metrics are exported through it.
- **038-secret-store-backends**: on Garage the backup key (FR-003b) lives in its secret store, and its read
  record is backed up here (FR-002). On the Secret Manager backend, service secrets are not in the database and FR-022
  lists nothing; on the Postgres backend they are restored with it.
- Gates eitheror's migration of a live casino onto ankka: the casino plan's Phase 0 lists backups as the
  first real-money gap.

## Open Questions

None that block planning. One is left to research: the restore procedure's form for the control plane (a
kustomize overlay that bootstraps from a backup and writes the marker, or a CLI command that does both).
