package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import io.grpc.ManagedChannel
import io.grpc.stub.{ClientCallStreamObserver, StreamObserver}
import org.apache.pekko.stream.scaladsl.Source

import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.DurationInt

/**
 * SC-007 and SC-008, measured.
 *
 * SC-007's denominator is the thing the criterion names: the same call — reach the entity, read it,
 * answer — made over HTTP to the same service on the same machine, through real clients to real
 * ports. Anything narrower (an empty handler, a serializer round-trip) measures the harness.
 *
 * SC-008: two 100,000-part streams, one each way, against a reader that keeps pace with nothing but
 * the transport; the heap after a full collection is no larger at the end than after the first
 * thousand parts plus a fixed margin, so memory does not grow with parts not yet read.
 *
 * Ignored unless `-Dankka.benchmarks=on`:
 * `sbt -Dankka.benchmarks=on 'grpc/testOnly *GrpcBenchmark'`.
 */
class GrpcBenchmark extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 10.minutes

  override def munitIgnore: Boolean = !sys.props.get("ankka.benchmarks").contains("on")

  private val Calls  = 2000
  private val Warmup = 500
  private val Parts  = 100000
  // What a heap may move by between two full collections for reasons that are not this stream.
  private val MarginBytes = 32L * 1024 * 1024

  private final class Grpc(clients: EndpointClients) extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    unary(CartServiceGrpc.METHOD_GET_CART) { request =>
      val state = clients.componentClient
        .forKeyValueEntity(EntityId(request.cartId))
        .call(FixtureCart.get)
        .invoke()
      Cart(request.cartId, state.items.map(Item(_, 1)))
    }
    unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))

  private final class Http(clients: EndpointClients) extends HttpEndpoint("/carts"):
    val acl: Acl = Acl.AllowAll
    get("/{cartId}") { (cartId: String) =>
      val state =
        clients.componentClient.forKeyValueEntity(EntityId(cartId)).call(FixtureCart.get).invoke()
      state.items.mkString(",")
    }

  private final class Streams extends GrpcEndpoint(CartStreamsGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    serverStream(CartStreamsGrpc.METHOD_WATCH_CART)(_ =>
      Source(1 to Parts).map(i => Cart(cartId = i.toString))
    )
    clientStream(CartStreamsGrpc.METHOD_IMPORT_ITEMS) { requests =>
      var count = 0
      requests.foreach { _ =>
        count += 1
        if count == 1000 then heapAtThousand = settledHeap()
        // A handler that reads slowly: the caller may send no faster than this.
        if count % 1000 == 0 then Thread.sleep(1)
      }
      ImportSummary(count)
    }
    bidiStream(CartStreamsGrpc.METHOD_CONVERSE)(_.asSource)

  @volatile private var heapAtThousand = 0L

  private val grpcServer              = GrpcServer.at("127.0.0.1", 0)(Grpc(_), _ => Streams())
  private val httpServer              = HttpServer.at("127.0.0.1", 0)(Http(_))
  private var testKit: AnkkaTestKit   = null
  private var channel: ManagedChannel = null

  override def beforeAll(): Unit =
    super.beforeAll()
    if !munitIgnore then
      testKit = AnkkaTestKit.start(Seq(FixtureCart.descriptor), Seq(grpcServer, httpServer))
      channel = GrpcChannels.plaintext(grpcServer.boundPort.getOrElse(fail("gRPC did not bind")))

  override def afterAll(): Unit =
    if channel != null then channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
    if testKit != null then testKit.stop()
    super.afterAll()

  private def median(samples: Vector[Long]): Long = samples.sorted.apply(samples.size / 2)

  private def settledHeap(): Long =
    val memory = ManagementFactory.getMemoryMXBean
    (1 to 3).foreach { _ =>
      System.gc()
      Thread.sleep(50)
    }
    memory.getHeapMemoryUsage.getUsed

  test(
    "SC-007: a unary gRPC call to an entity is no slower at the median than the same over HTTP"
  ) {
    val stub = CartServiceGrpc.blockingStub(channel)
    val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    val port = httpServer.boundPort.getOrElse(fail("HTTP did not bind"))
    val get  = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/carts/bench")).build()

    def timed(call: => Unit): Long =
      val start = System.nanoTime()
      call
      System.nanoTime() - start
    def overGrpc(): Unit = stub.getCart(GetCartRequest("bench")): Unit
    def overHttp(): Unit =
      val response = http.send(get, HttpResponse.BodyHandlers.ofString())
      assertEquals(response.statusCode(), 200)

    (1 to Warmup).foreach { _ =>
      overGrpc()
      overHttp()
    }
    // Interleaved, so neither protocol has the machine to itself for its whole run.
    val pairs   = Vector.fill(Calls)((timed(overGrpc()), timed(overHttp())))
    val grpc    = median(pairs.map(_._1))
    val viaHttp = median(pairs.map(_._2))
    println(
      f"SC-007 median of $Calls calls: gRPC ${grpc / 1000.0}%.0fµs, HTTP ${viaHttp / 1000.0}%.0fµs"
    )
    assert(grpc <= viaHttp, s"gRPC ${grpc}ns at the median, HTTP ${viaHttp}ns")
  }

  test("SC-008: 100,000 parts to a caller, and from one, without the heap growing with them") {
    // To the caller, which reads each part as it comes and asks for the next.
    val received   = AtomicInteger()
    val done       = CountDownLatch(1)
    var atThousand = 0L
    CartStreamsGrpc
      .stub(channel)
      .watchCart(
        GetCartRequest("big"),
        new StreamObserver[Cart]:
          def onNext(value: Cart): Unit =
            if received.incrementAndGet() == 1000 then atThousand = settledHeap()
          def onError(t: Throwable): Unit = done.countDown()
          def onCompleted(): Unit         = done.countDown()
      )
    assert(done.await(5, TimeUnit.MINUTES), s"the stream did not end; ${received.get} parts")
    val outAtEnd = settledHeap()
    assertEquals(received.get, Parts)

    // From the caller, to a handler that reads slowly.
    val summary = scala.concurrent.Promise[ImportSummary]()
    val requests = CartStreamsGrpc
      .stub(channel)
      .importItems(new StreamObserver[ImportSummary]:
        def onNext(value: ImportSummary): Unit = summary.trySuccess(value): Unit
        def onError(t: Throwable): Unit        = summary.tryFailure(t): Unit
        def onCompleted(): Unit                = ())
    // Sent only as the transport makes room, so what is measured is the service's memory and not
    // a sender queueing the whole stream in this JVM.
    val sender = requests.asInstanceOf[ClientCallStreamObserver[AddItemRequest]]
    (1 to Parts).foreach { i =>
      while !sender.isReady do Thread.sleep(0, 100_000)
      sender.onNext(AddItemRequest("c", s"p$i", 1))
    }
    requests.onCompleted()
    val added   = scala.concurrent.Await.result(summary.future, 5.minutes).count
    val inAtEnd = settledHeap()
    assertEquals(added, Parts)

    println(
      f"SC-008 heap after 1,000 / after 100,000: to the caller ${atThousand / 1e6}%.1fMB / ${outAtEnd / 1e6}%.1fMB; " +
        f"from the caller ${heapAtThousand / 1e6}%.1fMB / ${inAtEnd / 1e6}%.1fMB"
    )
    assert(outAtEnd <= atThousand + MarginBytes, s"to the caller: $atThousand → $outAtEnd")
    assert(inAtEnd <= heapAtThousand + MarginBytes, s"from the caller: $heapAtThousand → $inAtEnd")
  }
