package nakka.agent

/** The dynamic JSON path: tool schemas out, model-supplied arguments in. */
class JsonSuite extends munit.FunSuite:

  test("scalars round-trip") {
    assertEquals(Json.parse("\"hello\""), Right(Json.Str("hello")))
    assertEquals(Json.parse("42"), Right(Json.Num(42.0)))
    assertEquals(Json.parse("-1.5"), Right(Json.Num(-1.5)))
    assertEquals(Json.parse("true"), Right(Json.Bool(true)))
    assertEquals(Json.parse("false"), Right(Json.Bool(false)))
    assertEquals(Json.parse("null"), Right(Json.Null))
  }

  test("objects round-trip without a discriminator") {
    val text   = """{"location":"Berlin","days":3}"""
    val parsed = Json.parse(text).fold(fail(_), identity)
    assertEquals(parsed("location").flatMap(_.asString), Some("Berlin"))
    assertEquals(parsed("days").flatMap(_.asDouble), Some(3.0))
    // Plain JSON, with no discriminator field and no `3.0` where `3` was written.
    assertEquals(parsed.render, text)
  }

  test("whole numbers keep integer form") {
    // A tool schema saying `"type": "integer"` rejects `3.0`, so this is not cosmetic.
    assertEquals(Json.num(3).render, "3")
    assertEquals(Json.num(-7).render, "-7")
    assertEquals(Json.num(0).render, "0")
    assertEquals(Json.num(2.5).render, "2.5")
    assertEquals(Json.parse("3").map(_.render), Right("3"))
  }

  test("arrays and nesting round-trip") {
    val text   = """{"items":[{"id":1},{"id":2}],"tags":["a","b"]}"""
    val parsed = Json.parse(text).fold(fail(_), identity)
    assertEquals(parsed("items").flatMap(_.asArray).map(_.size), Some(2))
    assertEquals(
      parsed("items").flatMap(_.asArray).flatMap(_.head("id")).flatMap(_.asDouble),
      Some(1.0)
    )
    assertEquals(parsed.render, text)
  }

  test("empty objects and arrays are handled") {
    assertEquals(Json.parse("{}"), Right(Json.Obj(Map.empty)))
    assertEquals(Json.parse("[]"), Right(Json.Arr(Vector.empty)))
    assertEquals(Json.Obj(Map.empty).render, "{}")
    assertEquals(Json.Arr(Vector.empty).render, "[]")
  }

  test("nulls inside structures survive") {
    val parsed = Json.parse("""{"date":null}""").fold(fail(_), identity)
    assert(parsed("date").exists(_.isNull))
    assertEquals(parsed.render, """{"date":null}""")
  }

  test("strings needing escapes survive a round-trip") {
    val original = Json.obj("text" -> Json.str("line1\nline2 \"quoted\" \\ backslash"))
    assertEquals(Json.parse(original.render), Right(original))
  }

  test("malformed input is reported, not thrown") {
    assert(Json.parse("{not json").isLeft)
    assert(Json.parse("").isLeft)
    assert(Json.parse("{\"a\":}").isLeft)
  }

  test("accessors return None on a type mismatch rather than throwing") {
    val value = Json.str("text")
    assertEquals(value.asDouble, None)
    assertEquals(value.asBoolean, None)
    assertEquals(value.asArray, None)
    assertEquals(value("field"), None)
  }

  test("builders compose the shape a tool schema needs") {
    val schema = Json.obj(
      "type" -> Json.str("object"),
      "properties" -> Json.obj(
        "location" -> Json.obj("type" -> Json.str("string"))
      ),
      "required" -> Json.arr(Json.str("location"))
    )
    assertEquals(Json.parse(schema.render), Right(schema))
    assertEquals(schema("type").flatMap(_.asString), Some("object"))
  }
