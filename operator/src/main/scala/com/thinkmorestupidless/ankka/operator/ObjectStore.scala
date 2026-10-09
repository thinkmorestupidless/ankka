package com.thinkmorestupidless.ankka.operator

import java.time.Instant

/**
 * The installation's object store, as the operator administers it (feature 034).
 *
 * Six operations, which are the whole of what giving a service a bucket needs: a bucket, an access
 * key, and a permission of the one on the other. Garage implements it today (`GarageStore`); a
 * cloud provider's buckets would be a second implementation, and the logic that issues a credential
 * (`StorageCredential`) is written against this, not against Garage.
 *
 * Nothing here asks the store for a key's secret. A secret is returned once, by `createKey`, and
 * the operator writes it into one Secret and holds it nowhere else.
 *
 * Every operation that cannot reach the store, or that the store answers with a fault of its own,
 * throws `ObjectStoreUnavailable`. Anything else — a refusal, a malformed answer — throws something
 * that is not, so a store that is down reads as waiting and a store that says no reads as a
 * failure.
 */
trait ObjectStore:

  /** The bucket of this name, or `None` when there is none. */
  def bucket(name: String): Option[BucketInfo]

  def createBucket(name: String): BucketInfo

  /** The access key ids whose name is exactly this one. */
  def keysNamed(name: String): Vector[String]

  /** A new access key of this name. Its secret is in the answer and nowhere else, ever. */
  def createKey(name: String): IssuedKey

  def deleteKey(accessKeyId: String): Unit

  /**
   * Lets the key read the bucket and, when `write`, write and own it. Allowing what is allowed
   * changes nothing. A key that may not write is how a move pauses a service's writes (feature
   * 039).
   */
  def allow(bucketId: String, accessKeyId: String, write: Boolean = true): Unit

  /**
   * Replaces the bucket's CORS rules with one admitting exactly these origins, for signed reads and
   * writes from a browser; no origins removes every rule (feature 039: the platform sets a bucket's
   * rule, from the descriptor, so a service's code sets nothing).
   */
  def setCors(bucketId: String, origins: Seq[String]): Unit

  /** Makes the key stop working at `at`, by the store's own clock. */
  def expire(accessKeyId: String, at: Instant): Unit

  /**
   * The key's name and whether it has expired, or `None` when there is no such key. Never its
   * secret.
   */
  def keyInfo(accessKeyId: String): Option[KeyInfo]

/**
 * @param allowedKeys
 *   the access key ids allowed on the bucket
 */
final case class BucketInfo(id: String, created: Instant, allowedKeys: Set[String])

/** An access key as the store describes it, without its secret. */
final case class KeyInfo(accessKeyId: String, name: String, expired: Boolean)

/** An access key as the store issued it. `toString` prints the id alone. */
final case class IssuedKey(accessKeyId: String, secretAccessKey: String):
  override def toString: String = s"IssuedKey($accessKeyId)"

/** The store could not be reached, or answered with a fault of its own. */
final class ObjectStoreUnavailable(reason: String, cause: Throwable = null)
    extends RuntimeException(reason, cause)
