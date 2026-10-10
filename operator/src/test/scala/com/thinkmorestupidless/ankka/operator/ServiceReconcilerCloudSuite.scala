package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec, CloudResourceStatus}
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
    def observeDatabase(namespace: String, cluster: String, service: String) =
      DatabaseObservation.empty
    def resourceCreatedAt(namespace: String, name: String)       = None
    def observeBroker(namespace: String, user: String)           = BrokerObservation.empty
    def observeTopics(namespace: String, topics: Vector[String]) = Map.empty
    def podTemplateLabels(namespace: String, name: String)       = None
    def awaitNoPods(
        namespace: String,
        selector: Map[String, String],
        timeout: scala.concurrent.duration.FiniteDuration
    ) = true
    override def observeCloudResource(namespace: String, name: String) =
      asked += name
      answers.get(name)

  private def pass(answers: Map[String, CloudObservation]) =
    val client = new KubernetesClientBuilder().build()
    try
      val stub = Stub(answers)
      val seen = new ServiceReconciler(client, settings, stub, Clock.fixed(now, ZoneOffset.UTC))
        .decideCloudBucket(ServiceRef("ankka-shop", "reports"), resource, resource.getSpec)
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
  private val bucket = "reports-bucket" -> answered(
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
    val plan = ObjectStorage.decide(
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
