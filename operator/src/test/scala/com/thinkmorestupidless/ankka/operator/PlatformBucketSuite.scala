package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.Secret

import java.time.Instant
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * The platform's bucket for the erasure log (feature 042), against doubles of the store and of the
 * cluster's Secrets: made once, a credential per reader with a key of its own, where the bucket is
 * written beside each key, and a reader whose namespace is absent passed over.
 */
class PlatformBucketSuite extends munit.FunSuite:

  private val Settings = ObjectStoreSettings(
    "http://garage-admin:3903",
    "token",
    "http://garage.garage-system.svc:3900",
    "garage",
    ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
  )

  private class World:
    val buckets      = mutable.Map.empty[String, mutable.Set[String]] // name -> allowed keys
    val keys         = mutable.LinkedHashMap.empty[String, String]    // id -> name
    val secrets      = mutable.Map.empty[(String, String), Map[String, String]]
    var made         = 0
    private var next = 0

    val store: ObjectStore = new ObjectStore:
      def bucket(name: String): Option[BucketInfo] =
        buckets.get(name).map(allowed => BucketInfo(name, Instant.EPOCH, allowed.toSet))
      def createBucket(name: String): BucketInfo =
        made += 1
        buckets(name) = mutable.Set.empty
        bucket(name).get
      def keysNamed(name: String): Vector[String] = keys.collect { case (id, `name`) =>
        id
      }.toVector
      def createKey(name: String): IssuedKey =
        next += 1
        keys(s"GK$next") = name
        IssuedKey(s"GK$next", s"secret-$next")
      def deleteKey(accessKeyId: String): Unit = keys -= accessKeyId: Unit
      def allow(bucketId: String, accessKeyId: String): Unit =
        buckets(bucketId) += accessKeyId: Unit

    val writer: SecretWriter = new SecretWriter:
      def create(secret: Secret): SecretWriter.Outcome =
        val key = (secret.getMetadata.getNamespace, secret.getMetadata.getName)
        if secrets.contains(key) then SecretWriter.Outcome.Exists
        else
          secrets(key) = secret.getStringData.asScala.toMap
          SecretWriter.Outcome.Created
      def patch(namespace: String, name: String, entries: Map[String, String]): Unit =
        secrets((namespace, name)) = secrets.getOrElse((namespace, name), Map.empty) ++ entries

    def ensure(credentials: StorageCredential, exists: String => Boolean = _ => true): Unit =
      PlatformBucket.ensure(store, credentials, Settings, exists)

  test("the bucket is made, and each reader is given a key of its own, allowed on it") {
    val w = World()
    w.ensure(StorageCredential(w.store, w.writer))
    assertEquals(w.made, 1)
    val held = PlatformBucket.Readers.map(ns => w.secrets((ns, PlatformBucket.SecretName)))
    val ids  = held.map(_("ANKKA_S3_ACCESS_KEY"))
    assertEquals(ids.distinct.size, 2, "one key per reader")
    assertEquals(w.buckets(PlatformBucket.Name).toSet, ids.toSet, "both allowed, neither deleted")
    assertEquals(w.keys.keySet.toSet, ids.toSet)
  }

  test("each Secret says where the bucket is beside its key") {
    val w = World()
    w.ensure(StorageCredential(w.store, w.writer))
    val held = w.secrets(("ankka-keyring", PlatformBucket.SecretName))
    assertEquals(held("ANKKA_S3_ENDPOINT"), Settings.endpoint)
    assertEquals(held("ANKKA_S3_REGION"), "garage")
    assertEquals(held("ANKKA_S3_BUCKET"), PlatformBucket.Name)
    assertEquals(
      held.keySet,
      PlatformBucket.entries(Settings).keySet ++ Set("ANKKA_S3_ACCESS_KEY", "ANKKA_S3_SECRET_KEY")
    )
  }

  test("a restarted operator makes nothing again and deletes no reader's key") {
    val w = World()
    w.ensure(StorageCredential(w.store, w.writer))
    val before = w.keys.keySet.toSet
    w.ensure(StorageCredential(w.store, w.writer))
    assertEquals(w.made, 1)
    assertEquals(
      before.subsetOf(w.keys.keySet.toSet) && before.subsetOf(w.buckets(PlatformBucket.Name).toSet),
      true,
      s"$before then ${w.keys.keySet}"
    )
  }

  test("a reader whose namespace does not exist is passed over, and the other still served") {
    val w = World()
    w.ensure(StorageCredential(w.store, w.writer), exists = _ != "ankka-keyring")
    assertEquals(w.secrets.keySet.toSet, Set(("ankka-controlplane", PlatformBucket.SecretName)))
  }

  test("the bucket is in the reserved project, so no service's bucket can share its name") {
    assert(PlatformBucket.Name.startsWith("platform."))
  }
