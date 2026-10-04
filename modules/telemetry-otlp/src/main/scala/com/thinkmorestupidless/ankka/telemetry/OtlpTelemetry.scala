package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.core.BuildInfo
import com.thinkmorestupidless.ankka.runtime.{AnkkaService, Observability, RuntimeExtension}
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit
import java.util.logging.{Level, Logger as JulLogger}

/**
 * Exports what an instance records to the collector its settings name: its spans, read from the
 * trace window by a cursor, and (`Metrics`) its invocation counts since it started. Logs are never
 * exported: they stay on stdout, where a line written inside a handler names its trace.
 *
 * Started after everything else a service runs and stopped before it, by the runtime, because its
 * provider declared it; a service's code never names it.
 */
final class OtlpTelemetry(settings: TelemetrySettings) extends RuntimeExtension:

  def name: String = OtlpTelemetry.Name

  @volatile private var running: Option[OtlpTelemetry.Running] = None

  def start(service: AnkkaService): Unit =
    OtlpTelemetry.quietTheSdk()
    val system        = service.system
    val observability = Observability(system)
    val identity      = Identity.of(system.settings.config, system.name, settings)
    val resource      = identity.resource
    val scope  = InstrumentationScopeInfo.builder("ankka").setVersion(BuildInfo.version).build()
    val log    = LoggerFactory.getLogger(classOf[OtlpTelemetry])
    val outage = Outage(settings.endpoint, log)
    val exporter = settings.headers
      .foldLeft(
        OtlpHttpSpanExporter
          .builder()
          .setEndpoint(settings.tracesUrl)
          .setTimeout(settings.exportTimeout.toMillis, TimeUnit.MILLISECONDS)
          // Back-off is the loop's: one failed batch is one try, so the outage is said once.
          .setRetryPolicy(null)
      )((builder, header) => builder.addHeader(header._1, header._2))
      .build()
    val recorder = observability.recorder
    val cursor   = recorder.cursor()
    val loop = ExportLoop(
      cursor,
      recorder.capacity,
      exporter,
      span => RecordedSpanData(span, observability.names, recorder, resource, scope),
      settings,
      outage
    ).start()
    val metrics = Metrics(recorder, observability.names, cursor, resource, settings, outage)
    running = Some(OtlpTelemetry.Running(loop, exporter, metrics))
    log.info(
      "telemetry: exporting spans and metrics to the collector at {}://{} as service '{}'",
      settings.endpoint.getScheme,
      settings.endpoint.getAuthority,
      identity.service
    )

  override def stop(): Unit =
    running.foreach { r =>
      running = None
      val deadline = System.nanoTime() + settings.shutdownTimeout.toNanos
      r.loop.stop(settings.shutdownTimeout)
      def left = math.max(1L, (deadline - System.nanoTime()) / 1_000_000L)
      r.metrics.stop(left)
      r.exporter.shutdown().join(left, TimeUnit.MILLISECONDS): Unit
    }

  /** The export loop, for a test that asks how often it tried. */
  private[telemetry] def loop: Option[ExportLoop] = running.map(_.loop)

  /** The metrics, for a test that does not wait an interval. */
  private[telemetry] def metrics: Option[Metrics] = running.map(_.metrics)

object OtlpTelemetry:
  val Name = "telemetry-otlp"

  private final case class Running(
      loop: ExportLoop,
      exporter: OtlpHttpSpanExporter,
      metrics: Metrics
  )

  /**
   * The SDK logs a failed export through `java.util.logging`, once a batch, which nothing in a
   * service bridges: it would print to stderr every second of an outage. The loop says it once
   * instead (`Outage`). Held here because `java.util.logging` holds loggers weakly, and a level set
   * on one that is collected is forgotten.
   */
  private val sdkLoggers: Vector[JulLogger] =
    Vector("io.opentelemetry.exporter", "io.opentelemetry.sdk.metrics.export").map(
      JulLogger.getLogger
    )

  private def quietTheSdk(): Unit = sdkLoggers.foreach(_.setLevel(Level.OFF))
