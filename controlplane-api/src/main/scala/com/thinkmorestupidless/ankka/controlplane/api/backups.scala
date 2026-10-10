package com.thinkmorestupidless.ankka.controlplane.api

import java.time.Instant

/**
 * `GET /projects/{id}/status` (feature 041): whether the project is backed up, each line of
 * history's backups, the project database's instances, its clusters, its restores and its
 * rehearsals. Read live from what the operator last reported, with the project's own settings.
 *
 * @param backedUp
 *   every line archiving, with a base backup, and with a copy outside the failure domain where the
 *   installation requires one
 * @param target
 *   `none` or `object-store`
 */
final case class ProjectStatus(
    id: String,
    backedUp: Boolean,
    target: String,
    lines: Vector[BackupLine] = Vector.empty,
    database: Option[ProjectDatabase] = None,
    detail: Option[String] = None,
    /** The project's database clusters, and which services are on each. */
    clusters: Vector[ProjectCluster] = Vector.empty,
    /** Every restore of the project, oldest first. */
    restores: Vector[RestoreView] = Vector.empty,
    /** The project's latest rehearsal: one that failed is a backup failure (FR-025). */
    rehearsal: Option[RehearsalView] = None
)

/**
 * `GET /installation/restore` (feature 041): whether the control plane is held after its own
 * database was restored, to which moment, and what differs between what it records and what the
 * cluster runs, none of which it changes until a platform administrator releases it.
 */
final case class RestoreHoldStatus(
    held: Boolean,
    restoredAt: Option[Instant] = None,
    targetTime: Option[Instant] = None,
    releasedAt: Option[Instant] = None,
    releasedBy: Option[String] = None,
    services: Vector[ServiceDifference] = Vector.empty,
    /** Projects whose declared topics differ from what the cluster holds. */
    topics: Vector[String] = Vector.empty,
    /** Projects the cluster holds that the restored database does not know. */
    unknownProjects: Vector[String] = Vector.empty,
    /** What was brought back from elsewhere before a release could be made. */
    reconciled: Vector[String] = Vector.empty
)

/** A service whose recorded generation or image is not what the cluster runs. */
final case class ServiceDifference(
    project: String,
    service: String,
    recordedGeneration: Option[Long] = None,
    clusterGeneration: Option[Long] = None,
    recordedImage: Option[String] = None,
    clusterImage: Option[String] = None
)

/** `POST /projects/{id}/backups/credential`: the generation the new credential is issued at. */
final case class CredentialReissued(project: String, generation: Int)

/** `POST /projects/{id}/rehearsals`: a moment to rehearse to, the latest restorable by default. */
final case class RehearsalRequest(moment: Option[Instant] = None, line: Option[String] = None)

/**
 * A rehearsal of a restore: who asked (none for one the project's schedule started), when, to which
 * moment, how it ended — `Running`, `Completed`, `Failed` or `NotRemoved` — how long the restore
 * took, and what each service's database held.
 */
final case class RehearsalView(
    name: String,
    line: String,
    moment: Instant,
    outcome: String,
    requestedBy: Option[String] = None,
    requestedAt: Option[Instant] = None,
    elapsedSeconds: Option[Long] = None,
    detail: Option[String] = None,
    services: Vector[ServiceVerification] = Vector.empty
)

/**
 * One of the project's database clusters: `live`, a `restore`, or `left` once every service has
 * moved off it. The platform removes none of them.
 */
final case class ProjectCluster(
    name: String,
    line: String,
    phase: String,
    services: Vector[String] = Vector.empty,
    since: Option[Instant] = None,
    leftAt: Option[Instant] = None
)

/**
 * `POST /projects/{id}/restores` (feature 041): restore the project's database to `moment`, from
 * the line of history `line`, or the one every service is on when they are all on one.
 */
final case class RestoreRequest(moment: Instant, line: Option[String] = None)

/**
 * A restore: its cluster's `name`, the line and the moment, who asked, and how far it has got —
 * `Restoring`, `Verified`, `Failed` or `InUse` — with what each service's database holds once it is
 * verified.
 */
final case class RestoreView(
    name: String,
    line: String,
    moment: Instant,
    phase: String,
    requestedBy: Option[String] = None,
    requestedAt: Option[Instant] = None,
    reachedAt: Option[Instant] = None,
    services: Vector[ServiceVerification] = Vector.empty,
    detail: Option[String] = None,
    /**
     * What the restore cannot take back, as the project's services say at the moment of reading:
     * every topic they publish to or read with messages newer than the moment, and how far each
     * group has read past it. `notAsked` names the services that could not be asked.
     */
    broker: Vector[TopicDivergence] = Vector.empty,
    notAsked: Vector[String] = Vector.empty,
    note: Option[String] = None
)

/**
 * What a project asks of its database (feature 041): replicas beside the primary, whether a write
 * waits for one of them, how long its backups are kept, and how often a restore is rehearsed. The
 * same rules on both ends: `problems` names everything wrong at once.
 */
