package com.thinkmorestupidless.ankka.agent.autonomous

import com.thinkmorestupidless.ankka.agent.{Json, TokenUsage}

/** Every notification crosses the wire as JSON with a `type`, and comes back as itself. */
class NotificationCodecSuite extends munit.FunSuite:

  import Notification.*

  private val (c, i, t) = ("answerer", "i-1", "t-1")

  private val all: Vector[Notification] = Vector(
    Activated(c, i, 1L),
    Deactivated(c, i, 1L),
    Suspended(c, i, 1L),
    Resumed(c, i, 1L),
    Terminated(c, i, 1L),
    IterationStarted(c, i, t, 2, 3, 1L),
    IterationCompleted(c, i, t, 2, TokenUsage(10, 5), 1L),
    IterationFailed(c, i, t, 2, "timeout", 1L),
    TaskAssigned(c, i, t, 1L),
    TaskStarted(c, i, t, 1L),
    TaskCompleted(c, i, t, 2, TokenUsage(10, 5), 1L),
    TaskFailed(c, i, t, "budget of 5 exhausted", 5, 1L),
    TaskCancelled(c, i, t, "cancelled by caller", 1L),
    TaskResultRejected(c, i, t, "sources must not be empty", 2, 1L),
    TaskDependencyWait(c, i, t, "t-0", 1L),
    DependencyResolved(c, i, t, "t-0", 1L),
    TaskApproachingMaxIterations(c, i, t, 4, 5, 1L),
    RepeatedIterationFailure(c, i, t, 3, 1L),
    TaskDependencyStuck(c, i, t, "t-0", 300000L, 1L),
    ApprovalRequested(c, i, t, "a-1", "restart_service", """{"service":"cart"}""", Some(9L), 1L),
    ApprovalDecided(c, i, t, "a-1", approved = false, "dana", Some("not now"), expired = false, 1L),
    Dropped(c, i, 976, 1L)
  )

  test("the list above covers every case, so a new one cannot be added untested") {
    // Ordinals follow declaration order and `Dropped` is declared last, so a case added anywhere
    // either leaves a gap here or moves `Dropped`.
    assertEquals(all.map(_.ordinal), all.indices.toVector)
    assertEquals(Dropped(c, i, 0, 0L).ordinal, all.size - 1)
  }

  test("every notification round-trips and is discriminated by type") {
    all.foreach { n =>
      val bytes = serializer.toBytes(n)
      val json  = Json.parse(String(bytes, "UTF-8")).fold(fail(_), identity)
      assertEquals(json("type").flatMap(_.asString), Some(n.productPrefix))
      assertEquals(json("instanceId").flatMap(_.asString), Some(i))
      assertEquals(serializer.fromBytes(bytes), n)
    }
  }

  test("an instance's state carries its phase as a word") {
    val state = AgentState(
      c,
      i,
      Phase.Working,
      suspended = false,
      terminated = false,
      Some(AgentState.Current(t, 2, Some(5))),
      Vector("t-2"),
      TokenUsage(10, 5),
      TokenUsage(10, 5)
    )
    val text = String(AgentState.serializer.toBytes(state), "UTF-8")
    assert(text.contains("\"phase\":\"working\""), text)
    assertEquals(AgentState.serializer.fromBytes(text.getBytes("UTF-8")), state)
  }
