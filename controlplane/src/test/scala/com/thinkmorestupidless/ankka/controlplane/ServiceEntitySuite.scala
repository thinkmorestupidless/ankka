package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.application.ServiceEntity
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.ServiceEvent.*
import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

/** Desired state, observed state, and the rules that keep them apart. */
class ServiceEntitySuite extends munit.FunSuite with LogCapturing:

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

  test(
    "each object storage phase becomes its phrase, and a bucket is named when one is asked for"
  ) {
    val kit  = newKit
    val asks = descriptor().copy(service = descriptor().service.copy(provisionObjectStorage = true))
    val _    = kit.call(ServiceEntity.applyDescriptor)(ApplyService("acme", asks))
    assertEquals(kit.currentState.toStatus.bucket, Some("acme.cart"))
    assertEquals(kit.currentState.toStatus.objectStorage, None)
    val phrases = Vector(
      "Waiting"     -> "waiting for object storage",
      "Provisioned" -> "provisioned",
      "Recovered"   -> "recovered existing bucket",
      "Supplied"    -> "supplied",
      "Failed"      -> "object storage provisioning failed"
    )
    for (phase, phrase) <- phrases do
      val result = kit.call(ServiceEntity.observe)(
        ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1, objectStorage = Some(phase))
      )
      assertEquals(result.events.size, 1, s"$phase was not recorded")
      assertEquals(kit.currentState.toStatus.objectStorage, Some(phrase))
    // The same report again changes nothing, so nothing is recorded.
    val again = kit.call(ServiceEntity.observe)(
      ServiceObservation(1L, ServiceLifecycle.Ready, 1, 1, objectStorage = Some("Failed"))
    )
    assertEquals(again.events.size, 0)
    // A service that asks for none is named no bucket.
    val plain = newKit
    val _     = plain.call(ServiceEntity.applyDescriptor)(applying())
    assertEquals(plain.currentState.toStatus.bucket, None)
  }

  test("a web-hosted service's status says web, no database, its callers, mounts and port") {
    val kit = newKit
    val web = ServiceDescriptor(
      "cart",
      ServiceSpec(
        "shop-web:1.0",
        hosting = ServiceSpec.Web,
        processPort = Some(3000),
        mounts = Vector(Mount("/api/orders", "orders"), Mount("/admin", "admin")),
        callers = Vector("orders", "billing/invoices", "*")
      )
    )
    val _      = kit.call(ServiceEntity.applyDescriptor)(ApplyService("acme", web))
    val status = kit.currentState.toStatus
    assertEquals(status.hosting, "web")
    assertEquals(status.database, Some("none"))
    assertEquals(status.processPort, Some(3000))
    assertEquals(status.callers, Vector("orders", "billing/invoices", "*"))
    // The states are empty here: filling them means asking another entity, which the endpoint does.
    assertEquals(
      status.mounts,
      Vector(MountStatus("/api/orders", "orders"), MountStatus("/admin", "admin"))
    )
    // Nothing an operator reports gives it a database.
    val _ = kit.call(ServiceEntity.observe)(
      ServiceObservation(
        1L,
        ServiceLifecycle.Ready,
        readyInstances = 1,
        desiredInstances = 1,
        database = Some("Provisioned")
      )
    )
    assertEquals(kit.currentState.toStatus.database, Some("none"))
    // An unstated process port is the platform's default; an embedded service has none of this.
    val plain = newKit
    val _     = plain.call(ServiceEntity.applyDescriptor)(applying())
    assertEquals(plain.currentState.toStatus.processPort, None)
    assertEquals(plain.currentState.toStatus.mounts, Vector.empty)
    assertEquals(plain.currentState.toStatus.callers, Vector.empty)
    assertEquals(plain.currentState.toStatus.database, None)
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

  // Feature 039: a storage credential issued again.

  private def withBucket =
    ApplyService(
      "acme",
      descriptor().copy(service = descriptor().service.copy(provisionObjectStorage = true))
    )

  test(
    "issuing a storage credential again raises its generation, is in the history, and deploys nothing"
  ) {
    val kit    = newKit
    val _      = kit.call(ServiceEntity.applyDescriptor)(withBucket)
    val first  = kit.call(ServiceEntity.reissueStorageCredential)
    val second = kit.call(ServiceEntity.reissueStorageCredential)
    assertEquals(
      second.events.collect { case e: StorageCredentialReissued => e.generation },
      Vector(2)
    )
    assertEquals(first.replyValue.generation, 1L, "the service's own generation does not move")
    assertEquals(kit.currentState.storageCredentialGeneration, 2)
    assertEquals(kit.currentState.history.head.kind, "storage-credential-reissued")
  }

  test("a service whose descriptor asks for no bucket has no storage credential to issue again") {
    val kit     = newKit
    val _       = kit.call(ServiceEntity.applyDescriptor)(applying())
    val refused = kit.call(ServiceEntity.reissueStorageCredential)
    assertEquals(refused.error.code, ErrorCode.Conflict)
    assert(
      refused.error.message.contains("no storage credential to reissue"),
      refused.error.message
    )
  }

  test("a storage credential issued again survives a replay of the journal") {
    val kit      = newKit
    val _        = kit.call(ServiceEntity.applyDescriptor)(withBucket)
    val _        = kit.call(ServiceEntity.reissueStorageCredential)
    val replayed = kit.allEvents.foldLeft(Service.empty(ServiceKey("acme", "cart")))(Service.fold)
    assertEquals(replayed.storageCredentialGeneration, 1)
  }

  // Feature 039: a move from Garage to Google Cloud Storage.

  private def reported(state: String, generation: Int, store: String = "garage") =
    ServiceObservation(
      generation = 1L,
      lifecycle = ServiceLifecycle.Ready,
      readyInstances = 1,
      desiredInstances = 1,
      objectStorage = Some("Provisioned"),
      storage = Some(
        StorageReport(store = Some(store), move = Some(state), moveGeneration = Some(generation))
      )
    )

  test("a move is asked for with the shipped bound, and recorded in the history") {
    val kit   = newKit
    val _     = kit.call(ServiceEntity.applyDescriptor)(withBucket)
    val asked = kit.call(ServiceEntity.moveStorage)(StorageMoveRequest())
    assertEquals(
      asked.events.collect { case e: StorageMoveRequested => (e.generation, e.writePauseBound) },
      Vector((1, "10m"))
    )
    assertEquals(kit.currentState.history.head.kind, "storage-moved")
    val named = newKit
    val _     = named.call(ServiceEntity.applyDescriptor)(withBucket)
    val _     = named.call(ServiceEntity.moveStorage)(StorageMoveRequest(Some("30m")))
    assertEquals(named.currentState.storageMove, Some(MoveRequest(1, "30m")))
  }

  test("while a move is in progress, a second move and a re-issue are both refused") {
    val kit = newKit
    val _   = kit.call(ServiceEntity.applyDescriptor)(withBucket)
    val _   = kit.call(ServiceEntity.moveStorage)(StorageMoveRequest())
    // Asked for and not yet reported is in progress too.
    assertEquals(
      kit.call(ServiceEntity.moveStorage)(StorageMoveRequest()).error.code,
      ErrorCode.Conflict
    )
    val _       = kit.call(ServiceEntity.observe)(reported("Pausing", 1))
    val again   = kit.call(ServiceEntity.moveStorage)(StorageMoveRequest())
    val reissue = kit.call(ServiceEntity.reissueStorageCredential)
    assert(again.error.message.contains("is moving"), again.error.message)
    assert(reissue.error.message.contains("is moving"), reissue.error.message)
  }

  test("a move that failed may be asked for again, as the next generation") {
    val kit   = newKit
    val _     = kit.call(ServiceEntity.applyDescriptor)(withBucket)
    val _     = kit.call(ServiceEntity.moveStorage)(StorageMoveRequest())
    val _     = kit.call(ServiceEntity.observe)(reported("Failed", 1))
    val again = kit.call(ServiceEntity.moveStorage)(StorageMoveRequest())
    assertEquals(again.events.collect { case e: StorageMoveRequested => e.generation }, Vector(2))
  }

  test("a bucket already in Google Cloud Storage, or none at all, has nothing to move") {
    val moved = newKit
    val _     = moved.call(ServiceEntity.applyDescriptor)(withBucket)
    val _     = moved.call(ServiceEntity.moveStorage)(StorageMoveRequest())
    val _     = moved.call(ServiceEntity.observe)(reported("Switched", 1, store = "gcs"))
    assert(
      moved.call(ServiceEntity.moveStorage)(StorageMoveRequest()).error.message.contains("already"),
      "a switched bucket is moved again"
    )
    val none = newKit
    val _    = none.call(ServiceEntity.applyDescriptor)(applying())
    assert(
      none.call(ServiceEntity.moveStorage)(StorageMoveRequest()).error.message.contains("no bucket")
    )
  }

  test("a write pause bound is a duration from one minute to a day") {
    for bad <- Vector("30s", "25h", "ten", "10") do
      val kit = newKit
      val _   = kit.call(ServiceEntity.applyDescriptor)(withBucket)
      assertEquals(
        kit.call(ServiceEntity.moveStorage)(StorageMoveRequest(Some(bad))).error.code,
        ErrorCode.BadRequest,
        bad
      )
  }

  test(
    "the status says which store the bucket is in and where its move is, as the operator reported"
  ) {
    val kit    = newKit
    val _      = kit.call(ServiceEntity.applyDescriptor)(withBucket)
    val _      = kit.call(ServiceEntity.moveStorage)(StorageMoveRequest())
    val _      = kit.call(ServiceEntity.observe)(reported("Pausing", 1))
    val status = kit.currentState.toStatus
    assertEquals(status.objectStore, Some("garage"))
    assertEquals(status.storageMove, Some("write pause"))
  }

  test(
    "the installation's bucket settings are reapplied to a bucket in Google Cloud Storage, and to no other"
  ) {
    val garage = newKit
    val _      = garage.call(ServiceEntity.applyDescriptor)(withBucket)
    assertEquals(garage.call(ServiceEntity.reapplyStorageSettings).error.code, ErrorCode.Conflict)

    val gcs = newKit
    val _   = gcs.call(ServiceEntity.applyDescriptor)(withBucket)
    val _ = gcs.call(ServiceEntity.observe)(
      ServiceObservation(
        1L,
        ServiceLifecycle.Ready,
        1,
        1,
        storage = Some(StorageReport(store = Some("gcs")))
      )
    )
    val reapplied = gcs.call(ServiceEntity.reapplyStorageSettings)
    assertEquals(
      reapplied.events.collect { case e: StorageSettingsReapplied => e.generation },
      Vector(1)
    )
    assertEquals(gcs.currentState.history.head.kind, "storage-settings-reapplied")
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

  // ── rollbacks (feature 033) ────────────────────────────────────────────────

  /** A descriptor for `cart` with this image and these variables, applied as alice. */
  private def apply(
      kit: EventSourcedTestKit[ServiceEntity, Service, ServiceEvent],
      image: String,
      env: (String, String)*
  ) =
    kit.call(ServiceEntity.applyDescriptor, alice.metadata)(
      ApplyService(
        "acme",
        ServiceDescriptor(
          "cart",
          ServiceSpec(image, env = env.toVector.map((k, v) => EnvVar(k, Some(v))))
        )
      )
    )

  private def kitWith(images: String*) =
    val kit = newKit
    images.foreach(image => apply(kit, image))
    kit

  private def target(kit: EventSourcedTestKit[ServiceEntity, Service, ServiceEvent]) =
    kit.call(ServiceEntity.rollbackTarget)(RollbackRequest())

  private def rollBack(kit: EventSourcedTestKit[ServiceEntity, Service, ServiceEvent], n: Long) =
    kit.call(ServiceEntity.rollback, alice.metadata)(RollbackService(n))

  /** Roll back as the endpoint does: ask for the target, then name it. */
  private def rollBackUnnamed(kit: EventSourcedTestKit[ServiceEntity, Service, ServiceEvent]) =
    rollBack(kit, target(kit).replyValue.generation)

  test("the kept descriptors are the applied ones, newest first, and nothing else adds to them") {
    val kit = kitWith("cart:1", "cart:2", "cart:3")
    val _   = kit.call(ServiceEntity.restart, alice.metadata)
    val _   = kit.call(ServiceEntity.pause, alice.metadata)
    val _   = kit.call(ServiceEntity.expose, alice.metadata)
    assertEquals(
      kit.currentState.kept.map(k => (k.generation, k.descriptor.service.image)),
      Vector(3L -> "cart:3", 2L -> "cart:2", 1L -> "cart:1")
    )
  }

  test("fifty descriptors are kept and the oldest are forgotten") {
    val kit = kitWith((1 to 60).map(i => s"cart:$i")*)
    assertEquals(kit.currentState.kept.map(_.generation), (60L to 11L by -1).toVector)
  }

  test("deleting keeps the kept descriptors, and applying again adds to them") {
    val kit = kitWith("cart:1", "cart:2")
    val _   = kit.call(ServiceEntity.delete, alice.metadata)
    assertEquals(kit.currentState.kept.map(_.generation), Vector(2L, 1L))
    val _ = apply(kit, "cart:3")
    assertEquals(kit.currentState.kept.map(_.generation), Vector(3L, 2L, 1L))
  }

  test("state with kept descriptors and rollbacks is rebuilt purely by folding events") {
    val kit    = kitWith("cart:1", "cart:2")
    val _      = kit.call(ServiceEntity.restart, alice.metadata)
    val _      = rollBackUnnamed(kit)
    val folded = kit.allEvents.foldLeft(Service.empty(ServiceKey("acme", "cart")))(Service.fold)
    assertEquals(folded, kit.currentState)
  }

  test(
    "the desired state carries no kept descriptor and no history, and stays small with fifty kept"
  ) {
    // Twelve variables of forty characters: a typical descriptor, about 1.4 KiB on the wire.
    val env = (1 to 12).map(i => f"SETTING_NUMBER_$i%02d" -> ("x" * 40))
    val kit = newKit
    (1 to 50).foreach(i => apply(kit, s"ghcr.io/acme-shop/cart-service:2026.10.04-$i", env*))
    assertEquals(kit.currentState.kept.size, 50)
    val desired = kit.call(ServiceEntity.desiredState).replyValue
    assertEquals(desired.map(_.kept), Some(Vector.empty))
    assertEquals(desired.map(_.history), Some(Vector.empty))

    val state = ServiceEntity.stateSerializer.toBytes(kit.currentState).length
    val reply =
      ServiceEntity.desiredState.outputSerializer.toBytes(desired).length
    assert(state < 128 * 1024, s"the state is $state bytes")
    assert(reply < 4 * 1024, s"the desired state's reply is $reply bytes")
  }

  test("rolling back with no generation named applies the descriptor of the generation before") {
    val kit    = kitWith("cart:1", "cart:2")
    val status = rollBackUnnamed(kit).replyValue
    assertEquals((status.generation, status.image), (3L, "cart:1"))
  }

  test("rolling back with no generation named passes over a restart") {
    val kit = kitWith("cart:1", "cart:2")
    val _   = kit.call(ServiceEntity.restart, alice.metadata)
    assertEquals(target(kit).replyValue.generation, 1L)
    assertEquals(rollBackUnnamed(kit).replyValue.image, "cart:1")
    assertEquals(kit.currentState.generation, 4L)
  }

  test(
    "rolling back with no generation named passes over a generation applied with the same descriptor"
  ) {
    val kit = kitWith("cart:1", "cart:2", "cart:2")
    assertEquals(target(kit).replyValue.generation, 1L)
    assertEquals(rollBackUnnamed(kit).replyValue.image, "cart:1")
  }

  test("rolling back twice with no generation named brings back the descriptor it started with") {
    val kit    = kitWith("cart:1", "cart:2")
    val _      = rollBackUnnamed(kit)
    val second = rollBackUnnamed(kit).replyValue
    assertEquals((second.generation, second.image), (4L, "cart:2"))
  }

  test("rolling back to a named generation applies the descriptor of that generation") {
    val kit    = kitWith("cart:1", "cart:2")
    val result = rollBack(kit, 1)
    assertEquals(result.replyValue.generation, 3L)
    assertEquals(
      kit.currentState.descriptor,
      Some(ServiceDescriptor("cart", ServiceSpec("cart:1")))
    )
    assertEquals(
      result.events,
      Vector(
        ServiceApplied(
          "acme",
          ServiceDescriptor("cart", ServiceSpec("cart:1")),
          3L,
          Some(alice.actor),
          Some(now),
          rolledBackTo = Some(1L)
        )
      )
    )
  }

  test("the history shows a roll back, who made it and the generation it was rolled back to") {
    val kit   = kitWith("cart:1", "cart:2")
    val _     = rollBack(kit, 1)
    val entry = kit.call(ServiceEntity.history).replyValue.head
    assertEquals((entry.kind, entry.generation, entry.rolledBackTo), ("rolled-back", 3L, Some(1L)))
    assertEquals(entry.actor.map(_.subject), Some("alice"))
  }

  test("a paused service that is rolled back stays paused") {
    val kit    = kitWith("cart:1", "cart:2")
    val _      = kit.call(ServiceEntity.pause, alice.metadata)
    val status = rollBack(kit, 1).replyValue
    assertEquals(
      (status.generation, status.lifecycle, status.paused),
      (3L, ServiceLifecycle.Paused, true)
    )
    assertEquals(kit.call(ServiceEntity.desiredState).replyValue.map(_.targetInstances), Some(0))
  }

  test("an exposed service that is rolled back stays exposed, and its restart count is unchanged") {
    val kit = kitWith("cart:1", "cart:2")
    val _   = kit.call(ServiceEntity.expose, alice.metadata)
    val _   = kit.call(ServiceEntity.restart, alice.metadata)
    val _   = rollBack(kit, 1)
    assertEquals((kit.currentState.exposed, kit.currentState.restarts), (true, 1))
  }

  test("a roll back to a descriptor the platform no longer accepts is refused") {
    // A descriptor today's rules refuse cannot be applied, so the state is built by folding, as a
    // journal written under older rules would build it.
    val key = ServiceKey("acme", "cart")
    val events = Vector(
      ServiceApplied("acme", ServiceDescriptor("cart", ServiceSpec("")), 1L),
      ServiceApplied("acme", ServiceDescriptor("cart", ServiceSpec("cart:2")), 2L)
    )
    val state   = events.foldLeft(Service.empty(key))(Service.fold)
    val refusal = state.rollingBack(1, None, None).swap.getOrElse(fail("the rollback was allowed"))
    assertEquals(refusal.code, ErrorCode.BadRequest)
    assert(refusal.message.startsWith("invalid descriptor at generation 1: "), refusal.message)
    assert(refusal.message.contains("image"), refusal.message)
  }

  test("a refused roll back persists nothing") {
    val kit    = kitWith("cart:1", "cart:2")
    val before = kit.allEvents
    assert(rollBack(kit, 2).isError)
    assert(rollBack(kit, 9).isError)
    assertEquals(kit.allEvents, before)
  }

  test("a service never applied, or deleted, cannot be rolled back") {
    assertEquals(rollBack(newKit, 1).error.code, ErrorCode.NotFound)
    val kit = kitWith("cart:1", "cart:2")
    val _   = kit.call(ServiceEntity.delete, alice.metadata)
    assertEquals(rollBack(kit, 1).error.code, ErrorCode.NotFound)
  }

  test(
    "a service deleted and applied again is rolled back to a generation from before it was deleted"
  ) {
    val kit    = kitWith("cart:1", "cart:2")
    val _      = kit.call(ServiceEntity.delete, alice.metadata)
    val _      = apply(kit, "cart:3")
    val status = rollBack(kit, 1).replyValue
    assertEquals((status.generation, status.image), (4L, "cart:1"))
  }

  test("a service applied only once cannot be rolled back") {
    val refusal = target(kitWith("search:1")).error
    assertEquals(refusal.code, ErrorCode.Conflict)
    assertEquals(
      refusal.message,
      "service 'cart' has no earlier generation with a different descriptor"
    )
  }

  test("a roll back to the generation a service is at is refused") {
    val refusal = rollBack(kitWith("cart:1", "cart:2"), 2).error
    assertEquals(refusal.code, ErrorCode.Conflict)
    assertEquals(refusal.message, "service 'cart' already has the descriptor of generation 2")
  }

  test("a roll back to a generation with the descriptor the service already has is refused") {
    val kit = kitWith("cart:1", "cart:2")
    val _   = kit.call(ServiceEntity.restart, alice.metadata)
    assertEquals(
      rollBack(kit, 2).errorMessage,
      "service 'cart' already has the descriptor of generation 2"
    )
    assertEquals(kit.currentState.generation, 3L)
  }

  // ── what each generation ran ───────────────────────────────────────────────

  test("the history shows the image and a digest at every generation that was applied") {
    val kit     = kitWith("cart:1", "cart:2", "cart:3")
    val entries = kit.call(ServiceEntity.history).replyValue
    assertEquals(
      entries.map(e => (e.generation, e.image)),
      Vector(3L -> Some("cart:3"), 2L -> Some("cart:2"), 1L -> Some("cart:1"))
    )
    assertEquals(entries.map(_.digest), kit.currentState.kept.map(k => Some(k.descriptor.digest)))
  }

  test("a roll back shows the image and the digest of the generation it was rolled back to") {
    val kit     = kitWith("cart:1", "cart:2")
    val _       = rollBackUnnamed(kit)
    val entries = kit.call(ServiceEntity.history).replyValue
    assertEquals(entries.head.image, Some("cart:1"))
    assertEquals(entries.head.digest, entries.last.digest)
  }

  test("two generations with the same image and a different environment have different digests") {
    val kit                = newKit
    val _                  = apply(kit, "cart:1", "MODE" -> "test")
    val _                  = apply(kit, "cart:1", "MODE" -> "live")
    val Vector(live, test) = kit.call(ServiceEntity.history).replyValue.map(_.digest)
    assertNotEquals(live, test)
  }

  test("two generations applied with the same descriptor have the same digest") {
    val Vector(second, first) =
      kitWith("cart:1", "cart:1").call(ServiceEntity.history).replyValue.map(_.digest)
    assertEquals(second, first)
  }

  test("what applied no descriptor shows no image and no digest in the history") {
    val kit = kitWith("cart:1")
    Vector(
      ServiceEntity.pause,
      ServiceEntity.resume,
      ServiceEntity.restart,
      ServiceEntity.expose
    ).foreach(handle => kit.call(handle, alice.metadata))
    val entries = kit.call(ServiceEntity.history).replyValue
    assertEquals(
      entries.map(_.kind),
      Vector("exposed", "restarted", "resumed", "paused", "applied")
    )
    entries.init.foreach { e =>
      assertEquals((e.image, e.digest, e.rolledBackTo), (None, None, None), e.kind)
    }
  }

  test("a member reads the descriptor that was applied at a generation") {
    val kit = kitWith("cart:1", "cart:2")
    assertEquals(
      kit.call(ServiceEntity.descriptorAt)(1L).replyValue,
      ServiceDescriptor("cart", ServiceSpec("cart:1"))
    )
  }

  test("a deleted service still answers with a past descriptor") {
    val kit = kitWith("cart:1", "cart:2")
    val _   = kit.call(ServiceEntity.delete, alice.metadata)
    assertEquals(kit.call(ServiceEntity.descriptorAt)(1L).replyValue.service.image, "cart:1")
    assertEquals(newKit.call(ServiceEntity.descriptorAt)(1L).error.code, ErrorCode.NotFound)
  }

  // ── beyond the kept descriptors ────────────────────────────────────────────

  test("a roll back to a generation whose descriptor is no longer kept is refused") {
    val kit     = kitWith((1 to 60).map(i => s"cart:$i")*)
    val before  = kit.allEvents.size
    val refusal = rollBack(kit, 3).error
    assertEquals(refusal.code, ErrorCode.Conflict)
    assertEquals(
      refusal.message,
      "the descriptor of generation 3 is no longer kept; the oldest kept is generation 11"
    )
    assertEquals(kit.allEvents.size, before)
  }

  test("a roll back to the oldest generation whose descriptor is kept is made") {
    val kit    = kitWith((1 to 60).map(i => s"cart:$i")*)
    val status = rollBack(kit, 11).replyValue
    assertEquals((status.generation, status.image), (61L, "cart:11"))
  }

  test("a roll back to a generation the service never had is told there is no such generation") {
    val kit = kitWith("cart:1", "cart:2")
    Vector(0L, 9L).foreach { n =>
      val refusal = rollBack(kit, n).error
      assertEquals(
        (refusal.code, refusal.message),
        (ErrorCode.NotFound, s"service 'cart' has no generation $n")
      )
    }
  }

  test("a roll back to a generation that recorded no descriptor is refused") {
    val kit = kitWith("cart:1", "cart:2")
    val _   = kit.call(ServiceEntity.restart, alice.metadata)
    val _   = apply(kit, "cart:4")
    assertEquals(
      rollBack(kit, 3).errorMessage,
      "generation 3 was a restart and ran the descriptor of generation 2"
    )
    assertEquals(
      kit.call(ServiceEntity.descriptorAt)(3L).errorMessage,
      "generation 3 was a restart and ran the descriptor of generation 2"
    )
  }
