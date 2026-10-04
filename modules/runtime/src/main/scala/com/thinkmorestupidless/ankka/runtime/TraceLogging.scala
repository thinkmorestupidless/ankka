package com.thinkmorestupidless.ankka.runtime

import ch.qos.logback.classic.spi.LoggerContextListener
import ch.qos.logback.classic.turbo.TurboFilter
import ch.qos.logback.classic.{Level, Logger, LoggerContext}
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.{LoggerFactory, MDC, Marker}

import scala.jdk.CollectionConverters.*

/**
 * Every line a handler writes names its trace and its span, in the logging context as `trace_id`
 * and `span_id`, so the installation's log store can join a service's logs to its traces. Logs are
 * never exported: they stay on stdout, and this is all the platform adds to them.
 *
 * The ids are set when a line is about to be written, by a filter logback asks before it builds the
 * event, and not when a handler starts: setting them on every invocation would cost two strings and
 * a map on the path the recorder keeps free of allocation, for lines that are mostly never written.
 * Set there, they are in the event's own copy of the context, so an appender that writes on another
 * thread still has them. The filter sets them *or clears them* on every line, so a thread a handler
 * ran on and then left carries nothing of that handler onto its next line.
 *
 * Installed once when a service starts. It survives logback being configured again.
 */
object TraceLogging:

  val TraceIdKey: String = "trace_id"
  val SpanIdKey: String  = "span_id"

  /** Adds the filter to logback's context, once; does nothing when the backend is not logback. */
  def install(): Unit =
    LoggerFactory.getILoggerFactory match
      case context: LoggerContext => context.synchronized(ensure(context))
      case _                      => ()

  private def ensure(context: LoggerContext): Unit =
    if !context.getTurboFilterList.asScala.exists(_.isInstanceOf[TraceIds]) then
      val filter = TraceIds()
      filter.setName("ankka-trace-ids")
      filter.start()
      context.addTurboFilter(filter)
    if !context.getCopyOfListenerList.asScala.exists(_.isInstanceOf[Reinstall]) then
      context.addListener(Reinstall())

  /** Sets the two ids for a line that will be written, or clears them for one with no trace. */
  final class TraceIds extends TurboFilter:
    override def decide(
        marker: Marker,
        logger: Logger,
        level: Level,
        format: String,
        params: Array[AnyRef],
        t: Throwable
    ): FilterReply =
      if level != null && level.isGreaterOrEqual(logger.getEffectiveLevel) then
        Trace.currentContext match
          case Some(context) =>
            MDC.put(TraceIdKey, context.traceIdHex)
            MDC.put(SpanIdKey, context.spanIdHex)
          case None =>
            MDC.remove(TraceIdKey)
            MDC.remove(SpanIdKey)
      FilterReply.NEUTRAL

  /** Configuring logback again clears its filters; this puts the one above back. */
  private final class Reinstall extends LoggerContextListener:
    def isResetResistant: Boolean                         = true
    def onStart(context: LoggerContext): Unit             = ()
    def onReset(context: LoggerContext): Unit             = ensure(context)
    def onStop(context: LoggerContext): Unit              = ()
    def onLevelChange(logger: Logger, level: Level): Unit = ()
