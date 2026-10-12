package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.runtime.remote.{Payload, RemoteWorkflowHost}
import com.thinkmorestupidless.ankka.sdk.WorkflowLifecycle

/** How a workflow's journal is read as changes: only a recorded state is one (spec 046, D2). */
class WorkflowChangesSuite extends munit.FunSuite:

  private val bytes   = """{"id":"c1"}""".getBytes("UTF-8")
  private val scala   = ChangeReader.workflow("checkout")
  private val stamped = StandingRecord("Running", "charge", Map("reserve" -> 2), "")

  test("a stamped state is a change carrying the state and the stamp's standing") {
    scala.read(WorkflowRecord.stateUpdated(bytes, Some(stamped))) match
      case SourceChange.Changed(payload, Some(standing)) =>
        assertEquals(payload.manifest, "checkout")
        assert(payload.data.sameElements(bytes))
        assertEquals(
          standing,
          WorkflowLifecycle("Running", Some("charge"), Map("reserve" -> 2), None)
        )
      case other => fail(s"read as $other")
  }

  test("a failed standing carries its reason, and an empty step is none") {
    val failed = StandingRecord("Failed", "", Map.empty, "declined")
    scala.read(WorkflowRecord.stateUpdated(bytes, Some(failed))) match
      case SourceChange.Changed(_, Some(standing)) =>
        assertEquals(standing.pendingStep, None)
        assertEquals(standing.failure, Some("declined"))
        assert(standing.isFailed)
      case other => fail(s"read as $other")
  }

  test("a state recorded before the stamp has the standing Unknown") {
    scala.read(WorkflowRecord.stateUpdated(bytes)) match
      case SourceChange.Changed(_, Some(standing)) =>
        assert(standing.isUnknown)
        assertEquals(standing, WorkflowLifecycle.unknown)
      case other => fail(s"read as $other")
  }

  test("a deletion is the deletion") {
    assertEquals(scala.read(WorkflowRecord.deleted), SourceChange.Deleted)
  }

  test("a record that holds no state is no change") {
    Vector(
      WorkflowRecord.transitioned("charge", bytes),
      WorkflowRecord.paused("charge", 1L),
      WorkflowRecord.ended,
      WorkflowRecord.failed("declined"),
      WorkflowRecord.retryRecorded("charge")
    ).foreach(record => assertEquals(scala.read(record), SourceChange.Skip, record.kind))
  }

  test("a process's state is unpacked to its own manifest and content type") {
    val packed = Payload("application/x-thing", "checkout-state", bytes)
    val stored = RemoteWorkflowHost.pack(packed)
    ChangeReader.remoteWorkflow.read(WorkflowRecord.stateUpdated(stored, Some(stamped))) match
      case SourceChange.Changed(payload, Some(_)) =>
        assertEquals(payload.contentType, "application/x-thing")
        assertEquals(payload.manifest, "checkout-state")
        assert(payload.data.sameElements(bytes))
      case other => fail(s"read as $other")
  }

  test("a Scala workflow's state is its bytes, untouched, under the declared manifest") {
    scala.read(WorkflowRecord.stateUpdated(bytes, Some(stamped))) match
      case SourceChange.Changed(payload, _) =>
        assertEquals(payload.contentType, Payload.contentTypeFor("checkout"))
        assert(payload.data.sameElements(bytes))
      case other => fail(s"read as $other")
  }

  test(
    "an entity's journal reads as before: an event, a deletion, and an expiry that is no change"
  ) {
    val event = ChangeReader.journal.read(JournalRecord.domain("cart-event", bytes))
    event match
      case SourceChange.Changed(payload, None) => assertEquals(payload.manifest, "cart-event")
      case other                               => fail(s"read as $other")
    assertEquals(ChangeReader.journal.read(JournalRecord.deleted), SourceChange.Deleted)
    assertEquals(ChangeReader.journal.read(JournalRecord.expiry(1L)), SourceChange.Skip)
  }
