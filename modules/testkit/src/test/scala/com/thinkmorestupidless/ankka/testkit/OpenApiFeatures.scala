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

  final case class Messages(items: List[String], total: Int)

  /** How the messages route of `held-to-document.feature` answers, set by its scenario. */
  val messagesAnswer = java.util.concurrent.atomic.AtomicReference("as described")

  given JsonValueCodec[Profile]  = Codecs.make
  given JsonValueCodec[AddEmail] = Codecs.make

  /** What a players route of `describing.feature` is: how it takes its body and answers. */
  enum Kind:
    case TextBody, AnswersBytes, AnswersProfile, TakesEmail, AnswersStream, AnswersMessages

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
          case Kind.AnswersProfile  => get(template)((_: String) => Profile("p"))
          case Kind.AnswersMessages =>
            get(template) { (_: String) =>
              val whole = """{"items":["hello"],"total":1}"""
              messagesAnswer.get match
                case "conflict" => throw HttpProblem.conflict("conflict")
                case "lacking"  => Bytes("application/json", """{"items":[]}""".getBytes)
                case read       =>
                  if read.startsWith("read ") then query.raw(read.stripPrefix("read ")): Unit
                  Bytes("application/json", whole.getBytes)
            }
          case Kind.AnswersStream =>
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
    // Each outline's rows split by language: Scala and Java in the Java cart's tests, Kotlin in the
    // Kotlin cart's, each skipping the other's rows.
    val languages = "OpenApiJavaFeatures and the Kotlin cart's OpenApiFeaturesTest"
    Map(
      "a kept document that no longer matches the code is found, and nothing is written" -> "OpenApiCommandSuite (T023)",
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
  private var holding: Boolean                                                    = false
  private var seen: Vector[String]                                                = Vector.empty
  private var selection: Map[String, String]                                      = Map.empty

  // ── what writing it gave ──────────────────────────────────────────────────

  private var status                     = -1
  private var problems: Vector[String]   = Vector.empty
  private var text: Option[String]       = None
  private var earlier: Option[String]    = None
  private var threadsBefore: Set[String] = Set.empty

  override def beforeEach(context: BeforeEach): Unit =
    service = "cart"; routes = Set.empty; acl = Acl.AllowAll; sockets = false
    documentAcl = None; asked = None; unknownPath = None; answeredBefore = Vector.empty
    others = Vector.empty; beside = Vector.empty; focus = None; localDocument = None
    players = None; endpointDescription = Description.none; selection = Map.empty
    holding = false; seen = Vector.empty; messagesAnswer.set("as described")
    HeldToDocument.clear()
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
    val bare   = HttpServer.at("127.0.0.1", 0)(factories*)
    val plain  = documentAcl.fold(bare)(bare.withOpenApi)
    val server = if holding then HeldToDocument.server(plain) else plain
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
    // An earlier scenario's kit has stopped, but its dispatchers' threads end a moment later.
    val deadline = System.nanoTime() + 20.seconds.toNanos
    while ankkaThreads.exists(_.startsWith("ankka-test-")) && System.nanoTime() < deadline do
      Thread.sleep(100)
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

  // ── held-to-document.feature ──────────────────────────────────────────────

  private val Messages = "GET /players/{playerId}/messages"

  Given(
    "the route {string} whose route description gives its answer the shape of {string}, names the refusal {string} and names the query parameter {string}"
  ) { (route: String, name: String, reason: String, parameter: String) =>
    assertEquals((route, name), (Messages, "Messages"))
    player(
      route,
      Kind.AnswersMessages,
      _.answers[OpenApiFeatureFixtures.Messages](200)
        .refuses(404, reason)
        .query[Int](parameter)
    )
  }

  Given("a test that holds the service to its document") { () => holding = true }

  Given("a test that does not hold the service to its document") { () => holding = false }

  /** Requests made against the running service, and what the check recorded of them. */
  private def requests(made: (String, String, String, Option[String])*): Unit =
    running { base =>
      made.foreach((method, path, _, body) => send(base, method, path, body): Unit)
    }
    seen = HeldToDocument.violations
    HeldToDocument.clear()

  private def messages(answer: String, query: String = ""): Unit =
    messagesAnswer.set(answer)
    requests(("GET", s"/players/p1/messages$query", "", None))

  When("the route answers with the refusal {string}") { (reason: String) =>
    assertEquals(reason, "conflict"); messages("conflict")
  }

  When("the route answers a body that lacks a part {string} requires") { (name: String) =>
    assertEquals(name, "Messages"); messages("lacking")
  }

  Given("the route {string} whose route description gives the body the shape of {string}") {
    (route: String, name: String) =>
      assertEquals(name, "EditProfile")
      player(route, Kind.TextBody, _.body[EditProfile])
      focus = Some(Messages)
  }

  When("a request to it carries a body {string} refuses and its handler accepts it") {
    (name: String) =>
      assertEquals(name, "EditProfile")
      requests(("PATCH", "/players/p1", "", Some("""{"nick":"ann"}""")))
  }

  When("the route's handler reads the query parameter {string}") { (name: String) =>
    messages(s"read $name")
  }

  When("every request of the test is answered as the document says") { () =>
    messages("read page", "?page=2")
  }

  private def failedNamingRoute(route: String, words: String*): Unit =
    assert(holding, "the test does not hold the service to its document")
    assert(
      seen.exists(v => v.startsWith(s"$route: ") && words.forall(v.contains)),
      seen.mkString("; ")
    )

  Then("the test fails, naming the route and the refusal {string}") { (reason: String) =>
    assertEquals(reason, "conflict")
    failedNamingRoute(Messages, "answered 409", "which its description does not name (200, 404)")
  }

  Then("the test fails, naming the route and the part that is missing") { () =>
    failedNamingRoute(Messages, "answered 200 with a body its shape refuses", "total")
  }

  Then("the test fails, naming the route and what the shape refused") { () =>
    failedNamingRoute("PATCH /players/{playerId}", "took a body its shape refuses", "name")
  }

  Then("the test fails, naming the route and the query parameter {string}") { (name: String) =>
    failedNamingRoute(Messages, s"the query parameter \"$name\"")
  }

  Then("the test passes") { () =>
    assert(holding)
    assertEquals(seen, Vector.empty)
  }

  Then("nothing about the document fails the test")(() => assertEquals(seen, Vector.empty))

  Given("the route {string} is an incomplete route that lacks its body's shape") { (route: String) =>
    player(route, Kind.TextBody)
  }

  When("a request to it carries any body its handler accepts") { () =>
    requests(("PATCH", "/players/p1", "", Some("anything at all, which is no JSON")))
  }

  // ── serving.feature: the local scenario ───────────────────────────────────

  private var localDocument: Option[(Int, String)]  = None
  private var documentAcl: Option[Acl]              = None
  private var asked: Option[(Int, String, String)]  = None // status, challenge, body
  private var answeredBefore: Vector[(Int, String)] = Vector.empty

  /**
   * Tokens from the issuer a scenario names: a bearer of the issuer's name is a verified person.
   */
  private def issuerTokens(issuer: String): Acl = Acl.Authenticate(new TokenAuthenticator:
    def authorizationServers: Vector[String]         = Vector(s"https://auth.$issuer.example")
    def apply(context: RequestContext): AuthDecision =
      context.header("Authorization") match
        case Some(s"Bearer $t") if t == issuer => AuthDecision.Allow(Principal("ann"))
        case _ => AuthDecision.Unauthenticated(s"realm=\"$issuer\""))

  /** The scenario's service asked for its OpenAPI document, with `token` when it has one. */
  private def askForDocument(token: Option[String]): Unit =
    running { base =>
      val request = JdkRequest.newBuilder(URI.create(s"$base/_ankka/openapi.json")).GET()
      token.foreach(t => request.header("Authorization", s"Bearer $t"))
      val answer = http.send(request.build(), JdkResponse.BodyHandlers.ofString())
      val other  = send(base, "GET", "/_ankka/no-such-route", None)
      asked = Some(
        (answer.statusCode, answer.headers.firstValue("WWW-Authenticate").orElse(""), answer.body)
      )
      unknownPath = Some(other)
      if answer.statusCode == 200 then text = Some(answer.body)
    }

  private var unknownPath: Option[(Int, String)] = None

  private def answer: (Int, String, String) = asked.getOrElse(fail("nobody asked for the document"))

  Given(
    "the service states an ACL for its OpenAPI document that admits tokens from the issuer {string}"
  ) { (issuer: String) =>
    documentAcl = Some(issuerTokens(issuer))
  }

  Given("the service states no ACL for its OpenAPI document") { () => documentAcl = None }

  Given("the route {string} admits only the workloads it names") { (route: String) =>
    routes += route; focus = Some(route); acl = aclOf("admits only the workloads it names")
  }

  When(
    "a person with a verified token from the issuer {string} asks the service for its OpenAPI document"
  ) { (issuer: String) =>
    askForDocument(Some(issuer))
  }

  When("a person with no token asks the service for its OpenAPI document") { () =>
    askForDocument(None)
  }

  When("a person asks the service for its OpenAPI document")(() => askForDocument(None))

  Then("the person is given the document") { () =>
    val (status, _, body) = answer
    assertEquals(status, 200, body)
    text = Some(body)
  }

  Then("it is the same as the document a developer writes from that build") { () =>
    val served = text.getOrElse(fail("no document was given"))
    // The document's title is the running service's name, and a test kit runs every service as
    // "ankka-test"; the build is the same, so it is written under that name.
    service = "ankka-test"
    write()
    assertEquals(text, Some(served))
  }

  Then("the request is challenged") { () =>
    val (status, challenge, body) = answer
    assertEquals(status, 401, body)
    assert(challenge.startsWith("Bearer"), s"challenge: '$challenge'")
  }

  Then("the request is answered as a request to a path the service has no route for") { () =>
    val (status, _, body)        = answer
    val (otherStatus, otherBody) = unknownPath.getOrElse(fail("no other path was asked"))
    assertEquals(status, otherStatus)
    assertEquals(body, otherBody.replace("/_ankka/no-such-route", "/_ankka/openapi.json"))
  }

  Given("the route {string} has no route description") { (route: String) =>
    player(route, Kind.AnswersProfile)
    answeredBefore = answersOf(route)
  }

  When("the developer gives it one") { () =>
    describing(
      _.summary("A player's profile")
        .refuses(404, "no such player")
        .tag("profile")
        .query[Int]("page")
    )
  }

  Then("every request to the route is answered as it was before") { () =>
    assert(answeredBefore.nonEmpty, "nothing was answered before")
    assertEquals(answersOf(only), answeredBefore)
  }

  /** What the running service answers to a few requests of `route`'s, as (status, body). */
  private def answersOf(route: String): Vector[(Int, String)] =
    val (method, template) = route.split(' ') match
      case Array(m, t) => (m, t)
      case _           => fail(s"not a route: $route")
    running { base =>
      Vector("p1", "p2?page=3", "nobody").map { id =>
        send(base, method, template.replaceAll("\\{[^}]+\\}", id), None)
      }
    }

  Given("a deployed service {string} with an HTTP endpoint for the path {string}") {
    (name: String, prefix: String) =>
      assertEquals(prefix, "/carts")
      service = name
      routes = Set("GET /carts/{cartId}", "DELETE /carts/{cartId}")
  }

  Given("a local service {string} that states no ACL for its OpenAPI document") { (name: String) =>
    assertEquals(name, service)
  }

  When("the developer asks the local platform for the service's OpenAPI document") { () =>
    val registry = java.nio.file.Files.createTempDirectory("ankka-openapi-local")
    sys.props.put("ankka.running.dir", registry.toString)
    try
      running { _ =>
        val entry = java.nio.file.Files
          .list(registry)
          .filter(_.toString.endsWith(".json"))
          .findFirst()
          .orElseThrow(() => AssertionError("the service wrote no registry entry"))
        val json    = java.nio.file.Files.readString(entry)
        val marker  = "\"observabilityAddress\":\""
        val from    = json.indexOf(marker) + marker.length
        val address = json.substring(from, json.indexOf('"', from))
        val answer  = http.send(
          JdkRequest.newBuilder(URI.create(s"$address/observability/openapi")).GET().build(),
          JdkResponse.BodyHandlers.ofString()
        )
        localDocument = Some((answer.statusCode, answer.body))
      }
    finally sys.props.remove("ankka.running.dir"): Unit
  }

  Then("the developer is given the document") { () =>
    val (status, body) = localDocument.getOrElse(fail("nothing was asked"))
    assertEquals(status, 200, body)
    assert(body.contains("\"/carts/{cartId}\""), body)
  }
