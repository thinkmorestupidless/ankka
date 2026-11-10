package com.thinkmorestupidless.ankka.http

import org.apache.pekko.stream.scaladsl.Source

import java.util.UUID

/**
 * A route's handler takes as many path parameters as its template names: every kind of route, and
 * past the twenty-two parameters Scala's own function types stop at.
 */
class PathAritySuite extends munit.FunSuite:

  private final class Deep extends HttpEndpoint("/deep"):
    val acl: Acl = Acl.AllowAll

    get("/{a}/{b}/{c}")((a: String, b: Int, c: Long) => s"$a:$b:$c")
    delete("/{a}/{b}/{c}/{d}/{e}")((a: String, b: Int, c: Long, d: Boolean, e: UUID) =>
      s"$a:$b:$c:$d:$e"
    )
    post("/{a}/{b}/{c}")((a: String, b: String, c: String) => s"$a$b$c")
    put("/{a}/{b}/{c}/{d}")((a: Int, b: Int, c: Int, d: Int) => (a + b + c + d).toString)
    patch("/{a}/{b}/{c}")((a: String, b: String, c: String) => s"$c$b$a")
    postBody("/{a}/{b}/{c}/body")((a: String, b: Int, c: Long, body: String) => s"$a:$b:$c:$body")
    putBody("/{a}/{b}/{c}/{d}/body")((a: String, b: String, c: String, d: String, body: String) =>
      s"$a$b$c$d$body"
    )
    patchBody("/{a}/{b}/{c}/body")((a: String, b: String, c: String, body: String) =>
      s"$body$a$b$c"
    )
    postStream("/{a}/{b}/upload")((a: String, b: Int, _: StreamedBody) => s"$a:$b")
    sse("/{a}/{b}/{c}/events")((a: String, b: Int, c: Long) => Source.single(s"$a:$b:$c"))
    sseBody("/{a}/{b}/events")((a: String, b: Int, body: String) => Source.single(s"$a:$b:$body"))
    sseEvents("/{a}/{b}/named")((a: String, b: Int) => Source.single(SseEvent.text(s"$a:$b")))
    socket("/{a}/{b}/{c}/{d}/socket")((a: String, b: Int, c: Long, d: Boolean, socket: Socket) =>
      socket.send(s"$a:$b:$c:$d")
    )

    // One more than Scala's last function class of its own.
    get(
      "/{p1}/{p2}/{p3}/{p4}/{p5}/{p6}/{p7}/{p8}/{p9}/{p10}/{p11}/{p12}/{p13}/{p14}/{p15}/{p16}/{p17}/{p18}/{p19}/{p20}/{p21}/{p22}/{p23}"
    )(
      (
          p1: Int,
          p2: Int,
          p3: Int,
          p4: Int,
          p5: Int,
          p6: Int,
          p7: Int,
          p8: Int,
          p9: Int,
          p10: Int,
          p11: Int,
          p12: Int,
          p13: Int,
          p14: Int,
          p15: Int,
          p16: Int,
          p17: Int,
          p18: Int,
          p19: Int,
          p20: Int,
          p21: Int,
          p22: Int,
          last: String
      ) =>
        val sum = p1 + p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9 + p10 + p11 + p12 + p13 + p14 + p15 +
          p16 + p17 + p18 + p19 + p20 + p21 + p22
        s"$sum:$last"
    )

  private val endpoint = new Deep

  private def route(method: String, template: String): Route =
    endpoint.routes
      .find(r => r.method == method && r.template.render == template)
      .getOrElse(fail(s"no route $method $template among ${endpoint.routes.map(_.describe)}"))

  private def answer(method: String, template: String, args: String*)(body: String = ""): String =
    String(route(method, template).run(args.toVector, body.getBytes("UTF-8")).body, "UTF-8")

  test("a handler is given three path parameters, each as its own type, in the template's order") {
    assertEquals(answer("GET", "/{a}/{b}/{c}", "x", "2", "3")(), "x:2:3")
    assertEquals(answer("POST", "/{a}/{b}/{c}", "x", "y", "z")(), "xyz")
    assertEquals(answer("PATCH", "/{a}/{b}/{c}", "x", "y", "z")(), "zyx")
  }

  test("a handler is given four and five path parameters") {
    assertEquals(answer("PUT", "/{a}/{b}/{c}/{d}", "1", "2", "3", "4")(), "10")
    val id = UUID.randomUUID()
    assert(
      answer("DELETE", "/{a}/{b}/{c}/{d}/{e}", "x", "2", "3", "true", id.toString)()
        .contains(s"x:2:3:true:$id")
    )
  }

  test("a handler is given twenty-three path parameters") {
    val template = (1 to 23).map(i => s"{p$i}").mkString("/", "/", "")
    val args     = (1 to 22).map(_.toString) :+ "end"
    assertEquals(answer("GET", template, args*)(), s"${(1 to 22).sum}:end")
  }

  test("a handler with a body is given every path parameter and then the body") {
    assertEquals(answer("POST", "/{a}/{b}/{c}/body", "x", "2", "3")("b"), "x:2:3:b")
    assertEquals(answer("PUT", "/{a}/{b}/{c}/{d}/body", "w", "x", "y", "z")("b"), "wxyzb")
    assertEquals(answer("PATCH", "/{a}/{b}/{c}/body", "x", "y", "z")("b"), "bxyz")
  }

  test("a path value that is not its parameter's type is a 400 naming the template") {
    val refused = intercept[HttpProblem] {
      route("GET", "/{a}/{b}/{c}").run(Vector("x", "2", "three"), Array.emptyByteArray)
    }
    assertEquals(refused.status, 400)
    assert(refused.getMessage.contains("/{a}/{b}/{c}"), refused.getMessage)
  }

  test("a stream, a body read as it arrives and a socket take any number of path parameters") {
    assertEquals(
      endpoint.streamRoutes.map(_.describe),
      Vector(
        "GET /{a}/{b}/{c}/events (SSE)",
        "POST /{a}/{b}/events (SSE)",
        "GET /{a}/{b}/named (SSE)"
      )
    )
    assert(route("POST", "/{a}/{b}/upload").streamedBody.isDefined)
    assertEquals(endpoint.socketRoutes.map(_.describe), Vector("SOCKET /{a}/{b}/{c}/{d}/socket"))
  }

  test("a socket's path is read before any socket exists") {
    val refused = intercept[HttpProblem] {
      endpoint.socketRoutes.head.run(Vector("x", "two", "3", "true"))
    }
    assertEquals(refused.status, 400)
  }

  test("a handler that takes fewer parameters than its template names is refused at construction") {
    val refused = intercept[IllegalArgumentException] {
      new HttpEndpoint("/bad"):
        val acl: Acl = Acl.AllowAll
        get("/{a}/{b}/{c}")((a: String, b: String) => a + b)
    }
    assert(
      refused.getMessage.contains("GET /bad/{a}/{b}/{c} declares 3 path parameter"),
      refused.getMessage
    )
    assert(refused.getMessage.contains("its handler takes 2"), refused.getMessage)
  }

  test("a handler whose parameter no path segment can be read as does not compile") {
    val errors = compileErrors(
      """new HttpEndpoint("/bad"):
           val acl: Acl = Acl.AllowAll
           get("/{a}/{b}/{c}")((a: String, b: List[String], c: String) => a + c)
      """
    )
    assert(errors.contains("FromPath"), errors)
  }

  test("something that is not a function is not a handler") {
    val errors = compileErrors(
      """new HttpEndpoint("/bad"):
           val acl: Acl = Acl.AllowAll
           get("/x")("not a handler")
      """
    )
    assert(errors.contains("a route's handler is a function"), errors)
  }