final case class DatabaseSetting(
    replicas: Int = 0,
    synchronous: Boolean = false,
    retentionDays: Option[Int] = None,
    rehearse: Option[String] = None
):
  /** Every rule broken; `floorDays` is the least the installation keeps backups for. */
  def problems(floorDays: Int): Vector[String] =
    Vector(
      Option.when(replicas < 0 || replicas > DatabaseSetting.MaxReplicas)(
        s"replicas must be between 0 and ${DatabaseSetting.MaxReplicas}, not $replicas"
      ),
      Option.when(synchronous && replicas == 0)(
        "a synchronous database needs at least one replica to wait for"
      ),
      retentionDays
        .filter(_ < floorDays)
        .map(d => s"backups are kept at least $floorDays days on this installation, not $d"),
      rehearse
        .filterNot(DatabaseSetting.Schedules.contains)
        .map(s => s"a rehearsal is daily or weekly, not '$s'")
    ).flatten

object DatabaseSetting:
  val MaxReplicas: Int       = 4
  val Schedules: Set[String] = Set("daily", "weekly")

/**
 * One topic past a restore's moment: the messages newer than it (`after`), and for a group, how
 * many of them it has read (`read`), with the services that publish to it or read it.
 */
final case class TopicDivergence(
    topic: String,
    group: Option[String] = None,
    after: Long = 0L,
    read: Option[Long] = None,
    services: Vector[String] = Vector.empty
)

/**
 * What one service's database holds in a restore: whether it is there at all, the rows of the
 * tables the platform keeps, the highest journal sequence, and the names of the service secrets
 * changed after the restore point — never a value.
 */
final case class ServiceVerification(
    name: String,
    present: Boolean,
    journalRows: Long = 0L,
    stateRows: Long = 0L,
    offsetRows: Long = 0L,
    timerRows: Long = 0L,
    highestSequence: Long = 0L,
    changedSecrets: Vector[String] = Vector.empty
)

/** `POST /services/{p}/{n}/switch` (feature 041): the cluster to move the service onto. */
final case class SwitchRequest(cluster: String)

/** One thing done to a project's database, for `GET /projects/{id}/history`. */
final case class ProjectHistoryView(
    kind: String,
    by: Option[String] = None,
    at: Option[Instant] = None,
    detail: Option[String] = None
)

/**
 * One line of history's backups.
 *
 * @param phase
 *   `backing up`, `failing` or `not backed up`
 * @param lastRestorable
 *   now less the archive's lag: a moment the archive has not reached cannot be restored to
 * @param failing
 *   why it is failing, or not backed up, in the archiver's words where it has them
 */
final case class BackupLine(
    line: String,
    cluster: String,
    phase: String,
    lastBaseBackup: Option[Instant] = None,
    firstRestorable: Option[Instant] = None,
    lastRestorable: Option[Instant] = None,
    archiveLagSeconds: Option[Double] = None,
    failing: Option[String] = None,
    copiedAt: Option[Instant] = None
)

/** The live project database: its instances, which is primary, and what holds a write. */
final case class ProjectDatabase(
    cluster: String,
    instances: Int,
    readyInstances: Int,
    primary: Option[String] = None,
    synchronous: Boolean = false,
    writesWaitingOn: Option[String] = None
)

/**
 * The installation's backups (feature 041), `backups` on `GET /installation`: where they go and how
 * safely.
 *
 * @param sharesFailureDomain
 *   the backups are in the cluster they back up: Garage with no secondary store
 * @param encryption
 *   how a backup is encrypted at rest, or `none` and why
 * @param controlPlane
 *   the control plane's own database's backups, when it can read them
 */
final case class InstallationStatus(
    backupTarget: String,
    retentionDays: Int,
    copyRequired: Boolean,
    sharesFailureDomain: Boolean,
    encryption: String,
    controlPlane: Option[BackupLine] = None,
    notBackedUp: Option[String] = None,
    /** The copy of every bucket to a store outside the cluster, where the installation has one. */
    secondaryStore: Option[SecondaryStore] = None
)

/**
 * The last copy of the installation's buckets to its secondary store: when the last one completed
 * and when one last failed and why, how many buckets it copied, and how many objects it deleted
 * from the copy because the installation no longer held them.
 */
final case class SecondaryStore(
    lastCompleted: Option[Instant] = None,
    lastFailed: Option[Instant] = None,
    failure: Option[String] = None,
    buckets: Option[Int] = None,
    deletedObjects: Option[Long] = None
)

object BackupPhrases:

  /** The operator's word for a line's phase, as `BackupLine.phase` documents it. */
  def phase(reported: String): String = reported match
    case "BackingUp"   => "backing up"
    case "Failing"     => "failing"
    case "NotBackedUp" => "not backed up"
    case other         => other

  /** What every restore says of the broker (FR-020a). */
  val Republished: String =
    "the broker is not restored: a message published again from a restored journal is read " +
      "again by every view and consumer that reads its topic; its id is the one it carried before, " +
      "which protects only a reader that deduplicates by it"

  /** What a status says when the installation backs nothing up. */
  val NoTarget: String = "nothing is backed up: the installation has no backup target"

  /** What an installation on Garage says of how its backups are encrypted (research R2). */
  val GarageEncryption: String =
    "none: Garage holds no key the archiver can send, so a backup is protected by its bucket's " +
      "credential and by the installation's volume encryption"
