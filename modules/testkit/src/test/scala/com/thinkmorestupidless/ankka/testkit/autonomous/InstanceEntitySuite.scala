package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.agent.{ApprovalRequest, Decision, Json, TokenUsage}
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.agent.autonomous.InstanceEvent as E
import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode}
import com.thinkmorestupidless.ankka.testkit.{CommandResult, EventSourcedTestKit}

/** An instance's record: the queue, the current task, the counters and the refusals. */
class InstanceEntitySuite extends munit.FunSuite with LogCapturing:

  private def kit() =
    EventSourcedTestKit.of(InstanceEntity, InstanceEntity.idFor(ComponentId("answerer"), "i-1"))

  private def record(
      k: EventSourcedTestKit[InstanceEntity, InstanceRecord, InstanceEvent],
      e: InstanceEvent
  ) =
    k.call(InstanceEntity.record)(e)

  private def withQueue(ids: String*) =
    val k = kit()
    record(k, E.Created(terminateWhenDone = false, 1L))
    record(k, E.TasksAssigned(ids.toVector, 2L))
    k

  private def working(id: String) =
    val k = withQueue(id)
    record(k, E.TaskSelected(id, 3L))
    record(k, E.TaskStarted(id, 4L))
    k

  private def assertRefused(result: CommandResult[?, ?, ?], code: ErrorCode, mentions: String) =
    assert(result.isError, s"expected a refusal, got ${result.reply}")
    assertEquals(result.error.code, code, result.errorMessage)
    assert(result.errorMessage.contains(mentions), result.errorMessage)

  test("a never-used instance reads as idle and records nothing until it is created") {
    val k = kit()
    val s = k.call(InstanceEntity.get).replyValue
    assertEquals((s.componentId, s.instanceId), ("answerer", "i-1"))
    assertEquals(s.phase, Phase.Idle)
    assert(!s.created)
    assertRefused(record(k, E.TasksAssigned(Vector("t"), 1L)), ErrorCode.NotFound, "never created")
  }

  test("tasks queue in the order assigned, without duplicates") {
    val k = withQueue("a", "b")
    record(k, E.TasksAssigned(Vector("b", "c", "a"), 3L))
    assertEquals(k.currentState.queue, Vector("a", "b", "c"))
  }

  test("selecting a task moves it from the queue to current, and only a queued one") {
    val k = withQueue("a", "b")
    record(k, E.TaskSelected("b", 3L))
    assertEquals(k.currentState.queue, Vector("a"))
    assertEquals(k.currentState.current.map(_.taskId), Some("b"))
    assertRefused(record(k, E.TaskSelected("a", 4L)), ErrorCode.Conflict, "already working on 'b'")
    assertRefused(
      record(withQueue("a"), E.TaskSelected("z", 3L)),
      ErrorCode.Conflict,
      "'z' is not queued"
    )
  }

  test("queued work with nothing started reads as waiting") {
    val k = withQueue("a")
    assertEquals(k.currentState.phase, Phase.Waiting)
    record(k, E.TaskSelected("a", 3L))
    assertEquals(k.currentState.phase, Phase.Working)
  }

  test("iterations count, failures count and reset, usage accumulates per task and in total") {
    val k = working("a")
    record(k, E.IterationStarted(1, 5L))
    record(k, E.IterationFailed(1, "timeout", 6L))
    record(k, E.IterationFailed(1, "timeout", 7L))
    assertEquals(k.currentState.current.map(_.consecutiveFailures), Some(2))
    record(k, E.IterationCompleted(1, TokenUsage(100, 20), 8L))
    assertEquals(k.currentState.current.map(_.consecutiveFailures), Some(0))
    record(k, E.IterationStarted(2, 9L))
    record(k, E.IterationCompleted(2, TokenUsage(50, 10), 10L))
    assertEquals(k.currentState.current.map(_.iteration), Some(2))
    assertEquals(k.currentState.taskUsage, TokenUsage(150, 30))
    record(k, E.TaskEnded("a", TaskOutcome.Completed, 2, 11L))
    assertEquals(k.currentState.current, None)
    assertEquals(k.currentState.usage, TokenUsage(150, 30))
    record(k, E.TasksAssigned(Vector("b"), 12L))
    record(k, E.TaskSelected("b", 13L))
    assertEquals(k.currentState.taskUsage, TokenUsage.zero)
    assertEquals(k.currentState.usage, TokenUsage(150, 30))
  }

  test("iteration events need a task, and the right one") {
    val k = withQueue("a")
    assertRefused(record(k, E.IterationStarted(1, 3L)), ErrorCode.Conflict, "not working on a task")
    val w = working("a")
    assertRefused(
      record(w, E.TaskEnded("b", TaskOutcome.Completed, 1, 5L)),
      ErrorCode.Conflict,
      "not 'b'"
    )
  }

  test("a struggle is noted once, and again only after it is reset") {
    val k = working("a")
    assert(record(k, E.StruggleNoted(Struggle.ApproachingBudget, reset = false)).persisted)
    assert(!record(k, E.StruggleNoted(Struggle.ApproachingBudget, reset = false)).persisted)
    assert(record(k, E.StruggleNoted(Struggle.ApproachingBudget, reset = true)).persisted)
    assert(!record(k, E.StruggleNoted(Struggle.ApproachingBudget, reset = true)).persisted)
    assertEquals(k.currentState.current.map(_.warned), Some(Set.empty[Struggle]))
  }

  test(
    "suspend twice and resume without suspending are conflicts; a suspended instance keeps its task"
  ) {
    val k = working("a")
    record(k, E.Suspended(5L))
    assertEquals(k.currentState.phase, Phase.Suspended)
    assertEquals(k.currentState.current.map(_.taskId), Some("a"))
    assertRefused(record(k, E.Suspended(6L)), ErrorCode.Conflict, "already suspended")
    record(k, E.Resumed(7L))
    assertRefused(record(k, E.Resumed(8L)), ErrorCode.Conflict, "not suspended")
  }

  test("termination is permanent, empties the instance, and repeating it is harmless") {
    val k = working("a")
    record(k, E.TasksAssigned(Vector("b"), 5L))
    record(k, E.Terminated(6L))
    val s = k.currentState
    assertEquals(s.phase, Phase.Terminated)
    assertEquals((s.current, s.queue), (None, Vector.empty))
    assert(!record(k, E.Terminated(7L)).persisted)
    assertRefused(record(k, E.TasksAssigned(Vector("c"), 8L)), ErrorCode.Conflict, "terminated")
    assertRefused(record(k, E.Resumed(8L)), ErrorCode.Conflict, "terminated")
  }

  test("an instance that was never used can be terminated, which burns its id") {
    val k = kit()
    record(k, E.Terminated(1L))
    assertEquals(k.currentState.phase, Phase.Terminated)
    assertRefused(
      record(k, E.Created(terminateWhenDone = false, 2L)),
      ErrorCode.Conflict,
      "terminated"
    )
  }

  test("dequeuing takes a queued task out and leaves the order of the rest") {
    val k = withQueue("a", "b", "c")
    record(k, E.TaskDequeued("b", "cancelled by caller", 3L))
    assertEquals(k.currentState.queue, Vector("a", "c"))
    assertRefused(record(k, E.TaskDequeued("b", "again", 4L)), ErrorCode.Conflict, "not queued")
  }

  // ── Approval requests ────────────────────────────────────────────────────

  private def request(id: String, callId: String = "c-1") =
    ApprovalRequest(id, callId, "restart_service", Json.obj(), 5L)

  test("an approval request needs a task") {
    val k = withQueue("a")
    assertRefused(record(k, E.ApprovalRequested(request("r-1"))), ErrorCode.Conflict, "not working")
  }

  test("an approval request is recorded once per call, however often it is asked") {
    val k = working("a")
    record(k, E.ApprovalRequested(request("r-1")))
    assert(!record(k, E.ApprovalRequested(request("r-2"))).persisted, "the same call again")
    assertEquals(k.currentState.current.map(_.approvals.map(_.id)), Some(Vector("r-1")))
  }

  test("a decision is refused for an unknown id, a decided one, or one that names nobody") {
    val k = working("a")
    record(k, E.ApprovalRequested(request("r-1")))
    assertRefused(
      record(k, E.ApprovalDecided("r-404", Decision.approved("r-404", "dana"))),
      ErrorCode.NotFound,
      "r-404"
    )
    assertRefused(
      record(k, E.ApprovalDecided("r-1", Decision.approved("r-1", ""))),
      ErrorCode.BadRequest,
      "name who made it"
    )
    record(k, E.ApprovalDecided("r-1", Decision.approved("r-1", "dana")))
    assertRefused(
      record(k, E.ApprovalDecided("r-1", Decision.refused("r-1", "sam"))),
      ErrorCode.Conflict,
      "is decided"
    )
  }

  test("starting the next iteration, or ending the task, clears the approval requests") {
    val k = working("a")
    record(k, E.ApprovalRequested(request("r-1")))
    record(k, E.IterationStarted(2, 6L))
    assertEquals(k.currentState.current.map(_.approvals), Some(Vector.empty))
    record(k, E.ApprovalRequested(request("r-2", "c-2")))
    record(k, E.TaskEnded("a", TaskOutcome.Cancelled("cancelled by caller"), 2, 7L))
    assertEquals(k.currentState.current, None)
    assertRefused(
      record(k, E.ApprovalDecided("r-2", Decision.approved("r-2", "dana"))),
      ErrorCode.NotFound,
      "r-2"
    )
  }

  test("phase is a word on the wire") {
    val bytes = InstanceEntity.stateSerializer.toBytes(working("a").currentState)
    val text  = String(bytes, "UTF-8")
    assert(text.contains("\"warned\":[]"), text)
    assertEquals(InstanceEntity.stateSerializer.fromBytes(bytes), working("a").currentState)
  }
