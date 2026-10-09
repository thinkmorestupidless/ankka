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
    val calls                        = mutable.ArrayBuffer.empty[String]
    val keys                         = mutable.LinkedHashMap.empty[String, String] // id -> name
    val allowed                      = mutable.Set.empty[String]
    val secrets                      = mutable.Map.empty[(String, String), Map[String, String]]
    var created                      = Option.empty[Secret]
    var failing                      = false
    val writers                      = mutable.Set.empty[String]
    val expiries                     = mutable.Map.empty[String, Instant]
    var cors                         = Seq.empty[String]
    var now                          = Instant.parse("2026-10-09T10:00:00Z")
    def expired(id: String): Boolean = expiries.get(id).exists(!_.isAfter(now))
    private var next                 = 0

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
      def allow(bucketId: String, accessKeyId: String, write: Boolean): Unit =
        calls += (if write then s"allow $accessKeyId" else s"allow-read $accessKeyId")
        allowed += accessKeyId
        if write then writers += accessKeyId: Unit
      def setCors(bucketId: String, origins: Seq[String]): Unit =
        calls += s"cors ${origins.mkString(",")}"; cors = origins
      def expire(accessKeyId: String, at: Instant): Unit =
        calls += s"expire $accessKeyId"; expiries(accessKeyId) = at
      def keyInfo(accessKeyId: String): Option[KeyInfo] =
        keys.get(accessKeyId).map(info(accessKeyId, _))
      def keysOf(bucket: String): Vector[KeyInfo] =
        calls += "keysOf"
        keys.collect {
          case (id, name) if name == bucket || name.startsWith(bucket + "#") => info(id, name)
        }.toVector
      def deny(bucketId: String, accessKeyId: String): Unit =
        calls += s"deny $accessKeyId"; writers -= accessKeyId: Unit

    private def info(id: String, name: String) =
      KeyInfo(id, name, expired(id), expiries.get(id))

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

    val Grace = java.time.Duration.ofHours(1)

    def reissue(generation: Int): Unit =
      credentials.reissue(Namespace, Name, Bucket, generation, now.plus(Grace))

    def named(name: String): Vector[String] = keys.collect { case (id, `name`) => id }.toVector

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

  // Feature 039: a credential issued again, on Garage, and a move's write pause.

  private def issued(w: World): World =
    w.ensure(): Unit
    w.calls.clear()
    w

  test(
    "issuing again makes a key of the new generation, writes it, and expires the old one after the grace"
  ) {
    val w = issued(World())
    w.reissue(1)
    val fresh = w.named(s"$Bucket#1")
    assertEquals(fresh.size, 1)
    assertEquals(w.held(StorageCredential.AccessKeyEntry), fresh.head)
    assert(w.writers.contains(fresh.head), "the new key writes")
    assertEquals(
      w.expiries.get("GK1"),
      Some(w.now.plus(w.Grace)),
      "the first key ends after the grace"
    )
    assertEquals(w.expiries.get(fresh.head), None, "the new key does not end")
    assert(w.calls.indexOf(s"allow ${fresh.head}") < w.calls.indexOf("patch"), w.calls.toString)
  }

  test("an expiry already set is never moved, so a key's grace ends when it first began") {
    val w = issued(World())
    w.reissue(1)
    val first = w.expiries("GK1")
    w.now = w.now.plusSeconds(600)
    w.reissue(2)
    assertEquals(w.expiries("GK1"), first)
  }

  test(
    "a pass cut off after issuing and before writing issues the key again, so the Secret holds one that exists"
  ) {
    val w = issued(World())
    w.keys("GK-orphan") = s"$Bucket#1" // issued; its secret never reached the Secret
    w.reissue(1)
    assert(!w.keys.contains("GK-orphan"), "a key whose secret no Secret holds is deleted")
    assertEquals(w.named(s"$Bucket#1"), Vector(w.held(StorageCredential.AccessKeyEntry)))
  }

  test("only expired keys of the service's bucket are deleted, and never the one in place") {
    val w = issued(World())
    w.keys("GK-other") = "shop.ledger"
    w.expiries("GK-other") = w.now.minusSeconds(1)
    w.reissue(1)
    w.credentials.deleteExpired(Bucket)
    assert(w.keys.contains("GK1"), "the first key is still in its grace")
    w.now = w.now.plus(w.Grace)
    w.credentials.deleteExpired(Bucket)
    assert(!w.keys.contains("GK1"), "the first key is gone once its grace has passed")
    assert(w.keys.contains("GK-other"), "another bucket's key is not this service's to delete")
    assertEquals(w.named(s"$Bucket#1").size, 1)
  }

  test(
    "after a restart, ensuring a re-issued credential finds the key of its generation and changes nothing"
  ) {
    // The trap: 034's ensure looked for keys under the bare bucket name, found none once
    // generation 0 was gone, and would patch the Secret back to a key of generation 0.
    val w = issued(World())
    w.reissue(2)
    w.keys -= "GK1"
    val held  = w.held
    val fresh = StorageCredential(w.store, w.writer) // a new operator process
    assertEquals(
      fresh.ensure(Namespace, Name, Labels, Bucket, generation = 2),
      StorageCredential.Result.Unchanged
    )
    assertEquals(w.held, held)
  }

  test("a write pause takes write from the key in place at once, and gives it back") {
    val w = issued(World())
    w.credentials.pauseWrites(Bucket, generation = 0)
    assert(!w.writers.contains("GK1"), "the key in place no longer writes")
    assert(w.allowed.contains("GK1"), "and still reads")
    assertEquals(w.secrets((Namespace, Name)), w.held, "the Secret is untouched: nothing rolls")
    w.credentials.resumeWrites(Bucket, generation = 0)
    assert(w.writers.contains("GK1"))
  }
