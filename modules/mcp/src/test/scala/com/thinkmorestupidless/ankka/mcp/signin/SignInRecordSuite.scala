package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.core.effect.Retention
import com.thinkmorestupidless.ankka.sdk.{DeferredCall, TimerScheduler}
import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.duration.*

/**
 * The sign-in broker's records (R11): each state kept and answered as written, a grant coded once
 * and its code taken once, a refresh token taken once, every write forgotten at its lifetime, and
 * each record's deletion scheduled at its expiry under one name per record.
 */
class SignInRecordSuite extends munit.FunSuite:
  import SignInRecord.*

  private val at = Instant.parse("2026-10-19T10:00:00Z")
  private val person = SignedIn(
    "ada",
    Some("Ada"),
    Some("ada@example.test"),
    true,
    Set("staff"),
    Map("org" -> "o1"),
    "customers"
  )

  private def kit(id: String) = KeyValueEntityTestKit.of(SignInRecordEntity, id)

  private val pending: Grant =
    new Grant(
      "c1",
      "http://127.0.0.1:7/cb",
      "challenge",
      "https://shop.example/mcp",
      Some("s"),
      "n",
      "v",
      "customers",
      at
    )

  test("a client, a grant and a refresh token are each kept and answered as written") {
    val records = Vector(
      "client:c1"  -> Client("c1", Vector("https://app.example/cb"), Some("App"), at, at),
      "grant:s1"   -> pending,
      "refresh:d1" -> RefreshToken("c1", person, "https://shop.example/mcp", at, at.plusSeconds(60))
    )
    records.foreach { (id, record) =>
      val k = kit(id)
      val written =
        if record.isInstanceOf[Grant] then
          k.call(SignInRecordEntity.beginGrant)(PutSignInRecord(record, 60000))
        else k.call(SignInRecordEntity.put)(PutSignInRecord(record, 60000))
      assert(!written.isError, written.toString)
      assertEquals(written.retention, Some(Retention.ExpireAfter(1.minute)))
      assertEquals(k.call(SignInRecordEntity.get).replyValue, record)
      // Through the entity's own serializer, as the journal holds it.
      val serializer = SignInRecordEntity.stateSerializer
      assertEquals(serializer.fromBytes(serializer.toBytes(record)), record)
    }
  }

  test("a grant begins once, is coded once, and its code is taken once") {
    val k = kit("grant:s1")
    assert(!k.call(SignInRecordEntity.beginGrant)(PutSignInRecord(pending, 600000)).isError)
    assertEquals(
      k.call(SignInRecordEntity.beginGrant)(PutSignInRecord(pending, 600000)).error.code,
      ErrorCode.Conflict
    )

    val coded = k.call(SignInRecordEntity.completeGrant)(CompleteGrant(person, "digest", 600000))
    assertEquals(coded.replyValue, pending)
    assertEquals(k.currentState, pending.copy(signedIn = Some(person), codeDigest = Some("digest")))
    val again = k.call(SignInRecordEntity.completeGrant)(CompleteGrant(person, "other", 600000))
    assertEquals(again.error.code, ErrorCode.Conflict)

    assertEquals(
      k.call(SignInRecordEntity.takeCode)(TakeCode("wrong")).error.code,
      ErrorCode.BadRequest
    )
    val taken = k.call(SignInRecordEntity.takeCode)(TakeCode("digest"))
    assertEquals(
      taken.replyValue,
      pending.copy(signedIn = Some(person), codeDigest = Some("digest"))
    )
    assertEquals(taken.retention, Some(Retention.DeleteNow))
    assert(k.isDeleted)
    assertEquals(
      k.call(SignInRecordEntity.takeCode)(TakeCode("digest")).error.code,
      ErrorCode.NotFound
    )
  }

  test("a refresh token is taken once: using it replaces it") {
    val k       = kit("refresh:d1")
    val refresh = RefreshToken("c1", person, "https://shop.example/mcp", at, at.plusSeconds(60))
    k.call(SignInRecordEntity.put)(PutSignInRecord(refresh, 60000)): Unit
    assertEquals(k.call(SignInRecordEntity.takeRefresh).replyValue, refresh)
    assert(k.isDeleted)
    assertEquals(k.call(SignInRecordEntity.takeRefresh).error.code, ErrorCode.NotFound)
  }

  test("nothing is written empty, and a grant cannot begin coded") {
    assertEquals(
      kit("client:x").call(SignInRecordEntity.put)(PutSignInRecord(Absent, 1)).error.code,
      ErrorCode.BadRequest
    )
    val coded = pending.copy(codeDigest = Some("d"))
    assertEquals(
      kit("grant:x").call(SignInRecordEntity.beginGrant)(PutSignInRecord(coded, 1)).error.code,
      ErrorCode.BadRequest
    )
  }

  test("each record's deletion is scheduled at its expiry, one timer per record") {
    final class Timers extends TimerScheduler:
      val scheduled = mutable.Map.empty[String, FiniteDuration]
      def createSingleTimer(name: String, delay: FiniteDuration, call: DeferredCall): Unit =
        scheduled(name) = delay
      def createRecurringTimer(
          name: String,
          delay: FiniteDuration,
          period: FiniteDuration,
          call: DeferredCall
      ): Unit = ()
      def delete(name: String): Unit    = scheduled.remove(name): Unit
      def exists(name: String): Boolean = scheduled.contains(name)
    val timers = Timers()
    SignInExpiry.schedule(timers, "grant:s1", at.plusSeconds(600), at)
    assertEquals(timers.scheduled.toMap, Map("sign-in:grant:s1" -> 11.minutes))
    SignInExpiry.schedule(timers, "grant:s1", at.plusSeconds(1200), at)
    assertEquals(
      timers.scheduled.toMap,
      Map("sign-in:grant:s1" -> 21.minutes),
      "written again: moved, not added"
    )
    SignInExpiry.forget(timers, "grant:s1")
    assertEquals(timers.scheduled.toMap, Map.empty[String, FiniteDuration])
  }
