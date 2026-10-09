package com.thinkmorestupidless.ankka.runtime

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

/**
 * The runtime's reading of each topic's configuration from the broker (feature 043): read when
 * first asked, answered from what is held while a stale reading is refreshed, and the last reading
 * kept when the broker cannot answer. The reader is a function, so no broker is needed here; the
 * Kafka suites read a real one.
 */
class TopicConfigsSuite extends munit.FunSuite:

  private given ExecutionContext = ExecutionContext.global

  private val compacted = TopicConfig(Set("compact"), 2)
  private val deleting  = TopicConfig(Set("delete"), 1)

  test("a topic's cleanup policy and minimum in-sync copies are read as the broker writes them") {
    assertEquals(
      TopicConfig.of(Some("compact,delete"), Some("2")),
      TopicConfig(Set("compact", "delete"), 2)
    )
    assert(TopicConfig.of(Some("compact, delete"), None).compacted)
    assertEquals(TopicConfig.of(None, None), TopicConfig(Set.empty, 1))
  }

  test("the first reading is waited for; a fresh one is answered without asking again") {
    val reads   = AtomicInteger()
    val configs = TopicConfigs(_ => { reads.incrementAndGet(); Some(compacted) }, interval = 1.hour)
    assertEquals(Await.result(configs.get("shop.deltas"), 5.seconds), Some(compacted))
    assertEquals(Await.result(configs.get("shop.deltas"), 5.seconds), Some(compacted))
    assertEquals(reads.get, 1)
  }

  test(
    "a stale reading is answered while a new one is fetched, and the next ask sees the new one"
  ) {
    val now     = AtomicLong(0L)
    val current = AtomicReference(deleting)
    val gate    = CountDownLatch(1)
    val configs = TopicConfigs(
      _ =>
        if current.get == compacted then gate.await()
        Some(current.get)
      ,
      interval = 1.minute,
      now = () => now.get
    )
    assertEquals(Await.result(configs.get("shop.deltas"), 5.seconds), Some(deleting))
    // The topic is declared compacted on the broker; a minute passes.
    current.set(compacted)
    now.set(2.minutes.toNanos)
    // The stale reading answers at once, though the new one is held up.
    assertEquals(Await.result(configs.get("shop.deltas"), 1.second), Some(deleting))
    gate.countDown()
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while Await.result(configs.get("shop.deltas"), 1.second) != Some(compacted) && System
        .nanoTime() < deadline
    do Thread.sleep(20)
    assertEquals(Await.result(configs.get("shop.deltas"), 1.second), Some(compacted))
  }

  test(
    "a broker that cannot answer leaves the last reading, and a topic it does not know is None"
  ) {
    val failing = AtomicReference[Option[RuntimeException]](None)
    val now     = AtomicLong(0L)
    val configs = TopicConfigs(
      topic =>
        failing.get.foreach(e => throw e)
        Option.when(topic == "shop.deltas")(compacted)
      ,
      interval = 1.minute,
      now = () => now.get
    )
    assertEquals(Await.result(configs.get("shop.deltas"), 5.seconds), Some(compacted))
    assertEquals(Await.result(configs.get("shop.unknown"), 5.seconds), None)
    failing.set(Some(RuntimeException("the broker is not answering")))
    assertEquals(Await.result(configs.describe("shop.deltas"), 5.seconds), Some(compacted))
  }
