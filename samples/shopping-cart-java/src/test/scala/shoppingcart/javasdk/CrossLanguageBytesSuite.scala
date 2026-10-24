package shoppingcart.javasdk

import com.thinkmorestupidless.ankka.javasdk.cart.{CartEvent, CartState, LineItem as JLineItem}
import com.thinkmorestupidless.ankka.javasdk.codec.{RecordCodec, Serializer as JSerializer}
import shoppingcart.application.ShoppingCartEntity
import shoppingcart.domain.{LineItem, ShoppingCart, ShoppingCartEvent}

import java.nio.charset.StandardCharsets.UTF_8

/**
 * A state and events declared in Java and the same shapes declared in Scala are written the same:
 * each SDK's default serializer gives identical bytes under the same manifest, and each reads the
 * other's. This is what lets a Java service recover a journal a Scala service wrote.
 */
class CrossLanguageBytesSuite extends munit.FunSuite:

  private val scalaState  = ShoppingCartEntity.stateSerializer
  private val scalaEvents = ShoppingCartEntity.eventSerializer
  private val javaState   = RecordCodec.forType(classOf[CartState])
  private val javaEvents  = RecordCodec.forType(classOf[CartEvent])

  private def same[S, J](scala: S, java: J)(
      s: com.thinkmorestupidless.ankka.core.Serializer[S],
      j: JSerializer[J]
  ): Unit =
    val fromScala = s.toBytes(scala)
    val fromJava  = j.toBytes(java)
    assertEquals(String(fromJava, UTF_8), String(fromScala, UTF_8))
    assertEquals(j.manifest, s.manifest)
    assertEquals(s.fromBytes(fromJava), scala)
    assertEquals(j.fromBytes(fromScala), java)

  test("a cart's state") {
    val items = List(LineItem("a", "Apple", 2), LineItem("b", "Bread", 1))
    same(
      ShoppingCart("c1", items, checkedOut = true),
      CartState(
        "c1",
        java.util.List.of(JLineItem("a", "Apple", 2), JLineItem("b", "Bread", 1)),
        true
      )
    )(scalaState, javaState)
    same(ShoppingCart.empty("c2"), CartState.empty("c2"))(scalaState, javaState)
  }

  test("every event, a case with a record, a value and none") {
    same[ShoppingCartEvent, CartEvent](
      ShoppingCartEvent.ItemAdded(LineItem("a", "Apple", 2)),
      CartEvent.ItemAdded(JLineItem("a", "Apple", 2))
    )(scalaEvents, javaEvents)
    same[ShoppingCartEvent, CartEvent](
      ShoppingCartEvent.ItemRemoved("a"),
      CartEvent.ItemRemoved("a")
    )(scalaEvents, javaEvents)
    same[ShoppingCartEvent, CartEvent](ShoppingCartEvent.CheckedOut, CartEvent.CheckedOut())(
      scalaEvents,
      javaEvents
    )
    same[ShoppingCartEvent, CartEvent](ShoppingCartEvent.Discarded, CartEvent.Discarded())(
      scalaEvents,
      javaEvents
    )
  }
