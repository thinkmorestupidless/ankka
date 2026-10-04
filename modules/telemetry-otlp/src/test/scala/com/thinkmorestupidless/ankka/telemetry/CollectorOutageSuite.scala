package com.thinkmorestupidless.ankka.telemetry

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.read.ListAppender
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.{Names, Recorder, SpanKind, SpanOutcome}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import org.slf4j.LoggerFactory

import java.net.URI
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The scenarios of `features/observability/collector-unreachable.feature`. */
class CollectorOutageSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  /** A logger of the suite's own, its lines kept for the cases to count. */
  private def capturing(name: String): (org.slf4j.Logger, ListAppender[ILoggingEvent]) =
    val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val logger  = context.getLogger(name)
    val lines   = ListAppender[ILoggingEvent]()
    lines.setContext(context)
    lines.start()
    logger.addAppender(lines)
    logger.setLevel(Level.INFO)
    (logger, lines)

  private def settings(collector: FakeCollector) =
    TelemetrySettings
      .from(
        ConfigFactory
          .parseString(
            s"""ankka.telemetry.endpoint = "${collector.address}"
               |ankka.telemetry.headers = "authorization=Bearer s3cr3t"
               |ankka.telemetry.interval = 50ms
               |ankka.telemetry.max-backoff = 400ms
               |ankka.telemetry.export-timeout = 1s
               |ankka.telemetry.shutdown-timeout = 1s""".stripMargin
          )
          .withFallback(ConfigFactory.load())
      )
      .toOption
      .flatten
      .get

  /** A recorder, an export loop over it to `collector`, and a way to record spans into it. */
  private final class Harness(collector: FakeCollector, capacity: Int, log: String):
    val recorder        = Recorder(capacity)
    val names           = Names()
    private val handler = names.intern("add-item")
    private val cart    = names.intern("cart")
    val cursor          = recorder.cursor()
    val (logger, lines) = capturing(log)
    val outage          = Outage(URI.create(collector.address), logger)
    val exporter = OtlpHttpSpanExporter
      .builder()
      .setEndpoint(settings(collector).tracesUrl)
      .addHeader("authorization", "Bearer s3cr3t")
      .setRetryPolicy(null)
      .build()
    val loop = ExportLoop(
      cursor,
      capacity,
      exporter,
      span =>
        RecordedSpanData(
          span,
          names,
          recorder,
          Identity("orders", None, "i").resource,
          InstrumentationScopeInfo.create("ankka")
        ),
      settings(collector),
      outage
    ).start()

    def record(n: Int): Unit =
      (1 to n).foreach(_ =>
        recorder.complete(
          recorder.begin(1L, 2L, 0L, cart, handler, SpanKind.Internal),
          SpanOutcome.Ok
        )
      )

    def warnings = lines.list.asScala.toVector.filter(_.getLevel == Level.WARN)
    def infos    = lines.list.asScala.toVector.filter(_.getLevel == Level.INFO)

    def stop(): Unit =
      loop.stop(1.second)
      exporter.shutdown().join(1, TimeUnit.SECONDS): Unit

  private def eventually[A](what: String, within: FiniteDuration = 10.seconds)(
      check: => Option[A]
  ): A =
    val deadline = System.nanoTime() + within.toNanos
    var found    = check
    while found.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
      found = check
    found.getOrElse(fail(s"$what did not happen within $within"))

  test("a service whose collector cannot be reached handles its requests as before") {
    val collector = FakeCollector()
    collector.stop()
    val kit = AnkkaTestKit.start(
      Seq(CartEntity.descriptor),
      settings = ConfigFactory.parseString(
        s"""ankka.telemetry.endpoint = "${collector.address}"
           |ankka.telemetry.interval = 50ms
           |ankka.telemetry.max-backoff = 400ms""".stripMargin
      )
    )
    try
      val answered = (1 to 1000).count(i =>
        Try(
          kit.componentClient
            .forKeyValueEntity(EntityId(s"o-${i % 50}"))
            .call(CartEntity.addItem)
            .invoke("x")
        ).isSuccess
      )
      assertEquals(answered, 1000)
      val counted = com.thinkmorestupidless.ankka.runtime
        .Observability(kit.service.system)
        .recorder
        .totals
        .snapshot()
        .map(_.invocations)
        .sum
      assert(counted >= 1000, s"recorded $counted")
    finally kit.stop()
  }

  test("a collector that cannot be reached is reported once and not for every span") {
    val collector = FakeCollector()
    collector.stop()
    val h = Harness(collector, 1024, "outage-once")
    try
      h.record(10)
      val started = System.nanoTime()
      eventually("ten tries")(Option.when(h.loop.attempts >= 6)(()))
      val elapsed = (System.nanoTime() - started).nanos
      // Without a growing wait, six tries at 50 ms would take 300 ms; with it, at least 50 + 100 +
      // 200 + 400 + 400 between them.
      assert(elapsed >= 1.second, s"six tries in $elapsed: the wait between tries is not growing")
      assertEquals(h.warnings.size, 1, h.warnings.map(_.getFormattedMessage).toString)
      val line = h.warnings.head.getFormattedMessage
      assert(line.contains(s"127.0.0.1:${collector.port}"), line)
      assert(!line.contains("s3cr3t"), line)
    finally h.stop()
  }

  test("a service exports again when its collector can be reached again") {
    val collector = FakeCollector()
    collector.stop()
    val h = Harness(collector, 64, "outage-recovery")
    try
      h.record(500)
      eventually("a failed try")(h.warnings.headOption)
      collector.restart()
      val arrived = eventually("spans after the collector is back")(
        Option(collector.spans).filter(_.nonEmpty)
      )
      assert(arrived.size <= 64, "only what the window still held can be sent")
      eventually("one line saying it is back")(h.infos.headOption): Unit
      assertEquals(h.infos.size, 1)
      assert(h.cursor.lost > 0, "500 spans through a window of 64 lose some")
      assert(h.infos.head.getFormattedMessage.contains("spans were lost"))
    finally
      h.stop()
      collector.stop()
  }

  test("an instance that stops exports what it holds and is not kept from stopping") {
    // The collector up: what was recorded since the last export arrives as the loop stops.
    val up = FakeCollector()
    val h  = Harness(up, 1024, "stop-up")
    try
      h.record(3)
      h.stop()
      assertEquals(up.spans.size, 3)
    finally up.stop()

    // The collector down: stopping takes no longer than its limit.
    val down = FakeCollector()
    down.stop()
    val d = Harness(down, 1024, "stop-down")
    d.record(3)
    val started = System.nanoTime()
    d.stop()
    val took = (System.nanoTime() - started).nanos
    assert(took < 3.seconds, s"stopping took $took")
  }
