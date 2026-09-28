package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.agent.TokenUsage
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.agent.autonomous.TaskEntity.*
import com.thinkmorestupidless.ankka.core.{Done, ErrorCode}
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

/** Every transition of a task's lifecycle, and every refusal, with no runtime. */
class TaskEntitySuite extends munit.FunSuite:

  private val worker = Assignee("answerer", "i-1")
  private val other  = Assignee("answerer", "i-2")
  private val usage  = TokenUsage(10, 5)

  private def kit(id: String) = EventSourcedTestKit.of(TaskEntity, id)

  private def created(id: String = "t-1") =
    val k = kit(id)
    k.call(TaskEntity.createTask)(Create("answer", "How many?", dependencies = Vector("t-0")))
    k

  private def inProgress() =
    val k = created()
    k.call(TaskEntity.assign)(worker)
    k.call(TaskEntity.start)
    k

  private def assertRefused(
      result: com.thinkmorestupidless.ankka.testkit.CommandResult[?, ?, ?],
      code: ErrorCode
  ) =
    assert(result.isError, s"expected a refusal, got ${result.reply}")
    assertEquals(result.error.code, code, result.errorMessage)
    assert(!result.persisted)

  test("create records the task as pending with its instructions and dependencies") {
    val k = created()
    val s = k.currentState
    assertEquals(s.id, "t-1")
    assertEquals(s.typeName, "answer")
    assertEquals(s.status, TaskStatus.Pending)
    assertEquals(s.dependencies, Vector("t-0"))
    assert(s.createdAt > 0L)
    assertEquals(k.call(TaskEntity.get).replyValue, s)
  }

  test("create twice is a conflict; depending on itself is refused") {
    val k = created()
    assertRefused(k.call(TaskEntity.createTask)(Create("answer", "again")), ErrorCode.Conflict)
    assertRefused(
      kit("t-2").call(TaskEntity.createTask)(Create("answer", "x", dependencies = Vector("t-2"))),
      ErrorCode.BadRequest
    )
  }

  test("every command on a task that was never created is not found") {
    val k = kit("ghost")
    assertRefused(k.call(TaskEntity.get), ErrorCode.NotFound)
    assertRefused(k.call(TaskEntity.assign)(worker), ErrorCode.NotFound)
    assertRefused(k.call(TaskEntity.cancel)(Cancel("x")), ErrorCode.NotFound)
    assertRefused(k.call(TaskEntity.addDependent)(AddDependent("t-9")), ErrorCode.NotFound)
  }

  test("pending → assigned → in progress → completed, with the times of each") {
    val k = created()
    k.call(TaskEntity.assign)(worker)
    assertEquals(k.currentState.status, TaskStatus.Assigned)
    assertEquals(k.currentState.assignee, Some(worker))
    assert(k.currentState.assignedAt.isDefined)
    k.call(TaskEntity.start)
    assertEquals(k.currentState.status, TaskStatus.InProgress)
    assert(k.currentState.startedAt.isDefined)
    k.call(TaskEntity.complete)(Complete("""{"answer":"3"}""", 2, usage))
    val s = k.currentState
    assertEquals(s.status, TaskStatus.Completed)
    assertEquals(s.result, Some("""{"answer":"3"}"""))
    assertEquals(s.iterations, 2)
    assertEquals(s.usage, usage)
    assert(s.endedAt.isDefined)
  }

  test("assigning again is harmless for the same instance and a conflict for another") {
    val k = created()
    k.call(TaskEntity.assign)(worker)
    val again = k.call(TaskEntity.assign)(worker)
    assertEquals(again.replyValue, Done)
    assert(!again.persisted)
    assertRefused(k.call(TaskEntity.assign)(other), ErrorCode.Conflict)
  }

  test("start is harmless on a task already in progress, and refused on a pending one") {
    val k     = inProgress()
    val again = k.call(TaskEntity.start)
    assertEquals(again.replyValue, Done)
    assert(!again.persisted)
    assertRefused(created("t-3").call(TaskEntity.start), ErrorCode.Conflict)
  }

  test("a rejected result returns to in progress when the next iteration starts") {
    val k = inProgress()
    k.call(TaskEntity.rejectResult)(RejectResult("sources must not be empty", 1, usage))
    assertEquals(k.currentState.status, TaskStatus.ResultRejected)
    assertEquals(k.currentState.reason, Some("sources must not be empty"))
    k.call(TaskEntity.start)
    assertEquals(k.currentState.status, TaskStatus.InProgress)
    k.call(TaskEntity.complete)(Complete("{}", 2, usage))
    assertEquals(k.currentState.status, TaskStatus.Completed)
    assertEquals(k.currentState.reason, None)
  }

  test("a result-rejected task can also be completed directly") {
    val k = inProgress()
    k.call(TaskEntity.rejectResult)(RejectResult("no", 1, usage))
    assertEquals(k.call(TaskEntity.complete)(Complete("{}", 2, usage)).replyValue, Done)
  }

  test("an assigned task may fail before its first iteration") {
    val k = created()
    k.call(TaskEntity.assign)(worker)
    k.call(TaskEntity.fail)(Fail("guardrail 'no-secrets'", 0, TokenUsage.zero))
    assertEquals(k.currentState.status, TaskStatus.Failed)
    assertEquals(k.currentState.reason, Some("guardrail 'no-secrets'"))
  }

  test("unassigning returns a task to pending with no assignee and says why") {
    val k = inProgress()
    k.call(TaskEntity.unassign)(Unassign("assignee terminated"))
    val s = k.currentState
    assertEquals(s.status, TaskStatus.Pending)
    assertEquals(s.assignee, None)
    assertEquals(s.reason, Some("assignee terminated"))
    k.call(TaskEntity.assign)(other)
    assertEquals(k.currentState.assignee, Some(other))
  }

  test("any unfinished task can be cancelled; a finished one cannot") {
    for setup <- Seq(
        () => created(),
        () => { val k = created(); k.call(TaskEntity.assign)(worker); k },
        () => inProgress()
      )
    do
      val k = setup()
      k.call(TaskEntity.cancel)(Cancel("cancelled by caller"))
      assertEquals(k.currentState.status, TaskStatus.Cancelled)
      assertEquals(k.currentState.reason, Some("cancelled by caller"))
      assert(k.currentState.endedAt.isDefined)
  }

  test("a terminal task refuses every change") {
    val k = inProgress()
    k.call(TaskEntity.complete)(Complete("{}", 1, usage))
    assertRefused(k.call(TaskEntity.cancel)(Cancel("x")), ErrorCode.Conflict)
    assertRefused(k.call(TaskEntity.fail)(Fail("x", 1, usage)), ErrorCode.Conflict)
    assertRefused(k.call(TaskEntity.complete)(Complete("{}", 1, usage)), ErrorCode.Conflict)
    assertRefused(k.call(TaskEntity.assign)(other), ErrorCode.Conflict)
    assertRefused(k.call(TaskEntity.unassign)(Unassign("x")), ErrorCode.Conflict)
    assertRefused(k.call(TaskEntity.start), ErrorCode.Conflict)
  }

  test("refusals name the task and the state it is in") {
    val k      = created()
    val result = k.call(TaskEntity.complete)(Complete("{}", 1, usage))
    assert(result.errorMessage.contains("task 't-1'"), result.errorMessage)
    assert(result.errorMessage.contains("pending"), result.errorMessage)
  }

  test("a dependent is indexed once, and a finished task answers with how it ended") {
    val k = created()
    k.call(TaskEntity.addDependent)(AddDependent("t-2"))
    k.call(TaskEntity.addDependent)(AddDependent("t-2"))
    assertEquals(k.currentState.dependents, Vector("t-2"))
    assertEquals(
      k.call(TaskEntity.addDependent)(AddDependent("t-3")).replyValue,
      DependentAdded(None, None)
    )

    val failed = inProgress()
    failed.call(TaskEntity.fail)(Fail("budget of 3 exhausted", 3, usage))
    val answer = failed.call(TaskEntity.addDependent)(AddDependent("t-9"))
    assertEquals(
      answer.replyValue,
      DependentAdded(Some(TaskStatus.Failed), Some("budget of 3 exhausted"))
    )
    assert(!answer.persisted)
  }

  test("the status travels as a word") {
    assertEquals(TaskStatus.InProgress.wire, "in-progress")
    assertEquals(TaskStatus.ResultRejected.wire, "result-rejected")
    val bytes = TaskEntity.stateSerializer.toBytes(created().currentState)
    assert(String(bytes, "UTF-8").contains("\"status\":\"pending\""), String(bytes, "UTF-8"))
    assertEquals(TaskEntity.stateSerializer.fromBytes(bytes).status, TaskStatus.Pending)
  }
