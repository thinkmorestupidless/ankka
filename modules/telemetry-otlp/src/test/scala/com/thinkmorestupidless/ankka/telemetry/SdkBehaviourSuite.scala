package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.PeriodicMetricReader

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Two things about the SDK that the export loop rests on (research R2, V3 and V4): that it can be
 * kept from logging a failed export, which would otherwise be a line a batch; and that an
 * asynchronous counter is exported as a cumulative sum from a start that does not move.
 */
class SdkBehaviourSuite extends munit.FunSuite with LogCapturing:

  private val sdkLoggers =
    Vector("io.opentelemetry.exporter", "io.opentelemetry.sdk.metrics.export")

  /** What JUL published and what reached stderr while `body` ran. */
  private def captured(body: => Unit): (Vector[LogRecord], String) =
    val records = java.util.concurrent.ConcurrentLinkedQueue[LogRecord]()
    val handler = new Handler:
      def publish(record: LogRecord): Unit = if isLoggable(record) then records.add(record): Unit
      def flush(): Unit                    = ()
      def close(): Unit                    = ()
    handler.setLevel(Level.ALL)
    val root   = Logger.getLogger("")
    val err    = ByteArrayOutputStream()
    val before = System.err
    root.addHandler(handler)
    System.setErr(PrintStream(err, true))
    try body
    finally
      System.setErr(before)
      root.removeHandler(handler)
    import scala.jdk.CollectionConverters.*
    (records.asScala.toVector, err.toString)

  /** Three failed span exports and two failed metric exports, to a port nothing listens on. */
  private def failToExport(): Unit =
    val closed = FakeCollector()
    closed.stop()
    val spans = OtlpHttpSpanExporter
      .builder()
      .setEndpoint(s"${closed.address}/v1/traces")
      .setRetryPolicy(null)
      .build()
    val metrics = OtlpHttpMetricExporter
      .builder()
      .setEndpoint(s"${closed.address}/v1/metrics")
      .setRetryPolicy(null)
      .build()
    val reader =
      PeriodicMetricReader.builder(metrics).setInterval(java.time.Duration.ofDays(1)).build()
    val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
    provider.get("test").counterBuilder("c").build().add(1)
    val span = HandBuiltSpan(
      "4bf92f3577b34da6a3ce929d0e0e4736",
      "00f067aa0ba902b7",
      None,
      "s",
      SpanKind.INTERNAL,
      Attributes.of(AttributeKey.stringKey("k"), "v")
    )
    try
      (1 to 3).foreach(_ =>
        assert(!spans.`export`(java.util.List.of(span)).join(5, TimeUnit.SECONDS).isSuccess)
      )
      (1 to 2).foreach(_ => reader.forceFlush().join(5, TimeUnit.SECONDS))
    finally
      provider.shutdown().join(5, TimeUnit.SECONDS)
      spans.shutdown().join(5, TimeUnit.SECONDS): Unit

  /** Runs `body` with the SDK's loggers at `level`, putting back whatever they were. */
  private def withSdkLoggers[A](level: Level)(body: => A): A =
    val previous = sdkLoggers.map(name => name -> Logger.getLogger(name).getLevel)
    sdkLoggers.foreach(Logger.getLogger(_).setLevel(level))
    try body
    finally previous.foreach((name, was) => Logger.getLogger(name).setLevel(was))

  test("left alone, the SDK reports a failed export: the capture below can see it") {
    // At the SDK's own level, whatever an exporter started earlier in this JVM set it to.
    val (records, err) = withSdkLoggers(Level.INFO)(captured(failToExport()))
    assert(
      records.nonEmpty || err.nonEmpty,
      "nothing was reported, so the next case proves nothing"
    )
  }

  test("with its loggers off, the SDK reports nothing of a failed export") {
    withSdkLoggers(Level.OFF) {
      val (records, err) = captured(failToExport())
      val fromSdk        = records.filter(_.getLoggerName.startsWith("io.opentelemetry"))
      assertEquals(fromSdk.map(_.getMessage), Vector.empty)
      assertEquals(err, "")
    }
  }

  test("an asynchronous counter is exported as a cumulative sum from a start that does not move") {
    val collector = FakeCollector()
    val values    = Iterator(5L, 9L)
    val current   = AtomicLong(0)
    val exporter =
      OtlpHttpMetricExporter.builder().setEndpoint(s"${collector.address}/v1/metrics").build()
    val reader =
      PeriodicMetricReader.builder(exporter).setInterval(java.time.Duration.ofDays(1)).build()
    val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
    provider
      .get("test")
      .counterBuilder("ankka.test")
      .buildWithCallback(measurement => measurement.record(current.get()))
    try
      current.set(values.next())
      reader.forceFlush().join(5, TimeUnit.SECONDS)
      current.set(values.next())
      reader.forceFlush().join(5, TimeUnit.SECONDS)
      val points = collector.metrics
        .filter(_.name == "ankka.test")
        .flatMap(m => m.points.map(p => (m.cumulative, m.monotonic, p)))
      assertEquals(points.map(_._3.value), Vector(5.0, 9.0))
      assert(points.forall(_._1), "not cumulative")
      assert(points.forall(_._2), "not monotonic")
      assertEquals(points.map(_._3.startNanos).distinct.size, 1)
    finally
      provider.shutdown().join(5, TimeUnit.SECONDS)
      collector.stop()
  }
