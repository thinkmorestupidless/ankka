package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.{ObjectMetaBuilder, Secret, SecretBuilder}

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

  def ensure(
      namespace: String,
      secretName: String,
      labels: Map[String, String],
      bucket: String
  ): StorageCredential.Result =
    val info = store
      .bucket(bucket)
      .getOrElse(throw new IllegalStateException(s"the bucket $bucket has not been made"))
    if ensured.contains((namespace, secretName)) then
      reallow(info, bucket)
      StorageCredential.Result.Unchanged
    else
      val earlier = store.keysNamed(bucket)
      val issued  = store.createKey(bucket)
      store.allow(info.id, issued.accessKeyId)
      val result = secrets.create(secret(namespace, secretName, labels, issued)) match
        case SecretWriter.Outcome.Created =>
          earlier.foreach(store.deleteKey)
          StorageCredential.Result.Created
        case SecretWriter.Outcome.Exists if earlier.nonEmpty =>
          store.deleteKey(issued.accessKeyId)
          earlier.foreach(id => if !info.allowedKeys.contains(id) then store.allow(info.id, id))
          StorageCredential.Result.Unchanged
        case SecretWriter.Outcome.Exists =>
          secrets.patch(namespace, secretName, StorageCredential.entries(issued))
          StorageCredential.Result.Replaced
      ensured.add((namespace, secretName)): Unit
      result

  /** A bucket made again shows no key allowed; the service's keys are allowed on it once more. */
  private def reallow(info: BucketInfo, bucket: String): Unit =
    if info.allowedKeys.isEmpty then store.keysNamed(bucket).foreach(store.allow(info.id, _))

  private def secret(
      namespace: String,
      name: String,
      labels: Map[String, String],
      key: IssuedKey
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
      .withStringData(StorageCredential.entries(key).asJava)
      .build()

object StorageCredential:

  val AccessKeyEntry: String = "ANKKA_S3_ACCESS_KEY"
  val SecretKeyEntry: String = "ANKKA_S3_SECRET_KEY"

  def entries(key: IssuedKey): Map[String, String] =
    Map(AccessKeyEntry -> key.accessKeyId, SecretKeyEntry -> key.secretAccessKey)

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
