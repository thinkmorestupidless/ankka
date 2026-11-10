# Backups and recovery

> See whether a project is backed up, restore a project's database to a moment into a new cluster, switch one service to it and back, reclaim a cluster nobody uses, and back up and restore the keyring's database, as a platform administrator.

Source: https://docs.ankka.cloud/operate/recovery/
When the installation names a backup target, every project database archives every write as it is made
and takes a base backup every day, into a bucket the platform keeps for the project. No descriptor asks
for this and none can turn it off. A project can be restored to any moment inside its retention window,
which is 30 days unless the installation or the project says longer.

A restore never overwrites anything. It makes a new database cluster beside the project database, and
an owner of the project's organization then moves services onto it one at a time.

## See whether a project is backed up

```bash
ankka projects status shop
```

```text
shop: backed up
LINE      STATUS      LAST BASE BACKUP      RESTORABLE FROM       TO                    LAG
ankka-db  backing up  2026-10-08T00:00:12Z  2026-09-08T00:00:12Z  2026-10-08T10:11:47Z  12s
database ankka-db: 1/1 ready, primary ankka-db-1
```

Each line of history is one cluster's archive. `RESTORABLE FROM` and `TO` are the earliest and latest
moments the project can be restored to. `LAG` is how far the archive is behind the database: it is the
most a total loss of the project's cluster would lose. When archiving fails, the status says `failing`
and why, within five minutes, and the installation's telemetry carries `ankka.backups.failing` for the
project.

`ankka status` says where the installation's backups go, the least any project keeps them for, and
whether they share the cluster's failure domain.

## Restore a project to a moment

```bash
ankka projects restore shop 2026-10-08T09:20:00Z
ankka projects restores get shop ankka-db-r202610081012
```

The moment is RFC 3339 with a zone. The restore is named for the minute it was asked for. It is
`Restoring` until its cluster is up, then `Verified`, with what each service's database holds: its
journal's rows and highest sequence, its states, read positions and timers, and the names of the
service secrets changed after the moment. A service with no database at the moment is listed as not
present, and cannot be switched to the restore.

Nothing in the project database changes. Only an owner may restore, and only one restore of a project
runs at a time.

## Switch a service to the restore, and back

```bash
ankka services switch rewards -p shop --to ankka-db-r202610081012
ankka services switch rewards -p shop --to ankka-db
```

A switch moves one service and no other. It starts a rolling update of that service, which comes up on
the restore; the cluster it left is kept, with every write it had. Switching back is the same command,
naming `ankka-db`. While services are on two clusters, `ankka projects status` names the cluster each
one is on.

A restore a service is switched to archives from then on as a line of history of its own. Every earlier
line stays restorable for its retention.

A restore takes back a service's journal and read positions. It does not take back the broker: messages
published from events the restore lost are still on their topics, and a view or consumer that reads a
topic reads a message published again as a new one.

## Erased data subjects stay erased

A restore holds the personal fields of every data subject as it held them at the moment restored to,
encrypted. The keys are not in the project's database or its backups but in the installation's keyring,
which is not restored with the project, so a data subject erased after that moment reads as erased from
the restore too.

A view's rows are another matter: the redaction of an erased data subject's fields and lookup tokens was
written after the moment restored to. A service switched to a restore is therefore not ready, as
`erasure-replay`, until it has carried out every erasure the keyring holds for its project against its own
tables. The restore's status names when each service switched to it finished, as `erasureReplayedAt`.
A restart on the same database does not wait again.

