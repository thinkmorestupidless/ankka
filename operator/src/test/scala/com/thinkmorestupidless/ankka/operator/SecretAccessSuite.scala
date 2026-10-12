package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaService, AnkkaServiceSpec, CloudResourceStatus}
import com.thinkmorestupidless.ankka.operator.cnpg.DatabaseObservation
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.duration.DurationInt

/**
 * A service's access to its secrets on Secret Manager, asked of the cloud provider (feature 038's
 * T027 on feature 044's requests): what one pass asks for, what it makes of the answers, and that
 * the Deployment waits for the grant. The cluster is a stub answering `observeCloudResource` from a
 * map; nothing else is read.
 */
class SecretAccessSuite extends munit.FunSuite:

  private val now   = Instant.parse("2026-10-09T12:00:00Z")
  private val cloud = CloudSettings("gcp", "acct", "europe-west2", None, 2.minutes, 1.hour)
  private val onSecretManager = Settings.default.copy(
    cloud = Some(cloud),
    secretStore = Settings.SecretStore(backend = Some("secret-manager"))
  )

  private def service(name: String, bucket: Boolean = false): AnkkaService =
    val r = AnkkaService(
      "ankka-shop",
      name,
      AnkkaServiceSpec(
        projectId = "shop",
        serviceName = name,
        image = s"$name:1",
        provisionObjectStorage = bucket
      )
    )
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-shop").withName(name).withUid("u").build()
    )
    r

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

  private def reconciler[A](settings: Settings, answers: Map[String, CloudObservation])(
      f: (ServiceReconciler, Stub) => A
  ): A =
    val client = new KubernetesClientBuilder().build()
    try
      val stub = Stub(answers)
      f(new ServiceReconciler(client, settings, stub, Clock.fixed(now, ZoneOffset.UTC)), stub)
    finally client.close()

  private def pass(
      answers: Map[String, CloudObservation],
      settings: Settings = onSecretManager,
      name: String = "payments"
  ) =
    reconciler(settings, answers) { (r, stub) =>
      val resource = service(name)
      r.decideSecretAccess(ServiceRef("ankka-shop", name), resource, resource.getSpec, None) ->
        stub.asked.toVector
    }

  private def answered(phase: String, detail: Option[String], outputs: (String, String)*) =
    CloudObservation(
      1L,
      now.minusSeconds(5),
      Some(CloudResourceStatus(Some(1L), phase, detail = detail, outputs = outputs.toMap))
    )

  private val identity =
    "payments-identity" -> answered("Ready", None, "identity" -> "payments@acct.scripted")
  private val access = "payments-secret-access" -> answered("Ready", None)

  test("a first pass asks for the service's cloud identity, and holds the Deployment for it") {
    val (seen, asked) = pass(Map.empty)
    val got           = seen.getOrElse(fail("a service on Secret Manager has an access pass"))
    assertEquals(got.requests.map(_.getMetadata.getName), Vector("payments-identity"))
    assertEquals(asked, Vector("payments-identity"))
    assertEquals(got.hold, Some(SecretAccess.Hold.Waiting(SecretAccess.WaitingOnProvider)))
    assert(got.unacknowledged, "nothing has answered, so look again at the bound")
  }

  test(
    "once the identity is answered, access is asked for it: its own prefix, its project's to read"
  ) {
    val (seen, asked) = pass(Map(identity))
    val got           = seen.get
    assertEquals(
      got.requests.map(_.getMetadata.getName),
      Vector("payments-identity", "payments-secret-access")
    )
    val parameters = got.requests(1).getSpec.parameters
    assertEquals(parameters("identity"), "payments@acct.scripted")
    assertEquals(parameters("own"), "s_shop_payments_")
    assertEquals(parameters("read"), "p_shop_")
    assertEquals(asked.last, "payments-secret-access")
    assert(got.hold.isDefined, "access is not granted yet")
  }

  test("a service with no bucket has its ServiceAccount bound to the identity it was given") {
    val bound = "payments-identity" -> answered(
      "Ready",
      None,
      "identity"                  -> "payments@acct.scripted",
      "serviceAccountAnnotations" -> "iam.gke.io/gcp-service-account=payments@acct.scripted"
    )
    val got = pass(Map(bound, access))._1
    assertEquals(
      ServiceReconciler.serviceAccountAnnotations(None, got),
      Map("iam.gke.io/gcp-service-account" -> "payments@acct.scripted")
    )
    assertEquals(ServiceReconciler.serviceAccountAnnotations(None, pass(Map.empty)._1), Map.empty)
  }

  test("access granted holds nothing, and leaves nothing to look again for") {
    val got = pass(Map(identity, access))._1.get
    assertEquals(got.hold, None)
    assert(!got.unacknowledged)
  }

  test("access the provider refuses is the service's failure, in the provider's words") {
    val refused = "payments-secret-access" -> answered("Failed", Some("no such identity"))
    assertEquals(
      pass(Map(identity, refused))._1.get.hold,
      Some(SecretAccess.Hold.Refused(SecretAccess.refused("no such identity")))
    )
    val noIdentity = "payments-identity" -> answered("Failed", Some("quota exceeded"))
    assertEquals(
      pass(Map(noIdentity))._1.get.hold,
      Some(SecretAccess.Hold.Refused(SecretAccess.refused("quota exceeded")))
    )
  }

  test("nothing is asked on the Postgres backend, or with no cloud provider to ask") {
    for settings <- Vector(
        Settings.default.copy(cloud = Some(cloud)),
        Settings.default.copy(secretStore = Settings.SecretStore(backend = Some("secret-manager")))
      )
    do
      val (seen, asked) = pass(Map.empty, settings)
      assertEquals(seen, None)
      assertEquals(asked, Vector.empty)
  }

  test("a service with a cloud bucket asks for one identity, which both use") {
    reconciler(onSecretManager, Map(identity.copy(_1 = "reports-identity"))) { (r, _) =>
      val resource = service("reports", bucket = true)
      val ref      = ServiceRef("ankka-shop", "reports")
      val bucket   = r.decideCloudBucket(ref, resource, resource.getSpec)
      val access   = r.decideSecretAccess(ref, resource, resource.getSpec, bucket)
      val names = (bucket.get.requests ++ access.get.requests)
        .distinctBy(_.getMetadata.getName)
        .map(_.getMetadata.getName)
      assertEquals(names.count(_ == "reports-identity"), 1)
      assert(names.contains("reports-secret-access"), names.toString)
    }
  }

  test("the Deployment is not applied while access is held, and is once it is granted") {
    val resource = service("payments")
    def applies(hold: Option[SecretAccess.Hold]) =
      Rendering
        .render(resource, onSecretManager, ProvisioningPlan.Supplied, secretHold = hold)
        .getOrElse(fail("renders"))
        .exists(_.isInstanceOf[Action.ApplyDeployment])
    assert(applies(None))
    assert(!applies(Some(SecretAccess.Hold.Waiting(SecretAccess.WaitingOnProvider))))
    assert(!applies(Some(SecretAccess.Hold.Refused(SecretAccess.refused("no")))))
  }
