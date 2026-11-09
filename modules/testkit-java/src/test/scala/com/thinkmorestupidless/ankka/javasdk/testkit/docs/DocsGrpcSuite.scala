package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.javasdk.cart.LineItem
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, Settings}
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.grpc.stub.{ClientCalls, StreamObserver}
import io.grpc.{CallOptions, ManagedChannel, ManagedChannelBuilder}

import java.util.concurrent.{CompletableFuture, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** The Java samples of the gRPC endpoints page, against the cart service they declare. */
class DocsGrpcSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit       = null
  private var orders: AnkkaTestKit    = null
  private var channel: ManagedChannel = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(GrpcChecks.cartService())
    channel = ManagedChannelBuilder.forTarget(kit.grpcAddress()).usePlaintext().build()
    orders = AnkkaTestKit.start(
      GrpcChecks.ordersService(),
      Settings.defaults().withConfig("ankka.local-grpc-services.cart", kit.grpcAddress())
    )

  override def afterAll(): Unit =
    try if channel != null then channel.shutdownNow(): Unit
    finally
      try if orders != null then orders.close()
      finally if kit != null then kit.close()

  private def add(cartId: String, item: LineItem) =
    ClientCalls.blockingUnaryCall(
      channel,
      CartApi.ADD_ITEM,
      CallOptions.DEFAULT,
      CartApi.AddItemRequest(cartId, item)
    )

  private final class Collect[A] extends StreamObserver[A]:
    val values                   = LinkedBlockingQueue[A]()
    val done                     = CompletableFuture[Unit]()
    def onNext(value: A): Unit   = values.add(value): Unit
    def onError(t: Throwable): Unit = done.completeExceptionally(t): Unit
    def onCompleted(): Unit      = done.complete(()): Unit

  test("the test starts the service, picks the port and reads back what it added") {
    val cart = GrpcChecks.addThenRead()
    assertEquals(cart.items.asScala.toList, List(LineItem("p1", "Widget", 2)))
  }

  test("a server stream sends the cart's lines, one part each") {
    add("s-1", LineItem("a", "A", 1)): Unit
    add("s-1", LineItem("b", "B", 2)): Unit
    val parts = ClientCalls
      .blockingServerStreamingCall(
        channel,
        CartApi.LIST_ITEMS,
        CallOptions.DEFAULT,
        CartApi.GetCartRequest("s-1")
      )
      .asScala
      .toList
    assertEquals(parts, List(LineItem("a", "A", 1), LineItem("b", "B", 2)))
  }

  test("a client stream adds each item as it arrives and answers once the stream ends") {
    val answer   = Collect[CartApi.ImportSummary]()
    val requests = ClientCalls.asyncClientStreamingCall(
      channel.newCall(CartApi.IMPORT_ITEMS, CallOptions.DEFAULT),
      answer
    )
    requests.onNext(CartApi.AddItemRequest("i-1", LineItem("a", "A", 1)))
    requests.onNext(CartApi.AddItemRequest("i-1", LineItem("b", "B", 3)))
    requests.onCompleted()
    answer.done.get(20, TimeUnit.SECONDS)
    assertEquals(answer.values.asScala.toList, List(CartApi.ImportSummary(2)))
    val cart = ClientCalls.blockingUnaryCall(
      channel,
      CartApi.GET_CART,
      CallOptions.DEFAULT,
      CartApi.GetCartRequest("i-1")
    )
    assertEquals(cart.items.asScala.map(_.quantity).sum, 4)
  }

  test("a conversation answers each line as it arrives") {
    val answers  = Collect[CartApi.Line]()
    val requests = ClientCalls.asyncBidiStreamingCall(
      channel.newCall(CartApi.CONVERSE, CallOptions.DEFAULT),
      answers
    )
    requests.onNext(CartApi.Line("one"))
    assertEquals(answers.values.poll(20, TimeUnit.SECONDS), CartApi.Line("heard: one"))
    requests.onNext(CartApi.Line("two"))
    assertEquals(answers.values.poll(20, TimeUnit.SECONDS), CartApi.Line("heard: two"))
    requests.onCompleted()
    answers.done.get(20, TimeUnit.SECONDS)
  }

  test("another service reaches the cart's gRPC through its clients, by name") {
    add("o-1", LineItem("p9", "Nine", 9)): Unit
    val cart = GrpcChecks.readCart(orders.grpcClients(), "o-1")
    assertEquals(cart.items.asScala.toList, List(LineItem("p9", "Nine", 9)))
  }
