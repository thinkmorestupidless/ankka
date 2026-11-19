package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.shape.{Named, Shape, ShapeOf, TypeName}
import com.thinkmorestupidless.ankka.javasdk.codec.{RecordCodec, Shapes as J}

import java.lang.reflect.Type
import java.time.Instant

/** The Scala forms of the Java records `codec.Shapes` declares, for the byte-for-byte cases. */
object ScalaForms:
  final case class LineItem(productId: String, name: String, quantity: Int)
  final case class ShoppingCart(items: List[LineItem], checkedOut: Boolean)

  sealed trait ShoppingCartEvent
  final case class ItemAdded(item: LineItem)      extends ShoppingCartEvent
  final case class ItemRemoved(productId: String) extends ShoppingCartEvent
  final case class CheckedOut()                   extends ShoppingCartEvent

  final case class Step(name: String, order: Int)
  final case class Plan(title: String, steps: List[Step], labels: Map[String, String])

  enum Status:
    case Ready, Failed

  final case class Service(
      name: String,
      createdAt: Instant,
      owner: Option[String],
      note: Option[String],
      status: Status
  )

  final case class Tree(label: String, children: List[Tree])

/**
 * A Java type's shape is read from what its codec writes (feature 067, T029), and says what Scala's
 * derivation says for the same type.
 */
class ShapesSuite extends munit.FunSuite:

  private def shape(t: Type): ShapeOf[?] = Shapes.of(t).fold(fail(_), identity)

  /** The shape and every definition, rendered with simple names, as one string. */
  private def rendered(of: ShapeOf[?]): String =
    def ref(n: TypeName) = s"#/components/schemas/${n.simple}"
    (Shape.write(Shape.render(of.shape, ref)) +:
      of.definitions
        .sortBy(_.name.simple)
        .map(n => s"${n.name.simple}: ${Shape.write(Shape.render(n.shape, ref))}")).mkString("\n")

  private def named(of: ShapeOf[?], simple: String): Shape =
    of.definitions
      .collectFirst { case Named(n, s) if n.simple == simple => s }
      .getOrElse(fail(s"no definition $simple in ${of.definitions.map(_.name.simple)}"))

  test("a record is an object in declaration order; a collection is not required") {
    val cart = shape(classOf[J.ShoppingCart])
    assertEquals(
      cart.shape,
      Shape.Ref(TypeName(classOf[J.ShoppingCart].getName.replace('$', '.'), "ShoppingCart"))
    )
    named(cart, "ShoppingCart") match
      case Shape.Obj(properties, required) =>
        assertEquals(properties.map(_._1), Vector("items", "checkedOut"))
        assertEquals(required, Vector("checkedOut"))
      case other => fail(s"not an object: $other")
    assertEquals(
      named(cart, "LineItem"),
      Shape.Obj(
        Vector("productId" -> Shape.string, "name" -> Shape.string, "quantity" -> Shape.integer),
        Vector("productId", "name", "quantity")
      )
    )
  }

  test("Optional is nullable and not required; leaves carry Scala's formats") {
    named(shape(classOf[J.Service]), "Service") match
      case Shape.Obj(properties, required) =>
        assertEquals(
          properties.toMap.get("createdAt"),
          Some(Shape.Scalar("string", Some("date-time")))
        )
        assertEquals(properties.toMap.get("owner"), Some(Shape.Nullable(Shape.string)))
        assertEquals(required, Vector("name", "createdAt", "status"))
      case other => fail(s"not an object: $other")
    named(shape(classOf[J.Numbers]), "Numbers") match
      case Shape.Obj(properties, _) =>
        assertEquals(properties.toMap.get("big"), Some(Shape.Scalar("integer", Some("int64"))))
        assertEquals(properties.toMap.get("half"), Some(Shape.number))
      case other => fail(s"not an object: $other")
  }

  test("a sealed interface and an enum are one-of their cases by name") {
    named(shape(classOf[J.ShoppingCartEvent]), "ShoppingCartEvent") match
      case Shape.OneOf(cases) =>
        assertEquals(cases.map(_._1).toSet, Set("ItemAdded", "ItemRemoved", "CheckedOut"))
      case other => fail(s"not a one-of: $other")
    assertEquals(
      named(shape(classOf[J.Status]), "Status"),
      Shape.OneOf(
        Vector(
          "Ready"  -> Shape.Obj(Vector.empty, Vector.empty),
          "Failed" -> Shape.Obj(Vector.empty, Vector.empty)
        )
      )
    )
  }

  test("a map is a dictionary, and a type that contains itself is a reference to its name") {
    named(shape(classOf[J.Plan]), "Plan") match
      case Shape.Obj(properties, _) =>
        assertEquals(properties.toMap.get("labels"), Some(Shape.Dict(Shape.string)))
      case other => fail(s"not an object: $other")
    val tree = shape(classOf[J.Tree])
    named(tree, "Tree") match
      case Shape.Obj(properties, _) =>
        assertEquals(properties.toMap.get("children"), Some(Shape.Arr(tree.shape)))
      case other => fail(s"not an object: $other")
  }

  test("a type the codec has no form for has no shape, and says why") {
    val refused = Shapes.of(classOf[Thread])
    assert(refused.isLeft, refused)
    assert(refused.left.exists(_.contains("java.lang.Thread")), refused)
  }

  test("a missing collection reads as empty, as Scala's codec reads it") {
    val read = RecordCodec
      .forType(classOf[J.ShoppingCart])
      .fromBytes("""{"checkedOut":true}""".getBytes("UTF-8"))
    assertEquals(read, J.ShoppingCart(java.util.List.of(), true))
    val plan = RecordCodec.forType(classOf[J.Plan]).fromBytes("""{"title":"t"}""".getBytes("UTF-8"))
    assertEquals(plan, J.Plan("t", java.util.List.of(), java.util.Map.of()))
  }

  test("each Java record renders byte for byte as the Scala case class of the same shape") {
    import ScalaForms.*
    val pairs: Vector[(String, ShapeOf[?], ShapeOf[?])] = Vector(
      ("LineItem", shape(classOf[J.LineItem]), summon[ShapeOf[LineItem]]),
      ("ShoppingCart", shape(classOf[J.ShoppingCart]), summon[ShapeOf[ShoppingCart]]),
      (
        "ShoppingCartEvent",
        shape(classOf[J.ShoppingCartEvent]),
        summon[ShapeOf[ShoppingCartEvent]]
      ),
      ("Plan", shape(classOf[J.Plan]), summon[ShapeOf[Plan]]),
      ("Service", shape(classOf[J.Service]), summon[ShapeOf[Service]]),
      ("Tree", shape(classOf[J.Tree]), summon[ShapeOf[Tree]])
    )
    pairs.foreach { (name, java, scala) =>
      assertEquals(rendered(java), rendered(scala), name)
    }
  }
