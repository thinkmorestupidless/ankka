package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import com.thinkmorestupidless.ankka.http.{
  Principal,
  QueryParams,
  RequestContext,
  SimpleRequestContext
}

import scala.concurrent.duration.*
import scala.io.Source

/**
 * The kernel, one case per row of contracts/http-wire.md's two tables, and the protocol's examples.
 */
class ProtocolSuite extends munit.FunSuite:
  import TestTools.*

  final case class Cart(cartId: String, items: Int)
  given JsonValueCodec[Cart] = JsonCodecMaker.make

  private val tools = declaring { d =>
    d.tool("get_cart")
      .describedAs("Reads one cart")
      .param[String]("cartId", "The cart's id")
      .handle((cartId: String) => Cart(cartId, 2))
    d.tool("add_item")
      .describedAs("Adds an item")
      .param[String]("cartId", "The cart's id")
      .param[Int]("quantity", "How many")
      .handle { (_: String, quantity: Int) =>
        if quantity <= 0 then throw CommandError("quantity must be positive", ErrorCode.BadRequest)
        "added"
      }
    d.tool("explode")
      .describedAs("Throws")
      .handle(() => (throw IllegalStateException("boom")): String)
  }

  private val server = Protocol.ServerInfo("cart", "1.2.3")

  private def answer(request: Protocol.Request): Protocol.Response =
    Protocol.handle(request, context, server, _ => tools)

  private def bodyOf(response: Protocol.Response): Json = response.body.getOrElse(fail("no body"))

  private def rpc(method: String, params: String = "{}", id: String = "1"): Protocol.Request =
    post(s"""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")

  test("initialize answers the version the client asked for when it is supported") {
    Seq("2025-06-18", "2025-03-26").foreach { version =>
      val r = bodyOf(
        answer(
          rpc(
            "initialize",
            s"""{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"t","version":"1"}}"""
          )
        )
      )
      assertEquals(r("result").flatMap(_("protocolVersion")), Some(Json.str(version)))
    }
  }

  test("initialize answers 2025-06-18 to a version it does not know, with tools and its name") {
    val response = answer(rpc("initialize", """{"protocolVersion":"2099-01-01"}"""))
    assertEquals(response.status, 200)
    assert(!response.headers.exists(_._1.equalsIgnoreCase("Mcp-Session-Id")))
    val result = bodyOf(response)("result").get
    assertEquals(result("protocolVersion"), Some(Json.str("2025-06-18")))
    assertEquals(
      result("capabilities"),
      Some(json("""{"tools":{"listChanged":false}}"""))
    )
    assertEquals(result("serverInfo"), Some(json("""{"name":"cart","version":"1.2.3"}""")))
  }

  test("ping answers an empty result") {
    assertEquals(bodyOf(answer(rpc("ping")))("result"), Some(Json.obj()))
  }

  test("a notification is accepted with no body") {
    Seq("notifications/initialized", "notifications/cancelled", "notifications/whatever").foreach {
      method =>
        val response = answer(post(s"""{"jsonrpc":"2.0","method":"$method"}"""))
        assertEquals(response.status, 202)
        assertEquals(response.body, None)
    }
  }

  test("a response from the client is accepted with no body") {
    val response = answer(post("""{"jsonrpc":"2.0","id":7,"result":{}}"""))
    assertEquals(response.status, 202)
  }

  test("tools/list lists every tool with its schema and no cursor") {
    val result = bodyOf(answer(rpc("tools/list", """{"cursor":"ignored"}""")))("result").get
    val listed = result("tools").flatMap(_.asArray).get
    assertEquals(
      listed.flatMap(_("name")).flatMap(_.asString),
      Vector("get_cart", "add_item", "explode")
    )
    assertEquals(listed.head("description"), Some(Json.str("Reads one cart")))
    assertEquals(listed.head("inputSchema").flatMap(_("type")), Some(Json.str("object")))
    assertEquals(result("nextCursor"), None)
  }

  test("tools/list lists only what the caller may see") {
    val response = Protocol.handle(rpc("tools/list"), context, server, _ => tools.take(1))
    val listed   = bodyOf(response)("result").flatMap(_("tools")).flatMap(_.asArray).get
    assertEquals(listed.size, 1)
  }

  test("tools/call answers text, and structured content for an object") {
    val result =
      bodyOf(answer(rpc("tools/call", """{"name":"get_cart","arguments":{"cartId":"c1"}}""")))(
        "result"
      ).get
    assertEquals(result("isError"), Some(Json.bool(false)))
    assertEquals(
      result("content"),
      Some(json("""[{"type":"text","text":"{\"cartId\":\"c1\",\"items\":2}"}]"""))
    )
    assertEquals(result("structuredContent"), Some(json("""{"cartId":"c1","items":2}""")))
  }

  test("a string answer is text alone") {
    val result = bodyOf(
      answer(rpc("tools/call", """{"name":"add_item","arguments":{"cartId":"c1","quantity":1}}"""))
    )("result").get
    assertEquals(result("content"), Some(json("""[{"type":"text","text":"added"}]""")))
    assertEquals(result("structuredContent"), None)
  }

  test("a body that is not JSON is a parse error with a null id") {
    val response = answer(post("{not json"))
    assertEquals(response.status, 400)
    val body = bodyOf(response)
    assertEquals(body("id"), Some(Json.Null))
    assertEquals(body("error").flatMap(_("code")), Some(Json.num(-32700)))
  }

  test("a batch is refused") {
    val response = answer(post("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]"""))
    assertEquals(response.status, 400)
    val error = bodyOf(response)("error").get
    assertEquals(error("code"), Some(Json.num(-32600)))
    assertEquals(
      error("message"),
      Some(Json.str("batches are not accepted in protocol 2025-06-18"))
    )
  }

  test("a message with neither method nor result is an invalid request") {
    val response = answer(post("""{"jsonrpc":"2.0","id":1}"""))
    assertEquals(response.status, 400)
    assertEquals(bodyOf(response)("error").flatMap(_("code")), Some(Json.num(-32600)))
  }

  test("an unknown method is method not found, naming it") {
    val response = answer(rpc("resources/list"))
    assertEquals(response.status, 200)
    val error = bodyOf(response)("error").get
    assertEquals(error("code"), Some(Json.num(-32601)))
    assertEquals(error("message"), Some(Json.str("method not found: resources/list")))
  }

  test("an unknown tool is invalid params, in the words a refused one has") {
    val error =
      bodyOf(answer(rpc("tools/call", """{"name":"close_books","arguments":{}}""")))("error").get
    assertEquals(error("code"), Some(Json.num(-32602)))
    assertEquals(error("message"), Some(Json.str("tool not available: close_books")))
    val hidden = Protocol.handle(
      rpc("tools/call", """{"name":"get_cart","arguments":{"cartId":"c"}}"""),
      context,
      server,
      _ => Vector.empty
    )
    assertEquals(
      bodyOf(hidden)("error").flatMap(_("message")),
      Some(Json.str("tool not available: get_cart"))
    )
  }

  test("an argument the tool's types refuse is invalid params naming it, and no handler runs") {
    var ran     = 0
    val counted = declaring { d =>
      d.tool("add_item").describedAs("Adds").param[Int]("quantity", "How many").handle { (q: Int) =>
        ran += 1
        q
      }
    }
    val wrong = Protocol.handle(
      rpc("tools/call", """{"name":"add_item","arguments":{"quantity":"two"}}"""),
      context,
      server,
      _ => counted
    )
    val error = bodyOf(wrong)("error").get
    assertEquals(error("code"), Some(Json.num(-32602)))
    assert(error("message").flatMap(_.asString).get.startsWith("parameter 'quantity': "), error)
    val missing = Protocol.handle(
      rpc("tools/call", """{"name":"add_item","arguments":{}}"""),
      context,
      server,
      _ => counted
    )
    assert(
      bodyOf(missing)("error").flatMap(_("message")).flatMap(_.asString).get.contains("quantity")
    )
    assertEquals(ran, 0)
  }

  test("a refusing handler is a tool error carrying its message") {
    val result = bodyOf(
      answer(rpc("tools/call", """{"name":"add_item","arguments":{"cartId":"c1","quantity":0}}"""))
    )("result").get
    assertEquals(result("isError"), Some(Json.bool(true)))
    assertEquals(
      result("content"),
      Some(json("""[{"type":"text","text":"quantity must be positive"}]"""))
    )
  }

  test("a throwing handler is a tool error, not a protocol error") {
    val body = bodyOf(answer(rpc("tools/call", """{"name":"explode"}""")))
    assertEquals(body("error"), None)
    assertEquals(body("result").flatMap(_("isError")), Some(Json.bool(true)))
    assertEquals(
      body("result").flatMap(_("content")),
      Some(json("""[{"type":"text","text":"boom"}]"""))
    )
  }

  test("GET and DELETE are 405 with Allow: POST") {
    Seq("GET", "DELETE").foreach { method =>
      val response = answer(Protocol.Request(method, Vector.empty, Array.emptyByteArray))
      assertEquals(response.status, 405)
      assertEquals(response.headers, Vector("Allow" -> "POST"))
    }
  }

  test("an unsupported MCP-Protocol-Version is 400") {
    val response = answer(
      post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", "MCP-Protocol-Version" -> "1999-01-01")
    )
    assertEquals(response.status, 400)
    val body = bodyOf(response)
    assertEquals(body("id"), Some(Json.Null))
    assertEquals(
      body("error").flatMap(_("message")),
      Some(Json.str("unsupported MCP-Protocol-Version: 1999-01-01"))
    )
    val supported = answer(
      post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", "mcp-protocol-version" -> "2025-03-26")
    )
    assertEquals(supported.status, 200)
  }

  test("Mcp-Session-Id is ignored") {
    val response = answer(
      post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", "Mcp-Session-Id" -> "nobody-issued-this")
    )
    assertEquals(response.status, 200)
  }

  test("an Origin that is not the request's own host is 403") {
    val other = answer(
      post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", "Origin" -> "https://evil.example")
    )
    assertEquals(other.status, 403)
    val own = answer(
      post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", "Origin" -> "http://localhost:9000")
    )
    assertEquals(own.status, 200)
  }

  test("a string id is echoed as it came") {
    assertEquals(bodyOf(answer(rpc("ping", id = "\"abc\"")))("id"), Some(Json.str("abc")))
  }

  // The protocol page's own examples (R19.1): a request as the page writes it, and the answer's shape.

  private def example(name: String): Json =
    val stream = getClass.getResourceAsStream(s"/protocol-examples/$name.json")
    assert(stream != null, s"no example $name")
    try json(Source.fromInputStream(stream, "UTF-8").mkString)
    finally stream.close()

  private val weather = declaring { d =>
    d.tool("get_weather")
      .describedAs("Get current weather information for a location")
      .param[String]("location", "City name or zip code")
      .handle((location: String) =>
        s"Current weather in $location:\nTemperature: 72°F\nConditions: Partly cloudy"
      )
  }

  private def replay(name: String): Unit =
    val exchange = example(name)
    val request  = exchange("request").get
    val expected = exchange("response").get
    val got      = bodyOf(Protocol.handle(post(request.render), context, server, _ => weather))
    assertEquals(got("jsonrpc"), expected("jsonrpc"))
    assertEquals(got("id"), expected("id"))
    // Every field the example's answer has, the server's has, with the same value where the example
    // pins one the server is told (the tool's name, description, text).
    exchange("same").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString).foreach {
      pointer =>
        assertEquals(at(got, pointer), at(expected, pointer), pointer)
    }

  private def at(json: Json, pointer: String): Option[Json] =
    pointer.split('/').filter(_.nonEmpty).foldLeft(Option(json)) { (here, step) =>
      here.flatMap(j =>
        step.toIntOption match
          case Some(i) => j.asArray.flatMap(_.lift(i))
          case None    => j(step)
      )
    }

  test("the protocol page's tools/list example")(replay("tools-list"))
  test("the protocol page's tools/call example")(replay("tools-call"))
  test("the protocol page's unknown tool error example")(replay("unknown-tool"))
  test("the protocol page's initialize example")(replay("initialize"))

  // ── A client key's scope (US2, R19.2) ─────────────────────────────────────

  private val ranTools = java.util.concurrent.ConcurrentLinkedQueue[String]()

  private val plumbing = declaring { d =>
    Seq("list_jobs", "book_job", "reschedule_job", "close_books").foreach { name =>
      d.tool(name).describedAs(name).handle { () =>
        ranTools.add(name)
        name
      }
    }
  }

  private val issuedAt = java.time.Instant.parse("2026-10-19T10:00:00Z")

  /** The runtime's scope over the kernel, for a request a key of `scope` admitted. */
  private def underKey(scope: Set[String]): (RequestContext, Protocol.ToolsFor) =
    val runtime = McpRuntime(MovableClock(issuedAt), 1.minute)
    runtime.index.admit(
      "k1",
      ClientKeyIndex.Live("d", "acme", Map.empty, None, "/mcp", scope, issuedAt, None)
    )
    val principal = Principal(
      "acme",
      claims = Map("kind" -> "client-key", "client_key" -> "k1"),
      issuer = Some(McpRuntime.Issuer)
    )
    val keyed = SimpleRequestContext(
      "POST",
      "/mcp",
      QueryParams.empty,
      Vector("Host" -> "localhost:9000"),
      None,
      Some(principal)
    )
    (keyed, ctx => plumbing.filter(t => runtime.scopeOf(ctx).admits(t.name)))

  private def namesListed(response: Protocol.Response): Vector[String] =
    bodyOf(response)("result")
      .flatMap(_("tools"))
      .flatMap(_.asArray)
      .getOrElse(Vector.empty)
      .flatMap(_("name"))
      .flatMap(_.asString)

  test("tools/list under a key scoped to two of four tools lists those two and not the others") {
    val (keyed, toolsFor) = underKey(Set("list_jobs", "book_job"))
    val listed            = namesListed(Protocol.handle(rpc("tools/list"), keyed, server, toolsFor))
    assertEquals(listed, Vector("list_jobs", "book_job"))
    assert(!listed.contains("close_books"))
  }

  test(
    "tools/call of a tool outside a key's scope is a protocol error naming it, and runs nothing"
  ) {
    ranTools.clear()
    val (keyed, toolsFor) = underKey(Set("list_jobs"))
    val response          =
      Protocol.handle(
        rpc("tools/call", """{"name":"close_books","arguments":{}}"""),
        keyed,
        server,
        toolsFor
      )
    val error = bodyOf(response)("error").get
    assertEquals(error("code"), Some(Json.num(-32602)))
    assertEquals(error("message"), Some(Json.str("tool not available: close_books")))
    assert(ranTools.isEmpty, ranTools.toString)
  }

  test("a key scoped to no tool lists all four") {
    val (keyed, toolsFor) = underKey(Set.empty)
    assertEquals(
      namesListed(Protocol.handle(rpc("tools/list"), keyed, server, toolsFor)),
      Vector("list_jobs", "book_job", "reschedule_job", "close_books")
    )
  }
