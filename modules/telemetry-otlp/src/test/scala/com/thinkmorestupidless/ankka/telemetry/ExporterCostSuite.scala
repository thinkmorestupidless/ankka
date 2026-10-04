package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.core.{EntityId, Metadata}
import com.thinkmorestupidless.ankka.runtime.{
  CallOrigin,
  Observability,
  SpanKind,
  SpanOutcome,
  Trace
}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory

/**
 * `ServiceRecordingCostSuite`'s measurement with an exporter attached: what recording costs a real
 * invocation of a service whose telemetry is being exported. Once against a collector that answers,
 * where the export loop reads and sends throughout, and once against a port nothing listens on, the
 * outage the feature promises costs the service nothing. Off unless benchmarks are asked for.
 */
class ExporterCostSuite extends munit.FunSuite with LogCapturing:

  override def munitIgnore: Boolean = !sys.props.get("ankka.benchmarks").contains("on")

  override val munitTimeout = scala.concurrent.duration.Duration(8, "min")

  private def measure(collector: FakeCollector, label: String): Double =
    val kit = AnkkaTestKit.start(
      Seq(CartEntity.descriptor),
      settings = ConfigFactory.parseString(
        s"""ankka.telemetry.endpoint = "${collector.address}"
           |ankka.telemetry.interval = 100ms""".stripMargin
      )
    )
    try
      val client = kit.componentClient.forKeyValueEntity(EntityId("bench"))
      (1 to 200).foreach(i => client.call(CartEntity.addItem).invoke(s"w$i"))
      val iterations = 2_000
      val started    = System.nanoTime()
      (1 to iterations).foreach(i => client.call(CartEntity.addItem).invoke(s"i$i"))
      val perInvocation = (System.nanoTime() - started).toDouble / iterations

      val observability = Observability(kit.service.system)
      val origin        = CallOrigin("cart", "add-item")
      val componentRef  = observability.names.intern("cart")
      val handlerRef    = observability.names.intern("add-item")
      def observed(i: Int): Unit =
        val span = observability.recorder.begin(
          i.toLong,
          i.toLong + 1,
          0L,
          componentRef,
          handlerRef,
          SpanKind.Internal
        )
        val carried = Trace.within(span, origin)(Trace.outbound(Metadata.empty))
        observability.recorder.complete(span, SpanOutcome.Ok)
        observability.handled(carried, "cart", "add-item", SpanOutcome.Ok, 1_000L)
      (1 to 500_000).foreach(observed)
      val rounds        = 2_000_000
      val observedStart = System.nanoTime()
      var n             = 0
      while n < rounds do
        observed(n)
        n += 1
      val observingCost = (System.nanoTime() - observedStart).toDouble / rounds
      val fraction      = observingCost / perInvocation
      println(f"""
           |  $label
           |  one real invocation      : $perInvocation%,.0f ns
           |  span + caller + counting : $observingCost%.1f ns, an exporter reading the window
           |  cost of always-on        : ${fraction * 100}%.4f%% of an invocation    (budget 1%%)
           |""".stripMargin)
      fraction
    finally kit.stop()

  test("recording costs a real invocation no more than its budget with a collector exporting") {
    val collector = FakeCollector()
    try assert(measure(collector, "collector answering") <= 0.01)
    finally collector.stop()
  }

  test("recording costs a real invocation no more than its budget with the collector unreachable") {
    val collector = FakeCollector()
    collector.stop()
    assert(measure(collector, "collector unreachable") <= 0.01)
  }
