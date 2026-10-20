package com.thinkmorestupidless.ankka.modelkeeper

import com.thinkmorestupidless.ankka.core.{ModelCalls, ModelState, ModelServerDocument}
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.`export`.{MetricExporter, PeriodicMetricReader}
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.`export`.{BatchSpanProcessor, SimpleSpanProcessor, SpanExporter}

import java.lang
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/**
 * What the keeper says about itself (contracts/keeper.md of feature 053): its counts of calls,
 * which the status document carries, and, when the installation names a collector, the same as
 * metrics and each forwarded call as a span `models.call`, a child of the trace the caller sent.
 *
 * Pushed over OTLP as every platform program pushes: Ollama exposes nothing to scrape.
 */
final class KeeperTelemetry private (
    tracerProvider: SdkTracerProvider,
    meterProvider: Option[SdkMeterProvider],
    parallel: Int,
    source: KeeperTelemetry.Source
):
  import KeeperTelemetry.*

  private val tracer   = tracerProvider.get("ankka-model-keeper")
  private val answered = new AtomicLong
  private val failed   = new AtomicLong
  private val inFlight = new AtomicLong

  private val meter = meterProvider.map(_.get("ankka-model-keeper"))
  private val callCounter = meter.map(
    _.counterBuilder("ankka.models.calls")
      .setDescription("Calls through the model server's door, by model and how they ended.")
      .setUnit("{call}")
      .build()
  )
  private val inFlightCounter = meter.map(
    _.upDownCounterBuilder("ankka.models.in_flight")
      .setDescription("Calls through the door not yet answered.")
      .setUnit("{call}")
      .build()
  )
  private val fetchFailures = meter.map(
    _.counterBuilder("ankka.models.fetch_failures")
      .setDescription("Fetches of a declared model that ended in an error.")
      .setUnit("{fetch}")
      .build()
  )
  private val passDuration = meter.map(
    _.histogramBuilder("ankka.models.pass.duration")
      .setDescription("How long one pass of the keeper's loop took.")
      .setUnit("s")
      .build()
  )
  meter.foreach { m =>
    m.gaugeBuilder("ankka.models.waiting")
      .ofLongs()
      .setDescription("Calls in flight beyond what the server runs at once.")
      .setUnit("{call}")
      .buildWithCallback(_.record(counts.waiting)): Unit
    m.gaugeBuilder("ankka.models.declared")
      .ofLongs()
      .setDescription("Models the declaration names.")
      .buildWithCallback(
        _.record(source.document().models.count(_.state != ModelState.Removing).toLong)
      ): Unit
    m.gaugeBuilder("ankka.models.served")
      .ofLongs()
      .setDescription("Models served.")
      .buildWithCallback(_.record(source.document().served.size.toLong)): Unit
    m.gaugeBuilder("ankka.models.loaded")
      .ofLongs()
      .setDescription("Models Ollama reports loaded.")
      .buildWithCallback(_.record(source.loaded())): Unit
  }

  /** The counts since the keeper started, as the status document carries them. */
  def counts: ModelCalls =
    val flying = inFlight.get
    ModelCalls(answered.get, failed.get, flying, math.max(0L, flying - parallel))

  /** A forwarded call begins; `end` it with the status the caller was given. */
  def call(model: String, project: String, service: String, traceparent: Option[String]): Call =
    val parent = traceparent.fold(Context.root())(tp =>
      W3CTraceContextPropagator.getInstance().extract(Context.root(), tp, Getter)
    )
    val span = tracer
      .spanBuilder("models.call")
      .setParent(parent)
      .setSpanKind(SpanKind.SERVER)
      .setAttribute(Model, model)
      .setAttribute(CallerProject, project)
      .setAttribute(CallerService, service)
      .startSpan()
    inFlight.incrementAndGet(): Unit
    inFlightCounter.foreach(_.add(1, Attributes.of(Model, model)))
    new Call:
      def end(status: Int): Unit =
        inFlight.decrementAndGet(): Unit
        inFlightCounter.foreach(_.add(-1, Attributes.of(Model, model)))
        val outcome = if status / 100 == 2 then Answered else Failed
        if outcome == Answered then answered.incrementAndGet(): Unit
        else failed.incrementAndGet(): Unit
        callCounter.foreach(_.add(1, Attributes.of(Model, model, Outcome, outcome)))
        span.setAttribute(StatusAttribute, lang.Long.valueOf(status.toLong))
        if outcome == Failed then span.setStatus(StatusCode.ERROR): Unit
        span.end()

  /** A connection or request the door refused. */
  def refused(): Unit =
    callCounter.foreach(_.add(1, Attributes.of(Model, NoModel, Outcome, Refused)))

  def fetchFailed(model: String): Unit =
    fetchFailures.foreach(_.add(1, Attributes.of(Model, model)))

  def passTook(nanos: Long): Unit = passDuration.foreach(_.record(nanos / 1e9))

  /** Exports what is pending, for a test that does not wait an interval. */
  def flush(): Unit =
    tracerProvider.forceFlush().join(10, TimeUnit.SECONDS): Unit
    meterProvider.foreach(_.forceFlush().join(10, TimeUnit.SECONDS): Unit)

  def stop(): Unit =
    tracerProvider.shutdown().join(5, TimeUnit.SECONDS): Unit
    meterProvider.foreach(_.shutdown().join(5, TimeUnit.SECONDS): Unit)

