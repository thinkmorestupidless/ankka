package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.Buckets

/**
 * The platform's own databases' backup buckets and credentials (feature 041, research R21): the
 * control plane's today, and the read record's and the keyring's when they exist. The operator
 * makes them because it holds the store's administrator token and `create` on Secrets everywhere;
 * the control plane holds neither. Its archiver and schedule are the `backups` component's.
 *
 * Run at start and on every resync, so a Secret removed by hand is made again; issued once per
 * process, as a project's credential is.
 */
object PlatformBackups:

  /** Where the control plane and its database run: the `controlplane` component's namespace. */
  val ControlPlaneNamespace: String = "ankka-controlplane"

  /** The control plane's database, as the `postgres` component names it. */
  val ControlPlaneCluster: String = "ankka-controlplane-db"

  /** Nothing without a backup target, as for a project. */
  def actions(settings: Settings): Vector[Action] =
    if !settings.backups.enabled || settings.objectStore.isEmpty then Vector.empty
    else
      val bucket = Buckets.platformBackup("controlplane")
      Vector(
        Action.EnsureBucket(bucket),
        Action.EnsureBackupCredential(
          ControlPlaneNamespace,
          bucket,
          bucket,
          BucketPermission.ReadWrite,
          0
        ),
        // Its schedule is the `backups` component's, so its first base backup runs the moment the
        // database exists. Run before the credential above, it fails, and the next is a day away.
        Action.RetryBaseBackup(ControlPlaneNamespace, ControlPlaneCluster)
      )
