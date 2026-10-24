package com.thinkmorestupidless.ankka.javasdk.codec

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Optional
import scala.jdk.CollectionConverters.*

private def bytes(s: String): Array[Byte] = s.getBytes(UTF_8)

/** What the planner refuses at start, each naming the member that has no form. */
class PlannerSuite extends munit.FunSuite:

  private def refusal(cls: Class[?]): EncodingException =
    intercept[EncodingException](RecordCodec.forType(cls))

  test("a float is refused, naming the field") {
    val e = refusal(classOf[Refused.WithFloat])
    assertEquals(e.path, "WithFloat.weight")
    assert(e.getMessage.contains("float"), e.getMessage)
  }

  test("a map whose key is not a String is refused") {
    assertEquals(refusal(classOf[Refused.WithIntKeys]).path, "WithIntKeys.byNumber")
  }

  test("a field named type is refused") {
    assertEquals(refusal(classOf[Refused.WithTypeField]).path, "WithTypeField.type")
  }

  test("the path reaches into nested records and collections") {
    assertEquals(refusal(classOf[Refused.Outer]).path, "Outer.inner.counts[]")
  }

  test("a class that is not a record, sealed type or enum is refused") {
    val e = refusal(classOf[Refused.HoldsAClass])
    assertEquals(e.path, "HoldsAClass.thing")
    assert(e.getMessage.contains("Serializer"), e.getMessage)
  }

  test("a record that contains itself is planned once and round-trips") {
    val codec = RecordCodec.forType(classOf[Refused.Node])
    val leaf  = Refused.Node("b", Optional.empty(), List.empty[Refused.Node].asJava)
    val root  = Refused.Node("a", Optional.of(leaf), List(leaf).asJava)
    assertEquals(codec.fromBytes(codec.toBytes(root)), root)
  }

/** What the writer writes beyond the fixtures, and what it refuses. */
class JsonWriterSuite extends munit.FunSuite:

  private def written[A](cls: Class[A], value: A): String =
    String(RecordCodec.forType(cls).toBytes(value), UTF_8)

  test("a sum case is written with type first") {
    assertEquals(
      written(classOf[Refused.Shape], Refused.Circle(1.5)),
      """{"type":"Circle","radius":1.5}"""
    )
  }

  test("doubles take the shortest form, with the exponent where jsoniter puts one") {
    val cases = List(
      1.0       -> "1.0",
      0.001     -> "0.001",
      1.0e-4    -> "1.0E-4",
      1234567.0 -> "1234567.0",
      1.0e7     -> "1.0E7",
      0.1 + 0.2 -> "0.30000000000000004"
    )
    cases.foreach { (d, text) =>
      assertEquals(
        written(classOf[Refused.Shape], Refused.Square(d)),
        s"""{"type":"Square","side":$text}"""
      )
    }
  }

  test("a null field is refused, naming the record, the index and the field") {
    val basket = Refused.Basket(
      List(
        Refused.Discounted("p1", Optional.empty()),
        Refused.Discounted("p2", Optional.empty()),
        Refused.Discounted("p3", null)
      ).asJava
    )
    val e = intercept[EncodingException](written(classOf[Refused.Basket], basket))
    assertEquals(e.path, "Basket.items[2].discount")
  }

  test("an empty Optional is null and a long is exact past 2^53") {
    assertEquals(
      written(classOf[Refused.Discounted], Refused.Discounted("p", Optional.empty())),
      """{"productId":"p","discount":null}"""
    )
    assertEquals(
      written(classOf[Refused.Big], Refused.Big(9007199254740993L, 1)),
      """{"value":9007199254740993,"small":1}"""
    )
  }

  test("a string's control characters and quotes are escaped") {
    val text = "a\"b\\c\nd\u0001"
    assertEquals(
      written(classOf[Shapes.LineItem], Shapes.LineItem(text, "n", 1)),
      """{"productId":"a\"b\\c\nd\u0001","name":"n","quantity":1}"""
    )
  }

/** What the reader accepts and refuses. */
class JsonReaderSuite extends munit.FunSuite:

  test("fields in any order, an unknown field ignored, an absent Optional empty") {
    val codec = RecordCodec.forType(classOf[Refused.Discounted])
    assertEquals(
      codec.fromBytes(bytes("""{ "extra": [1, {"x": null}], "productId" : "p" }""")),
      Refused.Discounted("p", Optional.empty())
    )
  }

  test("type may come anywhere in a sum case") {
    val codec = RecordCodec.forType(classOf[Refused.Shape])
    assertEquals(codec.fromBytes(bytes("""{"side":2.0,"type":"Square"}""")), Refused.Square(2.0))
  }

  test("an enum is read from its object or from a bare string") {
    val codec = RecordCodec.forType(classOf[Shapes.Status])
    assertEquals(codec.fromBytes(bytes("\"Failed\"")), Shapes.Status.Failed)
    assertEquals(codec.fromBytes(bytes("""{"type":"Ready"}""")), Shapes.Status.Ready)
  }

  test("a long past 2^53 is read exactly") {
    val codec = RecordCodec.forType(classOf[Refused.Big])
    assertEquals(
      codec.fromBytes(bytes("""{"value":9007199254740993,"small":0}""")).value,
      9007199254740993L
    )
  }

  test("a missing field is refused, naming it") {
    val e = intercept[EncodingException](
      RecordCodec
        .forType(classOf[Refused.Basket])
        .fromBytes(bytes("""{"items":[{"productId":"a"},{"discount":3}]}"""))
    )
    assertEquals(e.path, "Basket.items[1].productId")
  }

  test("an unknown case is refused") {
    val e = intercept[EncodingException](
      RecordCodec
        .forType(classOf[Refused.Shape])
        .fromBytes(bytes("""{"type":"Triangle"}"""))
    )
    assert(e.getMessage.contains("Triangle"), e.getMessage)
  }

  test("a fraction where an int is declared is refused, naming the field") {
    val e = intercept[EncodingException](
      RecordCodec
        .forType(classOf[Refused.Big])
        .fromBytes(bytes("""{"value":1,"small":1.5}"""))
    )
    assertEquals(e.path, "Big.small")
  }
