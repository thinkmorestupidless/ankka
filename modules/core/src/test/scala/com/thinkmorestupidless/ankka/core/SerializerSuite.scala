package com.thinkmorestupidless.ankka.core

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec

final case class LineItem(productId: String, name: String, quantity: Int)

sealed trait CartEvent
object CartEvent:
  final case class ItemAdded(item: LineItem)      extends CartEvent
  final case class ItemRemoved(productId: String) extends CartEvent
  case object CheckedOut                          extends CartEvent

class SerializerSuite extends munit.FunSuite:

  private given JsonValueCodec[LineItem]  = Codecs.make[LineItem]
  private given JsonValueCodec[CartEvent] = Codecs.make[CartEvent]

  test("round-trips a flat record") {
    val ser  = Serializer.json[LineItem]("line-item")
    val item = LineItem("p1", "Widget", 3)
    assertEquals(ser.fromBytes(ser.toBytes(item)), item)
    assertEquals(ser.manifest, "line-item")
  }

  test("a sealed hierarchy round-trips through the shared discriminator") {
    val ser = Serializer.json[CartEvent]("cart-event")
    val events: List[CartEvent] = List(
      CartEvent.ItemAdded(LineItem("p1", "Widget", 2)),
      CartEvent.ItemRemoved("p1"),
      CartEvent.CheckedOut
    )
    events.foreach(e => assertEquals(ser.fromBytes(ser.toBytes(e)), e))
  }

  test("the discriminator is a readable 'type' field, so a journal is inspectable") {
    val ser  = Serializer.json[CartEvent]("cart-event")
    val json = String(ser.toBytes(CartEvent.ItemRemoved("p1")), "UTF-8")
    assert(json.contains("\"type\":\"ItemRemoved\""), s"unexpected encoding: $json")
  }

  test("Codecs.serializer derives codec and manifest together") {
    val ser  = Codecs.serializer[LineItem]("line-item")
    val item = LineItem("p2", "Gadget", 1)
    assertEquals(ser.fromBytes(ser.toBytes(item)), item)
    assertEquals(ser.manifest, "line-item")
  }

  test("the bytes serializer is a pass-through for opaque broker payloads") {
    val payload = "raw".getBytes("UTF-8")
    assert(java.util.Arrays.equals(Serializer.bytes.fromBytes(payload), payload))
  }

  test("the unit serializer encodes nothing") {
    assertEquals(Serializer.unit.toBytes(()).length, 0)
    assertEquals(Serializer.unit.fromBytes(Array.emptyByteArray), ())
  }
