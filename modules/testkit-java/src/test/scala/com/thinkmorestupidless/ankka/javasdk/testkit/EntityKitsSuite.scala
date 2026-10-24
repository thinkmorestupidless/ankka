package com.thinkmorestupidless.ankka.javasdk.testkit

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.bridge.{Clients, JavaServiceBuilder}
import com.thinkmorestupidless.ankka.javasdk.cart.{CartEvent, CartState, LineItem, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.client.{CallerError, CommandException}
import com.thinkmorestupidless.ankka.javasdk.codec.EncodingException
import com.thinkmorestupidless.ankka.javasdk.fixtures.Counter
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit as ScalaKit, LogCapturing}

import scala.jdk.CollectionConverters.*

/** The Java component kits answer as the running service does, with no runtime of their own. */
class EntityKitsSuite extends munit.FunSuite with LogCapturing:

  private val pen  = LineItem("p1", "Pen", 2)
  private val ink  = LineItem("p2", "Ink", 1)
  private val read = JavaServiceBuilder.read(Vector(classOf[ShoppingCart] -> None), Map.empty)

  test("a command persists its event and replies; the state follows") {
    val kit    = EventSourcedTestKit.of(classOf[ShoppingCart], "c1")
    val result = kit.call(CartRefs.addItem, pen)
    assertEquals(result.reply, Done.DONE)
    assertEquals(result.events.asScala.toList: List[Any], List(CartEvent.ItemAdded(pen)))
    assertEquals(kit.state.items.asScala.toList, List(pen))
    assertEquals(kit.state.cartId, "c1")
  }

  test("a refusal persists nothing and keeps its problem") {
    val kit     = EventSourcedTestKit.of(classOf[ShoppingCart])
    val refused = kit.call(CartRefs.addItem, LineItem("p", "x", 0))
    assert(refused.isError)
    assertEquals(refused.error, "a quantity must be positive")
    assertEquals(refused.problem.status, 400)
    assert(refused.events.isEmpty)
    assert(kit.events.isEmpty)
  }

  test("restart recovers the state from every event; withEvents starts from some") {
    val kit = EventSourcedTestKit.of(classOf[ShoppingCart])
    kit.call(CartRefs.addItem, pen)
    kit.call(CartRefs.checkout)
    kit.restart()
    assert(kit.state.checkedOut)
    assertEquals(kit.events.size, 2)
    val recovered =
      EventSourcedTestKit.withEvents(
        classOf[ShoppingCart],
        java.util.List.of(CartEvent.ItemAdded(ink))
      )
    assertEquals(recovered.call(CartRefs.getCart).reply.items.asScala.toList, List(ink))
  }

  test("a handler of another component is refused, naming both") {
    val kit = EventSourcedTestKit.of(classOf[ShoppingCart])
    val e   = intercept[CallerError](kit.call(CartRefs.counterGet))
    assert(e.getMessage.contains("Counter::get"), e.getMessage)
    assert(e.getMessage.contains("ShoppingCart"), e.getMessage)
  }

  test("a key value entity") {
    val kit = KeyValueEntityTestKit.of(classOf[Counter])
    assertEquals(kit.call(CartRefs.increase, Integer.valueOf(2)).reply, java.lang.Long.valueOf(2))
    assertEquals(kit.call(CartRefs.increase, Integer.valueOf(3)).reply, java.lang.Long.valueOf(5))
    assertEquals(kit.state, java.lang.Long.valueOf(5))
  }

  test("the kit and the running service answer the same commands the same way") {
    val kit     = EventSourcedTestKit.of(classOf[ShoppingCart], "same")
    val service = ScalaKit.start(read.descriptors)
    try
      val cart = Clients.of(service.componentClient, read.refs).forEventSourcedEntity("same")
      def both[O](viaKit: => Result[O], viaService: => O): Unit =
        val k = viaKit
        val s =
          try Right(viaService)
          catch case e: CommandException => Left(e.problem)
        assertEquals(if k.isError then Left(k.problem) else Right(k.reply), s)
      both(kit.call(CartRefs.addItem, pen), cart.call(CartRefs.addItem).invoke(pen))
      both(kit.call(CartRefs.addItem, ink), cart.call(CartRefs.addItem).invoke(ink))
      both(
        kit.call(CartRefs.removeItem, "nothing"),
        cart.call(CartRefs.removeItem).invoke("nothing")
      )
      both(kit.call(CartRefs.checkout), cart.call(CartRefs.checkout).invoke())
      both(kit.call(CartRefs.addItem, pen), cart.call(CartRefs.addItem).invoke(pen))
      both[CartState](kit.call(CartRefs.getCart), cart.call(CartRefs.getCart).invoke())
    finally service.stop()
  }

  test("a record holding null is refused naming the field, by the kit as by the service") {
    val held  = LineItem(null, "Pen", 1)
    val kit   = EventSourcedTestKit.of(classOf[ShoppingCart])
    val byKit = intercept[EncodingException](kit.call(CartRefs.addItem, held))
    assert(byKit.path.contains("productId"), byKit.getMessage)
    val service = ScalaKit.start(read.descriptors)
    try
      val cart      = Clients.of(service.componentClient, read.refs).forEventSourcedEntity("n")
      val byService = intercept[EncodingException](cart.call(CartRefs.addItem).invoke(held))
      assertEquals(byService.getMessage, byKit.getMessage)
    finally service.stop()
  }
