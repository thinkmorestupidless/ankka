package com.thinkmorestupidless.ankka.testkit

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.networknt.schema.{InputFormat, JsonSchemaFactory, SpecVersion}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.{
  Ankka,
  AnkkaService,
  DocumentMode,
  RuntimeExtension,
  ServedRoute
}
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions
import org.apache.pekko.stream.scaladsl.Source

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

object OpenApiFeatureFixtures:
  final case class AddItem(productId: String, quantity: Int)
  final case class LineItem(productId: String, quantity: Int)
  final case class Cart(cartId: String, items: List[LineItem])

  given JsonValueCodec[AddItem] = Codecs.make
  given JsonValueCodec[Cart]    = Codecs.make

  private val tokens: Acl = Acl.Authenticate(new TokenAuthenticator:
    def authorizationServers: Vector[String]         = Vector("https://auth.example/realms/shop")
    def apply(context: RequestContext): AuthDecision = AuthDecision.Unauthenticated("Bearer"))

  /** The ACLs a scenario can state, by its words. */
  def aclOf(words: String): Acl = words match
    case "allows all"                         => Acl.AllowAll
    case s"admits tokens from the issuer $_"  => tokens
    case "admits only the workloads it names" => Acl.allowCallers(CallerMatcher.AnyService)
    case other                                => throw IllegalArgumentException(other)

  /**
   * The cart endpoint a scenario declares: each route is declared only when the scenario named it,
   * so one class serves every scenario and each document holds only what its scenario said.
   */
  final class Carts(routes: Set[String], stated: Acl, sockets: Boolean)
      extends HttpEndpoint("/carts"):
    val acl: Acl = stated

    private def has(route: String) = routes(route)

    if has("GET /carts/{cartId}") then get("/{cartId}")((id: String) => Cart(id, Nil))
    if has("POST /carts/{cartId}/items") then
      postBody("/{cartId}/items")((id: String, item: AddItem) =>
        Cart(id, List(LineItem(item.productId, item.quantity)))
      )
    if has("GET /carts/{cartId}/items/{line}") then
      get("/{cartId}/items/{line}")((_: String, line: Int) => line)
    if has("DELETE /carts/{cartId}") then delete("/{cartId}")((_: String) => Done.done)
    if has("GET /carts/{cartId}/events") then
      sse("/{cartId}/events")((id: String) => Source.single(id))
    if has("POST /carts/{cartId}/checkout") then
      post("/{cartId}/checkout")((id: String) => Cart(id, Nil))
    if sockets then
      socket("/{cartId}/watch")((_: String, socket: Socket) => socket.send("watching"))

  /** An endpoint that serves routes of its own and says so, as an MCP endpoint does. */
  final class SaysItself extends HttpEndpoint("/mcp") with ServesRoutes:
    val acl: Acl = Acl.AllowAll
    post("/")(() => "answered")
    def served: Vector[ServedRoute] =
      Vector(ServedRoute("TOOL", "say_hello", streaming = false, ServedRoute.endpointId("/mcp")))

  /** An endpoint that reads from the running service while it is built. */
  final class Reports(clients: EndpointClients) extends HttpEndpoint("/reports"):
    val acl: Acl        = Acl.AllowAll
    private val opening = scala.concurrent.Await.result(
      clients.componentClient.transportRef.ask(
        ComponentId("cart"),
        EntityId("c1"),
        MethodName("get"),
        Array.emptyByteArray,
        Metadata.empty
      ),
      scala.concurrent.duration.Duration(1, "second")
    )
    get("/")(() => opening.length)

  /** Another extension beside the HTTP server, as a gRPC server is: it writes no document. */
  final class Beside extends RuntimeExtension:
    def name                                 = "grpc"
    def start(service: AnkkaService): Unit   = ()
    override def grpcAddress: Option[String] = Some("127.0.0.1:9090")

/**
 * `features/openapi-documents` (feature 067). `deriving.feature` runs here, each scenario over an
 * endpoint built for it, its document written as `ankka openapi` writes it: through
 * `DocumentMode.run`, which starts nothing. The other files are bound as their tasks land.
 */
