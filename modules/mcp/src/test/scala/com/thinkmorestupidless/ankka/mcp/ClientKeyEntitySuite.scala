package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.http.Expiry
import com.thinkmorestupidless.ankka.mcp.ClientKeyEvent.*
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.concurrent.duration.*

/**
 * A client key's entity (feature 054): it keeps a digest and never the key, refuses what the
 * contract refuses at issue, writes a use down at most once a minute and a revocation once.
 */
class ClientKeyEntitySuite extends munit.FunSuite with LogCapturing:

  private val now    = Instant.parse("2026-10-19T10:00:00Z")
  private val minted = McpRuntime.Credentials.mint()

  private def kit = EventSourcedTestKit.of(ClientKeyEntity, minted.id)

  private def asked(
      subject: String = "acme",
      attributes: Map[String, String] = Map("person" -> "alice"),
      expiry: Option[Expiry] = Some(Expiry.After(365.days))
  ) = IssueClientKey(
    minted.digest,
    subject,
    attributes,
    Some("Alice's assistant"),
    "/mcp",
    Vector("book_job"),
    now,
    expiry
  )

  private def issued() =
    val k      = kit
    val result = k.call(ClientKeyEntity.issue)(asked())
    assert(!result.isError, result.reply.toString)
    k

  test("issue keeps the digest and never the key") {
    val k      = kit
    val result = k.call(ClientKeyEntity.issue)(asked())
    result.events match
      case Vector(
            ClientKeyIssued(
              digest,
              "acme",
              attributes,
              Some("Alice's assistant"),
              "/mcp",
              Vector("book_job"),
              `now`,
              Some(expires)
            )
          ) =>
        assertEquals(digest, minted.digest)
        assertEquals(attributes, Map("person" -> "alice"))
        assertEquals(expires, now.plusMillis(365.days.toMillis))
      case other => fail(s"unexpected events $other")
    val bytes =
      result.events.map(e => String(ClientKeyEntity.eventSerializer.toBytes(e), UTF_8)).mkString
    assert(!bytes.contains(minted.secret), "the journal holds the key's secret")
    assert(!bytes.contains(minted.presented), "the journal holds the key")
  }

  test("issued as never expiring, a key keeps no expiry") {
    val result = kit.call(ClientKeyEntity.issue)(asked(expiry = Some(Expiry.Never)))
    assertEquals(result.eventOfType[ClientKeyIssued].expiresAt, None)
  }

  test("issue refuses a blank subject, no expiry and an expiry in the past") {
    val refusals = Vector(
      asked(subject = " ") -> "the subject is blank",
      asked(expiry = None) -> "the expiry is required: After, At or Never",
      asked(expiry = Some(Expiry.At(now.minusSeconds(86400)))) -> "is in the past"
    )
    refusals.foreach { (request, words) =>
      val result = kit.call(ClientKeyEntity.issue)(request)
      assertEquals(result.error.code, ErrorCode.BadRequest)
      assert(result.errorMessage.contains(words), result.errorMessage)
      assert(!result.persisted)
    }
  }

  test("an expiry sent as null is no expiry given, and refused, not never") {
    val json =
      s"""{"digest":"${minted.digest}","subject":"acme","attributes":{},"endpoint":"/mcp","scope":[],"issuedAt":"$now","expiry":null}"""
    val request = ClientKeyEntity.given_Serializer_IssueClientKey.fromBytes(json.getBytes(UTF_8))
    assertEquals(request.expiry, None)
    assertEquals(
      kit.call(ClientKeyEntity.issue)(request).errorMessage,
      "the expiry is required: After, At or Never"
    )
  }

  test(
    "issue refuses seventeen attributes, and a name or a value past 256 characters, naming the attributes"
  ) {
    val refusals = Vector(
      (1 to 17).map(i => s"a$i" -> "v").toMap -> "17 attributes; at most 16",
      Map(("n" * 257) -> "v")                 -> "is 257 characters; at most 256",
      Map("role" -> ("v" * 257)) -> "the value of the attribute 'role' is 257 characters"
    )
    refusals.foreach { (attributes, words) =>
      val result = kit.call(ClientKeyEntity.issue)(asked(attributes = attributes))
      assert(result.errorMessage.contains(words), result.errorMessage)
      assert(result.errorMessage.contains("attribute"), result.errorMessage)
    }
    // Sixteen, each exactly at the limit, are kept.
    val most = (1 to 16).map(i => (s"%0256d".format(i)) -> ("v" * 256)).toMap
    assert(!kit.call(ClientKeyEntity.issue)(asked(attributes = most)).isError)
  }

  test("a second issue of one id is a conflict") {
    val k = issued()
    assertEquals(k.call(ClientKeyEntity.issue)(asked()).error.code, ErrorCode.Conflict)
  }

  test("a use is written down only when a minute or more after the one held") {
    val k = issued()
    assert(k.call(ClientKeyEntity.recordUse)(RecordClientKeyUse(now.plusSeconds(10))).persisted)
    assert(!k.call(ClientKeyEntity.recordUse)(RecordClientKeyUse(now.plusSeconds(40))).persisted)
    assert(k.call(ClientKeyEntity.recordUse)(RecordClientKeyUse(now.plusSeconds(70))).persisted)
    assertEquals(k.currentState.lastUsed, Some(now.plusSeconds(70)))
  }

  test("revoking twice is one event, and a revoked key records no use") {
    val k = issued()
    assert(k.call(ClientKeyEntity.revoke)(RevokeClientKey(now.plusSeconds(5))).persisted)
    val again = k.call(ClientKeyEntity.revoke)(RevokeClientKey(now.plusSeconds(6)))
    assert(!again.isError && !again.persisted)
    assert(!k.call(ClientKeyEntity.recordUse)(RecordClientKeyUse(now.plusSeconds(600))).persisted)
    assertEquals(k.allEvents.count(_.isInstanceOf[ClientKeyRevoked]), 1)
  }

  test("an unknown key is not found for every command but issue") {
    assertEquals(
      kit.call(ClientKeyEntity.revoke)(RevokeClientKey(now)).error.code,
      ErrorCode.NotFound
    )
    assertEquals(
      kit.call(ClientKeyEntity.recordUse)(RecordClientKeyUse(now)).error.code,
      ErrorCode.NotFound
    )
    assertEquals(kit.call(ClientKeyEntity.get).error.code, ErrorCode.NotFound)
  }

  test("get answers the summary, which has no digest") {
    val k       = issued()
    val summary = k.call(ClientKeyEntity.get).replyValue
    assertEquals((summary.id, summary.subject, summary.scope), (minted.id, "acme", Set("book_job")))
    val bytes = String(ClientKeyEntity.given_Serializer_ClientKeySummary.toBytes(summary), UTF_8)
    assert(!bytes.contains(minted.digest), bytes)
  }
