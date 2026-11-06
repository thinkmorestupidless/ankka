package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.{Codecs, Personal}

import java.time.LocalDate

/** Feature 042, T031 (R12): a view's personal fields, derived from its row's type. */
object PersonalFieldsShapes:
  final case class Address(line1: Personal[String], city: String)
  final case class Contact(kind: String, value: Personal[String])
  final case class Row(
      playerId: String,
      email: Personal[String],
      nickname: Option[Personal[String]],
      dateOfBirth: Personal[LocalDate],
      address: Option[Address],
      contacts: List[Contact],
      score: Int
  )
  sealed trait Shape
  final case class Plain(name: String)                    extends Shape
  final case class Marked(owner: Personal[String])        extends Shape
  final case class Also(owner: Personal[String], n: Long) extends Shape
  final case class Tree(label: Personal[String], children: List[Tree])
  final case class NoPersonal(a: String, b: Int)

  sealed trait Event
  final case class Seen(id: String) extends Event

  final class RowView extends View[Event, Row]:
    def onChange(event: Event): Effect = effects.ignore()

  object RowView
      extends View.Companion[RowView, Event, Row](
        componentId = com.thinkmorestupidless.ankka.core.ComponentId("rows"),
        source = ChangeSource.fromTopic(
          "seen",
          Codecs.serializer[Event]("event"),
          StartFrom.Earliest
        ),
        rowSerializer = Codecs.serializer[Row]("row")
      ):
    def create(ctx: ViewComponentContext) = new RowView
    lookup(_.email)
    lookup(_.address.get.line1)

final class PersonalFieldsSuite extends munit.FunSuite:
  import PersonalFieldsShapes.*

  test("a row's personal fields are found at any depth, through options and lists") {
    assertEquals(
      PersonalFields.of[Row].paths.toSet,
      Set("email", "nickname", "dateOfBirth", "address.line1", "contacts[*].value")
    )
  }

  test("a sum type's personal fields are each case's, once") {
    assertEquals(PersonalFields.of[Shape].paths, Vector("owner"))
  }

  test("a recursive row is followed to a bounded depth") {
    val paths = PersonalFields.of[Tree].paths
    assertEquals(paths.head, "label")
    assert(paths.contains("children[*].label"), paths.toString)
    assert(paths.size < PersonalFields.Depth, paths.toString)
  }

  test("a row with none, and a type the SDK cannot see into, have none") {
    assertEquals(PersonalFields.of[NoPersonal].paths, Vector.empty)
    assertEquals(summon[PersonalFields[String]].paths, Vector.empty)
  }

  test("a view's descriptor carries its personal fields and the ones marked for lookup") {
    val d = RowView.descriptor
    assertEquals(d.personalFields.toSet, PersonalFields.of[Row].paths.toSet)
    assertEquals(d.lookupFields, Vector("email", "address.line1"))
  }

  test("a lookup on a field that is not personal is refused, naming the field and the view") {
    final class Bad extends View[Event, Row]:
      def onChange(event: Event): Effect = effects.ignore()
    val refused = intercept[IllegalArgumentException] {
      new View.Companion[Bad, Event, Row](
        com.thinkmorestupidless.ankka.core.ComponentId("bad"),
        ChangeSource.fromTopic("seen", Codecs.serializer[Event]("event"), StartFrom.Earliest),
        Codecs.serializer[Row]("row")
      ):
        def create(ctx: ViewComponentContext) = new Bad
        lookup(_.playerId)
    }
    assert(refused.getMessage.contains("'playerId'"), refused.getMessage)
    assert(refused.getMessage.contains("view 'bad'"), refused.getMessage)
  }

  test("a lookup names the path of the field its selector names") {
    assertEquals(FieldPath.of[Row](_.email), "email")
    assertEquals(FieldPath.of[Row](_.address), "address")
    assertEquals(FieldPath.of[Row](_.address.get.line1), "address.line1")
    assert(
      compileErrors("FieldPath.of[PersonalFieldsShapes.Row](r => r.email.toString.length)").nonEmpty
    )
  }
