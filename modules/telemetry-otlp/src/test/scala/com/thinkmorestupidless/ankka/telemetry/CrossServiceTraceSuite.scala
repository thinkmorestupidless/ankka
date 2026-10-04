package com.thinkmorestupidless.ankka.telemetry

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.grpc.{GrpcClients, GrpcEndpoint, GrpcServer}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.*

/** `payments`: an HTTP endpoint and a gRPC endpoint, each of which calls its own entity. */
final class PaymentsHttp(clients: EndpointClients) extends HttpEndpoint("/payments"):
  val acl: Acl = Acl.AllowAll
  get("/{id}")((id: String) =>
    clients.componentClient.forKeyValueEntity(EntityId(id)).call(CartEntity.addItem).invoke("paid")
  )

final class PaymentsGrpc(clients: EndpointClients) extends GrpcEndpoint(CartServiceGrpc.SERVICE):
  val acl: Acl = Acl.AllowAll
  unary(CartServiceGrpc.METHOD_GET_CART) { request =>
    clients.componentClient
      .forKeyValueEntity(EntityId(request.cartId))
      .call(CartEntity.addItem)
      .invoke("paid"): Unit
    Cart(cartId = request.cartId)
  }
  unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))

/** `orders`: an endpoint that calls `payments`, by HTTP or by gRPC. */
final class OrdersHttp(clients: EndpointClients, grpc: GrpcClients) extends HttpEndpoint("/orders"):
  val acl: Acl = Acl.AllowAll
  get("/http/{id}")((id: String) => clients.services("payments").getText(s"/payments/$id"))
  get("/grpc/{id}")((id: String) =>
    CartServiceGrpc.blockingStub(grpc("payments")).getCart(GetCartRequest(id)).cartId
  )

/**
 * One request that crosses from one service to another is one trace in the collector, whether the
 * call is HTTP or gRPC: two whole services in one JVM, exporting to one collector.
 */
class CrossServiceTraceSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private val collector = FakeCollector()

  private val paymentsHttp = HttpServer.at("127.0.0.1", 0)(c => PaymentsHttp(c))
  private val paymentsGrpc = GrpcServer.at("127.0.0.1", 0)(c => PaymentsGrpc(c))
  private val ordersHttp = (grpc: GrpcClients) =>
    HttpServer.at("127.0.0.1", 0)(c => OrdersHttp(c, grpc))

  private var payments: AnkkaTestKit   = null
  private var orders: AnkkaTestKit     = null
  private var ordersServer: HttpServer = null

  private def telemetry(service: String) =
    s"""ankka.telemetry.endpoint = "${collector.address}"
       |ankka.telemetry.interval = 100ms
       |ankka.telemetry.service-name = $service
       |ankka.telemetry.project = shop""".stripMargin

  override def beforeAll(): Unit =
    payments = AnkkaTestKit.start(
      Seq(CartEntity.descriptor),
      Seq(paymentsHttp, paymentsGrpc),
      settings = ConfigFactory.parseString(telemetry("payments"))
    )
    val grpc = GrpcClients()
    ordersServer = ordersHttp(grpc)
    orders = AnkkaTestKit.start(
      Seq(CartEntity.descriptor),
      Seq(grpc, ordersServer),
      settings = ConfigFactory.parseString(
        telemetry("orders") +
          s"""
             |ankka.local-services.payments = "http://127.0.0.1:${paymentsHttp.boundPort.get}"
             |ankka.local-grpc-services.payments = "127.0.0.1:${paymentsGrpc.boundPort.get}"
             |""".stripMargin
      )
    )

  override def afterAll(): Unit =
    if orders != null then orders.stop()
    if payments != null then payments.stop()
    collector.stop()

  private val client = HttpClient.newHttpClient()

  private def get(path: String): String =
    val response = client.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${ordersServer.boundPort.get}$path"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    assertEquals(response.statusCode(), 200, response.body())
    response.body()

  /** Every exported span of the trace the request whose endpoint span is named `rootName` began. */
  private def traceOf(rootName: String): Vector[FakeCollector.ExportedSpan] =
    val deadline = System.nanoTime() + 30.seconds.toNanos
    var spans    = Vector.empty[FakeCollector.ExportedSpan]
    def complete(trace: Vector[FakeCollector.ExportedSpan]) =
      trace.exists(_.serviceName.contains("payments")) && trace.count(
        _.serviceName.contains("orders")
      ) >= 2
    while !complete(spans) && System.nanoTime() < deadline do
      spans = collector.spans
        .find(_.name == rootName)
        .toVector
        .flatMap(root => collector.spans.filter(_.traceId == root.traceId))
      if !complete(spans) then Thread.sleep(100)
    spans

  private def assertOneTrace(rootName: String, callName: String, calleeName: String): Unit =
    val trace = traceOf(rootName)
    val root  = trace.find(_.name == rootName).getOrElse(fail(s"no $rootName among $trace"))
    val call =
      trace.find(_.name == callName).getOrElse(fail(s"no $callName among ${trace.map(_.name)}"))
    val callee =
      trace.find(_.name == calleeName).getOrElse(fail(s"no $calleeName among ${trace.map(_.name)}"))
    assertEquals(root.serviceName, Some("orders"))
    assertEquals(root.kind, FakeCollector.Kind.Server)
    assertEquals(call.serviceName, Some("orders"))
    assertEquals(call.kind, FakeCollector.Kind.Client)
    assertEquals(call.parentSpanId, root.spanId)
    assertEquals(callee.serviceName, Some("payments"))
    assertEquals(callee.kind, FakeCollector.Kind.Server)
    assertEquals(
      callee.parentSpanId,
      call.spanId,
      "the callee's endpoint is under the caller's call"
    )
    // And the callee's entity is in the same trace, under the callee's endpoint.
    val entity = trace.find(s => s.serviceName.contains("payments") && s.name == "cart add-item")
    assertEquals(entity.map(_.parentSpanId), Some(callee.spanId))
    assertEquals(trace.map(_.traceId).distinct.size, 1)

  test("a request that crosses from one service to another is one trace in the collector (HTTP)") {
    get("/orders/http/o1"): Unit
    assertOneTrace(
      "http GET /http/{id}",
      "service:local/payments GET",
      "http GET /{id}"
    )
  }

  test("a request that crosses from one service to another is one trace in the collector (gRPC)") {
    get("/orders/grpc/o2"): Unit
    assertOneTrace(
      "http GET /grpc/{id}",
      "service:local/payments ankka.fixtures.v1.CartService/GetCart",
      "grpc ankka.fixtures.v1.CartService/GetCart"
    )
  }
