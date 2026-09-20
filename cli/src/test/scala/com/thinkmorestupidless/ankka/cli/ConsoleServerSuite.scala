package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.console.*
import munit.FunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

/**
 * The console's own server: that it serves the UI, that its API is shaped by the `Source` it was
 * given, and that it does not fall over when a developer already has one running.
 *
 * Driven through a fake `Source` rather than a running service. That is the point of the seam — the
 * aggregation API and the UI must work against *a* source rather than against the registry, so a
 * console over a deployed installation is a second implementation rather than a rewrite. A test
 * that could only be written against a live service would be evidence the seam had not held.
 */
final class ConsoleServerSuite extends FunSuite:

  private val client = HttpClient.newHttpClient()

  /** Answers from memory, and records what it was asked for. */
  private final class FakeSource(
      var invoked: Option[(String, InvokeRequest)] = None
  ) extends Source:
    def services(): Vector[ServiceSummary] =
      Vector(ServiceSummary("orders", "1234", "http://127.0.0.1:1", "2026-09-19T12:00:00Z"))

    def service(name: String): Option[String] =
      Option.when(name == "orders")("""{"name":"orders","instances":[],"components":[]}""")

    def traces(name: String): Option[String] =
      Option.when(name == "orders")("""{"capacity":4096,"held":0,"traces":[]}""")

    def trace(name: String, traceId: String): Option[String] =
      Option.when(name == "orders" && traceId == "abc")("""{"traceId":"abc","spans":[]}""")

    def session(name: String, sessionId: String): Option[String] =
      Option.when(name == "orders" && sessionId == "s1")("""{"messages":[],"usage":{}}""")

    def invoke(name: String, request: InvokeRequest): Option[InvokeResponse] =
      invoked = Some((name, request))
      Option.when(name == "orders")(InvokeResponse(204, Vector("x" -> "y"), ""))

    // Streaming has its own suite; here it only has to exist. See ConsoleStreamSuite.
    def invokeStream(name: String, request: InvokeRequest, onChunk: String => Unit): Boolean =
      false

    def query(name: String, component: String, id: String, method: String): Option[String] =
      Option.when(name == "orders" && method == "get")("""{"name":"Ada"}""")

  private def withConsole[A](source: Source)(body: ConsoleServer => A): A =
    val quiet  = PrintStream(ByteArrayOutputStream())
    val server = ConsoleServer.start(source, 0, quiet)
    try body(server)
    finally server.stop()

  private def get(server: ConsoleServer, path: String): (Int, String) =
    val response = client.send(
      HttpRequest.newBuilder(URI.create(s"${server.address}$path")).GET().build(),
      HttpResponse.BodyHandlers.ofString()
    )
    (response.statusCode(), response.body)

  test("the UI is served from inside the jar") {
    withConsole(FakeSource()) { server =>
      val (status, body) = get(server, "/")
      assertEquals(status, 200)
      assert(body.contains("ankka"), "index.html is the page, not a directory listing")

      assertEquals(get(server, "/app.js")._1, 200)
      assertEquals(get(server, "/style.css")._1, 200)
    }
  }

  test("a path that is not an asset is a 404, not a stack trace") {
    withConsole(FakeSource())(server => assertEquals(get(server, "/nope.js")._1, 404))
  }

  test("the services listing renders a service as a list of instances") {
    withConsole(FakeSource()) { server =>
      val (status, body) = get(server, "/api/services")
      assertEquals(status, 200)
      assert(body.contains("\"instances\":["), s"instances is a list, even holding one: $body")
      assert(body.contains("orders"), body)
    }
  }

  test("the service, traces and session routes pass the source's answer through") {
    withConsole(FakeSource()) { server =>
      assert(get(server, "/api/service/orders")._2.contains("components"))
      assert(get(server, "/api/traces/orders")._2.contains("capacity"))
      assert(get(server, "/api/traces/orders/abc")._2.contains("spans"))
      assert(get(server, "/api/session/orders/s1")._2.contains("usage"))
    }
  }

  test("asking about a service the source does not have is a 404") {
    withConsole(FakeSource()) { server =>
      assertEquals(get(server, "/api/service/ghost")._1, 404)
      assertEquals(get(server, "/api/traces/ghost")._1, 404)
    }
  }

  test("invoke forwards the panel's request to the source, verbatim") {
    val source = FakeSource()
    withConsole(source) { server =>
      val body =
        """{"method":"POST","path":"/items/i1","body":"{\"name\":\"W\"}","contentType":"application/json"}"""
      val response = client.send(
        HttpRequest
          .newBuilder(URI.create(s"${server.address}/api/invoke/orders"))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )

      assertEquals(response.statusCode(), 200)
      assert(response.body.contains("\"status\":204"), response.body)

      val (name, request) = source.invoked.getOrElse(fail("the source was never asked"))
      assertEquals(name, "orders")
      assertEquals(request.method, "POST")
      assertEquals(request.path, "/items/i1")
      // The escaped body must arrive as the developer typed it, not as its JSON encoding.
      assertEquals(request.body, Some("""{"name":"W"}"""))
      assertEquals(request.headers, Vector("content-type" -> "application/json"))
    }
  }

  test("a busy port is not a failure — the console takes the next one and says so") {
    // Asks for a real port rather than 0: 0 means "any free port", which is never busy.
    withConsole(FakeSource()) { first =>
      val out    = ByteArrayOutputStream()
      val second = ConsoleServer.start(FakeSource(), first.port, PrintStream(out))
      try
        assertNotEquals(second.port, first.port, "it found another port rather than refusing")
        val said = out.toString(StandardCharsets.UTF_8)
        assert(said.contains("busy"), s"and told the developer why the address differs: $said")
        assert(said.contains(second.port.toString), said)
      finally second.stop()
    }
  }
