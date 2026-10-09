package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProject,
  AnkkaProjectSpec,
  AnkkaService,
  AnkkaServiceSpec,
  CloudResourceStatus,
  EnvEntry,
  ProjectSecretEntry
}
import com.thinkmorestupidless.ankka.operator.cnpg.DatabaseObservation
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.client.KubernetesClientBuilder

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.duration.DurationInt

/**
 * A project's secrets kept in step with Secret Manager by the cloud provider (feature 038's T047 on
 * feature 044's `secret-sync`): what the project asks for, and that a service taking a variable
 * from a project secret waits until the provider reports that secret's generation synced.
 */
class ProjectSecretSyncSuite extends munit.FunSuite:

  private val now   = Instant.parse("2026-10-09T12:00:00Z")
  private val cloud = CloudSettings("gcp", "acct", "europe-west2", None, 2.minutes, 1.hour)
  private val onSecretManager = Settings.default.copy(
    cloud = Some(cloud),
    secretStore = Settings.SecretStore(backend = Some("secret-manager"))
  )

  private val checkout = ProjectSecretEntry("checkout", List("STRIPE_KEY", "WEBHOOK_KEY"), 1700L)
  private val mail     = ProjectSecretEntry("mail", List("SMTP_PASSWORD"), 1200L)

  private val project: AnkkaProject =
    val p = AnkkaProject(
      "ankka-shop",
      "shop",
      AnkkaProjectSpec(projectId = "shop", secrets = List(checkout, mail))
    )
    p.getMetadata.setUid("project-uid")
    p

  private def service(env: List[EnvEntry]): AnkkaService =
    val r = AnkkaService(
      "ankka-shop",
      "payments",
      AnkkaServiceSpec(projectId = "shop", serviceName = "payments", image = "p:1", env = env)
    )
    r.setMetadata(
      new ObjectMetaBuilder().withNamespace("ankka-shop").withName("payments").withUid("u").build()
    )
    r

  private val takesStripeKey = List(
    EnvEntry("STRIPE_KEY", secretName = Some("checkout"), secretKey = Some("STRIPE_KEY"))
  )

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
    override def projectSecrets(namespace: String, projectId: String) =
      project.getSpec.secrets.toVector
    override def observeCloudResource(namespace: String, name: String) =
      asked += name
      answers.get(name)

  private def pass(
      env: List[EnvEntry],
      answers: Map[String, CloudObservation],
      settings: Settings = onSecretManager
  ) =
    val client = new KubernetesClientBuilder().build()
    try
      val stub     = Stub(answers)
      val resource = service(env)
      val seen = new ServiceReconciler(client, settings, stub, Clock.fixed(now, ZoneOffset.UTC))
        .decideProjectSecrets(ServiceRef("ankka-shop", "payments"), resource.getSpec)
      seen -> stub.asked.toVector
    finally client.close()

  private def synced(phase: String, generation: Option[Long], detail: Option[String] = None) =
    "shop.secret-sync.checkout" -> CloudObservation(
      1L,
      now.minusSeconds(5),
      Some(
        CloudResourceStatus(
          Some(1L),
          phase,
          detail = detail,
          outputs = generation.map(g => "entryGeneration" -> g.toString).toMap
        )
      )
    )

  test("a project asks for each of its secrets to be kept in step, each entry by its id") {
    val requests = ProjectSecretSync.requests(project, onSecretManager)
    assertEquals(
      requests.map(_.getMetadata.getName),
      Vector("shop.secret-sync.checkout", "shop.secret-sync.mail")
    )
    assertEquals(
      requests.head.getSpec.parameters,
      Map(
        "secretName" -> "checkout",
        "entries" -> "STRIPE_KEY=p_shop_checkout__STRIPE_uKEY,WEBHOOK_KEY=p_shop_checkout__WEBHOOK_uKEY",
        "entryGeneration" -> "1700"
      )
    )
    val actions = ProjectReconciler.actions(
      ServiceRef("ankka-shop", "shop"),
      project.getSpec,
      None,
      Map.empty,
      None,
      requests
    )
    assertEquals(actions.count(_.isInstanceOf[Action.EnsureCloudResource]), 2)
  }

  test("a project on the Postgres backend, or with no cloud provider, asks for nothing") {
    assertEquals(
      ProjectSecretSync.requests(project, Settings.default.copy(cloud = Some(cloud))),
      Vector.empty
    )
    assertEquals(
      ProjectSecretSync.requests(
        project,
        Settings.default.copy(secretStore = Settings.SecretStore(backend = Some("secret-manager")))
      ),
      Vector.empty
    )
  }

  test(
    "a service taking a variable from a project secret waits until the provider has it in step"
  ) {
    val (unanswered, asked) = pass(takesStripeKey, Map.empty)
    assertEquals(asked, Vector("shop.secret-sync.checkout"))
    assertEquals(
      unanswered.holds,
      Vector(SecretAccess.Hold.Waiting(ProjectSecretSync.waiting("checkout")))
    )
    assert(unanswered.unacknowledged)
    // Synced, but a generation behind the one the project now has: still waiting.
    val behind = pass(takesStripeKey, Map(synced("Ready", Some(1600L))))._1
    assertEquals(
      behind.holds,
      Vector(SecretAccess.Hold.Waiting(ProjectSecretSync.waiting("checkout")))
    )
    val current = pass(takesStripeKey, Map(synced("Ready", Some(1700L))))._1
    assertEquals(current.holds, Vector.empty)
    assert(!current.unacknowledged)
  }

  test("a sync the provider could not make is the service's failure, in the provider's words") {
    assertEquals(
      pass(takesStripeKey, Map(synced("Failed", None, Some("entry not found"))))._1.holds,
      Vector(SecretAccess.Hold.Refused(ProjectSecretSync.failed("checkout", "entry not found")))
    )
  }

  test("a service that takes nothing from a project secret waits on nothing and reads nothing") {
    val (seen, asked) = pass(List(EnvEntry("MODE", value = Some("live"))), Map.empty)
    assertEquals(seen.holds, Vector.empty)
    assertEquals(asked, Vector.empty)
    val (postgres, none) =
      pass(takesStripeKey, Map.empty, Settings.default.copy(cloud = Some(cloud)))
    assertEquals(postgres.holds, Vector.empty)
    assertEquals(none, Vector.empty)
  }
