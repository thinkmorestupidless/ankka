package com.thinkmorestupidless.ankka.agent

/** Tool declaration: schema out, model-supplied arguments in. */
class FunctionToolSuite extends munit.FunSuite:

  private val weather = FunctionTool
    .named("get_weather")
    .describedAs("Returns the weather forecast for a given city.")
    .param[String]("location", "A location or city name.")
    .param[Option[String]]("date", "Forecast date, in yyyy-MM-dd format.")
    .handle { (location, date) =>
      s"$location on ${date.getOrElse("today")}: sunny"
    }

  test("the schema describes name, types and descriptions") {
    val schema = weather.spec.inputSchema
    assertEquals(weather.spec.name, "get_weather")
    assertEquals(schema("type").flatMap(_.asString), Some("object"))

    val location = schema("properties").flatMap(_("location")).getOrElse(fail("no location"))
    assertEquals(location("type").flatMap(_.asString), Some("string"))
    assertEquals(location("description").flatMap(_.asString), Some("A location or city name."))
  }

  test("optional parameters are absent from required") {
    val required = weather.spec
      .inputSchema("required")
      .flatMap(_.asArray)
      .map(_.flatMap(_.asString))
      .getOrElse(fail("no required list"))
    assertEquals(required, Vector("location"))
  }

  test("additionalProperties is closed, so the model does not invent fields") {
    assertEquals(
      weather.spec.inputSchema("additionalProperties").flatMap(_.asBoolean),
      Some(false)
    )
  }

  test("arguments are decoded and passed positionally") {
    val result = weather.invoke(
      Json.obj("location" -> Json.str("Berlin"), "date" -> Json.str("2026-09-06"))
    )
    assertEquals(result, Right("Berlin on 2026-09-06: sunny"))
  }

  test("an omitted optional parameter arrives as None") {
    assertEquals(
      weather.invoke(Json.obj("location" -> Json.str("Oslo"))),
      Right("Oslo on today: sunny")
    )
    // An explicit null is the same as absent, which is what models actually send.
    assertEquals(
      weather.invoke(Json.obj("location" -> Json.str("Oslo"), "date" -> Json.Null)),
      Right("Oslo on today: sunny")
    )
  }

  test("a missing required parameter is an error for the model, not an exception") {
    val result = weather.invoke(Json.obj("date" -> Json.str("2026-09-06")))
    assert(result.isLeft, result.toString)
    assert(result.left.exists(_.contains("location")), result.toString)
  }

  test("a wrong-typed parameter names the parameter and the expectation") {
    val result = weather.invoke(Json.obj("location" -> Json.num(42)))
    assert(result.left.exists(m => m.contains("location") && m.contains("string")), result.toString)
  }

  test("integers reject non-integral numbers") {
    val counter = FunctionTool
      .named("repeat")
      .describedAs("Repeats a word.")
      .param[String]("word", "The word.")
      .param[Int]("times", "How many times.")
      .handle((word, times) => word * times)

    assertEquals(
      counter.spec
        .inputSchema("properties")
        .flatMap(_("times"))
        .flatMap(_("type"))
        .flatMap(_.asString),
      Some("integer")
    )
    assertEquals(
      counter.invoke(Json.obj("word" -> Json.str("ab"), "times" -> Json.num(2))),
      Right("abab")
    )
    assert(counter.invoke(Json.obj("word" -> Json.str("ab"), "times" -> Json.num(2.5))).isLeft)
  }

  test("a handler that throws becomes a tool error the model can react to") {
    val brittle = FunctionTool
      .named("brittle")
      .describedAs("Always fails.")
      .param[String]("input", "Anything.")
      .handle { (input: String) =>
        if input.nonEmpty then throw RuntimeException("upstream unavailable")
        else "unreachable"
      }

    assertEquals(
      brittle.invoke(Json.obj("input" -> Json.str("x"))),
      Left("upstream unavailable"): Either[String, String]
    )
  }

  test("a zero-argument tool needs no properties") {
    val clock = FunctionTool
      .named("current_date")
      .describedAs("Returns today's date.")
      .handle(() => "2026-09-06")

    assertEquals(clock.invoke(Json.obj()), Right("2026-09-06"))
    assertEquals(clock.spec.inputSchema("required").flatMap(_.asArray), Some(Vector.empty))
  }

  test("list parameters describe their item type") {
    val summing = FunctionTool
      .named("sum")
      .describedAs("Adds numbers.")
      .param[List[Int]]("values", "The numbers to add.")
      .handle(values => values.sum)

    val items = summing.spec.inputSchema("properties").flatMap(_("values")).flatMap(_("items"))
    assertEquals(items.flatMap(_("type")).flatMap(_.asString), Some("integer"))
    assertEquals(
      summing.invoke(Json.obj("values" -> Json.arr(Json.num(1), Json.num(2), Json.num(3)))),
      Right("6")
    )
  }

  test("a tool must be described, and cannot repeat a parameter name") {
    intercept[IllegalArgumentException](FunctionTool.named("x").describedAs(""))
    intercept[IllegalArgumentException](FunctionTool.named(""))
    intercept[IllegalArgumentException] {
      FunctionTool
        .named("dupe")
        .describedAs("Repeats a parameter name.")
        .param[String]("a", "first")
        .param[String]("a", "second")
        .handle((_, _) => "")
    }
  }

  test("the schema is valid JSON a provider can send verbatim") {
    assertEquals(Json.parse(weather.spec.inputSchema.render), Right(weather.spec.inputSchema))
  }
