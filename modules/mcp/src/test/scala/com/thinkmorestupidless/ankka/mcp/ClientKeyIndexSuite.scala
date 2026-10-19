package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.http.{AuthDecision, QueryParams, SimpleRequestContext}
import com.thinkmorestupidless.ankka.mcp.ClientKeyEvent.*
import com.thinkmorestupidless.ankka.mcp.ClientKeyIndex.{Live, Lookup}
import com.thinkmorestupidless.ankka.mcp.TestTools.MovableClock

import java.time.{Duration, Instant}
import scala.collection.mutable
import scala.concurrent.duration.*

/**
 * One instance's index of client keys, with no database behind it: what it admits, what it forgets,
 * and how seldom it writes a use down.
 */
class ClientKeyIndexSuite extends munit.FunSuite:

  private val start = Instant.parse("2026-10-19T10:00:00Z")

  private def live(expiresAt: Option[Instant] = None, scope: Set[String] = Set.empty) =
    Live(
      "digest",
      "acme",
      Map("person" -> "alice"),
      Some("Alice's"),
      "/mcp",
      scope,
      start,
      expiresAt
    )

  private def issued(expiresAt: Option[Instant] = None) =
    ClientKeyIssued("digest", "acme", Map.empty, None, "/mcp", Vector.empty, start, expiresAt)

  test("admit and evict take effect at once, on the instance that called them") {
    val index = ClientKeyIndex(MovableClock(start))
    assertEquals(index.lookup("k1"), Lookup.Missing)
    index.admit("k1", live())
    assert(index.lookup("k1").isInstanceOf[Lookup.Usable])
    index.evict("k1", start)
    assertEquals(index.lookup("k1"), Lookup.Missing)
  }

  test("an issue that arrives after the key was revoked here does not bring it back") {
    val index = ClientKeyIndex(MovableClock(start))
    index.evict("k1", start)
    index.applyEvent("k1", issued())
    assertEquals(index.lookup("k1"), Lookup.Missing)
    // Listed, as revoked.
    assertEquals(index.summaries.map(s => (s.id, s.revokedAt)), Vector("k1" -> Some(start)))
  }

  test("a revocation from the journal is refused from then on") {
    val index = ClientKeyIndex(MovableClock(start))
    index.applyEvent("k1", issued())
    index.applyEvent("k1", ClientKeyRevoked(start))
    assertEquals(index.lookup("k1"), Lookup.Missing)
  }

  test("an expired key is found as expired, and a key with no expiry never is") {
    val clock = MovableClock(start)
    val index = ClientKeyIndex(clock)
    index.applyEvent("soon", issued(Some(start.plusSeconds(60))))
    index.applyEvent("never", issued(None))
    clock.advance(Duration.ofDays(730))
    assert(index.lookup("soon").isInstanceOf[Lookup.Expired])
    assert(index.lookup("never").isInstanceOf[Lookup.Usable])
  }

  test("before it has replayed, the runtime answers neither yes nor no to a well-formed key") {
    val runtime = McpRuntime(MovableClock(start), 1.minute)
    val minted  = McpRuntime.Credentials.mint()
    val context = SimpleRequestContext(
      "POST",
      "/mcp",
      QueryParams.empty,
      Vector("Authorization" -> s"Bearer ${minted.presented}"),
      None
    )
    assert(runtime.authenticate(context).isInstanceOf[AuthDecision.Unavailable])
    runtime.index.markCaughtUp()
    assert(runtime.authenticate(context).isInstanceOf[AuthDecision.Unauthenticated])
  }

  test("a key used twice in a minute is written down once, and not again within the interval") {
    val clock = MovableClock(start)
    val index = ClientKeyIndex(clock)
    index.admit("k1", live())
    val sent    = mutable.ListBuffer.empty[(String, Instant)]
    def sweep() = index.recordUses(1.minute)((id, at) => sent += id -> at)
    sweep()
    assertEquals(sent.toVector, Vector.empty, "nothing used, nothing sent")
    index.touch("k1")
    clock.advance(Duration.ofSeconds(20))
    index.touch("k1")
    sweep()
    assertEquals(sent.toVector, Vector("k1" -> start.plusSeconds(20)))
    clock.advance(Duration.ofSeconds(20))
    index.touch("k1")
    sweep()
    assertEquals(sent.size, 1, "a use within the interval of the one sent is not sent")
    clock.advance(Duration.ofSeconds(60))
    index.touch("k1")
    sweep()
    assertEquals(sent.size, 2)
  }

  test("a key whose recorded use is within the interval is not written down again") {
    val clock = MovableClock(start)
    val index = ClientKeyIndex(clock)
    index.applyEvent("k1", issued())
    index.applyEvent("k1", ClientKeyUsed(start.plusSeconds(30)))
    clock.advance(Duration.ofSeconds(40))
    index.touch("k1")
    val sent = mutable.ListBuffer.empty[(String, Instant)]
    index.recordUses(1.minute)((id, at) => sent += id -> at)
    assertEquals(sent.toVector, Vector.empty)
  }

  test("a listing shows the latest use, this instance's or the recorded one, and never a digest") {
    val clock = MovableClock(start)
    val index = ClientKeyIndex(clock)
    index.admit("k1", live(scope = Set("book_job")))
    clock.advance(Duration.ofSeconds(5))
    index.touch("k1")
    val listed = index.summaries
    assertEquals(listed.map(_.lastUsed), Vector(Some(start.plusSeconds(5))))
    assert(!listed.toString.contains("digest"), listed.toString)
  }
