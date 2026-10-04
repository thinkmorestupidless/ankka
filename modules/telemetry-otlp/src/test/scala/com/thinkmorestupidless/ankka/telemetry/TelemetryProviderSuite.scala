package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * "a service of an installation that names no collector exports nothing", held where it is decided:
 * a service with this module on its classpath and no address starts no thread and tries no
 * connection; the same service given an address does both, which is what shows the first half
 * measures something.
 */
class TelemetryProviderSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private def exporting: Boolean =
    Thread.getAllStackTraces.keySet.asScala.exists(_.getName == "ankka-telemetry")

  test("a service of an installation that names no collector exports nothing") {
    val collector = FakeCollector()
    try
      val kit = AnkkaTestKit.start(
        Seq(CartEntity.descriptor),
        settings = ConfigFactory.parseString("""ankka.telemetry.interval = 50ms""")
      )
      try
        (1 to 10).foreach(i =>
          kit.componentClient
            .forKeyValueEntity(EntityId(s"n-$i"))
            .call(CartEntity.addItem)
            .invoke("tea")
        )
        Thread.sleep(500)
        assert(!exporting, "an exporter thread is running")
        assert(!kit.service.extensionNames.contains(OtlpTelemetry.Name), kit.service.extensionNames)
        assertEquals(collector.connections, 0)
      finally kit.stop()
    finally collector.stop()
  }

  test("the same service, given a collector's address, starts the exporter and reaches it") {
    val collector = FakeCollector()
    try
      val kit = AnkkaTestKit.start(
        Seq(CartEntity.descriptor),
        settings = ConfigFactory.parseString(
          s"""ankka.telemetry.endpoint = "${collector.address}"
             |ankka.telemetry.interval = 50ms""".stripMargin
        )
      )
      try
        kit.componentClient
          .forKeyValueEntity(EntityId("y-1"))
          .call(CartEntity.addItem)
          .invoke("tea"): Unit
        val deadline = System.nanoTime() + 10.seconds.toNanos
        while collector.connections == 0 && System.nanoTime() < deadline do Thread.sleep(50)
        assert(exporting, "no exporter thread")
        assert(kit.service.extensionNames.contains(OtlpTelemetry.Name))
        assert(collector.connections > 0)
      finally kit.stop()
      assert(!exporting, "the exporter thread outlived the service")
    finally collector.stop()
  }

  test("a collector named badly is said, and the service starts without exporting") {
    val kit = AnkkaTestKit.start(
      Seq(CartEntity.descriptor),
      settings = ConfigFactory.parseString("""ankka.telemetry.endpoint = "collector:4318"""")
    )
    try assert(!kit.service.extensionNames.contains(OtlpTelemetry.Name))
    finally kit.stop()
  }
