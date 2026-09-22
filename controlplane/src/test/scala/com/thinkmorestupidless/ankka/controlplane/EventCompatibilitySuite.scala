package com.thinkmorestupidless.ankka.controlplane

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
class EventCompatibilitySuite extends munit.FunSuite:

  private val fixture =
    new ObjectMapper().readTree(getClass.getResourceAsStream("/journal/pre-008-events.json"))

  private def samples(kind: String): Vector[Array[Byte]] =
    fixture.get(kind).elements().asScala.map(_.toString.getBytes("UTF-8")).toVector

  test("organization events from before the feature decode as unattributed") {
    val decoded = samples("organization-event").map(OrganizationEntity.eventSerializer.fromBytes)
    assertEquals(decoded.size, 3)
    decoded.foreach {
      case OrganizationEvent.OrganizationCreated(name, actor, at) =>
        assertEquals(name, "Acme Corp"); assertEquals(actor, None); assertEquals(at, None)
      case OrganizationEvent.OrganizationRenamed(_, actor, at) =>
        assertEquals((actor, at), (None, None))
      case OrganizationEvent.OrganizationDeleted(actor, at) =>
        assertEquals((actor, at), (None, None))
    }
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