class OpenApiFeatures extends GherkinSuite("../../features/openapi-documents") with LogCapturing:
  import OpenApiFeatureFixtures.*

  override val munitTimeout = 3.minutes

  override protected lazy val ranElsewhere: Map[String, String] =
    val describing = "OpenApiSuite (T019, T020: describing.feature, bound in T020)"
    val completing = "OpenApiSuite (T022, T023)"
    val held       = "HeldToDocumentSuite (T025)"
    val serving    = "OpenApiServedSuite (T027, T028)"
    val languages  = "the Java and Kotlin document suites (T029 to T034)"
    Map(
      "an incomplete route is in the document, marked with what it lacks"         -> completing,
      "requiring completeness fails the writing and names every incomplete route" -> completing,
      "requiring completeness of a service whose routes are all complete writes the document" -> completing,
      "a body or an answer that is a stream of bytes does not make a route incomplete" -> completing,
      "a kept document that no longer matches the code is found, and nothing is written" -> completing,
      "an answer the description does not name fails the test"           -> held,
      "an answer body its shape refuses fails the test"                  -> held,
      "a body the handler accepts that its shape refuses fails the test" -> held,
      "a query parameter the route did not declare fails the test when the handler reads it" -> held,
      "a service that answers as its document says passes"                       -> held,
      "a test that does not hold the service to its document is unaffected"      -> held,
      "an incomplete route is held only to what it states"                       -> held,
      "a text body described with a shape has that shape in the document"        -> describing,
      "an answer of bytes described with a shape has that shape in the document" -> describing,
      "a described refusal stands beside the answer the route's type gives"      -> describing,
      "a described refusal given no shape has the shape of the platform's own refusals" -> describing,
      "an endpoint that writes refusals of its own gives their shape once for all its routes" -> describing,
      "a described query parameter is in the document with its type and whether it is required" -> describing,
      "a shape described by a type is the named shape a typed route would have" -> describing,
      "a shape described as JSON Schema is in the document as it was given"     -> describing,
      "a description that contradicts the route fails the writing and names the route" -> describing,
      "describing a route changes nothing of how it answers" -> "RouteShapeSuite (T018)",
      "an endpoint's route tags and refusals are carried by each of its routes"  -> describing,
      "a document written for one route tag holds only the routes that carry it" -> describing,
      "a document written for one path prefix holds only the routes under it"    -> describing,
      "a selection that matches no route fails the writing"                      -> describing,
      "a caller the service names is given the document, the same as the one written from its code" -> serving,
      "a caller the service does not name is refused as that ACL refuses" -> serving,
      "a service that states nothing about its document answers as for a path it does not have" -> serving,
      "the served document is whole, whatever the reader may call" -> serving,
      "a local service's document is given to its developer whether or not the service serves it" -> serving,
      "the same service has the same document in every language that has one" -> languages,
      "a route description says the same in every language that has one"      -> languages,
      "a service in a language with no document yet is told so" -> "OpenApiCommandSuite (T015)"
    )

  private val mapper  = ObjectMapper()
  private val schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
  private val http    = HttpClient.newHttpClient()

  // ── what a scenario has declared ──────────────────────────────────────────

  private var service                                         = "cart"
  private var routes                                          = Set.empty[String]
  private var acl: Acl                                        = Acl.AllowAll
  private var sockets                                         = false
  private var others: Vector[EndpointClients => HttpEndpoint] = Vector.empty
  private var beside: Vector[RuntimeExtension]                = Vector.empty
  private var focus: Option[String]                           = None

  // ── what writing it gave ──────────────────────────────────────────────────

  private var status                     = -1
  private var problems: Vector[String]   = Vector.empty
  private var text: Option[String]       = None
  private var earlier: Option[String]    = None
  private var threadsBefore: Set[String] = Set.empty

  override def beforeEach(context: BeforeEach): Unit =
    service = "cart"; routes = Set.empty; acl = Acl.AllowAll; sockets = false
    others = Vector.empty; beside = Vector.empty; focus = None
    status = -1; problems = Vector.empty; text = None; earlier = None

  private def factories: Vector[EndpointClients => HttpEndpoint] =
    val (r, a, s) = (routes, acl, sockets)
    (((_: EndpointClients) => Carts(r, a, s)): EndpointClients => HttpEndpoint) +: others

  /** The document written as `ankka openapi` writes it, into a file of its own. */
  private def write(): Unit =
    val dir = Files.createTempDirectory("openapi-features")
    val out = dir.resolve("openapi.json")
    try
      val builder = beside.foldLeft(Ankka.service.withExtension(HttpServer.of(factories*)))(
        _.withExtension(_)
      )
      val (s, p) = DocumentMode.run(
        builder,
        Map("ankka.document" -> "openapi", "ankka.document.out" -> out.toString),
        service
      )
      status = s
      problems = p
      text = Option.when(Files.exists(out))(Files.readString(out, UTF_8))
    finally
      Files.deleteIfExists(out)
      Files.deleteIfExists(dir): Unit

  private def document: JsonNode =
    mapper.readTree(text.getOrElse(fail(s"no document was written: ${problems.mkString("; ")}")))

  private def operation(route: String): JsonNode =
    val (method, path) = route.split(' ') match
      case Array(m, p) => (m.toLowerCase, p)
      case _           => fail(s"not a route: $route")
    Option(document.path("paths").get(path)).flatMap(p => Option(p.get(method))) match
      case Some(op) => op
      case None     => fail(s"the document has no $route")

  private def only: String = focus.getOrElse(fail("the scenario named no route"))

  private def json(node: JsonNode): String = mapper.writeValueAsString(node)

  private def bodySchema(route: String): JsonNode =
    operation(route).path("requestBody").path("content").path("application/json").path("schema")

  private def answerSchema(route: String): JsonNode =
    operation(route)
      .path("responses")
      .path("200")
      .path("content")
      .path("application/json")
      .path("schema")

  private def violations(schema: JsonNode, value: String): Vector[String] =
    val root = schema.deepCopy[JsonNode]() match
      case o: ObjectNode => o
      case other         => fail(s"a schema that is not an object: $other")
    root.set[JsonNode]("components", document.get("components"))
    schemas.getSchema(root).validate(value, InputFormat.JSON).asScala.toVector.map(_.getMessage)

  /** The scenario's endpoint, running; `use` is given its address. */
  private def running[A](use: String => A): A =
    val server = HttpServer.at("127.0.0.1", 0)(factories*)
    val kit    = AnkkaTestKit.start(Seq.empty, Seq(server))
    try use(s"http://127.0.0.1:${server.boundPort.getOrElse(fail("did not bind"))}")
    finally kit.stop()

  private def send(base: String, method: String, path: String, body: Option[String]) =
    val request = JdkRequest.newBuilder(URI.create(base + path))
    body.foreach(_ => request.header("Content-Type", "application/json"))
    request.method(
      method,
      body.fold(JdkRequest.BodyPublishers.noBody())(JdkRequest.BodyPublishers.ofString)
    )
    val response = http.send(request.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def ankkaThreads: Set[String] =
    Thread.getAllStackTraces.keySet.asScala.map(_.getName).filter(_.startsWith("ankka-")).toSet

  // ── the service and its routes ────────────────────────────────────────────

  Given("a service {string} with an HTTP endpoint for the path {string}") {
    (name: String, prefix: String) =>
      assertEquals(prefix, "/carts", "the scenarios' endpoint is the cart's")
      service = name
  }

  Given("the endpoint has the routes {string} and {string}") { (a: String, b: String) =>
    routes ++= Set(a, b)
  }

  Given("the endpoint has the route {string} whose {string} is a whole number") {
    (route: String, _: String) =>
      routes += route; focus = Some(route)
  }

  Given("the endpoint has the route {string} whose body is an {string}") {
    (route: String, _: String) =>
      routes += route; focus = Some(route)
  }

  Given("the endpoint has the route {string} answering a {string}") { (route: String, _: String) =>
    routes += route; focus = Some(route)
  }

  Given("the endpoint has the route {string} answering nothing") { (route: String) =>
    routes += route; focus = Some(route)
  }

  Given("the endpoint has the route {string} answered as a stream of events") { (route: String) =>
    routes += route; focus = Some(route)
  }

  Given("the endpoint has the routes {string} and {string} each answering a {string}") {
    (a: String, b: String, _: String) =>
      routes ++= Set(a, b)
  }

  Given("the route {string} states an ACL that allows all") { (route: String) =>
    routes += route; focus = Some(route); acl = aclOf("allows all")
  }

  Given("the route {string} states an ACL that admits tokens from the issuer {string}") {
    (route: String, issuer: String) =>
      routes += route; focus = Some(route)
      acl = aclOf(s"admits tokens from the issuer $issuer")
  }

  Given("the route {string} states an ACL that admits only the workloads it names") {
    (route: String) =>
      routes += route; focus = Some(route); acl = aclOf("admits only the workloads it names")
  }

  Given("the service also has a socket route") { () =>
    routes += "GET /carts/{cartId}"; sockets = true
  }

  Given("the service also has an MCP endpoint") { () =>
    routes += "GET /carts/{cartId}"; others :+= (_ => SaysItself())
  }

  Given("the service also has a gRPC endpoint") { () =>
    routes += "GET /carts/{cartId}"; beside :+= Beside()
  }

  Given("the service also has the platform's own routes") { () =>
    routes += "GET /carts/{cartId}"
  }

  Given(
    "the service has an HTTP endpoint for the path {string} that cannot be built without the running service"
  ) { (prefix: String) =>
    assertEquals(prefix, "/reports")
    routes += "GET /carts/{cartId}"; others :+= (clients => Reports(clients))
  }

  Given("the developer's machine has no database, no broker and no network") { () =>
    routes += "GET /carts/{cartId}"
    threadsBefore = ankkaThreads
  }

  Given("the developer has written the service's OpenAPI document") { () =>
    routes ++= Set("GET /carts/{cartId}", "POST /carts/{cartId}/items", "DELETE /carts/{cartId}")
    write()
    earlier = text
  }

  // ── writing ───────────────────────────────────────────────────────────────

  When("the developer writes the service's OpenAPI document")(() => write())

  When("the developer writes it again from the same code")(() => write())

  // ── the document ──────────────────────────────────────────────────────────

  Then("the document is a valid OpenAPI document") { () =>
    val options = ParseOptions()
    options.setResolve(true)
    val result   = OpenAPIParser().readContents(text.getOrElse(fail("no document")), null, options)
    val messages = Option(result.getMessages).map(_.asScala.toVector).getOrElse(Vector.empty)
    assertEquals(messages, Vector.empty[String])
    assertEquals(result.getOpenAPI.getOpenapi, "3.1.0")
  }

  Then("the document holds the routes {string} and {string}") { (a: String, b: String) =>
    operation(a); operation(b): Unit
  }

  Then("the route has the path parameters {string}, a text, and {string}, a whole number") {
    (text: String, number: String) =>
      val parameters = operation(only)
        .path("parameters")
        .elements
        .asScala
        .toVector
        .map(p => p.path("name").asText -> p.path("schema").path("type").asText)
      assertEquals(parameters, Vector(text -> "string", number -> "integer"))
  }

  Then("the route's body has the shape of {string}") { (name: String) =>
    assertEquals(json(bodySchema(only)), s"""{"$$ref":"#/components/schemas/$name"}""")
    assert(document.path("components").path("schemas").has(name), s"no shape $name")
  }

  Then("a body the running service accepts for that route fits the shape") { () =>
    val body = """{"productId":"p1","quantity":2}"""
    running { base =>
      val (code, _) = send(base, "POST", "/carts/c1/items", Some(body))
      assertEquals(code, 200, "the running service accepts the body")
    }
    assertEquals(violations(bodySchema(only), body), Vector.empty)
    assert(
      violations(bodySchema(only), """{"productId":"p1"}""").nonEmpty,
      "a body the service refuses fits too, so the shape checks nothing"
    )
  }

  Then("the route's answer has the shape of {string}") { (name: String) =>
    assertEquals(json(answerSchema(only)), s"""{"$$ref":"#/components/schemas/$name"}""")
  }

  Then("the answer the running service gives for that route fits the shape") { () =>
    val (code, answer) = running(base => send(base, "GET", "/carts/c1", None))
    assertEquals(code, 200)
    assertEquals(violations(answerSchema(only), answer), Vector.empty)
    assert(violations(answerSchema(only), """{"cartId":1}""").nonEmpty)
  }

  Then("the route's answer has no body") { () =>
    val answers = operation(only).path("responses")
    assert(answers.has("204"), json(answers))
    assert(!answers.path("204").has("content"), json(answers))
  }

  Then("the route is not an incomplete route") { () =>
    assert(!operation(only).has("x-ankka-incomplete"), json(operation(only)))
  }

  Then("the route's answer is a stream of events") { () =>
    val content = operation(only).path("responses").path("200").path("content")
    assertEquals(content.properties.asScala.map(_.getKey).toVector, Vector("text/event-stream"))
  }

  Then("the document holds the shape {string} once") { (name: String) =>
    val names = document.path("components").path("schemas").properties.asScala.map(_.getKey)
    assertEquals(names.count(n => n == name || n.endsWith(s".$name")), 1, names.mkString(", "))
  }

  Then("both routes refer to it") { () =>
    for route <- Seq("GET /carts/{cartId}", "POST /carts/{cartId}/checkout") do
      assertEquals(json(answerSchema(route)), """{"$ref":"#/components/schemas/Cart"}""", route)
  }

  Then("the two documents are the same, character for character") { () =>
    assert(earlier.isDefined && earlier == text, "the two documents differ")
  }

  private def paths: Vector[String] =
    document.path("paths").properties.asScala.map(_.getKey).toVector

  Then("the document holds nothing of a socket route") { () =>
    assert(!paths.exists(_.endsWith("/watch")), paths.mkString(", "))
    assert(!text.exists(_.contains("watch")), "the socket's path is in the document")
  }

  Then("the document holds nothing of an MCP endpoint") { () =>
    assert(!paths.exists(_.startsWith("/mcp")), paths.mkString(", "))
    assert(!text.exists(_.contains("say_hello")))
  }

  Then("the document holds nothing of a gRPC endpoint") { () =>
    assertEquals(paths, Vector("/carts/{cartId}"))
    assert(!text.exists(_.contains("9090")))
  }

  Then("the document holds nothing of the platform's own routes") { () =>
    assert(!paths.exists(_.startsWith("/_ankka")), paths.mkString(", "))
    assert(!text.exists(_.contains("_ankka")))
  }

  Then("the document is written") { () =>
    assertEquals((status, problems), (0, Vector.empty[String]))
    assert(text.isDefined)
    assertEquals(ankkaThreads -- threadsBefore, Set.empty[String], "something was started")
  }

  Then("the route is marked as needing no credential") { () =>
    assertEquals(json(operation(only).path("security")), "[]")
    assert(!operation(only).has("x-ankka-admission"))
  }

  Then("the route is marked as needing a token, with no issuer named") { () =>
    assertEquals(json(operation(only).path("security")), """[{"bearer":[]}]""")
    assert(!text.exists(_.contains("auth.example")), "the issuer is named")
  }

  Then("the route is marked as not open, with no credential named") { () =>
    val op = operation(only)
    assertEquals(op.path("x-ankka-admission").asText, "not-open")
    assert(!op.has("security"), json(op))
    assert(!document.path("components").has("securitySchemes"))
  }

  Then("the writing fails, naming the endpoint that could not be built") { () =>
    assertEquals(status, 1)
    assert(
      problems.exists(_.startsWith("the endpoint Reports could not be built")),
      problems.mkString("; ")
    )
  }

  Then("no document is written") { () =>
    assertEquals(text, None)
  }
