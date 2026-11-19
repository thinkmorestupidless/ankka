package com.thinkmorestupidless.ankka.core.shape

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.core.Codecs

import java.time.{Duration, Instant, LocalDate}
import java.util.UUID

object ShapeFixtures:
  final case class LineItem(productId: String, name: String, quantity: Int)
  final case class Cart(id: String, items: List[LineItem], note: Option[String], lines: Int = 0)
  final case class Basket(first: LineItem, second: LineItem)

  sealed trait Event
  object Event:
    final case class Added(item: LineItem) extends Event
    case object Cleared                    extends Event

  enum Status:
    case Ready, Paused

  enum Figure:
    case Circle(radius: Double)
    case Square(side: Double)

  final case class Tree(label: String, children: List[Tree])

  final case class CartId(value: String) extends AnyVal
  final case class Owned(id: CartId)

  /** A codec written by hand in the companion, and no shape. */
  final case class Lifecycle(word: String)
  object Lifecycle:
    given JsonValueCodec[Lifecycle] = new JsonValueCodec[Lifecycle]:
      def nullValue: Lifecycle                                       = null
      def encodeValue(x: Lifecycle, out: JsonWriter): Unit           = out.writeVal(x.word)
      def decodeValue(in: JsonReader, default: Lifecycle): Lifecycle =
        Lifecycle(in.readString(null))

  /** A codec written by hand, and a shape beside it. */
  final case class Stated(word: String)
  object Stated:
    given JsonValueCodec[Stated] = new JsonValueCodec[Stated]:
      def nullValue: Stated                                    = null
      def encodeValue(x: Stated, out: JsonWriter): Unit        = out.writeVal(x.word)
      def decodeValue(in: JsonReader, default: Stated): Stated = Stated(in.readString(null))
    given ShapeOf[Stated] = ShapeOf.of("""{"type":"string"}""")

  /** `Codecs.make` in the companion, opted in with `derived` beside it. */
  final case class Made(word: String, count: Int)
  object Made:
    given JsonValueCodec[Made] = Codecs.make
    given ShapeOf[Made]        = ShapeOf.derived

  final case class HoldsLifecycle(lifecycle: Lifecycle)

object players:
  final case class Profile(name: String)

object reporting:
  final case class Profile(total: Int)

