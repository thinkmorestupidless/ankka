package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.Buckets

import scala.jdk.CollectionConverters.*

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

  /** The keyring's ServiceAccount, as its component names it. */
  val KeyringServiceAccount: String = "ankka-keyring"

  private val Platform = Map(Labels.ManagedByKey -> Labels.ManagedByAnkka)

  /** Whose requests the keyring's are. */
  val KeyringRequester: CloudRequests.Requester =
    CloudRequests.Requester.Platform(KeyringSettings.Component)

  /**
   * What the keyring asks of a Google installation that names a wrapping key, while erasure is on:
   * a cloud identity for its ServiceAccount, then, once the provider has made it, that the identity
   * may wrap with the installation's key, and the ServiceAccount bound to the identity as the
   * provider's answer says. Nothing on any other installation: without a wrapping key the keyring
   * keeps its root key on Postgres under its secret key.
   *
   * `observe` reads a request's answer, as `Executor.observeCloudResource` does; the next request
   * follows within a resync of the identity's answer, since no resource the operator watches owns
   * these.
   */
  def cloudActions(
      settings: Settings,
      observe: (String, String) => Option[CloudObservation],
      now: java.time.Instant
  ): Vector[Action] =
    (for
      _     <- settings.erasure
      cloud <- settings.cloud if cloud.provider == GoogleProvider
      key   <- cloud.kmsKey.filter(_.nonEmpty)
    yield
      val by       = KeyringRequester
      val identity = CloudRequests.identity(cloud, by, KeyringServiceAccount)
      val plan     = CloudProvisioning.decide(
        identity,
        observe(by.namespace, identity.getMetadata.getName),
        now,
        cloud.acknowledgementBound
      )
      val made = plan match
        case CloudPlan.Ready(outputs, _, _, _) => outputs.get(CloudRequests.Keys.Identity)
        case _                                 => None
      Action.EnsureCloudResource(identity) +: made.toVector.flatMap { named =>
        Vector(
          Action.EnsureCloudResource(CloudRequests.wrappingKey(cloud, by, named, key)),
          Action.EnsureServiceAccount(
            new io.fabric8.kubernetes.api.model.ServiceAccountBuilder()
              .withMetadata(
                new io.fabric8.kubernetes.api.model.ObjectMetaBuilder()
                  .withNamespace(by.namespace)
                  .withName(KeyringServiceAccount)
                  .withAnnotations(ObjectStorage.identityAnnotations(plan).asJava)
                  .build()
              )
              .build()
          )
        )
      }
    ).getOrElse(Vector.empty)

  /** The provider whose installation wraps the keyring's root key with Cloud KMS. */
  val GoogleProvider: String = "gcp"

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
