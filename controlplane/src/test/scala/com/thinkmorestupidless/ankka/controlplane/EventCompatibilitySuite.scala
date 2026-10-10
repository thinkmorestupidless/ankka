package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.controlplane.application.{
  OrganizationEntity,
  ProjectEntity,
  ServiceEntity
}
import com.thinkmorestupidless.ankka.controlplane.api.{
  Mount,
  ServiceDescriptor,
  ServiceLifecycle,
  ServiceSpec
}
import com.thinkmorestupidless.ankka.controlplane.domain.*

import scala.jdk.CollectionConverters.*

/**
 * A journal written before feature 008 still replays (FR-024).
 *
 * The fixture holds every command-produced event *as the old codec wrote it* — no `actor`, no `at`.
 * Each must decode through the entity's current serializer and read as unattributed. A field added
 * to an event without a default fails here, before it fails against a real installation's journal.
 */
class EventCompatibilitySuite extends munit.FunSuite with LogCapturing:

  private val fixture =
    new ObjectMapper().readTree(getClass.getResourceAsStream("/journal/pre-008-events.json"))

  private def samples(kind: String): Vector[Array[Byte]] =
    fixture.get(kind).elements().asScala.map(_.toString.getBytes("UTF-8")).toVector

  test("organization events from before the feature decode as unattributed") {
    val decoded = samples("organization-event").map(OrganizationEntity.eventSerializer.fromBytes)
    assertEquals(decoded.size, 3)
    decoded.foreach {
      case OrganizationEvent.OrganizationCreated(name, actor, at, owner) =>
        assertEquals(name, "Acme Corp"); assertEquals(actor, None); assertEquals(at, None)
        assertEquals(owner, None)
      case OrganizationEvent.OrganizationRenamed(_, actor, at) =>
        assertEquals((actor, at), (None, None))
      case OrganizationEvent.OrganizationDeleted(actor, at) =>
        assertEquals((actor, at), (None, None))
      case other => fail(s"not an event the fixture holds: $other")
    }
  }

  test("an organization created for an owner has a pinned shape (feature 011)") {
    val json =
      """{"type":"OrganizationCreated","name":"Acme Corp",""" +
        """"actor":{"subject":"carol","administrative":true},""" +
        """"owner":{"subject":"alice","email":"alice@example.test","display":"Alice"}}"""
    val decoded = OrganizationEntity.eventSerializer.fromBytes(json.getBytes("UTF-8"))
    assertEquals(
      decoded,
      OrganizationEvent.OrganizationCreated(
        "Acme Corp",
        Some(Actor("carol", None, administrative = true)),
        None,
        Some(
          com.thinkmorestupidless.ankka.controlplane.api
            .Owner("alice", Some("alice@example.test"), Some("Alice"))
        )
      )
    )
    val written = String(OrganizationEntity.eventSerializer.toBytes(decoded), "UTF-8")
    assert(written.contains(""""owner":{"subject":"alice""""), written)
  }

  test("project events from before the feature decode as unattributed") {
    val decoded = samples("project-event").map(ProjectEntity.eventSerializer.fromBytes)
    assertEquals(decoded.size, 3)
    decoded.foreach {
      case ProjectEvent.ProjectCreated(_, organizationId, actor, at) =>
        assertEquals(organizationId, "acme"); assertEquals((actor, at), (None, None))
      case ProjectEvent.ProjectRenamed(_, actor, at) => assertEquals((actor, at), (None, None))
      case ProjectEvent.ProjectDeleted(actor, at)    => assertEquals((actor, at), (None, None))
      case other => fail(s"not an event the fixture holds: $other")
    }
  }

  test("service events from before the feature decode as unattributed, observations unchanged") {
    val decoded = samples("service-event").map(ServiceEntity.eventSerializer.fromBytes)
    assertEquals(decoded.size, 8)
    decoded.foreach {
      case ServiceEvent.ServiceApplied(projectId, descriptor, generation, actor, at, rolledBack) =>
        assertEquals((projectId, descriptor.name, generation), ("checkout", "cart", 1L))
        assertEquals((actor, at), (None, None))
        // An apply from before rollbacks rolled back to nothing (feature 033).
        assertEquals(rolledBack, None)
        // A descriptor stored before gRPC endpoints existed declares none (feature 020).
        assertEquals((descriptor.service.grpc, descriptor.service.resolvedGrpcPort), (false, None))
      case ServiceEvent.ServiceRestarted(generation, actor, at) =>
        assertEquals((generation, actor, at), (2L, None, None))
      case ServiceEvent.ServicePaused(actor, at)    => assertEquals((actor, at), (None, None))
      case ServiceEvent.ServiceResumed(actor, at)   => assertEquals((actor, at), (None, None))
      case ServiceEvent.ServiceExposed(actor, at)   => assertEquals((actor, at), (None, None))
      case ServiceEvent.ServiceUnexposed(actor, at) => assertEquals((actor, at), (None, None))
      case ServiceEvent.ServiceDeleted(actor, at)   => assertEquals((actor, at), (None, None))
      case observed: ServiceEvent.ServiceObserved =>
        assertEquals(observed.readyInstances, 1); assert(observed.confirmed)
      case other => fail(s"not an event the fixture holds: $other")
    }
  }

  test(
    "a storage credential issued again round-trips, and its stored form names no key (feature 039)"
  ) {
    val event: ServiceEvent = ServiceEvent.StorageCredentialReissued(3)
    val bytes               = ServiceEntity.eventSerializer.toBytes(event)
    assertEquals(ServiceEntity.eventSerializer.fromBytes(bytes), event)
    val json = String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    assert(!json.toLowerCase.contains("secret") && !json.toLowerCase.contains("key\""), json)
  }

  test(
    "a move asked for, and an observation reporting a store and a move, round-trip (feature 039)"
  ) {
    val events: Vector[ServiceEvent] = Vector(
      ServiceEvent.StorageMoveRequested(2, "30m"),
      ServiceEvent.ServiceObserved(
        3L,
        ServiceLifecycle.Ready,
        1,
        1,
        None,
        storage = Some(
          StorageReport(
            store = Some("gcs"),
            bucket = Some("ankka-casino-kyc-3f9a1c2e"),
            bucketAddress = Some("https://storage.googleapis.com/ankka-casino-kyc-3f9a1c2e"),
            location = Some("europe-west2"),
            softDeleteDays = Some(7),
            move = Some("Switched"),
            moveGeneration = Some(2)
          )
        )
      )
    )
    for event <- events do
      assertEquals(
        ServiceEntity.eventSerializer.fromBytes(ServiceEntity.eventSerializer.toBytes(event)),
        event
      )
  }

  test("a project's location, named and taken back, round-trips (feature 039)") {
    for event <- Vector[ProjectEvent](
        ProjectEvent.ProjectLocationSet(Some("europe-west6")),
        ProjectEvent.ProjectLocationSet(None)
      )
    do
      assertEquals(
        ProjectEntity.eventSerializer.fromBytes(ProjectEntity.eventSerializer.toBytes(event)),
        event
      )
  }

  test("an observation from before feature 039 reports no store") {
    val observed = samples("service-event")
      .map(ServiceEntity.eventSerializer.fromBytes)
      .collectFirst { case o: ServiceEvent.ServiceObserved => o }
      .getOrElse(fail("the fixture has no ServiceObserved"))
    assertEquals(observed.storage, None)
  }

  test("a descriptor of feature 039 round-trips through the event, its new fields whole") {
    val descriptor = ServiceDescriptor(
      "kyc",
      ServiceSpec(
        "kyc:1",
        provisionObjectStorage = true,
        exposeObjectStorage = true,
        objectStorageOrigins = Vector("https://play.example"),
        objectStorageCredential = false,
        objectStorageVersionAgeDays = Some(365)
      )
    )
    val event: ServiceEvent = ServiceEvent.ServiceApplied("casino", descriptor, 1L)
    val bytes               = ServiceEntity.eventSerializer.toBytes(event)
    assertEquals(ServiceEntity.eventSerializer.fromBytes(bytes), event)
  }

  test(
    "a descriptor applied before feature 039 names no origins, takes a credential and keeps every version"
  ) {
    val applied = samples("service-event")
      .map(ServiceEntity.eventSerializer.fromBytes)
      .collectFirst { case a: ServiceEvent.ServiceApplied => a }
      .getOrElse(fail("the fixture has no ServiceApplied"))
    val spec = applied.descriptor.service
    assertEquals(
      (spec.objectStorageOrigins, spec.objectStorageCredential, spec.objectStorageVersionAgeDays),
      (Vector.empty, true, None)
    )
  }

  test("a descriptor applied before web hosting decodes with no mounts, callers or process port") {
    val applied = samples("service-event")
      .map(ServiceEntity.eventSerializer.fromBytes)
      .collectFirst { case a: ServiceEvent.ServiceApplied => a }
      .getOrElse(fail("the fixture has no ServiceApplied"))
    val spec = applied.descriptor.service
    assertEquals((spec.mounts, spec.callers, spec.processPort), (Vector.empty, Vector.empty, None))
  }

  test("a web-hosted service's descriptor round-trips through the event, its new fields whole") {
    val descriptor = ServiceDescriptor(
      "web",
      ServiceSpec(
        "shop-web:1",
        hosting = ServiceSpec.Web,
        processPort = Some(3000),
        mounts = Vector(Mount("/api/cart", "cart")),
        callers = Vector("orders", "billing/invoices", "*")
      )
    )
    val event: ServiceEvent = ServiceEvent.ServiceApplied("shop", descriptor, 1L)
    val bytes               = ServiceEntity.eventSerializer.toBytes(event)
    assertEquals(ServiceEntity.eventSerializer.fromBytes(bytes), event)
  }

  test("a descriptor applied before object storage asks for no bucket (feature 034)") {
    val applied = samples("service-event")
      .map(ServiceEntity.eventSerializer.fromBytes)
      .collectFirst { case a: ServiceEvent.ServiceApplied => a }
      .getOrElse(fail("the fixture has no ServiceApplied"))
    assertEquals(applied.descriptor.service.provisionObjectStorage, false)
  }

  test("a descriptor that asks for a bucket round-trips through the event") {
    val descriptor =
      ServiceDescriptor("reports", ServiceSpec("reports:1", provisionObjectStorage = true))
    val event: ServiceEvent = ServiceEvent.ServiceApplied("shop", descriptor, 1L)
    assertEquals(
      ServiceEntity.eventSerializer.fromBytes(ServiceEntity.eventSerializer.toBytes(event)),
      event
    )
  }

  test("an attributed event round-trips with its actor and time") {
    val at = java.time.Instant.parse("2026-09-22T10:00:00Z")
    val event = ServiceEvent.ServicePaused(
      Some(Actor("alice", Some("alice@example.test"), administrative = true)),
      Some(at)
    )
    val bytes = ServiceEntity.eventSerializer.toBytes(event)
    assertEquals(ServiceEntity.eventSerializer.fromBytes(bytes), event)
    assert(new String(bytes, "UTF-8").contains("\"administrative\":true"))
  }

  test("a project's registry events round-trip, and the wire form is pinned") {
    // The wire form is asserted on literally because the journal is the one thing a later release
    // cannot change its mind about: a renamed field is a project that silently loses its registry
    // on the next replay, and a *password* field appearing here at all would be the defect this
    // design exists to prevent.
    val at = java.time.Instant.parse("2026-09-25T10:00:00Z")
    val configured = ProjectEvent.RegistryConfigured(
      "ghcr.io",
      "octocat",
      "ankka-registry",
      Some(Actor("alice", Some("alice@example.test"))),
      Some(at)
    )
    val bytes = ProjectEntity.eventSerializer.toBytes(configured)
    val json  = new String(bytes, "UTF-8")
    assertEquals(ProjectEntity.eventSerializer.fromBytes(bytes), configured)
    assert(json.contains("\"type\":\"RegistryConfigured\""), json)
    assert(json.contains("\"server\":\"ghcr.io\""), json)
    assert(json.contains("\"username\":\"octocat\""), json)
    assert(json.contains("\"secretName\":\"ankka-registry\""), json)
    assert(!json.contains("password"), s"a password reached the journal: $json")

    val cleared = ProjectEvent.RegistryCleared(None, None)
    assertEquals(
      ProjectEntity.eventSerializer.fromBytes(ProjectEntity.eventSerializer.toBytes(cleared)),
      cleared
    )
  }

  test("the control plane records that a project secret was set and never a value") {
    // The field names are pinned exactly: a field that could carry a value must not exist at all.
    val at = java.time.Instant.parse("2026-10-03T10:00:00Z")
    val set = ProjectEvent.ProjectSecretEntriesSet(
      "checkout",
      Vector("STRIPE_KEY", "WEBHOOK_KEY"),
      Some(Actor("alice", Some("alice@example.test"))),
      Some(at)
    )
    val removed = ProjectEvent.ProjectSecretEntryRemoved("checkout", "WEBHOOK_KEY", None, None)
    def fields(json: String): Set[String] =
      val top = com.fasterxml.jackson.databind.ObjectMapper().readTree(json)
      top.fieldNames().asScala.toSet
    for (event, expected) <- Vector(
        set     -> Set("type", "name", "entries", "actor", "at"),
        removed -> Set("type", "name", "entry")
      )
    do
      val bytes = ProjectEntity.eventSerializer.toBytes(event)
      val json  = new String(bytes, "UTF-8")
      assertEquals(ProjectEntity.eventSerializer.fromBytes(bytes), event)
      assertEquals(fields(json), expected, json)
      assert(!json.contains("sk_live"), s"a value reached the journal: $json")
  }

  test("a topic's declaration pins its wire form, with and without a contract (feature 037)") {
    val at   = java.time.Instant.parse("2026-10-07T10:00:00Z")
    val bare = ProjectEvent.ProjectTopicDeclared("orders", 3, None, Some(at))
    val full = ProjectEvent.ProjectTopicDeclared(
      "orders",
      3,
      Some(Actor("alice", Some("alice@example.test"))),
      Some(at),
      compacted = true,
      contract = Some(com.thinkmorestupidless.ankka.core.Contract("order.v1", "sha256:ab"))
    )
    def fields(json: String): Set[String] =
      com.fasterxml.jackson.databind.ObjectMapper().readTree(json).fieldNames().asScala.toSet
    for (event, expected) <- Vector(
        // A field at its default is left out, so an event from before feature 037 and one written
        // now without a contract are the same bytes.
        bare -> Set("type", "name", "partitions", "at"),
        full -> Set("type", "name", "partitions", "actor", "at", "compacted", "contract")
      )
    do
      val bytes = ProjectEntity.eventSerializer.toBytes(event)
      val json  = new String(bytes, "UTF-8")
      assertEquals(ProjectEntity.eventSerializer.fromBytes(bytes), event)
      assertEquals(fields(json), expected, json)
    // The journal never holds a schema document: a contract is its name and fingerprint.
    val json = new String(ProjectEntity.eventSerializer.toBytes(full), "UTF-8")
    assert(
      json.contains("\"contract\":{\"name\":\"order.v1\",\"fingerprint\":\"sha256:ab\"}"),
      json
    )
    // An event from before feature 037 decodes as neither compacted nor under a contract.
    val old =
      """{"type":"ProjectTopicDeclared","name":"orders","partitions":3,"at":"2026-10-07T10:00:00Z"}"""
    assertEquals(ProjectEntity.eventSerializer.fromBytes(old.getBytes("UTF-8")), bare)
  }

  test("a broker's declaration pins its wire form, and never a credential (feature 037)") {
    val at = java.time.Instant.parse("2026-10-07T10:00:00Z")
    val declared = ProjectEvent.ProjectBrokerDeclared(
      "legacy",
      "kafka.legacy:9094",
      "sasl",
      "legacy-credential",
      None,
      Some(at)
    )
    val removed = ProjectEvent.ProjectBrokerRemoved("legacy", None, None)
    def fields(json: String): Set[String] =
      com.fasterxml.jackson.databind.ObjectMapper().readTree(json).fieldNames().asScala.toSet
    for (event, expected) <- Vector(
        declared -> Set("type", "name", "bootstrap", "shape", "secretName", "at"),
        removed  -> Set("type", "name")
      )
    do
      val bytes = ProjectEntity.eventSerializer.toBytes(event)
      val json  = new String(bytes, "UTF-8")
      assertEquals(ProjectEntity.eventSerializer.fromBytes(bytes), event)
      assertEquals(fields(json), expected, json)
      assert(!json.contains("password"), json)
  }

  test("a project's state from before project secrets decodes with none") {
    val old   = """{"id":"checkout","name":"Checkout","organizationId":"acme","deleted":false}"""
    val state = ProjectEntity.stateSerializer.fromBytes(old.getBytes("UTF-8"))
    assertEquals(state.secrets, Map.empty)
  }

  test("a project's state from before the registry decodes with none") {
    // The snapshot, not the events: a `Project` written by an earlier release has no `registry`
    // field at all, and must read as a project with no registry rather than failing to decode.
    val old   = """{"id":"checkout","name":"Checkout","organizationId":"acme","deleted":false}"""
    val state = ProjectEntity.stateSerializer.fromBytes(old.getBytes("UTF-8"))
    assertEquals(state.registry, None)
    assertEquals(state.name, "Checkout")
  }

  test("an organization's state from before quotas decodes with none and no usage (feature 015)") {
    val old =
      """{"id":"acme","name":"Acme Corp","deleted":false,"members":{},"invitations":{},""" +
        """"disabled":false}"""
    val state = OrganizationEntity.stateSerializer.fromBytes(old.getBytes("UTF-8"))
    assertEquals(state.quota, None)
    assertEquals(state.usage, com.thinkmorestupidless.ankka.controlplane.api.Usage.zero)
    assertEquals(state.name, "Acme Corp")
  }

  test("the quota events have a pinned wire shape, and a null limit reads as unlimited") {
    val set =
      """{"type":"QuotaSet","quota":{"projects":2,"services":null},""" +
        """"actor":{"subject":"carol","administrative":true}}"""
    assertEquals(
      OrganizationEntity.eventSerializer.fromBytes(set.getBytes("UTF-8")),
      OrganizationEvent.QuotaSet(
        com.thinkmorestupidless.ankka.controlplane.api.Quota(projects = Some(2)),
        Some(Actor("carol", None, administrative = true))
      )
    )
    val reserved = """{"type":"ServiceReserved","key":"checkout/cart","instances":2}"""
    assertEquals(
      OrganizationEntity.eventSerializer.fromBytes(reserved.getBytes("UTF-8")),
      OrganizationEvent.ServiceReserved("checkout/cart", 2)
    )
    val reconciled =
      """{"type":"UsageReconciled","projects":["checkout"],"services":{"checkout/cart":2}}"""
    assertEquals(
      OrganizationEntity.eventSerializer.fromBytes(reconciled.getBytes("UTF-8")),
      OrganizationEvent.UsageReconciled(Set("checkout"), Map("checkout/cart" -> 2))
    )
    val written = String(
      OrganizationEntity.eventSerializer.toBytes(OrganizationEvent.ProjectReserved("checkout")),
      "UTF-8"
    )
    assertEquals(written, """{"type":"ProjectReserved","projectId":"checkout"}""")
  }

  // ── rollbacks (feature 033) ────────────────────────────────────────────────

  /**
   * A `Service` exactly as the code before feature 033 wrote it: applied at 1, restarted at 2,
   * paused and resumed — no `kept`, and history entries with no image, digest or `rolledBackTo`.
   * Captured from that code's own serializer, not written by hand.
   */
  private val OldService =
    """{"key":{"projectId":"acme","name":"cart"},"descriptor":{"name":"cart","service":{"image":"cart:1.0"}},"generation":2,"paused":false,"lifecycle":"UpdateInProgress","readyInstances":0,"desiredInstances":0,"detail":null,"confirmed":true,"deleted":false,"restarts":1,"history":[{"kind":"resumed","generation":2,"actor":{"subject":"alice","display":"alice@example.test"},"at":"2026-09-22T10:00:00Z"},{"kind":"paused","generation":2,"actor":{"subject":"alice","display":"alice@example.test"},"at":"2026-09-22T10:00:00Z"},{"kind":"restarted","generation":2,"actor":{"subject":"alice","display":"alice@example.test"},"at":"2026-09-22T10:00:00Z"},{"kind":"applied","generation":1,"actor":{"subject":"alice","display":"alice@example.test"},"at":"2026-09-22T10:00:00Z"}]}"""

  private def oldService = ServiceEntity.stateSerializer.fromBytes(OldService.getBytes("UTF-8"))

  private def applied(state: Service, image: String, rolledBackTo: Option[Long] = None) =
    Service.fold(
      state,
      ServiceEvent.ServiceApplied(
        "acme",
        ServiceDescriptor("cart", ServiceSpec(image)),
        state.generation + 1,
        rolledBackTo = rolledBackTo
      )
    )

  test("history an older platform kept is still read, and shows no image and no digest") {
    val state = oldService
    assertEquals(state.kept, Vector.empty)
    assertEquals(state.history.map(_.kind), Vector("resumed", "paused", "restarted", "applied"))
    assertEquals(state.history.flatMap(e => e.image ++ e.digest ++ e.rolledBackTo), Vector.empty)
    assertEquals((state.generation, state.restarts, state.image), (2L, 1, "cart:1.0"))
  }

  test(
    "an older state's descriptor stands as kept, at the generation its history says it applied"
  ) {
    val seeded = oldService.keptDescriptors
    assertEquals(
      seeded.map(k => (k.generation, k.descriptor.service.image)),
      Vector(1L -> "cart:1.0")
    )
    val next = applied(oldService, "cart:2.0")
    assertEquals(
      next.keptDescriptors.map(k => (k.generation, k.descriptor.service.image)),
      Vector(3L -> "cart:2.0", 1L -> "cart:1.0")
    )
  }

  test(
    "a service applied before the platform kept descriptors is rolled back to the descriptor it had"
  ) {
    val next   = applied(oldService, "cart:2.0")
    val target = next.rollbackTarget(None).fold(r => fail(r.message("cart")), identity)
    assertEquals((target.generation, target.descriptor.service.image), (1L, "cart:1.0"))
    val back = applied(next, target.descriptor.service.image, rolledBackTo = Some(1L))
    assertEquals((back.generation, back.image), (4L, "cart:1.0"))
    assertEquals(back.history.head.kind, "rolled-back")
  }

  test("a rollback's event has a pinned shape, and an apply's still has no such field") {
    val descriptor = ServiceDescriptor("cart", ServiceSpec("cart:1.0"))
    val rollback: ServiceEvent =
      ServiceEvent.ServiceApplied("acme", descriptor, 3L, rolledBackTo = Some(1L))
    val written = String(ServiceEntity.eventSerializer.toBytes(rollback), "UTF-8")
    assertEquals(
      written,
      """{"type":"ServiceApplied","projectId":"acme","descriptor":{"name":"cart","service":{"image":"cart:1.0"}},"generation":3,"rolledBackTo":1}"""
    )
    assertEquals(ServiceEntity.eventSerializer.fromBytes(written.getBytes("UTF-8")), rollback)
    val apply: ServiceEvent = ServiceEvent.ServiceApplied("acme", descriptor, 3L)
    assert(!String(ServiceEntity.eventSerializer.toBytes(apply), "UTF-8").contains("rolledBackTo"))
  }

  test("a build from before rollbacks reads a rollback's event as an apply") {
    // The shared codec skips a field it does not know, which is what lets a node still on the
    // previous version follow a rolling update.
    import PreRollback.OldEvent
    val old = PreRollback.serializer
    val rollback: ServiceEvent = ServiceEvent.ServiceApplied(
      "acme",
      ServiceDescriptor("cart", ServiceSpec("cart:1.0")),
      3L,
      rolledBackTo = Some(1L)
    )
    assertEquals(
      old.fromBytes(ServiceEntity.eventSerializer.toBytes(rollback)),
      OldEvent.ServiceApplied("acme", ServiceDescriptor("cart", ServiceSpec("cart:1.0")), 3L)
    )
  }

  /** `ServiceApplied` as the build before feature 033 declared it: five fields. */

  // ── custom hostnames (feature 045) ─────────────────────────────────────────────────────────

  test("the hostname events have a pinned wire shape, and decode with no actor and no take-away") {
    def read(json: String) = ServiceEntity.eventSerializer.fromBytes(json.getBytes("UTF-8"))
    assertEquals(
      read("""{"type":"CustomHostnameAdded","hostname":"app.example.com"}"""),
      ServiceEvent.CustomHostnameAdded("app.example.com")
    )
    assertEquals(
      read("""{"type":"CustomHostnameRemoved","hostname":"app.example.com"}"""),
      ServiceEvent.CustomHostnameRemoved("app.example.com")
    )
    val taken = String(
      ServiceEntity.eventSerializer.toBytes(
        ServiceEvent.CustomHostnameRemoved("app.example.com", byAdministrator = true)
      ),
      "UTF-8"
    )
    assertEquals(
      taken,
      """{"type":"CustomHostnameRemoved","hostname":"app.example.com","byAdministrator":true}"""
    )
  }

  test("an observation from before custom hostnames reports none") {
    val observed = ServiceEntity.eventSerializer.fromBytes(
      """{"type":"ServiceObserved","generation":1,"lifecycle":"Ready","readyInstances":1,"desiredInstances":1}"""
        .getBytes("UTF-8")
    )
    observed match
      case o: ServiceEvent.ServiceObserved => assertEquals(o.hostnames, Vector.empty)
      case other                           => fail(s"decoded as $other")
  }

private object PreRollback:
  import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

  enum OldEvent:
    case ServiceApplied(
        projectId: String,
        descriptor: ServiceDescriptor,
        generation: Long,
        actor: Option[Actor] = None,
        at: Option[java.time.Instant] = None
    )

  val serializer = com.thinkmorestupidless.ankka.core.Codecs.serializer[OldEvent]("service-event")
