package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.TokenUsage
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.core.Serializer
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.testkit.autonomous.Fixtures

/** Pins the journal form of every run event and of the record a reader gets. */
class RunCompatibilitySuite extends munit.FunSuite with LogCapturing:

  private val usage = TokenUsage(120, 30, 5, 0)

  private val events: Vector[RunEvent] =
    import RunEvent.*
    Vector(
      Started("digest", 2, """{"from":"a","to":"b"}""", "caller", Some(1000L), 1L),
      StepStarted("papers", 2L),
      ItemEnded(
        "findings",
        ItemRecord(0, Some("""{"finding":"x"}"""), None, "run:r-1:findings:reader:0", usage)
      ),
      RoundEnded(
        "script",
        RoundRecord(
          1,
          """{"text":"draft"}""",
          passed = false,
          Vector("too long"),
          Vector("run:r-1:script:writer"),
          usage
        )
      ),
      WaitingForDecision("send", ApprovalRef("run:r-1:send:clerk", "a-1", "send_letter"), 3L),
      DecisionReceived("send", "a-1", 4L),
      ApprovalsRefused("send", Vector("a-2"), 5L),
      StepEnded(
        "papers",
        """["p1"]""",
        Vector("run:r-1:papers:writer"),
        usage,
        TokenUsage.zero,
        2,
        6L
      ),
      CancelRequested("caller", 7L),
      Ended(RunStatus.Cancelled, Some("cancelled by caller"), 8L)
    )

  private def lines[A](label: String, values: Vector[A], s: Serializer[A]): Vector[String] =
    values.map(v => s"$label ${String(s.toBytes(v), "UTF-8")}")

  private val path =
    Fixtures.repositoryRoot.resolve("modules/testkit/src/test/resources/journal/run-events.json")

  test("every run event decodes from its pinned form and back") {
    val generated = lines("run-event", events, RunEntity.eventSerializer).mkString("", "\n", "\n")
    events.foreach(e =>
      assertEquals(RunEntity.eventSerializer.fromBytes(RunEntity.eventSerializer.toBytes(e)), e)
    )
    val problems = Fixtures.check(Map(path -> generated))
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("every event case is pinned, and the status words are the wire's") {
    assertEquals(events.map(_.ordinal).distinct.sorted, events.indices.toVector)
    assertEquals(RunEvent.Ended(RunStatus.Running, None, 0L).ordinal, events.map(_.ordinal).max)
    assertEquals(
      RunStatus.values.map(_.wire).toVector,
      Vector("running", "waiting-for-decision", "completed", "failed", "cancelled")
    )
  }

  test("a record round-trips") {
    val record = RunRecord(
      "r-1",
      "digest",
      2,
      "{}",
      RunStatus.WaitingForDecision,
      Vector(StepRecord("send", waiting = Vector(ApprovalRef("s", "a-1", "t")))),
      "caller",
      1L,
      None,
      None,
      Some(9L),
      None
    )
    assertEquals(
      RunEntity.stateSerializer.fromBytes(RunEntity.stateSerializer.toBytes(record)),
      record
    )
  }
