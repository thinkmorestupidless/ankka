# Contract: the installation

Components, overlays, the recovery kustomization, and what a platform administrator does by hand.

## Components

| Component | What it holds | Local overlay | Cloud overlay |
|---|---|---|---|
| `cnpg-barman` | the Barman Cloud plugin's release manifest at `v0.15.1`, into `cnpg-system` | listed | listed |
| `backups` | patches on the operator's and the control plane's Deployments: the five settings (`ANKKA_BACKUP_TARGET=object-store` in both overlays, the rest at their defaults, marked `SET` in the cloud overlay) | listed | listed |
| `garage` | unchanged shape; its network policy admits `cnpg.io/cluster` pods in ankka-managed namespaces and `ankka-controlplane` on 3900 | listed | replaced by `garage-replicated` |
| `garage-replicated` | the StatefulSet at three replicas, `replication_factor = 3`, required anti-affinity across nodes, the `garage-layout` Deployment | not listed | listed |
| `garage-copy` | CronJob `garage-copy` (`rclone/rclone` pinned) at the interval `ANKKA_GARAGE_COPY_SCHEDULE` (`0 * * * *`), Secret `garage-secondary` (`ENDPOINT`, `ACCESS_KEY_ID`, `SECRET_ACCESS_KEY`, `REGION`; the development one deleted by `$patch: delete` in the cloud overlay), ConfigMap `garage-copy-status`, a Role for the job to patch it, and a Role for the control plane to read it | not listed | listed |
| `postgres` | gains `ObjectStore ankka-backups` to `platform.backups-controlplane`, the `plugins` block and `ScheduledBackup` on `ankka-controlplane-db`, and a Role letting the control plane `get` `clusters` and `objectstores` in its namespace | as is | as is |

`RemoteOverlaySuite` asserts: both overlays set each of the five settings exactly once on each of
the two containers; the cloud overlay lists `garage-replicated` and `garage-copy` and not `garage`;
the local overlay the reverse; the development secondary Secret is absent remotely and present
locally; the operator's sidecar patch still names a container that exists.

## The operator's grant

`kustomization/components/operator/operator.yaml`: the ClusterRole additions in
[operator.md](operator.md); a second ClusterRole `ankka-operator-rehearsal`.

## The control plane's grant

`kustomization/components/controlplane/controlplane-rbac.yaml`: `namespaces` gains `list`;
`rolebindings` `create, patch`; `clusterroles` `bind` with `resourceNames: [ankka-operator-rehearsal]`;
a Role in `ankka-controlplane` for `clusters` and `objectstores` `get`; a Role in `garage-system`
for `configmaps` `get` by `resourceNames: [garage-copy-status]`.

## `kustomization/recovery/controlplane/` (applied by hand)

```
kustomization.yaml       # the three resources below, with TARGET_TIME and RESTORE_NAME marked SET
cluster.yaml             # Cluster ankka-controlplane-db-r<RESTORE_NAME>: bootstrap.recovery from
                         #   externalClusters[line].plugin serverName ankka-controlplane-db at TARGET_TIME,
                         #   bootstrap.recovery.secret: ankka-controlplane-db-app
deployment-patch.yaml    # the control plane reads ankka-controlplane-db-r<RESTORE_NAME>-app
marker-job.yaml          # Job ankka-controlplane-restore-marker: psql inserts the marker row
```

The documented procedure (`docs/operate/recovery.md`): scale the control plane to zero; set the two
values; `kubectl apply -k`; wait for the cluster; the Job completes; scale the control plane up; read
`ankka installation restore`; release with `--release` when satisfied. Then, to archive the restored
cluster as a new line, apply the overlay's second kustomization, `archive/`, which adds the
`plugins` block with `serverName: ankka-controlplane-db-r<RESTORE_NAME>` and a `ScheduledBackup`.
The old cluster is kept.

## By hand, as a platform administrator

- **Reclaiming a left or abandoned cluster**: `kubectl -n ankka-<project> delete cluster <name>`
  deletes the cluster and its volumes; the archive of its line stays in the bucket for its
  retention. The status listed the cluster with its age and, for a left one, when the last service
  left; the documentation says to confirm no service names it (`ankka projects status`).
- **A rehearsal database the operator could not remove** is the same command in
  `ankka-<project>-rehearsal`.
- **Garage's layout after a lost node**: nothing to run. The `garage-layout` Deployment reconciles
  every fifteen seconds and gives the replacement node the lost node's zone, removing the stale role
  (a one-off Job was the first shape; a k3s run showed the replacement refusing a third of requests
  with `Layout not ready` until someone re-ran it).
- **The secondary store's credential**: `garage-secondary` in `garage-system`, created out of band.

## What each k3s suite installs

`BackupStack.install(k3s, client)`: CNPG 1.30.0, the plugin's v0.15.1 manifest, `PkiStack`,
`ObjectStoreStack` (Garage) with the policy change, the `backups` settings into the in-process
operator's `Settings` and the control plane's. The durability suite installs `garage-replicated`
and a second one-node Garage as the secondary with `garage-copy`. The after-a-restore suite adds
`BrokerStack`.
