package com.thinkmorestupidless.ankka.operator

import org.slf4j.{Logger, LoggerFactory}

/**
 * The executor's work on the object store alone, with no cluster (features 034 and 055): what
 * `EnsureBucket`, `SetWebsiteAccess` and the observation of a service's buckets do. Here rather
 * than in `Fabric8Executor` so a suite can hold it to a store double with no Kubernetes client.
 */
object StoreActions:

  private val log: Logger = LoggerFactory.getLogger("ankka.operator.executor")

  /**
   * Makes the bucket when it is absent, never deletes it. With `keyName`, every key of that name
   * not yet allowed on the bucket is allowed: a named bucket added after the service's credential
   * was issued is reached with the same key (feature 055, R8). Without it, as feature 034 does: a
   * bucket the service deleted and the operator made again shows no key, and the keys of its own
   * name are allowed on it once more, unless they have ended.
   */
  def ensureBucket(store: ObjectStore, bucket: String, keyName: Option[String]): Unit =
    val info = store.bucket(bucket) match
      case None =>
        val made = store.createBucket(bucket)
        log.info("made bucket {}", bucket)
        made
      case Some(found) if found.allowedKeys.isEmpty && keyName.isEmpty =>
        store
          .keysOf(bucket)
          .filterNot(_.expired)
          .foreach(k => store.allow(found.id, k.accessKeyId))
        found
      case Some(found) => found
    keyName.foreach { name =>
      store.keysNamed(name).filterNot(info.allowedKeys.contains).foreach { id =>
        store.allow(info.id, id)
        log.info("allowed the key {} on bucket {}", name, bucket)
      }
    }

  /** Serves the bucket at the store's web endpoint, or stops; writes only when it differs. */
  def setWebsite(store: ObjectStore, bucket: String, enabled: Boolean): Unit =
    store.bucket(bucket).foreach { info =>
      if info.website != enabled then
        store.setWebsite(info.id, enabled)
        log.info(
          "{} bucket {} at the store's web endpoint",
          if enabled then "serving" else "not serving",
          bucket
        )
    }

  /**
   * One observation per bucket (feature 055): when it was made, and whether the service's own key,
   * any key named `keyName` or for it and a generation that has not ended, is allowed on it. Not
   * merely any key: a move's copy key allowed on a bucket is not the service's (R8). A store that
   * cannot be reached is an answer for every bucket.
   */
  def observe(
      store: ObjectStore,
      buckets: Vector[String],
      keyName: String
  ): Map[String, ObjectStorageObservation] =
    try
      val own = store.keysOf(keyName).filterNot(_.expired).map(_.accessKeyId).toSet
      buckets.map { bucket =>
        bucket -> store
          .bucket(bucket)
          .fold(ObjectStorageObservation.empty)(info =>
            ObjectStorageObservation(
              bucketCreated = Some(info.created),
              keyAllowed = info.allowedKeys.exists(own.contains)
            )
          )
      }.toMap
    catch
      case e: ObjectStoreUnavailable =>
        buckets.map(_ -> ObjectStorageObservation(unreachable = Some(e.getMessage))).toMap
