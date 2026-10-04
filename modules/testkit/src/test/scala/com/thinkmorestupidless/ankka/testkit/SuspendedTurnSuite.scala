package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{EntityId, ErrorCode}

/**
 * A session's suspended turn, one command at a time, with no runtime: what each records, and what
 * each refuses.
 */
class SuspendedTurnSuite extends munit.FunSuite with LogCapturing:

  private def kit() = EventSourcedTestKit.of(SessionMemoryEntity, EntityId("s-1"))

  private def request(id: String) =
    ApprovalRequest(id, s"call-$id", "issue_refund", Json.obj("amount" -> Json.num(40)), 1L)

  private val user = SessionMessage.UserMessage(1L, "refund order 7", "support")
  private val ai = SessionMessage.AiMessage(
    2L,
    "",
    "support",
    Vector(RecordedToolCall("call-a-1", "issue_refund", """{"amount":40}"""))
  )

  private def turn(requests: ApprovalRequest*) = SuspendedTurn(
    agentId = "support-agent",
    handler = "ask",
    streaming = false,
    payload = SuspendedTurn.encodePayload("refund order 7".getBytes("UTF-8")),
    messages = Vector(user, ai),
    usage = TokenUsage(10, 2),
    steps = 1,
    requests = requests.toVector
  )

  test("suspend-turn records the turn apart from the history") {
    val k = kit()
    k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-1"))): Unit
    assertEquals(k.currentState.suspended.map(_.handler), Some("ask"))
    assertEquals(k.currentState.messages, Vector.empty)
    assertEquals(k.currentState.awaiting.map(_.id), Vector("a-1"))
  }

  test("a second turn is refused while a request is awaiting a decision") {
    val k = kit()
    k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-1"))): Unit
    val refused = k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-2")))
    assertEquals(refused.error.code, ErrorCode.Conflict)
    assert(!refused.persisted)
  }

  test("a turn whose requests are all decided is replaced when it waits again") {
    val k = kit()
    k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-1"))): Unit
    k.call(SessionMemoryEntity.decideApproval)(Decision.approved("a-1", "dana")): Unit
    val again = k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-2")))
    assert(again.persisted)
    assertEquals(k.currentState.awaiting.map(_.id), Vector("a-2"))
  }

  test("decide-approval records the decision and answers with the turn as it then stands") {
    val k = kit()
    k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-1"), request("a-2"))): Unit
    val decided = k.call(SessionMemoryEntity.decideApproval)(Decision.approved("a-1", "dana"))
    assertEquals(decided.replyValue.awaiting.map(_.id), Vector("a-2"))
    assertEquals(
      decided.replyValue.requests.find(_.id == "a-1").flatMap(_.decision).map(_.by),
      Some("dana")
    )
  }

  test("a decision that names nobody is refused and decides nothing") {
    val k = kit()
    k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-1"))): Unit
    val refused = k.call(SessionMemoryEntity.decideApproval)(Decision.approved("a-1", " "))
    assertEquals(refused.error.code, ErrorCode.BadRequest)
    assertEquals(k.currentState.awaiting.map(_.id), Vector("a-1"))
  }

  test("a request decided while its turn is suspended is a conflict") {
    val k = kit()
    k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-1"), request("a-2"))): Unit
    k.call(SessionMemoryEntity.decideApproval)(Decision.approved("a-1", "dana")): Unit
    val again = k.call(SessionMemoryEntity.decideApproval)(Decision.refused("a-1", "sam"))
    assertEquals(again.error.code, ErrorCode.Conflict)
  }

  test("a request decided before its turn ended is a conflict once the turn is over") {
    val k = kit()
    k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-1"))): Unit
    val decision = Decision.approved("a-1", "dana")
    k.call(SessionMemoryEntity.decideApproval)(decision): Unit
    val result = SessionMessage.ToolResultMessage(
      3L,
      "call-a-1",
      "issue_refund",
      "refunded",
      isError = false,
      "support",
      Some(decision)
    )
    k.call(SessionMemoryEntity.append)(
      SessionMemoryEntity.Append(Vector(user, ai, result), TokenUsage.zero, endsTurn = true)
    ): Unit
    assertEquals(k.currentState.suspended, None)
    val again = k.call(SessionMemoryEntity.decideApproval)(Decision.approved("a-1", "dana"))
    assertEquals(again.error.code, ErrorCode.Conflict)
  }

  test("an id with no record anywhere is not found, naming it") {
    val k = kit()
    k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-1"))): Unit
    val unknown = k.call(SessionMemoryEntity.decideApproval)(Decision.approved("a-404", "dana"))
    assertEquals(unknown.error.code, ErrorCode.NotFound)
    assert(unknown.errorMessage.contains("'a-404'"), unknown.errorMessage)
  }

  test("a decision sent to an empty session is not found, naming the session") {
    val unknown = kit().call(SessionMemoryEntity.decideApproval)(Decision.approved("a-1", "dana"))
    assertEquals(unknown.error.code, ErrorCode.NotFound)
    assert(unknown.errorMessage.contains("'s-1'"), unknown.errorMessage)
  }

  test("end-turn clears the turn and adds nothing; with nothing suspended it does nothing") {
    val k = kit()
    assert(!k.call(SessionMemoryEntity.endTurn).persisted)
    k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-1"))): Unit
    val ended = k.call(SessionMemoryEntity.endTurn)
    assertEquals(ended.events, Vector(SessionMemoryEvent.TurnEnded))
    assertEquals(k.currentState.suspended, None)
    assertEquals(k.currentState.messages, Vector.empty)
  }

  test("an append that ends a turn persists its messages and the end together") {
    val k = kit()
    k.call(SessionMemoryEntity.suspendTurn)(turn(request("a-1"))): Unit
    val written = k.call(SessionMemoryEntity.append)(
      SessionMemoryEntity.Append(Vector(user), TokenUsage.zero, endsTurn = true)
    )
    assertEquals(
      written.events,
      Vector(SessionMemoryEvent.UserMessageAdded(user), SessionMemoryEvent.TurnEnded)
    )
  }

  test("an append that ends a turn when none is suspended writes only its messages") {
    val written = kit().call(SessionMemoryEntity.append)(
      SessionMemoryEntity.Append(Vector(user), TokenUsage.zero, endsTurn = true)
    )
    assertEquals(written.events, Vector(SessionMemoryEvent.UserMessageAdded(user)))
  }
