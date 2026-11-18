package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.graph.GraphJson
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.routes.Players
import com.thinkmorestupidless.ankka.javasdk.routes.broken.{ReadsBound, SharedSummary}

import scala.jdk.CollectionConverters.*

/**
 * A Java endpoint's routes in the service's OpenAPI document (feature 067, T030): what each route's
 * types say, and what its annotations and its class's add.
 */
class DescribedRoutesSuite extends munit.FunSuite:

  private lazy val document: GraphJson =
    val read = JavaServiceBuilder.read(Vector.empty, Map.empty, Vector(classOf[Players]))
    read.httpServer(None).openApi(Map.empty) match
      case Right(d)       => GraphJson.parse(d.render.getBytes("UTF-8")).fold(fail(_), identity)
      case Left(problems) => fail(problems.mkString("\n"))

  private def at(json: GraphJson, path: String*): GraphJson =
    path.foldLeft(json)((j, key) =>
      j match
        case GraphJson.Arr(items) => items(key.toInt)
        case _                    => j.field(key).getOrElse(fail(s"no $key in ${Shape(j)}"))
    )

  private def has(json: GraphJson, path: String*): Boolean =
    scala.util.Try(at(json, path*)).isSuccess

  private object Shape:
    def apply(j: GraphJson): String = com.thinkmorestupidless.ankka.core.shape.Shape.write(j)

  private def op(path: String, method: String) = at(document, "paths", path, method)

  private def params(o: GraphJson): Map[String, GraphJson] =
    at(o, "parameters") match
      case GraphJson.Arr(items) =>
        items
          .map(p =>
            (at(p, "name") match { case GraphJson.Str(s) => s; case x => fail(s"$x") }) -> p
          )
          .toMap
      case other => fail(s"parameters are $other")

  test("a path parameter, a @QueryParam and a @ReadsQuery are parameters of their types") {
    val o = params(op("/players/{playerId}/messages", "get"))
    assertEquals(at(o("playerId"), "in"), GraphJson.Str("path"))
    assertEquals(at(o("page"), "in"), GraphJson.Str("query"))
    assertEquals(at(o("page"), "schema", "type"), GraphJson.Str("integer"))
    assertEquals(at(o("page"), "required"), GraphJson.Bool(false))
    assertEquals(at(o("currency"), "schema", "type"), GraphJson.Str("string"))
    val n = params(op("/players/{n}", "delete"))
    assertEquals(at(n("n"), "schema", "format"), GraphJson.Str("int64"))
  }

  test("the method's annotations and the class's are folded together") {
    val o = op("/players/{playerId}/messages", "get")
    assertEquals(at(o, "summary"), GraphJson.Str("A player's messages"))
    assertEquals(at(o, "tags"), GraphJson.Arr(Vector(GraphJson.Str("player"))))
    assertEquals(at(o, "responses", "404", "description"), GraphJson.Str("not found"))
    assertEquals(at(o, "responses", "403", "description"), GraphJson.Str("wrong brand"))
    assert(
      Shape(at(o, "responses", "403")).contains("Refusal"),
      Shape(at(o, "responses", "403"))
    )
    // The class's tag and refusal reach every route, not only the one that says more.
    assertEquals(at(op("/players/{n}", "delete"), "tags", "0"), GraphJson.Str("player"))
    assert(has(op("/players/{n}", "delete"), "responses", "403"))
  }

  test("the body and the answer are the codec's shapes; void and Done are nothing") {
    val get = op("/players/{playerId}/messages", "get")
    assert(Shape(at(get, "responses", "200")).contains("Messages"), Shape(get))
    val put = op("/players/{playerId}", "put")
    assert(Shape(at(put, "requestBody")).contains("Profile"), Shape(put))
    assert(has(put, "responses", "204"), Shape(put))
    assert(has(op("/players/{n}", "delete"), "responses", "204"))
    val profile = at(document, "components", "schemas", "Profile")
    assertEquals(at(profile, "required"), GraphJson.Arr(Vector(GraphJson.Str("name"))))
  }

  test("a Response is incomplete until @Answers says what it answers") {
    assert(has(op("/players/{playerId}/raw", "get"), "x-ankka-incomplete"))
    val card = op("/players/{playerId}/card", "get")
    assert(!has(card, "x-ankka-incomplete"), Shape(card))
    assertEquals(
      at(card, "responses", "200", "content", "application/json", "schema", "type"),
      GraphJson.Str("string")
    )
    assert(has(card, "responses", "410"), Shape(card))
    assert(!has(card, "responses", "410", "content"), Shape(card))
  }

  test("a route declared in code has its body, and is incomplete until described") {
    val notes = op("/players/{playerId}/notes", "post")
    assert(Shape(at(notes, "requestBody")).contains("Profile"), Shape(notes))
    assert(has(notes, "x-ankka-incomplete"), Shape(notes))
  }

  test("@ReadsQuery naming a @QueryParam, and a shape named two ways, are refused at start") {
    val found = intercept[ServiceProblems](
      JavaServiceBuilder.read(Vector.empty, Map.empty, Vector(classOf[ReadsBound]))
    ).problems.asScala.toVector.map(_.toString)
    assert(found.exists(_.contains("""@ReadsQuery("page") names the parameter""")), found)
    assert(found.exists(_.contains("@BodyShape names both a type and a schema")), found)
  }

  test("a route declared in code carries its scopes, outermost first, and its class's") {
    val profile = op("/players/{playerId}/profile", "get")
    assertEquals(at(profile, "summary"), GraphJson.Str("A player's profile"))
    assert(!has(profile, "x-ankka-incomplete"), Shape(profile))
    assert(Shape(at(profile, "responses", "200")).contains("Profile"), Shape(profile))
    assertEquals(at(profile, "responses", "409", "description"), GraphJson.Str("already noted"))
    assertEquals(at(profile, "responses", "403", "description"), GraphJson.Str("wrong brand"))
    assertEquals(
      at(profile, "tags"),
      GraphJson.Arr(Vector(GraphJson.Str("player"), GraphJson.Str("notes")))
    )
    assert(params(profile).contains("fields"), Shape(profile))
    // The shared scope reaches the other route made inside it too.
    assertEquals(
      at(op("/players/{playerId}/notes", "delete"), "responses", "409", "description"),
      GraphJson.Str("already noted")
    )
  }

  test("a summary given to a scope of two routes declared in code is refused") {
    val read = JavaServiceBuilder.read(Vector.empty, Map.empty, Vector(classOf[SharedSummary]))
    read.httpServer(None).openApi(Map.empty) match
      case Right(_)       => fail("the document was written")
      case Left(problems) =>
        assert(problems.exists(_.contains("described for 2 routes at once")), problems)
  }
