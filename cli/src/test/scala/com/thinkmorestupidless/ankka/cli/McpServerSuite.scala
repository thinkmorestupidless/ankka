package com.thinkmorestupidless.ankka.cli

import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.cli.mcp.{AnkkaTools, Json, McpServer, ReleaseStatus}
import munit.FunSuite

import java.io.{BufferedReader, ByteArrayOutputStream, PrintStream, StringReader}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}
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

  // The latest release, served beside the stub control plane, and the remembered check's directory.
  @volatile private var latest: String    = """{"version":"0.16.0","released":"2026-11-06"}"""
  @volatile private var latestStatus: Int = 200
  @volatile private var latestDelay: Long = 0
  @volatile private var installed: String = "0.16.0"
  private var dir: Path                   = null

  private val cart =
    """{"name":"cart","projectId":"checkout","lifecycle":"Ready","generation":3,""" +
      """"image":"cart:1.0.0","readyInstances":1,"desiredInstances":1,""" +
      """"topicSources":[{"kind":"consumer","component":"relay","topic":"events","group":"ankka.checkout.cart.consumer.relay","start":"earliest","version":1,"lag":60}],""" +
      """"topicChecks":[{"topic":"orders","service":"cart","component":"consumer:relay","direction":"publishes","stated":"order.v1","state":"checked"}]}"""

  override def beforeAll(): Unit =
    controlPlane = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    controlPlane.createContext(
      "/",
      exchange =>
        requests.synchronized(
          requests += s"${exchange.getRequestMethod} ${exchange.getRequestURI.getPath}"
        )
        val (status, body) = exchange.getRequestURI.getPath match
          case "/latest.json" =>
            if latestDelay > 0 then Thread.sleep(latestDelay)
            latestStatus -> latest
          case "/auth/whoami"            => 200 -> """{"subject":"ada","name":"Ada"}"""
          case "/installation"           => 200 -> s"""{"platformVersion":"$installed"}"""
          case "/services/checkout/cart" => 200 -> cart
          case "/services/checkout"      => 200 -> s"[$cart]"
          case "/projects/checkout/erasures" if exchange.getRequestMethod == "POST" =>
            val asked = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
            requests.synchronized(requests += asked)
            201 -> """{"id":"01J","project":"checkout","subject":"player/8c1f","state":"held","askedBy":"ada"}"""
          case "/projects/checkout/erasures/01J/override" =>
            403 -> """{"status":403,"error":"owner role required in organization 'acme'"}"""
          case _ => 404 -> """{"message":"no such thing"}"""
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        val out = exchange.getResponseBody
        try out.write(bytes)
        finally out.close()
    )
    controlPlane.start()

  override def afterAll(): Unit = if controlPlane != null then controlPlane.stop(0)

  override def beforeEach(context: BeforeEach): Unit =
    requests.synchronized(requests.clear())
    latest = """{"version":"0.16.0","released":"2026-11-06"}"""
    latestStatus = 200
    latestDelay = 0
    installed = "0.16.0"
    dir = Files.createTempDirectory("ankka-mcp-release").toRealPath()
    sys.props("ankka.config") = dir.resolve("config.json").toString

  // Clean up while the override is still in place: `Credentials.path` follows it.
  override def afterEach(context: AfterEach): Unit =
    Files
      .walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(p => Files.deleteIfExists(p): Unit)
    sys.props.remove("ankka.config"): Unit

  private def settings = Settings(
    url = s"http://127.0.0.1:${controlPlane.getAddress.getPort}",
    token = Some("a-token"),
    project = Some("checkout")
  )

  private def server: McpServer = serverWith(None)

  private def serverWith(release: Option[ReleaseStatus]): McpServer =
    val tools =
      release.fold(AnkkaTools(() => settings))(r => AnkkaTools(() => settings, release = r))
    McpServer("ankka", "test", AnkkaTools.Instructions, tools.all, () => tools.resources(), release)

  /** Sends each message as a line and returns every line the server wrote. */
  private def exchange(messages: String*): Vector[Json] = exchangeWith(server, messages*)

  private def exchangeWith(server: McpServer, messages: String*): Vector[Json] =
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

  test(
    "started at a terminal, the server says on stderr what it is waiting for, and stdout stays protocol"
  ) {
    def run(interactive: Boolean): (String, String) =
      val out = ByteArrayOutputStream()
      val err = ByteArrayOutputStream()
      server.serve(
        BufferedReader(StringReader("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")),
        PrintStream(out, true, StandardCharsets.UTF_8),
        PrintStream(err, true, StandardCharsets.UTF_8),
        interactive
      )
      (
        String(out.toByteArray, StandardCharsets.UTF_8),
        String(err.toByteArray, StandardCharsets.UTF_8)
      )

    val (out, err) = run(interactive = true)
    assert(err.contains("claude mcp add ankka -- ankka mcp"), err)
    assertEquals(out.linesIterator.toVector, Vector("""{"jsonrpc":"2.0","id":1,"result":{}}"""))

    val (_, quiet) = run(interactive = false)
    assertEquals(quiet, "")
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

  test("the erasure tools are listed, and asking for an erasure is marked destructive") {
    val tools = exchange("""{"jsonrpc":"2.0","id":4,"method":"tools/list"}""")
      .head("result")
      .flatMap(_("tools")) match
      case Some(Json.Arr(values)) => values
      case other                  => fail(s"no tools: $other")
    val names = tools.flatMap(_.string("name")).toSet
    Seq(
      "erasures_list",
      "erasure_get",
      "erasure_file",
      "erasure_replace",
      "erasure_withdraw",
      "erasure_override",
      "erasure_certificate"
    ).foreach(n => assert(names.contains(n), s"$n is not among $names"))
    def hint(name: String, which: String) =
      tools.find(_.string("name").contains(name)).flatMap(_("annotations")).flatMap(_(which))
    assertEquals(hint("erasure_file", "destructiveHint"), Some(Json.bool(true)))
    // A second ask makes a second request: asking again is not the same as asking once.
    assertEquals(hint("erasure_file", "idempotentHint"), Some(Json.bool(false)))
    assertEquals(hint("erasure_certificate", "readOnlyHint"), Some(Json.bool(true)))
  }

  test("erasure_file asks as the logged-in member, with the date held from the start of its day") {
    val result =
      call("erasure_file", """{"subject":"player/8c1f","notBefore":"2027-01-31","reason":"aml"}""")
    assertEquals(result("isError"), Some(Json.bool(false)), text(result))
    assert(text(result).contains("\"state\": \"held\""), text(result))
    assertEquals(
      requests.toVector,
      Vector(
        "POST /projects/checkout/erasures",
        """{"subject":"player/8c1f","notBefore":"2027-01-31T00:00:00Z","reason":"aml"}"""
      )
    )
  }

  test("an override refused to a member is a tool error naming why") {
    val result = call("erasure_override", """{"request":"01J","reason":"regulator order"}""")
    assertEquals(result("isError"), Some(Json.bool(true)))
    assert(text(result).contains("owner role required"), text(result))
  }

  test("get_service answers with the control plane's status, in the configured project") {
    val result = call("get_service", """{"name":"cart"}""")
    assertEquals(result("isError"), Some(Json.bool(false)))
    assert(text(result).contains("\"lifecycle\": \"Ready\""), text(result))
    // features/topics/status.feature: the server shows each topic source with its lag, and the checks
    assert(text(result).contains("\"lag\": 60"), text(result))
    assert(text(result).contains("\"topicChecks\""), text(result))
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

  // `features/documentation/mcp-endpoints.feature`: the coding agent asks `ankka mcp` what a local
  // service serves, and is told its MCP endpoint and tools beside its routes.
  test("a coding agent is told a local service's tools") {
    object Cart extends console.Source:
      def services(): Vector[console.ServiceSummary] = Vector.empty
      def service(name: String): Option[String]      =
        Option.when(name == "cart")(
          """{"name":"cart","instances":[],"components":[],"routes":[""" +
            """{"method":"GET","path":"/carts/{cartId}","streaming":false},""" +
            """{"method":"MCP","path":"/mcp get_cart","streaming":false},""" +
            """{"method":"MCP","path":"/mcp add_item","streaming":false}]}"""
        )
      def traces(name: String): Option[String]                                           = None
      def topology(name: String): Option[console.QueryResponse]                          = None
      def trace(name: String, id: String): Option[String]                                = None
      def session(name: String, id: String): Option[String]                              = None
      def invoke(name: String, r: console.InvokeRequest): Option[console.InvokeResponse] = None
      def invokeStream(name: String, r: console.InvokeRequest, onChunk: String => Unit): Boolean =
        false
      def query(n: String, c: String, i: String, m: String): Option[console.QueryResponse] = None
    val tools = AnkkaTools(() => settings, () => Cart)
    val tool  = tools.all
      .find(_.name == "describe_local_service")
      .getOrElse(fail("no describe_local_service"))
    assert(tool.description.contains("MCP endpoints and their tools"), tool.description)
    val agent =
      McpServer("ankka", "test", AnkkaTools.Instructions, tools.all, () => tools.resources())
    val bytes = ByteArrayOutputStream()
    agent.serve(
      BufferedReader(
        StringReader(
          """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"describe_local_service","arguments":{"service":"cart"}}}"""
        )
      ),
      PrintStream(bytes, true, StandardCharsets.UTF_8),
      PrintStream(ByteArrayOutputStream())
    )
    val told = String(bytes.toByteArray, StandardCharsets.UTF_8)
    Seq("GET", "/carts/{cartId}", "/mcp get_cart", "/mcp add_item").foreach(fact =>
      assert(told.contains(fact), s"the coding agent was not told $fact: $told")
    )
  }

  // ── features/command-line/new-release-notice.feature, User Story 2 ────────
  //
  // The command line is fixed at release 0.13.0, the latest release is the stub's `/latest.json`, and
  // the clock is the test's, so "a day has passed" is a step rather than a wait.

  private val Notice16 = "ankka version 0.16.0 is available (you're currently using version 0.13.0)"

  @volatile private var now: Instant = Instant.now()

  private def latestUrl = s"http://127.0.0.1:${controlPlane.getAddress.getPort}/latest.json"

  private def status(enabled: Boolean = true): ReleaseStatus =
    ReleaseStatus("0.13.0", enabled, latestUrl, () => now).start()

  private def callWith(server: McpServer, tool: String, arguments: String = "{}"): Json =
    val reply = exchangeWith(
      server,
      s"""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}"""
    )
    reply.head("result").getOrElse(fail(s"no result: ${reply.head.render}"))

  private def answer(server: McpServer, tool: String, arguments: String = "{}"): Json =
    Json.parse(text(callWith(server, tool, arguments))).fold(problem => fail(problem), identity)

  private def instructions(server: McpServer): String =
    exchangeWith(
      server,
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}"""
    ).head("result").flatMap(_.string("instructions")).getOrElse(fail("no instructions"))

  test("the command line's MCP server gives the notice in its instructions") {
    // Nothing remembered and a read that takes 300 ms: the instructions carry the notice only when
    // `initialize` waits for the first read.
    latestDelay = 300
    val said = instructions(serverWith(Some(status())))
    assert(said.startsWith(AnkkaTools.Instructions), said)
    assert(said.contains(Notice16), said)
  }

  test(
    "a command's attempt to read just before the server starts does not stop the server reading"
  ) {
    // The command line tried a minute ago and was cut short; the server reads regardless.
    ReleaseCheck.remember(RememberedCheck(attemptedAt = Some(now.minusSeconds(60))))
    val said = instructions(serverWith(Some(status())))
    assert(said.contains(Notice16), said)
  }

  test(
    "every page of documentation the model reads says it is the command line's release's and a newer one exists"
  ) {
    val release = status()
    release.awaitStart()
    val page = text(callWith(serverWith(Some(release)), "read_doc", """{"path":"index.md"}"""))
    val note = page.linesIterator.next()
    assert(note.contains("the documentation of ankka 0.13.0"), note)
    assert(note.endsWith(Notice16), note)
    assert(page.contains("\n\n"), page)
  }

  test(
    "the pages the model finds by keywords say they are the command line's release's and a newer one exists"
  ) {
    val release = status()
    release.awaitStart()
    val found = text(callWith(serverWith(Some(release)), "search_docs", """{"query":"view"}"""))
    val note  = found.linesIterator.next()
    assert(note.contains("the documentation of ankka 0.13.0"), note)
    assert(note.endsWith(Notice16), note)
  }

  test("the model asking who it is is told the command line's release and the latest release") {
    val release = status()
    release.awaitStart()
    val told = answer(serverWith(Some(release)), "whoami")
    assertEquals(told.string("subject"), Some("ada"))
    val said = told("release").getOrElse(fail(s"no release: ${told.render}"))
    assertEquals(said.string("cli"), Some("0.13.0"))
    assertEquals(said.string("latest"), Some("0.16.0"))
    assertEquals(said.bool("available"), Some(true))
    assertEquals(said.string("notice"), Some(Notice16))
  }

  test(
    "the model asking for the installation is told when the installation is at a newer release than the command line"
  ) {
    // The check turned off: what the installation runs is said whether or not a read happened.
    val said =
      answer(serverWith(Some(status(enabled = false))), "installation_status")("release").get
    assertEquals(said.string("installation"), Some("0.16.0"))
    assertEquals(said.string("cli"), Some("0.13.0"))
    assertEquals(said.bool("installationNewer"), Some(true))
  }

  test("an MCP server that runs for more than a day reads the latest release again") {
    latest = """{"version":"0.13.0","released":"2026-10-20"}"""
    val release = status()
    release.awaitStart()
    val server = serverWith(Some(release))
    assertEquals(answer(server, "whoami")("release").flatMap(_.string("latest")), Some("0.13.0"))
    latest = """{"version":"0.16.0","released":"2026-11-06"}"""
    release.tick() // less than a day: nothing read
    assertEquals(answer(server, "whoami")("release").flatMap(_.string("latest")), Some("0.13.0"))
    now = now.plus(Duration.ofHours(25))
    release.tick()
    assertEquals(answer(server, "whoami")("release").flatMap(_.string("latest")), Some("0.16.0"))
  }

  test("a latest release that cannot be read changes nothing the MCP server answers") {
    latestStatus = 503
    latest = "unavailable"
    val release = status()
    release.awaitStart()
    val unreadable = text(callWith(serverWith(Some(release)), "whoami"))
    val off        = text(callWith(serverWith(Some(status(enabled = false))), "whoami"))
    assertEquals(unreadable, off)
    assertEquals(instructions(serverWith(Some(release))), AnkkaTools.Instructions)
    assert(
      !text(callWith(serverWith(Some(release)), "read_doc", """{"path":"index.md"}"""))
        .startsWith("Note:")
    )
  }

  test("the release the server read is remembered as the command line's is") {
    status().awaitStart()
    val file = dir.resolve("release-check.json")
    assert(Files.readString(file).contains("0.16.0"))
  }

  test("an installation at the command line's release is not said to be newer") {
    installed = "0.13.0"
    val said =
      answer(serverWith(Some(status(enabled = false))), "installation_status")("release").get
    assertEquals(said.bool("installationNewer"), None)
    assertEquals(said("latest"), None)
  }
