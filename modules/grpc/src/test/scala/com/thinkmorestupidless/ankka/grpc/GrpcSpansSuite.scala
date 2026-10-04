package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.Acl
import com.thinkmorestupidless.ankka.runtime.{Observability, RecordedSpan, SpanOutcome, Trace}
import com.typesafe.config.ConfigFactory
import io.grpc.stub.ClientCalls
import io.grpc.{CallOptions, ManagedChannel, MethodDescriptor, StatusRuntimeException}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
 * What a gRPC call records: one root span per call to a declared method, named for the method, with
 * the work it does nested beneath it, and an outcome that tells a refusal from a fault. On a server
 * and an actor system of its own, so it needs no database; `traces.feature` shows the same through
 * a whole service.
 */
class GrpcSpansSuite extends munit.FunSuite:

  private val system        = ActorSystem(Behaviors.empty, "grpc-spans-suite")
  private val observability = Observability(system)
  private val seenTrace     = AtomicReference[Option[(Long, Long)]](None)

  private final class Traced extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    unary(CartServiceGrpc.METHOD_GET_CART) { request =>
      seenTrace.set(Trace.currentTrace)
      request.cartId match
        case "refuse" => throw CommandError("gone", ErrorCode.NotFound)
        case "fail"   => throw RuntimeException("connection reset")
        case id       => Cart(cartId = id)
    }
    withAcl(Acl.DenyAll) {
      unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))
    }

  private final class Streaming extends GrpcEndpoint(CartStreamsGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    serverStream(CartStreamsGrpc.METHOD_WATCH_CART)(_ =>
      Source(1 to 3).map(i => Cart(cartId = i.toString))
    )
    clientStream(CartStreamsGrpc.METHOD_IMPORT_ITEMS)(requests => ImportSummary(requests.size))
    bidiStream(CartStreamsGrpc.METHOD_CONVERSE)(_.asSource)

  private val server                  = GrpcServer.at("127.0.0.1", 0)()
  private var channel: ManagedChannel = null

  override def beforeAll(): Unit =
    server.serve(
      Vector(Traced(), Streaming()),
      "127.0.0.1",
      0,
      ConfigFactory.load(),
      Hosting(Some(Materializer(system)), Some(observability))
    )
    channel = GrpcChannels.plaintext(server.boundPort.getOrElse(fail("not bound")))

  override def afterAll(): Unit =
    if channel != null then channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
    server.stop()
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private def spansAfter(call: => Any): Vector[RecordedSpan] =
    val before = observability.recorder.snapshot().map(_.spanId).toSet
    Try(call): Unit
    // A span completes as its call ends, which the caller may hear first.
    val deadline = System.nanoTime() + 5.seconds.toNanos
    var found    = Vector.empty[RecordedSpan]
    while found.isEmpty && System.nanoTime() < deadline do
      found = observability.recorder.snapshot().filterNot(s => before.contains(s.spanId))
      if found.isEmpty then Thread.sleep(10)
    found

  private def named(span: RecordedSpan): (Option[String], Option[String]) =
    (observability.names.nameOf(span.componentRef), observability.names.nameOf(span.handlerRef))

  private def stub = CartServiceGrpc.blockingStub(channel)

  test("a call is one root span named for its method, and the handler runs inside it") {
    val spans = spansAfter(stub.getCart(GetCartRequest("c1")))
    assertEquals(spans.size, 1, spans.toString)
    val span = spans.head
    assertEquals(named(span), (Some("grpc"), Some("ankka.fixtures.v1.CartService/GetCart")))
    assertEquals(span.parentSpanId, 0L)
    assertEquals(span.outcome, SpanOutcome.Ok)
    // What the handler calls nests here: it ran with this span as the current trace.
    assertEquals(seenTrace.get, Some(span.traceId -> span.spanId))
  }

  test("a refusal by a component is recorded as refused, a fault as failed") {
    assertEquals(
      spansAfter(stub.getCart(GetCartRequest("refuse"))).map(_.outcome),
      Vector(SpanOutcome.Refused)
    )
    assertEquals(
      spansAfter(stub.getCart(GetCartRequest("fail"))).map(_.outcome),
      Vector(SpanOutcome.Failed)
    )
  }

  test("a call the ACL refuses is recorded as refused, though no handler ran") {
    val spans = spansAfter(stub.addItem(AddItemRequest("c1", "Widget", 1)))
    assertEquals(
      spans.map(named),
      Vector((Some("grpc"), Some("ankka.fixtures.v1.CartService/AddItem")))
    )
    assertEquals(spans.map(_.outcome), Vector(SpanOutcome.Refused))
  }

  test("a call answered with a stream is one span, complete when the stream ends") {
    val spans = spansAfter {
      val parts = ClientCalls.blockingServerStreamingCall(
        channel,
        CartStreamsGrpc.METHOD_WATCH_CART,
        CallOptions.DEFAULT,
        GetCartRequest("c1")
      )
      while parts.hasNext do parts.next(): Unit
    }
    assertEquals(spans.map(_.outcome), Vector(SpanOutcome.Ok))
  }

  test("calls to methods nobody declared record nothing and add no names") {
    def size   = Iterator.from(0).takeWhile(i => observability.names.nameOf(i).isDefined).size
    val before = size
    (1 to 200).foreach { i =>
      val invented = MethodDescriptor
        .newBuilder(Binding.bytes, Binding.bytes)
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(s"ankka.fixtures.v1.CartService/Invented$i")
        .build()
      Try(
        ClientCalls.blockingUnaryCall(channel, invented, CallOptions.DEFAULT, Array.emptyByteArray)
      ): Unit
    }
    assertEquals(size, before)
  }

  test("the methods served are listed for the local console, streaming ones marked") {
    val routes = server.routes.map(r => (r.method, r.path, r.streaming))
    assert(
      routes.contains(("GRPC", "ankka.fixtures.v1.CartService/GetCart", false)),
      routes.toString
    )
    assert(
      routes.contains(("GRPC", "ankka.fixtures.v1.CartStreams/WatchCart", true)),
      routes.toString
    )
    intercept[StatusRuntimeException](stub.addItem(AddItemRequest("c", "p", 1))): Unit
  }
