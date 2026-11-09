package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.javasdk.cart.{CartEvent, CartState, LineItem, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.client.ServiceResponse
import com.thinkmorestupidless.ankka.javasdk.reference.Fanned
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.{
  Checkout,
  CheckoutRows,
  KindRefs,
  Ledger
}
import com.thinkmorestupidless.ankka.javasdk.testkit.{
  AnkkaTestKit,
  ConsumerTestKit,
  Settings,
  TestClient
}
import com.thinkmorestupidless.ankka.javasdk.{Ankka, Done}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.http.{HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * The Java samples of the component client, calling services, consumers and event sourced entities
 * pages, run against one service in the Java kit.
 */
class DocsComponentsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit = null
  private def client            = kit.componentClient

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Ankka
        .service("carts")
        .register(classOf[ShoppingCart])
        .register(classOf[Checkout])
        .register(classOf[CheckoutRows])
        .register(classOf[PayoutWorkflow])
        .withEndpoint(classOf[CartRoutes]),
      Settings.defaults.withScriptedService("psp-gateway")
    )
  override def afterAll(): Unit = if kit != null then kit.close()

  private def send(method: String, path: String, body: String): HttpResponse[String] =
    kit.httpClient.send(
      HttpRequest
        .newBuilder(kit.http.resolve(path))
        .header("content-type", "text/plain")
        .method(method, HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("a socket answers refresh with the cart, and anything else with an error") {
    val socket = kit.socket("/s-1/watch")
    try
      socket.send("refresh")
      val cart = socket.receive().toScala.getOrElse(fail("no frame"))
      assert(cart.contains("\"cartId\":\"s-1\""), cart)
      assert(cart.contains("\"checkedOut\":false"), cart)
      socket.send("purge")
      val error = socket.receive().toScala.getOrElse(fail("no frame"))
      assert(error.contains("unknown request 'purge'"), error)
    finally socket.close()
  }

  test("a step calls another service as this service, and goes on with its answer") {
    kit
      .service("psp-gateway")
      .answer(_ => ServiceResponse(200, "text/plain", "accepted".getBytes, java.util.List.of()))
    client.forWorkflow("p-1").call(DocsRefs.startPayout).invoke(PayoutWorkflow.Payout(5)): Unit
    val state = client.forWorkflow("p-1").awaitEnd(classOf[PayoutWorkflow], Duration.ofSeconds(20))
    assertEquals(state, PayoutWorkflow.State("initiated", "accepted"))
    val asked = kit.service("psp-gateway").requests.asScala.map(_.path).toList
    assertEquals(asked, List("/payouts?amount=5"))
  }

  test("the client is addressed by kind and instance, then by handler") {
    val state = CartCalls.addAndRead(client)
    assertEquals(state.items.asScala.toList, List(LineItem("p1", "Widget", 2)))
  }

  test("several calls at once take as long as the slowest, and each answer is collected") {
    client.forEventSourcedEntity("f-1").call(DocsRefs.addItem).invoke(LineItem("a", "A", 3)): Unit
    client.forEventSourcedEntity("f-2").call(DocsRefs.addItem).invoke(LineItem("b", "B", 4)): Unit
    assertEquals(
      CartCalls
        .totals(client, java.util.List.of("f-1", "f-2", "f-3"))
        .asScala
        .toList
        .map(_.intValue),
      List(3, 4, 0)
    )
  }

  test("a refused command reaches the caller as a problem with the entity's status") {
    assertEquals(CartCalls.addNothing(client), 400)
  }

  test("an endpoint starts a workflow from its own thread, and lists a view's rows") {
    val started = send("POST", "/c-9/checkouts", "60000")
    assertEquals(started.statusCode, 204, started.body)
    kit.eventually(
      Duration.ofSeconds(20),
      () =>
        val listed = send("GET", "/checkouts", "")
        assertEquals(listed.statusCode, 200, listed.body)
        assert(listed.body.contains("\"cartId\":\"c-9\""), listed.body)
    )
    val row = send("GET", "/c-9/checkout-row", "")
    assertEquals(row.statusCode, 200, row.body)
    val missing = send("GET", "/nope/checkout-row", "")
    assertEquals(missing.statusCode, 404, missing.body)
    assert(missing.body.contains("no row for cart 'nope'"), missing.body)
  }

  test("a graph consumer reads the cart's state when the event does not carry it") {
    val carts = TestClient
      .of(classOf[ShoppingCart])
      .stub(
        KindRefs.getCart,
        () =>
          CartState(
            "c1",
            java.util.List.of(LineItem("p1", "Pen", 2), LineItem("p2", "Ink", 3)),
            false
          )
      )
    val kit    = ConsumerTestKit.graph(classOf[CartContentsGraph], carts)
    val deltas =
      kit.onMessage(CartEvent.ItemAdded(LineItem("p2", "Ink", 3)), "c1", 2).asScala.toList
    assertEquals(deltas.map(_.key), List("node:cart-contents:c1"))
    assertEquals(deltas.head.version, 2L)
    assertEquals(deltas.head.properties.get("lines"): Any, 2L)
    assertEquals(deltas.head.properties.get("quantity"): Any, 5L)
    assertEquals(kit.onDelete("c1").get(0).key, "node:cart-contents:c1")
    assert(kit.onDelete("c1").get(0).isTombstone)
  }

  test("a key value entity replaces its value and is read back through the client") {
    val result = KitSamples.recordCheckout()
    assertEquals(result.reply, Done.done())
    assertEquals(result.state, CheckoutLog.CheckoutRecord("c1", 1_700_000_000_000L, true))
    val logs = AnkkaTestKit.start(CartCalls.checkoutLogService())
    try
      logs.componentClient.forKeyValueEntity("c1").call(DocsRefs.recordCheckout).invoke(5L): Unit
      assertEquals(
        CartCalls.checkoutRecord(logs.componentClient, "c1"),
        CheckoutLog.CheckoutRecord("c1", 5L, true)
      )
      assertEquals(
        CartCalls.checkoutRecord(logs.componentClient, "c2"),
        CheckoutLog.CheckoutRecord("c2", 0L, false)
      )
    finally logs.close()
  }

  test("the graph kit shows every element a change publishes, under the key the sink reads") {
    val deltas = KitSamples.checkoutDeltas().asScala.toList
    assertEquals(deltas.map(_.key), List("node:cart:c1", "node:checkout:c1", "edge:checked-out:c1"))
    assertEquals(deltas.map(_.version).distinct, List(4L))
    assertEquals(deltas(0).properties.get("checkedOut"): Any, true)
    assertEquals(
      (deltas(2).edgeType.toScala, deltas(2).from.toScala, deltas(2).to.toScala),
      (Some("CHECKED_OUT"), Some("cart:c1"), Some("checkout:c1"))
    )
  }

  test("a consumer given a replicated entity's changes reads where each was written") {
    val result = ConsumerTestKit.of(classOf[LedgerFeed]).onMessage(Ledger.Entered("x"), "l1", 1)
    assertEquals(
      result.payloads.asScala.toList,
      List(LedgerFeed.Entry("l1", "x", "unreplicated", true))
    )
  }

  test("the entity kit runs one handler with no runtime") {
    val result = KitSamples.addItem()
    assertEquals(result.reply, Done.done())
    val events: List[Any] = result.events.asScala.toList
    assertEquals(events, List(CartEvent.ItemAdded(LineItem("p1", "Widget", 2))))
  }

  test("a follower kit answers from the history it is given, written at the home") {
    val state = KitSamples.followerState()
    assertEquals(
      state.entries.asScala.toList,
      List("created@leipzig (mirrored)", "frame@leipzig (mirrored)")
    )
  }

  test("the consumer kit shows every message a change produced, with its key and metadata") {
    val result = KitSamples.fanout()
    assertEquals(result.payloads.asScala.toList, List(Fanned(1), Fanned(2), Fanned(3)))
    assertEquals(
      result.recordKeys.asScala.toList.map(_.toScala),
      List(Some("c1"), Some("second:c1"), Some("c1"))
    )
    assertEquals(result.messages.get(2).metadata.get("x-n"), "3")
  }

  test("any installation's copy answers its replication status from its own journal") {
    val group = AnkkaTestKit.installations("leipzig", "vienna")
    try
      def service = Ankka.service("ledgers").register(classOf[Ledger])
      val leipzig = group.start("leipzig", service)
      val vienna  = group.start("vienna", service)
      val atHome  = leipzig.componentClient.forEventSourcedEntity("shared")
      atHome.call(KindRefs.share).invoke("vienna"): Unit
      atHome.call(KindRefs.enter).invoke("first"): Unit
      vienna.eventually(
        Duration.ofSeconds(30),
        () =>
          val status = KitSamples.status(vienna.componentClient)
          assertEquals(status.home.toScala, Some("leipzig"))
          assertEquals(status.replicationSet.asScala.toSet, Set("leipzig", "vienna"))
          assert(status.position > 0, status.toString)
      )
    finally group.close()
  }
