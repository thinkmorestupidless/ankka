package com.thinkmorestupidless.ankka.telemetry

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/**
 * An OTLP collector for tests: answers `/v1/traces` and `/v1/metrics` and remembers what it was
 * sent, decoded.
 *
 * The bodies are read with [[Protobuf]], a reader of protobuf's wire format by field number, rather
 * than with the `opentelemetry-proto` classes: those need protobuf-java 4, and this module's tests
 * share a classpath with ScalaPB code generated against 3. OTLP's messages are few and stable, and
 * the field numbers below are theirs.
 *
 * Binds an ephemeral port on loopback. `stop()` and `restart()` keep the port, so a test can make
 * the collector unreachable and reachable again at the same address.
 */
final class FakeCollector private (val port: Int):
  import FakeCollector.*

  @volatile private var server: HttpServer = null

  private val received      = ConcurrentLinkedQueue[Request]()
  private val acceptedCount = AtomicInteger(0)

  def address: String = s"http://127.0.0.1:$port"

  /** Every request, in the order it arrived. */
  def requests: Vector[Request] = received.asScala.toVector

  /** Every path a request was sent to. */
  def paths: Vector[String] = requests.map(_.path)

  /** How many exchanges the server accepted, whatever their path. */
  def connections: Int = acceptedCount.get()

  def spans: Vector[ExportedSpan] =
    requests.filter(_.path == "/v1/traces").flatMap(r => decodeSpans(r.body))

  def metrics: Vector[ExportedMetric] =
    requests.filter(_.path == "/v1/metrics").flatMap(r => decodeMetrics(r.body))

  def start(): FakeCollector =
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    s.createContext("/", exchange => handle(exchange))
    s.setExecutor(null)
    s.start()
    server = s
    this

  def stop(): Unit =
    val s = server
    server = null
    if s ne null then s.stop(0)

  def restart(): FakeCollector = start()

  private def handle(exchange: HttpExchange): Unit =
    acceptedCount.incrementAndGet()
    try
      val body = exchange.getRequestBody.readAllBytes()
      val headers =
        exchange.getRequestHeaders.asScala.toVector.flatMap((k, vs) =>
          vs.asScala.map(v => k.toLowerCase -> v)
        )
      val path = exchange.getRequestURI.getPath
      received.add(Request(path, headers, body))
      val status = if path == "/v1/traces" || path == "/v1/metrics" then 200 else 404
      exchange.getResponseHeaders.add("Content-Type", "application/x-protobuf")
      exchange.sendResponseHeaders(status, -1)
    finally exchange.close()

