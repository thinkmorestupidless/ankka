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
      SimpleAutonomousAgentContext(ComponentId("answerer"), "i-1", router.client, None)
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

  private def result(at: Long) =
    SessionMessage.ToolResultMessage(at, "c", "lookup", "ok", false, "answerer")

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
