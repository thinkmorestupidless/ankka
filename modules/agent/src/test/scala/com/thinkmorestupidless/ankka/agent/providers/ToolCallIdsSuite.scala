package com.thinkmorestupidless.ankka.agent.providers

import com.thinkmorestupidless.ankka.agent.*

class ToolCallIdsSuite extends munit.FunSuite:

  private val nine = IdRule.Alphanumeric(9)

  test("an id another vendor gave becomes nine letters and digits, the same every time") {
    val once = ToolCallIds.under(nine)("toolu_01ABCdefGHIjklMNO")
    assert(once.matches("[a-zA-Z0-9]{9}"), once)
    assertEquals(ToolCallIds.under(nine)("toolu_01ABCdefGHIjklMNO"), once)
    assertNotEquals(ToolCallIds.under(nine)("toolu_01ABCdefGHIjklMNP"), once)
  }

  test("an id already of the shape is kept") {
    assertEquals(ToolCallIds.under(nine)("abc123XYZ"), "abc123XYZ")
  }

  test("a call and its result in different messages get the same id, and nothing else changes") {
    val call = ToolCall("toolu_01ABC", "lookup", Json.obj())
    val messages = Vector(
      ChatMessage.User.text("weather?"),
      ChatMessage.Assistant("Let me look.", Vector(call)),
      ChatMessage.ToolResults(Vector(ToolResult("toolu_01ABC", "lookup", "sunny")))
    )
    val rewritten = ToolCallIds.rewrite(messages, nine)
    val id        = ToolCallIds.under(nine)("toolu_01ABC")
    assertEquals(
      rewritten,
      Vector(
        ChatMessage.User.text("weather?"),
        ChatMessage.Assistant("Let me look.", Vector(call.copy(id = id))),
        ChatMessage.ToolResults(Vector(ToolResult(id, "lookup", "sunny")))
      )
    )
  }
