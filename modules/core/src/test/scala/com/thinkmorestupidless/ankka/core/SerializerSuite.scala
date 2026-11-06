package com.thinkmorestupidless.ankka.core

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec

final case class LineItem(productId: String, name: String, quantity: Int)

final case class Registered(playerId: String, email: Personal[String])

sealed trait CartEvent
object CartEvent:
  final case class ItemAdded(item: LineItem)      extends CartEvent
  final case class ItemRemoved(productId: String) extends CartEvent
  case object CheckedOut                          extends CartEvent

class SerializerSuite extends munit.FunSuite:

  private given JsonValueCodec[LineItem]   = Codecs.make[LineItem]
  private given JsonValueCodec[CartEvent]  = Codecs.make[CartEvent]
  private given JsonValueCodec[Registered] = Codecs.make[Registered]

  test("a value with no personal field encodes exactly as it did before personal fields existed") {
    // The bytes the shared codec wrote before feature 042, which every journal already holds.
    val ser = Serializer.json[CartEvent]("cart-event")
    val e   = CartEvent.ItemAdded(LineItem("p1", "Widget", 3))
    assertEquals(
      String(ser.toBytes(e), "UTF-8"),
      """{"type":"ItemAdded","item":{"productId":"p1","name":"Widget","quantity":3}}"""
    )
  }

  test("a serializer seals a personal field under its own manifest, and reads it back") {
    val secret  = IArray.fill[Byte](32)(3)
    val keyring = new Keyring:
      val project: String                                                        = "brand"
      def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
        KeyAnswer.Key(secret)
    val ser   = Serializer.json[Registered]("player-registered")
    val value = Registered("8c1f", Personal(DataSubject("player/8c1f"), "ada@example.com"))
    Keyring.binding(keyring) {
      val bytes = ser.toBytes(value)
      val text  = String(bytes, "UTF-8")
      assert(text.contains("\"$personal\":1"), text)
      assert(!text.contains("ada@example.com"), text)
      assertEquals(ser.fromBytes(bytes), value)
      // Under another manifest the associated data differs, so the same bytes do not open.
      intercept[CommandError](Serializer.json[Registered]("other").fromBytes(bytes))
    }
  }

  test("outside a serializer the same value is written in the edge form") {
    import com.github.plokhotnyuk.jsoniter_scala.core.writeToString
    val value = Registered("8c1f", Personal(DataSubject("player/8c1f"), "ada@example.com"))
    assertEquals(
      writeToString(value),
      """{"playerId":"8c1f","email":{"subject":"player/8c1f","value":"ada@example.com"}}"""
    )
  }

  test("round-trips a flat record") {
    val ser  = Serializer.json[LineItem]("line-item")
    val item = LineItem("p1", "Widget", 3)
    assertEquals(ser.fromBytes(ser.toBytes(item)), item)
    assertEquals(ser.manifest, "line-item")
  }

  test("a sealed hierarchy round-trips through the shared discriminator") {
    val ser                     = Serializer.json[CartEvent]("cart-event")
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
