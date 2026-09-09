package nakka.controlplane

import nakka.controlplane.api.*
import nakka.controlplane.application.ServiceEntity
import nakka.controlplane.domain.*
import nakka.controlplane.domain.ServiceEvent.*
import nakka.core.ErrorCode
import nakka.testkit.EventSourcedTestKit

/** Desired state, observed state, and the rules that keep them apart. */
class ServiceEntitySuite extends munit.FunSuite:

  private def newKit = EventSourcedTestKit.of(ServiceEntity, "acme/cart")

  private def descriptor(
      name: String = "cart",
      image: String = "cart:1.0",
      minInstances: Int = 1
  ) =
    ServiceDescriptor(
      name,
      ServiceSpec(
        image,
        resources = ServiceResources(autoscaling = Autoscaling(minInstances = minInstances))
      )
    )

  private def applying(
      name: String = "cart",
      image: String = "cart:1.0",
      projectId: String = "acme"
  ) =
    ApplyService(projectId, descriptor(name, image))

  test("applying a descriptor creates the service at generation 1") {
    val kit    = newKit
    val result = kit.call(ServiceEntity.applyDescriptor)(applying())

    assertEquals(result.events, Vector(ServiceApplied("acme", descriptor(), 1L)))
    assertEquals(result.replyValue.generation, 1L)
    assertEquals(result.replyValue.lifecycle, ServiceLifecycle.UpdateInProgress)
    assertEquals(result.replyValue.image, "cart:1.0")
    assertEquals(result.replyValue.projectId, "acme")
    assertEquals(result.replyValue.name, "cart")
  }

  test("re-applying an unchanged descriptor still bumps the generation") {
    val kit    = newKit
    val _      = kit.call(ServiceEntity.applyDescriptor)(applying())
    val second = kit.call(ServiceEntity.applyDescriptor)(applying())

    // Deliberate: `apply` re-runs the rollout. `restart` is not a synonym for it, but an
    // operator repeating `apply` is asking for the image to be pulled again.
    assertEquals(second.replyValue.generation, 2L)
    assertEquals(kit.allEvents.size, 2)
  }

  test("a descriptor naming a different service is refused") {
    val result = newKit.call(ServiceEntity.applyDescriptor)(applying(name = "basket"))
    assert(result.isError)
    assert(result.errorMessage.contains("names service 'basket'"), result.errorMessage)
    assertEquals(result.events, Vector.empty)
  }

  test("a descriptor targeting a different project is refused") {
    val result = newKit.call(ServiceEntity.applyDescriptor)(applying(projectId = "other"))
    assert(result.isError)
    assert(result.errorMessage.contains("targets project 'other'"), result.errorMessage)
  }

  test("an invalid descriptor reports every problem at once") {
    val broken = ApplyService(
      "acme",
      ServiceDescriptor(
        "cart",
        ServiceSpec(
          image = "",
          env = Vector(EnvVar("BOTH", Some("x"), Some(SecretKeyRef("s", "k")))),
          resources = ServiceResources(instanceType = "enormous")
        )
      )
    )
    val result = newKit.call(ServiceEntity.applyDescriptor)(broken)

    assert(result.isError)
    val message = result.errorMessage
    assert(message.contains("image must not be empty"), message)
    assert(message.contains("sets both value and secretKeyRef"), message)
    assert(message.contains("unknown instanceType 'enormous'"), message)
  }

  test("an observation of the current generation is recorded") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())

    val result = kit.call(ServiceEntity.observe)(
      ServiceObservation(1L, ServiceLifecycle.Ready, readyInstances = 1, desiredInstances = 1)
    )
    assertEquals(result.events.size, 1)
    assertEquals(kit.currentState.lifecycle, ServiceLifecycle.Ready)
    assertEquals(kit.currentState.readyInstances, 1)
  }

  test("an observation of a superseded generation is dropped") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying(image = "cart:2.0"))

    val stale = kit.call(ServiceEntity.observe)(
      ServiceObservation(1L, ServiceLifecycle.Ready, readyInstances = 1, desiredInstances = 1)
    )
    assertEquals(stale.events, Vector.empty, "a report about generation 1 must not land on 2")
    assertEquals(kit.currentState.lifecycle, ServiceLifecycle.UpdateInProgress)
    assertEquals(kit.currentState.generation, 2L)
  }

  test("an observation that changes nothing is not persisted") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val observation =
      ServiceObservation(1L, ServiceLifecycle.Ready, readyInstances = 1, desiredInstances = 1)
    val _ = kit.call(ServiceEntity.observe)(observation)

    val before = kit.allEvents.size
    val again  = kit.call(ServiceEntity.observe)(observation)

    // The reconciler runs on a timer. Without this, a steady-state service would append
    // an identical event forever.
    assertEquals(again.events, Vector.empty)
    assertEquals(kit.allEvents.size, before)
  }

  test("an observation before the service exists is ignored, not an error") {
    val result = newKit.call(ServiceEntity.observe)(
      ServiceObservation(0L, ServiceLifecycle.Ready, 1, 1)
    )
    assert(!result.isError)
    assertEquals(result.events, Vector.empty)
  }

  test("pausing is idempotent and zeroes the desired instances") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _ = kit.call(ServiceEntity.observe)(
      ServiceObservation(1L, ServiceLifecycle.Ready, readyInstances = 2, desiredInstances = 2)
    )

    val paused = kit.call(ServiceEntity.pause)
    assertEquals(paused.events, Vector(ServicePaused))
    assertEquals(paused.replyValue.lifecycle, ServiceLifecycle.Paused)
    assertEquals(paused.replyValue.desiredInstances, 0)
    assertEquals(kit.currentState.targetInstances, 0)

    val again = kit.call(ServiceEntity.pause)
    assertEquals(again.events, Vector.empty)
    assertEquals(again.replyValue.lifecycle, ServiceLifecycle.Paused)
  }

  test("applying to a paused service changes the descriptor but does not start it") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.pause)

    val applied = kit.call(ServiceEntity.applyDescriptor)(applying(image = "cart:2.0"))
    assertEquals(applied.replyValue.lifecycle, ServiceLifecycle.Paused)
    assertEquals(applied.replyValue.image, "cart:2.0")
    assertEquals(applied.replyValue.generation, 2L)
    assertEquals(kit.currentState.targetInstances, 0)
  }

  test("a paused service cannot be restarted until it is resumed") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.pause)

    val refused = kit.call(ServiceEntity.restart)
    assertEquals(refused.error.code, ErrorCode.Conflict)

    val resumed = kit.call(ServiceEntity.resume)
    assertEquals(resumed.replyValue.lifecycle, ServiceLifecycle.UpdateInProgress)

    val restarted = kit.call(ServiceEntity.restart)
    assertEquals(restarted.replyValue.generation, 2L)
    assertEquals(restarted.replyValue.readyInstances, 0)
  }

  test("resuming a running service is a no-op") {
    val kit    = newKit
    val _      = kit.call(ServiceEntity.applyDescriptor)(applying())
    val result = kit.call(ServiceEntity.resume)
    assertEquals(result.events, Vector.empty)
  }

  test("a restart bumps the generation so a stale observation is recognised") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _ = kit.call(ServiceEntity.observe)(
      ServiceObservation(1L, ServiceLifecycle.Ready, readyInstances = 1, desiredInstances = 1)
    )

    val restarted = kit.call(ServiceEntity.restart)
    assertEquals(restarted.events, Vector(ServiceRestarted(2L)))
    assertEquals(restarted.replyValue.lifecycle, ServiceLifecycle.UpdateInProgress)
    assertEquals(restarted.replyValue.readyInstances, 0, "restarting means the old pods are gone")
  }

  test("deletion is a tombstone: the entity remains, the service does not") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.delete)

    assert(!kit.isDeleted, "the journal is the audit trail, so the entity is not removed")
    assertEquals(kit.call(ServiceEntity.get).error.code, ErrorCode.NotFound)
    assertEquals(kit.call(ServiceEntity.desired).replyValue, None)
  }

  test("a service name is reusable after deletion, unlike a project or organization id") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.delete)

    // A service name is a deployment target, not a tenancy boundary. The generation
    // keeps climbing, so the earlier life is still in the journal.
    // Deleting does not bump the generation — there is nothing left to observe — so the
    // next apply is generation 2.
    val recreated = kit.call(ServiceEntity.applyDescriptor)(applying(image = "cart:9.0"))
    assertEquals(recreated.replyValue.generation, 2L)
    assertEquals(recreated.replyValue.image, "cart:9.0")
    assertEquals(kit.call(ServiceEntity.get).replyValue.image, "cart:9.0")
  }

  test("operations on a service that was never applied are 404s") {
    val kit = newKit
    assertEquals(kit.call(ServiceEntity.get).error.code, ErrorCode.NotFound)
    assertEquals(kit.call(ServiceEntity.pause).error.code, ErrorCode.NotFound)
    assertEquals(kit.call(ServiceEntity.restart).error.code, ErrorCode.NotFound)
    assertEquals(kit.call(ServiceEntity.delete).error.code, ErrorCode.NotFound)
  }

  test("the desired descriptor is what the reconciler reads") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying(image = "cart:3.0"))
    assertEquals(kit.call(ServiceEntity.desired).replyValue, Some(descriptor(image = "cart:3.0")))
  }

  test("state is rebuilt purely by folding events") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.observe)(ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1))
    val _   = kit.call(ServiceEntity.pause)
    val _   = kit.call(ServiceEntity.resume)
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying(image = "cart:2.0"))

    val folded = kit.allEvents.foldLeft(Service.empty(ServiceKey("acme", "cart"))) {
      case (service, ServiceApplied(_, d, generation)) => service.onApplied(d, generation)
      case (service, ServiceRestarted(generation))     => service.onRestarted(generation)
      case (service, ServicePaused)                    => service.onPaused
      case (service, ServiceResumed)                   => service.onResumed
      case (service, observed: ServiceObserved)        => service.onObserved(observed)
      case (service, ServiceDeleted)                   => service.onDeleted
    }
    assertEquals(folded, kit.currentState, "replaying the journal must reproduce the state")
  }

  test("a malformed entity id is rejected at construction") {
    intercept[IllegalArgumentException] {
      EventSourcedTestKit.of(ServiceEntity, "no-project-separator")
    }: Unit
  }
