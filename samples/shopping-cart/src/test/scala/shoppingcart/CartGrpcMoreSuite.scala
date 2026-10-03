package shoppingcart

import com.thinkmorestupidless.ankka.grpc.{GrpcChannels, GrpcClients, GrpcServer}
import com.thinkmorestupidless.ankka.http.{Acl, Callers, HttpServer}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import io.grpc.ManagedChannel
import io.grpc.reflection.v1.{
  ServerReflectionGrpc,
  ServerReflectionRequest,
  ServerReflectionResponse
}
import io.grpc.stub.StreamObserver
import shoppingcart.api.{CartGrpcEndpoint, CartStreamsEndpoint, GrpcCallersEndpoint}
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.v1.cart.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Promise}
import scala.jdk.CollectionConverters.*

/**
 * The rest of the cart's gRPC as the sample registers it: its streaming methods, reflection, and
 * calling a service's gRPC endpoint through `GrpcClients`.
 */
class CartGrpcMoreSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val grpc = GrpcServer
    .at("127.0.0.1", 0)(
      clients => CartGrpcEndpoint(clients),
      clients => CartStreamsEndpoint(clients)
    )
    .withReflection(Acl.allowCallers(Callers.internet))
  private val grpcClients = GrpcClients()
  private val http        = HttpServer.at("127.0.0.1", 0)(_ => GrpcCallersEndpoint(grpcClients))

  private var testKit: AnkkaTestKit   = null
  private var channel: ManagedChannel = null

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(Seq(ShoppingCartEntity.descriptor), Seq(grpc, grpcClients, http))
    channel = GrpcChannels.plaintext(grpc.boundPort.getOrElse(fail("the gRPC server did not bind")))

  override def afterAll(): Unit =
    if channel != null then channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS): Unit
    if testKit != null then testKit.stop()

  private def carts = CartServiceGrpc.blockingStub(channel)

  test("watching a cart is sent the cart, and again after it changes") {
    val _        = carts.addItem(AddItemRequest("watch", Some(LineItem("p1", "Widget", 1))))
    val watching = CartStreamsGrpc.blockingStub(channel).watchCart(GetCartRequest("watch"))
    assertEquals(watching.next().items.map(_.productId), Seq("p1"))
    val _ = carts.addItem(AddItemRequest("watch", Some(LineItem("p2", "Gadget", 1))))
    assertEquals(watching.next().items.map(_.productId), Seq("p1", "p2"))
  }

  test("items sent as a stream are all added, and the count answered once") {
    val done = Promise[ImportSummary]()
    val requests = CartStreamsGrpc
      .stub(channel)
      .importItems(new StreamObserver[ImportSummary]:
        def onNext(value: ImportSummary): Unit = done.trySuccess(value): Unit
        def onError(t: Throwable): Unit        = done.tryFailure(t): Unit
        def onCompleted(): Unit                = ())
    (1 to 3).foreach(i =>
      requests.onNext(AddItemRequest("import", Some(LineItem(s"p$i", s"Item $i", 1))))
    )
    requests.onCompleted()
    assertEquals(Await.result(done.future, 30.seconds).added, 3)
    assertEquals(carts.getCart(GetCartRequest("import")).items.size, 3)
  }

  test("a conversation answers each line") {
    val heard = Promise[Vector[String]]()
    var lines = Vector.empty[String]
    val requests = CartStreamsGrpc
      .stub(channel)
      .converse(new StreamObserver[Line]:
        def onNext(value: Line): Unit   = lines :+= value.text
        def onError(t: Throwable): Unit = heard.tryFailure(t): Unit
        def onCompleted(): Unit         = heard.trySuccess(lines): Unit)
    Seq("hello", "again").foreach(t => requests.onNext(Line(t)))
    requests.onCompleted()
    assertEquals(Await.result(heard.future, 30.seconds), Vector("heard: hello", "heard: again"))
  }

  test("the cart answers reflection, listing its service definitions") {
    val listed = Promise[Seq[String]]()
    val requests = ServerReflectionGrpc
      .newStub(channel)
      .serverReflectionInfo(new StreamObserver[ServerReflectionResponse]:
        def onNext(value: ServerReflectionResponse): Unit =
          listed.trySuccess(
            value.getListServicesResponse.getServiceList.asScala.map(_.getName).toSeq
          ): Unit
        def onError(t: Throwable): Unit = listed.tryFailure(t): Unit
        def onCompleted(): Unit         = ())
    requests.onNext(ServerReflectionRequest.newBuilder().setListServices("").build())
    requests.onCompleted()
    val names = Await.result(listed.future, 30.seconds)
    assert(names.contains("shoppingcart.v1.CartService"), names.toString)
    assert(names.contains("shoppingcart.v1.CartStreams"), names.toString)
  }

  test("the cart calls a service's gRPC endpoint through GrpcClients, finding it as it runs") {
    // A service on this machine is found by the name it announced, its actor system's; here the
    // service finds itself, and reads the call as local.
    val port = http.boundPort.getOrElse(fail("no HTTP port"))
    val response = HttpClient
      .newHttpClient()
      .send(
        HttpRequest
          .newBuilder(
            URI.create(s"http://127.0.0.1:$port/callers/grpc/${testKit.service.system.name}")
          )
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    assertEquals((response.statusCode, response.body), (200, "local"))
  }

  test("the cart asks a service for reflection through GrpcClients, and is told what it serves") {
    // The same route the cluster suite drives from another service's pod; here the service asks
    // itself, and a local caller is admitted by every ACL.
    val port = http.boundPort.getOrElse(fail("no HTTP port"))
    val response = HttpClient
      .newHttpClient()
      .send(
        HttpRequest
          .newBuilder(
            URI.create(
              s"http://127.0.0.1:$port/callers/grpc/${testKit.service.system.name}/reflection"
            )
          )
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
    assertEquals(response.statusCode, 200, response.body)
    val listed = response.body.split(",").toSeq
    assert(listed.contains("shoppingcart.v1.CartService"), response.body)
    assert(listed.contains("shoppingcart.v1.CartStreams"), response.body)
  }
