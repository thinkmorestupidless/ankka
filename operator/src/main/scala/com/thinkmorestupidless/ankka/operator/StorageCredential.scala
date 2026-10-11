package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.{ObjectMetaBuilder, Secret, SecretBuilder}

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/**
 * Where a storage credential's Secret is written: a `create`, whose answer is the only thing the
 * operator ever learns about a Secret, and a merge `patch`. No read: the operator's grant on
 * Secrets is `create` and `patch`, and a credential is written where the pod reads it and never
 * read back.
 */
trait SecretWriter:
  def create(secret: Secret): SecretWriter.Outcome
  def patch(namespace: String, name: String, entries: Map[String, String]): Unit

object SecretWriter:
  enum Outcome:
    case Created, Exists

/**
 * Issues a service's storage credential once (feature 034, research R6).
 *
 * The store returns a key's secret once, when it issues the key, and the operator never asks again;
 * the cluster's Secret is never read. So whether the Secret already holds a credential is learned
 * from the `create` itself: a key is issued speculatively, allowed on the bucket, and offered as a
 * new Secret, and the answer decides what happens to it.
 *
 * | Keys named for the service | The `create` | Then                                                               |
 * |:---------------------------|:-------------|:-------------------------------------------------------------------|
 * | none                       | `Created`    | done                                                               |
 * | some                       | `Created`    | the earlier keys are deleted: their secrets are in no Secret       |
 * | some                       | `Exists`     | the key just issued is deleted: the Secret holds one of the others |
 * | none                       | `Exists`     | the Secret is patched: the store has lost the key it held          |
 *
 * Allowing the key before the `create` means a Secret that exists holds a key already allowed on
 * the bucket, whatever pass was interrupted where. A key is deleted only when its secret is in no
 * Secret; nothing a service has is ever deleted.
 *
 * Once a Secret is ensured, this process remembers it and asks nothing more about it, as the secret
 * key's Secret is remembered: a reconcile every few minutes does not issue a key every few minutes.
 * Every pass still allows the keys named for the service on the bucket when it shows none, which is
 * what gives a bucket made again its key back.
 */
