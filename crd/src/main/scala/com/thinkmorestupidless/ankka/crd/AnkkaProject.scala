package com.thinkmorestupidless.ankka.crd

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.{Group, Kind, Plural, ShortNames, Version}

/**
 * What a project declares that is not a service's (feature 027): its topics on the installation's
 * broker. Written by the control plane from the project's declarations, never by the operator.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class AnkkaProjectSpec(
    projectId: String = "",
    topics: List[ProjectTopicEntry] = Nil,
    /** Brokers the project declares beside the installation's (feature 037). */
    brokers: List[ProjectBrokerEntry] = Nil,
    /**
     * Where the project's new buckets in Google Cloud Storage are made (feature 039), in the
     * installation's own words; `None` is the installation's default. A member sets it.
     */
    bucketLocation: Option[String] = None,
    /** Feature 041: the project database's replicas, retention and rehearsal schedule. */
    database: Option[ProjectDatabaseSpec] = None,
    /** Feature 041: what re-issues the backup credential. */
    backups: Option[ProjectBackupsSpec] = None,
    /** Feature 041: every restore of the project ever asked for, in order. */
    restores: List[RestoreEntry] = Nil,
    /** Feature 041: rehearsals asked for and not yet reported back to the control plane. */
    rehearsals: List[RehearsalEntry] = Nil
)

/**
 * A project database's settings (feature 041), set by a member on the project, never a service.
 * `replicas` is the number beside the primary; `retentionDays` is never below the installation's,
 * which the control plane refuses; absent means the installation's.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ProjectDatabaseSpec(
    replicas: Int = 0,
    synchronous: Boolean = false,
    @JsonDeserialize(contentAs = classOf[java.lang.Integer])
    retentionDays: Option[Int] = None,
    /** `daily` or `weekly`; absent means none. */
    rehearsalSchedule: Option[String] = None
)

/** Raising `credentialGeneration` asks for a new backup credential in the same Secret. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ProjectBackupsSpec(credentialGeneration: Int = 0)

/**
 * A restore asked for: the cluster it makes, the line of history it reads, and the moment, RFC 3339
 * in UTC. Never removed: a restore's cluster is kept whatever becomes of it.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class RestoreEntry(
    name: String = "",
    line: String = "",
    targetTime: String = "",
    requestedAt: String = ""
)

/** A rehearsal asked for: as a restore, into the project's rehearsal namespace. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class RehearsalEntry(
    name: String = "",
    line: String = "",
    targetTime: String = "",
    requestedAt: String = ""
)

/**
 * One declared topic: its name as the project's components use it, its partitions, and when the
 * project declared it (RFC 3339), so a topic the broker held from before can be told apart.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ProjectTopicEntry(
    name: String = "",
    partitions: Int = 1,
    declaredAt: String = "",
    /** Feature 037: the broker keeps the last message under each key. */
    compacted: Boolean = false,
    /**
     * Feature 037: the contract every side must state, and the fingerprint of its schema. Flat: the
     * schema suite checks one level.
     */
    contractName: Option[String] = None,
    contractFingerprint: Option[String] = None
)

/**
 * A broker a project declares by name (feature 037): where it is, the shape of its credential
 * (`certificate` or `sasl`), and the project secret holding it, which every service's platform
 * container mounts.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ProjectBrokerEntry(
    name: String = "",
    bootstrap: String = "",
    shape: String = "",
    secretName: String = "",
    declaredAt: String = ""
)

/** How far the operator has got with one declared topic. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ProjectTopicStatus(
    name: String = "",
    /** "Waiting", "Provisioned", "Recovered" or "Failed". */
    phase: String = "",
    /** The partitions the topic's resource asks for, when it exists. */
    @JsonDeserialize(contentAs = classOf[java.lang.Integer])
    partitions: Option[Int] = None,
    detail: Option[String] = None,
    @JsonDeserialize(contentAs = classOf[java.lang.Boolean])
    compacted: Option[Boolean] = None
)

