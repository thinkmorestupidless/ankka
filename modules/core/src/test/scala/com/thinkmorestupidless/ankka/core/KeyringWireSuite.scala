package com.thinkmorestupidless.ankka.core

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.thinkmorestupidless.ankka.core.keyring.*
import com.thinkmorestupidless.ankka.core.keyring.KeyringWire.given

import java.time.Instant

/**
 * Feature 042, T046: every message of the keyring's routes and notices WebSocket, as
 * `contracts/keyring-and-protocol.md` writes it, both ways.
 */
class KeyringWireSuite extends munit.FunSuite:

  private def pins[A: JsonValueCodec](value: A, json: String)(using munit.Location): Unit =
    assertEquals(writeToString(value), json)
    assertEquals(readFromString[A](json), value)

  test("a key request, and each of the three answers") {
    pins(
      KeyRequest("brand", "player/8c1f", create = true),
      """{"project":"brand","subject":"player/8c1f","create":true}"""
    )
    pins(
      KeyRequest("brand", "player/8c1f"),
      """{"project":"brand","subject":"player/8c1f","create":false}"""
    )
    pins(KeyReply(key = Some("AAEC")), """{"key":"AAEC"}""")
    pins(KeyReply(destroyed = Some(true)), """{"destroyed":true}""")
    pins(
      KeyReply(refused = Some("not a service of brand")),
      """{"refused":"not a service of brand"}"""
    )
    pins(LookupKeyRequest("brand"), """{"project":"brand"}""")
    pins(LookupKeyReply("AAEC"), """{"key":"AAEC"}""")
  }

  test("the frames of the notices WebSocket, as the contract writes them") {
    pins[NoticeFrame](
      NoticeFrame.Notice(12, "brand", "player/8c1f", rerun = false),
      """{"type":"notice","seq":12,"project":"brand","subject":"player/8c1f","rerun":false}"""
    )
    pins[NoticeFrame](
      NoticeFrame.Ack(
        12,
        "players",
        "players-7f9c-0",
        Completion(3, 1, 0, Some(HandlerReport(2, "immediate")))
      ),
      """{"type":"ack","seq":12,"service":"players","instance":"players-7f9c-0","completion":{"rowsRedacted":3,"tokensDropped":1,"sessionsEnded":0,"handler":{"objectsErased":2,"finality":"immediate"}}}"""
    )
    pins[NoticeFrame](
      NoticeFrame.Ack(13, "players", "i", Completion(0, 0, 0, Some(HandlerReport.failed("boom")))),
      """{"type":"ack","seq":13,"service":"players","instance":"i","completion":{"rowsRedacted":0,"tokensDropped":0,"sessionsEnded":0,"handler":{"objectsErased":0,"finality":"failed","error":"boom"}}}"""
    )
    pins[NoticeFrame](
      NoticeFrame.Hello("brand", "players", "players-7f9c-0", 11),
      """{"type":"hello","project":"brand","service":"players","instance":"players-7f9c-0","after":11}"""
    )
    pins[NoticeFrame](
      NoticeFrame.Heartbeat(Instant.parse("2026-11-04T10:00:00Z")),
      """{"type":"heartbeat","at":"2026-11-04T10:00:00Z"}"""
    )
  }

  test("the control plane's erasure order, the destruction and the status") {
    pins(
      ErasureOrder(7, "brand", "player/8c1f"),
      """{"seq":7,"project":"brand","subject":"player/8c1f"}"""
    )
    pins(
      ErasureDestroyed(Instant.parse("2026-11-04T10:00:00Z")),
      """{"destroyedAt":"2026-11-04T10:00:00Z"}"""
    )
    pins(
      ErasureStatus(
        Vector(
          ServiceErasureStatus(
            "players",
            Some(Instant.parse("2026-11-04T10:00:01Z")),
            Some(Completion(1, 0, 0))
          ),
          ServiceErasureStatus("wallet")
        )
      ),
      """{"services":[{"service":"players","completedAt":"2026-11-04T10:00:01Z","completion":{"rowsRedacted":1,"tokensDropped":0,"sessionsEnded":0}},{"service":"wallet"}]}"""
    )
    pins(ErasureStatus(), """{"services":[]}""")
  }

  test("a decrypt request carries each envelope as written; the answer each value or null") {
    val envelope = """{"$personal":1,"project":"brand","subject":"player/8c1f","c":"AAEC"}"""
    pins(
      DecryptRequest("g1", "brand", "player-registered", Vector(RawJson(envelope))),
      s"""{"grant":"g1","project":"brand","manifest":"player-registered","fields":[$envelope]}"""
    )
    pins(
      DecryptReply(
        Vector(Some(RawJson("\"ada@example.com\"")), None, Some(RawJson("""{"city":"Leeds"}""")))
      ),
      """{"values":["ada@example.com",null,{"city":"Leeds"}]}"""
    )
  }
