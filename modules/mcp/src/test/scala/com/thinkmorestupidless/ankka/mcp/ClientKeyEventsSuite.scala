package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.mcp.ClientKeyEvent.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/**
 * The three event shapes of a client key's journal, pinned: every instance replays them, and a
 * journal written by this release must be read by the next. A failure here is a change to a
 * persisted format: add a new shape beside the old one, never edit the JSON below to match.
 */
class ClientKeyEventsSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-19T10:00:00Z")

  private val pinned = Vector[(ClientKeyEvent, String)](
    ClientKeyIssued(
      "d1",
      "acme",
      Map("person" -> "alice"),
      Some("Alice's"),
      "/mcp",
      Vector("book_job"),
      at,
      Some(at)
    ) ->
      """{"type":"ClientKeyIssued","digest":"d1","subject":"acme","attributes":{"person":"alice"},"label":"Alice's","endpoint":"/mcp","scope":["book_job"],"issuedAt":"2026-10-19T10:00:00Z","expiresAt":"2026-10-19T10:00:00Z"}""",
    ClientKeyUsed(at)    -> """{"type":"ClientKeyUsed","at":"2026-10-19T10:00:00Z"}""",
    ClientKeyRevoked(at) -> """{"type":"ClientKeyRevoked","at":"2026-10-19T10:00:00Z"}"""
  )

  test("each event is written as pinned, and read back from the pinned form") {
    pinned.foreach { (event, json) =>
      assertEquals(String(ClientKeyEntity.eventSerializer.toBytes(event), UTF_8), json)
      assertEquals(ClientKeyEntity.eventSerializer.fromBytes(json.getBytes(UTF_8)), event)
    }
  }

  test("a key issued as never expiring, with no label and every tool, is read from its form") {
    val json =
      """{"type":"ClientKeyIssued","digest":"d1","subject":"acme","attributes":{},"endpoint":"/mcp","scope":[],"issuedAt":"2026-10-19T10:00:00Z"}"""
    assertEquals(
      ClientKeyEntity.eventSerializer.fromBytes(json.getBytes(UTF_8)),
      ClientKeyIssued("d1", "acme", Map.empty, None, "/mcp", Vector.empty, at, None)
    )
  }