The keyring's own database, and what an installation does when it is restored, is in
[Erasure on the platform](../platform/erasure.md#restores-and-the-erasure-log).

## Reclaim a cluster nobody uses

The platform never removes a project database, a restore, or a cluster every service has left. Each is
listed on `ankka projects status` with its age, or with when the last service left it. A platform
administrator removes one by hand, with the cluster's own credentials, once nothing names it:

```bash
ankka projects status shop                     # confirm no service is on the cluster
kubectl -n ankka-<project> delete cluster <name>
```

This deletes the cluster and its volumes. Its line of history stays in the project's backup bucket for
its retention, so the moments it held can still be restored to until then.

## Restore the control plane's own database

The control plane's database is backed up as a project database is, under the line of history
`ankka-controlplane-db` in the installation's bucket `platform.backups-controlplane`. There is no
control plane to ask for its restore, so a platform administrator restores it with the cluster's own
credentials, by hand:

1. Stop the control plane, so nothing writes to the database it is leaving.

   ```bash
   kubectl -n ankka-controlplane scale deployment/ankka-controlplane --replicas=0
   ```

2. Set the two values in `kustomization/recovery/controlplane/kustomization.yaml`: `restoreName`,
   the restored cluster's name, `ankka-controlplane-db-r<yyyyMMddHHmm>` by convention, and
   `targetTime`, the moment, RFC 3339 with a zone. Then apply it.

   ```bash
   kubectl apply -k kustomization/recovery/controlplane
   kubectl -n ankka-controlplane wait --for=condition=complete job/ankka-controlplane-restore-marker --timeout=60m
   ```

   This makes the restored cluster beside the current one, which is kept, and writes the restore
   marker into it once it answers.

3. Point the control plane at the restored cluster, and start it. An installation kept in its own
   overlay lists `deployment-patch.yaml` there, with the name set; one deployed by hand sets the
   host on both of the control plane's containers:

   ```bash
   kubectl -n ankka-controlplane set env deployment/ankka-controlplane -c wait-for-postgres ANKKA_DB_HOST=<restoreName>-rw
   kubectl -n ankka-controlplane set env deployment/ankka-controlplane -c ankka-controlplane ANKKA_DB_HOST=<restoreName>-rw
   kubectl -n ankka-controlplane scale deployment/ankka-controlplane --replicas=1
   ```

4. Read what differs. The control plane starts held: it changes nothing in the cluster, since what its
   database records is older than what the cluster runs, and lists every service whose recorded
   generation or image differs from what runs, every project whose declared topics differ, and every
   project the cluster holds that the database does not know.

   ```bash
   ankka installation restore
   ```

   While held, writes that would reach the cluster, such as a project secret or a registry
   credential, are refused as unavailable. Applying a service is recorded and listed, not projected.

5. Release it, as a platform administrator, once what it lists is what should happen. From its next
   sweep the control plane makes the cluster what its database records, and the release is recorded
   with who made it.

   ```bash
   ankka installation restore --release
   ```

   On an installation whose erasure is on, the held control plane has already brought its copy of the
   erasure log up to date from the platform bucket `platform.erasure-log`, and says so in what it lists,
   so no erasure applied after the moment restored to is forgotten.

6. Archive the restored database as a line of history of its own: set `restoreName` in
   `kustomization/recovery/controlplane/archive/kustomization.yaml` and apply it server-side, which adds
   the archiver to the restored cluster and changes nothing else of it.

   ```bash
   kubectl apply --server-side -k kustomization/recovery/controlplane/archive
   ```

The database the control plane left is kept. It is removed by hand, as any cluster is, once nothing
needs it.

## Back up and restore the keyring's database

On an installation whose erasure is on, the keyring keeps every data subject's key in a database of its
own, `keyring-db` in the namespace `ankka-keyring`, apart from every project's database and from the
control plane's (see [Erasure on the platform](../platform/erasure.md)). It is backed up as the control
plane's is, with nothing to ask for: every write is archived within a minute, and a base backup is taken
every day at midnight, under the line of history `keyring-db` in the installation's bucket
`platform.backups-keyring`.

### What a backup holds, and what to keep apart from it

A backup of the keyring's database holds every key that existed when it was taken, wrapped by the root
key, including the keys of data subjects erased since. Whoever holds the backup and can unwrap them can
read those data subjects' fields in any project backup of the same time. So:

- **On Postgres**, the root key is in the keyring's database too, encrypted with `ANKKA_SECRET_KEY`
  (the Secret `ankka-keyring-secret-key`). Keep that key out of the backup bucket and out of whatever
  copies the bucket elsewhere, and keep a copy of it you can recover: without it, neither the keyring
  nor a restore of its database can unwrap anything.
- **On Google Cloud**, the root key is the KMS key and never leaves Cloud KMS. Keep the right to use it
  to the keyring's own identity.

### How long the backups are kept

Keep the keyring's backups as long as the installation keeps project backups, and no longer. Both are 30
days unless the installation says otherwise (`ANKKA_BACKUP_RETENTION_DAYS` for projects, the
`retentionPolicy` of the `ankka-backups` object store in `kustomization/components/keyring/database.yaml`
for the keyring). Kept no longer, an erasure is final everywhere at the same moment: once the oldest
backup and archived log taken before it age out, no backup holds the key, and the project backups
that still hold the data subject's fields hold only ciphertext. Kept shorter, a restored project can
outlive the keys its own backups need. An installation that changes one changes the other in its
overlay.

### While the keyring is down

A service instance keeps a key it already holds for at most 60 seconds after the keyring stops
answering. After that, every read or write of a personal field is refused as unavailable, and an entity
whose recovery is refused is recovered again with backoff: nothing is read as erased because the keyring
could not be reached (see [Personal data](../build/personal-data.md)). Erasures asked for meanwhile are
held in the erasure log and applied when the keyring answers.

### Restore it to a moment

A platform administrator restores the keyring's database with the cluster's own credentials, by hand:

1. Stop the keyring, so nothing writes to the database it is leaving.

   ```bash
   kubectl -n ankka-keyring scale deployment/ankka-keyring --replicas=0
   ```

2. Set the two values in `kustomization/recovery/keyring/kustomization.yaml`: `restoreName`, the
   restored cluster's name, `keyring-db-r<yyyyMMddHHmm>` by convention, and `targetTime`, the moment,
   RFC 3339 with a zone. Then apply it and wait for the cluster.

   ```bash
   kubectl apply -k kustomization/recovery/keyring
   kubectl -n ankka-keyring wait --for=condition=Ready cluster/<restoreName> --timeout=60m
   ```

   This makes the restored cluster beside the current one, which is kept.

3. Point the keyring at the restored cluster, and start it. An installation kept in its own overlay
   lists `kustomization/recovery/keyring/deployment-patch.yaml` there, with the name set; one deployed
   by hand sets the host on both of the keyring's containers:

   ```bash
   kubectl -n ankka-keyring set env deployment/ankka-keyring -c wait-for-postgres ANKKA_DB_HOST=<restoreName>-rw
   kubectl -n ankka-keyring set env deployment/ankka-keyring -c ankka-keyring ANKKA_DB_HOST=<restoreName>-rw
   kubectl -n ankka-keyring scale deployment/ankka-keyring --replicas=2
   ```

   Before it answers anything, the keyring reads both copies of the erasure log, the control plane's and
   the bucket `platform.erasure-log`, and applies every erasure the restored database lacks, so a key
   destroyed after the moment restored to is destroyed again. `/ready` holds until it has; with either
   copy unreadable it applies nothing, answers nothing, and tries again every five seconds.

4. Confirm that erased data subjects are erased again. The keyring's `/status` on its probe port says
   whether a copy of the log was behind and how many entries it applied; an erasure's certificate still
   reads as applied, and a read of an erased data subject's fields is refused as erased.

   ```bash
   kubectl -n ankka-keyring port-forward deployment/ankka-keyring 7627:7627 &
   curl -s localhost:7627/status
   ankka projects erasures list -p <project> --state applied
   ```

5. Archive the restored database as a line of history of its own: set `restoreName` in
   `kustomization/recovery/keyring/archive/kustomization.yaml` and apply it server-side, which adds the
   archiver to the restored cluster and changes nothing else of it.

   ```bash
   kubectl apply --server-side -k kustomization/recovery/keyring/archive
   ```

The database the keyring left is kept. It holds every key it held, the erased ones' among them until
its pages are reused, so remove it by hand once nothing needs it.