/**
 * What the operator observed of the project: its topics, and since feature 041 its backups, its
 * database, its clusters, its restores and its rehearsals. Written to the status subresource only.
 * Every time is RFC 3339.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class AnkkaProjectStatus(
    topics: List[ProjectTopicStatus] = Nil,
    backups: Option[BackupsStatus] = None,
    database: Option[ProjectDatabaseStatus] = None,
    clusters: List[ProjectClusterStatus] = Nil,
    restores: List[RestoreStatus] = Nil,
    rehearsals: List[RehearsalStatus] = Nil
)

/** `target` is `none` or `object-store`; one line per cluster that archives. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class BackupsStatus(
    target: String = "",
    lines: List[LineStatus] = Nil,
    detail: Option[String] = None
)

/**
 * One line of history's backups: `phase` is `NotBackedUp`, `BackingUp` or `Failing`, with the
 * reason in `failing`. `copiedAt` is the secondary store's last complete copy newer than the last
 * base backup, where the installation requires one.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class LineStatus(
    line: String = "",
    cluster: String = "",
    phase: String = "",
    lastBaseBackup: Option[String] = None,
    firstRestorable: Option[String] = None,
    lastRestorable: Option[String] = None,
    @JsonDeserialize(contentAs = classOf[java.lang.Double])
    archiveLagSeconds: Option[Double] = None,
    failing: Option[String] = None,
    copiedAt: Option[String] = None
)

/** The live project database: its instances, its primary, and what holds a synchronous write. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ProjectDatabaseStatus(
    cluster: String = "",
    instances: Int = 0,
    readyInstances: Int = 0,
    primary: Option[String] = None,
    synchronous: Boolean = false,
    writesWaitingOn: Option[String] = None
)

/** A cluster of the project: `live`, a `restore`, or `left` once every service moved off it. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ProjectClusterStatus(
    name: String = "",
    line: String = "",
    phase: String = "",
    services: List[String] = Nil,
    since: Option[String] = None,
    leftAt: Option[String] = None
)

/** `phase` is `Restoring`, `Verified`, `Failed` or `InUse`. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class RestoreStatus(
    name: String = "",
    line: String = "",
    targetTime: String = "",
    phase: String = "",
    reachedAt: Option[String] = None,
    detail: Option[String] = None,
    services: List[ServiceVerification] = Nil
)

/**
 * What one service's database holds in a restore: whether it is there at all, the rows of the
 * tables the platform keeps, the highest journal sequence, and the names (never the values) of the
 * service secrets changed after the restore point.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class ServiceVerification(
    name: String = "",
    present: Boolean = false,
    journalRows: Long = 0L,
    stateRows: Long = 0L,
    offsetRows: Long = 0L,
    timerRows: Long = 0L,
    highestSequence: Long = 0L,
    changedSecrets: List[String] = Nil
)

/** `outcome` is `Running`, `Completed`, `Failed` or `NotRemoved`. */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
final case class RehearsalStatus(
    name: String = "",
    targetTime: String = "",
    outcome: String = "",
    startedAt: Option[String] = None,
    @JsonDeserialize(contentAs = classOf[java.lang.Long])
    elapsedSeconds: Option[Long] = None,
    detail: Option[String] = None,
    services: List[ServiceVerification] = Nil
)

@Group("ankka.thinkmorestupidless.com")
@Version("v1alpha1")
@Kind("AnkkaProject")
@Plural("ankkaprojects")
@ShortNames(Array("aproj"))
class AnkkaProject extends CustomResource[AnkkaProjectSpec, AnkkaProjectStatus] with Namespaced:
  override protected def initSpec(): AnkkaProjectSpec = AnkkaProjectSpec()

  /** Null until the operator has reported, as `AnkkaService`'s is. */
  override protected def initStatus(): AnkkaProjectStatus = null

object AnkkaProject:
  def apply(namespace: String, name: String, spec: AnkkaProjectSpec): AnkkaProject =
    val resource = new AnkkaProject
    resource.setMetadata(
      new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
        .withNamespace(namespace)
        .withName(name)
        .build()
    )
    resource.setSpec(spec)
    resource
