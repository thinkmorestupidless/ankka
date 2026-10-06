package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.http.Acl
import com.thinkmorestupidless.ankka.runtime.{
  Observability,
  RecordedSpan,
  SpanKind,
  SpanOutcome,
  Trace,
  TraceContext,
  Traceparent
}
import com.typesafe.config.ConfigFactory
import io.grpc.stub.{ClientCalls, MetadataUtils, StreamObserver}
import io.grpc.{CallOptions, Channel, ManagedChannel, Metadata}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
 * A call's trace context, read by the gRPC server it arrives at, for every kind of method: the
 * scenarios of `features/observability/trace-across-services.feature` one gRPC endpoint can hold.
 */
class GrpcTraceContextSuite extends munit.FunSuite:

  private val system        = ActorSystem(Behaviors.empty, "grpc-trace-context-suite")
  private val observability = Observability(system)
  private val seen          = AtomicReference[Option[TraceContext]](None)

  private final class Unary extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    unary(CartServiceGrpc.METHOD_GET_CART) { request =>
      seen.set(Trace.currentContext)
      Cart(cartId = request.cartId)
    }
    withAcl(Acl.DenyAll) {
      unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))
    }

  private final class Streaming extends GrpcEndpoint(CartStreamsGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    serverStream(CartStreamsGrpc.METHOD_WATCH_CART)(_ =>
      Source(1 to 2).map(i => Cart(cartId = i.toString))
    )
    clientStream(CartStreamsGrpc.METHOD_IMPORT_ITEMS)(requests => ImportSummary(requests.size))
    bidiStream(CartStreamsGrpc.METHOD_CONVERSE)(_.asSource)

  private val server                  = GrpcServer.at("127.0.0.1", 0)()
  private var channel: ManagedChannel = null

  override def beforeAll(): Unit =
    server.serve(
      Vector(Unary(), Streaming()),
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

  private val fromOutside = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  private val parent      = Traceparent.parse(fromOutside).get

  private def carrying(value: Option[String]): Channel =
    value match
      case None => channel
      case Some(v) =>
        val headers = Metadata()
        headers.put(Metadata.Key.of(Traceparent.Name, Metadata.ASCII_STRING_MARSHALLER), v)
        io.grpc.ClientInterceptors.intercept(
          channel,
          MetadataUtils.newAttachHeadersInterceptor(headers)
        )

  /** The one span a call records, waited for: a span completes as its call ends. */
  private def spanOf(call: => Any): RecordedSpan =
    val before = observability.recorder.snapshot().map(_.spanId).toSet
    Try(call): Unit
    val deadline = System.nanoTime() + 5.seconds.toNanos
    var found    = Vector.empty[RecordedSpan]
    while found.isEmpty && System.nanoTime() < deadline do
      found = observability.recorder.snapshot().filterNot(s => before.contains(s.spanId))
      if found.isEmpty then Thread.sleep(10)
    assertEquals(found.size, 1, found.toString)
    found.head

  private def assertContinued(span: RecordedSpan): Unit =
    assertEquals((span.traceIdHigh, span.traceId), (parent.traceIdHigh, parent.traceId))
    assertEquals(span.parentSpanId, parent.spanId)
    assertEquals(span.kind, SpanKind.Server)

  private def assertNewTrace(span: RecordedSpan): Unit =
    assertEquals(span.parentSpanId, 0L)
    assertNotEquals(span.traceId, parent.traceId)
    assertEquals(span.kind, SpanKind.Server)

  private def callUnary(on: Channel) =
    CartServiceGrpc.blockingStub(on).getCart(GetCartRequest("c1"))

  private def callServerStream(on: Channel) =
    val parts = ClientCalls.blockingServerStreamingCall(
      on,
      CartStreamsGrpc.METHOD_WATCH_CART,
      CallOptions.DEFAULT,
      GetCartRequest("c1")
    )
    while parts.hasNext do parts.next(): Unit

  /** A call whose request is a stream, sent whole and waited on. */
  private def streamed[Req, Res](
      on: Channel,
      method: io.grpc.MethodDescriptor[Req, Res],
      requests: Vector[Req]
  ): Unit =
    val done = CountDownLatch(1)
    val responses = new StreamObserver[Res]:
      def onNext(value: Res): Unit    = ()
      def onError(t: Throwable): Unit = done.countDown()
      def onCompleted(): Unit         = done.countDown()
    val sending =
      if method.getType == io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING then
        ClientCalls.asyncBidiStreamingCall(on.newCall(method, CallOptions.DEFAULT), responses)
      else ClientCalls.asyncClientStreamingCall(on.newCall(method, CallOptions.DEFAULT), responses)
    requests.foreach(sending.onNext)
    sending.onCompleted()
    done.await(5, TimeUnit.SECONDS): Unit

  private val kinds: Vector[(String, Channel => Unit)] = Vector(
    "unary"         -> (on => callUnary(on): Unit),
    "server stream" -> callServerStream,
    "client stream" -> (on =>
      streamed(on, CartStreamsGrpc.METHOD_IMPORT_ITEMS, Vector(AddItemRequest("c1", "Widget", 1)))
    ),
    "both streams" -> (on => streamed(on, CartStreamsGrpc.METHOD_CONVERSE, Vector(Line("hello"))))
  )

  kinds.foreach { (kind, call) =>
    test(
      s"a request that carries a trace context from outside the cluster continues that trace ($kind)"
    ) {
      assertContinued(spanOf(call(carrying(Some(fromOutside)))))
    }
    test(s"a request that carries no trace context starts a new trace ($kind)") {
      assertNewTrace(spanOf(call(carrying(None))))
    }
  }

  test("the handler runs in the continued trace, under the call's span") {
    val span = spanOf(callUnary(carrying(Some(fromOutside))))
    assertEquals(seen.get(), Some(TraceContext(span.traceIdHigh, span.traceId, span.spanId)))
  }

  test("a trace context that cannot be read starts a new trace") {
    assertNewTrace(spanOf(callUnary(carrying(Some("00-nonsense")))))
  }

  test("a call the ACL refuses continues the trace it carried, as refused") {
    val span = spanOf(
      CartServiceGrpc
        .blockingStub(carrying(Some(fromOutside)))
        .addItem(AddItemRequest("c1", "x", 1))
    )
    assertContinued(span)
    assertEquals(span.outcome, SpanOutcome.Refused)
  }
