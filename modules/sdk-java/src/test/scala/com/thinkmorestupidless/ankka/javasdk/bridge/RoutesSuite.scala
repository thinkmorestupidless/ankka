package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.routes.{Closed, Things}
import com.thinkmorestupidless.ankka.javasdk.routes.broken.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import scala.jdk.CollectionConverters.*

/**
 * Java endpoint routes served by the Scala HTTP server: each binding, each answer, each refusal.
 */
class RoutesSuite extends munit.FunSuite with LogCapturing:

  private var kit: AnkkaTestKit = null
  private lazy val http         = HttpCalls(() => kit.service.boundAddresses.head)

  override def beforeAll(): Unit =
    val read =
      JavaServiceBuilder.read(Vector.empty, Map.empty, Vector(classOf[Things], classOf[Closed]))
    kit = AnkkaTestKit.start(Nil, extensions = Seq(read.httpServer(Some("127.0.0.1" -> 0))))
  override def afterAll(): Unit = if kit != null then kit.stop()

  test("a literal segment outranks a parameter") {
    assertEquals(http.get("/things/special").body, "literal")
    assertEquals(http.get("/things/pen").body, """{"id":"pen","size":3}""")
  }

  test("a path parameter bound by name, and by @Path with a conversion") {
    assertEquals(http.get("/sized/7").body, """{"id":"sized","size":7}""")
    assertEquals(http.get("/sized/seven").statusCode, 400)
  }

  test("a required and an optional query parameter") {
    assertEquals(http.get("/search?q=ink&limit=2").body, "ink:2")
    assertEquals(http.get("/search?q=ink").body, "ink:none")
    assertEquals(http.get("/search").statusCode, 400)
  }

  test("a header, the caller, and the request from a parameter and from the base class") {
    assertEquals(http.get("/echo-header", "X-Greeting" -> "hello").body, "hello from Local")
    assertEquals(http.get("/request").body, "GET /request")
    assertEquals(http.get("/from-request", "X-Greeting" -> "hi").body, "hi")
  }

  test("a body read by the record codec; a Response with its status and header") {
    val created = http.post("/things", """{"id":"cup","size":2}""")
    assertEquals(created.statusCode, 201)
    assertEquals(created.headers.firstValue("Location").orElse(""), "/things/cup")
    assertEquals(created.body, """{"id":"cup","size":2}""")
    assertEquals(http.post("/things", """{"id":"cup"}""").statusCode, 400)
  }

  test("Done answers 204; a ProblemException answers its status") {
    assertEquals(http.put("/things/cup", """{"id":"cup","size":1}""").statusCode, 204)
    val conflict = http.put("/things/cup", """{"id":"mug","size":1}""")
    assertEquals(conflict.statusCode, 409)
    assert(conflict.body.contains("the id is cup"), conflict.body)
  }

  test("an endpoint whose acl denies all refuses every route") {
    assertEquals(http.get("/closed").statusCode, 403)
  }

  private def problems(cls: Class[?]): Vector[String] =
    intercept[ServiceProblems](
      JavaServiceBuilder.read(Vector.empty, Map.empty, Vector(cls))
    ).problems.asScala.toVector
      .map(_.toString)

  test("route problems are found at start, each naming the route's method") {
    assert(problems(classOf[BadTemplate]).exists(_.contains("malformed path segment")))
    assert(problems(classOf[PathNotInTemplate]).exists(_.contains("""@Path("b") is not in""")))
    assert(problems(classOf[Unbound]).exists(_.contains("binds nothing to {a}")))
    assert(problems(classOf[Unbindable]).exists(_.contains("Thing")))
    val twice = problems(classOf[Twice])
    assert(twice.exists(_.contains("GET /x/{b} is also first's route")), twice)
    assert(twice.head.startsWith("Twice."), twice)
  }
