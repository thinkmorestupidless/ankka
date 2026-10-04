package com.thinkmorestupidless.ankka.telemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.sdk.common.InstrumentationLibraryInfo
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData

/** A `SpanData` built by hand, as the module builds one from a recorded span: not the SDK's own. */
final case class HandBuiltSpan(
    traceId: String,
    spanId: String,
    parentSpanId: Option[String],
    name: String,
    kind: SpanKind,
    attributes: Attributes,
    resource: Resource = Resource.empty()
) extends SpanData:
  private val now = System.currentTimeMillis() * 1_000_000L

  def getName: String   = name
  def getKind: SpanKind = kind
  def getSpanContext: SpanContext =
    SpanContext.create(traceId, spanId, TraceFlags.getSampled, TraceState.getDefault)
  def getParentSpanContext: SpanContext = parentSpanId match
    case Some(parent) =>
      SpanContext.create(traceId, parent, TraceFlags.getSampled, TraceState.getDefault)
    case None => SpanContext.getInvalid
  def getStatus: StatusData                = StatusData.unset()
  def getStartEpochNanos: Long             = now
  def getAttributes: Attributes            = attributes
  def getEvents: java.util.List[EventData] = java.util.List.of()
  def getLinks: java.util.List[LinkData]   = java.util.List.of()
  def getEndEpochNanos: Long               = now + 1_000_000L
  def hasEnded: Boolean                    = true
  def getTotalRecordedEvents: Int          = 0
  def getTotalRecordedLinks: Int           = 0
  def getTotalAttributeCount: Int          = attributes.size
  @annotation.nowarn("cat=deprecation")
  def getInstrumentationLibraryInfo: InstrumentationLibraryInfo = InstrumentationLibraryInfo.empty()
  def getResource: Resource                                     = resource
