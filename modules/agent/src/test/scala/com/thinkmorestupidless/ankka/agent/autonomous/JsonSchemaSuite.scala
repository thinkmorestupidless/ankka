package com.thinkmorestupidless.ankka.agent.autonomous

import com.thinkmorestupidless.ankka.agent.Json

class JsonSchemaSuite extends munit.FunSuite:

  import JsonSchemaSuite.*

  private def parse(text: String): Json = Json.parse(text).fold(fail(_), identity)

  test("a case class is an object of its fields, all required") {
    assertEquals(
      JsonSchema[Answer].schema,
      parse(
        """{"type":"object",
          | "properties":{"answer":{"type":"string"},
          |               "confidence":{"type":"integer"},
          |               "sources":{"type":"array","items":{"type":"string"}}},
          | "required":["answer","confidence","sources"],
          | "additionalProperties":false}""".stripMargin
      )
    )
  }

  test("required keeps declaration order") {
    val required = JsonSchema[Answer].schema("required").flatMap(_.asArray)
    assertEquals(required, Some(Vector("answer", "confidence", "sources").map(Json.str)))
  }

  test("an Option field is described but not required") {
    val schema = JsonSchema[WithOptional].schema
    assertEquals(
      schema("properties").flatMap(_("note")),
      Some(Json.obj("type" -> Json.str("string")))
    )
    assertEquals(schema("required"), Some(Json.arr(Json.str("id"))))
  }

  test("products nest, and collections describe their elements") {
    val schema = JsonSchema[Report].schema
    val props  = schema("properties").getOrElse(fail("no properties"))
    assertEquals(props("summary"), Some(JsonSchema[Answer].schema))
    assertEquals(
      props("scores"),
      Some(
        Json.obj(
          "type"                 -> Json.str("object"),
          "additionalProperties" -> Json.obj("type" -> Json.str("number"))
        )
      )
    )
    assertEquals(
      props("tags"),
      Some(Json.obj("type" -> Json.str("array"), "items" -> Json.obj("type" -> Json.str("string"))))
    )
  }

  test("a sum-typed field does not derive, and the error says why") {
    val errors = compileErrors("JsonSchema.derived[JsonSchemaSuite.WithSum]")
    assert(errors.contains("no JsonSchema for"), errors)
    assert(errors.contains("sealed traits"), errors)
  }

object JsonSchemaSuite:
  final case class Answer(answer: String, confidence: Int, sources: List[String])
  object Answer:
    given JsonSchema[Answer] = JsonSchema.derived

  final case class WithOptional(id: Long, note: Option[String])
  object WithOptional:
    given JsonSchema[WithOptional] = JsonSchema.derived

  final case class Report(summary: Answer, scores: Map[String, Double], tags: Vector[String])
  object Report:
    given JsonSchema[Report] = JsonSchema.derived

  sealed trait Kind
  final case class Red()  extends Kind
  final case class Blue() extends Kind
  final case class WithSum(kind: Kind)