object FakeCollector:

  /** Started on a free port of loopback. */
  def apply(): FakeCollector =
    val probe = java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
    val port =
      try probe.getLocalPort
      finally probe.close()
    new FakeCollector(port).start()

  final case class Request(path: String, headers: Vector[(String, String)], body: Array[Byte]):
    def header(name: String): Option[String] =
      headers.collectFirst { case (k, v) if k == name.toLowerCase => v }

  final case class ExportedSpan(
      resource: Map[String, Any],
      traceId: String,
      spanId: String,
      parentSpanId: String,
      name: String,
      kind: Int,
      startNanos: Long,
      endNanos: Long,
      attributes: Map[String, Any],
      statusCode: Int
  ):
    def serviceName: Option[String]             = resource.get("service.name").map(_.toString)
    def attribute(name: String): Option[String] = attributes.get(name).map(_.toString)

  final case class ExportedPoint(
      attributes: Map[String, Any],
      startNanos: Long,
      timeNanos: Long,
      value: Double
  )

  final case class ExportedMetric(
      resource: Map[String, Any],
      name: String,
      unit: String,
      monotonic: Boolean,
      cumulative: Boolean,
      points: Vector[ExportedPoint]
  )

  /** OTLP's span kinds, by their wire numbers. */
  object Kind:
    val Internal = 1
    val Server   = 2
    val Client   = 3
    val Consumer = 5

  /** OTLP's status codes, by their wire numbers. */
  object Status:
    val Unset = 0
    val Ok    = 1
    val Error = 2

  def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  // ExportTraceServiceRequest.resource_spans = 1; ResourceSpans.resource = 1, scope_spans = 2;
  // ScopeSpans.spans = 2.
  def decodeSpans(body: Array[Byte]): Vector[ExportedSpan] =
    for
      resourceSpans <- Protobuf.read(body).messages(1)
      resource = attributesOf(resourceSpans.message(1).map(_.messages(1)).getOrElse(Vector.empty))
      scopeSpans <- resourceSpans.messages(2)
      span       <- scopeSpans.messages(2)
    yield toSpan(resource)(span)

  // Span: trace_id = 1, span_id = 2, parent_span_id = 4, name = 5, kind = 6,
  // start_time_unix_nano = 7, end_time_unix_nano = 8, attributes = 9, status = 15 (code = 3).
  private def toSpan(resource: Map[String, Any])(m: Protobuf.Message): ExportedSpan =
    ExportedSpan(
      resource = resource,
      traceId = hex(m.bytes(1).getOrElse(Array.empty)),
      spanId = hex(m.bytes(2).getOrElse(Array.empty)),
      parentSpanId = hex(m.bytes(4).getOrElse(Array.empty)),
      name = m.string(5).getOrElse(""),
      kind = m.long(6).getOrElse(0L).toInt,
      startNanos = m.long(7).getOrElse(0L),
      endNanos = m.long(8).getOrElse(0L),
      attributes = attributesOf(m.messages(9)),
      statusCode = m.message(15).flatMap(_.long(3)).getOrElse(0L).toInt
    )

  // ExportMetricsServiceRequest.resource_metrics = 1; ResourceMetrics.resource = 1,
  // scope_metrics = 2; ScopeMetrics.metrics = 2; Metric.name = 1, unit = 3, sum = 7;
  // Sum.data_points = 1, aggregation_temporality = 2 (2 is cumulative), is_monotonic = 3.
  def decodeMetrics(body: Array[Byte]): Vector[ExportedMetric] =
    for
      resourceMetrics <- Protobuf.read(body).messages(1)
      resource = attributesOf(resourceMetrics.message(1).map(_.messages(1)).getOrElse(Vector.empty))
      scopeMetrics <- resourceMetrics.messages(2)
      metric       <- scopeMetrics.messages(2)
      sum          <- metric.message(7).toVector
    yield ExportedMetric(
      resource = resource,
      name = metric.string(1).getOrElse(""),
      unit = metric.string(3).getOrElse(""),
      monotonic = sum.long(3).contains(1L),
      cumulative = sum.long(2).contains(2L),
      points = sum.messages(1).map(point)
    )

  // NumberDataPoint: start_time_unix_nano = 2, time_unix_nano = 3, as_double = 4, as_int = 6,
  // attributes = 7.
  private def point(m: Protobuf.Message): ExportedPoint =
    ExportedPoint(
      attributes = attributesOf(m.messages(7)),
      startNanos = m.long(2).getOrElse(0L),
      timeNanos = m.long(3).getOrElse(0L),
      value = m.double(4).orElse(m.long(6).map(_.toDouble)).getOrElse(0.0)
    )

  // KeyValue: key = 1, value = 2; AnyValue: string = 1, bool = 2, int = 3, double = 4.
  private def attributesOf(keyValues: Vector[Protobuf.Message]): Map[String, Any] =
    keyValues.flatMap { kv =>
      for
        key   <- kv.string(1)
        value <- kv.message(2)
        any <- value
          .string(1)
          .orElse(value.long(2).map(_ == 1L))
          .orElse(value.long(3))
          .orElse(value.double(4))
      yield key -> any
    }.toMap

/** Protobuf's wire format, read by field number with no schema. Enough for OTLP, nothing more. */
object Protobuf:

  enum Value:
    case Varint(value: Long)
    case Fixed64(value: Long)
    case Fixed32(value: Int)
    case Bytes(value: Array[Byte])

  final class Message(fields: Vector[(Int, Value)]):
    private def all(n: Int): Vector[Value] = fields.collect { case (`n`, v) => v }

    def long(n: Int): Option[Long] = all(n).lastOption.collect {
      case Value.Varint(v)  => v
      case Value.Fixed64(v) => v
      case Value.Fixed32(v) => v.toLong
    }

    def double(n: Int): Option[Double] = all(n).lastOption.collect { case Value.Fixed64(v) =>
      java.lang.Double.longBitsToDouble(v)
    }

    def bytes(n: Int): Option[Array[Byte]] = all(n).lastOption.collect { case Value.Bytes(b) => b }

    def string(n: Int): Option[String] = bytes(n).map(String(_, "UTF-8"))

    def message(n: Int): Option[Message] = bytes(n).map(read)

    def messages(n: Int): Vector[Message] = all(n).collect { case Value.Bytes(b) => read(b) }

  def read(bytes: Array[Byte]): Message =
    val fields = Vector.newBuilder[(Int, Value)]
    var at     = 0

    def varint(): Long =
      var result = 0L
      var shift  = 0
      var more   = true
      while more do
        val b = bytes(at)
        at += 1
        result |= (b & 0x7fL) << shift
        shift += 7
        more = (b & 0x80) != 0
      result

    def littleEndian(width: Int): Long =
      var result = 0L
      var i      = 0
      while i < width do
        result |= (bytes(at + i) & 0xffL) << (8 * i)
        i += 1
      at += width
      result

    while at < bytes.length do
      val tag    = varint()
      val number = (tag >>> 3).toInt
      val value = (tag & 7).toInt match
        case 0 => Value.Varint(varint())
        case 1 => Value.Fixed64(littleEndian(8))
        case 5 => Value.Fixed32(littleEndian(4).toInt)
        case 2 =>
          val length = varint().toInt
          val slice  = java.util.Arrays.copyOfRange(bytes, at, at + length)
          at += length
          Value.Bytes(slice)
        case other => throw IllegalArgumentException(s"wire type $other is not read here")
      fields += number -> value
    Message(fields.result())
