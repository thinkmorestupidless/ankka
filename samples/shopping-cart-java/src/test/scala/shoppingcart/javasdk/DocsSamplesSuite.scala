package shoppingcart.javasdk

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** The documentation's Java samples that run against the sample service. */
class DocsSamplesSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  test("a checkout notice is published under the cart's id") {
    assertEquals(Checks.noticeKeys().asScala.toList.map(_.toScala), List(Some("c-7")))
  }

  test("an assistant adds to a cart through the MCP endpoint's tools, with a client key") {
    val answer = Checks.cartThroughTools()
    assert(answer.contains("\"structuredContent\""), answer)
    assert(answer.contains("\"productId\":\"p1\""), answer)
    assert(answer.contains("\"quantity\":2"), answer)
  }
