package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaService,
  AnkkaServiceSpec,
  AnkkaServiceStatus,
  BucketSpec,
  BucketStatus,
  CloudResourceStatus,
  ObjectStorageMoveRequest,
  ObjectStorageStatus,
  MoveStatus
}
import com.thinkmorestupidless.ankka.operator.cnpg.DatabaseObservation
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.duration.DurationInt

/**
 * What one pass makes of a cloud bucket's three requests (feature 044): which it renders, what it
 * decides of each answer, and whether any is still unacknowledged, so it looks again at the bound.
 * The cluster is a stub that answers `observeCloudResource` from a map; nothing else is read.
 */
class ServiceReconcilerCloudSuite extends munit.FunSuite:

  private val now      = Instant.parse("2026-10-09T12:00:00Z")
  private val cloud    = CloudSettings("gcp", "acct", "europe-west2", None, 2.minutes, 1.hour)
  private val settings = Settings.default.copy(cloud = Some(cloud))

  private val resource: AnkkaService =
    val r = AnkkaService(
      "ankka-shop",
      "reports",
      AnkkaServiceSpec(
        projectId = "shop",
        serviceName = "reports",
        image = "reports:1",
        provisionObjectStorage = true
      )
    )
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-shop").withName("reports").withUid("u").build()
    )
    r

  /** Only the cloud requests' answers are read; anything else is a mistake the test should see. */
  private final class Stub(answers: Map[String, CloudObservation]) extends Executor:
    val asked                         = scala.collection.mutable.ArrayBuffer.empty[String]
    def execute(action: Action): Unit = ()
    def snapshot(namespace: String, name: String)                              = None
    def foreignObjectAt(namespace: String, name: String)                       = false
    def podProblems(namespace: String, serviceName: String, projectId: String) = Vector.empty
    def observeRoute(namespace: String, name: String)                          = None
    def observeDatabase(namespace: String, cluster: String, service: String)   =
      DatabaseObservation.empty
    def resourceCreatedAt(namespace: String, name: String)       = None
    def observeBroker(namespace: String, user: String)           = BrokerObservation.empty
    def observeTopics(namespace: String, topics: Vector[String]) = Map.empty
    def podTemplateLabels(namespace: String, name: String)       = None
    def awaitNoPods(
        namespace: String,
        selector: Map[String, String],
        timeout: scala.concurrent.duration.FiniteDuration
    )                                                                  = true
    override def observeCloudResource(namespace: String, name: String) =
      asked += name
      answers.get(name)

  private def pass(answers: Map[String, CloudObservation], of: AnkkaService = resource) =
    val client = new KubernetesClientBuilder().build()
    try
      val stub = Stub(answers)
      val seen = new ServiceReconciler(client, settings, stub, Clock.fixed(now, ZoneOffset.UTC))
        .decideCloudBucket(ServiceRef("ankka-shop", "reports"), of, of.getSpec)
        .getOrElse(fail("a service on the cloud path has a cloud bucket pass"))
      seen -> stub.asked.toVector
    finally client.close()

  private def answered(outputs: (String, String)*) = CloudObservation(
    1L,
    now.minusSeconds(5),
    Some(
      CloudResourceStatus(
        Some(1L),
        "Ready",
        outputs = outputs.toMap,
        credentialGeneration = Some(1L)
      )
    )
  )

  private val identity = "reports-identity" -> answered("identity" -> "reports@acct.scripted")
  private val bucket   = "reports-bucket"   -> answered(
    "bucket"   -> "acct-shop-reports",
    "endpoint" -> "https://storage.scripted.invalid",
    "region"   -> "europe-west2"
  )
  private val credential =
    "reports-storage-credential" -> answered("secretName" -> "reports-storage")

  test("a first pass renders the identity and the bucket, and waits on both") {
    val (seen, asked) = pass(Map.empty)
    assertEquals(
      seen.requests.map(_.getMetadata.getName),
      Vector("reports-identity", "reports-bucket")
    )
    // The bucket's request is read first, once: what it already asks is kept (feature 039).
    assertEquals(asked, Vector("reports-bucket", "reports-identity"))
    assertEquals(seen.plans.credential, None)
    assert(seen.unacknowledged, "nothing has answered, so look again at the bound")
  }

  test("once both are answered the credential is asked for, naming what they answered") {
    val (seen, asked) = pass(Map(identity, bucket))
    assertEquals(seen.requests.size, 3)
    val c = seen.requests(2)
    assertEquals(c.getSpec.parameters("identity"), "reports@acct.scripted")
    assertEquals(c.getSpec.parameters("bucket"), "acct-shop-reports")
    assertEquals(asked.last, "reports-storage-credential")
    assert(seen.unacknowledged, "the credential's request is not answered yet")
  }

  test("all three answered is a ready bucket, and nothing to look again for") {
    val (seen, _) = pass(Map(identity, bucket, credential))
    assert(!seen.unacknowledged)
    assertEquals(
      ObjectStorage
        .decide(resource.getSpec, settings, ObjectStorageObservation.empty, None, Some(seen.plans)),
      ObjectStoragePlan.Ready(
        recovered = false,
        Some(
          CloudBucket("acct-shop-reports", "https://storage.scripted.invalid", "europe-west2", 1L)
        )
      )
    )
  }

  test("a request unanswered past the bound says so, and the Deployment is held with that reason") {
    val stale     = CloudObservation(1L, now.minusSeconds(121), None)
    val (seen, _) = pass(Map("reports-identity" -> stale, "reports-bucket" -> stale))
    val plan      = ObjectStorage.decide(
      resource.getSpec,
      settings,
      ObjectStorageObservation.empty,
      None,
      Some(seen.plans)
    )
    assertEquals(plan, ObjectStoragePlan.Waiting(Some("no provider for gcp has answered")))
    assertEquals(
      ObjectStorage.withheld(plan, resource.getSpec, settings, None),
      Some("no provider for gcp has answered")
    )
  }

  test("a service not on the cloud path makes no cloud read at all") {
    val client = new KubernetesClientBuilder().build()
    try
      val stub = Stub(Map.empty)
      val none = new ServiceReconciler(client, Settings.default, stub)
        .decideCloudBucket(ServiceRef("ankka-shop", "reports"), resource, resource.getSpec)
      assertEquals(none, None)
      assertEquals(stub.asked.toVector, Vector.empty[String])
    finally client.close()
  }

  // Feature 055: a request per named bucket, one credential over all, the public address.

  private val photos  = BucketSpec("photos", "public")
  private val uploads = BucketSpec("uploads", "signed", List("https://shop.example"))

  private def withBuckets(
      spec: AnkkaServiceSpec,
      status: Option[ObjectStorageStatus] = None
  ): AnkkaService =
    val r = AnkkaService("ankka-shop", "reports", spec)
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-shop").withName("reports").withUid("u").build()
    )
    status.foreach(s => r.setStatus(AnkkaServiceStatus(objectStorage = Some(s))))
    r

  private val three = withBuckets(resource.getSpec.copy(buckets = List(photos, uploads)))

  private val photosBucket = "reports-bucket-photos" -> answered(
    "bucket"    -> "acct-shop-reports-photos",
    "endpoint"  -> "https://storage.scripted.invalid",
    "region"    -> "europe-west2",
    "publicUrl" -> "https://cdn.example/acct-shop-reports-photos"
  )
  private val uploadsBucket = "reports-bucket-uploads" -> answered(
    "bucket"   -> "acct-shop-reports-uploads",
    "endpoint" -> "https://storage.scripted.invalid",
    "region"   -> "europe-west2"
  )

  test("055: a service with two named buckets asks for three buckets, each by its own request") {
    val (seen, _) = pass(Map.empty, three)
    assertEquals(
      seen.requests.map(_.getMetadata.getName),
      Vector(
        "reports-identity",
        "reports-bucket",
        "reports-bucket-photos",
        "reports-bucket-uploads"
      )
    )
    val asks = seen.requests.drop(2).map(_.getSpec.parameters)
    assertEquals(
      asks.map(p => p("name") -> p("public")),
      Vector("photos" -> "true", "uploads" -> "false")
    )
    assertEquals(asks(1)("corsOrigins"), "https://shop.example")
    assertEquals(seen.requests(1).getSpec.parameters.get("name"), None)
    assertEquals(seen.named.keySet, Set("photos", "uploads"))
    assert(seen.unacknowledged)
  }

  test("055: the credential is asked once every bucket is answered, over all three, in order") {
    val (waiting, _) = pass(Map(identity, bucket, photosBucket), three)
    assert(
      !waiting.requests.exists(_.getMetadata.getName == "reports-storage-credential"),
      "uploads is not answered yet, so the credential cannot name it"
    )
    val (seen, _) = pass(Map(identity, bucket, photosBucket, uploadsBucket), three)
    val c         = seen.requests.last
    assertEquals(c.getMetadata.getName, "reports-storage-credential")
    assertEquals(
      c.getSpec.parameters,
      Map(
        "buckets"    -> "acct-shop-reports,acct-shop-reports-photos,acct-shop-reports-uploads",
        "identity"   -> "reports@acct.scripted",
        "secretName" -> "reports-cloud-storage"
      )
    )
  }

  test("055: a public bucket's address is the provider's, in the variable and the status") {
    val (seen, _) =
      pass(Map(identity, bucket, photosBucket, uploadsBucket, credential), three)
    assert(!seen.unacknowledged)
    val decided = ObjectStorage.decideBuckets(three.getSpec, settings, Map.empty, None, seen.named)
    val p       = decided.find(_.spec.name == "photos").get
    assertEquals(p.status.store, "gcs")
    assertEquals(p.status.bucket, "acct-shop-reports-photos")
    assertEquals(p.status.publicAddress, Some("https://cdn.example/acct-shop-reports-photos"))
    assert(p.told)
    val env = Rendering
      .withNamedStorage(None, three.getSpec, settings, decided, 0)
      .getOrElse(fail("the named buckets are told"))
    val literals = env.literals.toMap
    assertEquals(
      literals.get("ANKKA_S3_PUBLIC_URL_PHOTOS"),
      Some("https://cdn.example/acct-shop-reports-photos")
    )
    assertEquals(literals.get("ANKKA_S3_BUCKET_UPLOADS"), Some("acct-shop-reports-uploads"))
    assertEquals(literals.get("ANKKA_S3_ENDPOINT"), Some("https://storage.scripted.invalid"))
    // A signed bucket's URLs are signed for the cloud's own address, not the installation's.
    assertEquals(literals.get("ANKKA_S3_PUBLIC_ENDPOINT"), Some("https://storage.scripted.invalid"))
    assertEquals(env.secret, Some("reports-cloud-storage"))
  }

  test(
    "055: a bucket named during a move waits for it, and is made in the cloud once it switches"
  ) {
    val inGarage = ObjectStorageStatus(
      phase = "Provisioned",
      bucket = "shop.reports",
      store = "garage",
      move = Some(MoveStatus(generation = 1, state = "Copying"))
    )
    val moving = resource.getSpec.copy(
      buckets = List(photos),
      objectStorageMove = Some(ObjectStorageMoveRequest(generation = 1))
    )
    val during = ObjectStorage.decideBuckets(moving, settings, Map.empty, Some(inGarage))
    assertEquals(during.head.plan, ObjectStoragePlan.Waiting(Some(ObjectStorage.WaitingOnMove)))
    assertEquals(during.head.status.publicAddress, None)
    assert(!during.head.provisioned, "nothing is made in Garage for a bucket the move will make")
    assertEquals(ObjectStorage.namedWithheld(during), Some(ObjectStorage.WaitingOnMove))
    assertEquals(ServiceReconciler.namedInCloud(moving, settings, Some(inGarage)), Vector.empty)
    val switched = inGarage.copy(move = inGarage.move.map(_.copy(state = "Switched")))
    assertEquals(
      ServiceReconciler.namedInCloud(moving, settings, Some(switched)).map(_.name),
      Vector("photos")
    )
  }

  test("055: a bucket not yet made follows the unnamed one's store, so one key reaches both") {
    val withGarage = settings.copy(objectStore =
      Some(
        ObjectStoreSettings(
          "http://admin",
          "token",
          "http://garage.garage-system.svc.cluster.local:3900",
          "garage",
          ObjectStoreSettings.ServiceRef("garage-system", "garage", 3900)
        )
      )
    )
    val inGarage =
      ObjectStorageStatus(phase = "Provisioned", bucket = "shop.reports", store = "garage")
    assert(!ObjectStorage.bucketInCloud(settings, None, Some(inGarage)))
    val inCloud = inGarage.copy(store = "gcs")
    assert(ObjectStorage.bucketInCloud(withGarage, None, Some(inCloud)))
    // And one reported made stays where it was made.
    val made = BucketStatus("photos", "Provisioned", "garage", "shop.reports.photos")
    assert(!ObjectStorage.bucketInCloud(settings, Some(made), Some(inCloud)))
  }
