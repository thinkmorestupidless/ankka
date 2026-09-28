package com.thinkmorestupidless.ankka.testkit.autonomous

import com.thinkmorestupidless.ankka.agent.TokenUsage
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.Serializer

/**
 * Pins the journal form of every task and instance event, and of the state a client reads.
 *
 * `autonomous-events.json` holds one line per case. A field renamed, reordered or given no default
 * changes a line, and the change is then a decision rather than an accident: a journal written by
 * one release has to replay under the next.
 */
class EventCompatibilitySuite extends munit.FunSuite:

  private val usage    = TokenUsage(120, 30, 5, 0)
  private val assignee = Assignee("answerer", "i-1")
  private val attached =
    Vector(
      Attachment("brief", "text/plain", AttachmentContent.Inline("Be brief.")),
      Attachment(
        "catalogue",
        "application/json",
        AttachmentContent.Reference("https://example.test/c")
      )
    )

  private val taskEvents: Vector[TaskEvent] =
    import TaskEvent.*
    Vector(
      Created("t-1", "answer", "How many?", attached, Vector("t-0"), 1L),
      DependentAdded("t-2"),
      Assigned(assignee, 2L),
      Unassigned("assignee terminated", 3L),
      Started(4L),
      ResultRejected("sources must not be empty", 2, usage, 5L),
      Completed("""{"answer":"3"}""", 3, usage, 6L),
      Failed("iteration budget of 5 exhausted", 5, usage, 7L),
      Cancelled("cancelled by caller", 8L)
    )

  private val instanceEvents: Vector[InstanceEvent] =
    import InstanceEvent.*
    Vector(
      Created(terminateWhenDone = true, 1L),
      TasksAssigned(Vector("t-1", "t-2"), 2L),
      TaskDequeued("t-2", "cancelled by caller", 3L),
      TaskSelected("t-1", 4L),
      TaskStarted("t-1", 6L),
      IterationStarted(1, 7L),
      IterationFailed(1, "the model did not respond within 2 minutes", 8L),
      IterationCompleted(1, usage, 9L),
      StruggleNoted(Struggle.ApproachingBudget, reset = false),
      StruggleNoted(Struggle.ApproachingBudget, reset = true),
      TaskEnded("t-1", TaskOutcome.Completed, 1, 10L),
      TaskEnded("t-1", TaskOutcome.Failed("budget"), 5, 10L),
      TaskEnded("t-1", TaskOutcome.Cancelled("cancelled by caller"), 2, 10L),
      Suspended(11L),
      Resumed(12L),
      Terminated(13L)
    )

  private def lines[A](label: String, values: Vector[A], s: Serializer[A]): Vector[String] =
    values.map(v => s"$label ${String(s.toBytes(v), "UTF-8")}")

  private val path =
    Fixtures.repositoryRoot.resolve(
      "modules/testkit/src/test/resources/journal/autonomous-events.json"
    )

  test("every task event, instance event and record decodes from its pinned form and back") {
    val generated =
      (lines("task-event", taskEvents, TaskEntity.eventSerializer) ++
        lines("agent-instance-event", instanceEvents, InstanceEntity.eventSerializer))
        .mkString("", "\n", "\n")

    // Each pinned line must decode to the value that produced it: the journal's direction.
    taskEvents.foreach(e =>
      assertEquals(TaskEntity.eventSerializer.fromBytes(TaskEntity.eventSerializer.toBytes(e)), e)
    )
    instanceEvents.foreach(e =>
      assertEquals(
        InstanceEntity.eventSerializer.fromBytes(InstanceEntity.eventSerializer.toBytes(e)),
        e
      )
    )
    val problems = Fixtures.check(Map(path -> generated))
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("every event case is pinned") {
    assertEquals(
      taskEvents.map(_.ordinal).distinct.sorted,
      taskEvents.map(_.ordinal).distinct.sorted.indices.toVector
    )
    assertEquals(TaskEvent.Cancelled("", 0L).ordinal, taskEvents.map(_.ordinal).max)
    assertEquals(InstanceEvent.Terminated(0L).ordinal, instanceEvents.map(_.ordinal).max)
    assertEquals(
      instanceEvents.map(_.ordinal).distinct.size,
      InstanceEvent.Terminated(0L).ordinal + 1
    )
  }
