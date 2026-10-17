package com.thinkmorestupidless.ankka.modelkeeper

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.{AggregationTemporality, MetricData}
import io.opentelemetry.sdk.metrics.`export`.MetricExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SpanExporter

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/** Spans and metrics the keeper exported, held for a test to read. */
final class RecordingExporter:
  private val spanBuffer   = new CopyOnWriteArrayList[SpanData]()
  private val metricBuffer = new CopyOnWriteArrayList[MetricData]()

  def spans: Vector[SpanData]     = spanBuffer.asScala.toVector
  def metrics: Vector[MetricData] = metricBuffer.asScala.toVector

  val spanExporter: SpanExporter = new SpanExporter:
    def `export`(spans: java.util.Collection[SpanData]): CompletableResultCode =
      spanBuffer.addAll(spans): Unit
      CompletableResultCode.ofSuccess()
    def flush(): CompletableResultCode    = CompletableResultCode.ofSuccess()
    def shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

  val metricExporter: MetricExporter = new MetricExporter:
    def `export`(metrics: java.util.Collection[MetricData]): CompletableResultCode =
      metricBuffer.addAll(metrics): Unit
      CompletableResultCode.ofSuccess()
    def getAggregationTemporality(instrument: InstrumentType): AggregationTemporality =
      AggregationTemporality.CUMULATIVE
    def flush(): CompletableResultCode    = CompletableResultCode.ofSuccess()
    def shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
