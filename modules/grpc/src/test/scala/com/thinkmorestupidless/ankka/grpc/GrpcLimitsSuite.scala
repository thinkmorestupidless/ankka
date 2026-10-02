package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.http.Acl
import com.typesafe.config.ConfigFactory
import io.grpc.{Status, StatusRuntimeException}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{CountDownLatch, TimeUnit}

/** A caller's deadline, and how large a request may be. */
class GrpcLimitsSuite extends munit.FunSuite:

  private val finished = CountDownLatch(1)
  private val ran      = AtomicBoolean(false)
  private val handled  = AtomicInteger()

  private final class Slow extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    unary(CartServiceGrpc.METHOD_GET_CART) { request =>
      if request.cartId == "slow" then
        Thread.sleep(1000)
        ran.set(true)
        finished.countDown()
      if request.cartId == "large" then Cart(cartId = "w" * 2048) else Cart(cartId = request.cartId)
    }
    unary(CartServiceGrpc.METHOD_ADD_ITEM) { request =>
      handled.incrementAndGet()
      Cart(request.cartId)
    }

  private val server = GrpcServer.at("127.0.0.1", 0)()

  override def beforeAll(): Unit =
    server.serve(
      Vector(Slow()),
      "127.0.0.1",
      0,
      ConfigFactory
        .parseString("ankka.grpc.max-message-size = 1KiB")
        .withFallback(ConfigFactory.load())
    )

  override def afterAll(): Unit = server.stop()

  private def stub() =
    CartServiceGrpc.blockingStub(
      GrpcChannels.plaintext(server.boundPort.getOrElse(fail("not bound")))
    )

  test("a caller whose deadline passes is told so, and the handler still runs to its end") {
    // The connection is opened first: a deadline spent connecting would never reach a handler, and
    // the case is about one that does.
    val connected = stub()
    val _         = connected.getCart(GetCartRequest("warm"))
    val failure = intercept[StatusRuntimeException](
      connected.withDeadlineAfter(200, TimeUnit.MILLISECONDS).getCart(GetCartRequest("slow"))
    )
    assertEquals(failure.getStatus.getCode, Status.Code.DEADLINE_EXCEEDED)
    assert(finished.await(5, TimeUnit.SECONDS), "the handler did not finish")
    assert(ran.get)
  }

  test("a request over the size limit is refused before any handler runs") {
    val failure = intercept[StatusRuntimeException](
      stub().addItem(AddItemRequest("c1", "x" * 2048, 1))
    )
    assertEquals(failure.getStatus.getCode, Status.Code.RESOURCE_EXHAUSTED)
    assertEquals(handled.get, 0)
  }

  test("an answer larger than the service's own request limit reaches a caller that accepts it") {
    // The limit is on what the service accepts. What it sends is the caller's client's to limit,
    // and a grpc-java client accepts 4 MiB by default.
    assertEquals(stub().getCart(GetCartRequest("large")).cartId.length, 2048)
  }
