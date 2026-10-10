---
title: Backups and recovery
description: See whether a project is backed up, restore a project's database to a moment into a new cluster, switch one service to it and back, and reclaim a cluster nobody uses, as a platform administrator.
kind: guide
related: [platform/databases.md, reference/cli.md, reference/control-plane-api.md, reference/limitations.md]
---

# Backups and recovery

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

6. Archive the restored database as a line of history of its own: set `restoreName` in
   `kustomization/recovery/controlplane/archive/kustomization.yaml` and apply it server-side, which adds
   the archiver to the restored cluster and changes nothing else of it.

   ```bash
   kubectl apply --server-side -k kustomization/recovery/controlplane/archive
   ```

The database the control plane left is kept. It is removed by hand, as any cluster is, once nothing
needs it.
