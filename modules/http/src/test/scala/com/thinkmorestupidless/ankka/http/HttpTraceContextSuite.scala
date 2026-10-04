package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.{
  Observability,
  RecordedSpan,
  SpanKind,
  Trace,
  TraceContext,
  Traceparent
}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * A request's trace context, read by the server it arrives at: the scenarios of
 * `features/observability/trace-across-services.feature` that one HTTP endpoint can hold.
 */
class HttpTraceContextSuite extends munit.FunSuite:

  private given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "http-trace-context")

  /** What the handler's thread was working for, read inside the handler. */
  private val seen = AtomicReference[Option[TraceContext]](None)

  private final class Orders extends HttpEndpoint("/orders"):
    val acl: Acl = Acl.AllowAll
    get("/{id}")((id: String) =>
      seen.set(Trace.currentContext)
      id
    )

  private val server = HttpServer.at("127.0.0.1", 0)()
  private val client = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    server.serve(Vector(new Orders), "127.0.0.1", 0, 5.seconds)

  override def afterAll(): Unit =
    server.stop()
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private def recorder = Observability(system).recorder

  /** Sends one request, and returns the endpoint's span for it and the response's headers. */
  private def request(headers: (String, String)*): (RecordedSpan, HttpResponse[String]) =
    val before = recorder.snapshot().map(_.spanId).toSet
    val builder =
      HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.boundPort.get}/orders/o1"))
    headers.foreach((k, v) => builder.header(k, v))
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    assertEquals(response.statusCode(), 200)
    val names = Observability(system).names
    val spans = recorder
      .snapshot()
      .filterNot(s => before(s.spanId))
      .filter(s => names.nameOf(s.componentRef).contains("http"))
    assertEquals(spans.size, 1, "one request, one endpoint span")
    (spans.head, response)

  private val fromOutside = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

  test("a request that carries a trace context from outside the cluster continues that trace") {
    val (span, _) = request(Traceparent.Name -> fromOutside)
    val parent    = Traceparent.parse(fromOutside).get
    assertEquals((span.traceIdHigh, span.traceId), (parent.traceIdHigh, parent.traceId))
    assertEquals(span.parentSpanId, parent.spanId)
    assertEquals(span.kind, SpanKind.Server)
    assert(!span.callerUnknown)
  }

  test("what the endpoint calls is under the endpoint's span, in the continued trace") {
    val (span, _) = request(Traceparent.Name -> fromOutside)
    assertEquals(seen.get(), Some(TraceContext(span.traceIdHigh, span.traceId, span.spanId)))
  }

  test("a request that carries no trace context starts a new trace") {
    val (span, _) = request()
    assertEquals(span.parentSpanId, 0L)
    assertNotEquals(span.traceId, 0L)
    assertNotEquals(span.traceIdHigh, Traceparent.parse(fromOutside).get.traceIdHigh)
    assertEquals(span.kind, SpanKind.Server)
  }

  test("a request whose trace context cannot be read starts a new trace, and nothing is echoed") {
    val (span, response) = request(Traceparent.Name -> "00-nonsense")
    assertEquals(span.parentSpanId, 0L)
    assertEquals(response.headers().firstValue(Traceparent.Name).isPresent, false)
  }
