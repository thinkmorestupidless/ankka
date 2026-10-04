package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.agent.judgment.Judgments
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.agent.autonomous.IterationLoop.ResumePoint
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}

/**
 * Where an instance that stopped mid-task picks up, read from its record and the last message in
 * the task's session — every case of the rule, over real session memory and no runtime.
 */
class ResumePointSuite extends munit.FunSuite with LogCapturing:

  private val router = EntityRouter()
  private val loop = IterationLoop(
    Answerer.definition,
    Answerer.create(
      SimpleAutonomousAgentContext(
        ComponentId("answerer"),
        "i-1",
        router.client,
        None,
        com.thinkmorestupidless.ankka.testkit.InMemorySecretStore(),
        com.thinkmorestupidless.ankka.testkit.ScriptedServices()
      )
    ),
    "i-1",
    router.client,
    TestModelProvider(),
    scala.concurrent.duration.DurationInt(5).seconds,
    Judgments.none,
    _ => ()
  )

  private def session(taskId: String, messages: SessionMessage*): Unit =
    router.client
      .forEventSourcedEntity(EntityId(s"task:$taskId"))
      .call(SessionMemoryEntity.append)
      .invoke(SessionMemoryEntity.Append(messages.toVector, TokenUsage.zero)): Unit

  private def ai(at: Long, calls: String*) =
    SessionMessage.AiMessage(
      at,
      "",
      "answerer",
      calls.toVector.map(c => RecordedToolCall(c, c, "{}"))
    )

  // A result answers the call it follows: the rule that settles a response reads results by call id.
  private def result(at: Long, callId: String = "c1") =
    SessionMessage.ToolResultMessage(at, callId, "lookup", "ok", false, "answerer")

  private def working(taskId: String, iteration: Int, completed: Int, startedAt: Long) =
    Working(taskId, iteration, completed, startedAt, started = true, 0, Set.empty)

  test("nothing started: the first iteration") {
    assertEquals(loop.resumePoint(working("t0", 0, 0, 0L)), ResumePoint.NextIteration(1))
  }

  test(
    "started, and the response landed after it started: record the completion, do not call again"
  ) {
    session("t1", ai(100L, "c1"), result(101L), ai(300L, "c2"))
    assertEquals(loop.resumePoint(working("t1", 2, 1, 200L)), ResumePoint.RecordCompletion(2))
  }

  test("started, and the last response is the previous iteration's: call the model again for it") {
    session("t2", ai(100L))
    assertEquals(loop.resumePoint(working("t2", 2, 1, 200L)), ResumePoint.CallModel(2))
  }

  test("started, and the session ends with tool results: the model call never landed") {
    session("t3", ai(100L, "c1"), result(101L))
    assertEquals(loop.resumePoint(working("t3", 2, 1, 200L)), ResumePoint.CallModel(2))
  }

  test("completed, and the response's tools did not all land: run them again") {
    val response = ai(300L, "c2")
    session("t4", ai(100L, "c1"), result(101L), response)
    assertEquals(loop.resumePoint(working("t4", 2, 2, 200L)), ResumePoint.RunTools(2, response))
  }

  test("completed, and the tools' results landed: the next iteration") {
    session("t5", ai(100L, "c1"), result(101L))
    assertEquals(loop.resumePoint(working("t5", 1, 1, 50L)), ResumePoint.NextIteration(2))
  }

  test("completed with a reply that asked for no tool: the next iteration") {
    session("t6", ai(100L))
    assertEquals(loop.resumePoint(working("t6", 1, 1, 50L)), ResumePoint.NextIteration(2))
  }

  test("completed, and one of the response's calls has a result and another does not: settle") {
    val response = ai(300L, "c2", "c3")
    session("t7", response, result(301L, "c2"))
    assertEquals(loop.resumePoint(working("t7", 2, 2, 200L)), ResumePoint.RunTools(2, response))
  }

  // ── Settling a response with calls that require approval ─────────────────

  private val operator = ComponentId("operator")
  private val opLoop = IterationLoop(
    Operator.definition,
    Operator.create(
      SimpleAutonomousAgentContext(
        operator,
        "i-1",
        router.client,
        None,
        com.thinkmorestupidless.ankka.testkit.InMemorySecretStore()
      )
    ),
    "i-1",
    router.client,
    TestModelProvider(),
    scala.concurrent.duration.DurationInt(5).seconds,
    Judgments.none,
    _ => ()
  )

  private def instance = router.client.forEventSourcedEntity(InstanceEntity.idFor(operator, "i-1"))

  private def record(event: InstanceEvent): InstanceRecord =
    instance.call(InstanceEntity.record).invoke(event)

  /** An instance working on `taskId` whose first iteration has completed with `response`. */
  private def completedWith(taskId: String, response: SessionMessage.AiMessage): Unit =
    import InstanceEvent.*
    val existing = instance.call(InstanceEntity.get).invoke()
    if !existing.created then record(Created(terminateWhenDone = false, 1L)): Unit
    existing.current.foreach(w => record(TaskEnded(w.taskId, TaskOutcome.Completed, 1, 2L)))
    record(TasksAssigned(Vector(taskId), 2L)): Unit
    record(TaskSelected(taskId, 3L)): Unit
    record(TaskStarted(taskId, 4L)): Unit
    record(IterationStarted(1, 5L)): Unit
    session(taskId, response)
    record(IterationCompleted(1, TokenUsage.zero, 7L)): Unit

  private def opCall(id: String, tool: String, argument: String) =
    RecordedToolCall(id, tool, s"""{"service":"$argument","node":"$argument"}""")

  private def responseOf(calls: RecordedToolCall*) =
    SessionMessage.AiMessage(6L, "", "operator", calls.toVector)

  private def settle(taskId: String, response: SessionMessage.AiMessage) =
    opLoop.run(
      ResumePoint.RunTools(1, response),
      TaskRecord.empty.copy(id = taskId),
      Tasks.summary,
      4,
      Vector.empty
    )

  private def resultsOf(taskId: String): Vector[SessionMessage.ToolResultMessage] =
    router.client
      .forEventSourcedEntity(EntityId(s"task:$taskId"))
      .call(SessionMemoryEntity.history)
      .invoke()
      .messages
      .collect { case r: SessionMessage.ToolResultMessage => r }

  private def awaitingOf: Vector[ApprovalRequest] =
    instance.call(InstanceEntity.get).invoke().current.toVector.flatMap(_.awaiting)

  private def decide(decision: String => Decision): Unit =
    val request = awaitingOf.head
    record(InstanceEvent.ApprovalDecided(request.id, decision(request.id))): Unit

  test("a call with an approval request awaiting a decision waits, and runs nothing") {
    Operator.runs.clear()
    val response =
      responseOf(opCall("r1", "restart_service", "cart"), opCall("m1", "read_metrics", "cart"))
    completedWith("s1", response)

    assertEquals(settle("s1", response), IterationLoop.IterationResult.Waiting)

    assertEquals(awaitingOf.map(_.callId), Vector("r1"))
    assertEquals(Operator.runsOf("restart_service"), Vector.empty)
    assertEquals(Operator.runsOf("read_metrics"), Vector("read_metrics(cart)"))
    assertEquals(resultsOf("s1").map(_.callId), Vector("m1"))
    // Settled again, as after a stop: the request is not asked twice, and nothing runs twice.
    assertEquals(settle("s1", response), IterationLoop.IterationResult.Waiting)
    assertEquals(awaitingOf.size, 1)
    assertEquals(Operator.runsOf("read_metrics").size, 1)
  }

  test("a decided-approved call is run and its result carries the decision") {
    Operator.runs.clear()
    val response = responseOf(opCall("r1", "restart_service", "cart"))
    completedWith("s2", response)
    settle("s2", response): Unit
    decide(Decision.approved(_, "dana"))

    assertEquals(settle("s2", response), IterationLoop.IterationResult.Continue)

    assertEquals(Operator.runsOf("restart_service"), Vector("restart_service(cart)"))
    assertEquals(resultsOf("s2").flatMap(_.decision).map(_.by), Vector("dana"))
    assertEquals(opLoop.resumePoint(working("s2", 1, 1, 5L)), ResumePoint.NextIteration(2))
  }

  test("a decided-refused call is answered with the refusal and is not run") {
    Operator.runs.clear()
    val response = responseOf(opCall("r1", "restart_service", "cart"))
    completedWith("s3", response)
    settle("s3", response): Unit
    decide(Decision.refused(_, "dana", "not now"))

    assertEquals(settle("s3", response), IterationLoop.IterationResult.Continue)

    assertEquals(Operator.runsOf("restart_service"), Vector.empty)
    val refused = resultsOf("s3").head
    assert(refused.isError)
    assert(refused.content.contains("not now"), refused.content)
  }

  test("a tool approved before the service stopped runs again when its result was not recorded") {
    Operator.runs.clear()
    val response = responseOf(opCall("r1", "restart_service", "cart"))
    completedWith("s4", response)
    settle("s4", response): Unit
    decide(Decision.approved(_, "dana"))

    // The decision is recorded and its result is not: where the instance picks up, it settles.
    assertEquals(opLoop.resumePoint(working("s4", 1, 1, 5L)), ResumePoint.RunTools(1, response))
    settle("s4", response): Unit
    assertEquals(Operator.runsOf("restart_service"), Vector("restart_service(cart)"))
  }
