package com.thinkmorestupidless.ankka.mcp.features

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.agent.mcp.{McpClient, McpTransport}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.mcp.McpRuntime
import com.thinkmorestupidless.ankka.mcp.TestTools.MovableClock
import com.thinkmorestupidless.ankka.runtime.Observability
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, GherkinSuite, LogCapturing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * The steps of the MCP endpoint features, over one service on loopback per scenario: the cart and
 * job fixtures, the jobs endpoint and the cart's MCP endpoint at `/mcp`, built as the scenario's
 * `Given`s say and started at its first `When`.
 *
 * "An MCP client" is the agent module's own `McpClient` where a listing or a call is all the step
 * reads, so the platform's two halves are held to each other, and a raw request where a step reads
 * what the client hides (a status, a header, structured content).
 *
 * Every secret a scenario mints is searched for, after the scenario, in the captured log, every
 * span the recorder holds and every listing the scenario read (SC-005), so every feature file run
 * through this class inherits that check.
 */
abstract class McpEndpointSteps(feature: String) extends GherkinSuite(feature) with LogCapturing:
  import Fixtures.*

  override val munitTimeout = 3.minutes

  // ── The scenario's service ─────────────────────────────────────────────────

  protected var references                 = References()
  protected var endpointAcl: Acl           = Acl.AllowAll
  protected var kit: Option[AnkkaTestKit]  = None
  protected var server: Option[HttpServer] = None

  /** What a refusal scenario starts instead of the cart, and what its start said. */
  protected var shop: Option[Vector[EndpointClients => HttpEndpoint]] = None
  protected var noSchema                                              = false
  protected var startFailure: Option[String]                          = None

  /** The plumbers of client-keys.feature instead of the cart, and their other instances. */
  protected var plumbing = false
  protected var clock    = MovableClock(java.time.Instant.now())
  protected var peers: Vector[(AnkkaTestKit.Peer, HttpServer)] = Vector.empty
  protected var officeKit: Option[(AnkkaTestKit, HttpServer)]  = None

  /** The orders of access.feature instead of the cart: their ACL, a tool's own, and the issuer. */
  protected var ordering                                                              = false
  protected var ordersAcl: Acl                                                        = Acl.DenyAll
  protected var cancelAcl: Option[Acl]                                                = None
  protected var customers: Option[com.thinkmorestupidless.ankka.auth.oidc.TestIssuer] = None

  /**
   * observing.feature: the cart's endpoint behind client keys, with the key routes and the runtime.
   */
  protected var observing = false

  /** brokered-sign-in.feature: the orders' endpoint opts into brokered sign-in. */
  protected var brokeredSignIn = false

  /** Every secret the scenario minted, and everything it read that a secret must not be in. */
  protected val minted = mutable.Set.empty[String]
  protected val read   = mutable.ListBuffer.empty[String]

  override def beforeEach(context: BeforeEach): Unit =
    references = References()
    endpointAcl = Acl.AllowAll
    shop = None
    noSchema = false
    startFailure = None
    minted.clear()
    read.clear()
    ran.clear()
    last = None
    listed = Vector.empty
    plumbing = false
    clock = MovableClock(java.time.Instant.now())
    told.clear()
    issued = None
    issueAnswer = None
    listing = None
    revokeDelays = Vector.empty
    traceRead = Vector.empty
    ordering = false
    observing = false
    ordersAcl = Acl.DenyAll
    cancelAcl = None
    brokeredSignIn = false
    callersTold.clear()
    lastToken = None
    metadata = None

  override def afterEach(context: AfterEach): Unit =
    peers.foreach(_._1.stop())
    peers = Vector.empty
    officeKit.foreach(_._1.stop())
    officeKit = None
    customers.foreach(_.stop())
    customers = None
    kit.foreach(_.stop())
    kit = None
    server = None

  /** SC-005, after every scenario and before its log is discarded. */
  override def munitTestTransforms: List[TestTransform] =
    new TestTransform(
      "no secret on any wire",
      test =>
        test.withBodyMap(
          _.map { value =>
            if minted.nonEmpty then
              val places = Vector("the captured log" -> capturedLog) ++
                read.zipWithIndex.map((text, i) => s"read $i" -> text) ++
                spanTexts.map("a span" -> _)
              for secret <- minted; (where, text) <- places do
                assert(!text.contains(secret), s"a minted secret was found in $where")
              assert(capturingLogs || sys.props.get("ankka.test.logs").contains("all"))
            value
          }(using munitExecutionContext)
        )
    ) +: super.munitTestTransforms

  private def spanTexts: Vector[String] =
    kit.toVector.flatMap { k =>
      val observability = Observability(k.service.system)
      observability.recorder
        .snapshot()
        .map(span =>
          s"${observability.names.nameOf(span.handlerRef).getOrElse("")} ${span.subject.getOrElse("")} ${span.attributes}"
        )
    }

  /** Starts the scenario's service, the first time a step needs it. */
  protected def running: AnkkaTestKit = kit.getOrElse {
    if ordering then
      val acl      = ordersAcl
      val own      = cancelAcl
      val brokered = brokeredSignIn
      val started  = HttpServer.at("127.0.0.1", 0)(
        _ => OrdersMcp(acl, own, brokered),
        clients => KeysEndpoint(clients)
      )
      // Brokered sign-in forgets its records through timers, so it needs the service's TimerRuntime.
      val timers =
        if brokered then Seq(com.thinkmorestupidless.ankka.runtime.TimerRuntime()) else Nil
      val k = AnkkaTestKit.start(
        McpRuntime.descriptors,
        Seq(started, McpRuntime(clock, 1.minute)) ++ timers
      )
      kit = Some(k)
      server = Some(started)
      awaitReady(k.service)
      k
    else if plumbing then
      // The runtime after the server, on purpose: extensions start in the order given, and a key's
      // ACL must work whichever comes first.
      val started = HttpServer.at("127.0.0.1", 0)(plumbers*)
      val k = AnkkaTestKit.start(McpRuntime.descriptors, Seq(started, McpRuntime(clock, 1.minute)))
      kit = Some(k)
      server = Some(started)
      awaitReady(k.service)
      k
    else if observing then
      val acl     = endpointAcl
      val started = HttpServer.at("127.0.0.1", 0)(
        clients => CartMcp(clients, references, acl),
        clients => KeysEndpoint(clients)
      )
      val k = AnkkaTestKit.start(
        Seq(CartEntity.descriptor) ++ McpRuntime.descriptors,
        Seq(started, McpRuntime(clock, 1.minute))
      )
      kit = Some(k)
      server = Some(started)
      awaitReady(k.service)
      k
    else
      val started = HttpServer.at("127.0.0.1", 0)(
        clients => CartMcp(clients, references, endpointAcl),
        _ => JobsEndpoint()
      )
      val k = AnkkaTestKit.start(Seq(CartEntity.descriptor, JobEntity.descriptor), Seq(started))
      kit = Some(k)
      server = Some(started)
      k
  }

  /** Until the service's client keys have replayed: a request before then is answered 503. */
  protected def awaitReady(service: com.thinkmorestupidless.ankka.runtime.AnkkaService): Unit =
    val ready    = service.extension[McpRuntime].flatMap(_.readiness).getOrElse(() => true)
    val deadline = 30.seconds.fromNow
    while !ready() && deadline.hasTimeLeft() do Thread.sleep(20)
    assert(ready(), "the client key index never caught up")

  /** Another instance of the plumbers, its own server and runtime, beside the first. */
  protected def startInstance(): Unit =
    val started = HttpServer.at("127.0.0.1", 0)(plumbers*)
    val peer    = running.startPeer(Seq(started, McpRuntime(clock, 1.minute)))
    awaitReady(peer.service)
    peers = peers :+ (peer -> started)

  /** Every instance's MCP endpoint, the first one first. */
  protected def instances: Vector[String] =
    url +: peers.map((_, s) => s"http://127.0.0.1:${s.boundPort.get}/mcp")

  protected def url: String = s"http://127.0.0.1:${running.pipe(_ => server.get.boundPort.get)}/mcp"

  extension [A](a: A) private def pipe[B](f: A => B): B = f(a)

  // ── Talking to it ─────────────────────────────────────────────────────────

  protected val http: HttpClient = HttpClient.newHttpClient()

  final case class Answer(status: Int, headers: Map[String, String], body: Option[Json]):
    def result: Json = body.flatMap(_("result")).getOrElse(fail(s"no result in $body"))
    def error: Json  = body.flatMap(_("error")).getOrElse(fail(s"no error in $body"))

  protected var last: Option[Answer] = None
  protected var listed: Vector[Json] = Vector.empty

  protected def raw(
      method: String,
      body: Option[String],
      headers: (String, String)*
  ): (Answer, FiniteDuration) = rawTo(url, method, body, headers*)

  protected def rawTo(
      address: String,
      method: String,
      body: Option[String],
      headers: (String, String)*
  ): (Answer, FiniteDuration) =
    val builder =
      HttpRequest.newBuilder(URI.create(address)).timeout(java.time.Duration.ofSeconds(30))
    (("Content-Type" -> "application/json") +: ("Accept" -> "application/json, text/event-stream") +:
      headers).foreach((n, v) => builder.header(n, v))
    builder.method(
      method,
      body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
    )
    val started  = System.nanoTime()
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    val took     = (System.nanoTime() - started).nanos
    val text     = response.body()
    read += text
    val headersOf = response
      .headers()
      .map()
      .entrySet()
      .toArray
      .collect { case e: java.util.Map.Entry[?, ?] =>
        e.getKey.toString.toLowerCase -> e.getValue.toString
      }
      .toMap
    (Answer(response.statusCode(), headersOf, Json.parse(text).toOption), took)

  protected def rpc(method: String, params: Json, headers: (String, String)*): Answer =
    rpcTo(url, method, params, headers*)

  protected def rpcTo(
      address: String,
      method: String,
      params: Json,
      headers: (String, String)*
  ): Answer =
    val message = Json.obj(
      "jsonrpc" -> Json.str("2.0"),
      "id"      -> Json.num(1),
      "method"  -> Json.str(method),
      "params"  -> params
    )
    val (answer, _) = rawTo(address, "POST", Some(message.render), headers*)
    last = Some(answer)
    answer

  protected def client: McpClient =
    val c = McpClient("cart", McpTransport.Url(url, 5.seconds, 30.seconds), Nil, "test")
    c.initialize()
    c

  /** `"cartId c1, quantity 0"` as arguments: a whole number is a number, anything else a string. */
  protected def arguments(text: String): Json =
    Json.Obj(
      text
        .split(',')
        .map(_.trim)
        .filter(_.nonEmpty)
        .map { pair =>
          val (name, value) = pair.span(_ != ' ')
          name -> value.trim.toLongOption.fold(Json.str(value.trim))(n => Json.num(n.toDouble))
        }
        .toMap
    )

  protected def call(tool: String, args: Json): Answer =
    rpc("tools/call", Json.obj("name" -> Json.str(tool), "arguments" -> args))

  protected def listTools(): Vector[Json] =
    listed = rpc("tools/list", Json.obj()).result("tools").flatMap(_.asArray).get
    listed

  protected def toolNamed(name: String): Json =
    listed.find(_("name").flatMap(_.asString).contains(name)).getOrElse(fail(s"$name not listed"))

  protected def text(answer: Answer): String =
    answer
      .result("content")
      .flatMap(_.asArray)
      .flatMap(_.headOption)
      .flatMap(_("text"))
      .flatMap(_.asString)
      .getOrElse(fail(s"no text in ${answer.body}"))

  // ── Starting a service that should not start ─────────────────────────────

  protected def startShop(): Unit =
    if noSchema then
      // A Scala tool whose arguments have no schema is refused before the service exists: it does not compile.
      startFailure = Some(
        compileErrors(
          """
          final class NoSchema(n: Int)
          final class Bad extends com.thinkmorestupidless.ankka.mcp.McpEndpoint("/mcp"):
            val acl = com.thinkmorestupidless.ankka.http.Acl.AllowAll
            tool("get_cart").describedAs("x").arguments[NoSchema].handle((_: NoSchema) => "x")
          """
        )
      )
    else
      val factories = shop.getOrElse(fail("no shop was declared"))
      val started   = HttpServer.at("127.0.0.1", 0)(factories*)
      startFailure =
        try
          kit = Some(
            AnkkaTestKit.start(Seq(CartEntity.descriptor, JobEntity.descriptor), Seq(started))
          )
          None
        catch
          case NonFatal(failure) =>
            Some(
              Iterator
                .iterate[Throwable](failure)(_.getCause)
                .takeWhile(_ != null)
                .map(t => String.valueOf(t.getMessage))
                .mkString("\n")
            )

  protected def reason: String = startFailure.getOrElse(fail("the service started"))

  protected def shopWith(declare: ShopMcp => Unit): Unit =
    shop = Some(Vector(_ => ShopMcp(declare), _ => JobsEndpoint()))

  // ── Steps: tools.feature ──────────────────────────────────────────────────

  Given(
    "a service {string} with an MCP endpoint at {string} whose tools are {string}, {string} and {string}"
  ) { (service: String, path: String, a: String, b: String, c: String) =>
    assertEquals((service, path), ("cart", "/mcp"))
    assertEquals(Vector(a, b, c), Vector("get_cart", "add_item", "checkout"))
  }

  Given("the ACL of the MCP endpoint allows all") { () => endpointAcl = Acl.AllowAll }

  When("an MCP client asks the MCP endpoint for its tools") { () =>
    // Through the platform's own client first: what an agent of another service would be told.
    val names = client.listTools().map(_.name)
    listTools()
    assertEquals(listed.flatMap(_("name")).flatMap(_.asString), names)
  }

  Then("the MCP client is told the tools {string}, {string} and {string}") {
    (a: String, b: String, c: String) =>
      assertEquals(listed.flatMap(_("name")).flatMap(_.asString), Vector(a, b, c))
  }

  Then("each tool is told with the description and the arguments it declares") { () =>
    val declared = Map(
      "get_cart" -> ("Reads one cart", Vector("cartId")),
      "add_item" -> ("Adds an item to a cart", Vector("cartId", "quantity")),
      "checkout" -> ("Checks a cart out", Vector("cartId"))
    )
    declared.foreach { case (name, (description, properties)) =>
      val told = toolNamed(name)
      assertEquals(told("description").flatMap(_.asString), Some(description))
      val schema = told("inputSchema").get
      assertEquals(
        schema("properties").collect { case Json.Obj(f) => f.keySet }.getOrElse(Set.empty),
        properties.toSet
      )
      assertEquals(
        schema("required").flatMap(_.asArray).map(_.flatMap(_.asString).toSet),
        Some(properties.toSet)
      )
    }
    val quantity =
      toolNamed("add_item")("inputSchema").flatMap(_("properties")).flatMap(_("quantity")).get
    assertEquals(quantity("type"), Some(Json.str("integer")))
    assertEquals(quantity("description"), Some(Json.str("How many; positive")))
  }

  When("an MCP client calls {string} with the arguments {string}") { (tool: String, args: String) =>
    running
    call(tool, arguments(args))
  }

  When("an MCP client calls {string}") { (tool: String) =>
    running
    call(tool, Json.obj())
  }

  Then("the handler of {string} is run with the cart {string}") { (tool: String, cartId: String) =>
    assert(runs.contains(("tool", cartId, tool, "")), runs)
    assert(runs.contains(("cart", cartId, "get-cart", "")), runs)
  }

  Then("the MCP client is told what the handler answered as the result of the tool call") { () =>
    val answer = last.get
    assertEquals(answer.result("isError"), Some(Json.bool(false)))
    val expected =
      Json.obj("cartId" -> Json.str("c1"), "items" -> Json.arr(), "checkedOut" -> Json.bool(false))
    assertEquals(answer.result("structuredContent"), Some(expected))
    assertEquals(Json.parse(text(answer)).toOption, Some(expected))
  }

  Given("the handler of {string} refuses a quantity of {string} saying {string}") {
    (tool: String, quantity: String, saying: String) =>
      assertEquals((tool, quantity, saying), ("add_item", "0", Refusal))
  }

  Then("the MCP client is told {string} as an error of the tool call") { (message: String) =>
    val answer = last.get
    assertEquals(answer.status, 200)
    assertEquals(answer.result("isError"), Some(Json.bool(true)))
    assertEquals(text(answer), message)
  }

  Then("the MCP client is told a protocol error naming {string}") { (tool: String) =>
    val error = last.get.error
    assertEquals(error("code"), Some(Json.num(-32602)))
    assert(error("message").flatMap(_.asString).exists(_.contains(tool)), error)
  }

  Then("no handler is run")(() => assertEquals(runs, Vector.empty))

  Then("the MCP client is told a protocol error saying what is wrong with {string}") {
    (argument: String) =>
      val error = last.get.error
      assertEquals(error("code"), Some(Json.num(-32602)))
      val message = error("message").flatMap(_.asString).getOrElse("")
      assert(message.contains(s"'$argument'"), message)
      assert(message.contains("expected"), message)
  }

  Then("the handler of {string} is not run") { (tool: String) =>
    assert(!runs.exists((_, _, handler, _) => handler == tool), runs)
    assert(!runs.exists((component, _, _, _) => component == "cart"), runs)
  }

  protected var opened: Option[(Answer, FiniteDuration)] = None

  When("an MCP client opens a stream on the MCP endpoint") { () =>
    running
    opened = Some(raw("GET", None, "Accept" -> "text/event-stream"))
  }

  Then("the stream is accepted and closed") { () =>
    // The request is answered whole, at once, with no event stream: the protocol's 405 for a server
    // that offers none, which a client reads as "nothing will be pushed".
    val (answer, _) = opened.get
    assertEquals(answer.status, 405)
    assertEquals(answer.headers.get("allow"), Some("[POST]"))
    assert(
      !answer.headers.get("content-type").exists(_.contains("text/event-stream")),
      answer.headers
    )
  }

  Then("the MCP client does not wait") { () =>
    val (_, took) = opened.get
    assert(took < 2.seconds, took)
  }

  Given(
    "a service {string} with an HTTP endpoint whose route is {string} and an MCP endpoint at {string}"
  ) { (_: String, route: String, path: String) =>
    val (method, routePath) = route.span(_ != ' ')
    shop = Some(
      Vector(
        _ => RootEndpoint(method, routePath.trim),
        _ =>
          ShopMcp(m => m.declared("get_cart").describedAs("Reads one cart").handle(() => "c"): Unit)
      )
    )
    assertEquals(path, "/mcp")
  }

  When("{string} starts")((_: String) => startShop())

  Then("{string} does not start")((_: String) => assert(startFailure.isDefined, "it started"))

  Then("the reason names the MCP endpoint and the route {string}") { (route: String) =>
    assert(reason.contains("MCP endpoint /mcp"), reason)
    assert(reason.contains(s"'$route'"), reason)
  }

  private var refusedTool = ""

  Given("a service {string} with an MCP endpoint that declares two tools named {string}") {
    (_: String, name: String) =>
      refusedTool = name
      shopWith { m =>
        m.declared(name).describedAs("One").handle(() => "1")
        m.declared(name).describedAs("Two").handle(() => "2"): Unit
      }
  }

  Given(
    "a service {string} with an MCP endpoint that declares a tool {string} with no description"
  ) { (_: String, name: String) =>
    refusedTool = name
    shopWith(m =>
      m.declared(name).param[String]("cartId", "The cart").handle((c: String) => c): Unit
    )
  }

  Given("a service {string} with an MCP endpoint that declares a tool named {string}") {
    (_: String, name: String) =>
      refusedTool = name
      shopWith(m => m.declared(name).describedAs("Reads one cart").handle(() => "c"): Unit)
  }

  Given(
    "a service {string} with an MCP endpoint that declares a tool whose arguments have no schema"
  ) { (_: String) =>
    refusedTool = "get_cart"
    noSchema = true
  }

  Then("the reason names the MCP endpoint and the tool") { () =>
    if noSchema then
      // The compiler's refusal: no `JsonSchema` for the argument type, at the tool's declaration.
      assert(reason.contains("JsonSchema") || reason.contains("NoSchema"), reason)
    else
      assert(reason.contains("MCP endpoint /mcp"), reason)
      assert(reason.contains(s"'$refusedTool'"), reason)
  }

  Given(
    "the tool {string} of the MCP endpoint of {string} is the command {string} of the event sourced entity {string}, with the id passed as {string}"
  ) { (tool: String, _: String, command: String, entity: String, id: String) =>
    assertEquals((tool, command, entity, id), ("book_job", "book", "job", "jobId"))
    references = references.copy(bookJob = true)
  }

  Then("the command {string} of the entity {string} with the id {string} is called with {string}") {
    (command: String, entity: String, id: String, input: String) =>
      val (name, value) = input.span(_ != ' ')
      assertEquals(name, "slot")
      assert(runs.contains((entity, id, command, value.trim)), runs)
  }

  Then("the MCP client is told the command's reply as the result of the tool call") { () =>
    val answer = last.get
    assertEquals(answer.result("isError"), Some(Json.bool(false)))
    assertEquals(
      answer.result("structuredContent"),
      Some(Json.obj("jobId" -> Json.str("j1"), "slot" -> Json.str("thursday-morning")))
    )
  }

  Given(
    "the tool {string} of the MCP endpoint of {string} is the query {string} of the event sourced entity {string}, with the id passed as {string}"
  ) { (tool: String, _: String, query: String, entity: String, id: String) =>
    assertEquals((tool, query, entity, id), ("get_job", "get", "job", "jobId"))
    references = references.copy(getJob = Some("Reads one job"))
  }

  Then(
    "the MCP client is told what the query {string} of the entity {string} with the id {string} answers"
  ) { (_: String, _: String, id: String) =>
    val direct = running.componentClient
      .forEventSourcedEntity(com.thinkmorestupidless.ankka.core.EntityId(id))
      .call(JobEntity.get)
      .invoke()
    val answer = last.get
    assertEquals(answer.result("isError"), Some(Json.bool(false)))
    assertEquals(
      answer.result("structuredContent"),
      Json.parse(com.github.plokhotnyuk.jsoniter_scala.core.writeToString(direct)).toOption
    )
  }

  Given(
    "the tool {string} of the MCP endpoint of {string} is the route {string} of an HTTP endpoint of {string}"
  ) { (tool: String, _: String, route: String, _: String) =>
    assertEquals((tool, route), ("todays_jobs", "GET /jobs/{day}"))
    references = references.copy(todaysJobs = true)
  }

  Then("the tool {string} is told with the argument {string}") { (tool: String, argument: String) =>
    val schema = toolNamed(tool)("inputSchema").get
    assertEquals(schema("properties").collect { case Json.Obj(f) => f.keySet }, Some(Set(argument)))
    assertEquals(schema("required"), Some(Json.arr(Json.str(argument))))
  }

  Then(
    "a call to {string} with the arguments {string} is served as a request to {string} with the day {string} is"
  ) { (tool: String, args: String, route: String, day: String) =>
    val answer = call(tool, arguments(args))
    assertEquals(route, "GET /jobs/{day}")
    assert(runs.contains(("jobs-endpoint", day, route, "")), runs)
    val (direct, _) = rawAt(s"/jobs/$day")
    assertEquals(answer.result("structuredContent"), direct.body)
  }

  private def rawAt(path: String): (Answer, FiniteDuration) =
    val request  = HttpRequest.newBuilder(URI.create(url.stripSuffix("/mcp") + path)).GET().build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    (Answer(response.statusCode(), Map.empty, Json.parse(response.body()).toOption), Duration.Zero)

  Given(
    "the tool {string} of the MCP endpoint of {string} is the query {string} of the event sourced entity {string}, with the id passed as {string} and the description {string}"
  ) { (tool: String, _: String, query: String, entity: String, id: String, description: String) =>
    assertEquals((tool, query, entity, id), ("get_job", "get", "job", "jobId"))
    references = references.copy(getJob = Some(description))
  }

  Then(
    "the MCP client is told the tool {string} with the description {string} and the argument {string}"
  ) { (tool: String, description: String, argument: String) =>
    val told = toolNamed(tool)
    assertEquals(told("description").flatMap(_.asString), Some(description))
    assertEquals(
      told("inputSchema").flatMap(_("properties")).collect { case Json.Obj(f) => f.keySet },
      Some(Set(argument))
    )
  }

  Then("nothing the MCP client is told says whether {string} has a handler or is a query") {
    (tool: String) =>
      val told      = toolNamed(tool)
      val asHandler = toolNamed("get_cart")
      assertEquals(
        told.asInstanceOf[Json.Obj].fields.keySet,
        asHandler.asInstanceOf[Json.Obj].fields.keySet
      )
      val rendered = told.render.toLowerCase
      Seq("query", "handler", "command", "\"get\"", "job entity").foreach(word =>
        assert(!rendered.contains(word), s"'$word' in $rendered")
      )
  }

  Given(
    "a service {string} with an MCP endpoint whose tool {string} is the command {string} of the event sourced entity {string}, which has no such command"
  ) { (_: String, tool: String, command: String, entity: String) =>
    refusedTool = tool
    assertEquals(entity, "job")
    // A handle the entity does not declare: the wire name no registered handler has.
    val book   = JobEntity.book
    val vanish = com.thinkmorestupidless.ankka.sdk.CommandHandle[JobEntity, String, Job](
      book.componentId,
      com.thinkmorestupidless.ankka.core.MethodName(command),
      readOnly = false,
      book.inputSerializer,
      book.outputSerializer,
      (_, _) => ()
    )
    shopWith(m =>
      m.declared(tool).describedAs("Vanishes").command(vanish, id = "jobId", input = "slot"): Unit
    )
  }

  Given(
    "a service {string} with an MCP endpoint whose tool {string} is the route {string} of an HTTP endpoint of {string}, which has no such route"
  ) { (_: String, tool: String, route: String, _: String) =>
    refusedTool = tool
    val (method, path) = route.span(_ != ' ')
    shopWith(m => m.declared(tool).describedAs("Nowhere").route("/jobs", method, path.trim): Unit)
  }

  Then("the reason names the MCP endpoint and the tool {string}") { (tool: String) =>
    assert(reason.contains("MCP endpoint /mcp"), reason)
    assert(reason.contains(s"'$tool'"), reason)
  }

  Given(
    "a service {string} with an MCP endpoint whose tool {string} is the query {string} of the event sourced entity {string} with no description"
  ) { (_: String, tool: String, query: String, entity: String) =>
    refusedTool = tool
    assertEquals((query, entity), ("get", "job"))
    shopWith(m => m.declared(tool).query(JobEntity.get, id = "jobId"): Unit)
  }

  // ── Steps: client-keys.feature ────────────────────────────────────────────

  import com.thinkmorestupidless.ankka.http.{ClientKeySummary, Expiry, IssuedClientKey}
  import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
  import Fixtures.given

  /** The key the scenario holds, and what the last issue answered. */
  protected var issued: Option[IssuedClientKey]    = None
  protected var issueAnswer: Option[(Int, String)] = None
  protected var listing: Option[String]            = None
  private var revokeDelays: Vector[FiniteDuration] = Vector.empty

  private def base(address: String) = address.stripSuffix("/mcp")

  /** A request to the plumbers' own routes, as a handler of theirs would be asked. */
  protected def keysRoute(
      method: String,
      path: String,
      body: Option[String] = None,
      at: String = url
  ): (Int, String) =
    val builder = HttpRequest
      .newBuilder(URI.create(s"${base(at)}/keys$path"))
      .timeout(java.time.Duration.ofSeconds(30))
    builder.header("Content-Type", "application/json")
    builder.method(
      method,
      body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
    )
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    read += response.body()
    (response.statusCode(), response.body())

  protected def issue(body: IssueBody): (Int, String) =
    running
    val answer = keysRoute("POST", "/issue", Some(writeToString(body)))
    // The one answer that shows the key, by design: it is not among what the scan reads.
    read.remove(read.size - 1): Unit
    issueAnswer = Some(answer)
    if answer._1 == 200 then
      val key = readFromString[IssuedClientKey](answer._2)
      minted += key.clientKey
      minted += key.clientKey.drop("ankka_ck_".length + 16)
      issued = Some(key)
    answer

  protected def issueOk(body: IssueBody): IssuedClientKey =
    val (status, text) = issue(body)
    assertEquals(status, 200, text)
    issued.get

  private def year = Some(Expiry.After(365.days))

  protected def key: IssuedClientKey = issued.getOrElse(fail("no client key was issued"))

  protected def carrying: (String, String) = "Authorization" -> s"Bearer ${key.clientKey}"

  protected def callCarrying(tool: String, at: String = url): Answer =
    rpcTo(at, "tools/call", Json.obj("name" -> Json.str(tool), "arguments" -> Json.obj()), carrying)

  private def lastTold(tool: String) =
    principalsTold
      .filter(_._1 == tool)
      .lastOption
      .map(_._2)
      .getOrElse(fail(s"$tool was told no principal; $principalsTold"))

  Given(
    "a service {string} with an MCP endpoint whose tools are {string}, {string}, {string} and {string}"
  ) { (service: String, a: String, b: String, c: String, d: String) =>
    assertEquals(service, "plumbers")
    assertEquals(Vector(a, b, c, d), PlumbingTools)
    plumbing = true
  }

  Given("the ACL of the MCP endpoint admits client keys") { () =>
    if ordering then ordersAcl = com.thinkmorestupidless.ankka.mcp.ClientKeys.authenticate()
    else if observing then endpointAcl = com.thinkmorestupidless.ankka.mcp.ClientKeys.authenticate()
    else assert(plumbing)
  }

  When(
    "a handler of {string} issues a client key for the subject {string}, labelled {string}, expiring in one year"
  ) { (_: String, subject: String, label: String) =>
    issue(IssueBody(subject, year, label = Some(label))): Unit
  }

  Then("the handler is told the client key's id and the client key") { () =>
    val (status, text) = issueAnswer.get
    assertEquals(status, 200, text)
    assert(key.id.matches("[0-9a-f]{16}"), key.id)
    assert(
      key.clientKey.matches(s"ankka_ck_${key.id}[0-9a-f]{64}"),
      "the key has not the shape it should"
    )
  }

  Then("the client key is shown nowhere again") { () =>
    val (status, text) = keysRoute("GET", "/list")
    assertEquals(status, 200, text)
    assert(text.contains(key.id), text)
    assert(!text.contains(key.clientKey.drop(25)), "the listing shows the key")
  }

  Given("a client key of {string} for the subject {string}") { (_: String, subject: String) =>
    issueOk(IssueBody(subject, year)): Unit
  }

  When("an MCP client calls {string} carrying the client key") { (tool: String) =>
    callCarrying(tool): Unit
  }

  Then("the handler of {string} is told a principal whose subject is {string}") {
    (tool: String, subject: String) =>
      assertEquals(last.get.result("isError"), Some(Json.bool(false)), last.toString)
      val principal = lastTold(tool)
      assertEquals(principal.subject, subject)
      // A key's principal says which key; a token's is the token's own claims.
      if issued.isDefined then
        assertEquals(principal.claims.get("client_key"), Some(key.id))
        assertEquals(principal.claims.get("kind"), Some("client-key"))
      else assertEquals(principal.claims.get("client_key"), None)
  }

  Given(
    "a client key of {string} for the subject {string} with the attributes {string} and {string}"
  ) { (_: String, subject: String, a: String, b: String) =>
    def pair(text: String) = text.span(_ != ' ') match { case (n, v) => n -> v.trim }
    issueOk(IssueBody(subject, year, attributes = Map(pair(a), pair(b)))): Unit
  }

  Then(
    "the handler of {string} is told a principal whose subject is {string}, whose claim {string} is {string} and whose claim {string} is {string}"
  ) { (tool: String, subject: String, k1: String, v1: String, k2: String, v2: String) =>
    val principal = lastTold(tool)
    assertEquals(principal.subject, subject)
    assertEquals(principal.claims.get(k1), Some(v1))
    assertEquals(principal.claims.get(k2), Some(v2))
  }

  When(
    "a handler of {string} issues a client key for the subject {string} with seventeen attributes"
  ) { (_: String, subject: String) =>
    issue(IssueBody(subject, year, attributes = (1 to 17).map(i => s"a$i" -> "v").toMap)): Unit
  }

  When(
    "a handler of {string} issues a client key for the subject {string} with an attribute whose name is {int} characters"
  ) { (_: String, subject: String, length: Int) =>
    issue(IssueBody(subject, year, attributes = Map(("n" * length) -> "v"))): Unit
  }

  When(
    "a handler of {string} issues a client key for the subject {string} with an attribute whose value is {int} characters"
  ) { (_: String, subject: String, length: Int) =>
    issue(IssueBody(subject, year, attributes = Map("role" -> ("v" * length)))): Unit
  }

  Then("the handler is refused") { () =>
    val (status, text) = issueAnswer.get
    assertEquals(status, 400, text)
    assertEquals(issued, None)
  }

  Then("the refusal names the attributes") { () =>
    assert(issueAnswer.get._2.contains("attribute"), issueAnswer)
  }

  private def scoped(subject: String, tools: String*) = issueOk(
    IssueBody(subject, year, scope = tools.toSet)
  ): Unit

  Given(
    "a client key of {string} for the subject {string} scoped to the tools {string}, {string} and {string}"
  ) { (_: String, subject: String, a: String, b: String, c: String) =>
    scoped(subject, a, b, c)
  }

  Given("a client key of {string} for the subject {string} scoped to the tools {string}") {
    (_: String, subject: String, a: String) => scoped(subject, a)
  }

  Given("a client key of {string} for the subject {string} scoped to no tool") {
    (_: String, subject: String) =>
      scoped(subject)
  }

  When("an MCP client asks for the tools of {string} carrying the client key") { (_: String) =>
    // The agent module's own client, as an assistant on the platform would ask, then the raw listing.
    val viaClient =
      McpClient("plumbers", McpTransport.Url(url, 5.seconds, 30.seconds), List(carrying), "test")
    viaClient.initialize()
    val names = viaClient.listTools().map(_.name)
    listed = rpc("tools/list", Json.obj(), carrying).result("tools").flatMap(_.asArray).get
    assertEquals(listed.flatMap(_("name")).flatMap(_.asString), names)
  }

  Then("the MCP client is not told the tool {string}") { (tool: String) =>
    assert(!listed.flatMap(_("name")).flatMap(_.asString).contains(tool), listed)
  }

  Then("the MCP client is told the tools {string}, {string}, {string} and {string}") {
    (a: String, b: String, c: String, d: String) =>
      assertEquals(listed.flatMap(_("name")).flatMap(_.asString), Vector(a, b, c, d))
  }

  When(
    "a handler of {string} issues a client key for the subject {string} scoped to the tool {string}"
  ) { (_: String, subject: String, tool: String) =>
    issue(IssueBody(subject, year, scope = Set(tool))): Unit
  }

  When("a handler of {string} issues a client key for a blank subject") { (_: String) =>
    issue(IssueBody(" ", year)): Unit
  }

  When("a handler of {string} issues a client key for the subject {string} that expired yesterday") {
    (_: String, subject: String) =>
      issue(
        IssueBody(subject, Some(Expiry.At(clock.instant().minus(java.time.Duration.ofDays(1)))))
      ): Unit
  }

  When("a handler of {string} issues a client key for the subject {string} with no expiry given") {
    (_: String, subject: String) => issue(IssueBody(subject, None)): Unit
  }

  Then("the refusal names the tool {string}") { (tool: String) =>
    assert(issueAnswer.get._2.contains(s"'$tool'"), issueAnswer)
  }

  Then("the refusal names the subject") { () =>
    assert(issueAnswer.get._2.contains("subject"), issueAnswer)
  }

  Then("the refusal names the expiry") { () =>
    assert(issueAnswer.get._2.contains("expiry"), issueAnswer)
  }

  Given(
    "a client key of {string} for the subject {string}, labelled {string}, that an MCP client has used"
  ) { (_: String, subject: String, label: String) =>
    issueOk(IssueBody(subject, year, label = Some(label)))
    assertEquals(callCarrying("list_jobs").result("isError"), Some(Json.bool(false)))
  }

  When("a handler of {string} lists the client keys of {string}") { (_: String, _: String) =>
    val (status, text) = keysRoute("GET", "/list")
    assertEquals(status, 200, text)
    listing = Some(text)
  }

  Then(
    "the client key is listed with its id, its subject {string}, its attributes, {string}, its scope, when it was issued, when it expires and when it was last used"
  ) { (subject: String, label: String) =>
    val text   = listing.get
    val listed =
      readFromString[Vector[ClientKeySummary]](text).find(_.id == key.id).getOrElse(fail(text))
    assertEquals((listed.subject, listed.label), (subject, Some(label)))
    assertEquals(
      (listed.attributes, listed.scope, listed.endpoint),
      (Map.empty[String, String], Set.empty[String], "/mcp")
    )
    assert(listed.expiresAt.exists(_.isAfter(listed.issuedAt)), listed.toString)
    assert(listed.lastUsed.exists(!_.isBefore(listed.issuedAt)), listed.toString)
    Seq("\"attributes\"", "\"scope\"", "\"issuedAt\"", "\"expiresAt\"", "\"lastUsed\"").foreach(
      field => assert(text.contains(field), s"$field not in $text")
    )
  }

  Then("nothing listed reads as the client key") { () =>
    val text = listing.get
    assert(
      !text.contains(key.clientKey) && !text.contains(key.clientKey.drop(25)),
      "the listing shows the key"
    )
    assert(!text.contains("digest"), text)
  }

  Given("{string} is deployed with {int} instances") { (_: String, count: Int) =>
    assert(plumbing)
    running
    (2 to count).foreach(_ => startInstance())
  }

  When("a handler of {string} revokes the client key") { (_: String) =>
    // Every instance admits the key first, by a listing, which runs no handler: what is measured
    // is the revocation reaching each instance, not the issue.
    instances.foreach { at =>
      val deadline = 10.seconds.fromNow
      while rpcTo(at, "tools/list", Json.obj(), carrying).status != 200 && deadline.hasTimeLeft() do
        Thread.sleep(20)
      assertEquals(last.get.status, 200, s"$at never admitted the key")
    }
    ran.clear()
    told.clear()
    val revoked        = System.nanoTime()
    val (status, text) = keysRoute("DELETE", s"/${key.id}")
    assertEquals(status, 200, text)
    revokeDelays = instances.map { at =>
      val deadline = 5.seconds.fromNow
      while rpcTo(at, "tools/list", Json.obj(), carrying).status != 401 && deadline.hasTimeLeft() do
        Thread.sleep(10)
      assertEquals(last.get.status, 401, s"$at still admits a revoked key")
      (System.nanoTime() - revoked).nanos
    }
    println(
      s"revoke-to-refusal (ms), first instance first: ${revokeDelays.map(_.toMillis).mkString(", ")}"
    )
  }

  Then("within one second a request carrying the client key is refused at every instance") { () =>
    assertEquals(revokeDelays.size, instances.size)
    revokeDelays.foreach(delay => assert(delay < 1.second, revokeDelays))
  }

  Then("no handler of a tool is run for it") { () =>
    instances.foreach(at => assertEquals(callCarrying("list_jobs", at).status, 401))
    assertEquals(runs, Vector.empty)
    assertEquals(principalsTold, Vector.empty)
  }

  Given("a client key of {string} for the subject {string} issued as never expiring") {
    (_: String, subject: String) =>
      issueOk(IssueBody(subject, Some(Expiry.Never))): Unit
  }

  When("an MCP client calls {string} carrying the client key two years later") { (tool: String) =>
    clock.advance(java.time.Duration.ofDays(730))
    callCarrying(tool): Unit
  }

  Given("a client key of {string} for the subject {string} that has since expired") {
    (_: String, subject: String) =>
      issueOk(IssueBody(subject, Some(Expiry.After(1.hour))))
      clock.advance(java.time.Duration.ofHours(2))
  }

  Then("the request is refused") { () =>
    // By the endpoint's ACL (no credential it reads, or one it does not admit), or by a tool's own,
    // which the protocol answers as a tool that is not available to this caller.
    val answer = last.get
    val byTool = answer.status == 200 && answer.body
      .flatMap(_("error"))
      .flatMap(_("code"))
      .contains(Json.num(-32602))
    assert(answer.status == 401 || answer.status == 403 || byTool, answer.toString)
    if !ordering then assertEquals(answer.status, 401, answer.toString)
  }

  Then("the refusal says the client key expired") { () =>
    val challenge = last.get.headers.getOrElse("www-authenticate", "")
    assert(challenge.contains("client key expired"), challenge)
  }

  When("{string} restarts") { (_: String) =>
    running.restartService()
    awaitReady(running.service)
  }

  Then("a request carrying the client key runs {string} as the subject {string}") {
    (tool: String, subject: String) =>
      assertEquals(callCarrying(tool).result("isError"), Some(Json.bool(false)), last.toString)
      assertEquals(lastTold(tool).subject, subject)
  }

  Given("a service {string} with an MCP endpoint whose ACL admits client keys") {
    (service: String) =>
      assertEquals(service, "office")
      plumbing = true
  }

  When("an MCP client calls a tool of {string} carrying the client key") { (_: String) =>
    val started = HttpServer.at("127.0.0.1", 0)(office*)
    val k = AnkkaTestKit.start(McpRuntime.descriptors, Seq(started, McpRuntime(clock, 1.minute)))
    officeKit = Some(k -> started)
    awaitReady(k.service)
    callCarrying("file_invoice", s"http://127.0.0.1:${started.boundPort.get}/mcp"): Unit
  }

  Then("no handler of {string} is run") { (_: String) =>
    assert(!principalsTold.exists(_._1 == "file_invoice"), principalsTold)
    assert(!runs.exists(_._3 == "file_invoice"), runs)
  }

  Given(
    "a client key of {string} for the subject {string} that an MCP client has used to call {string}"
  ) { (_: String, subject: String, tool: String) =>
    issueOk(IssueBody(subject, year))
    assertEquals(callCarrying(tool).result("isError"), Some(Json.bool(false)), last.toString)
  }

  private var traceRead: Vector[com.thinkmorestupidless.ankka.runtime.RecordedSpan] = Vector.empty

  When(
    "a developer reads the trace of that request, the logs of {string} and a listing of its client keys"
  ) { (_: String) =>
    traceRead = Observability(running.service.system).recorder.snapshot()
    listing = Some(keysRoute("GET", "/list")._2)
  }

  Then(
    "the trace shows the tool call to {string} as the subject {string} with the client key's id"
  ) { (tool: String, subject: String) =>
    val names = Observability(running.service.system).names
    val span  = traceRead
      .find(s => names.nameOf(s.handlerRef).contains(s"/mcp $tool"))
      .getOrElse(fail(s"no span for $tool in ${traceRead.map(s => names.nameOf(s.handlerRef))}"))
    assertEquals(names.nameOf(span.componentRef), Some("mcp"))
    assertEquals(span.subject, Some(subject))
    assertEquals(span.attributes.get("client_key"), Some(key.id))
  }

  Then("nothing in the trace, the logs or the listing reads as the client key") { () =>
    val names  = Observability(running.service.system).names
    val traces = traceRead
      .map(s => s"${names.nameOf(s.handlerRef)} ${s.subject} ${s.attributes}")
      .mkString("\n")
    val secret = key.clientKey.drop("ankka_ck_".length + 16)
    Seq("the trace" -> traces, "the log" -> capturedLog, "the listing" -> listing.get).foreach {
      (where, text) =>
        assert(!text.contains(key.clientKey), s"the key is in $where")
        assert(!text.contains(secret), s"the key's secret is in $where")
    }
    assert(
      capturingLogs || sys.props.get("ankka.test.logs").contains("all"),
      "the log was not captured"
    )
  }

  // ── Steps: access.feature ─────────────────────────────────────────────────

  import com.thinkmorestupidless.ankka.auth.oidc.{Oidc, OidcConfig, TestIssuer}
  import com.thinkmorestupidless.ankka.http.{Caller, Callers, LocalCallers}

  /** The last token a step sent, and what reading the resource metadata answered. */
  protected var lastToken: Option[String]                            = None
  protected var metadata: Option[(Int, Map[String, String], String)] = None

  /** What every MCP client outside the cluster arrives as: through the gateway. */
  protected def fromOutside: (String, String) = LocalCallers.header(Caller.Gateway)

  protected def issuer: TestIssuer = customers.getOrElse(fail("no issuer was declared"))

  /** The broker's client at the issuer, as `ANKKA_AUTH_CUSTOMERS_CLIENT_ID` would give it. */
  protected val BrokerClientId = "orders-broker"

  protected def tokensAcl: Acl =
    Oidc.authenticate(OidcConfig(Vector(issuer.asIssuer().copy(clientId = Some(BrokerClientId)))))

  private def token(subject: String): String =
    val minted = issuer.token(subject, audience = Seq(issuer.defaultAudience))
    this.minted += minted
    lastToken = Some(minted)
    minted

  private def callOrder(tool: String, headers: (String, String)*): Answer =
    running
    rpc("tools/call", Json.obj("name" -> Json.str(tool), "arguments" -> Json.obj()), headers*)

  Given("an issuer {string} that signs tokens for the audience {string}") {
    (name: String, audience: String) =>
      // Served on loopback, so the sign-in broker can send a person to it and exchange the code.
      val served = TestIssuer.serving(name, audience)
      served.registeredClients.put(BrokerClientId, None)
      customers = Some(served)
  }

  Given("a service {string} that lists the issuer {string} with the audience {string}") {
    (service: String, name: String, audience: String) =>
      assertEquals((service, name, audience), ("orders", issuer.name, issuer.defaultAudience))
      ordering = true
  }

  Given("an MCP endpoint of {string} whose tools are {string} and {string}") {
    (_: String, a: String, b: String) =>
      assertEquals(Vector(a, b), OrderTools)
  }

  Given("a service {string} with an MCP endpoint that states no ACL") { (_: String) =>
    shop = Some(Vector(_ => NoAclMcp()))
  }

  Then("the reason names the MCP endpoint and says that an ACL must be stated") { () =>
    assert(reason.contains("MCP endpoint /mcp"), reason)
    assert(reason.contains("must state its ACL"), reason)
  }

  Given("the ACL of the MCP endpoint admits tokens") { () =>
    ordersAcl = tokensAcl
  }

  Given("the ACL of the MCP endpoint admits only the service {string}") { (service: String) =>
    ordersAcl = Acl.allowCallers(Callers.service("shop", service))
  }

  Given("the ACL of the tool {string} admits only the service {string}") {
    (tool: String, service: String) =>
      assertEquals(tool, "cancel_order")
      cancelAcl = Some(Acl.allowCallers(Callers.service("shop", service)))
  }

  Given("{string} and {string} are deployed in the project {string}") {
    (a: String, b: String, project: String) =>
      // Locally the certificate a deployed caller would present is the local caller header.
      assertEquals((a, b, project), ("orders", "support", "shop"))
  }

  When("an MCP client calls {string} with no credential") { (tool: String) =>
    callOrder(tool, fromOutside): Unit
  }

  When("an MCP client calls {string} with a token from {string} whose subject is {string}") {
    (tool: String, name: String, subject: String) =>
      assertEquals(name, issuer.name)
      callOrder(tool, fromOutside, "Authorization" -> s"Bearer ${token(subject)}"): Unit
  }

  When("{string} calls {string} with no credential") { (service: String, tool: String) =>
    callOrder(tool, LocalCallers.header(Caller.Service("shop", service))): Unit
  }

  When("an MCP client calls {string} carrying a token from {string}") {
    (tool: String, name: String) =>
      assertEquals(name, issuer.name)
      callOrder(tool, fromOutside, "Authorization" -> s"Bearer ${token("ada")}"): Unit
  }

  When("an MCP client calls {string} carrying a client key of {string}") {
    (tool: String, service: String) =>
      assertEquals(service, "orders")
      issueOk(IssueBody("ada", Some(Expiry.After(365.days))))
      ran.clear()
      callOrder(tool, fromOutside, carrying): Unit
  }

  When("an MCP client reads the resource metadata of {string}") { (service: String) =>
    assertEquals(service, "orders")
    readMetadata()
  }

  /** Reads the orders' resource metadata from outside, into `metadata`. */
  protected def readMetadata(): Unit =
    running
    val address = s"${url.stripSuffix("/mcp")}/.well-known/oauth-protected-resource/mcp"
    val request = HttpRequest
      .newBuilder(URI.create(address))
      .header(fromOutside._1, fromOutside._2)
      .GET()
      .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    read += response.body()
    val headers = response
      .headers()
      .map()
      .keySet()
      .toArray
      .collect { case n: String =>
        n.toLowerCase -> response.headers().firstValue(n).orElse("")
      }
      .toMap
    metadata = Some((response.statusCode(), headers, response.body()))

  Then("the request is challenged") { () =>
    val answer = last.get
    assertEquals(answer.status, 401, answer.toString)
    assert(answer.headers.get("www-authenticate").exists(_.contains("Bearer")), answer.headers)
  }

  Then("the challenge names the resource metadata of {string}") { (service: String) =>
    assertEquals(service, "orders")
    val challenge = last.get.headers.getOrElse("www-authenticate", "")
    // Through the gateway the client reached the endpoint over TLS, so the document is named https.
    val expected =
      s"https://127.0.0.1:${server.get.boundPort.get}/.well-known/oauth-protected-resource/mcp"
    assert(challenge.contains(s"""resource_metadata="$expected""""), challenge)
  }

  protected def authorizationServers: Vector[String] =
    val (status, _, body) = metadata.getOrElse(fail("the resource metadata was not read"))
    if status == 404 then Vector.empty
    else
      assertEquals(status, 200, body)
      val document = Json.parse(body).toOption.getOrElse(fail(body))
      assertEquals(
        document("resource").flatMap(_.asString),
        Some(s"https://127.0.0.1:${server.get.boundPort.get}/mcp")
      )
      assertEquals(document("bearer_methods_supported"), Some(Json.arr(Json.str("header"))))
      document("authorization_servers").flatMap(_.asArray).getOrElse(fail(body)).flatMap(_.asString)

  Then("the resource metadata names the issuer {string}") { (name: String) =>
    assertEquals(name, issuer.name)
    assertEquals(authorizationServers, Vector(issuer.issuer))
  }

  Then("the resource metadata names no issuer of the platform's") { () =>
    // The platform's issuer is its own Keycloak realm; only the service's issuers are named.
    val servers = authorizationServers
    assert(servers.forall(_ == issuer.issuer), servers)
    assert(!servers.exists(_.contains("/realms/ankka")), servers)
  }

  Then("the resource metadata names no issuer") { () =>
    assertEquals(metadata.map(_._1), Some(404), metadata.toString)
    assertEquals(authorizationServers, Vector.empty)
  }

  Then(
    "the handler of {string} is told that the call came from the service {string} in the project {string}"
  ) { (tool: String, service: String, project: String) =>
    assertEquals(last.get.result("isError"), Some(Json.bool(false)), last.toString)
    assertEquals(callersOf.filter(_._1 == tool).map(_._2), Vector(Caller.Service(project, service)))
    assertEquals(principalsTold, Vector.empty)
  }

  Then("a call to {string} with the same token is served") { (tool: String) =>
    val sent = lastToken.getOrElse(fail("no token was sent"))
    assertEquals(runs, Vector.empty)
    val answer = callOrder(tool, fromOutside, "Authorization" -> s"Bearer $sent")
    assertEquals(answer.result("isError"), Some(Json.bool(false)), answer.toString)
    assertEquals(runs.map(_._3), Vector(tool))
    assertEquals(lastTold(tool).subject, "ada")
  }

  // ── Steps: observing.feature ──────────────────────────────────────────────

  Given(
    "a service {string} with an MCP endpoint at {string} whose tools are {string} and {string}"
  ) { (service: String, path: String, a: String, b: String) =>
    assertEquals((service, path), ("cart", "/mcp"))
    assertEquals(Vector(a, b), Vector("get_cart", "add_item"))
    observing = true
  }

  Given("an HTTP endpoint of {string} whose route is {string}") { (service: String, route: String) =>
    // The report scenario reads it, beside the tools; it runs where a service is deployed.
    assertEquals((service, route), ("cart", "GET /carts/{cartId}"))
  }

  Given(
    "{string} has handled a tool call to {string} carrying the client key, whose handler called the event sourced entity {string}"
  ) { (_: String, tool: String, entity: String) =>
    assertEquals(entity, "cart")
    val answer = rpcTo(
      url,
      "tools/call",
      Json.obj("name" -> Json.str(tool), "arguments" -> Json.obj("cartId" -> Json.str("c1"))),
      carrying
    )
    assertEquals(answer.result("isError"), Some(Json.bool(false)), answer.toString)
  }

  When("the developer reads the trace of the request in the local console") { () =>
    // What the local console reads: the service's recorder, through the observability routes.
    val observability = Observability(running.service.system)
    val tool          = observability.recorder
      .snapshot()
      .find(s => observability.names.nameOf(s.handlerRef).contains("/mcp get_cart"))
    traceRead =
      tool.toVector.flatMap(t => observability.recorder.snapshot().filter(_.traceId == t.traceId))
  }

  Then(
    "the trace shows a span for the MCP endpoint {string} and the tool {string} whose subject is {string}"
  ) { (path: String, tool: String, subject: String) =>
    val names = Observability(running.service.system).names
    val span  = toolSpan(s"$path $tool")
    assertEquals(names.nameOf(span.componentRef), Some("mcp"))
    assertEquals(span.subject, Some(subject))
  }

  Then("the trace shows the call to {string} nested under it") { (entity: String) =>
    val names = Observability(running.service.system).names
    val tool  = toolSpan("/mcp get_cart")
    // A descendant of the tool's span, not merely in the same trace.
    def under(span: com.thinkmorestupidless.ankka.runtime.RecordedSpan): Boolean =
      span.parentSpanId == tool.spanId ||
        traceRead.find(_.spanId == span.parentSpanId).exists(under)
    val calls = traceRead.filter(s => names.nameOf(s.componentRef).contains(entity))
    assert(
      calls.nonEmpty,
      s"no span of $entity in ${traceRead.map(s => names.nameOf(s.componentRef))}"
    )
    assert(calls.exists(under), s"no span of $entity under the tool's")
  }

  Then("nothing in the trace reads as the client key") { () =>
    val names  = Observability(running.service.system).names
    val traces = traceRead
      .map(s => s"${names.nameOf(s.handlerRef)} ${s.subject} ${s.attributes}")
      .mkString("\n")
    assert(traceRead.nonEmpty, "the trace was not read")
    assert(!traces.contains(key.clientKey), "the key is in the trace")
    assert(
      !traces.contains(key.clientKey.drop("ankka_ck_".length + 16)),
      "the key's secret is in the trace"
    )
  }

  private def toolSpan(handler: String): com.thinkmorestupidless.ankka.runtime.RecordedSpan =
    val names = Observability(running.service.system).names
    traceRead
      .find(s => names.nameOf(s.handlerRef).contains(handler))
      .getOrElse(fail(s"no span for $handler in ${traceRead.map(s => names.nameOf(s.handlerRef))}"))
