package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.runtime.{
  Names,
  RecordedSpan,
  Recorder,
  SpanKind as AnkkaKind,
  SpanOutcome,
  TraceContext
}
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.{SpanContext, SpanKind, StatusCode, TraceFlags, TraceState}
import io.opentelemetry.sdk.common.{InstrumentationLibraryInfo, InstrumentationScopeInfo}
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.data.{EventData, LinkData, SpanData, StatusData}

/**
 * A span the recorder recorded, as the SDK's exporter takes one.
 *
 * The SDK's tracer is not used: a recorded span is already finished and has ids the recorder chose,
 * and `SpanData` is the exporter's own seam for exactly that. What a collector receives is
 * `contracts/export.md`: a name of component and handler, the four outcomes as `ankka.outcome`, the
 * status an error only for a fault or a timeout — a refusal is the service working — and a span
 * whose caller is unknown with no parent and `ankka.caller = unknown`.
 */
final class RecordedSpanData(
    span: RecordedSpan,
    names: Names,
    recorder: Recorder,
    resource: Resource,
    scope: InstrumentationScopeInfo
) extends SpanData:
  import RecordedSpanData.*

  private val component = names.nameOf(span.componentRef).getOrElse("(unknown)")
  private val handler   = names.nameOf(span.handlerRef).getOrElse("(unknown)")
  private val start     = recorder.epochNanos(span.startedNanos)
  private val context   = TraceContext(span.traceIdHigh, span.traceId, span.spanId)

  def getName: String = s"$component $handler"

  def getKind: SpanKind = span.kind match
    case AnkkaKind.Server   => SpanKind.SERVER
    case AnkkaKind.Client   => SpanKind.CLIENT
    case AnkkaKind.Consumer => SpanKind.CONSUMER
    case AnkkaKind.Internal => SpanKind.INTERNAL

  def getSpanContext: SpanContext =
    SpanContext.create(
      context.traceIdHex,
      context.spanIdHex,
      TraceFlags.getSampled,
      TraceState.getDefault
    )

  def getParentSpanContext: SpanContext =
    if span.parentSpanId == 0L then SpanContext.getInvalid
    else
      SpanContext.create(
        context.traceIdHex,
        TraceContext(0L, 0L, span.parentSpanId).spanIdHex,
        TraceFlags.getSampled,
        TraceState.getDefault
      )

  def getStatus: StatusData = span.outcome match
    case SpanOutcome.Failed   => StatusData.create(StatusCode.ERROR, "failed")
    case SpanOutcome.TimedOut => StatusData.create(StatusCode.ERROR, "timed out")
    case _                    => StatusData.unset()

  def getAttributes: Attributes =
    val builder = Attributes
      .builder()
      .put(Component, component)
      .put(Handler, handler)
      .put(Outcome, outcomeName(span.outcome))
    if span.callerUnknown then builder.put(Caller, "unknown"): Unit
    builder.build()

  def getStartEpochNanos: Long                                       = start
  def getEndEpochNanos: Long                                         = start + span.durationNanos
  def hasEnded: Boolean                                              = true
  def getEvents: java.util.List[EventData]                           = java.util.List.of()
  def getLinks: java.util.List[LinkData]                             = java.util.List.of()
  def getTotalRecordedEvents: Int                                    = 0
  def getTotalRecordedLinks: Int                                     = 0
  def getTotalAttributeCount: Int                                    = getAttributes.size
  override def getInstrumentationScopeInfo: InstrumentationScopeInfo = scope
  @annotation.nowarn("cat=deprecation")
  def getInstrumentationLibraryInfo: InstrumentationLibraryInfo =
    InstrumentationLibraryInfo.create(scope.getName, scope.getVersion)
  def getResource: Resource = resource

object RecordedSpanData:
  val Component: AttributeKey[String] = AttributeKey.stringKey("ankka.component")
  val Handler: AttributeKey[String]   = AttributeKey.stringKey("ankka.handler")
  val Outcome: AttributeKey[String]   = AttributeKey.stringKey("ankka.outcome")
  val Caller: AttributeKey[String]    = AttributeKey.stringKey("ankka.caller")

  def outcomeName(outcome: SpanOutcome): String = outcome match
    case SpanOutcome.Ok       => "ok"
    case SpanOutcome.Refused  => "refused"
    case SpanOutcome.Failed   => "failed"
    case SpanOutcome.TimedOut => "timed_out"