class ShapeSuite extends munit.FunSuite:
  import ShapeFixtures.*

  private def rendered(shape: Shape): String = Shape.write(Shape.render(shape, _.simple))

  private def of[A](using s: ShapeOf[A]): String = rendered(s.shape)

  private def defined[A](using s: ShapeOf[A]): Map[String, String] =
    s.definitions.map(n => n.name.simple -> rendered(n.shape)).toMap

  // T005: the instances.

  test("each scalar is its JSON type, with its format") {
    assertEquals(of[String], """{"type":"string"}""")
    assertEquals(of[Boolean], """{"type":"boolean"}""")
    assertEquals(of[Int], """{"type":"integer"}""")
    assertEquals(of[Long], """{"type":"integer","format":"int64"}""")
    assertEquals(of[Double], """{"type":"number"}""")
    assertEquals(of[BigDecimal], """{"type":"number"}""")
    assertEquals(of[Instant], """{"type":"string","format":"date-time"}""")
    assertEquals(of[Duration], """{"type":"string","format":"duration"}""")
    assertEquals(of[LocalDate], """{"type":"string","format":"date"}""")
    assertEquals(of[UUID], """{"type":"string","format":"uuid"}""")
  }

  test("an Option is its value's shape or null") {
    assertEquals(of[Option[Int]], """{"type":["integer","null"]}""")
  }

  test("each collection is an array of its element's shape") {
    val expected = """{"type":"array","items":{"type":"string"}}"""
    assertEquals(of[List[String]], expected)
    assertEquals(of[Vector[String]], expected)
    assertEquals(of[Seq[String]], expected)
    assertEquals(of[Set[String]], expected)
  }

  test("a map of strings is an object of any keys") {
    assertEquals(
      of[Map[String, Int]],
      """{"type":"object","additionalProperties":{"type":"integer"}}"""
    )
  }

  test("a shape given as JSON Schema is used as it is, its keys sorted") {
    given ShapeOf[Lifecycle] = ShapeOf.of("""{"type":"string","enum":["Ready","Paused"]}""")
    assertEquals(of[Lifecycle], """{"enum":["Ready","Paused"],"type":"string"}""")
  }

  test("a Raw that is not a JSON object is reported when asked") {
    assertEquals(Shape.problems(Shape.Raw("""{"type":"string"}""")), Vector.empty)
    assertEquals(
      Shape.problems(Shape.Raw("""["string"]""")),
      Vector("""the JSON Schema ["string"] is not a JSON object""")
    )
    assert(Shape.problems(Shape.Raw("""{"type":""")).head.contains("is not JSON"))
  }

  // T006: the derivation, one case per row of research R1's table.

  test(
    "a case class is an object in declaration order; Option, collection and default fields are not required"
  ) {
    assertEquals(of[Cart], """{"$ref":"Cart"}""")
    assertEquals(
      defined[Cart]("Cart"),
      """{"type":"object","properties":{"id":{"type":"string"},"items":{"type":"array","items":{"$ref":"LineItem"}},"note":{"type":["string","null"]},"lines":{"type":"integer"}},"required":["id"]}"""
    )
  }

  test("a nested case class is a reference, and each named type is defined once") {
    val defs = summon[ShapeOf[Basket]].definitions
    assertEquals(defs.map(_.name.simple).sorted, Vector("Basket", "LineItem"))
    assertEquals(
      defined[Basket]("Basket"),
      """{"type":"object","properties":{"first":{"$ref":"LineItem"},"second":{"$ref":"LineItem"}},"required":["first","second"]}"""
    )
  }

  test(
    "a sealed trait is one of its cases, each with a type constant; a case object has only that"
  ) {
    assertEquals(
      defined[Event]("Event"),
      """{"oneOf":[{"type":"object","properties":{"type":{"type":"string","const":"Added"},"item":{"$ref":"LineItem"}},"required":["type","item"]},{"type":"object","properties":{"type":{"type":"string","const":"Cleared"}},"required":["type"]}]}"""
    )
  }

  test("an enumeration with fields is one of its cases") {
    assertEquals(
      defined[Figure]("Figure"),
      """{"oneOf":[{"type":"object","properties":{"type":{"type":"string","const":"Circle"},"radius":{"type":"number"}},"required":["type","radius"]},{"type":"object","properties":{"type":{"type":"string","const":"Square"},"side":{"type":"number"}},"required":["type","side"]}]}"""
    )
  }

  test("a fieldless enumeration case is an object with a type constant") {
    assertEquals(
      defined[Status]("Status"),
      """{"oneOf":[{"type":"object","properties":{"type":{"type":"string","const":"Ready"}},"required":["type"]},{"type":"object","properties":{"type":{"type":"string","const":"Paused"}},"required":["type"]}]}"""
    )
  }

  test("a value class is its one field") {
    assertEquals(
      defined[Owned]("Owned"),
      """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}"""
    )
  }

  test("a type whose companion gives a codec and no shape has none") {
    assertEquals(summon[ShapeOf.Maybe[Lifecycle]].found, None)
    assertEquals(summon[ShapeOf.Maybe[HoldsLifecycle]].found, None)
    val refused = compileErrors("summon[ShapeOf[ShapeFixtures.Lifecycle]]")
    assert(refused.contains("has a JsonValueCodec in its companion and no ShapeOf"), refused)
  }

  test("a type whose companion gives a codec and a shape uses the shape") {
    assertEquals(of[Stated], """{"type":"string"}""")
    assertEquals(
      summon[ShapeOf.Maybe[Stated]].found.map(s => rendered(s.shape)),
      Some("""{"type":"string"}""")
    )
  }

  test("a companion's Codecs.make opts in with derived beside it") {
    assertEquals(
      summon[ShapeOf.Maybe[Made]].found.map(_.definitions.map(n => rendered(n.shape))),
      Some(
        Vector(
          """{"type":"object","properties":{"word":{"type":"string"},"count":{"type":"integer"}},"required":["word","count"]}"""
        )
      )
    )
  }

  test("a type that refers to itself is a reference to its own name, and terminates") {
    assertEquals(
      defined[Tree]("Tree"),
      """{"type":"object","properties":{"label":{"type":"string"},"children":{"type":"array","items":{"$ref":"Tree"}}},"required":["label"]}"""
    )
  }

  test("two types of one simple name keep distinct full names") {
    val one = summon[ShapeOf[players.Profile]].definitions.map(_.name)
    val two = summon[ShapeOf[reporting.Profile]].definitions.map(_.name)
    assertEquals(one.map(_.simple), two.map(_.simple))
    assertNotEquals(one.map(_.full), two.map(_.full))
    assertEquals(one.head.qualifiers.last, "players")
  }

  test("Maybe finds a shape when there is one, and none for a type with no derivation") {
    assert(summon[ShapeOf.Maybe[Cart]].found.isDefined)
    assertEquals(summon[ShapeOf.Maybe[Array[Byte] => Int]].found, None)
  }

  test("a shape renders alone with its definitions, for a validator") {
    val s     = summon[ShapeOf[Basket]]
    val alone = Shape.write(Shape.standalone(s.shape, s.definitions))
    assert(
      alone.startsWith(
        """{"$schema":"https://json-schema.org/draft/2020-12/schema","$ref":"#/$defs/"""
      ),
      alone
    )
    assert(alone.contains(""""$defs":{"""), alone)
  }
