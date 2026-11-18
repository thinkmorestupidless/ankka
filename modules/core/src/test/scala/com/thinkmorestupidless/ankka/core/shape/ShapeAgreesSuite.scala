package com.thinkmorestupidless.ankka.core.shape

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.networknt.schema.{InputFormat, JsonSchemaFactory, SpecVersion}
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.core.graph.GraphJson
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*

object AgreesFixtures:
  final case class Ledger(
      id: UUID,
      at: Instant,
      amount: BigDecimal,
      total: Long,
      counts: Map[String, Int],
      ratio: Double,
      flag: Boolean,
      tags: Vector[String],
      memo: Option[String] = None
  )

/**
 * FR-009's proof (feature 067, T007): for every row of research R1's table, what `Codecs.make`
 * writes is valid against the derived shape, and a value the codec refuses to read for a missing
 * required field, or a field of the wrong JSON type, is refused by the shape too. Driven by the
 * codec, not by the derivation's own idea of the wire.
 */
class ShapeAgreesSuite extends ScalaCheckSuite:
  import AgreesFixtures.*
  import ShapeFixtures.*

  private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

  private def problems[A](json: String)(using s: ShapeOf[A]): Vector[String] =
    val schema = factory.getSchema(Shape.write(Shape.standalone(s.shape, s.definitions)))
    schema.validate(json, InputFormat.JSON).asScala.toVector.map(_.getMessage).sorted

  private def reads[A](json: String)(using codec: JsonValueCodec[A]): Boolean =
    try
      readFromString[A](json)
      true
    catch case _: JsonReaderException => false

  /** `value` as written; then each field removed, and each field given another JSON type. */
  private def agrees[A](value: A)(using codec: JsonValueCodec[A], s: ShapeOf[A]): Unit =
    val written = writeToString(value)
    assertEquals(problems[A](written), Vector.empty, s"$written should fit its shape")
    GraphJson.parse(written.getBytes("UTF-8")) match
      case Right(GraphJson.Obj(fields)) =>
        fields.foreach { (name, field) =>
          val without = Shape.write(GraphJson.Obj(fields.filterNot(_._1 == name)))
          assertEquals(
            problems[A](without).isEmpty,
            reads[A](without),
            s"$without: the shape and the codec disagree about a missing $name"
          )
          wrongType(field).foreach { other =>
            val mistyped = Shape.write(GraphJson.Obj(fields.map {
              case (`name`, _) => name -> other
              case kept        => kept
            }))
            assert(!reads[A](mistyped), s"the codec read $mistyped")
            assert(problems[A](mistyped).nonEmpty, s"the shape accepted $mistyped")
          }
        }
      case _ => ()

  /** A value of a JSON type `json` is not, unless `json` is null (an `Option` takes most). */
  private def wrongType(json: GraphJson): Option[GraphJson] = json match
    case GraphJson.Str(text) if text == "Ready" || text == "Paused" => Some(GraphJson.Num(1))
    case GraphJson.Str(_)                                           => Some(GraphJson.Num(1))
    case GraphJson.Num(_)                                           => Some(GraphJson.Str("1"))
    case GraphJson.Bool(_)                                          => Some(GraphJson.Str("true"))
    case GraphJson.Arr(_)                                           => Some(GraphJson.Num(1))
    case GraphJson.Obj(_)                                           => Some(GraphJson.Num(1))
    case GraphJson.Null                                             => None

  private given JsonValueCodec[Cart]           = Codecs.make
  private given JsonValueCodec[Basket]         = Codecs.make
  private given JsonValueCodec[Event]          = Codecs.make
  private given JsonValueCodec[Figure]         = Codecs.make
  private given JsonValueCodec[Status]         = Codecs.make
  private given JsonValueCodec[Tree]           = Codecs.make
  private given JsonValueCodec[Owned]          = Codecs.make
  private given JsonValueCodec[Ledger]         = Codecs.make
  private given JsonValueCodec[Option[String]] = Codecs.make

  private val item = LineItem("p1", "Widget", 3)

  test("a case class: an Option, a collection and a default may be absent; nothing else") {
    agrees(Cart("c1", List(item), Some("gift"), 2))
    agrees(Cart("c1", Nil, None))
  }

  test("nested case classes, referred to by name") {
    agrees(Basket(item, item))
  }

  test("a sealed hierarchy and its case object") {
    agrees[Event](Event.Added(item))
    agrees[Event](Event.Cleared)
    assert(problems[Event]("""{"type":"Gone"}""").nonEmpty)
    assert(problems[Event]("""{"item":{"productId":"p","name":"n","quantity":1}}""").nonEmpty)
  }

  test("an enumeration with fields") {
    agrees[Figure](Figure.Circle(1.5))
    agrees[Figure](Figure.Square(2))
  }

  test("a fieldless enumeration") {
    agrees(Status.Ready)
    assert(problems[Status]("\"Ready\"").nonEmpty, "the codec writes an object, never a string")
  }

  test("a type that refers to itself") {
    agrees(Tree("root", List(Tree("leaf", Nil), Tree("branch", List(Tree("twig", Nil))))))
  }

  test("a value class is its field") {
    agrees(Owned(CartId("c1")))
  }

  test("the scalars, a map and a defaulted Option") {
    val ledger = Ledger(
      UUID.fromString("00000000-0000-0000-0000-000000000001"),
      Instant.parse("2026-11-18T10:00:00Z"),
      BigDecimal("12.50"),
      Long.MaxValue,
      Map("a" -> 1),
      0.25,
      flag = true,
      Vector("x"),
      Some("m")
    )
    agrees(ledger)
    agrees(ledger.copy(memo = None, counts = Map.empty, tags = Vector.empty))
  }

  test("an Option on its own is its value or null") {
    assertEquals(problems[Option[String]](writeToString(Option("a"))), Vector.empty)
    assertEquals(problems[Option[String]](writeToString(Option.empty[String])), Vector.empty)
    assert(problems[Option[String]]("1").nonEmpty)
  }

  private val label = Gen.alphaNumStr.map(_.take(8))

  private def tree(depth: Int): Gen[Tree] =
    if depth <= 0 then label.map(Tree(_, Nil))
    else
      for
        l <- label
        n <- Gen.choose(0, 3)
        c <- Gen.listOfN(n, tree(depth - 1))
      yield Tree(l, c)

  private val carts: Gen[Cart] = for
    id    <- label
    n     <- Gen.choose(0, 4)
    items <- Gen.listOfN(n, Gen.zip(label, label, Gen.choose(-5, 100)).map(LineItem.apply))
    note  <- Gen.option(label)
    lines <- Gen.choose(0, 3)
  yield Cart(id, items, note, lines)

  property("every nested value the codec writes fits its shape") {
    Prop.forAll(tree(3), carts) { (t, c) =>
      assertEquals(problems[Tree](writeToString(t)), Vector.empty)
      assertEquals(problems[Cart](writeToString(c)), Vector.empty)
    }
  }
