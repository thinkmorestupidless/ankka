package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{Buckets, Recovery, RehearsalEntry, RestoreEntry}
import com.thinkmorestupidless.ankka.operator.cnpg.PostgresCluster

import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 * A rehearsal of a project's restore (feature 041, research R15): the restore's own cluster, made
 * in the project's rehearsal namespace rather than beside the project database, where no service
 * runs and the operator may delete. It reads the project's backups with a credential that can only
 * read them, so a rehearsal can change nothing of what it rehearses. Pure, as `RestoreRendering`
 * is.
 */
object RehearsalRendering:

  /** When a rehearsal's cluster may be removed whatever became of the rehearsal. */
  val ExpiresAt: String = "ankka.thinkmorestupidless.com/expires-at"

  /** The key a rehearsal namespace reads the project's backups with. */
  def keyName(projectId: String): String = s"${Buckets.backup(projectId)}.rehearsal"

  def namespace(settings: Settings, projectId: String): String =
    Recovery.rehearsalNamespace(settings.namespacePrefix, projectId)

  /** When the rehearsal's cluster expires: from when it was asked for, so every pass agrees. */
  def expiresAt(entry: RehearsalEntry, settings: Settings): Option[Instant] =
    scala.util
      .Try(Instant.parse(entry.requestedAt))
      .toOption
      .map(_.plusMillis(settings.backups.rehearsalTtl.toMillis))

  def cluster(projectId: String, entry: RehearsalEntry, settings: Settings): PostgresCluster =
    val restored = RestoreRendering.cluster(
      namespace(settings, projectId),
      RestoreEntry(entry.name, entry.line, entry.targetTime, entry.requestedAt),
      settings,
      database = None,
      inUse = false
    )
    expiresAt(entry, settings).foreach(at =>
      restored.getMetadata.setAnnotations(Map(ExpiresAt -> at.toString).asJava)
    )
    restored

  /** Whether a cluster found in a rehearsal namespace is past its time to live. */
  def expired(expires: Option[Instant], now: Instant): Boolean = expires.exists(!_.isAfter(now))

  /**
   * Everything a rehearsal needs this pass: the database authority its cluster's certificates come
   * from, a read-only credential for the project's backups and where they are, the cluster, and who
   * may connect to it. Nothing without a backup target: there is nothing to rehearse from.
   */
  def actions(
      projectId: String,
      entry: RehearsalEntry,
      settings: Settings,
      credentialGeneration: Int
  ): Vector[Action] =
    val ns = namespace(settings, projectId)
    CnpgRendering
      .backupObjectStore(ns, projectId, settings, settings.backups.retentionDays)
      .toVector
      .flatMap { store =>
        CnpgRendering.projectAuthority(ns).map {
          case issuer if issuer.getKind == "Issuer" => Action.EnsureIssuer(issuer)
          case certificate                          => Action.EnsureCertificate(certificate)
        } ++ Vector(
          Action.EnsureBackupCredential(
            ns,
            Buckets.backup(projectId),
            keyName(projectId),
            BucketPermission.ReadOnly,
            credentialGeneration
          ),
          Action.EnsureObjectStore(store),
          Action.EnsureCluster(cluster(projectId, entry, settings)),
          Action.EnsureNetworkPolicy(CnpgRendering.databasePolicy(ns, entry.name))
        )
      }
