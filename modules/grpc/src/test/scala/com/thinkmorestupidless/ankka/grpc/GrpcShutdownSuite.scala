package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.http.Acl
import com.typesafe.config.{Config, ConfigFactory}
import io.grpc.stub.{ClientCalls, StreamObserver}
import io.grpc.{CallOptions, ManagedChannel, Status}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source

import java.io.{InputStream, OutputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Promise}
import scala.util.Try

/**
 * Connections that age out, servers that stop, and callers that vanish: none of them may cost a
 * call that could have been answered, and none may leave a stream produced for nobody.
 */
class GrpcShutdownSuite extends munit.FunSuite:

  override val munitTimeout = 2.minutes

  private val system       = ActorSystem("grpc-shutdown-suite")
  private val materializer = Materializer(system)
  private val connections  = AtomicInteger()

  override def afterAll(): Unit = Await.ready(system.terminate(), 10.seconds): Unit

  private final class Slow extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    unary(CartServiceGrpc.METHOD_GET_CART) { request =>
      if request.cartId == "slow" then Thread.sleep(1500)
      Cart(cartId = request.cartId)
    }
    unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))

  private final class Endless(streamEnded: AtomicBoolean)
      extends GrpcEndpoint(CartStreamsGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    serverStream(CartStreamsGrpc.METHOD_WATCH_CART) { _ =>
      Source
        .tick(0.millis, 50.millis, ())
        .map(_ => Cart(cartId = "tick"))
        .watchTermination() { (_, done) =>
          done.onComplete(_ => streamEnded.set(true))(using system.dispatcher)
          org.apache.pekko.NotUsed
        }
    }
    clientStream(CartStreamsGrpc.METHOD_IMPORT_ITEMS)(requests => ImportSummary(requests.size))
    bidiStream(CartStreamsGrpc.METHOD_CONVERSE)(_.asSource)

  private def config(settings: String): Config =
    ConfigFactory.parseString(settings).withFallback(ConfigFactory.load())

  private def serving(settings: String, ended: AtomicBoolean = AtomicBoolean(false)): GrpcServer =
    val server = GrpcServer.at("127.0.0.1", 0)()
    server.serve(
      Vector(Slow(), Endless(ended)),
      "127.0.0.1",
      0,
      config(settings),
      Hosting(Some(materializer))
    )
    server

  private def close(channel: ManagedChannel): Unit =
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit

  test("a connection that ages out is replaced, and no call made across it fails") {
    val server = serving("ankka.grpc.max-connection-age = 1s\nankka.grpc.shutdown-grace = 1s")
    // Through a relay that counts connections, so a renewed one can be seen.
    val relay   = Relay(server.boundPort.get, counting = connections)
    val channel = GrpcChannels.plaintext(relay.port)
    try
      val failures = (1 to 60).count { i =>
        Thread.sleep(100)
        Try(CartServiceGrpc.blockingStub(channel).getCart(GetCartRequest(s"c$i"))).isFailure
      }
      assertEquals(failures, 0)
      assert(
        connections.get > 1,
        s"${connections.get} connection(s) in six seconds with a one-second age"
      )
    finally
      close(channel)
      relay.stop()
      server.stop()
  }

  test("a stopping server lets a call in progress finish, and accepts no new one") {
    val server  = serving("ankka.grpc.shutdown-grace = 5s")
    val channel = GrpcChannels.plaintext(server.boundPort.get)
    try
      val _        = CartServiceGrpc.blockingStub(channel).getCart(GetCartRequest("warm"))
      val answered = Promise[Cart]()
      CartServiceGrpc
        .stub(channel)
        .getCart(GetCartRequest("slow"))
        .onComplete(answered.complete)(using system.dispatcher)
      Thread.sleep(300)
      val stopping = Thread.ofVirtual().start(() => server.stop())
      assertEquals(Await.result(answered.future, 10.seconds).cartId, "slow")
      val late = Try(
        CartServiceGrpc
          .blockingStub(GrpcChannels.plaintext(server.boundPort.getOrElse(1)))
          .getCart(GetCartRequest("late"))
      )
      assert(late.isFailure, late)
      stopping.join()
    finally close(channel)
  }

  test("a stream that outlives the grace ends unavailable, after the grace and not before") {
    val server  = serving("ankka.grpc.shutdown-grace = 1s")
    val channel = GrpcChannels.plaintext(server.boundPort.get)
    try
      val ended   = Promise[Status]()
      val started = CountDownLatch(1)
      ClientCalls.asyncServerStreamingCall(
        channel.newCall(CartStreamsGrpc.METHOD_WATCH_CART, CallOptions.DEFAULT),
        GetCartRequest("c"),
        new StreamObserver[Cart]:
          def onNext(value: Cart): Unit   = started.countDown()
          def onError(t: Throwable): Unit = ended.trySuccess(Status.fromThrowable(t)): Unit
          def onCompleted(): Unit         = ended.trySuccess(Status.OK): Unit
      )
      assert(started.await(5, TimeUnit.SECONDS))
      val stoppedAt = System.nanoTime()
      server.stop()
      val status = Await.result(ended.future, 10.seconds)
      assertEquals(status.getCode, Status.Code.UNAVAILABLE, status.toString)
      assert(System.nanoTime() - stoppedAt >= 900.millis.toNanos, "ended before the grace")
    finally close(channel)
  }

  test("a caller that vanishes without closing is found by keepalive, and its stream stops") {
    def run(settings: String): Boolean =
      val streamEnded = AtomicBoolean(false)
      val server      = serving(settings, streamEnded)
      val relay       = Relay(server.boundPort.get, counting = AtomicInteger())
      val channel     = GrpcChannels.plaintext(relay.port)
      try
        val parts = ClientCalls.blockingServerStreamingCall(
          channel,
          CartStreamsGrpc.METHOD_WATCH_CART,
          CallOptions.DEFAULT,
          GetCartRequest("c")
        )
        parts.next(): Unit
        // Neither side's socket closes; the relay simply stops passing anything either way.
        relay.silence()
        // Measured at about eleven seconds with one-second settings: grpc-java pings no more often
        // than it chooses to, whatever is asked. Thirty seconds is room for that, and still short
        // of anything the default settings would notice.
        val deadline = System.nanoTime() + 30.seconds.toNanos
        while !streamEnded.get && System.nanoTime() < deadline do Thread.sleep(50)
        streamEnded.get
      finally
        close(channel)
        relay.stop()
        server.stop()
    assert(
      run("ankka.grpc.keepalive-time = 1s\nankka.grpc.keepalive-timeout = 1s"),
      "the stream was still produced long after its caller went silent"
    )
    // And the case can fail: with keepalive at its default the same silence goes unnoticed.
    assert(!run(""), "the stream ended though keepalive could not yet have noticed")
  }

  /**
   * A TCP relay a test controls: it counts connections, and can stop forwarding without closing.
   */
  private final class Relay(target: Int, counting: AtomicInteger):
    private val listener          = ServerSocket(0)
    @volatile private var silent  = false
    @volatile private var running = true
    val port: Int                 = listener.getLocalPort

    private val acceptor = Thread.ofVirtual().start { () =>
      while running do
        Try(listener.accept()).foreach { client =>
          counting.incrementAndGet()
          val upstream = Socket()
          upstream.connect(InetSocketAddress("127.0.0.1", target))
          pump(client.getInputStream, upstream.getOutputStream)
          pump(upstream.getInputStream, client.getOutputStream)
        }
    }

    private def pump(from: InputStream, to: OutputStream): Unit =
      Thread.ofVirtual().start { () =>
        val buffer = new Array[Byte](16384)
        Try {
          var n = from.read(buffer)
          while n >= 0 do
            if !silent then
              to.write(buffer, 0, n)
              to.flush()
            n = from.read(buffer)
        }
        Try(to.close()): Unit
      }: Unit

    def silence(): Unit = silent = true

    def stop(): Unit =
      running = false
      Try(listener.close())
      acceptor.interrupt()