object KeeperTelemetry:

  /** A forwarded call's span and counts, ended once. */
  trait Call:
    def end(status: Int): Unit

  /** What the gauges read: the document as last published, and how many models are loaded. */
  final case class Source(document: () => ModelServerDocument, loaded: () => Long)

  val ServiceName: String = "ankka-model-keeper"

  val Model: AttributeKey[String]              = AttributeKey.stringKey("ankka.model")
  val CallerProject: AttributeKey[String]      = AttributeKey.stringKey("ankka.caller.project")
  val CallerService: AttributeKey[String]      = AttributeKey.stringKey("ankka.caller.service")
  val Outcome: AttributeKey[String]            = AttributeKey.stringKey("outcome")
  val StatusAttribute: AttributeKey[lang.Long] = AttributeKey.longKey("http.response.status_code")
  val Answered: String                         = "answered"
  val Failed: String                           = "failed"
  val Refused: String                          = "refused"
  private val NoModel: String                  = "(none)"

  /** The eight instruments' names, for a test and the documentation. */
  val InstrumentNames: Vector[String] = Vector(
    "ankka.models.calls",
    "ankka.models.in_flight",
    "ankka.models.waiting",
    "ankka.models.declared",
    "ankka.models.served",
    "ankka.models.loaded",
    "ankka.models.fetch_failures",
    "ankka.models.pass.duration"
  )

  def resource(installation: Option[String]): Resource =
    val builder = Attributes.builder().put(AttributeKey.stringKey("service.name"), ServiceName)
    installation.foreach(i => builder.put(AttributeKey.stringKey("ankka.installation"), i))
    Resource.getDefault.merge(Resource.create(builder.build()))

  /** Counts only: nothing is exported, and a span is made and dropped. */
  def local(parallel: Int, source: Source): KeeperTelemetry =
    new KeeperTelemetry(SdkTracerProvider.builder().build(), Option.empty, parallel, source)

  /** Spans and metrics to the collector at `endpoint`, as every platform program sends them. */
  def otlp(
      endpoint: URI,
      headers: Vector[(String, String)],
      installation: Option[String],
      parallel: Int,
      source: Source
  ): KeeperTelemetry =
    val base = endpoint.toString.stripSuffix("/")
    val spans = headers
      .foldLeft(
        OtlpHttpSpanExporter.builder().setEndpoint(s"$base/v1/traces").setRetryPolicy(null)
      )((b, h) => b.addHeader(h._1, h._2))
      .build()
    val metrics = headers
      .foldLeft(
        OtlpHttpMetricExporter.builder().setEndpoint(s"$base/v1/metrics").setRetryPolicy(null)
      )((b, h) => b.addHeader(h._1, h._2))
      .build()
    exporting(
      BatchSpanProcessor.builder(spans).build(),
      Some(metrics),
      installation,
      parallel,
      source
    )

  /** With exporters a test holds: each span exported as it ends. */
  def exportingTo(
      spans: SpanExporter,
      metrics: Option[MetricExporter],
      installation: Option[String],
      parallel: Int,
      source: Source
  ): KeeperTelemetry =
    exporting(SimpleSpanProcessor.create(spans), metrics, installation, parallel, source)

  private def exporting(
      processor: io.opentelemetry.sdk.trace.SpanProcessor,
      metrics: Option[MetricExporter],
      installation: Option[String],
      parallel: Int,
      source: Source
  ): KeeperTelemetry =
    val res = resource(installation)
    val tracing =
      SdkTracerProvider.builder().setResource(res).addSpanProcessor(processor).build()
    val meters = metrics.map { exporter =>
      SdkMeterProvider
        .builder()
        .setResource(res)
        .registerMetricReader(
          PeriodicMetricReader
            .builder(exporter)
            .setInterval(java.time.Duration.ofSeconds(30))
            .build()
        )
        .build()
    }
    new KeeperTelemetry(tracing, meters, parallel, source)

  private object Getter extends TextMapGetter[String]:
    def keys(carrier: String): java.lang.Iterable[String] = Seq("traceparent").asJava
    def get(carrier: String, key: String): String =
      if key == "traceparent" then carrier else null
