package shoppingcart

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.core.Done
import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit
import shoppingcart.application.CheckoutLog

/**
 * The key value entity with no actor system, cluster or database — but with the real serializers,
 * so a state that cannot cross the wire fails here rather than on first deployment.
 */
class CheckoutLogSuite extends munit.FunSuite with LogCapturing:

  private def newKit = KeyValueEntityTestKit.of(CheckoutLog, "cart-1")

  test("an unrecorded cart starts from the empty state, carrying its own id") {
    val kit = newKit

    assertEquals(kit.currentState.cartId, "cart-1")
    assertEquals(kit.currentState.at, 0L)
    assertEquals(kit.currentState.notified, false)
  }

  // docs:start key-value-test
  test("recording a checkout replaces the value and acknowledges") {
    val kit    = KeyValueEntityTestKit.of(CheckoutLog, "c1")
    val result = kit.call(CheckoutLog.record)(1_700_000_000_000L)

    assertEquals(result.replyValue, Done)
    assert(result.changed, "recording should write a new value")
    assertEquals(result.state.at, 1_700_000_000_000L)
    assertEquals(result.state.notified, true)
  }
  // docs:end key-value-test

  test("a later recording replaces the earlier one; there is no history to accumulate") {
    val kit = newKit
    val _   = kit.call(CheckoutLog.record)(1L)
    val _   = kit.call(CheckoutLog.record)(2L)

    assertEquals(kit.currentState.at, 2L)
  }

  test("the query replies without changing anything") {
    val kit = newKit
    val _   = kit.call(CheckoutLog.record)(7L)

    val result = kit.call(CheckoutLog.get)

    assertEquals(result.replyValue.at, 7L)
    assert(!result.changed, "a query must not write")
  }
