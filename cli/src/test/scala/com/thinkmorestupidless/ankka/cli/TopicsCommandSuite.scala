package com.thinkmorestupidless.ankka.cli

/**
 * `projects topics set` reads what a refused change would remove from the control plane's refusal,
 * so it can ask the owner (feature 043). The refusal reaches the CLI as the control plane's JSON
 * body, with its quotes escaped, and as plain text from anything that unwrapped it.
 */
class TopicsCommandSuite extends munit.FunSuite:

  test("the removal is read from the refusal as the control plane sends it, and as text") {
    val json =
      """{"status":400,"error":"this declaration removes messages older than 30d; a declaration that removes messages says so with \"removes\""}"""
    assertEquals(TopicsCommand.removalAsked(json), Some("messages older than 30d"))
    val text =
      """this declaration removes messages older than 30d; messages beyond 10GiB on a partition; a declaration that removes messages says so with "removes""""
    assertEquals(
      TopicsCommand.removalAsked(text),
      Some("messages older than 30d; messages beyond 10GiB on a partition")
    )
  }

  test("any other refusal is not a question to ask") {
    assertEquals(
      TopicsCommand.removalAsked(
        """{"status":403,"error":"owner role required: this declaration removes messages older than 30d"}"""
      ),
      None
    )
    assertEquals(
      TopicsCommand.removalAsked("topic 'x': partitions 0 is outside the range 1-1000"),
      None
    )
  }
