package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd
import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, Buckets, EnvEntry, ObjectStorageStatus}

import java.time.Instant

/**
 * What the operator decides about a service's bucket (feature 034): the rule table of
 * `contracts/operator.md`, one case per row, in the order the rules are tried.
 */
class ObjectStorageSuite extends munit.FunSuite:

  private val store = ObjectStoreSettings(
    "http://admin",
    "token",
    "http://garage.garage-system.svc.cluster.local:3900",
    "garage",
    ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
  )
  private val withStore    = Settings.default.copy(objectStore = Some(store))
  private val withoutStore = Settings.default

  private val asks = AnkkaServiceSpec(
    projectId = "shop",
    serviceName = "reports",
    image = "reports:1",
    provisionObjectStorage = true
  )
  private val supplies =
    AnkkaServiceSpec(
      projectId = "shop",
      serviceName = "reports",
      image = "reports:1",
      env = List(EnvEntry("ANKKA_S3_ENDPOINT", Some("https://s3.example.com"), None, None))
    )

  private val made    = Instant.parse("2026-10-04T10:00:00Z")
  private val earlier = Instant.parse("2026-10-01T10:00:00Z")
  private val ready =
    ObjectStorageObservation(
      bucketCreated = Some(made),
      keyAllowed = true,
      resourceCreatedAt = Some(earlier)
    )

  private def decide(spec: AnkkaServiceSpec, settings: Settings = withStore)(
      observed: ObjectStorageObservation = ObjectStorageObservation.empty
  ) = ObjectStorage.decide(spec, settings, observed, reported = None)

  test("1: a service that neither asks nor supplies is not asked about") {
    assertEquals(decide(asks.copy(provisionObjectStorage = false))(), ObjectStoragePlan.NotAsked)
  }

  test("2: a service that gives an ANKKA_S3_ variable has a store of its own") {
    assertEquals(decide(supplies)(), ObjectStoragePlan.Supplied)
    assertEquals(decide(supplies, withoutStore)(), ObjectStoragePlan.Supplied)
  }

  test("3: a name over the limit fails, naming it, even with a store and before asking it") {
    val long = asks.copy(projectId = "p" * 40, serviceName = "s" * 30)
    decide(long)(ready) match
      case ObjectStoragePlan.Failed(problems) => assert(problems.head.contains("63"), problems.head)
      case other                              => fail(s"expected Failed, got $other")
  }

  test("3 before 4: a name over the limit is the reason even when there is no store") {
    val long = asks.copy(projectId = "p" * 40, serviceName = "s" * 30)
    decide(long, withoutStore)() match
      case ObjectStoragePlan.Failed(problems) => assert(problems.head.contains("63"))
      case other                              => fail(s"expected Failed, got $other")
  }

  test("4: an installation with no store fails the service that asks, saying so") {
    assertEquals(
      decide(asks, withoutStore)(),
      ObjectStoragePlan.Failed(Vector(ObjectStorage.NoStore))
    )
  }

  test("4 before 5: no store is not a store that cannot be reached") {
    assertEquals(
      decide(asks, withoutStore)(ObjectStorageObservation(unreachable = Some("refused"))),
      ObjectStoragePlan.Failed(Vector(ObjectStorage.NoStore))
    )
  }

  test("5: a store that cannot be reached is waiting, with the reason") {
    assertEquals(
      decide(asks)(ObjectStorageObservation(unreachable = Some("connection refused"))),
      ObjectStoragePlan.Waiting(Some("connection refused"))
    )
  }

  test("6: no bucket yet is waiting") {
    assertEquals(decide(asks)(), ObjectStoragePlan.Waiting(None))
  }

  test("6: a bucket with no key allowed on it is waiting") {
    assertEquals(
      decide(asks)(ready.copy(keyAllowed = false)),
      ObjectStoragePlan.Waiting(None)
    )
  }

  test("7: a bucket older than the resource was recovered") {
    assertEquals(
      decide(asks)(ready.copy(bucketCreated = Some(earlier), resourceCreatedAt = Some(made))),
      ObjectStoragePlan.Ready(recovered = true)
    )
  }

  test("8: a bucket made for this incarnation was provisioned") {
    assertEquals(decide(asks)(ready), ObjectStoragePlan.Ready(recovered = false))
  }

  test("with no creation time for the resource, a bucket is not called recovered") {
    assertEquals(
      decide(asks)(ready.copy(resourceCreatedAt = None)),
      ObjectStoragePlan.Ready(recovered = false)
    )
  }

  test("asking, and giving a variable too, decides by the ask: the control plane refuses the pair") {
    assertEquals(
      decide(supplies.copy(provisionObjectStorage = true))(ready),
      ObjectStoragePlan.Ready(recovered = false)
    )
  }

  test("each plan reports the database's phase") {
    assertEquals(ObjectStoragePlan.NotAsked.reportedPhase, None)
    assertEquals(ObjectStoragePlan.Supplied.reportedPhase, Some("Supplied"))
    assertEquals(ObjectStoragePlan.Waiting(None).reportedPhase, Some("Waiting"))
    assertEquals(ObjectStoragePlan.Ready(false).reportedPhase, Some("Provisioned"))
    assertEquals(ObjectStoragePlan.Ready(true).reportedPhase, Some("Recovered"))
    assertEquals(ObjectStoragePlan.Failed(Vector("x")).reportedPhase, Some("Failed"))
  }

  test("the status of each plan") {
    def status(plan: ObjectStoragePlan, spec: AnkkaServiceSpec = asks) =
      ObjectStorage.status(plan, spec, withStore, None)
    assertEquals(status(ObjectStoragePlan.NotAsked), None)
    assertEquals(
      status(ObjectStoragePlan.Supplied, supplies),
      Some(ObjectStorageStatus("Supplied"))
    )
    // A bucket the platform made names its store (feature 039); one of the service's own does not.
    assertEquals(
      status(ObjectStoragePlan.Waiting(Some("down"))),
      Some(ObjectStorageStatus("Waiting", "shop.reports", detail = Some("down"), store = "garage"))
    )
    assertEquals(
      status(ObjectStoragePlan.Ready(false)),
      Some(ObjectStorageStatus("Provisioned", "shop.reports", store = "garage"))
    )
    assertEquals(
      status(ObjectStoragePlan.Ready(true)),
      Some(ObjectStorageStatus("Recovered", "shop.reports", recovered = true, store = "garage"))
    )
    assertEquals(
      status(ObjectStoragePlan.Failed(Vector(ObjectStorage.NoStore))),
      Some(
        ObjectStorageStatus(
          "Failed",
          "shop.reports",
          detail = Some(ObjectStorage.NoStore),
          store = "garage"
        )
      )
    )
  }

  test("a reachable bucket's status carries its address, when there is a base domain") {
    val exposed  = asks.copy(exposeObjectStorage = true)
    val settings = withStore.copy(baseDomain = Some("example.com"))
    assertEquals(
      ObjectStorage
        .status(ObjectStoragePlan.Ready(false), exposed, settings, None)
        .flatMap(_.publicAddress),
      Some("https://storage.example.com/shop.reports")
    )
    assertEquals(
      ObjectStorage
        .status(ObjectStoragePlan.Ready(false), exposed, withStore, None)
        .flatMap(_.publicAddress),
      None
    )
  }

  test("the store is observed only for a service that asks, in an installation that has one") {
    assert(ObjectStorage.observes(asks, withStore))
    assert(!ObjectStorage.observes(asks, withoutStore))
    assert(!ObjectStorage.observes(supplies, withStore))
    assert(!ObjectStorage.observes(asks.copy(serviceName = "s" * 70), withStore))
  }

  // A bucket in the installation's cloud account (feature 044).

  private val cloud = CloudSettings(
    "gcp",
    "acct",
    "europe-west2",
    None,
    scala.concurrent.duration.Duration(2, "minutes"),
    scala.concurrent.duration.Duration(1, "hour")
  )
  private val withCloud = Settings.default.copy(cloud = Some(cloud))

  private val bucketOutputs = Map(
    "bucket"   -> "acct-shop-reports",
    "endpoint" -> "https://storage.scripted.invalid",
    "region"   -> "europe-west2"
  )
  private val identityReady =
    CloudPlan.Ready(Map("identity" -> "reports@acct.scripted"), false, None)
  private val bucketReady: CloudPlan.Ready = CloudPlan.Ready(bucketOutputs, false, None)
  private val credReady = CloudPlan.Ready(Map("secretName" -> "reports-storage"), false, Some(2L))

  private def cloudDecide(plans: CloudBucketPlans) =
    ObjectStorage.decide(asks, withCloud, ObjectStorageObservation.empty, None, Some(plans))

  test("cloud: all three answered is a bucket, named and placed as the provider answered") {
    assertEquals(
      cloudDecide(CloudBucketPlans(identityReady, bucketReady, Some(credReady))),
      ObjectStoragePlan.Ready(
        recovered = false,
        Some(
          CloudBucket("acct-shop-reports", "https://storage.scripted.invalid", "europe-west2", 2L)
        )
      )
    )
  }

  test("cloud: a recovered bucket is recovered") {
    val plan = cloudDecide(
      CloudBucketPlans(identityReady, bucketReady.copy(recovered = true), Some(credReady))
    )
    assertEquals(plan.reportedPhase, Some("Recovered"))
  }

  test("cloud: any refusal fails the bucket with the provider's words") {
    for plans <- Vector(
        CloudBucketPlans(CloudPlan.Failed("no"), bucketReady, None),
        CloudBucketPlans(identityReady, CloudPlan.Failed("no"), None),
        CloudBucketPlans(identityReady, bucketReady, Some(CloudPlan.Failed("no")))
      )
    do assertEquals(cloudDecide(plans), ObjectStoragePlan.Failed(Vector("no")))
  }

  test("cloud: anything unanswered waits, saying the first thing said") {
    assertEquals(
      cloudDecide(CloudBucketPlans(CloudPlan.Waiting(None), bucketReady, None)),
      ObjectStoragePlan.Waiting(None)
    )
    assertEquals(
      cloudDecide(
        CloudBucketPlans(
          identityReady,
          CloudPlan.Waiting(Some("no provider for gcp has answered")),
          None
        )
      ),
      ObjectStoragePlan.Waiting(Some("no provider for gcp has answered"))
    )
    assertEquals(
      cloudDecide(CloudBucketPlans(identityReady, bucketReady, None)),
      ObjectStoragePlan.Waiting(None),
      "the credential is asked for once both are answered, and until then the bucket waits"
    )
  }

  test("cloud: nothing decided yet is waiting, and a cloud bucket waiting holds its Deployment") {
    assertEquals(
      ObjectStorage.decide(asks, withCloud, ObjectStorageObservation.empty, None),
      ObjectStoragePlan.Waiting(None)
    )
    assertEquals(
      ObjectStorage.withheld(ObjectStoragePlan.Waiting(None), asks, withCloud, None),
      Some(ObjectStorage.WaitingOnProvider)
    )
    assertEquals(
      ObjectStorage.withheld(ObjectStoragePlan.Waiting(Some("why")), asks, withCloud, None),
      Some("why")
    )
    assertEquals(
      ObjectStorage.withheld(ObjectStoragePlan.Waiting(None), asks, withStore, None),
      None
    )
    assertEquals(
      // A bucket the provider refused holds its Deployment too, with the provider's reason.
      ObjectStorage.withheld(ObjectStoragePlan.Failed(Vector("x")), asks, withCloud, None),
      Some("x")
    )
  }

  // Feature 039: the store a bucket is in (data-model.md, "Where the store of a bucket comes from").

  private def reported(
      phase: String,
      store: String,
      move: Option[String] = None
  ): Option[com.thinkmorestupidless.ankka.crd.ObjectStorageStatus] =
    Some(
      com.thinkmorestupidless.ankka.crd.ObjectStorageStatus(
        phase = phase,
        store = store,
        move = move.map(state => com.thinkmorestupidless.ankka.crd.MoveStatus(state = state))
      )
    )

  test("store: a bucket whose move has switched is in the cloud account, whatever the backend") {
    val garageBackend = withStore.copy(cloud = Some(cloud))
    assert(
      ObjectStorage.takesCloudPath(
        asks,
        garageBackend,
        reported("Provisioned", "garage", Some("Switched"))
      )
    )
  }

  test("store: a bucket made stays in the store it was made in, whatever the backend says now") {
    val gcsBackend =
      withStore.copy(cloud = Some(cloud), objectStoreBackend = Some(ObjectStoreBackend.Gcs))
    val garageBackend =
      withStore.copy(cloud = Some(cloud), objectStoreBackend = Some(ObjectStoreBackend.Garage))
    for phase <- Vector("Provisioned", "Recovered") do
      assert(!ObjectStorage.takesCloudPath(asks, gcsBackend, reported(phase, "garage")), phase)
      assert(ObjectStorage.takesCloudPath(asks, garageBackend, reported(phase, "gcs")), phase)
  }

  test("store: a bucket reported before stores were named is in Garage, the one store there was") {
    val gcsBackend =
      withStore.copy(cloud = Some(cloud), objectStoreBackend = Some(ObjectStoreBackend.Gcs))
    assert(!ObjectStorage.takesCloudPath(asks, gcsBackend, reported("Provisioned", "")))
  }

  test("store: a bucket not made yet, or never reported, takes the installation's backend") {
    val gcsBackend =
      withStore.copy(cloud = Some(cloud), objectStoreBackend = Some(ObjectStoreBackend.Gcs))
    for seen <- Vector(None, reported("Waiting", "garage"), reported("Failed", "garage")) do
      assert(ObjectStorage.takesCloudPath(asks, gcsBackend, seen), seen.toString)
    assert(!ObjectStorage.takesCloudPath(asks, withStore.copy(cloud = Some(cloud)), None))
  }

  test(
    "store: a name too long for Garage is no refusal in the cloud, whose provider names the bucket"
  ) {
    val long = asks.copy(projectId = "p" * 40, serviceName = "s" * 30)
    assertEquals(
      ObjectStorage.decide(long, withCloud, ObjectStorageObservation.empty, None),
      ObjectStoragePlan.Waiting(None)
    )
  }

  test("store: an installation as 044 served it, a provider and no Garage, takes the cloud path") {
    assert(ObjectStorage.takesCloudPath(asks, withCloud, None))
    assert(
      !ObjectStorage.takesCloudPath(asks.copy(provisionObjectStorage = false), withCloud, None)
    )
  }

  test("cloud: an installation with its own store keeps it, whatever cloud it names") {
    val both = withStore.copy(cloud = Some(cloud))
    assert(!ObjectStorage.takesCloudPath(asks, both, None))
    assertEquals(
      ObjectStorage.decide(
        asks,
        both,
        ready,
        None,
        Some(CloudBucketPlans(CloudPlan.Failed("no"), bucketReady, None))
      ),
      ObjectStoragePlan.Ready(recovered = false)
    )
  }

  test("cloud: the status names the provider's bucket, and none until it has answered") {
    val ok = ObjectStorage.status(
      cloudDecide(CloudBucketPlans(identityReady, bucketReady, Some(credReady))),
      asks,
      withCloud,
      None
    )
    assertEquals(ok.map(_.bucket), Some("acct-shop-reports"))
    assertEquals(ok.map(_.phase), Some("Provisioned"))
    val waiting = ObjectStorage.status(ObjectStoragePlan.Waiting(Some("w")), asks, withCloud, None)
    assertEquals(waiting.map(_.bucket), Some(""))
    assertEquals(waiting.flatMap(_.detail), Some("w"))
    val failed = ObjectStorage.status(
      ObjectStoragePlan.Failed(Vector("the location is refused")),
      asks,
      withCloud,
      None
    )
    assertEquals(failed.flatMap(_.detail), Some("the location is refused"))
    assertEquals(failed.map(_.publicAddress), Some(None))
  }

  // Feature 055: named buckets.
  private val web = ObjectStoreSettings.ServiceRef("garage-system", "garage", 3902)
  private val served = withStore.copy(
    objectStore = Some(store.copy(webService = Some(web))),
    baseDomain = Some("example.test"),
    httpsPort = 443
  )
  private val four = asks.copy(
    serviceName = "listings",
    buckets = List(
      com.thinkmorestupidless.ankka.crd.BucketSpec("photos", exposure = "public"),
      com.thinkmorestupidless.ankka.crd.BucketSpec("uploads", exposure = "signed"),
      com.thinkmorestupidless.ankka.crd.BucketSpec("documents"),
      com.thinkmorestupidless.ankka.crd.BucketSpec(
        "archive",
        supplied = Some(
          com.thinkmorestupidless.ankka.crd.SuppliedBucket(
            "https://s3.example.com",
            "eu-west-1",
            "acme-archive",
            "archive-credential"
          )
        )
      )
    )
  )
  private def everyReady = four.buckets
    .filter(_.made)
    .map(b => Buckets.named("shop", "listings", b.name) -> ready)
    .toMap

  test("055: each exposure is decided, with its name in the store and its address") {
    val decided = ObjectStorage.decideBuckets(four, served, everyReady, reported = None)
    assertEquals(
      decided.map(_.named),
      Vector(
        "shop.listings.photos",
        "shop.listings.uploads",
        "shop.listings.documents",
        "acme-archive"
      )
    )
    assertEquals(
      decided.map(_.plan),
      Vector(
        ObjectStoragePlan.Ready(recovered = false),
        ObjectStoragePlan.Ready(recovered = false),
        ObjectStoragePlan.Ready(recovered = false),
        ObjectStoragePlan.Supplied
      )
    )
    assertEquals(
      decided.map(_.status).toList,
      List(
        crd.BucketStatus(
          "photos",
          "Provisioned",
          "garage",
          "shop.listings.photos",
          "public",
          Some("https://storage.example.test/shop.listings.photos")
        ),
        crd.BucketStatus(
          "uploads",
          "Provisioned",
          "garage",
          "shop.listings.uploads",
          "signed",
          Some("https://storage.example.test/shop.listings.uploads")
        ),
        crd
          .BucketStatus("documents", "Provisioned", "garage", "shop.listings.documents", "private"),
        crd.BucketStatus("archive", "Supplied", "", "acme-archive", "supplied")
      )
    )
  }

  test("055: a named bucket not made yet, or with no key on it, is waiting") {
    val decided = ObjectStorage.decideBuckets(four, served, Map.empty, reported = None)
    assertEquals(decided.take(3).map(_.plan), Vector.fill(3)(ObjectStoragePlan.Waiting(None)))
    assertEquals(decided.take(3).map(_.status.phase), Vector.fill(3)("Waiting"))
    val noKey = everyReady.updated("shop.listings.documents", ready.copy(keyAllowed = false))
    assertEquals(
      ObjectStorage.decideBuckets(four, served, noKey, None)(2).plan,
      ObjectStoragePlan.Waiting(None)
    )
  }

  test("055: a public bucket with no web endpoint named fails, naming the setting") {
    val decided =
      ObjectStorage.decideBuckets(four, served.copy(objectStore = Some(store)), everyReady, None)
    assertEquals(decided.head.plan, ObjectStoragePlan.Failed(Vector(ObjectStorage.NoWebService)))
    assertEquals(decided.head.status.phase, "Failed")
    assert(decided.head.status.detail.exists(_.contains("ANKKA_OBJECT_STORE_WEB_SERVICE")))
    // Only the public one: the others are served by the S3 endpoint.
    assertEquals(decided(1).plan, ObjectStoragePlan.Ready(recovered = false))
  }

  test("055: a named bucket over the name limit, or with no store, fails saying why") {
    val long = four.copy(projectId = "p" * 40, buckets = List(crd.BucketSpec("a" * 20)))
    val over = ObjectStorage.decideBuckets(long, served, Map.empty, None).head
    assert(over.status.detail.exists(_.contains("63")), over.status)
    val none = ObjectStorage.decideBuckets(four, withoutStore, Map.empty, None)
    assertEquals(none(2).plan, ObjectStoragePlan.Failed(Vector(ObjectStorage.NoStore)))
    // A supplied bucket is the service's own, store or no store.
    assertEquals(none(3).plan, ObjectStoragePlan.Supplied)
  }

  test("055: a store that cannot be reached leaves each made bucket waiting with the reason") {
    val down =
      everyReady.map((k, _) => k -> ObjectStorageObservation(unreachable = Some("refused")))
    val decided = ObjectStorage.decideBuckets(four, served, down, None)
    assertEquals(decided.take(3).map(_.status.detail), Vector.fill(3)(Some("refused")))
    assertEquals(decided(3).status.phase, "Supplied")
  }

  test(
    "055: a bucket the descriptor stopped naming is kept, with its last store, name and address"
  ) {
    val first = ObjectStorage.decideBuckets(four, served, everyReady, None)
    val status =
      ObjectStorageStatus(phase = "", buckets = ObjectStorage.bucketStatuses(first, four, None))
    val fewer  = four.copy(buckets = four.buckets.filterNot(_.name == "photos"))
    val second = ObjectStorage.decideBuckets(fewer, served, everyReady, Some(status))
    val listed = ObjectStorage.bucketStatuses(second, fewer, Some(status))
    assertEquals(listed.map(_.name), List("uploads", "documents", "archive", "photos"))
    assertEquals(
      listed.last,
      crd.BucketStatus(
        "photos",
        "Kept",
        "garage",
        "shop.listings.photos",
        "public",
        Some("https://storage.example.test/shop.listings.photos"),
        kept = true
      )
    )
    // Kept across a second pass, from the status the first wrote.
    val again = ObjectStorage.bucketStatuses(
      ObjectStorage.decideBuckets(fewer, served, everyReady, Some(status.copy(buckets = listed))),
      fewer,
      Some(status.copy(buckets = listed))
    )
    assertEquals(again, listed)
    // A supplied bucket was never the platform's, and is not kept.
    val noArchive = four.copy(buckets = four.buckets.filterNot(_.name == "archive"))
    assertEquals(ObjectStorage.kept(noArchive, Some(status)), Nil)
  }

  test("055: a kept bucket named again is decided afresh") {
    val keptPhotos = ObjectStorageStatus(buckets =
      List(
        crd.BucketStatus(
          "photos",
          "Kept",
          "garage",
          "shop.listings.photos",
          "public",
          None,
          kept = true
        )
      )
    )
    val decided = ObjectStorage.decideBuckets(four, served, everyReady, Some(keptPhotos))
    assertEquals(decided.head.status.phase, "Provisioned")
    assertEquals(decided.head.status.kept, false)
    assertEquals(ObjectStorage.kept(four, Some(keptPhotos)), Nil)
  }

  test("055: a supplied bucket is decided without an observation of the store") {
    // The decision has nothing to ask: even an observation that says the store is down is not read.
    val archive = four.buckets.last
    val decided = ObjectStorage.decideBucket(
      four,
      archive,
      withoutStore,
      ObjectStorageObservation(unreachable = Some("refused")),
      None
    )
    assertEquals(decided.plan, ObjectStoragePlan.Supplied)
    assertEquals(decided.status.detail, None)
    assertEquals(decided.status.store, "")
  }
