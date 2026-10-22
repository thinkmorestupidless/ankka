package com.thinkmorestupidless.ankka.operator

/**
 * One key over several buckets (feature 055, R8): the store double keeps each bucket's grants, so a
 * case can say which bucket a key reaches, and records every write it is asked for.
 */
class StorageCredentialBucketsSuite extends munit.FunSuite:
  import scala.collection.mutable
  import java.time.Instant

  private final class Store extends ObjectStore:
    val calls     = mutable.ArrayBuffer.empty[String]
    val buckets   = mutable.LinkedHashMap.empty[String, BucketInfo]
    val writers   = mutable.Map.empty[String, Set[String]].withDefaultValue(Set.empty)
    val keys      = mutable.LinkedHashMap.empty[String, String] // id -> name
    private var n = 0
    def bucket(name: String): Option[BucketInfo] = buckets.get(name)
    def createBucket(name: String): BucketInfo =
      calls += s"create $name"
      val made = BucketInfo(s"id-$name", Instant.EPOCH, Set.empty)
      buckets(name) = made
      made
    def keysNamed(name: String): Vector[String] = keys.collect { case (id, `name`) => id }.toVector
    def createKey(name: String): IssuedKey =
      n += 1
      keys(s"GK$n") = name
      IssuedKey(s"GK$n", s"secret-$n")
    def deleteKey(id: String): Unit = keys -= id: Unit
    def allow(bucketId: String, id: String, write: Boolean): Unit =
      calls += s"allow $id on $bucketId"
      update(bucketId)(b => b.copy(allowedKeys = b.allowedKeys + id))
      if write then writers(bucketId) = writers(bucketId) + id
    def setCors(bucketId: String, origins: Seq[String]): Unit = ()
    def setWebsite(bucketId: String, enabled: Boolean): Unit =
      calls += s"website $enabled on $bucketId"
      update(bucketId)(_.copy(website = enabled))
    def expire(id: String, at: Instant): Unit = ()
    def keyInfo(id: String): Option[KeyInfo]  = keys.get(id).map(KeyInfo(id, _, false))
    def keysOf(bucket: String): Vector[KeyInfo] =
      keys.collect {
        case (id, name) if name == bucket || name.startsWith(bucket + "#") =>
          KeyInfo(id, name, false)
      }.toVector
    def deny(bucketId: String, id: String): Unit =
      calls += s"deny $id on $bucketId"
      writers(bucketId) = writers(bucketId) - id
    private def update(id: String)(f: BucketInfo => BucketInfo): Unit =
      buckets.find(_._2.id == id).foreach((name, b) => buckets(name) = f(b))

  private final class Secrets extends SecretWriter:
    def create(secret: io.fabric8.kubernetes.api.model.Secret): SecretWriter.Outcome =
      SecretWriter.Outcome.Created
    def patch(namespace: String, name: String, entries: Map[String, String]): Unit = ()

  private val three = Seq("shop.listings", "shop.listings.photos", "shop.listings.documents")

  private def world =
    val store = Store()
    three.foreach(store.createBucket)
    (store, StorageCredential(store, Secrets()))

  test("one key is issued for the service and allowed on every bucket the platform made for it") {
    val (store, credential) = world
    credential.ensure("ankka-shop", "listings-storage", Map.empty, "shop.listings", buckets = three)
    assertEquals(store.keys.values.toVector, Vector("shop.listings"))
    val key = store.keys.keys.head
    three.foreach(b => assertEquals(store.buckets(b).allowedKeys, Set(key), b))
  }

  test("a service with only named buckets has its key named for it all the same") {
    val (store, credential) = world
    val named               = three.tail
    credential.ensure("ankka-shop", "listings-storage", Map.empty, "shop.listings", buckets = named)
    assertEquals(store.keys.values.toVector, Vector("shop.listings"))
    assertEquals(store.buckets("shop.listings").allowedKeys, Set.empty[String])
    named.foreach(b => assertEquals(store.buckets(b).allowedKeys.size, 1, b))
  }

  test("a credential issued again is allowed on every bucket") {
    val (store, credential) = world
    credential.ensure("ankka-shop", "listings-storage", Map.empty, "shop.listings", buckets = three)
    credential.reissue(
      "ankka-shop",
      "listings-storage",
      "shop.listings",
      1,
      Instant.EPOCH,
      buckets = three
    )
    val renewed = store.keysNamed("shop.listings#1")
    assertEquals(renewed.size, 1)
    three.foreach(b => assert(store.buckets(b).allowedKeys.contains(renewed.head), b))
  }

  test("a write pause takes write from the key on every bucket, and the end gives it back") {
    val (store, credential) = world
    credential.ensure("ankka-shop", "listings-storage", Map.empty, "shop.listings", buckets = three)
    val key = store.keys.keys.head
    credential.pauseWrites("shop.listings", 0, three)
    three.foreach(b => assert(!store.writers(s"id-$b").contains(key), b))
    credential.resumeWrites("shop.listings", 0, three)
    three.foreach(b => assert(store.writers(s"id-$b").contains(key), b))
  }

  test("a named bucket added later is made and allowed the key already issued") {
    val (store, credential) = world
    credential.ensure("ankka-shop", "listings-storage", Map.empty, "shop.listings", buckets = three)
    StoreActions.ensureBucket(store, "shop.listings.exports", Some("shop.listings"))
    assertEquals(store.buckets("shop.listings.exports").allowedKeys, Set(store.keys.keys.head))
    val before = store.calls.size
    StoreActions.ensureBucket(store, "shop.listings.exports", Some("shop.listings"))
    assertEquals(store.calls.size, before, "a key allowed is not allowed again")
  }

  test("a bucket reached only by another key is not the service's key allowed") {
    val (store, credential) = world
    credential.ensure("ankka-shop", "listings-storage", Map.empty, "shop.listings", buckets = three)
    val copy = store.createKey("garage-copy")
    store.createBucket("shop.listings.exports")
    store.allow("id-shop.listings.exports", copy.accessKeyId, write = true)
    val seen = StoreActions.observe(
      store,
      Vector("shop.listings.photos", "shop.listings.exports", "shop.listings.missing"),
      "shop.listings"
    )
    assertEquals(seen("shop.listings.photos").keyAllowed, true)
    assertEquals(seen("shop.listings.exports").keyAllowed, false)
    assertEquals(seen("shop.listings.missing"), ObjectStorageObservation.empty)
  }

  test("website access is written only when the store says otherwise") {
    val (store, _) = world
    StoreActions.setWebsite(store, "shop.listings.photos", enabled = false)
    assertEquals(store.calls.count(_.startsWith("website")), 0)
    StoreActions.setWebsite(store, "shop.listings.photos", enabled = true)
    StoreActions.setWebsite(store, "shop.listings.photos", enabled = true)
    assertEquals(store.calls.count(_.startsWith("website")), 1)
    assertEquals(store.buckets("shop.listings.photos").website, true)
  }

  test("a key over several buckets is described by its list, and a lone bucket as before") {
    assertEquals(
      Action.EnsureStorageCredential("ns", "listings-storage", Map.empty, "shop.listings").describe,
      "ensure storage credential ns/listings-storage for bucket shop.listings (create-if-absent)"
    )
    assertEquals(
      Action
        .EnsureStorageCredential(
          "ns",
          "s",
          Map.empty,
          "shop.listings",
          0,
          List("shop.listings.photos")
        )
        .describe,
      "ensure storage credential ns/s for buckets shop.listings.photos (key shop.listings) (create-if-absent)"
    )
    assertEquals(
      Action.PauseWrites("casino.kyc", 3).describe,
      "pause writes to bucket casino.kyc (key of generation 3)"
    )
  }
