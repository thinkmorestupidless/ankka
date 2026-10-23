package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.javasdk.fixtures.{Calls, Cart, Counter, LineItem, ShoppingCart}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import scala.jdk.CollectionConverters.*

/**
 * Java components hosted by the Scala runtime and called through the Java client: by method
 * reference, by wire name, after a restart, and refused as their handlers decide.
 */
class ClientsSuite extends munit.FunSuite with LogCapturing:

  private val read = JavaServiceBuilder.read(
    Vector(classOf[ShoppingCart] -> None, classOf[Counter] -> None),
    Map.empty
  )

  private var kit: AnkkaTestKit = null

  private def client = Clients.of(kit.componentClient, read.refs)

  override def beforeAll(): Unit = kit = AnkkaTestKit.start(read.descriptors)
  override def afterAll(): Unit  = if kit != null then kit.stop()

  test("a Java entity called by method reference persists and answers") {
    val cart = client.forEventSourcedEntity("cart-1")
    assertEquals(cart.call(Calls.addItem()).invoke(LineItem("p1", "Pen", 2)), Done.DONE)
    assertEquals(cart.call(Calls.addItem()).invoke(LineItem("p1", "Pen", 1)), Done.DONE)
    val state = cart.call(Calls.getCart()).invoke()
    assertEquals(state.items.asScala.toList, List(LineItem("p1", "Pen", 3)))
  }

  test("the same handler called by component id and wire name") {
    val cart = client.forEventSourcedEntity("cart-2")
    cart.call("cart", "add-item", classOf[LineItem], classOf[Done]).invoke(LineItem("p2", "Ink", 1))
    val state = cart.call("cart", "get-cart", classOf[Void], classOf[Cart]).invoke()
    assertEquals(state.items.size, 1)
  }

  test("a handler's rejection reaches the caller as its problem") {
    val cart = client.forEventSourcedEntity("cart-3")
    val e    = intercept[CommandException](cart.call(Calls.addItem()).invoke(LineItem("p", "x", 0)))
    assertEquals(e.problem.status, 400)
    assertEquals(e.problem.detail, "a quantity must be positive")
  }

  test("a handler reads its own context") {
    val whoami =
      client
        .forEventSourcedEntity("cart-ctx")
        .call("cart", "whoami", classOf[Void], classOf[String])
    assertEquals(whoami.invoke(), "cart-ctx")
  }

  test("state survives a restart: the journal holds what the Java codec wrote") {
    val cart = client.forEventSourcedEntity("cart-4")
    cart.call(Calls.addItem()).invoke(LineItem("p1", "Pen", 2))
    cart.call(Calls.checkout()).invoke()
    kit.restartService()
    val state = client.forEventSourcedEntity("cart-4").call(Calls.getCart()).invoke()
    assert(state.checkedOut)
    assertEquals(state.items.asScala.toList, List(LineItem("p1", "Pen", 2)))
  }

  test("a key value entity, asynchronously") {
    val counter = client.forKeyValueEntity("c-1")
    counter.call("counter", "increase", classOf[Integer], classOf[java.lang.Long]).invoke(2)
    val stage =
      counter.call("counter", "increase", classOf[Integer], classOf[java.lang.Long]).invokeAsync(3)
    assertEquals(stage.toCompletableFuture.get().longValue, 5L)
  }
