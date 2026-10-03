package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.ConsumerTestKit

/**
 * The reference's fan-out consumer, one change at a time, with nothing started. It is what the
 * several-message conformance cases read through a running service; here it is read through the
 * unit test kit.
 */
class CheckoutFanoutSuite extends munit.FunSuite:
  import ConformanceReference.*
  import ConformanceReference.ShoppingCartEvent.*

  // docs:start consumer-test
  test("a checkout is answered with three messages, the second under a key of its own") {
    val kit    = ConsumerTestKit.of(CheckoutFanout)
    val result = kit.onMessage(CheckedOut, subject = "c1", sequenceNumber = 4)

    assertEquals(result.payloads, Vector(Fanned(1), Fanned(2), Fanned(3)))
    // The key each message named; the others are keyed by their subject, the cart's id.
    assertEquals(result.keys, Vector(None, Some("second:c1"), None))
    assertEquals(result.recordKeys, Vector(Some("c1"), Some("second:c1"), Some("c1")))
    assertEquals(result.messages(2).metadata.get("x-n"), Some("3"))
  }
  // docs:end consumer-test

  test("an item added is answered with no messages, and an item removed with one, the old way") {
    val kit = ConsumerTestKit.of(CheckoutFanout)
    assertEquals(kit.onMessage(ItemAdded(LineItem("p1", "Pen", 1)), "c1").messages, Vector.empty)
    val removed = kit.onMessage(ItemRemoved("p1"), "c1")
    assert(removed.effect.isInstanceOf[ConsumerEffect.Produce[?]])
    assertEquals(removed.payloads, Vector(Fanned(0)))
    assertEquals(removed.recordKeys, Vector(Some("c1")))
  }
