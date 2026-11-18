package com.thinkmorestupidless.ankka.testkit

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.networknt.schema.{InputFormat, JsonSchemaFactory, SpecVersion}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.http.openapi.Description
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

  final case class Profile(name: String)
  final case class EditProfile(name: String)
  final case class AddEmail(address: String)
  final case class OtherEmail(address: String, primary: Boolean)
  final case class Refusal(code: String, message: String)

  given JsonValueCodec[Profile]  = Codecs.make
  given JsonValueCodec[AddEmail] = Codecs.make

  /** What a players route of `describing.feature` is: how it takes its body and answers. */
  enum Kind:
    case TextBody, AnswersBytes, AnswersProfile, TakesEmail, AnswersStream

  /**
   * The players endpoint of `describing.feature`: each route of the kind its scenario gave, inside
   * the description its scenario gave it, and the endpoint's own description.
   */
  final class Players(
      routes: Vector[(String, Kind, Description => Description)],
      stated: Description
  ) extends HttpEndpoint("/players"):
    val acl: Acl = Acl.AllowAll

    override protected def description: Description = stated

    routes.foreach { (route, kind, describe) =>
      val template = route.split(' ')(1).stripPrefix("/players")
      described(describe) {
        kind match
          case Kind.TextBody =>
            patchBody(template)((_: String, text: String) => text.length)
          case Kind.AnswersBytes =>
            get(template)((_: String) => Bytes("application/json", """{"name":"p"}""".getBytes))
          case Kind.AnswersProfile => get(template)((_: String) => Profile("p"))
          case Kind.AnswersStream  =>
            get(template)((_: String) =>
              Streamed("application/octet-stream", None, java.io.InputStream.nullInputStream())
            )
          case Kind.TakesEmail =>
            postBody(template)((_: String, email: AddEmail) => email.address)
      }
    }

  final class Sessions extends HttpEndpoint("/sessions"):
    val acl: Acl = Acl.AllowAll
    get("/{sessionId}")((id: String) => id)

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
    val held      = "HeldToDocumentSuite (T025)"
    val serving   = "OpenApiServedSuite (T027, T028)"
    val languages = "the Java and Kotlin document suites (T029 to T034)"
    Map(
      "a kept document that no longer matches the code is found, and nothing is written" -> "OpenApiCommandSuite (T023)",
      "an answer the description does not name fails the test"           -> held,
      "an answer body its shape refuses fails the test"                  -> held,
      "a body the handler accepts that its shape refuses fails the test" -> held,
      "a query parameter the route did not declare fails the test when the handler reads it" -> held,
      "a service that answers as its document says passes"                  -> held,
      "a test that does not hold the service to its document is unaffected" -> held,
      "an incomplete route is held only to what it states"                  -> held,
      "describing a route changes nothing of how it answers" -> "RouteShapeSuite (T018)",
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
  private var players: Option[Vector[(String, Kind, Description => Description)]] = None
  private var endpointDescription: Description                                    = Description.none
  private var selection: Map[String, String]                                      = Map.empty

  // ── what writing it gave ──────────────────────────────────────────────────

  private var status                     = -1
  private var problems: Vector[String]   = Vector.empty
  private var text: Option[String]       = None
  private var earlier: Option[String]    = None
  private var threadsBefore: Set[String] = Set.empty

  override def beforeEach(context: BeforeEach): Unit =
    service = "cart"; routes = Set.empty; acl = Acl.AllowAll; sockets = false
    others = Vector.empty; beside = Vector.empty; focus = None
    players = None; endpointDescription = Description.none; selection = Map.empty
    status = -1; problems = Vector.empty; text = None; earlier = None

  private def factories: Vector[EndpointClients => HttpEndpoint] =
    val (r, a, s)                             = (routes, acl, sockets)
    val main: EndpointClients => HttpEndpoint = players match
      case Some(declared) =>
        val stated = endpointDescription
        _ => Players(declared, stated)
      case None => _ => Carts(r, a, s)
    main +: others

  /** A players route of `kind`, declared once, its description added to on every mention. */
  private def player(route: String, kind: Kind, describe: Description => Description = identity) =
    val declared = players.getOrElse(Vector.empty)
    players = Some(declared.indexWhere(_._1 == route) match
      case -1 => declared :+ (route, kind, describe)
      case i  =>
        val (r, k, d) = declared(i)
        declared.updated(i, (r, k, d.andThen(describe))))
    focus = Some(route)

  /** A description for the route in focus, which must already be declared. */
  private def describing(describe: Description => Description): Unit =
    val kind = players.flatMap(_.find(_._1 == only)).map(_._2).getOrElse(Kind.AnswersProfile)
    player(only, kind, describe)

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
        Map("ankka.document" -> "openapi", "ankka.document.out" -> out.toString) ++ selection,
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

  /** The schema of a body's or an answer's one content, whatever its media type. */
  private def schemaOf(content: JsonNode): JsonNode =
    content.properties.asScala.toVector match
      case Vector(one) => one.getValue.path("schema")
      case other       => fail(s"not one content: ${other.map(_.getKey)}")

  private def bodySchema(route: String): JsonNode =
    schemaOf(operation(route).path("requestBody").path("content"))

  private def answerSchema(route: String): JsonNode =
    schemaOf(operation(route).path("responses").path("200").path("content"))

  /** The answer of `route` whose description is `reason`. */
  private def refusal(route: String, reason: String): JsonNode =
    operation(route)
      .path("responses")
      .properties
      .asScala
      .find(_.getValue.path("description").asText == reason)
      .map(_.getValue)
      .getOrElse(fail(s"no refusal \"$reason\" on $route"))

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
      service = name
      prefix match
        case "/carts"   => ()
        case "/players" => players = Some(Vector.empty)
        case other      => fail(s"no endpoint for $other")
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

  // ── describing.feature ────────────────────────────────────────────────────

  Given("the route {string} takes its body as text") { (route: String) =>
    player(route, Kind.TextBody)
  }

  Given("its route description gives the body the shape of {string}") { (name: String) =>
    assertEquals(name, "EditProfile"); describing(_.body[EditProfile])
  }

  Given("the route {string} answers bytes")((route: String) => player(route, Kind.AnswersBytes))

  Given("its route description gives its answer the shape of {string}") { (name: String) =>
    assertEquals(name, "Profile"); describing(_.answers[Profile](200))
  }

  Given("the route {string} answers a {string}") { (route: String, name: String) =>
    assertEquals(name, "Profile"); player(route, Kind.AnswersProfile)
  }

  Given("its route description names a refusal {string}") { (reason: String) =>
    describing(_.refuses(404, reason))
  }

  Then("the route has the answer of shape {string} and the refusal {string}") {
    (name: String, reason: String) =>
      assertEquals(json(answerSchema(only)), s"""{"$$ref":"#/components/schemas/$name"}""")
      refusal(only, reason): Unit
  }

  Given("the route description of {string} names a refusal {string} and gives it no shape") {
    (route: String, reason: String) => player(route, Kind.AnswersProfile, _.refuses(404, reason))
  }

  Then("the refusal {string} has the shape of the platform's own refusals") { (reason: String) =>
    assertEquals(
      json(schemaOf(refusal(only, reason).path("content"))),
      """{"$ref":"#/components/schemas/AnkkaProblem"}"""
    )
  }

  Given("the endpoint states the shape {string} for the refusals of all its routes") {
    (name: String) =>
      assertEquals(name, "Refusal")
      endpointDescription = endpointDescription.andThen(Description.none.refusalShape[Refusal])
  }

  Then("the refusal {string} has the shape of {string}") { (reason: String, name: String) =>
    assertEquals(
      json(schemaOf(refusal(only, reason).path("content"))),
      s"""{"$$ref":"#/components/schemas/$name"}"""
    )
  }

  Given(
    "the route description of {string} names the query parameters {string}, an optional whole number, and {string}, a required text"
  ) { (route: String, optional: String, required: String) =>
    player(route, Kind.AnswersProfile, _.query[Int](optional).queryRequired[String](required))
  }

  private def queryParameter(name: String): JsonNode =
    operation(only)
      .path("parameters")
      .elements
      .asScala
      .find(p => p.path("in").asText == "query" && p.path("name").asText == name)
      .getOrElse(fail(s"no query parameter $name"))

  Then("the route has the query parameter {string}, an optional whole number") { (name: String) =>
    val p = queryParameter(name)
    assertEquals(
      (p.path("required").asBoolean, json(p.path("schema"))),
      (false, """{"type":"integer"}""")
    )
  }

  Then("the route has the query parameter {string}, a required text") { (name: String) =>
    val p = queryParameter(name)
    assertEquals(
      (p.path("required").asBoolean, json(p.path("schema"))),
      (true, """{"type":"string"}""")
    )
  }

  Given("the route description of {string} gives its answer the shape of the type {string}") {
    (route: String, name: String) =>
      assertEquals(name, "Profile")
      player(route, Kind.AnswersBytes, _.answers[Profile](200))
  }

  Then("both routes refer to the one shape {string}") { (name: String) =>
    val declared = players.getOrElse(Vector.empty).map(_._1)
    assertEquals(declared.size, 2)
    declared.foreach(r =>
      assertEquals(json(answerSchema(r)), s"""{"$$ref":"#/components/schemas/$name"}""", r)
    )
    val names = document.path("components").path("schemas").properties.asScala.map(_.getKey)
    assertEquals(names.count(_ == name), 1, names.mkString(", "))
  }

  private val GivenSchema = """{"type":"object","properties":{"total":{"type":"integer"}}}"""

  Given("the route description of {string} gives its answer a shape written as JSON Schema") {
    (route: String) => player(route, Kind.AnswersBytes, _.answers(200, GivenSchema))
  }

  Then("the route's answer has that shape as it was given") { () =>
    assertEquals(answerSchema(only), mapper.readTree(GivenSchema))
  }

  Given("the route {string} whose body is an {string}") { (route: String, name: String) =>
    assertEquals(name, "AddEmail"); player(route, Kind.TakesEmail)
  }

  Given("its route description gives the body a different shape") { () =>
    describing(_.body[OtherEmail])
  }

  Given("its route description names a query parameter {string}, a path parameter's name") {
    (name: String) => describing(_.query[String](name))
  }

  Given("its route description names the query parameter {string} twice") { (name: String) =>
    describing(_.query[Int](name).query[Int](name))
  }

  private def failedNaming(words: String): Unit =
    assertEquals(status, 1)
    assert(
      problems.exists(p => p.startsWith(s"$only: ") && p.contains(words)),
      problems.mkString("; ")
    )

  Then("the writing fails, naming the route and the body's two shapes") { () =>
    failedNaming("the body's two shapes")
    assert(problems.exists(p => p.contains("AddEmail") && p.contains("OtherEmail")), problems)
  }

  Then("the writing fails, naming the route and the parameter {string}") { (name: String) =>
    failedNaming(s"the parameter \"$name\"")
  }

  Then("the writing fails, naming the route and the query parameter {string}") { (name: String) =>
    failedNaming(s"the query parameter \"$name\"")
  }

  Given("the endpoint states the route tag {string} and the refusal {string} for all its routes") {
    (tag: String, reason: String) =>
      endpointDescription =
        endpointDescription.andThen(Description.none.tag(tag).refuses(403, reason))
  }

  Given("its route {string} states the route tag {string}") { (route: String, tag: String) =>
    player(route, Kind.AnswersProfile, _.tag(tag))
  }

  Then("the route has the route tags {string} and {string} and the refusal {string}") {
    (a: String, b: String, reason: String) =>
      assertEquals(json(operation(only).path("tags")), s"""["$a","$b"]""")
      refusal(only, reason): Unit
  }

  Given("the route {string} carries the route tag {string}") { (route: String, tag: String) =>
    val kind = if route.startsWith("POST") then Kind.TakesEmail else Kind.AnswersProfile
    player(route, kind, _.tag(tag))
  }

  When("the developer writes the service's OpenAPI document for the route tag {string}") {
    (tag: String) =>
      selection = Map("ankka.document.tags" -> tag); write()
  }

  When("the developer writes the service's OpenAPI document for the paths under {string}") {
    (prefix: String) =>
      selection = Map("ankka.document.prefixes" -> prefix); write()
  }

  Then("the document holds the route {string}")((route: String) => operation(route): Unit)

  Then("the document holds neither the route {string} nor a shape only it uses") {
    (route: String) =>
      val (method, path) = route.split(' ') match
        case Array(m, p) => (m.toLowerCase, p)
        case _           => fail(route)
      assert(!document.path("paths").path(path).has(method), s"$route is in the document")
      assert(!document.path("components").path("schemas").has("AddEmail"), "AddEmail is kept")
  }

  Given("the service also has an HTTP endpoint for the path {string}") { (prefix: String) =>
    assertEquals(prefix, "/sessions")
    player("GET /players/{playerId}", Kind.AnswersProfile)
    others :+= (_ => Sessions())
  }

  Then("the document holds no route under {string}") { (prefix: String) =>
    val held = paths
    assert(held.nonEmpty)
    assert(!held.exists(_.startsWith(prefix + "/")), held.mkString(", "))
  }

  Given("no route carries the route tag {string}") { (_: String) =>
    player("GET /players/{playerId}", Kind.AnswersProfile, _.tag("player"))
  }

  Then("the writing fails, saying that no route matches") { () =>
    assertEquals(status, 1)
    assert(problems.exists(_.startsWith("no route carries the tag")), problems.mkString("; "))
  }

  // ── completeness.feature ──────────────────────────────────────────────────

  Given("the route {string} takes its body as text and has no route description") {
    (route: String) => player(route, Kind.TextBody)
  }

  Then("the route is marked an incomplete route that lacks its body's shape") { () =>
    assertEquals(json(operation(only).path("x-ankka-incomplete")), """["request body"]""")
  }

  Given("the route {string} answers bytes and has no route description") { (route: String) =>
    player(route, Kind.AnswersBytes)
  }

  When("the developer writes the service's OpenAPI document, requiring completeness") { () =>
    selection = Map("ankka.document.strict" -> "true"); write()
  }

  Then(
    "the writing fails, naming {string} and {string} and what each lacks"
  ) { (a: String, b: String) =>
    assertEquals(status, 1)
    assertEquals(
      problems.toSet,
      Set(s"$a: incomplete, lacks request body", s"$b: incomplete, lacks answer 200")
    )
  }

  Given("the route's description gives the body the shape of {string} and names its answer") {
    (name: String) =>
      assertEquals(name, "EditProfile")
      describing(_.body[EditProfile].answers[Int](200))
  }

  Given("the route {string} answers a stream of bytes") { (route: String) =>
    player(route, Kind.AnswersStream)
  }

  Then("the route {string} is not an incomplete route") { (route: String) =>
    assert(!operation(route).has("x-ankka-incomplete"), json(operation(route)))
  }
