package com.thinkmorestupidless.ankka.runtime

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{AsyncAppender, Level, LoggerContext}
import ch.qos.logback.core.read.ListAppender
import munit.FunSuite
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/**
 * The scenarios of `features/observability/logs-and-traces.feature` that one logger can hold, and
 * the pattern the platform's own programs print them with. Not `LogCapturing`: these cases need the
 * logger context as logback has it.
 */
final class TraceLoggingSuite extends FunSuite:

  private val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
  private val logger  = context.getLogger("trace-logging-suite")
  // An event reads the logging context lazily, the first time it is asked; a console appender
  // asks at once and an asynchronous one snapshots first. This list does what the latter does, so
  // a case reads what the line was written with and not what the context holds later.
  private val events = new ListAppender[ILoggingEvent]:
    override def append(event: ILoggingEvent): Unit =
      event.prepareForDeferredProcessing()
      super.append(event)

  override def beforeAll(): Unit =
    TraceLogging.install()
    events.setContext(context)
    events.start()
    logger.addAppender(events)
    logger.setLevel(Level.INFO)
    logger.setAdditive(false)

  override def beforeEach(context: BeforeEach): Unit = events.list.clear()

  private val recorder = Recorder(16)

  private def inHandler[A](body: => A): (Span, A) =
    val span = recorder.begin(0x0af7651916cd43ddL, 0x02f2b9ee8a6d1c5dL, 0L, 1, 2, SpanKind.Internal)
    val result = Trace.within(span, CallOrigin("cart", "add-item"))(body)
    recorder.complete(span, SpanOutcome.Ok)
    (span, result)

  private def mdc(i: Int) = events.list.asScala(i).getMDCPropertyMap.asScala.toMap

  test("what a handler prints names the trace id and the span of the handler") {
    val (span, _) = inHandler(logger.info("an item was added"))
    assertEquals(
      mdc(0),
      Map(
        TraceLogging.TraceIdKey -> span.context.traceIdHex,
        TraceLogging.SpanIdKey  -> span.context.spanIdHex
      )
    )
    assertEquals(mdc(0)(TraceLogging.TraceIdKey).length, 32)
  }

  test("what a service prints outside any handler names no trace id") {
    // On the same thread, straight after a handler's line: a filter that only ever set the ids
    // would leave the handler's on this one.
    inHandler(logger.info("inside")): Unit
    logger.info("outside")
    assertEquals(mdc(1), Map.empty[String, String])
  }

  test("a line below its logger's level leaves the logging context as it was") {
    org.slf4j.MDC.put("other", "kept")
    try
      inHandler(logger.debug("not written")): Unit
      assertEquals(Option(org.slf4j.MDC.get(TraceLogging.TraceIdKey)), None)
      assertEquals(org.slf4j.MDC.get("other"), "kept")
    finally org.slf4j.MDC.clear()
  }

  test("installing twice installs one filter") {
    TraceLogging.install()
    TraceLogging.install()
    assertEquals(
      context.getTurboFilterList.asScala.count(_.isInstanceOf[TraceLogging.TraceIds]),
      1
    )
  }

  test("the ids reach a line written on another thread, through an asynchronous appender") {
    val async = AsyncAppender()
    async.setContext(context)
    val behind = ListAppender[ILoggingEvent]()
    behind.setContext(context)
    behind.start()
    async.addAppender(behind)
    async.start()
    val asyncLogger = context.getLogger("trace-logging-suite-async")
    asyncLogger.addAppender(async)
    asyncLogger.setAdditive(false)
    val (span, _) = inHandler(asyncLogger.info("later"))
    async.stop()
    assertEquals(
      behind.list.asScala.head.getMDCPropertyMap.get(TraceLogging.TraceIdKey),
      span.context.traceIdHex
    )
  }

  /** A line as the platform's own pattern prints it. */
  private def printed(event: ILoggingEvent): String =
    val xml = String(
      Files.readAllBytes(Paths.get("../../sidecar/src/main/resources/logback.xml")),
      StandardCharsets.UTF_8
    )
    val pattern = """<pattern>(.*)</pattern>""".r.findFirstMatchIn(xml).get.group(1)
    val encoder = PatternLayoutEncoder()
    encoder.setContext(context)
    encoder.setPattern(pattern.replace("%d{HH:mm:ss.SSS} ", ""))
    encoder.start()
    String(encoder.encode(event), StandardCharsets.UTF_8)

  test(
    "the platform's pattern ends a handler's line with the ids, and leaves any other as it was"
  ) {
    val (span, _) = inHandler(logger.info("an item was added"))
    logger.info("started")
    val lines = events.list.asScala.toVector.map(printed).map(_.stripLineEnd)
    val ids   = s"trace_id=${span.context.traceIdHex} span_id=${span.context.spanIdHex}"
    assert(lines(0).endsWith(s" - an item was added $ids"), lines(0))
    // A line with no trace ends exactly as the pattern before the ids ended it.
    assert(lines(1).endsWith(" - started"), lines(1))
  }

  test("the filter is back after logback is configured again") {
    // The configuration this module's tests run with, loaded again: as a reload would.
    val configurator = JoranConfigurator()
    configurator.setContext(context)
    context.reset()
    configurator.doConfigure(getClass.getResource("/logback-test.xml"))
    assert(context.getTurboFilterList.asScala.exists(_.isInstanceOf[TraceLogging.TraceIds]))
  }
