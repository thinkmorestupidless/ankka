package com.thinkmorestupidless.ankka.javasdk

import com.thinkmorestupidless.ankka.javasdk.bridge.{HttpCalls, JavaServiceBuilder}
import com.thinkmorestupidless.ankka.javasdk.cart.{CartEndpoint, ShoppingCart}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.typesafe.config.ConfigFactory

/**
 * A Java cart and its endpoint, embedded: a command through HTTP answered, state read back and
 * recovered after a restart; a handler that throws fails its request as a Scala handler's does (the
 * instance stops and the caller's wait times out: 504), records nothing, and the service goes on
 * serving.
 */
class EntityEndpointSuite extends munit.FunSuite with LogCapturing:

  private val read = JavaServiceBuilder.read(
    Vector(classOf[ShoppingCart] -> None),
    Map.empty,
    Vector(classOf[CartEndpoint])
  )

  private var kit: AnkkaTestKit = null
  private lazy val http         = HttpCalls(() => kit.service.boundAddresses.head)

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      read.descriptors,
      extensions = Seq(read.httpServer(Some("127.0.0.1" -> 0))),
      // A throwing handler is answered only by the caller's timeout, as in Scala; keep it short.
      settings = ConfigFactory.parseString("ankka.ask-timeout = 2s")
    )
  override def afterAll(): Unit = if kit != null then kit.stop()

  private val pen = """{"productId":"p1","name":"Pen","quantity":2}"""

  test("a command sent through HTTP is answered, and the state is read back") {
    assertEquals(http.post("/carts/c1/items", pen).statusCode, 204)
    assertEquals(http.post("/carts/c1/items", pen).statusCode, 204)
    assertEquals(
      http.get("/carts/c1").body,
      """{"cartId":"c1","items":[{"productId":"p1","name":"Pen","quantity":4}],"checkedOut":false}"""
    )
  }

  test("a handler's refusal reaches the HTTP caller with its status") {
    assertEquals(http.delete("/carts/c2/items/nothing").statusCode, 404)
    val zero = http.post("/carts/c2/items", """{"productId":"p","name":"x","quantity":0}""")
    assertEquals(zero.statusCode, 400)
    assert(zero.body.contains("a quantity must be positive"), zero.body)
  }

  test("the cart is recovered after a restart") {
    http.post("/carts/c3/items", pen)
    assertEquals(http.post("/carts/c3/checkout").statusCode, 204)
    kit.restartService()
    val body = http.get("/carts/c3").body
    assert(body.contains("\"checkedOut\":true"), body)
    assert(body.contains("\"quantity\":2"), body)
  }

  for kind <- List("unchecked", "checked") do
    test(s"a handler that throws $kind fails its request, records nothing, and serving goes on") {
      http.post(s"/carts/c4-$kind/items", pen)
      val failed = http.post(s"/carts/c4-$kind/fail/$kind")
      assertEquals(failed.statusCode, 504)
      assert(!failed.body.contains("failure"), s"the exception's message leaked: ${failed.body}")
      assertEquals(http.post(s"/carts/c4-$kind/items", pen).statusCode, 204)
      assert(http.get(s"/carts/c4-$kind").body.contains("\"quantity\":4"))
    }
