package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.thinkmorestupidless.ankka.http.Acl
import com.typesafe.config.ConfigFactory

/** Everything wrong with a service's gRPC endpoints is found when it starts, all of it at once. */
class GrpcServerValidationSuite extends munit.FunSuite:

  private val config = ConfigFactory.load()

  private final class Complete extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = Acl.AllowAll
    unary(CartServiceGrpc.METHOD_GET_CART)(request => Cart(request.cartId))
    unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))

  private def problems(endpoints: GrpcEndpoint*): String =
    intercept[IllegalArgumentException](GrpcServer.validate(endpoints.toVector)).getMessage

  test("an endpoint that answers every method of its definition is valid") {
    GrpcServer.validate(Vector(Complete()))
  }

  test("a method of the definition with no handler is named, with what to declare it with") {
    val message = problems(new GrpcEndpoint(CartServiceGrpc.SERVICE):
      val acl: Acl = Acl.AllowAll
      unary(CartServiceGrpc.METHOD_GET_CART)(request => Cart(request.cartId)))
    assert(message.contains("'ankka.fixtures.v1.CartService/AddItem' with no handler"), message)
    assert(message.contains("declare it with unary"), message)
  }

  test("a handler declared as the wrong kind of method is named, with the kind it is") {
    val message = problems(new GrpcEndpoint(CartStreamsGrpc.SERVICE):
      val acl: Acl = Acl.AllowAll
      unary(CartStreamsGrpc.METHOD_WATCH_CART)(request => Cart(request.cartId)))
    assert(
      message.contains(
        "'ankka.fixtures.v1.CartStreams/WatchCart' is declared with unary but is a serverStream method"
      ),
      message
    )
  }

  test("a method declared twice is named") {
    val message = problems(new GrpcEndpoint(CartServiceGrpc.SERVICE):
      val acl: Acl = Acl.AllowAll
      unary(CartServiceGrpc.METHOD_GET_CART)(request => Cart(request.cartId))
      unary(CartServiceGrpc.METHOD_GET_CART)(request => Cart(request.cartId))
      unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId)))
    assert(message.contains("'ankka.fixtures.v1.CartService/GetCart' is declared 2 times"), message)
  }

  test("a method of another definition is named") {
    val message = problems(new GrpcEndpoint(CartServiceGrpc.SERVICE):
      val acl: Acl = Acl.AllowAll
      unary(CartServiceGrpc.METHOD_GET_CART)(request => Cart(request.cartId))
      unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))
      unary(OrderServiceGrpc.METHOD_GET_ORDER)(request => Order(request.orderId)))
    assert(
      message.contains(
        "'ankka.fixtures.v1.OrderService/GetOrder' is not a method of the service definition " +
          "'ankka.fixtures.v1.CartService'"
      ),
      message
    )
  }

  test("two endpoints for one definition are refused") {
    val message = problems(Complete(), Complete())
    assert(
      message.contains(
        "2 endpoints implement the service definition 'ankka.fixtures.v1.CartService'"
      ),
      message
    )
  }

  test("every problem of every endpoint is reported in one failure") {
    val message = problems(
      new GrpcEndpoint(CartServiceGrpc.SERVICE):
        val acl: Acl = Acl.AllowAll
        unary(CartServiceGrpc.METHOD_GET_CART)(request => Cart(request.cartId))
      ,
      new GrpcEndpoint(OrderServiceGrpc.SERVICE):
        val acl: Acl = Acl.AllowAll
    )
    assert(message.contains("CartService/AddItem"), message)
    assert(message.contains("OrderService/GetOrder"), message)
  }

  test("a port already in use stops the service, naming the port") {
    val first = GrpcServer.at("127.0.0.1", 0)()
    first.serve(Vector(Complete()), "127.0.0.1", 0, config)
    try
      val port = first.boundPort.getOrElse(fail("did not bind"))
      val failure = intercept[IllegalStateException](
        GrpcServer.at("127.0.0.1", port)().serve(Vector(Complete()), "127.0.0.1", port, config)
      )
      assert(failure.getMessage.contains(s"127.0.0.1:$port"), failure.getMessage)
    finally first.stop()
  }
