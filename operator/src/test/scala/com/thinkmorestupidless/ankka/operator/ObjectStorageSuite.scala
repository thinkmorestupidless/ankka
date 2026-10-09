package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, EnvEntry, ObjectStorageStatus}

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
  ) = ObjectStorage.decide(spec, settings, observed)

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
      ObjectStorage.status(plan, spec, withStore)
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
        .status(ObjectStoragePlan.Ready(false), exposed, settings)
        .flatMap(_.publicAddress),
      Some("https://storage.example.com/shop.reports")
    )
    assertEquals(
      ObjectStorage
        .status(ObjectStoragePlan.Ready(false), exposed, withStore)
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
    ObjectStorage.decide(asks, withCloud, ObjectStorageObservation.empty, Some(plans))

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
      ObjectStorage.withheld(ObjectStoragePlan.Waiting(None), asks, withCloud),
      Some(ObjectStorage.WaitingOnProvider)
    )
    assertEquals(
      ObjectStorage.withheld(ObjectStoragePlan.Waiting(Some("why")), asks, withCloud),
      Some("why")
    )
    assertEquals(ObjectStorage.withheld(ObjectStoragePlan.Waiting(None), asks, withStore), None)
    assertEquals(
      ObjectStorage.withheld(ObjectStoragePlan.Failed(Vector("x")), asks, withCloud),
      None
    )
  }

  test("cloud: an installation with its own store keeps it, whatever cloud it names") {
    val both = withStore.copy(cloud = Some(cloud))
    assert(!ObjectStorage.takesCloudPath(asks, both))
    assertEquals(
      ObjectStorage.decide(
        asks,
        both,
        ready,
        Some(CloudBucketPlans(CloudPlan.Failed("no"), bucketReady, None))
      ),
      ObjectStoragePlan.Ready(recovered = false)
    )
  }

  test("cloud: the status names the provider's bucket, and none until it has answered") {
    val ok = ObjectStorage.status(
      cloudDecide(CloudBucketPlans(identityReady, bucketReady, Some(credReady))),
      asks,
      withCloud
    )
    assertEquals(ok.map(_.bucket), Some("acct-shop-reports"))
    assertEquals(ok.map(_.phase), Some("Provisioned"))
    val waiting = ObjectStorage.status(ObjectStoragePlan.Waiting(Some("w")), asks, withCloud)
    assertEquals(waiting.map(_.bucket), Some(""))
    assertEquals(waiting.flatMap(_.detail), Some("w"))
    val failed = ObjectStorage.status(
      ObjectStoragePlan.Failed(Vector("the location is refused")),
      asks,
      withCloud
    )
    assertEquals(failed.flatMap(_.detail), Some("the location is refused"))
    assertEquals(failed.map(_.publicAddress), Some(None))
  }
