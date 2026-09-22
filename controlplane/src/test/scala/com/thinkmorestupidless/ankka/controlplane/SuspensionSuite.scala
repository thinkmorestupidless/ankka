package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.application.{
  OrganizationEntity,
  ProjectEntity,
  ServiceEntity
}
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, ServiceProjector}
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Disabling an organization stops its services (spec US3, FR-033/035; research R10): the trigger on
 * the organization's events is the latency half, the projector's sweep the correctness half, and
 * the resource the operator sees says `paused` for either.
 */
class SuspensionSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit        = null
  private var fake: FakeAnkkaServiceClient = null

  private val now   = java.time.Instant.parse("2026-09-22T10:00:00Z")
  private val alice = Attribution(Actor("alice", Some("alice@example.test")), now)
  private val carol =
    Attribution(Actor("carol", Some("carol@example.test"), administrative = true), now)

  override def beforeAll(): Unit =
    fake = new FakeAnkkaServiceClient
    val projector =
      ServiceProjector.withClient(DeployConfig.default.copy(sweepInterval = 1.second), fake)
    testKit = AnkkaTestKit.start(
      ControlPlane.componentsWith(projector),
      Seq(ProjectionRuntime(), projector)
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def client                   = testKit.componentClient
  private def organization(id: String) = client.forEventSourcedEntity(EntityId(id))
  private def service(name: String) =
    client.forEventSourcedEntity(EntityId(ServiceKey("checkout", name).id))

  private def status(name: String): ServiceStatus = service(name).call(ServiceEntity.get).invoke()

  private def eventually(what: String, timeout: FiniteDuration = 30.seconds)(
      check: => Boolean
  ): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(100)
    if !passed then fail(s"$what did not hold within $timeout")

  private def apply(name: String): Unit =
    val _ = service(name)
      .call(ServiceEntity.applyDescriptor)
      .withMetadata(alice.metadata)
      .invoke(ApplyService("checkout", ServiceDescriptor(name, ServiceSpec("cart:1.0"))))

  test("1. disabling suspends the running service and leaves the paused one paused (S3.9)") {
    val _ = organization("acme")
      .call(OrganizationEntity.createOrganization)
      .withMetadata(alice.metadata)
      .invoke("Acme")
    val _ = client
      .forEventSourcedEntity(EntityId("checkout"))
      .call(ProjectEntity.createProject)
      .withMetadata(alice.metadata)
      .invoke(CreateProject("Checkout", "acme"))
    apply("cart")
    apply("inventory")
    val _ = service("inventory").call(ServiceEntity.pause).withMetadata(alice.metadata).invoke()
    eventually("both services are projected") {
      fake.current("ankka-checkout", "cart").isDefined && fake
        .current("ankka-checkout", "inventory")
        .isDefined
    }

    val _ =
      organization("acme").call(OrganizationEntity.disable).withMetadata(carol.metadata).invoke()
    eventually("cart is suspended") {
      val s = status("cart")
      s.suspended && s.lifecycle == ServiceLifecycle.Suspended
    }
    eventually("the cluster is told to stop it") {
      fake.current("ankka-checkout", "cart").exists(_.spec.paused)
    }
    val inventory = status("inventory")
    assertEquals((inventory.lifecycle, inventory.suspended), (ServiceLifecycle.Paused, true))
    val history = service("cart").call(ServiceEntity.history).invoke()
    assertEquals(history.head.kind, "suspended")
    assertEquals(
      history.head.actor.map(a => (a.subject, a.administrative)),
      Some(("carol", true)),
      "the administrator who disabled it"
    )
  }

  test("2. an apply that slipped past the trigger is caught by the sweep (the lagging-view case)") {
    // The endpoint would have refused this; here the entity is commanded directly, which is the
    // race the spec names — an apply in flight during a disable. Its row reaches the listing view
    // after the trigger has already enumerated, and the sweep finds it.
    apply("late")
    eventually("the late service is suspended by the sweep", 45.seconds) {
      status("late").suspended
    }
    assertEquals(
      service("late").call(ServiceEntity.history).invoke().head.actor.map(_.subject),
      Some("ankka-controlplane")
    )
  }

  test("3. enabling reinstates exactly what was running (S3.11)") {
    val _ =
      organization("acme").call(OrganizationEntity.enable).withMetadata(carol.metadata).invoke()
    eventually("cart runs again") {
      val s = status("cart")
      !s.suspended && s.lifecycle == ServiceLifecycle.UpdateInProgress
    }
    eventually("the cluster is told")(fake.current("ankka-checkout", "cart").exists(!_.spec.paused))
    eventually("late runs again")(!status("late").suspended)
    val inventory = status("inventory")
    assertEquals(
      (inventory.lifecycle, inventory.suspended),
      (ServiceLifecycle.Paused, false),
      "still paused by its members"
    )
    assert(fake.current("ankka-checkout", "inventory").exists(_.spec.paused))
  }

  test(
    "4. after a pause and a resume, the listing row follows the entity to Ready (the k3s regression)"
  ) {
    // What EndToEndClusterSuite saw: `services get` said Ready 1/1 while `services list` stayed
    // Paused 1/1 — a listing row that never left Paused once the resume landed.
    val rows = testKit.service.viewClient.forView(
      com.thinkmorestupidless.ankka.controlplane.application.ServiceRows
    )
    apply("web")
    def report(lifecycle: String, ready: Int, desired: Int): Unit =
      fake.setStatus(
        "ankka-checkout",
        "web",
        com.thinkmorestupidless.ankka.crd.AnkkaServiceStatus(
          generation = 1L,
          lifecycle = lifecycle,
          readyInstances = ready,
          desiredInstances = desired
        )
      )
    eventually("web is projected")(fake.current("ankka-checkout", "web").isDefined)
    report("Ready", 1, 1)
    eventually("the row is Ready")(
      rows.get("checkout/web").exists(_.lifecycle == ServiceLifecycle.Ready)
    )
    val _ = service("web").call(ServiceEntity.pause).withMetadata(alice.metadata).invoke()
    eventually("the row is Paused")(
      rows.get("checkout/web").exists(_.lifecycle == ServiceLifecycle.Paused)
    )
    report("Ready", 1, 1) // the operator has not scaled down yet
    Thread.sleep(500)
    report("UpdateInProgress", 0, 0)
    val _ = service("web").call(ServiceEntity.resume).withMetadata(alice.metadata).invoke()
    report("Paused", 0, 0) // the operator\'s stale report of the pause, landing after the resume
    Thread.sleep(500)
    report("UpdateInProgress", 0, 1)
    Thread.sleep(500)
    report("Ready", 1, 1)
    eventually("the entity is Ready")(status("web").lifecycle == ServiceLifecycle.Ready)
    eventually("the row is Ready again", 20.seconds) {
      val row = rows.get("checkout/web")
      row.exists(_.lifecycle == ServiceLifecycle.Ready)
    }
  }
