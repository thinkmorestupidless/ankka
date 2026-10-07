package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{EntityId, Serializer}
import com.thinkmorestupidless.ankka.testkit.autonomous.Fixtures

/**
 * Pins the journal form of session memory: every event, the state, and the batch a loop writes.
 *
 * Every agent's conversation is in this journal, so a field renamed or given no default is a
 * session nobody can read after the next deploy. `session-memory.json` holds one line per value; a
 * change to any of them shows up as a diff to review, not as a replay failure in production.
 */
class SessionMemoryCompatibilitySuite extends munit.FunSuite with LogCapturing:

  private val usage = TokenUsage(120, 30, 5, 2)

  private val user = SessionMessage.UserMessage(1L, "What is the weather in Berlin?", "weather")
  private val ai = SessionMessage.AiMessage(
    2L,
    "Let me look.",
    "weather",
    Vector(
      RecordedToolCall("c-1", "get_weather", """{"location":"Berlin"}"""),
      RecordedToolCall("c-2", "current_date", "{}")
    )
  )
  private val result =
    SessionMessage.ToolResultMessage(3L, "c-1", "get_weather", "sunny", isError = false, "weather")
  private val failed =
    SessionMessage.ToolResultMessage(4L, "c-2", "current_date", "boom", isError = true, "weather")
  private val approvedResult = SessionMessage.ToolResultMessage(
    6L,
    "c-3",
    "issue_refund",
    "refunded",
    isError = false,
    "weather",
    decision = Some(
      Decision("a-1", approved = true, by = "dana", note = Some("ok"), at = 6L)
    )
  )
  private val summary = SessionMessage.SummaryMessage(5L, "They asked about Berlin.", "weather")

  private val events: Vector[SessionMemoryEvent] =
    import SessionMemoryEvent.*
    Vector(
      UserMessageAdded(user),
      AiMessageAdded(ai, usage),
      ToolResultAdded(result),
      ToolResultAdded(failed),
      HistoryCompacted(summary, 3),
      Cleared,
      JudgmentUsageAdded(TokenUsage(inputTokens = 296, outputTokens = 20)),
      ToolResultAdded(approvedResult),
      TurnSuspended(suspendedTurn),
      ApprovalDecided("a-2", Decision("a-2", approved = false, by = "sam", at = 8L)),
      TurnEnded
    )

  private lazy val suspendedTurn = SuspendedTurn(
    agentId = "weather",
    handler = "ask",
    streaming = false,
    payload = "cmVmdW5kIG9yZGVyIDc=",
    messages = Vector(user, ai, result),
    usage = usage,
    steps = 1,
    requests = Vector(
      ApprovalRequest(
        "a-2",
        "c-2",
        "current_date",
        Json.obj(),
        7L,
        expiresAt = Some(1807L)
      )
    ),
    judgmentUsage = TokenUsage(inputTokens = 12, outputTokens = 1)
  )

  private val state = SessionHistory(Vector(summary, user, ai, result), usage, 98)

  private val waitingState = state.copy(suspended = Some(suspendedTurn))

  private val append = SessionMemoryEntity.Append(Vector(user, ai, result), usage)

  private val endingAppend = SessionMemoryEntity.Append(Vector(result), usage, endsTurn = true)

  private val eventSerializer = SessionMemoryEntity.eventSerializer
  private val stateSerializer = SessionMemoryEntity.stateSerializer
  private val appendSerializer: Serializer[SessionMemoryEntity.Append] =
    import SessionMemoryEntity.given
    summon[Serializer[SessionMemoryEntity.Append]]

  private def line[A](label: String, value: A, s: Serializer[A]): String =
    s"$label ${String(s.toBytes(value), "UTF-8")}"

  private val path =
    Fixtures.repositoryRoot.resolve(
      "modules/testkit/src/test/resources/journal/session-memory.json"
    )

  test("every session memory event, the state and a batch decode from their pinned form and back") {
    val generated =
      (events.map(line("session-memory-event", _, eventSerializer)) ++
        Vector(
          line("session-history", state, stateSerializer),
          line("session-append", append, appendSerializer),
          line("session-history", waitingState, stateSerializer),
          line("session-append", endingAppend, appendSerializer)
        )).mkString("", "\n", "\n")

    events.foreach(e => assertEquals(eventSerializer.fromBytes(eventSerializer.toBytes(e)), e))
    Vector(state, waitingState).foreach(s =>
      assertEquals(stateSerializer.fromBytes(stateSerializer.toBytes(s)), s)
    )
    Vector(append, endingAppend).foreach(a =>
      assertEquals(appendSerializer.fromBytes(appendSerializer.toBytes(a)), a)
    )

    val problems = Fixtures.check(Map(path -> generated))
    assert(problems.isEmpty, problems.mkString("\n"))
  }

  test("each pinned line decodes, as a journal written by an earlier release must") {
    val lines = java.nio.file.Files.readAllLines(path).toArray.toVector.map(_.toString)
    lines.foreach { l =>
      val (label, json) = l.splitAt(l.indexOf(' '))
      val bytes         = json.trim.getBytes("UTF-8")
      label match
        case "session-memory-event" => eventSerializer.fromBytes(bytes): Unit
        case "session-history"      => stateSerializer.fromBytes(bytes): Unit
        case "session-append"       => appendSerializer.fromBytes(bytes): Unit
        case other                  => fail(s"unknown label '$other'")
    }
  }

  test("every event case is pinned") {
    assertEquals(
      events.map(_.ordinal).distinct.size,
      SessionMemoryEvent.TurnEnded.ordinal + 1
    )
  }

  // ── Approval requests ────────────────────────────────────────────────────

  // A session and a tool result as the journal held them before a turn could wait for approval,
  // copied from this suite's pinned file at that time.
  private val historyBeforeApprovals =
    """{"messages":[{"type":"SummaryMessage","timestamp":5,"text":"They asked about Berlin.","agentId":"weather"},{"type":"UserMessage","timestamp":1,"text":"What is the weather in Berlin?","agentId":"weather"},{"type":"AiMessage","timestamp":2,"text":"Let me look.","agentId":"weather","toolCalls":[{"id":"c-1","name":"get_weather","arguments":"{\"location\":\"Berlin\"}"},{"id":"c-2","name":"current_date","arguments":"{}"}]},{"type":"ToolResultMessage","timestamp":3,"callId":"c-1","toolName":"get_weather","content":"sunny","isError":false,"agentId":"weather"}],"usage":{"inputTokens":120,"outputTokens":30,"cacheReadTokens":5,"cacheWriteTokens":2},"sizeInBytes":98}"""

  test("a session with no approval request is read as it was recorded") {
    val read = stateSerializer.fromBytes(historyBeforeApprovals.getBytes("UTF-8"))
    assertEquals(read, state)
    assertEquals(read.suspended, None)
    assertEquals(read.awaiting, Vector.empty)
    val encoded = String(stateSerializer.toBytes(state), "UTF-8")
    assertEquals(encoded, historyBeforeApprovals)
    assert(!encoded.contains("suspended"), encoded)
    val batch = String(appendSerializer.toBytes(append), "UTF-8")
    assert(!batch.contains("endsTurn"), batch)
  }

  test(
    "a tool result with no decision is stored exactly as before, and one with a decision keeps it"
  ) {
    val plain = String(eventSerializer.toBytes(SessionMemoryEvent.ToolResultAdded(result)), "UTF-8")
    assert(!plain.contains("decision"), plain)
    val decided =
      String(eventSerializer.toBytes(SessionMemoryEvent.ToolResultAdded(approvedResult)), "UTF-8")
    assert(decided.contains("\"by\":\"dana\""), decided)
    assert(decided.contains("\"approvalId\":\"a-1\""), decided)
  }

  // ── Judgment tokens ──────────────────────────────────────────────────────

  private def kit() = EventSourcedTestKit.of(SessionMemoryEntity, EntityId("s-1"))

  private val judged = TokenUsage(inputTokens = 100, outputTokens = 20)

  test("a session in which no judgment was made is stored exactly as before") {
    val encoded = String(stateSerializer.toBytes(state), "UTF-8")
    assert(!encoded.contains("judgmentUsage"), encoded)
    val withJudgments = String(stateSerializer.toBytes(state.copy(judgmentUsage = judged)), "UTF-8")
    assert(
      withJudgments.contains("\"judgmentUsage\":{\"inputTokens\":100,\"outputTokens\":20}"),
      withJudgments
    )
  }

  test("a turn's judgment tokens are persisted after its messages, as their own event") {
    val k = kit()
    val result = k.call(SessionMemoryEntity.append)(
      SessionMemoryEntity.Append(Vector(user, ai), usage, judged)
    )
    assertEquals(
      result.events,
      Vector(
        SessionMemoryEvent.UserMessageAdded(user),
        SessionMemoryEvent.AiMessageAdded(ai, usage),
        SessionMemoryEvent.JudgmentUsageAdded(judged)
      )
    )
    assertEquals(k.currentState.judgmentUsage, judged)
    assertEquals(k.currentState.usage, usage)
  }

  test("judgment tokens with no messages are persisted alone, and change nothing else") {
    val k = kit()
    k.call(SessionMemoryEntity.append)(SessionMemoryEntity.Append(Vector(user), usage)): Unit
    val before = k.currentState
    val result =
      k.call(SessionMemoryEntity.append)(
        SessionMemoryEntity.Append(Vector.empty, TokenUsage.zero, judged)
      )
    assertEquals(result.events, Vector(SessionMemoryEvent.JudgmentUsageAdded(judged)))
    assertEquals(k.currentState, before.copy(judgmentUsage = judged))
  }

  test("an append with nothing in it persists nothing") {
    val result =
      kit().call(SessionMemoryEntity.append)(
        SessionMemoryEntity.Append(Vector.empty, TokenUsage.zero)
      )
    assert(!result.persisted)
  }

  test("clearing a session clears its judgment tokens too") {
    val k = kit()
    k.call(SessionMemoryEntity.append)(
      SessionMemoryEntity.Append(Vector.empty, TokenUsage.zero, judged)
    ): Unit
    k.call(SessionMemoryEntity.clear): Unit
    assertEquals(k.currentState.judgmentUsage, TokenUsage.zero)
  }
