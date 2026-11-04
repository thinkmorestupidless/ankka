package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.Buckets

/**
 * What the keyring's component cannot hold itself because each is a credential (feature 042): the
 * keyring's secret key, which keeps its root key on Postgres, the erasure log's bucket
 * `platform.erasure-log`, and the two keys on it, the control plane's to write the log's second
 * copy and the keyring's to read it. The operator makes them as it makes the control plane's backup
 * credential (`PlatformBackups`): it holds the store's administrator token and `create` on Secrets,
 * and neither the control plane nor the keyring holds either.
 *
 * Run at start and on every resync beside the platform's backups, so a Secret removed by hand is
 * made again. Nothing while erasure is off.
 */
object PlatformErasure:

  /** The keyring's database, as its component names it. */
  val KeyringCluster: String = "keyring-db"

  /** The Secret each reader of the erasure log reads its key from, in its own namespace. */
  val LogSecret: String = "ankka-erasure-log"

  /** The keyring's secret key, which it keeps its root key under when no wrapping key is named. */
  val KeyringSecretKey: String = "ankka-keyring-secret-key"

  /** The control plane's key on the log, which may write it. */
  val ControlPlaneLogKey: String = "erasure-log-controlplane"

  /** The keyring's key on the log, which may only read it. */
  val KeyringLogKey: String = "erasure-log-keyring"

  private val Platform = Map(Labels.ManagedByKey -> Labels.ManagedByAnkka)

  def actions(settings: Settings): Vector[Action] =
    if settings.erasure.isEmpty then Vector.empty
    else
      Action.EnsureSecretKey(KeyringSettings.Namespace, KeyringSecretKey, Platform) +:
        (if settings.objectStore.isEmpty then Vector.empty
         else
           Vector(
             Action.EnsureBucket(Buckets.ErasureLog),
             Action.EnsurePlatformCredential(
               PlatformBackups.ControlPlaneNamespace,
               LogSecret,
               Buckets.ErasureLog,
               ControlPlaneLogKey,
               BucketPermission.ReadWrite
             ),
             Action.EnsurePlatformCredential(
               KeyringSettings.Namespace,
               LogSecret,
               Buckets.ErasureLog,
               KeyringLogKey,
               BucketPermission.ReadOnly
             )
           ))
