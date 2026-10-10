package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.runtime.{Gauges, Names, Recorder}
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.data.{AggregationTemporality, MetricData}
import io.opentelemetry.sdk.metrics.`export`.{MetricExporter, PeriodicMetricReader}
import io.opentelemetry.sdk.metrics.{InstrumentType, SdkMeterProvider}
import io.opentelemetry.sdk.resources.Resource

import java.util.concurrent.TimeUnit

/**
 * What an instance exports about how it is doing: how many times each handler ran and for how long,
 * counted since the instance started — never over the trace window, which forgets — and how many
 * spans the window lost before they could be exported.
 *
 * The counts are the runtime's (`Recorder.totals`), read when the SDK asks; the SDK only carries
 * them. They are cumulative, so a collector that misses one export loses nothing.
 */
final class Metrics(
    recorder: Recorder,
    names: Names,
    cursor: Recorder.Cursor,
    resource: Resource,
    settings: TelemetrySettings,
    outage: Outage,
    gauges: Gauges = Gauges.global
):
  import Metrics.*

  private val exporter = Reporting(
    settings.headers
      .foldLeft(
        OtlpHttpMetricExporter
          .builder()
          .setEndpoint(settings.metricsUrl)
          .setTimeout(settings.exportTimeout.toMillis, TimeUnit.MILLISECONDS)
          .setRetryPolicy(null)
      )((builder, header) => builder.addHeader(header._1, header._2))
      .build(),
    outage,
    () => cursor.lost
  )

  private val reader = PeriodicMetricReader
    .builder(exporter)
    .setInterval(java.time.Duration.ofNanos(settings.metricInterval.toNanos))
    .build()

  private val provider =
    SdkMeterProvider.builder().setResource(resource).registerMetricReader(reader).build()

  private val meter = provider.get("ankka")

  private def nameOf(pair: Option[(Int, Int)]): (String, String) =
    pair match
      case Some((component, handler)) =>
        (names.nameOf(component).getOrElse(Other), names.nameOf(handler).getOrElse(Other))
      case None => (Other, Other)

  meter
    .counterBuilder("ankka.invocations")
    .setDescription("Invocations of a handler since the instance started, by how they ended.")
    .setUnit("{invocation}")
    .buildWithCallback { measurement =>
      recorder.totals.snapshot().foreach { entry =>
        val (component, handler) = nameOf(entry.pair)
        entry.byOutcome.foreach { (outcome, count) =>
          if count > 0 then
            measurement.record(
              count,
              Attributes.of(
                Component,
                component,
                Handler,
                handler,
                Outcome,
                RecordedSpanData.outcomeName(outcome)
              )
            )
        }
      }
    }: Unit

  meter
    .counterBuilder("ankka.invocation.duration")
    .ofDoubles()
    .setDescription("Time spent in a handler since the instance started.")
    .setUnit("s")
    .buildWithCallback { measurement =>
      recorder.totals.snapshot().foreach { entry =>
        val (component, handler) = nameOf(entry.pair)
        measurement.record(
          entry.durationNanos / 1e9,
          Attributes.of(Component, component, Handler, handler)
        )
      }
    }: Unit

  meter
    .counterBuilder("ankka.telemetry.lost_spans")
    .setDescription("Spans the trace window overwrote before they could be exported.")
    .setUnit("{span}")
    .buildWithCallback(measurement => measurement.record(cursor.lost)): Unit

  // What the process watches rather than does (feature 041): a gauge per name, registered the first
  // time the name is set, since a control plane sets a project's backup gauges long after start.
  gauges.onNewName { (name, description) =>
    meter
      .gaugeBuilder(name)
      .setDescription(description)
      .buildWithCallback { measurement =>
        gauges.snapshot(name).foreach { (attributes, value) =>
          val builder = Attributes.builder()
          attributes.foreach((key, v) => builder.put(key, v))
          measurement.record(value, builder.build())
        }
      }: Unit
  }

  /** Exports once more and stops, within `timeoutMillis`. */
  def stop(timeoutMillis: Long): Unit =
    provider.shutdown().join(math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS): Unit

  /** Exports now: for a test that does not wait an interval. */
  private[telemetry] def flush(): Unit = reader.forceFlush().join(10, TimeUnit.SECONDS): Unit

object Metrics:
  val Component: AttributeKey[String] = RecordedSpanData.Component
  val Handler: AttributeKey[String]   = RecordedSpanData.Handler
  val Outcome: AttributeKey[String]   = RecordedSpanData.Outcome
  val Other: String                   = "(other)"

  /** A metric exporter whose every result is told to the outage, so an outage is said once. */
  private final class Reporting(delegate: MetricExporter, outage: Outage, lost: () => Long)
      extends MetricExporter:
    def getAggregationTemporality(instrument: InstrumentType): AggregationTemporality =
      delegate.getAggregationTemporality(instrument)
    def `export`(metrics: java.util.Collection[MetricData]): CompletableResultCode =
      val result = delegate.`export`(metrics)
      result.whenComplete { () =>
        if result.isSuccess then outage.succeeded(lost())
        else
          outage.failed(
            Option(result.getFailureThrowable).map(_.getMessage).getOrElse("metrics refused"),
            lost()
          )
      }
    def flush(): CompletableResultCode    = delegate.flush()
    def shutdown(): CompletableResultCode = delegate.shutdown()
