package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter

import java.util.concurrent.TimeUnit

/** A span the SDK did not make is accepted by its exporter (research R2, V2). */
class SpanDataSuite extends munit.FunSuite with LogCapturing:

  private val collector = FunFixture[FakeCollector](_ => FakeCollector(), _.stop())

  collector.test("the exporter sends a span built by hand, ids and all") { collector =>
    val exporter =
      OtlpHttpSpanExporter.builder().setEndpoint(s"${collector.address}/v1/traces").build()
    try
      val span = HandBuiltSpan(
        traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
        spanId = "00f067aa0ba902b7",
        parentSpanId = None,
        name = "cart add-item",
        kind = SpanKind.SERVER,
        attributes = Attributes.of(AttributeKey.stringKey("ankka.component"), "cart")
      )
      val result = exporter.`export`(java.util.List.of(span)).join(5, TimeUnit.SECONDS)
      assert(result.isSuccess, "the export failed")
      val received = collector.spans
      assertEquals(received.size, 1)
      val only = received.head
      assertEquals(only.traceId, "4bf92f3577b34da6a3ce929d0e0e4736")
      assertEquals(only.spanId, "00f067aa0ba902b7")
      assertEquals(only.parentSpanId, "")
      assertEquals(only.name, "cart add-item")
      assertEquals(only.kind, FakeCollector.Kind.Server)
      assertEquals(only.attribute("ankka.component"), Some("cart"))
    finally exporter.shutdown().join(5, TimeUnit.SECONDS): Unit
  }
