package com.thinkmorestupidless.ankka.agent

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}

/** The values of an approval: their stored form, and what a decision must carry. */
class ApprovalsSuite extends munit.FunSuite:

  private val request = ApprovalRequest(
    id = "a-1",
    callId = "call-1",
    tool = "issue_refund",
    arguments = Json.obj("amount" -> Json.num(40)),
    requestedAt = 1000L
  )

  test("an approval request round-trips and awaits a decision until it has one") {
    assertEquals(readFromString[ApprovalRequest](writeToString(request)), request)
    assert(request.awaiting)
    val decided = request.copy(decision = Some(Decision.approved("a-1", "dana")))
    assertEquals(readFromString[ApprovalRequest](writeToString(decided)), decided)
    assert(!decided.awaiting)
  }

  test("a decision leaves out a note and expiry it does not have") {
    val json = writeToString(Decision("a-1", approved = true, by = "dana", at = 5L))
    assert(!json.contains("note"), json)
    assert(!json.contains("expired"), json)
    assert(json.contains("\"approvalId\":\"a-1\""), json)
  }

  test("a refusal keeps its note, and an empty note is none") {
    assertEquals(Decision.refused("a-1", "dana", "over the limit").note, Some("over the limit"))
    assertEquals(Decision.refused("a-1", "dana").note, None)
  }

  test("a decision must name who made it") {
    assert(Decision.problem(Decision.approved("a-1", "  ")).isDefined)
    assert(Decision.problem(Decision.approved("a-1", "dana")).isEmpty)
  }

  test("a handler may not take the prefix the platform reaches an agent's host by") {
    import com.thinkmorestupidless.ankka.core.ComponentId
    import com.thinkmorestupidless.ankka.core.Serializers.given
    final class Squatter extends Agent:
      def go(text: String): Effect[String] = effects.userMessage(text).thenReply()
    object Squatter extends Agent.Companion[Squatter](ComponentId("squatter")):
      def create(context: AgentContext) = new Squatter
      val decide                        = command[String, String]("ankka:decide")(_.go)
    val refused = intercept[IllegalArgumentException](Squatter.descriptor)
    assert(refused.getMessage.contains("'ankka:decide'"), refused.getMessage)
  }

  test("an expiry is the platform's decision, and names who made it") {
    val expiry = Decision.expired("a-1", at = 9L)
    assertEquals(expiry.by, Decision.Platform)
    assert(expiry.expired)
    assert(!expiry.approved)
    assertEquals(Decision.problem(expiry), None)
  }
