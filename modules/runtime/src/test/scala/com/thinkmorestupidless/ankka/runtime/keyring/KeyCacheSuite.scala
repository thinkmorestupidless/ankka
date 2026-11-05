package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.{DataSubject, KeyAnswer}

import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.duration.*

final class KeyCacheSuite extends munit.FunSuite:

  /** A clock a test moves by hand. */
  private final class Manual extends Clock:
    @volatile var now: Instant                           = Instant.parse("2026-11-04T10:00:00Z")
    def getZone                                          = ZoneOffset.UTC
    override def withZone(zone: java.time.ZoneId): Clock = this
    override def instant(): Instant                      = now
    def move(by: FiniteDuration): Unit                   = now = now.plusMillis(by.toMillis)

  private val key       = KeyAnswer.Key(IArray.fill[Byte](32)(1))
  private def s(n: Int) = DataSubject(s"player/$n")

  private def cache(clock: Clock, maxKeys: Int = 3) =
    new KeyCache(maxKeys, ttl = 5.minutes, clock)

  test("a key is fresh for its lifetime, then held only for the client's outage bound to serve") {
    val clock = Manual()
    val c     = cache(clock)
    c.put("brand", s(1), key)
    assertEquals(c.fresh("brand", s(1)), Some(key))
    clock.move(5.minutes)
    assertEquals(c.fresh("brand", s(1)), None)
    assertEquals(c.stale("brand", s(1)), Some(key))
  }

  test("a destroyed key is held whatever its age; an absent one is never held") {
    val clock = Manual()
    val c     = cache(clock)
    c.put("brand", s(1), KeyAnswer.Destroyed)
    c.put("brand", s(2), KeyAnswer.Absent)
    clock.move(1.day)
    assertEquals(c.fresh("brand", s(1)), Some(KeyAnswer.Destroyed))
    assertEquals(c.fresh("brand", s(2)), None)
    assertEquals(c.stale("brand", s(2)), None)
  }

  test("past the bound, the least recently used key goes first") {
    val c = cache(Manual(), maxKeys = 2)
    c.put("brand", s(1), key)
    c.put("brand", s(2), key)
    c.fresh("brand", s(1))
    c.put("brand", s(3), key)
    assertEquals(c.size, 2)
    assertEquals(c.fresh("brand", s(2)), None)
    assert(c.fresh("brand", s(1)).nonEmpty)
    assert(c.fresh("brand", s(3)).nonEmpty)
  }

  test("a notice drops one key, and losing the channel drops all; projects never cross") {
    val c = cache(Manual())
    c.put("brand", s(1), key)
    c.put("brand", s(2), key)
    c.put("payments", s(1), key)
    c.drop("brand", s(1))
    assertEquals(c.fresh("brand", s(1)), None)
    assertEquals(c.fresh("payments", s(1)), Some(key))
    c.dropAll()
    assertEquals(c.size, 0)
  }
