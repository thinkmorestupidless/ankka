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
