package com.thinkmorestupidless.ankka.javasdk.testkit

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.cart.{CartEndpoint, LineItem, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.client.ServiceResponse
import com.thinkmorestupidless.ankka.javasdk.routes.PriceEndpoint
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.http.{HttpRequest, HttpResponse}
import java.time.Duration
import scala.jdk.CollectionConverters.*

/** The Java service kit boots the Java service whole, as the Scala kit boots a Scala one. */
class ServiceKitSuite extends munit.FunSuite with LogCapturing:

  private def cart =
    Ankka.service("cart").register(classOf[ShoppingCart]).withEndpoint(classOf[CartEndpoint])

  private def get(kit: AnkkaTestKit, path: String): HttpResponse[String] =
    kit.httpClient.send(
      HttpRequest.newBuilder(kit.http.resolve(path)).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("the Java cart survives restartService, through its client and its endpoint") {
    val kit = AnkkaTestKit.start(cart)
    try
      kit.componentClient
        .forEventSourcedEntity("r1")
        .call(CartRefs.addItem)
        .invoke(LineItem("p1", "Pen", 2))
      kit.restartService()
      val state = kit.componentClient.forEventSourcedEntity("r1").call(CartRefs.getCart).invoke()
      assertEquals(state.items.asScala.map(_.productId).toList, List("p1"))
      val answer = get(kit, "/carts/r1")
      assertEquals(answer.statusCode, 200)
      assert(answer.body.contains("\"p1\""), answer.body)
    finally kit.close()
  }

  test("eventually retries on the value and fails with the last failure past its deadline") {
    val kit = AnkkaTestKit.start(cart)
    try
      var tries = 0
      kit.eventually(Duration.ofSeconds(5), () => { tries += 1; assert(tries >= 3) })
      assertEquals(tries, 3)
      val e = intercept[AssertionError](
        kit.eventually(Duration.ofMillis(200), () => assert(false, "never"))
      )
      assert(e.getMessage.contains("never"), e.getMessage)
    finally kit.close()
  }

  test("a stand-in answers another service's calls as the test says") {
    val settings = Settings.defaults.withScriptedService("pricing")
    val kit =
      AnkkaTestKit.start(Ankka.service("shop").withEndpoint(classOf[PriceEndpoint]), settings)
    try
      kit
        .service("pricing")
        .answer(_ =>
          new ServiceResponse(
            200,
            "application/json",
            "{\"id\":\"pen\",\"size\":3}".getBytes,
            java.util.List.of()
          )
        )
      val answer = get(kit, "/price/pen")
      assertEquals(answer.statusCode, 200, answer.body)
      assertEquals(kit.service("pricing").requests.asScala.map(_.path).toList, List("/prices/pen"))
    finally kit.close()
  }

  test("a service with no endpoint has no address, and an unknown stand-in is named") {
    val kit = AnkkaTestKit.start(Ankka.service("bare").register(classOf[ShoppingCart]))
    try
      intercept[IllegalStateException](kit.http)
      val e = intercept[IllegalArgumentException](kit.service("nope"))
      assert(e.getMessage.contains("'nope'"), e.getMessage)
    finally kit.close()
  }
