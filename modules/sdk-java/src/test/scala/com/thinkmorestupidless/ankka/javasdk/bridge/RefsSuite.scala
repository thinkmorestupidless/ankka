package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.client.CallerError
import com.thinkmorestupidless.ankka.javasdk.fixtures.{Calls, Counter, ShoppingCart}

/** A method reference names its handler's wire name; anything else is a caller's error. */
class RefsSuite extends munit.FunSuite:

  private val refs = Refs(
    JavaServiceBuilder
      .read(Vector(classOf[ShoppingCart] -> None, classOf[Counter] -> None), Map.empty)
      .plans
  )

  test("a method reference resolves to the annotation's wire name, not the method's name") {
    val (component, handler) = refs.resolve(Calls.addItem())
    assertEquals(component.componentId, "cart")
    assertEquals(handler.wireName, "add-item")
    assertEquals(handler.method.getName, "putInCart")
  }

  test("a reference to a handler with no argument, and to a query") {
    assertEquals(refs.resolve(Calls.checkout())._2.wireName, "checkout")
    val (_, query) = refs.resolve(Calls.getCart())
    assertEquals(query.wireName, "get-cart")
    assert(query.readOnly)
  }

  test("a lambda is refused, naming the class it was written in") {
    val e = intercept[CallerError](refs.resolve(Calls.lambda()))
    assert(e.getMessage.contains("Calls"), e.getMessage)
    assert(e.getMessage.contains("method reference"), e.getMessage)
  }

  test("a handler of a component the service does not register is refused, naming it") {
    val e = intercept[CallerError](refs.resolve(Calls.unregistered()))
    assert(e.getMessage.contains("Unregistered::name"), e.getMessage)
  }
