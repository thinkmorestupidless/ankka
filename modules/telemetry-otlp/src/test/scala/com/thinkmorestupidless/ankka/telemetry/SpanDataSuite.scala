package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter

import com.thinkmorestupidless.ankka.runtime.{Names, Recorder, SpanKind as AnkkaKind, SpanOutcome}
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.InstrumentationScopeInfo

import java.util.concurrent.TimeUnit

/**
 * A span the SDK did not make is accepted by its exporter (research R2, V2), and a recorded span
 * becomes what `contracts/export.md` says a collector receives.
 */
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

  // ── A recorded span, mapped ────────────────────────────────────────────────

  private val recorder = Recorder(16)
  private val names    = Names()
  private val cart     = names.intern("cart")
  private val addItem  = names.intern("add-item")
  private val identity = Identity("orders", Some("shop"), "orders-1")
  private val scope    = InstrumentationScopeInfo.create("ankka")

  private def mapped(
      kind: AnkkaKind = AnkkaKind.Internal,
      outcome: SpanOutcome = SpanOutcome.Ok,
      parent: Long = 0L
  ): RecordedSpanData =
    val span = recorder.begin(0x0af7651916cd43ddL, 0x02f2b9ee8a6d1c5dL, parent, cart, addItem, kind)
    recorder.complete(span, outcome)
    val recorded = recorder.snapshot().find(_.spanId == span.id).get
    RecordedSpanData(recorded, names, recorder, identity.resource, scope)

  test("a span is named for its component and handler, and says both, and how it ended") {
    val data = mapped()
    assertEquals(data.getName, "cart add-item")
    assertEquals(data.getAttributes.get(RecordedSpanData.Component), "cart")
    assertEquals(data.getAttributes.get(RecordedSpanData.Handler), "add-item")
    assertEquals(data.getAttributes.get(RecordedSpanData.Outcome), "ok")
    assertEquals(data.getResource.getAttributes.get(Identity.ServiceName), "orders")
  }

  test("each kind of span is the OTLP kind a collector pairs services by") {
    assertEquals(mapped(AnkkaKind.Server).getKind, SpanKind.SERVER)
    assertEquals(mapped(AnkkaKind.Client).getKind, SpanKind.CLIENT)
    assertEquals(mapped(AnkkaKind.Consumer).getKind, SpanKind.CONSUMER)
    assertEquals(mapped(AnkkaKind.Internal).getKind, SpanKind.INTERNAL)
  }

  test("a refusal is not an error; a fault and a timeout are") {
    assertEquals(mapped(outcome = SpanOutcome.Ok).getStatus.getStatusCode, StatusCode.UNSET)
    assertEquals(mapped(outcome = SpanOutcome.Refused).getStatus.getStatusCode, StatusCode.UNSET)
    assertEquals(
      mapped(outcome = SpanOutcome.Refused).getAttributes.get(RecordedSpanData.Outcome),
      "refused"
    )
    assertEquals(mapped(outcome = SpanOutcome.Failed).getStatus.getStatusCode, StatusCode.ERROR)
    assertEquals(mapped(outcome = SpanOutcome.TimedOut).getStatus.getStatusCode, StatusCode.ERROR)
    assertEquals(
      mapped(outcome = SpanOutcome.TimedOut).getAttributes.get(RecordedSpanData.Outcome),
      "timed_out"
    )
  }

  test("a root has no parent, and neither does a span whose caller is unknown, which says so") {
    val root = mapped()
    assert(!root.getParentSpanContext.isValid)
    assertEquals(root.getAttributes.get(RecordedSpanData.Caller), null)
    val orphan = mapped(parent = Recorder.UnknownCaller)
    assert(!orphan.getParentSpanContext.isValid)
    assertEquals(orphan.getAttributes.get(RecordedSpanData.Caller), "unknown")
  }

  test("a parent is exported as recorded, in the span's trace, whatever the window holds") {
    val child = mapped(parent = 0x00f067aa0ba902b7L)
    assertEquals(child.getParentSpanContext.getSpanId, "00f067aa0ba902b7")
    assertEquals(child.getParentSpanContext.getTraceId, "0af7651916cd43dd02f2b9ee8a6d1c5d")
    assertEquals(child.getSpanContext.getTraceId, "0af7651916cd43dd02f2b9ee8a6d1c5d")
  }

  test("a span's times are the recorder's clock set against the wall") {
    val data = mapped()
    val now  = System.currentTimeMillis() * 1_000_000L
    assert(math.abs(data.getStartEpochNanos - now) < 1_000_000_000L)
    assert(data.getEndEpochNanos >= data.getStartEpochNanos)
  }
