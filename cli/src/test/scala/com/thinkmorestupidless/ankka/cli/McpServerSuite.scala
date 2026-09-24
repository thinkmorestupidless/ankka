package com.thinkmorestupidless.ankka.cli

import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.cli.mcp.{AnkkaTools, Json, McpServer}
import munit.FunSuite

import java.io.{BufferedReader, ByteArrayOutputStream, PrintStream, StringReader}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import scala.collection.mutable

/**
 * `ankka mcp` speaks the protocol, and its tools do what the matching commands do.
 *
 * The control plane is a stub on a loopback port that records what it was asked, so a case can
 * prove both halves: the tool's answer, and that a refused descriptor never left the machine.
 */
final class McpServerSuite extends FunSuite:

  private var controlPlane: HttpServer = null
  private val requests                 = mutable.Buffer.empty[String]

  private val cart =
    """{"name":"cart","projectId":"checkout","lifecycle":"Ready","generation":3,""" +
      """"image":"cart:1.0.0","readyInstances":1,"desiredInstances":1}"""

  override def beforeAll(): Unit =
    controlPlane = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    controlPlane.createContext(
      "/",
      exchange =>
        requests.synchronized(
          requests += s"${exchange.getRequestMethod} ${exchange.getRequestURI.getPath}"
        )
        val (status, body) = exchange.getRequestURI.getPath match
          case "/services/checkout/cart" => 200 -> cart
          case "/services/checkout"      => 200 -> s"[$cart]"
          case _                         => 404 -> """{"message":"no such thing"}"""
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        val out = exchange.getResponseBody
        try out.write(bytes)
        finally out.close()
    )
    controlPlane.start()

  override def afterAll(): Unit = if controlPlane != null then controlPlane.stop(0)

  override def beforeEach(context: BeforeEach): Unit = requests.synchronized(requests.clear())

  private def settings = Settings(
    url = s"http://127.0.0.1:${controlPlane.getAddress.getPort}",
    token = Some("a-token"),
    project = Some("checkout")
  )

  private def server: McpServer =
    val tools = AnkkaTools(() => settings)
    McpServer("ankka", "test", AnkkaTools.Instructions, tools.all, () => tools.resources())

  /** Sends each message as a line and returns every line the server wrote. */
  private def exchange(messages: String*): Vector[Json] =
    val bytes = ByteArrayOutputStream()
    val out   = PrintStream(bytes, true, StandardCharsets.UTF_8)
    server.serve(
      BufferedReader(StringReader(messages.mkString("\n"))),
      out,
      PrintStream(ByteArrayOutputStream())
    )
    String(bytes.toByteArray, StandardCharsets.UTF_8).linesIterator.toVector.map(line =>
      Json
        .parse(line)
        .fold(
          problem => fail(s"the server wrote a line that is not JSON: $problem\n$line"),
          identity
        )
    )

  private def call(tool: String, arguments: String): Json =
    val reply = exchange(
      s"""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""
    )
    assertEquals(reply.size, 1)
    reply.head("result").getOrElse(fail(s"no result: ${reply.head.render}"))

  private def text(result: Json): String =
    result("content") match
      case Some(Json.Arr(Vector(item))) => item.string("text").getOrElse(fail("no text"))
      case other                        => fail(s"unexpected content: $other")

  test("initialize agrees the client's version when it is one the server speaks") {
    val reply = exchange(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
    ).head
    assertEquals(reply("id"), Some(Json.num(1)))
    val result = reply("result").get
    assertEquals(result.string("protocolVersion"), Some("2025-06-18"))
    assert(result("capabilities").flatMap(_("tools")).isDefined)
    assert(result("capabilities").flatMap(_("resources")).isDefined)
    assertEquals(result("serverInfo").flatMap(_.string("name")), Some("ankka"))
  }

  test("an unknown version is answered with the newest the server speaks") {
    val reply = exchange(
      """{"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"1999-01-01"}}"""
    ).head
    assertEquals(reply("id"), Some(Json.str("a")))
    assertEquals(
      reply("result").flatMap(_.string("protocolVersion")),
      Some(McpServer.SupportedVersions.head)
    )
  }

  test("a notification gets no reply, and an unknown method is an error naming it") {
    val replies = exchange(
      """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
      """{"jsonrpc":"2.0","id":2,"method":"no/such"}"""
    )
    assertEquals(replies.size, 1)
    assertEquals(replies.head("error").flatMap(_("code")), Some(Json.num(McpServer.MethodNotFound)))
  }

  test("a line that is not JSON is a parse error, and the server keeps serving") {
    val replies = exchange("{not json", """{"jsonrpc":"2.0","id":3,"method":"ping"}""")
    assertEquals(replies.head("error").flatMap(_("code")), Some(Json.num(McpServer.ParseError)))
    assertEquals(replies(1)("result"), Some(Json.obj()))
  }

  test("every tool has an object input schema and says how far it reaches") {
    val tools = exchange("""{"jsonrpc":"2.0","id":4,"method":"tools/list"}""")
      .head("result")
      .flatMap(_("tools")) match
      case Some(Json.Arr(values)) => values
      case other                  => fail(s"no tools: $other")
    val names = tools.flatMap(_.string("name"))
    assert(
      names.contains("get_service") && names.contains("apply_service") && names.contains(
        "read_doc"
      ),
      names
    )
    tools.foreach { tool =>
      assertEquals(tool("inputSchema").flatMap(_.string("type")), Some("object"), tool.render)
      assert(tool("annotations").flatMap(_("readOnlyHint")).isDefined, tool.render)
    }
    val delete = tools.find(_.string("name").contains("delete_service")).get
    assertEquals(delete("annotations").flatMap(_("destructiveHint")), Some(Json.bool(true)))
  }

  test("get_service answers with the control plane's status, in the configured project") {
    val result = call("get_service", """{"name":"cart"}""")
    assertEquals(result("isError"), Some(Json.bool(false)))
    assert(text(result).contains("\"lifecycle\": \"Ready\""), text(result))
    assertEquals(requests.toVector, Vector("GET /services/checkout/cart"))
  }

  test("a control plane refusal is a tool error the model can read, not a protocol error") {
    val result = call("get_service", """{"name":"ghost"}""")
    assertEquals(result("isError"), Some(Json.bool(true)))
    assert(text(result).contains("no such thing"), text(result))
  }

  test("an invalid descriptor is refused before anything is sent") {
    val result = call("apply_service", """{"descriptor":{"name":"Cart","service":{"image":""}}}""")
    assertEquals(result("isError"), Some(Json.bool(true)))
    assert(text(result).contains("service name 'Cart' is invalid"), text(result))
    assert(text(result).contains("service image must not be empty"), text(result))
    assertEquals(requests.toVector, Vector.empty)
  }

  test("the documentation of this version is on the classpath, as resources and through read_doc") {
    val resources =
      exchange("""{"jsonrpc":"2.0","id":5,"method":"resources/list"}""")
        .head("result")
        .flatMap(_("resources")) match
        case Some(Json.Arr(values)) => values
        case other                  => fail(s"no resources: $other")
    assert(resources.flatMap(_.string("uri")).contains("ankka://docs/index.md"))
    assert(
      resources.forall(_.string("description").exists(_.nonEmpty)),
      "every page has a description"
    )

    val read = exchange(
      """{"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"ankka://docs/index.md"}}"""
    ).head
    assert(read.render.contains("# ankka"), read.render.take(300))

    assert(
      text(call("read_doc", """{"path":"reference/cli.md"}""")).contains("ankka services apply")
    )
    assert(
      text(call("search_docs", """{"query":"expose hostname"}""")).contains("deploy/expose.md")
    )
  }
