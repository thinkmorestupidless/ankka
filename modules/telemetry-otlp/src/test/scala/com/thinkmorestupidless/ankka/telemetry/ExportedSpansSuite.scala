package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.*
import scala.util.Try

/**
 * What a service exports, read back from a collector: the scenarios of
 * `features/observability/exported-spans.feature`, and that its logs are not among it.
 */
class ExportedSpansSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val collector             = FakeCollector()
  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(CartEntity.descriptor),
      settings = ConfigFactory.parseString(
        s"""ankka.telemetry.endpoint = "${collector.address}"
           |ankka.telemetry.headers = "authorization=Bearer t, x-scope=a"
           |ankka.telemetry.interval = 100ms
           |ankka.telemetry.service-name = orders
           |ankka.telemetry.project = shop""".stripMargin
      )
    )

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    collector.stop()

  private def addItem(cart: String, item: String) =
    Try(
      testKit.componentClient
        .forKeyValueEntity(EntityId(cart))
        .call(CartEntity.addItem)
        .invoke(item)
    )

  /** Spans already at the collector when a case began: each case reads only what came after. */
  private var before = Set.empty[String]

  override def beforeEach(context: BeforeEach): Unit =
    before = collector.spans.map(_.spanId).toSet

  /** The exported span of `add-item` this case caused, waited for: exports run once an interval. */
  private def exported(cart: String): FakeCollector.ExportedSpan =
    val deadline = System.nanoTime() + 20.seconds.toNanos
    var found    = Option.empty[FakeCollector.ExportedSpan]
    while found.isEmpty && System.nanoTime() < deadline do
      found = collector.spans.find(s => s.name == "cart add-item" && !before(s.spanId))
      if found.isEmpty then Thread.sleep(50)
    found.getOrElse(fail(s"no exported span of cart add-item for $cart within 20s"))

  test("an exported span says where it ran, which handler ran and how it ended") {
    addItem("c1", "tea")
    val span = exported("c1")
    assertEquals(span.serviceName, Some("orders"))
    assertEquals(span.resource.get("ankka.project"), Some("shop"))
    assertEquals(span.attribute("ankka.component"), Some("cart"))
    assertEquals(span.attribute("ankka.handler"), Some("add-item"))
    assertEquals(span.attribute("ankka.outcome"), Some("ok"))
    assertEquals(span.statusCode, FakeCollector.Status.Unset)
    assertEquals(span.traceId.length, 32)
  }

  test("a refusal is exported as refused and never as failed") {
    addItem("c2", "refuse")
    val span = exported("c2")
    assertEquals(span.attribute("ankka.outcome"), Some("refused"))
    assertNotEquals(span.statusCode, FakeCollector.Status.Error)
  }

  test("a span with an unknown caller is exported with no parent and is never given one") {
    // A call from this test's own thread is made outside any handler.
    addItem("c3", "tea")
    val span = exported("c3")
    assertEquals(span.parentSpanId, "")
    assertEquals(span.attribute("ankka.caller"), Some("unknown"))
  }

  test("every export carries the headers the installation set, and none is sent without them") {
    addItem("c4", "tea")
    exported("c4"): Unit
    val requests = collector.requests
    assert(requests.nonEmpty)
    requests.foreach { r =>
      assertEquals(r.header("authorization"), Some("Bearer t"), r.path)
      assertEquals(r.header("x-scope"), Some("a"), r.path)
    }
  }

  test("a service's logs are not exported") {
    org.slf4j.LoggerFactory.getLogger("orders").info("an order was placed")
    addItem("c5", "tea")
    exported("c5"): Unit
    assert(
      collector.paths.forall(p => p == "/v1/traces" || p == "/v1/metrics"),
      collector.paths.distinct.toString
    )
  }