final class StorageCredential(store: ObjectStore, secrets: SecretWriter):

  private val ensured = ConcurrentHashMap.newKeySet[(String, String)]()

  /**
   * @param generation
   *   the generation of the credential in place (feature 039): its key is named for the bucket and
   *   the generation, so a pass after an operator restart looks for the key the Secret holds, never
   *   for one of an older generation
   * @param keyName
   *   what the store calls the key, when it is not the generation's (feature 041): a project
   *   database's archiver's, and a rehearsal's, the bucket's and `.rehearsal`
   * @param permission
   *   what the key may do on the bucket (feature 041 issues less than owner)
   * @param entries
   *   what the Secret holds, from the key: the S3 variables for a service, barman's three for a
   *   database's archiver
   */
  def ensure(
      namespace: String,
      secretName: String,
      labels: Map[String, String],
      bucket: String,
      generation: Int = 0,
      keyName: Option[String] = None,
      permission: BucketPermission = BucketPermission.Owner,
      entries: IssuedKey => Map[String, String] = StorageCredential.entries
  ): StorageCredential.Result =
    val info = existing(bucket)
    val name = keyName.getOrElse(StorageCredential.keyName(bucket, generation))
    if ensured.contains((namespace, secretName)) then
      reallow(info, name, permission)
      StorageCredential.Result.Unchanged
    else
      val earlier = store.keysNamed(name)
      val issued  = store.createKey(name)
      store.allowAs(info.id, issued.accessKeyId, permission)
      val result = secrets.create(secret(namespace, secretName, labels, entries(issued))) match
        case SecretWriter.Outcome.Created =>
          earlier.foreach(store.deleteKey)
          StorageCredential.Result.Created
        case SecretWriter.Outcome.Exists if earlier.nonEmpty =>
          store.deleteKey(issued.accessKeyId)
          earlier.foreach(id =>
            if !info.allowedKeys.contains(id) then store.allowAs(info.id, id, permission)
          )
          StorageCredential.Result.Unchanged
        case SecretWriter.Outcome.Exists =>
          secrets.patch(namespace, secretName, entries(issued))
          StorageCredential.Result.Replaced
      ensured.add((namespace, secretName)): Unit
      result

  /**
   * A bucket made again shows no key allowed; the keys named for it are allowed on it once more.
   */
  private def reallow(info: BucketInfo, keyName: String, permission: BucketPermission): Unit =
    if info.allowedKeys.isEmpty then
      store.keysNamed(keyName).foreach(store.allowAs(info.id, _, permission))

  private def existing(bucket: String): BucketInfo =
    store
      .bucket(bucket)
      .getOrElse(throw new IllegalStateException(s"the bucket $bucket has not been made"))

  /**
   * Issues the credential of a new generation and writes it where the pod reads it (feature 039).
   *
   * Called only while the generation in place is behind the one a member asked for, so it always
   * issues: a key of this generation already in the store is one a pass made and was cut off before
   * writing, whose secret no Secret holds, and it is deleted first. Every other key of the bucket
   * is set to stop working at `expireAt`, once — an expiry already set is never moved — so an
   * instance not yet replaced keeps working through the grace and is refused after it.
   */
  def reissue(
      namespace: String,
      secretName: String,
      bucket: String,
      generation: Int,
      expireAt: Instant
  ): Unit =
    val info = existing(bucket)
    val name = StorageCredential.keyName(bucket, generation)
    store.keysNamed(name).foreach(store.deleteKey)
    val issued = store.createKey(name)
    store.allow(info.id, issued.accessKeyId)
    secrets.patch(namespace, secretName, StorageCredential.entries(issued))
    store
      .keysOf(bucket)
      .filter(k => k.accessKeyId != issued.accessKeyId && k.expiration.isEmpty)
      .foreach(k => store.expire(k.accessKeyId, expireAt))
    ensured.add((namespace, secretName)): Unit

  /**
   * A new key of the same name in the same Secret, the old ones deleted (feature 041, FR-003a):
   * what a member's re-issue of a backup credential asks for. The Secret is patched, the one write
   * a credential Secret gets after its `create`; the old keys stop working at once, and the
   * archiver reads the Secret afresh at its next upload.
   */
  def reissueKey(
      namespace: String,
      secretName: String,
      bucket: String,
      keyName: String,
      permission: BucketPermission,
      entries: IssuedKey => Map[String, String]
  ): Unit =
    val info    = existing(bucket)
    val earlier = store.keysNamed(keyName)
    val issued  = store.createKey(keyName)
    store.allowAs(info.id, issued.accessKeyId, permission)
    secrets.patch(namespace, secretName, entries(issued))
    earlier.foreach(store.deleteKey)
    ensured.add((namespace, secretName)): Unit

  /** Deletes the keys of the bucket the store says have expired; never the one in place. */
  def deleteExpired(bucket: String): Unit =
    store.keysOf(bucket).filter(_.expired).foreach(k => store.deleteKey(k.accessKeyId))

  /**
   * A move's write pause (feature 039): the key in place keeps reading and may no longer write,
   * from now, for every instance at once. Nothing is rewritten, so nothing rolls.
   */
  def pauseWrites(bucket: String, generation: Int): Unit =
    val info = existing(bucket)
    store.keysNamed(StorageCredential.keyName(bucket, generation)).foreach(store.deny(info.id, _))

  /** Ends a write pause on Garage: the key in place writes again. */
  def resumeWrites(bucket: String, generation: Int): Unit =
    val info = existing(bucket)
    store.keysNamed(StorageCredential.keyName(bucket, generation)).foreach(store.allow(info.id, _))

  private def secret(
      namespace: String,
      name: String,
      labels: Map[String, String],
      entries: Map[String, String]
  ): Secret =
    new SecretBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withNamespace(namespace)
          .withName(name)
          .withLabels(labels.asJava)
          .build()
      )
      .withType("Opaque")
      .withStringData(entries.asJava)
      .build()

object StorageCredential:

  /**
   * The name of the key of a credential's generation (feature 039): the bucket's for generation 0,
   * which every key made before the feature is, and the bucket's and the generation's after.
   */
  def keyName(bucket: String, generation: Int): String =
    if generation == 0 then bucket else s"$bucket#$generation"

  val AccessKeyEntry: String = "ANKKA_S3_ACCESS_KEY"
  val SecretKeyEntry: String = "ANKKA_S3_SECRET_KEY"

  def entries(key: IssuedKey): Map[String, String] =
    Map(AccessKeyEntry -> key.accessKeyId, SecretKeyEntry -> key.secretAccessKey)

  /** The three a database's archiver reads (feature 041), by the names its ObjectStore gives. */
  val BackupAccessKeyEntry: String = "ACCESS_KEY_ID"
  val BackupSecretKeyEntry: String = "ACCESS_SECRET_KEY"
  val BackupRegionEntry: String    = "REGION"

  def backupEntries(region: String)(key: IssuedKey): Map[String, String] =
    Map(
      BackupAccessKeyEntry -> key.accessKeyId,
      BackupSecretKeyEntry -> key.secretAccessKey,
      BackupRegionEntry    -> region
    )

  enum Result:
    /** The Secret already held a working credential, and still does. */
    case Unchanged

    /** A credential was issued and written into a new Secret. */
    case Created

    /**
     * The store held no key for the service, so the Secret was given a new one. A running instance
     * still has the old one in its environment until it is restarted.
     */
    case Replaced
