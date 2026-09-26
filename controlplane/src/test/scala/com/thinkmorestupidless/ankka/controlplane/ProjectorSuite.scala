package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.application.{
  OrganizationEntity,
  ProjectEntity,
  ServiceEntity
}
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, ServiceProjector}
import com.thinkmorestupidless.ankka.controlplane.domain.{
  ApplyService,
  ConfigureRegistry,
  ServiceKey
}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.crd.AnkkaServiceStatus
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * The control plane's half of reconciliation, against real Postgres and a fake cluster.
 *
 * Everything the projector does — projecting, sweeping, ingesting status, retrying, reporting
 * staleness — runs here with no Kubernetes anywhere. That is the point of the seam: the paths that
 * matter most are the ones a real cluster will not produce on demand.
 */
class ProjectorSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  private val Project   = "checkout"
  private val Service   = "cart"
  private val Key       = ServiceKey(Project, Service)
  private val Namespace = "ankka-checkout"

  private var testKit: AnkkaTestKit        = null
  private var fake: FakeAnkkaServiceClient = null

  // A one-second sweep so a test asserts on the next pass rather than waiting thirty.
  private val config =
    DeployConfig.default.copy(sweepInterval = 1.second, platformVersion = "0.3.1")

  override def beforeAll(): Unit =
    fake = new FakeAnkkaServiceClient
    val projector = ServiceProjector.withClient(config, fake)
    testKit = AnkkaTestKit.start(
      ControlPlane.componentsWith(projector),
      Seq(ProjectionRuntime(), projector)
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def client = testKit.componentClient

  private def applyService(image: String = "cart:1.0"): Unit =
    val _ = client
      .forEventSourcedEntity(EntityId(Key.id))
      .call(ServiceEntity.applyDescriptor)
      .invoke(ApplyService(Project, ServiceDescriptor(Service, ServiceSpec(image))))

  private def status(): ServiceStatus =
    client.forEventSourcedEntity(EntityId(Key.id)).call(ServiceEntity.get).invoke()

  /** Projections are asynchronous; every assertion against one polls to a deadline. */
  private def eventually(timeout: FiniteDuration = 30.seconds)(check: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    var passed   = false
    while !passed && System.nanoTime() < deadline do
      passed =
        try check
        catch case _: Throwable => false
      if !passed then Thread.sleep(100)
    if !passed then fail(s"condition did not hold within $timeout")

  test("1. applying a descriptor projects a resource into the cluster") {
    val _ = client
      .forEventSourcedEntity(EntityId("acme"))
      .call(OrganizationEntity.createOrganization)
      .invoke("Acme")
    val _ = client
      .forEventSourcedEntity(EntityId(Project))
      .call(ProjectEntity.createProject)
      .invoke(CreateProject("Checkout", "acme"))

    applyService()

    eventually() {
      fake.current(Namespace, Service).exists(_.spec.generation == 1L)
    }
    assertEquals(fake.current(Namespace, Service).map(_.spec.image), Some("cart:1.0"))
  }

  test("2. a resource nothing has reported on is unconfirmed, and says why") {
    // This is how an operator discovers the operator is not installed, is crash-looping,
    // or is watching a different namespace prefix.
    eventually() {
      val s = status()
      !s.confirmed && s.detail.contains("no operator has reported on this service")
    }
  }

  test("3. an operator's report becomes a confirmed observation") {
    fake.setStatus(
      Namespace,
      Service,
      AnkkaServiceStatus(
        generation = 1L,
        lifecycle = "Ready",
        readyInstances = 1,
        desiredInstances = 1
      )
    )
    eventually() {
      val s = status()
      s.confirmed && s.lifecycle == ServiceLifecycle.Ready && s.readyInstances == 1
    }
  }

  test("4. a steady-state service performs no writes and records no observations") {
    // The requirement that is easiest to satisfy on paper and hardest to keep: without the
    // idempotence check in `put` and the dedupe in `observe`, this is a continuous write
    // load against the API server rather than a failing test.
    val before = client.forEventSourcedEntity(EntityId(Key.id)).call(ServiceEntity.get).invoke()
    fake.resetCounters()

    Thread.sleep(4000) // several sweeps

    assertEquals(fake.writeCount, 0, "a steady-state service must not be rewritten")
    val after = client.forEventSourcedEntity(EntityId(Key.id)).call(ServiceEntity.get).invoke()
    assertEquals(after, before, "no new observation should have been recorded")
  }

  test("5. an out-of-band edit to the resource is restored") {
    // The control plane's record is authoritative: someone editing the spec with kubectl
    // does not get to change what the platform is trying to run.
    fake.driftEdit(Namespace, Service)(_.copy(image = "somebody-elses-image:evil"))

    eventually() {
      fake.current(Namespace, Service).exists(_.spec.image == "cart:1.0")
    }
  }

  test("6. an out-of-band deletion of the resource is restored") {
    fake.driftDelete(Namespace, Service)
    eventually() {
      fake.current(Namespace, Service).isDefined
    }
  }

  test("7. an unreachable cluster reports unconfirmed exactly once, then recovers") {
    fake.setStatus(
      Namespace,
      Service,
      AnkkaServiceStatus(
        generation = 1L,
        lifecycle = "Ready",
        readyInstances = 1,
        desiredInstances = 1
      )
    )
    eventually()(status().confirmed)

    fake.disconnect()
    eventually() {
      val s = status()
      !s.confirmed && s.detail.exists(_.contains("could not reach the cluster"))
    }

    // The last known state is restated, not erased — an operator still sees what was true.
    assertEquals(status().lifecycle, ServiceLifecycle.Ready)

    val duringOutage = status()
    Thread.sleep(3000) // several failed sweeps
    assertEquals(
      status(),
      duringOutage,
      "repeating an unconfirmed observation must not grow the journal"
    )

    fake.reconnect()
    eventually()(status().confirmed)
  }

  test("8. applying while the cluster is unreachable still succeeds and deploys later") {
    // The reason desired state is persisted separately from acting on it.
    fake.disconnect()
    applyService(image = "cart:2.0")
    assertEquals(status().generation, 2L, "the apply is durable regardless of the cluster")

    fake.reconnect()
    eventually() {
      fake.current(Namespace, Service).exists(_.spec.image == "cart:2.0")
    }
  }

  test("9. a transient failure is retried rather than abandoned") {
    fake.failNext(3)
    applyService(image = "cart:3.0")
    eventually() {
      fake.current(Namespace, Service).exists(_.spec.image == "cart:3.0")
    }
  }

  test("10. deleting a service removes its resource, and survives an outage to do it") {
    fake.disconnect()
    val _ = client.forEventSourcedEntity(EntityId(Key.id)).call(ServiceEntity.delete).invoke()

    fake.reconnect()
    eventually() {
      fake.current(Namespace, Service).isEmpty
    }
  }

  test("11. a restart re-projects from the journal without duplicating anything") {
    applyService(image = "cart:4.0")
    eventually()(fake.current(Namespace, Service).isDefined)

    testKit.restartService()

    eventually() {
      fake.current(Namespace, Service).exists(_.spec.image == "cart:4.0")
    }
    assertEquals(fake.all.count(r => r.name == Service && r.namespace == Namespace), 1)
  }

  test(
    "12. a declared runtime outside the platform's range is refused: Unavailable, both versions, no resource"
  ) {
    // Feature 006. An apply records intent and succeeds; the projector is what declines.
    val before = fake.current(Namespace, Service).map(_.spec.image)
    val _ = client
      .forEventSourcedEntity(EntityId(Key.id))
      .call(ServiceEntity.applyDescriptor)
      .invoke(
        ApplyService(
          Project,
          ServiceDescriptor(Service, ServiceSpec("cart:5.0", runtime = Some("9.0.0")))
        )
      )
    eventually() {
      val s = status()
      s.lifecycle == ServiceLifecycle.Unavailable && s.confirmed &&
      s.detail.exists(d => d.contains("9.0.0") && d.contains("runtimes 0.2.x–0.3.x"))
    }
    // The resource in the cluster is the previous one: nothing was written for 9.0.0.
    assertEquals(fake.current(Namespace, Service).map(_.spec.image), before)

    // A supported declaration proceeds as if nothing happened.
    val _ = client
      .forEventSourcedEntity(EntityId(Key.id))
      .call(ServiceEntity.applyDescriptor)
      .invoke(
        ApplyService(
          Project,
          ServiceDescriptor(Service, ServiceSpec("cart:6.0", runtime = Some("0.2.0")))
        )
      )
    eventually() {
      fake.current(Namespace, Service).exists(_.spec.image == "cart:6.0")
    }
  }

  test("the resource names the project's pull secret, and stops when the registry is cleared") {
    // The credential belongs to the project and the resource belongs to the service, so this is the
    // one path where a projection has to read another entity. It reads it on *every* pass, which is
    // what makes setting or clearing a registry reach a service that is already running without any
    // event on the service itself.
    val project = client.forEventSourcedEntity(EntityId(Project))

    val _ = project
      .call(ProjectEntity.configureRegistry)
      .invoke(ConfigureRegistry("ghcr.io", "octocat", "ankka-registry"))

    // No apply: the sweep alone must carry it, because nothing about the service changed.
    eventually() {
      fake.current(Namespace, Service).flatMap(_.spec.imagePullSecret).contains("ankka-registry")
    }

    val _ = project.call(ProjectEntity.clearRegistry).invoke()
    eventually() {
      fake.current(Namespace, Service).exists(_.spec.imagePullSecret.isEmpty)
    }
  }
