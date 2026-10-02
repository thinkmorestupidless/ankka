package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.Acl
import com.thinkmorestupidless.ankka.sdk.{
  ServiceIdentityMismatch,
  ServiceServesNoGrpc,
  ServiceUnresolvable
}
import com.thinkmorestupidless.ankka.testpki.TestPki
import com.typesafe.config.{Config, ConfigFactory}
import io.grpc.StatusRuntimeException

import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

/** Calling another service's gRPC endpoint: where it is, who answered, and how it said no. */
class GrpcClientsSuite extends munit.FunSuite:

  private final class Counting(counter: AtomicInteger)
      extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    unary(CartServiceGrpc.METHOD_GET_CART) { request =>
      counter.incrementAndGet()
      request.cartId.split(':') match
        case Array("refuse", code) =>
          throw CommandError(s"refused with $code", ErrorCode.valueOf(code))
        case _ => Cart(cartId = request.cartId)
    }
    unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))

  private def serving(
      counter: AtomicInteger,
      config: Config = ConfigFactory.load(),
      tls: Option[java.nio.file.Path] = None
  ): GrpcServer =
    val server = tls.fold(GrpcServer.at("127.0.0.1", 0)())(GrpcServer.at("127.0.0.1", 0)().withTls)
    server.serve(Vector(Counting(counter)), "127.0.0.1", 0, config)
    server

  private def at(servers: GrpcServer*): GrpcClients.Located =
    GrpcClients.Located.Addresses(
      servers.toVector.map(s => InetSocketAddress("127.0.0.1", s.boundPort.get)),
      "localhost"
    )

  private def clients(locate: GrpcClients.Locate, config: Config = ConfigFactory.load()) =
    GrpcClients.locatedBy(locate).configure(config)

  test("a service that cannot be found fails the first call, naming it") {
    val grpc = clients((_, n) => Left(ServiceUnresolvable(n, "no service at basket")))
    val failure = intercept[ServiceUnresolvable](
      CartServiceGrpc.blockingStub(grpc("basket")).getCart(GetCartRequest("c"))
    )
    assert(failure.getMessage.contains("basket"), failure.getMessage)
  }

  test("a service that serves no gRPC fails the first call, saying so") {
    val grpc = clients((p, n) => Left(ServiceServesNoGrpc(s"$p/$n")))
    val failure = intercept[ServiceServesNoGrpc](
      CartServiceGrpc.blockingStub(grpc("orders")).getCart(GetCartRequest("c"))
    )
    assertEquals(failure.getMessage, "local/orders serves no gRPC")
  }

  test("a refusal arrives with the matching CommandError as its cause, for every code") {
    val counter = AtomicInteger()
    val server  = serving(counter)
    try
      val grpc = clients((_, _) => Right(at(server)))
      ErrorCode.values.foreach { code =>
        val failure = intercept[StatusRuntimeException](
          CartServiceGrpc.blockingStub(grpc("cart")).getCart(GetCartRequest(s"refuse:$code"))
        )
        assertEquals(failure.getCause, CommandError(s"refused with $code", code), code.toString)
        assertEquals(CommandError.from(failure), Some(CommandError(s"refused with $code", code)))
      }
    finally server.stop()
  }

  test("calls to a service with two instances reach both") {
    val (first, second) = (AtomicInteger(), AtomicInteger())
    val (a, b)          = (serving(first), serving(second))
    try
      val grpc = clients((_, _) => Right(at(a, b)))
      (1 to 10).foreach(i =>
        CartServiceGrpc.blockingStub(grpc("cart")).getCart(GetCartRequest(s"c$i"))
      )
      assert(first.get > 0 && second.get > 0, s"${first.get} and ${second.get}")
      assertEquals(first.get + second.get, 10)
    finally
      a.stop()
      b.stop()
  }

  test("a workload whose certificate names another service is never sent the call") {
    val root = TestPki.root("grpc-clients-suite")
    def dir(uri: String, dns: Seq[String] = Nil) =
      root.issue(uris = Seq(uri), dnsNames = dns).writeTo(Files.createTempDirectory("grpc-clients"))
    val counter = AtomicInteger()
    // Answering at cart's address, holding orders' certificate.
    val server = serving(counter, tls = Some(dir("ankka://shop/orders", Seq("localhost"))))
    try
      val grpc = clients(
        (_, _) => Right(at(server)),
        ConfigFactory
          .parseString(s"""ankka.tls.service-directory = "${dir("ankka://shop/checkout")}"""")
          .withFallback(ConfigFactory.load())
      )
      val failure = intercept[StatusRuntimeException](
        CartServiceGrpc.blockingStub(grpc("cart")).getCart(GetCartRequest("c1"))
      )
      assert(failure.getCause.isInstanceOf[ServiceIdentityMismatch], Try(failure.getCause).toString)
      assertEquals(counter.get, 0)
    finally server.stop()
  }
