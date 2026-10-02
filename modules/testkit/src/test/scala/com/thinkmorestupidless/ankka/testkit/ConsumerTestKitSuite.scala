package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.core.graph.GraphElementRefused

/**
 * A consumer tested with nothing started: hand it a change at a sequence number, read back what it
 * would publish. No `AnkkaTestKit`, no database, no broker — the suite has no `beforeAll`.
 */
class ConsumerTestKitSuite extends munit.FunSuite:

  test("several messages are read back in order, each with the record key a broker is given") {
    val kit    = ConsumerTestKit.of(LedgerFanout)
    val result = kit.onMessage(LedgerEvent.Added(7), subject = "l1", sequenceNumber = 5)

    assertEquals(result.payloads.map(_.n), Vector(1, 2, 3))
    assertEquals(result.keys, Vector(Some("l1"), Some("second:l1"), Some("l1")))
    // The handler saw the subject and the sequence number it was handed.
    assertEquals(result.payloads.map(_.subject).distinct, Vector("l1"))
    assertEquals(result.payloads.map(_.sequence).distinct, Vector(5L))
    // The subject is the entity's id on every message, as the runtime sets it.
    assertEquals(result.messages.map(_.metadata.subject).distinct, Vector(Some("l1")))
    assert(result.effect.isInstanceOf[ConsumerEffect.ProduceAll[?]])
  }

  test("a single message is read back the same way, keyed by its subject") {
    val result =
      ConsumerTestKit.of(LowStockNotifier).onMessage(StockEvent("sku-1", -20, "w1"), "sku-1")
    assertEquals(result.payloads, Vector(LowStockAlert("sku-1", -20)))
    assertEquals(result.keys, Vector(Some("sku-1")))
    assertEquals(result.messages.head.text, """{"sku":"sku-1","onHand":-20}""")
  }

  test("a message's own headers are read back with it") {
    val result = ConsumerTestKit.of(StockFanout).onMessage(StockEvent("sku-2", 1, "w1"), "sku-2")
    assertEquals(result.messages.map(_.metadata.get("x-n")), Vector(None, None, Some("3")))
    assertEquals(result.messages(2).metadata.subject, Some("sku-2"))
  }

  test("nothing produced is nothing read back: an empty list, done and ignore") {
    assertEquals(
      ConsumerTestKit.of(LedgerFanout).onMessage(LedgerEvent.Added(0)).messages,
      Vector.empty
    )
    assertEquals(
      ConsumerTestKit.of(LedgerFanout).onMessage(LedgerEvent.Closed).messages,
      Vector.empty
    )
    val ignored = ConsumerTestKit.of(LowStockNotifier).onMessage(StockEvent("sku-3", 1, "w1"))
    assertEquals(ignored.effect, ConsumerEffect.Ignore)
    assertEquals(ignored.messages, Vector.empty)
  }

  test("the deletion handler is driven the same way") {
    val result = ConsumerTestKit.of(LedgerFanout).onDelete(subject = "l2", sequenceNumber = 9)
    assertEquals(result.keys, Vector(Some("l2"), Some("gone:l2")))
    assertEquals(result.payloads.map(_.sequence).distinct, Vector(9L))
  }

  test("a result the runtime would refuse is refused here") {
    val failure = intercept[IllegalStateException] {
      ConsumerTestKit.of(TopiclessFanout).onMessage(StockEvent("sku-4", 1, "w1"))
    }
    assert(failure.getMessage.contains("no publish target"), failure.getMessage)
  }

  test("a graph consumer is read back as deltas: kind, id, version, labels, properties") {
    val kit = ConsumerTestKit.graph(ProfileGraph)
    val deltas =
      kit.onMessage(Profile("Ada", "ada@example.com", 2), subject = "p1", sequenceNumber = 3)

    assertEquals(deltas.size, 1)
    val node = deltas.head
    assertEquals(
      (node.kind.name, node.id, node.key, node.version),
      ("node", "profile:p1", "node:profile:p1", 3L)
    )
    assertEquals(node.labels, Vector("Profile"))
    assertEquals(node.properties, Map("name" -> "Ada", "logins" -> 2L))

    val gone = kit.onDelete(subject = "p1", sequenceNumber = 4)
    assertEquals(
      gone.map(d => (d.key, d.isTombstone, d.version)),
      Vector(("node:profile:p1", true, 4L))
    )
  }

  test("a graph consumer's records carry the element key, the subject and the contract's name") {
    val result = ConsumerTestKit.graph(LedgerGraph).records.onMessage(LedgerEvent.Added(5), "l3", 2)
    assertEquals(result.keys, Vector(Some("node:ledger:l3"), Some("node:latest:l3")))
    assertEquals(result.messages.map(_.metadata.subject).distinct, Vector(Some("l3")))
    assertEquals(
      result.messages.map(_.metadata.get(Metadata.CeType)).distinct,
      Vector(Some("ankka.graph-delta.v1"))
    )
  }

  test(
    "a graph consumer over a source with no sequence number is refused unless it states a version"
  ) {
    val kit = ConsumerTestKit.graph(StockGraph)
    val failure = intercept[GraphElementRefused](
      kit.onMessage(StockEvent("s1", 9, "w1"), "s1", sequenceNumber = 0)
    )
    assertEquals(failure.why, "no-sequence")
    assertEquals(
      kit.onMessage(StockEvent("s1", 9, "stated"), "s1", sequenceNumber = 0).map(_.version),
      Vector(9L)
    )
  }
