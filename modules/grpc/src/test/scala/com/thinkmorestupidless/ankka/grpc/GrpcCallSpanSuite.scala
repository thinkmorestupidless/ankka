package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.Acl
import com.thinkmorestupidless.ankka.runtime.{
  CallOrigin,
  Observability,
  RecordedSpan,
  SpanKind,
  SpanOutcome,
  Trace,
  TraceContext
}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.Materializer

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
 * A call one service makes to another's gRPC endpoint is a span of the caller's, under the calling
 * handler's, and the called service continues the trace under that span.
 */
class GrpcCallSpanSuite extends munit.FunSuite:

  // Two actor systems in one JVM: neither forms a cluster, or both would bind the remoting port.
  private val local = ConfigFactory
    .parseString("pekko.actor.provider = local")
    .withFallback(ConfigFactory.load())
  private val callee = ActorSystem(Behaviors.empty, "grpc-call-span-callee", local)
  private val caller = ActorSystem(Behaviors.empty, "grpc-call-span-caller", local)

  private val seen = AtomicReference[Option[TraceContext]](None)

  private final class Carts extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    unary(CartServiceGrpc.METHOD_GET_CART) { request =>
      seen.set(Trace.currentContext)
      request.cartId match
        case "refuse" => throw CommandError("gone", ErrorCode.NotFound)
        case "fail"   => throw RuntimeException("connection reset")
        case id       => Cart(cartId = id)
    }
    unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))

  private val server = GrpcServer.at("127.0.0.1", 0)()

  override def beforeAll(): Unit =
    server.serve(
      Vector(Carts()),
      "127.0.0.1",
      0,
      ConfigFactory.load(),
      Hosting(Some(Materializer(callee)), Some(Observability(callee)))
    )

  override def afterAll(): Unit =
    server.stop()
    Vector(callee, caller).foreach { s =>
      s.terminate()
      Await.ready(s.whenTerminated, 10.seconds): Unit
    }

  private def observability = Observability(caller)

  private def clients() =
    GrpcClients
      .locatedBy((_, _) =>
        Right(
          GrpcClients.Located.Addresses(
            Vector(InetSocketAddress("127.0.0.1", server.boundPort.get)),
            "localhost"
          )
        )
      )
      .configure(
        ConfigFactory.load(),
        Some(observability)
      )

  private def stub(grpc: GrpcClients) = CartServiceGrpc.blockingStub(grpc("carts"))

  /** The caller's spans a call made, the call made from inside a handler's span. */
  private def calledFromHandler(call: => Any): (RecordedSpan, Vector[RecordedSpan]) =
    val recorder = observability.recorder
    val handler =
      recorder.beginRoot(observability.names.intern("checkout"), observability.names.intern("pay"))
    val before = recorder.snapshot().map(_.spanId).toSet
    try Trace.within(handler, CallOrigin("checkout", "pay"))(Try(call)): Unit
    finally recorder.complete(handler, SpanOutcome.Ok)
    val made = recorder.snapshot().filterNot(s => before(s.spanId) || s.spanId == handler.id)
    (recorder.snapshot().find(_.spanId == handler.id).get, made)

  private def named(span: RecordedSpan) =
    (observability.names.nameOf(span.componentRef), observability.names.nameOf(span.handlerRef))

  test(
    "a gRPC call made inside a handler is a span under the handler's, and the callee continues it"
  ) {
    val grpc            = clients()
    val (handler, made) = calledFromHandler(stub(grpc).getCart(GetCartRequest("c1")))
    assertEquals(made.size, 1, made.toString)
    val call = made.head
    assertEquals(call.kind, SpanKind.Client)
    assertEquals(call.parentSpanId, handler.spanId)
    assertEquals((call.traceIdHigh, call.traceId), (handler.traceIdHigh, handler.traceId))
    assertEquals(
      named(call),
      (Some("service:local/carts"), Some("ankka.fixtures.v1.CartService/GetCart"))
    )
    assertEquals(call.outcome, SpanOutcome.Ok)
    val atCallee = seen.get().get
    assertEquals((atCallee.traceIdHigh, atCallee.traceId), (handler.traceIdHigh, handler.traceId))
    grpc.stop()
  }

  test("a call ends as its caller saw it: refused by the service, or failed") {
    val grpc = clients()
    assertEquals(
      calledFromHandler(stub(grpc).getCart(GetCartRequest("refuse")))._2.map(_.outcome),
      Vector(SpanOutcome.Refused)
    )
    assertEquals(
      calledFromHandler(stub(grpc).getCart(GetCartRequest("fail")))._2.map(_.outcome),
      Vector(SpanOutcome.Failed)
    )
    grpc.stop()
  }

  test("methods beyond the limit are recorded together, so no name grows without bound") {
    val services = com.thinkmorestupidless.ankka.runtime.ExternalServices(32, methodLimit = 2)
    assertEquals(services.methodFor("a.S/One"), "a.S/One")
    assertEquals(services.methodFor("a.S/Two"), "a.S/Two")
    assertEquals(services.methodFor("a.S/Three"), "(other methods)")
    assertEquals(services.methodFor("a.S/One"), "a.S/One", "an admitted method keeps its name")
  }
