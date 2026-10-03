package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.controlplane.application.{
  OrganizationEntity,
  ProjectEntity,
  ServiceEntity
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
    }
  }

  test("service events from before the feature decode as unattributed, observations unchanged") {
    val decoded = samples("service-event").map(ServiceEntity.eventSerializer.fromBytes)
    assertEquals(decoded.size, 8)
    decoded.foreach {
      case ServiceEvent.ServiceApplied(projectId, descriptor, generation, actor, at) =>
        assertEquals((projectId, descriptor.name, generation), ("checkout", "cart", 1L))
        assertEquals((actor, at), (None, None))
      case ServiceEvent.ServiceRestarted(generation, actor, at) =>
        assertEquals((generation, actor, at), (2L, None, None))
      case ServiceEvent.ServicePaused(actor, at)    => assertEquals((actor, at), (None, None))
      case ServiceEvent.ServiceResumed(actor, at)   => assertEquals((actor, at), (None, None))
      case ServiceEvent.ServiceExposed(actor, at)   => assertEquals((actor, at), (None, None))
      case ServiceEvent.ServiceUnexposed(actor, at) => assertEquals((actor, at), (None, None))
      case ServiceEvent.ServiceDeleted(actor, at)   => assertEquals((actor, at), (None, None))
      case observed: ServiceEvent.ServiceObserved =>
        assertEquals(observed.readyInstances, 1); assert(observed.confirmed)
    }
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
