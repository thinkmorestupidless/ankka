package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.*
import scala.util.Try

/** The scenarios of `features/observability/exported-metrics.feature`. */
class ExportedMetricsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val collector             = FakeCollector()
  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(CartEntity.descriptor),
      settings = ConfigFactory.parseString(
        s"""ankka.telemetry.endpoint = "${collector.address}"
           |ankka.telemetry.interval = 100ms
           |ankka.telemetry.metric-interval = 200ms
           |ankka.telemetry.service-name = orders
           |ankka.telemetry.project = shop
           |ankka.observability.ring-capacity = 64""".stripMargin
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

  /**
   * The latest point of `metric` whose attributes include `where`, waited for until `until` holds.
   */
  private def latest(metric: String, where: Map[String, String])(
      until: FakeCollector.ExportedPoint => Boolean
  ): (FakeCollector.ExportedMetric, FakeCollector.ExportedPoint) =
    val deadline = System.nanoTime() + 30.seconds.toNanos
    var found    = Option.empty[(FakeCollector.ExportedMetric, FakeCollector.ExportedPoint)]
    while !found.exists(f => until(f._2)) && System.nanoTime() < deadline do
      found = collector.metrics
        .filter(_.name == metric)
        .flatMap(m => m.points.map(m -> _))
        .filter((_, p) => where.forall((k, v) => p.attributes.get(k).contains(v)))
        .lastOption
      if !found.exists(f => until(f._2)) then Thread.sleep(100)
    found.filter(f => until(f._2)).getOrElse(fail(s"$metric $where never satisfied: $found"))

  test("an exported metric counts every run of a handler since the instance started") {
    (1 to 200).foreach(i => addItem(s"m-$i", "tea"))
    val (metric, point) = latest(
      "ankka.invocations",
      Map("ankka.component" -> "cart", "ankka.handler" -> "add-item", "ankka.outcome" -> "ok")
    )(_.value >= 200)
    assertEquals(point.value, 200.0, "every run, though the window holds 64")
    assert(metric.cumulative && metric.monotonic, metric.toString)
    val (_, duration) =
      latest(
        "ankka.invocation.duration",
        Map("ankka.component" -> "cart", "ankka.handler" -> "add-item")
      )(
        _.value > 0
      )
    assert(duration.value > 0)
  }

  test("an exported metric names the same service and project as the spans") {
    addItem("named", "tea")
    val (metric, _) = latest("ankka.invocations", Map("ankka.handler" -> "add-item"))(_ => true)
    assertEquals(metric.resource.get("service.name"), Some("orders"))
    assertEquals(metric.resource.get("ankka.project"), Some("shop"))
    val span = collector.spans.find(_.name == "cart add-item").get
    assertEquals(metric.resource, span.resource)
  }

  test("a refused command is counted as refused") {
    addItem("refused", "refuse")
    latest(
      "ankka.invocations",
      Map("ankka.component" -> "cart", "ankka.handler" -> "add-item", "ankka.outcome" -> "refused")
    )(_.value >= 1): Unit
  }

  test("the spans the window lost before they were exported are counted") {
    // 200 invocations through a window of 64, read once every 100 ms: some are lost, or none are;
    // either way the metric is there and says how many.
    latest("ankka.telemetry.lost_spans", Map.empty)(_ => true): Unit
  }
