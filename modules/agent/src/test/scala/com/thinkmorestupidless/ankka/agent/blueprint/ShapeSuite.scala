package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.agent.Json

/**
 * The JSON Schema subset a blueprint's shapes are written in, and the check of a value against one.
 */
class ShapeSuite extends munit.FunSuite:

  private def shape(text: String): Shape =
    Shape.fromText(text).fold(ps => fail(ps.mkString("; ")), identity)

  private def json(text: String): Json = Json.parse(text).fold(fail(_), identity)

  private def problems(s: Shape, value: String): Vector[String] =
    s.check(json(value)).map(_.toString)

  test("a shape reads every keyword of the subset") {
    val s = shape(
      """{"type":"object","description":"a paper","properties":{"doi":{"type":"string"},
        |"year":{"type":"integer"},"tags":{"type":"array","items":{"type":"string","enum":["a","b"]}}},
        |"required":["doi"]}""".stripMargin
    )
    assert(s.isObject)
    assertEquals(s.field("tags").map(_.isStringArray), Some(true))
    assertEquals(s.field("year").map(_.kind), Some("integer"))
  }

  test("a keyword outside the subset is refused, naming it and where") {
    val refused = Shape.fromText(
      """{"type":"object","properties":{"n":{"type":"number","minimum":0}},"additionalProperties":false}"""
    )
    assertEquals(
      refused.left.map(_.map(_.takeWhile(_ != '(').trim)),
      Left(
        Vector(
          "$: 'additionalProperties' is not in the shapes the platform checks",
          "$.n: 'minimum' is not in the shapes the platform checks"
        )
      )
    )
  }

  test("a shape needs a type the platform knows") {
    assertEquals(Shape.fromText("""{"properties":{}}""").left.map(_.size), Left(1))
    assert(Shape.fromText("""{"type":"date"}""").left.exists(_.head.contains("type 'date'")))
    assert(
      Shape
        .fromText("""{"type":"object","required":["x"]}""")
        .left
        .exists(_.head.contains("required 'x' is not a property"))
    )
  }

  test("a conforming value has no problems") {
    val s = Shape.obj(
      "doi"  -> Shape.string,
      "year" -> Shape.integer,
      "tags" -> Shape.arr(Shape.enumOf("a", "b"))
    )
    assertEquals(problems(s, """{"doi":"10.1/x","year":2026,"tags":["a"]}"""), Vector.empty)
  }

  test("every departure is named with its path") {
    val s = Shape.obj(
      "doi"  -> Shape.string,
      "year" -> Shape.integer,
      "tags" -> Shape.arr(Shape.enumOf("a", "b"))
    )
    assertEquals(
      problems(s, """{"year":2026.5,"tags":["a","c",3]}"""),
      Vector(
        "$.doi: is required and missing",
        "$.tags[1]: must be one of \"a\", \"b\", not \"c\"",
        "$.tags[2]: must be a string, not a number",
        "$.year: must be an integer, not a number"
      )
    )
  }

  test("a value of the wrong kind at the root is one problem") {
    assertEquals(
      problems(Shape.arr(Shape.string), """{"a":1}"""),
      Vector("$: must be an array, not an object")
    )
    assertEquals(problems(Shape.boolean, "\"yes\""), Vector("$: must be a boolean, not a string"))
  }

  test("a shape round-trips through its codec, and one outside the subset does not decode") {
    import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
    val s = Shape.obj("passed" -> Shape.boolean, "reasons" -> Shape.arr(Shape.string))
    assertEquals(readFromString[Shape](writeToString(s)), s)
    intercept[com.github.plokhotnyuk.jsoniter_scala.core.JsonReaderException] {
      readFromString[Shape]("""{"type":"string","pattern":"^x"}""")
    }
  }
