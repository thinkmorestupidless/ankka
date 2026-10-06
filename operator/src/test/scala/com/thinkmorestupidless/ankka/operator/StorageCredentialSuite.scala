package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.Secret

import java.time.Instant
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Issuing a service's storage credential (feature 034, research R6), against doubles of the store
 * and of the cluster's Secrets.
 *
 * The operator never reads a Secret back and never asks the store for a secret again, so the only
 * thing it learns about the Secret is whether its `create` met one that exists. Every case here is
 * a state the two can be in when a pass starts, including the ones an interrupted pass leaves.
 */
class StorageCredentialSuite extends munit.FunSuite:

  private val Namespace = "ankka-shop"
  private val Name      = "reports-storage"
  private val Bucket    = "shop.reports"
  private val Labels    = Map("app.kubernetes.io/name" -> "reports")

  /** The calls made, in order, by both doubles. */
  private class World:
    val calls        = mutable.ArrayBuffer.empty[String]
    val keys         = mutable.LinkedHashMap.empty[String, String] // id -> name
    val allowed      = mutable.Set.empty[String]
    val secrets      = mutable.Map.empty[(String, String), Map[String, String]]
    var created      = Option.empty[Secret]
    var failing      = false
    private var next = 0

    val store: ObjectStore = new ObjectStore:
      def bucket(name: String): Option[BucketInfo] =
        Some(BucketInfo("bucket-1", Instant.EPOCH, allowed.toSet))
      def createBucket(name: String): BucketInfo = bucket(name).get
      def keysNamed(name: String): Vector[String] =
        calls += "keysNamed"; keys.collect { case (id, `name`) => id }.toVector
      def createKey(name: String): IssuedKey =
        calls += "createKey"
        if failing then throw new ObjectStoreUnavailable("down")
        next += 1
        val id = s"GK$next"
        keys(id) = name
        IssuedKey(id, s"secret-$next")
      def deleteKey(accessKeyId: String): Unit =
        calls += s"deleteKey $accessKeyId"; keys -= accessKeyId: Unit
      def allow(bucketId: String, accessKeyId: String): Unit =
        calls += s"allow $accessKeyId"; allowed += accessKeyId: Unit

    val writer: SecretWriter = new SecretWriter:
      def create(secret: Secret): SecretWriter.Outcome =
        calls += "create"
        val key = (secret.getMetadata.getNamespace, secret.getMetadata.getName)
        if secrets.contains(key) then SecretWriter.Outcome.Exists
        else
          created = Some(secret)
          secrets(key) = secret.getStringData.asScala.toMap
          SecretWriter.Outcome.Created
      def patch(namespace: String, name: String, entries: Map[String, String]): Unit =
        calls += "patch"
        secrets((namespace, name)) = secrets.getOrElse((namespace, name), Map.empty) ++ entries

    val credentials = StorageCredential(store, writer)

    def ensure(): StorageCredential.Result =
      credentials.ensure(Namespace, Name, Labels, Bucket)

    def held: Map[String, String] = secrets((Namespace, Name))

  test("with nothing issued, one key is issued, allowed and written into the Secret") {
    val w = World()
    assertEquals(w.ensure(), StorageCredential.Result.Created)
    assertEquals(w.keys.keySet.toSet, Set("GK1"))
    assertEquals(w.allowed.toSet, Set("GK1"))
    assertEquals(
      w.held,
      Map(
        StorageCredential.AccessKeyEntry -> "GK1",
        StorageCredential.SecretKeyEntry -> "secret-1"
      )
    )
  }

  test("the key is allowed on the bucket before the Secret is created") {
    val w = World()
    w.ensure(): Unit
    assert(w.calls.indexOf("allow GK1") < w.calls.indexOf("create"), w.calls.mkString(", "))
  }

  test("the Secret is opaque, labelled as given, owned by nothing, and holds exactly two entries") {
    val w = World()
    w.ensure(): Unit
    val secret = w.created.getOrElse(fail("no Secret was created"))
    assertEquals(secret.getType, "Opaque")
    assertEquals(secret.getMetadata.getNamespace, Namespace)
    assertEquals(secret.getMetadata.getName, Name)
    assertEquals(secret.getMetadata.getLabels.asScala.toMap, Labels)
    assert(
      secret.getMetadata.getOwnerReferences == null || secret.getMetadata.getOwnerReferences.isEmpty
    )
    assertEquals(
      secret.getStringData.keySet.asScala.toSet,
      Set(StorageCredential.AccessKeyEntry, StorageCredential.SecretKeyEntry)
    )
  }

  test(
    "keys left by an interrupted pass, and no Secret: the new key is written, the others deleted"
  ) {
    val w = World()
    w.keys("GK-stale") = Bucket
    assertEquals(w.ensure(), StorageCredential.Result.Created)
    assertEquals(w.keys.keySet.toSet, Set("GK1"))
    assertEquals(w.held(StorageCredential.AccessKeyEntry), "GK1")
    assert(w.calls.contains("deleteKey GK-stale"))
  }

  test("a key and a Secret: the key just issued is deleted, and the Secret is not touched") {
    val w = World()
    w.keys("GK0") = Bucket
    w.secrets((Namespace, Name)) =
      Map(StorageCredential.AccessKeyEntry -> "GK0", StorageCredential.SecretKeyEntry -> "old")
    assertEquals(w.ensure(), StorageCredential.Result.Unchanged)
    assertEquals(w.keys.keySet.toSet, Set("GK0"))
    assertEquals(w.held(StorageCredential.AccessKeyEntry), "GK0")
    assert(!w.calls.contains("patch"), w.calls.mkString(", "))
  }

  test("a Secret, and no key in the store: the Secret is patched with the key just issued") {
    val w = World()
    w.secrets((Namespace, Name)) =
      Map(StorageCredential.AccessKeyEntry -> "GK-gone", StorageCredential.SecretKeyEntry -> "old")
    assertEquals(w.ensure(), StorageCredential.Result.Replaced)
    assertEquals(w.held(StorageCredential.AccessKeyEntry), "GK1")
    assertEquals(w.held(StorageCredential.SecretKeyEntry), "secret-1")
  }

  test("a project secret's own entries survive the patch") {
    val w = World()
    w.secrets((Namespace, Name)) = Map("MINE" -> "kept")
    w.ensure(): Unit
    assertEquals(w.held("MINE"), "kept")
  }

  test("a second ensure of the same Secret in one process asks nothing of anyone") {
    val w = World()
    w.ensure(): Unit
    w.calls.clear()
    assertEquals(w.ensure(), StorageCredential.Result.Unchanged)
    assertEquals(w.calls.toVector, Vector.empty[String])
  }

  test("a store that fails leaves no Secret, and the next pass tries again") {
    val w = World()
    w.failing = true
    intercept[ObjectStoreUnavailable](w.ensure())
    assert(!w.secrets.contains((Namespace, Name)))
    w.failing = false
    assertEquals(w.ensure(), StorageCredential.Result.Created)
  }

  test("an existing Secret's key is allowed on the bucket again when the bucket shows none") {
    // A service may delete its own empty bucket; the operator makes it again, and the key the
    // Secret holds has to reach it.
    val w = World()
    w.keys("GK0") = Bucket
    w.secrets((Namespace, Name)) =
      Map(StorageCredential.AccessKeyEntry -> "GK0", StorageCredential.SecretKeyEntry -> "old")
    w.ensure(): Unit
    assert(w.allowed.contains("GK0"), w.calls.mkString(", "))
  }
