package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex
import com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex.Live
import com.thinkmorestupidless.ankka.controlplane.domain.DeployTokenEvent.*
import com.typesafe.config.ConfigFactory

import java.time.{Clock, Duration, Instant, LocalDate, ZoneOffset}
import java.util.concurrent.ConcurrentHashMap

/**
 * The index's fold and its clock-dependent answers, with no database.
 *
 * What a node must get right here is small and total: which events add, update and remove an entry,
 * and when an entry has expired. The parts that need a journal — the cold replay and the caught-up
 * signal — are `IndexProjectionSpike`'s, and the end-to-end behaviour is `ControlPlaneHttpSuite`'s.
 */
class DeployTokenIndexSuite extends munit.FunSuite with LogCapturing:

  private val created = Instant.parse("2026-09-25T10:00:00Z")
  private val expiry  = Instant.parse("2026-12-24T10:00:00Z")

  private def fresh = new ConcurrentHashMap[String, Live]()
  private def none  = ConcurrentHashMap.newKeySet[String]()

  private def creation(expiresAt: Option[Instant] = Some(expiry)) =
    DeployTokenCreated("acme", "github-deploy", "d" * 64, expiresAt, None, Some(created))

  test("a creation adds an entry carrying everything the acl needs, and nothing else") {
    val map     = fresh
    val revoked = none
    DeployTokenIndex.fold(map, revoked, "t1", creation())

    val entry = map.get("t1")
    assertEquals(entry.digest, "d" * 64)
    assertEquals(entry.organizationId, "acme")
    assertEquals(entry.label, "github-deploy")
    assertEquals(entry.expiresAt, Some(expiry))
    assertEquals(entry.persistedLastUsed, None)
  }

  test("a revocation removes the entry rather than flagging it") {
    val map     = fresh
    val revoked = none
    DeployTokenIndex.fold(map, revoked, "t1", creation())
    DeployTokenIndex.fold(map, revoked, "t1", DeployTokenRevoked())
    assertEquals(map.get("t1"), null)
    // And a revocation for something this node never saw is harmless, which matters because a
    // node that started late replays from the beginning and sees both events in order anyway.
    DeployTokenIndex.fold(map, revoked, "never-seen", DeployTokenRevoked())
    assertEquals(map.size, 0)
  }

  test("a creation that arrives after the revocation does not bring the token back") {
    // The live query can be behind when a revocation lands, so the creation can come second.
    val map     = fresh
    val revoked = none
    DeployTokenIndex.fold(map, revoked, "t1", DeployTokenRevoked())
    DeployTokenIndex.fold(map, revoked, "t1", creation())
    assertEquals(map.get("t1"), null)
  }

  test("evict is remembered: the creation still in flight cannot re-admit the token") {
    // The endpoint's write-through, then the journal's late delivery of the creation — the order
    // in which a just-revoked token was once accepted again on the node that revoked it.
    val index = new DeployTokenIndex()
    index.admit("t1", "d" * 64, "acme", "github-deploy", Some(expiry))
    index.evict("t1")
    index.applyEvent("t1", creation())
    assertEquals(index.lookup("t1"), None)
  }

  test("a use updates the persisted date, and one for an unknown token is ignored") {
    val map     = fresh
    val revoked = none
    DeployTokenIndex.fold(map, revoked, "t1", creation())
    DeployTokenIndex.fold(map, revoked, "t1", DeployTokenUsed(LocalDate.parse("2026-09-25")))
    assertEquals(map.get("t1").persistedLastUsed, Some(LocalDate.parse("2026-09-25")))

    DeployTokenIndex.fold(map, revoked, "t2", DeployTokenUsed(LocalDate.parse("2026-09-25")))
    assertEquals(map.get("t2"), null)
  }

  test("lookup hides an expired token, and a token that never expires is never hidden") {
    val before = Clock.fixed(Instant.parse("2026-12-24T09:59:59Z"), ZoneOffset.UTC)
    val after  = Clock.fixed(Instant.parse("2026-12-24T10:00:01Z"), ZoneOffset.UTC)

    val earlier = new DeployTokenIndex(before)
    val later   = new DeployTokenIndex(after)
    val entry   = Live("d" * 64, "acme", "github-deploy", Some(expiry))
    earlier.put("t1", entry)
    later.put("t1", entry)
    assert(earlier.lookup("t1").isDefined)
    assertEquals(later.lookup("t1"), None)

    val forever = new DeployTokenIndex(after)
    forever.put("t2", Live("d" * 64, "acme", "forever", None))
    assert(forever.lookup("t2").isDefined)
  }

  test("expiry is exclusive at the instant itself: a token expires *at* its expiry") {
    val exactly = new DeployTokenIndex(Clock.fixed(expiry, ZoneOffset.UTC))
    exactly.put("t1", Live("d" * 64, "acme", "github-deploy", Some(expiry)))
    assertEquals(exactly.lookup("t1"), None)
  }

  test("an unknown token is simply absent") {
    assertEquals(new DeployTokenIndex().lookup("nothing"), None)
  }

  test("touch records today once, and does not go backwards") {
    val day   = Instant.parse("2026-09-25T10:00:00Z")
    val index = new DeployTokenIndex(Clock.fixed(day, ZoneOffset.UTC))
    val entry = Live("d" * 64, "acme", "github-deploy", Some(expiry))
    index.put("t1", entry)

    index.touch("t1")
    assertEquals(entry.touched.get(), Some(LocalDate.parse("2026-09-25")))
    index.touch("t1")
    assertEquals(entry.touched.get(), Some(LocalDate.parse("2026-09-25")))

    // Touching something this node does not know about is a no-op, not a crash: the token may
    // have been revoked between the acl's lookup and this call.
    index.touch("gone")
  }

  test("a node is not ready until it has replayed") {
    val index = new DeployTokenIndex()
    val ready = index.readiness.getOrElse(fail("the index must have an opinion about readiness"))
    assertEquals(ready(), false)
    assertEquals(index.ready, false)
    index.markCaughtUp()
    assertEquals(ready(), true)
  }

  /**
   * The setting the whole revocation window rests on.
   *
   * It lives in this module's `reference.conf`, and reference files are *merged* rather than
   * layered — so "I wrote it down" is not evidence that it takes effect. This asserts the value a
   * control plane actually loads.
   */
  test("the control plane's read-refresh interval is the one its reference.conf asks for") {
    val loaded = ConfigFactory.load()
    assertEquals(
      loaded.getDuration("pekko.persistence.r2dbc.refresh-interval"),
      Duration.ofMillis(500),
      "the control plane's reference.conf did not win; see feature 013's research V2"
    )
  }
