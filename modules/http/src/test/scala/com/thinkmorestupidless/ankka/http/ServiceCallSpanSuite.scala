package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.{
  CallOrigin,
  HttpServiceClients,
  Observability,
  RecordedSpan,
  SpanKind,
  SpanOutcome,
  Trace,
  Traceparent
}
import com.thinkmorestupidless.ankka.sdk.{ServiceCallFailed, ServiceUnresolvable}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * A call one service makes to another over HTTP is a span of the caller's, under the calling
 * handler's, and the request carries that span's trace context for the callee to continue.
 */
class ServiceCallSpanSuite extends munit.FunSuite:

  // Two actor systems in one JVM: neither forms a cluster, or both would bind the remoting port.
  private val local = ConfigFactory
    .parseString("pekko.actor.provider = local")
    .withFallback(ConfigFactory.load())
  private given callee: ActorSystem[Nothing] =
    ActorSystem(Behaviors.empty, "call-span-callee", local)
  private val caller = ActorSystem(Behaviors.empty, "call-span-caller", local)

  private val received = AtomicReference[Option[String]](None)

  private final class Payments extends HttpEndpoint("/payments"):
    val acl: Acl = Acl.AllowAll
    get("/{answer}")((answer: String) =>
      received.set(request.header(Traceparent.Name))
      answer match
        case "missing" => throw HttpProblem.notFound("no such payment")
        case "broken"  => throw HttpProblem(500, "broken")

        case other => other
    )

  private val server = HttpServer.at("127.0.0.1", 0)()

  override def beforeAll(): Unit =
    server.serve(Vector(new Payments), "127.0.0.1", 0, 5.seconds)

  override def afterAll(): Unit =
    server.stop()
    Vector(callee, caller).foreach { s =>
      s.terminate()
      Await.ready(s.whenTerminated, 10.seconds): Unit
    }

  private def observability = Observability(caller)

  private def clients(extra: String = "") =
    HttpServiceClients(
      ConfigFactory
        .parseString(
          s"""ankka.local-services.payments = "http://127.0.0.1:${server.boundPort.get}"
             |$extra""".stripMargin
        )
        .withFallback(ConfigFactory.load()),
      None,
      observability = Some(observability)
    )

  /** The caller's spans a call made, the call made from inside a handler's span. */
  private def calledFromHandler(call: => Any): (RecordedSpan, Vector[RecordedSpan]) =
    val recorder = observability.recorder
    val handler =
      recorder.beginRoot(observability.names.intern("checkout"), observability.names.intern("pay"))
    val before = recorder.snapshot().map(_.spanId).toSet
    try Trace.within(handler, CallOrigin("checkout", "pay"))(scala.util.Try(call)): Unit
    finally recorder.complete(handler, SpanOutcome.Ok)
    val made = recorder.snapshot().filterNot(s => before(s.spanId) || s.spanId == handler.id)
    (recorder.snapshot().find(_.spanId == handler.id).get, made)

  private def named(span: RecordedSpan) =
    (observability.names.nameOf(span.componentRef), observability.names.nameOf(span.handlerRef))

  test(
    "a call made inside a handler is a span under the handler's, and the callee is sent that span"
  ) {
    val (handler, made) = calledFromHandler(clients()("payments").getText("/payments/ok"))
    assertEquals(made.size, 1, made.toString)
    val call = made.head
    assertEquals(call.kind, SpanKind.Client)
    assertEquals((call.traceIdHigh, call.traceId), (handler.traceIdHigh, handler.traceId))
    assertEquals(call.parentSpanId, handler.spanId)
    assertEquals(named(call), (Some("service:local/payments"), Some("GET")))
    assertEquals(call.outcome, SpanOutcome.Ok)
    val sent = received.get().flatMap(Traceparent.parse).get
    assertEquals(sent.spanId, call.spanId, "the callee's parent is the call, not the handler")
    assertEquals(sent.traceId, handler.traceId)
  }

  test("a trace context the handler supplied is replaced by the platform's") {
    val forwarded = "00-11111111111111111111111111111111-2222222222222222-01"
    val (_, made) = calledFromHandler(
      clients()("payments")
        .request("GET", "/payments/ok", None, None, Seq("Traceparent" -> forwarded))
    )
    assertEquals(received.get().flatMap(Traceparent.parse).map(_.spanId), Some(made.head.spanId))
  }

  test("a call ends as its caller saw it: refused, failed, timed out, or never delivered") {
    def outcome(call: => Any) = calledFromHandler(call)._2.map(_.outcome)
    assertEquals(
      outcome(intercept[ServiceCallFailed](clients()("payments").getText("/payments/missing"))),
      Vector(SpanOutcome.Refused)
    )
    assertEquals(
      outcome(intercept[ServiceCallFailed](clients()("payments").getText("/payments/broken"))),
      Vector(SpanOutcome.Failed)
    )
    assertEquals(
      outcome(intercept[ServiceUnresolvable](clients()("nowhere").getText("/"))),
      Vector(SpanOutcome.Failed)
    )
  }

  test("a call made outside any handler is a root whose caller is unknown") {
    val before = observability.recorder.snapshot().map(_.spanId).toSet
    clients()("payments").getText("/payments/ok"): Unit
    val made = observability.recorder.snapshot().filterNot(s => before(s.spanId))
    assertEquals(made.size, 1)
    assertEquals(made.head.parentSpanId, 0L)
    assert(made.head.callerUnknown)
  }

  test("a path with an id in it names nothing: a hundred paths add no name") {
    val c = clients()
    c("payments").getText("/payments/warm-up"): Unit
    val size = observability.names.size
    (1 to 100).foreach(i => c("payments").getText(s"/payments/p-$i"): Unit)
    assertEquals(observability.names.size, size)
  }
