package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{
  ComponentDescriptor,
  ComponentId,
  ComponentKind,
  ComponentRegistry,
  DeclaredHandler,
  HandlerKind,
  Metadata
}
import munit.FunSuite

/**
 * Who a call is from, as it is written into a call's metadata and believed when it is read back.
 *
 * The second half is the one that matters: a name in metadata is whatever was sent, and these cases
 * pin that only a name the service declared is taken at its word.
 */
final class CallOriginSuite extends FunSuite:

  private def descriptor(id: String, of: ComponentKind, handlers: String*): ComponentDescriptor =
    new ComponentDescriptor:
      val componentId: ComponentId = ComponentId(id)
      val kind: ComponentKind      = of
      override def declaredHandlers: Vector[DeclaredHandler] =
        handlers.toVector.map(DeclaredHandler(_, HandlerKind.Command))

  private val declared = DeclaredNames.of(
    ComponentRegistry.fromOrThrow(
      Seq(
        descriptor("shopping-cart", ComponentKind.EventSourcedEntity, "add-item", "get-cart"),
        descriptor("checkout", ComponentKind.Workflow, "start", "reserve")
      )
    ),
    Vector(ServedRoute("POST", "/carts/{cartId}/items", streaming = false, "endpoint:/carts"))
  )

  test("an origin round-trips through metadata, which is what crosses a node and a process") {
    val origin = CallOrigin("checkout", "reserve")
    assertEquals(CallOrigin.from(CallOrigin.into(Metadata.empty, origin)), Some(origin))
    assertEquals(CallOrigin.encode(origin), "checkout#reserve")
  }

  test("a handler may hold a '#', and the component before the first one is still the component") {
    val origin = CallOrigin("endpoint:/pages", "GET /pages/{page}#section")
    assertEquals(CallOrigin.decode(CallOrigin.encode(origin)), Some(origin))
  }

  test("a value that names no component or no handler is no origin, and nothing throws") {
    Seq("", "#", "checkout", "checkout#", "#reserve").foreach { value =>
      assertEquals(CallOrigin.decode(value), None, value)
    }
    assertEquals(CallOrigin.from(Metadata.empty), None)
  }

  test("into replaces an origin that was already there, and strip takes one out") {
    val first  = CallOrigin.into(Metadata.empty, CallOrigin("shopping-cart", "add-item"))
    val second = CallOrigin.into(first, CallOrigin("checkout", "reserve"))
    assertEquals(CallOrigin.from(second), Some(CallOrigin("checkout", "reserve")))
    assertEquals(second.toSeq.count(_._1 == CallOrigin.MetadataKey), 1)

    val kept = second.set("other", "kept")
    assertEquals(CallOrigin.from(CallOrigin.strip(kept)), None)
    assertEquals(CallOrigin.strip(kept).get("other"), Some("kept"))
    assertEquals(CallOrigin.strip(Metadata.empty), Metadata.empty)
  }

  test("a declared component and handler is believed, and a route is its endpoint's handler") {
    assert(declared.validate("shopping-cart", "add-item"))
    assert(declared.validate("checkout", "reserve"))
    assert(declared.validate("endpoint:/carts", "POST /carts/{cartId}/items"))
    assertEquals(
      declared.origin(CallOrigin.into(Metadata.empty, CallOrigin("checkout", "reserve"))),
      Some(CallOrigin("checkout", "reserve"))
    )
  }

  test("a name nobody declared is not believed, whichever half is wrong") {
    def believed(component: String, handler: String) =
      declared.origin(CallOrigin.into(Metadata.empty, CallOrigin(component, handler)))

    assertEquals(believed("nobody", "add-item"), None, "an unregistered component")
    assertEquals(believed("shopping-cart", "reserve"), None, "another component's handler")
    assertEquals(believed("shopping-cart", "cart-7"), None, "an entity id is not a handler")
    assertEquals(believed("endpoint:/carts", "POST /carts/c1/items"), None, "a filled-in path")
    assertEquals(believed("endpoint:/orders", "POST /carts/{cartId}/items"), None)
  }

  test("a registered component is known as one even when the handler named is not its own") {
    assert(declared.registered("shopping-cart"))
    assert(!declared.registered("nobody"))
    assert(!declared.registered("endpoint:/carts"), "an endpoint is not a component a call is to")
  }

  test("before a service has declared anything, nothing is believed") {
    assert(!DeclaredNames.none.validate("shopping-cart", "add-item"))
    assertEquals(DeclaredNames.none.names, 0)
  }

  test("the console is not a name anything can declare, or send") {
    // A component id cannot start with a bracket, so no component can be the console...
    assert(ComponentId.parse(CallOrigin.Console.component).isLeft)
    // ...and it is not a declared name, so a process that sends it is not believed.
    assert(!declared.validate(CallOrigin.Console.component, CallOrigin.Console.handler))
    assertEquals(declared.origin(CallOrigin.into(Metadata.empty, CallOrigin.Console)), None)
  }

  test("the names to intern are counted once each, however many pairs use them") {
    // shopping-cart, add-item, get-cart, checkout, start, reserve, the endpoint and its route.
    assertEquals(declared.names, 8)
    assertEquals(declared.size, 5)
  }
