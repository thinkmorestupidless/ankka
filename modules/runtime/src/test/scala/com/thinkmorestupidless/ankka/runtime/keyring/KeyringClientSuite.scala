package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.{CommandError, DataSubject, ErrorCode, KeyAnswer}
import com.thinkmorestupidless.ankka.core.keyring.{KeyringAck, KeyringNotice}

import java.time.{Clock, Instant, ZoneOffset}
import java.util.concurrent.CopyOnWriteArrayList
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Feature 042, T029: the instance's client of the keyring, against a store a test drives. */
final class KeyringClientSuite extends munit.FunSuite:

  private final class Manual extends Clock:
    @volatile var now: Instant                           = Instant.parse("2026-11-04T10:00:00Z")
    def getZone                                          = ZoneOffset.UTC
    override def withZone(zone: java.time.ZoneId): Clock = this
    override def instant(): Instant                      = now
    def move(by: FiniteDuration): Unit                   = now = now.plusMillis(by.toMillis)

  /** A store whose answers, delays, confirmation and notices a test sets. */
  private final class Store(clock: Clock) extends KeyStoreClient:
    @volatile var answer: KeyAnswer                       = KeyAnswer.Key(IArray.fill[Byte](32)(7))
    @volatile var delay: FiniteDuration                   = Duration.Zero
    @volatile var confirmed: Option[Instant]              = Some(clock.instant())
    @volatile var fetches                                 = 0
    @volatile var listener: Option[KeyringNotice => Unit] = None
    def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
      fetches += 1
      if delay > Duration.Zero then Thread.sleep(delay.toMillis)
      answer
    def lookupKey(project: String): IArray[Byte]                        = IArray.fill[Byte](32)(0)
    def subscribe(after: Long)(l: KeyringNotice => Unit): AutoCloseable =
      listener = Some(l)
      () => listener = None
    def confirmedAt(): Option[Instant] = confirmed
    def caughtUp                       = scala.concurrent.Future.unit
    def ack(ack: KeyringAck): Unit     = ()
    def send(n: KeyringNotice): Unit   = listener.getOrElse(fail("not subscribed"))(n)

  private val settings = KeyringSettings(
    address = None,
    maxKeys = 100,
    ttl = 5.minutes,
    outageBound = 15.minutes,
    noticeBound = 60.seconds,
    fetchTimeout = 200.millis
  )
  private val ada = DataSubject("player/8c1f")

  private def client(clock: Manual) =
    val store = Store(clock)
    val c     =
      new KeyringClient(store, new KeyCache(100, 5.minutes, 15.minutes, clock), settings, clock)
    (store, c)

  test("a fresh key is asked for once, then answered from the cache") {
    val (store, c) = client(Manual())
    c.key("brand", ada, create = true)
    c.key("brand", ada, create = false)
    assertEquals(store.fetches, 1)
  }

  test("a notice drops its subject's key before any duty hears of it") {
    val clock      = Manual()
    val (store, c) = client(clock)
    c.key("brand", ada, create = true)
    val heldWhenHeard = CopyOnWriteArrayList[Int]()
    c.onNotice(_ => heldWhenHeard.add(c.cache.size): Unit)
    c.start()
    try
      store.answer = KeyAnswer.Destroyed
      store.send(KeyringNotice(1, "brand", ada.value, rerun = false))
      assertEquals(heldWhenHeard.asScala.toList, List(0))
      assertEquals(c.key("brand", ada, create = false), KeyAnswer.Destroyed)
      assertEquals(c.lastNotice, 1L)
    finally c.close()
  }

  test("a channel unconfirmed for longer than notice-bound drops every key held") {
    val clock      = Manual()
    val (store, c) = client(clock)
    c.key("brand", ada, create = true)
    c.key("brand", DataSubject("player/9d2e"), create = true)
    clock.move(59.seconds)
    assert(!c.check(), "dropped within the bound")
    assertEquals(c.cache.size, 2)
    clock.move(2.seconds)
    assert(c.check(), "kept past the bound")
    assertEquals(c.cache.size, 0)
    // Confirmed again, what is fetched afterwards is held.
    store.confirmed = Some(clock.instant())
    c.key("brand", ada, create = false)
    assert(!c.check())
    assertEquals(c.cache.size, 1)
  }

  test("a channel never confirmed holds nothing past a check") {
    val (store, c) = client(Manual())
    store.confirmed = None
    c.key("brand", ada, create = true)
    assert(c.check())
    assertEquals(c.cache.size, 0)
  }

  test("a fetch that does not answer within fetch-timeout fails as unavailable, naming it") {
    val (store, c) = client(Manual())
    store.delay = 2.seconds
    val error = intercept[CommandError](c.key("brand", ada, create = true))
    assertEquals(error.code, ErrorCode.Unavailable)
    assert(error.getMessage.contains("ankka.keyring.fetch-timeout"), error.getMessage)
    c.close()
  }

  test("in an outage a key fetched within the outage bound is still served, and past it is not") {
    val clock      = Manual()
    val (store, c) = client(clock)
    val key        = c.key("brand", ada, create = true)
    store.delay = 2.seconds
    clock.move(6.minutes)
    assertEquals(c.key("brand", ada, create = false), key)
    clock.move(10.minutes)
    intercept[CommandError](c.key("brand", ada, create = false))
    c.close()
  }

  private def foreignClient(clock: Manual) =
    val store = Store(clock)
    val c     = new KeyringClient(
      store,
      new KeyCache(100, 5.minutes, 15.minutes, clock),
      settings,
      clock,
      ownProject = Some("payments")
    )
    (store, c)

  test("another project's key is held for foreign-ttl and no longer; the own project's for ttl") {
    val clock      = Manual()
    val (store, c) = foreignClient(clock)
    c.key("brand", ada, create = false)
    c.key("payments", ada, create = false)
    assertEquals(store.fetches, 2)
    clock.move(20.seconds)
    c.key("brand", ada, create = false)
    c.key("payments", ada, create = false)
    assertEquals(store.fetches, 2, "both were held within 30 seconds")
    clock.move(20.seconds)
    c.key("brand", ada, create = false)
    c.key("payments", ada, create = false)
    assertEquals(store.fetches, 3, "only another project's key is asked for again past 30 seconds")
    c.close()
  }

  test(
    "a refusal of another project's key is answered and never held, so a grant made later counts"
  ) {
    val clock      = Manual()
    val (store, c) = foreignClient(clock)
    store.answer = KeyAnswer.Refused("no grant")
    assertEquals(c.key("brand", ada, create = false), KeyAnswer.Refused("no grant"))
    store.answer = KeyAnswer.Key(IArray.fill[Byte](32)(9))
    assert(c.key("brand", ada, create = false).isInstanceOf[KeyAnswer.Key])
    assertEquals(store.fetches, 2)
    c.close()
  }
