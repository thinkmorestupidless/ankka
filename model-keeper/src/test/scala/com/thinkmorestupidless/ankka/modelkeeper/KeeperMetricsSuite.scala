package com.thinkmorestupidless.ankka.modelkeeper

import com.thinkmorestupidless.ankka.core.{ModelServerDocument, ModelState, ModelStatus}
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.opentelemetry.api.common.AttributeKey

import scala.jdk.CollectionConverters.*

/** The model server's metrics, as the installation's telemetry store receives them. */
class KeeperMetricsSuite extends munit.FunSuite with LogCapturing:

  test("the model server's metrics reach the installation's telemetry store") {
    val document = ModelServerDocument(
      server = "https://models.models-system.svc:8443",
      ready = true,
      models = Vector(
        ModelStatus("qwen3:0.6b", ModelState.Served, Some("sha256:1")),
        ModelStatus("no-such-model:1b", ModelState.Failed, detail = Some("file does not exist"))
      )
    )
    val exported = RecordingExporter()
    val telemetry = KeeperTelemetry.exportingTo(
      exported.spanExporter,
      Some(exported.metricExporter),
      Some("arrakis"),
      1,
      KeeperTelemetry.Source(() => document, () => 1L)
    )
    try
      telemetry.call("qwen3:0.6b", "shop", "cart", None).end(200)
      telemetry.refused()
      telemetry.fetchFailed("no-such-model:1b")
      telemetry.passTook(1_000_000L)
      telemetry.flush()
      val metrics = exported.metrics
      assertEquals(
        metrics.map(_.getName).toSet,
        KeeperTelemetry.InstrumentNames.toSet,
        metrics.map(_.getName).sorted.mkString(", ")
      )
      val resource = metrics.head.getResource.getAttributes
      assertEquals(resource.get(AttributeKey.stringKey("service.name")), "ankka-model-keeper")
      assertEquals(resource.get(AttributeKey.stringKey("ankka.installation")), "arrakis")
      def gauge(name: String): Long =
        metrics.reverse.find(_.getName == name).get.getLongGaugeData.getPoints.asScala.head.getValue
      assertEquals(gauge("ankka.models.declared"), 2L)
      assertEquals(gauge("ankka.models.served"), 1L)
      assertEquals(gauge("ankka.models.loaded"), 1L)
    finally telemetry.stop()
  }
