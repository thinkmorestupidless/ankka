package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.http.Acl
import com.typesafe.config.ConfigFactory
import io.grpc.stub.{ClientCalls, StreamObserver}
import io.grpc.{CallOptions, ManagedChannel}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Promise}

/**
 * Streams in both directions, whole: every part delivered, in order, however slowly the other side
 * reads — and nothing produced or accepted beyond what the reader has made room for.
 *
 * On a server of its own with no test kit, so it needs no database.
 */
class GrpcFlowControlSuite extends munit.FunSuite:

  override val munitTimeout = 2.minutes

  private val system       = ActorSystem("grpc-flow-control-suite")
  private val materializer = Materializer(system)
  private val produced     = AtomicInteger()
  private val stopped      = AtomicBoolean(false)
  private val readGate     = CountDownLatch(1)
  private val read         = AtomicInteger()

  private val Parts = 100000

  /**
   * Parts large enough that what the transport may buffer is a few hundred of them. The HTTP/2
   * window is counted in bytes and grows to megabytes, so a stream of tiny parts can be produced
   * whole before anyone reads it, correctly; backpressure shows only once parts outgrow the window.
   */
  private val LargeParts = 2000
  private val Padding    = "x" * 16384

  private final class Streams extends GrpcEndpoint(CartStreamsGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    serverStream(CartStreamsGrpc.METHOD_WATCH_CART) { request =>
      val endless = request.cartId == "endless"
      (if endless then Source.repeat(0) else Source(1 to LargeParts))
        .map { i =>
          produced.incrementAndGet()
          Cart(cartId = s"$i:$Padding")
        }
        .watchTermination() { (_, done) =>
          done.onComplete(_ => stopped.set(true))(using system.dispatcher)
          org.apache.pekko.NotUsed
        }
    }
    clientStream(CartStreamsGrpc.METHOD_IMPORT_ITEMS) { requests =>
      var count = 0
      requests.foreach { item =>
        if count == 10 then readGate.await(60, TimeUnit.SECONDS): Unit
        assertEquals(item.quantity, count, "a part arrived out of order")
        count += 1
        read.set(count)
      }
      ImportSummary(count)
    }
    bidiStream(CartStreamsGrpc.METHOD_CONVERSE)(_.asSource)

  private val server                  = GrpcServer.at("127.0.0.1", 0)()
  private var channel: ManagedChannel = null

  override def beforeAll(): Unit =
    server.serve(
      Vector(Streams()),
      "127.0.0.1",
      0,
      ConfigFactory.load(),
      Hosting(Some(materializer))
    )
    channel = GrpcChannels.plaintext(server.boundPort.getOrElse(fail("not bound")))

  override def afterAll(): Unit =
    if channel != null then channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
    server.stop()
    Await.ready(system.terminate(), 10.seconds): Unit

  test("a stream to a reader that pauses stops producing, then arrives whole and in order") {
    produced.set(0)
    val parts = ClientCalls.blockingServerStreamingCall(
      channel,
      CartStreamsGrpc.METHOD_WATCH_CART,
      CallOptions.DEFAULT,
      GetCartRequest("whole")
    )
    def index(cart: Cart) = cart.cartId.takeWhile(_ != ':').toInt
    (1 to 10).foreach(i => assertEquals(index(parts.next()), i))
    Thread.sleep(1000)
    assert(produced.get < 1000, s"${produced.get} of $LargeParts parts were produced for 10 read")
    (11 to LargeParts).foreach(i => assertEquals(index(parts.next()), i))
    assert(!parts.hasNext)
  }

  test("a caller that goes away stops the stream within a second") {
    stopped.set(false)
    val own = GrpcChannels.plaintext(server.boundPort.get)
    val parts = ClientCalls.blockingServerStreamingCall(
      own,
      CartStreamsGrpc.METHOD_WATCH_CART,
      CallOptions.DEFAULT,
      GetCartRequest("endless")
    )
    parts.next(): Unit
    parts.next(): Unit
    own.shutdownNow()
    val deadline = System.nanoTime() + 1.second.toNanos
    while !stopped.get && System.nanoTime() < deadline do Thread.sleep(10)
    assert(stopped.get, "the stream was still being produced a second after its caller left")
  }

  test("100,000 parts sent to a handler that pauses are all read, in order, once it resumes") {
    val done = Promise[ImportSummary]()
    val requests = CartStreamsGrpc
      .stub(channel)
      .importItems(new StreamObserver[ImportSummary]:
        def onNext(value: ImportSummary): Unit = done.trySuccess(value): Unit
        def onError(t: Throwable): Unit        = done.tryFailure(t): Unit
        def onCompleted(): Unit                = ())
    val sender = Thread.ofVirtual().start { () =>
      (0 until Parts).foreach(i => requests.onNext(AddItemRequest("c", s"p$i", i)))
      requests.onCompleted()
    }
    // The async stub queues what the transport will not take, so this asserts the handler is held
    // at ten, not the sender: nothing reaches it until it asks.
    Thread.sleep(500)
    assertEquals(read.get, 10)
    readGate.countDown()
    assertEquals(Await.result(done.future, 60.seconds).count, Parts)
    sender.join()
  }

  test("a conversation answers every part in order") {
    val lines    = (1 to 1000).map(i => Line(s"l$i", i))
    val answered = Promise[Vector[Line]]()
    var heard    = Vector.empty[Line]
    val requests = CartStreamsGrpc
      .stub(channel)
      .converse(new StreamObserver[Line]:
        def onNext(value: Line): Unit   = heard :+= value
        def onError(t: Throwable): Unit = answered.tryFailure(t): Unit
        def onCompleted(): Unit         = answered.trySuccess(heard): Unit)
    lines.foreach(requests.onNext)
    requests.onCompleted()
    assertEquals(Await.result(answered.future, 30.seconds), lines.toVector)
  }
