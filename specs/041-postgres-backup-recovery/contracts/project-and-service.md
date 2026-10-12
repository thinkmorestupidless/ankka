# Contract: what a member reads and asks of the control plane

The wire types are in `controlplane-api` (`descriptors.scala`), with jsoniter codecs in `Wire`;
every new `Option` field defaults to `None` and no status word is a fieldless enum without a
string codec. Every route below gets a hand-written section in `docs/reference/control-plane-api.md`.

## Routes

| Method and path | Role | Body | Answer |
|---|---|---|---|
| `GET /installation` | any authenticated user | | `Installation`, whose `backups` is `InstallationStatus` |
| `GET /installation/restore` | any member | | `RestoreHoldStatus`, or 404 when no marker |
| `POST /installation/restore/release` | platform admin | | `RestoreHoldStatus` with `releasedAt` |
| `GET /projects/{id}/status` | member | | `ProjectStatus` |
| `PUT /projects/{id}/database` | member | `DatabaseSetting` | `ProjectStatus` |
| `POST /projects/{id}/backups/credential` | member | | `ProjectStatus` |
| `POST /projects/{id}/restores` | **owner** | `RestoreRequest` | `RestoreView` (202) |
| `GET /projects/{id}/restores` | member | | `[RestoreView]` |
| `GET /projects/{id}/restores/{name}` | member | | `RestoreView` with the report |
| `POST /projects/{id}/rehearsals` | member | `RehearsalRequest` | `RehearsalView` (202) |
| `GET /projects/{id}/rehearsals` | member | | `[RehearsalView]` |
| `GET /projects/{id}/history` | member | | `[ProjectHistoryEntry]` |
| `POST /services/{p}/{n}/switch` | **owner** | `SwitchRequest` | `ServiceStatus` |

A member who is not an owner is answered 403 `owner role required in organization '…'` on the two
owner routes, as member management is. A deploy token is a member and cannot restore or switch.

## Types

```scala
case class DatabaseSetting(replicas: Int = 0, synchronous: Boolean = false,
                           retentionDays: Option[Int] = None, rehearsalSchedule: Option[String] = None)
// refused: replicas < 0 or > 4; synchronous with replicas == 0; retentionDays below the installation's
// floor ("retention 14 days is below the installation's 30"); rehearsalSchedule not "daily" | "weekly"

case class RestoreRequest(moment: Instant, line: Option[String] = None)
// refused: moment outside [earliest, latest] ("shop can be restored between <earliest> and <latest>");
// a restore in progress ("a restore of shop is in progress: <name>"); services on two clusters and
// no line ("name the line: shop's services are on ankka-db and ankka-db-r…")

case class RehearsalRequest(moment: Instant, line: Option[String] = None)
case class SwitchRequest(cluster: String)
// refused: the cluster is not a Verified/InUse restore or a cluster the service left; the restore has
// no database for the service ("rewards had no database at <moment>"); a rollout in progress waits

case class ProjectStatus(id: String, backedUp: Boolean, target: String, lines: Vector[LineStatus],
                         database: DatabaseStatus, clusters: Vector[ClusterStatus],
                         restores: Vector[RestoreView], rehearsals: Vector[RehearsalView],
                         setting: DatabaseSetting, detail: Option[String])
case class LineStatus(line: String, cluster: String, phase: String /* not backed up | backing up | failing */,
                      lastBaseBackup: Option[Instant], firstRestorable: Option[Instant], lastRestorable: Option[Instant],
                      archiveLagSeconds: Option[Double], failing: Option[String], copiedAt: Option[Instant])
case class DatabaseStatus(cluster: String, instances: Int, readyInstances: Int, primary: Option[String],
                          synchronous: Boolean, writesWaitingOn: Option[String])
case class ClusterStatus(name: String, line: String, phase: String /* live | restore | left */,
                         services: Vector[String], since: Instant, leftAt: Option[Instant])
case class RestoreView(name: String, line: String, moment: Instant, phase: String /* Restoring | Verified | Failed | InUse */,
                       requestedBy: HistoryActor, requestedAt: Instant, reachedAt: Option[Instant],
                       services: Vector[ServiceVerification], divergence: Option[Divergence],
                       changedSecrets: Vector[String], detail: Option[String], ageSeconds: Option[Long])
case class ServiceVerification(name: String, present: Boolean, journalRows: Long, stateRows: Long,
                               offsetRows: Long, timerRows: Long, highestSequence: Long, changedSecrets: Vector[String])
case class Divergence(topics: Vector[TopicDivergence], groups: Vector[GroupDivergence], notAsked: Vector[String],
                      note: String /* FR-020a's sentence */)
case class RehearsalView(name: String, moment: Instant, outcome: String, requestedBy: HistoryActor,
                         requestedAt: Instant, elapsedSeconds: Option[Long], services: Vector[ServiceVerification],
                         detail: Option[String])
case class ProjectHistoryEntry(kind: String, actor: HistoryActor, at: Instant, detail: Option[String])
// kinds: database-set, credential-reissued, restore-requested, restore-completed, restore-failed,
//        rehearsal-requested, rehearsal-completed, switched (also on the service's history)

case class InstallationStatus(backupTarget: String, retentionDays: Int, copyRequired: Boolean,
                              sharesFailureDomain: Boolean, encryption: String /* "none: …" on Garage; "installation key" on gcs */,
                              secondaryStore: Option[CopyStatus], controlPlane: Option[LineStatus], notBackedUp: Option[String])
case class CopyStatus(lastCompleted: Option[Instant], lastFailed: Option[Instant], deletedObjects: Option[Long], detail: Option[String])
case class RestoreHoldStatus(restoredAt: Instant, targetTime: Instant, releasedAt: Option[Instant], releasedBy: Option[String],
                             differingServices: Vector[ServiceDifference], differingTopics: Vector[String],
                             unknownNamespaces: Vector[String], reconciled: Vector[String] /* 042's, empty */)
case class ServiceDifference(project: String, name: String, recordedGeneration: Long, runningGeneration: Option[Long],
                             recordedImage: String, runningImage: Option[String])
```

`ServiceStatus` gains `databaseCluster: Option[String]` and `switchable: Vector[String]` (the
clusters a switch may name now). `HistoryEntry` gains kind `switched` with `detail` naming the
clusters.

## CLI

```
ankka status                                   # GET /installation; "nothing is backed up" when the target is none
ankka projects status  -p <project>
ankka projects database set -p <project> [--replicas N] [--synchronous|--no-synchronous] [--retention-days N] [--rehearse daily|weekly|off]
ankka projects restore -p <project> <moment> [--line <cluster>]       # prints the restore's name; `restores get` follows it
ankka projects restores [list] -p <project>
ankka projects restores get -p <project> <name>                      # the report, divergence and changed secret names
ankka projects rehearse -p <project> <moment>
ankka projects rehearsals -p <project>
ankka projects backups reissue-credential -p <project>
ankka projects history -p <project>
ankka services switch <name> -p <project> --to <cluster>
ankka installation restore [--release]                                # the hold's listing; release is platform admin
```

`<moment>` is RFC 3339 and must carry a zone, or the CLI refuses it before any request. Output
follows `Output.scala`'s tables; the restore report prints one row per service, and the divergence under
the heading "newer than the restore point", never the type's name.

## The observe port (a service answers the control plane)

`GET /divergence?since=<RFC 3339>` on port 7628, answered by the runtime: the service's
publications and consumer groups against the broker as [data-model.md](../data-model.md) shows.
A service with no broker answers an empty divergence. The control plane calls it as it reads
topologies, through the service's certificate.
