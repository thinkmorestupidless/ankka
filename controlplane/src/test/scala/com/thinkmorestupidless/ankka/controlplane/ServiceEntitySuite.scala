package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.application.ServiceEntity
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.ServiceEvent.*
import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

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

    // Deliberate: the generation is the thing observations are matched against, and a re-apply
    // is a new statement of desired state that deserves a fresh observation. Since feature 004
    // it no longer rolls the pods by itself — only a changed template, or `restart`, does that.
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

  test("an observation carries the database phase through to the status, as the CLI's phrase") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())

    val _ = kit.call(ServiceEntity.observe)(
      ServiceObservation(
        1L,
        ServiceLifecycle.Ready,
        readyInstances = 1,
        desiredInstances = 1,
        database = Some("Provisioned")
      )
    )
    assertEquals(kit.currentState.database, Some("Provisioned"))
    assertEquals(kit.currentState.toStatus.database, Some("provisioned"))
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
    assertEquals(paused.events, Vector(ServicePaused()))
    assertEquals(paused.replyValue.lifecycle, ServiceLifecycle.Paused)
    assertEquals(paused.replyValue.desiredInstances, 0)
    assertEquals(kit.currentState.targetInstances, 0)

    val again = kit.call(ServiceEntity.pause)
    assertEquals(again.events, Vector.empty)
    assertEquals(again.replyValue.lifecycle, ServiceLifecycle.Paused)
  }

  // --- Exposure (feature 005): desired state beside the descriptor, like pause.

  test("exposing persists one event, is idempotent, and leaves the generation alone") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())

    val exposed = kit.call(ServiceEntity.expose)
    assertEquals(exposed.events, Vector(ServiceExposed()))
    assertEquals(exposed.replyValue.exposed, true)
    assertEquals(exposed.replyValue.generation, 1L)
    assertEquals(kit.currentState.exposed, true)

    val again = kit.call(ServiceEntity.expose)
    assertEquals(again.events, Vector.empty)
    assertEquals(again.replyValue.exposed, true)
  }

  test("unexposing persists one event, is idempotent, and changes nothing else") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _ = kit.call(ServiceEntity.observe)(
      ServiceObservation(1L, ServiceLifecycle.Ready, readyInstances = 3, desiredInstances = 3)
    )
    val before = kit.call(ServiceEntity.expose).replyValue

    val unexposed = kit.call(ServiceEntity.unexpose)
    assertEquals(unexposed.events, Vector(ServiceUnexposed()))
    assertEquals(unexposed.replyValue, before.copy(exposed = false))
    assertEquals(unexposed.replyValue.lifecycle, ServiceLifecycle.Ready)

    val again = kit.call(ServiceEntity.unexpose)
    assertEquals(again.events, Vector.empty)
    assertEquals(again.replyValue.exposed, false)
  }

  test("a service that does not exist cannot be exposed or unexposed") {
    val kit = newKit
    assertEquals(kit.call(ServiceEntity.expose).error.code, ErrorCode.NotFound)
    assertEquals(kit.call(ServiceEntity.unexpose).error.code, ErrorCode.NotFound)
  }

  test("apply, restart, pause and resume leave exposure as it was") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.expose)

    assertEquals(
      kit.call(ServiceEntity.applyDescriptor)(applying(image = "cart:2.0")).replyValue.exposed,
      true
    )
    assertEquals(kit.call(ServiceEntity.restart).replyValue.exposed, true)
    assertEquals(kit.call(ServiceEntity.pause).replyValue.exposed, true)
    assertEquals(kit.call(ServiceEntity.resume).replyValue.exposed, true)
    assertEquals(kit.currentState.exposed, true)
  }

  test("exposure survives a replay, and deleting then re-applying starts unexposed") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.expose)
    val replayed = kit.allEvents.foldLeft(Service.empty(kit.currentState.key)) {
      case (service, applied: ServiceApplied) =>
        service.onApplied(applied.descriptor, applied.generation)
      case (service, _: ServiceExposed)   => service.onExposed
      case (service, _: ServiceUnexposed) => service.onUnexposed
      case (service, _)                   => service
    }
    assertEquals(replayed.exposed, true)

    val _ = kit.call(ServiceEntity.delete)
    val _ = kit.call(ServiceEntity.applyDescriptor)(applying())
    assertEquals(kit.currentState.exposed, false)
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

  test(
    "a restart counts, so the operator can roll the pods without the generation on the template"
  ) {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    assertEquals(kit.currentState.restarts, 0)
    val _ = kit.call(ServiceEntity.restart)
    val _ = kit.call(ServiceEntity.restart)
    assertEquals(kit.currentState.restarts, 2)
    // Replay agrees: the count is folded from events, not remembered.
    val replayed =
      kit.allEvents.foldLeft(
        com.thinkmorestupidless.ankka.controlplane.domain.Service.empty(kit.currentState.key)
      ) {
        case (
              service,
              applied: com.thinkmorestupidless.ankka.controlplane.domain.ServiceEvent.ServiceApplied
            ) =>
          service.onApplied(applied.descriptor, applied.generation)
        case (
              service,
              restarted: com.thinkmorestupidless.ankka.controlplane.domain.ServiceEvent.ServiceRestarted
            ) =>
          service.onRestarted(restarted.generation)
        case (service, _) => service
      }
    assertEquals(replayed.restarts, 2)
  }

  test("deletion is a tombstone: the entity remains, the service does not") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.delete)

    assert(!kit.isDeleted, "the journal is the audit trail, so the entity is not removed")
    assertEquals(kit.call(ServiceEntity.get).error.code, ErrorCode.NotFound)
    assertEquals(kit.call(ServiceEntity.desiredState).replyValue, None)
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

  test("the desired state is what the projector reads") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying(image = "cart:3.0"))
    assertEquals(
      kit.call(ServiceEntity.desiredState).replyValue.flatMap(_.descriptor),
      Some(descriptor(image = "cart:3.0"))
    )
  }

  test("state is rebuilt purely by folding events") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.observe)(ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1))
    val _   = kit.call(ServiceEntity.pause)
    val _   = kit.call(ServiceEntity.resume)
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying(image = "cart:2.0"))

    // The entity's own fold, applied from empty over the journal it wrote — no second copy of it.
    val folded = kit.allEvents.foldLeft(Service.empty(ServiceKey("acme", "cart")))(Service.fold)
    assertEquals(folded, kit.currentState, "replaying the journal must reproduce the state")
  }

  test("a malformed entity id is rejected at construction") {
    intercept[IllegalArgumentException] {
      EventSourcedTestKit.of(ServiceEntity, "no-project-separator")
    }: Unit
  }

  // ── Confirmed observations ────────────────────────────────────────────────

  test("an unconfirmed observation is recorded and surfaces on the status") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.observe)(ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1))

    val result = kit.call(ServiceEntity.observe)(
      ServiceObservation(
        1L,
        ServiceLifecycle.Ready,
        1,
        1,
        detail = Some("could not reach the cluster: connection refused"),
        confirmed = false
      )
    )

    assertEquals(result.events.size, 1)
    assertEquals(kit.currentState.confirmed, false)
    assertEquals(kit.call(ServiceEntity.get).replyValue.confirmed, false)
  }

  test("repeating an unconfirmed observation is refused, so an outage costs one event") {
    // The reconciler reports on a timer. Without the dedupe an unreachable cluster would
    // grow the journal for as long as it stayed unreachable.
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val unreachable =
      ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1, Some("unreachable"), confirmed = false)

    val first  = kit.call(ServiceEntity.observe)(unreachable)
    val second = kit.call(ServiceEntity.observe)(unreachable)
    val third  = kit.call(ServiceEntity.observe)(unreachable)

    assertEquals(first.events.size, 1)
    assertEquals(second.events, Vector.empty)
    assertEquals(third.events, Vector.empty)
  }

  test("recovering from unconfirmed back to confirmed is a change worth recording") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _ = kit.call(ServiceEntity.observe)(
      ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1, Some("unreachable"), confirmed = false)
    )

    val recovered =
      kit.call(ServiceEntity.observe)(ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1))

    assertEquals(recovered.events.size, 1)
    assertEquals(kit.currentState.confirmed, true)
    assertEquals(kit.currentState.detail, None)
  }

  test("an unconfirmed observation of a superseded generation is still dropped") {
    // Staleness wins over freshness reporting: a report about generation 1 says nothing
    // about generation 2, confirmed or not.
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying(image = "cart:2.0"))

    val stale = kit.call(ServiceEntity.observe)(
      ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1, None, confirmed = false)
    )

    assertEquals(stale.events, Vector.empty)
    assertEquals(kit.currentState.confirmed, true)
  }

  test("an operator action clears staleness, because it supersedes what was observed") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _ = kit.call(ServiceEntity.observe)(
      ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1, Some("unreachable"), confirmed = false)
    )
    assertEquals(kit.currentState.confirmed, false)

    val _ = kit.call(ServiceEntity.applyDescriptor)(applying(image = "cart:2.0"))
    assertEquals(kit.currentState.confirmed, true)
  }

  test("an observation cannot un-pause a service") {
    // `paused` is desired state and `lifecycle` is observed. Deriving one from the other
    // let a report that was already in flight when the pause happened erase the pause —
    // and pause does not bump the generation, so the staleness guard could not catch it.
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.pause)
    assert(kit.currentState.isPaused)

    // A report from before the pause, describing the same generation.
    val _ = kit.call(ServiceEntity.observe)(ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1))

    assert(kit.currentState.isPaused, "a stale report must not resume a paused service")
    assertEquals(kit.currentState.targetInstances, 0)
  }

  test("resuming clears the pause even if the last observation said Paused") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(applying())
    val _   = kit.call(ServiceEntity.pause)
    val _   = kit.call(ServiceEntity.observe)(ServiceObservation(1L, ServiceLifecycle.Paused, 0, 0))
    val _   = kit.call(ServiceEntity.resume)

    assert(!kit.currentState.isPaused)
    assertEquals(kit.currentState.targetInstances, 1)
  }

  // ── suspension and history (feature 008) ───────────────────────────────────

  private val now   = java.time.Instant.parse("2026-09-22T10:00:00Z")
  private val alice = Attribution(Actor("alice", Some("alice@example.test")), now)
  private val carol =
    Attribution(Actor("carol", Some("carol@example.test"), administrative = true), now)

  test("suspend stops a running service and says so; a paused one keeps saying paused") {
    val kit       = newKit
    val _         = kit.call(ServiceEntity.applyDescriptor, alice.metadata)(applying())
    val suspended = kit.call(ServiceEntity.suspend, carol.metadata)
    assertEquals(suspended.events, Vector(ServiceSuspended(Some(carol.actor), Some(now))))
    assertEquals(suspended.replyValue.lifecycle, ServiceLifecycle.Suspended)
    assertEquals(suspended.replyValue.suspended, true)
    assertEquals(kit.call(ServiceEntity.desiredState).replyValue.map(_.targetInstances), Some(0))
    assertEquals(kit.call(ServiceEntity.suspend, carol.metadata).events, Vector.empty, "idempotent")

    val paused = newKit
    val _      = paused.call(ServiceEntity.applyDescriptor, alice.metadata)(applying())
    val _      = paused.call(ServiceEntity.pause, alice.metadata)
    val both   = paused.call(ServiceEntity.suspend, carol.metadata).replyValue
    assertEquals((both.lifecycle, both.suspended), (ServiceLifecycle.Paused, true))
  }

  test("reinstate restores what the members had chosen, and an observation cannot un-suspend") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor, alice.metadata)(applying())
    val _   = kit.call(ServiceEntity.suspend, carol.metadata)
    val observed =
      kit.call(ServiceEntity.observe)(ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1))
    assertEquals(
      observed.state.lifecycle,
      ServiceLifecycle.Suspended,
      "desired state wins over a stale report"
    )
    assertEquals(
      kit.call(ServiceEntity.reinstate, carol.metadata).replyValue.lifecycle,
      ServiceLifecycle.UpdateInProgress
    )
    assertEquals(
      kit.call(ServiceEntity.reinstate, carol.metadata).events,
      Vector.empty,
      "idempotent"
    )

    val paused = newKit
    val _      = paused.call(ServiceEntity.applyDescriptor, alice.metadata)(applying())
    val _      = paused.call(ServiceEntity.pause, alice.metadata)
    val _      = paused.call(ServiceEntity.suspend, carol.metadata)
    val back   = paused.call(ServiceEntity.reinstate, carol.metadata).replyValue
    assertEquals((back.lifecycle, back.suspended), (ServiceLifecycle.Paused, false))
  }

  test("history names who did what, newest first, capped, and never counts observations") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor, alice.metadata)(applying())
    val bob = Attribution(Actor("bob", Some("bob@example.test")), now.plusSeconds(60))
    val _   = kit.call(ServiceEntity.pause, bob.metadata)
    val _   = kit.call(ServiceEntity.observe)(ServiceObservation(1L, ServiceLifecycle.Paused, 0, 0))
    val history = kit.call(ServiceEntity.history).replyValue
    assertEquals(history.map(_.kind), Vector("paused", "applied"))
    assertEquals(history.head.actor.map(_.subject), Some("bob"))
    assertEquals(history.head.at, Some(now.plusSeconds(60)))
    assertEquals(history.last.actor.map(_.display), Some(Some("alice@example.test")))

    // An unattributed command (pre-feature shape) is remembered with no actor.
    val _ = kit.call(ServiceEntity.resume)
    assertEquals(kit.call(ServiceEntity.history).replyValue.head.actor, None)

    (1 to 60).foreach(_ => kit.call(ServiceEntity.restart, alice.metadata))
    assertEquals(kit.call(ServiceEntity.history).replyValue.size, Service.HistoryLimit)
  }

  test("the history of a deleted service is still readable; a never-applied one is not") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor, alice.metadata)(applying())
    val _   = kit.call(ServiceEntity.delete, alice.metadata)
    assertEquals(kit.call(ServiceEntity.history).replyValue.head.kind, "deleted")
    assertEquals(newKit.call(ServiceEntity.history).error.code, ErrorCode.NotFound)
  }
