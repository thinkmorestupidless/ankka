package shoppingcart

import com.thinkmorestupidless.ankka.sdk.Publication
import shoppingcart.application.CheckoutTopic

/**
 * How the sample's topic variables name a topic (feature 040): `<project>/<name>` is another
 * project's, a bare name this project's. The cluster suites deploy the sample under both.
 */
class CheckoutTopicSuite extends munit.FunSuite:

  test("a topic with a project is another project's, and a bare name is this project's") {
    assertEquals(
      CheckoutTopic.parse("spinvibe/casino.players"),
      (Some("spinvibe"), "casino.players")
    )
    assertEquals(CheckoutTopic.parse(" cart-checkouts "), (None, "cart-checkouts"))
  }

  test("with neither variable set, notices go to and are read from this project's cart-checkouts") {
    assume(!sys.env.contains("CART_CHECKOUTS_TOPIC") && !sys.env.contains("CART_PUBLISH_TO"))
    assertEquals((CheckoutTopic.project, CheckoutTopic.name), (None, "cart-checkouts"))
    assertEquals(CheckoutTopic.publishTo, Publication("cart-checkouts"))
  }
