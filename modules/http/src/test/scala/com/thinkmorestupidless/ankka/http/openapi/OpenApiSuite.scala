package com.thinkmorestupidless.ankka.http.openapi

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.graph.GraphJson
import com.thinkmorestupidless.ankka.core.shape.TypeName
import com.thinkmorestupidless.ankka.core.{Codecs, Done}
import com.thinkmorestupidless.ankka.http.*
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions
import org.apache.pekko.stream.scaladsl.Source

import scala.jdk.CollectionConverters.*

object OpenApiFixtures:
  final case class AddItem(productId: String, quantity: Int)
  final case class LineItem(productId: String, quantity: Int)
  final case class Cart(cartId: String, items: List[LineItem])

  object players:
    final case class Profile(name: String)
  object reporting:
    final case class Profile(total: Int)

  final case class AnkkaProblem(reason: String)

/** The document `OpenApi.build` writes (feature 067); cases named after their scenarios. */
class OpenApiSuite extends munit.FunSuite:
  import OpenApiFixtures.*

  private given JsonValueCodec[AddItem]                        = Codecs.make
  private given JsonValueCodec[Cart]                           = Codecs.make
  private given playerCodec: JsonValueCodec[players.Profile]   = Codecs.make
  private given reportCodec: JsonValueCodec[reporting.Profile] = Codecs.make
  private given JsonValueCodec[AnkkaProblem]                   = Codecs.make

  private def document(endpoints: HttpEndpoint*): Document =
    OpenApi.build("cart", "1.0.0", endpoints) match
      case Right(d)       => d
      case Left(problems) => fail(problems.mkString("\n"))

  private def json(d: Document): GraphJson =
    GraphJson.parse(d.render.getBytes("UTF-8")).fold(fail(_), identity)

  private def at(j: GraphJson, path: String*): GraphJson =
    path.foldLeft(j)((node, key) =>
      node match
        case GraphJson.Arr(items) if key.toIntOption.isDefined => items(key.toInt)
        case other => other.field(key).getOrElse(fail(s"no $key in ${Shapes.write(other)}"))
    )

  private def has(j: GraphJson, path: String*): Boolean =
    path.foldLeft(Option(j))((node, key) => node.flatMap(_.field(key))).isDefined

  private object Shapes:
    def write(j: GraphJson): String = com.thinkmorestupidless.ankka.core.shape.Shape.write(j)

  /** What swagger-parser says of the document as 3.1: nothing, for a valid one. */
  private def parserMessages(d: Document): Vector[String] =
    val options = ParseOptions()
    options.setResolve(true)
    val result = OpenAPIParser().readContents(d.render, null, options)
    assert(result.getOpenAPI != null, s"not read: ${result.getMessages}")
    assertEquals(result.getOpenAPI.getOpenapi, "3.1.0")
    Option(result.getMessages).map(_.asScala.toVector).getOrElse(Vector.empty)

  private final class Carts extends HttpEndpoint("/carts"):
    val acl: Acl = Acl.AllowAll

    postBody("/{cartId}/items")((cartId: String, item: AddItem) =>
      Cart(cartId, List(LineItem(item.productId, item.quantity)))
    )
    get("/{cartId}")((cartId: String) => Cart(cartId, Nil))
    get("/{cartId}/items/{line}")((cartId: String, line: Int) =>
      Cart(cartId, Nil).copy(cartId = s"$line")
    )
    delete("/{cartId}")((_: String) => Done.done)
    post("/{cartId}/checkout")((cartId: String) => Cart(cartId, Nil))
    sse("/{cartId}/events")((_: String) => Source.single("changed"))
    socket("/{cartId}/live")((_: String, socket: Socket) => socket.send("hello"))

  // T010: schema names.

  test("a type's schema is named by its simple name") {
    val names = SchemaNames
      .assign(Vector(TypeName("shop.Cart", "Cart")))
      .fold(p => fail(p.mkString), identity)
    assertEquals(names.values.toVector, Vector("Cart"))
  }

  test(
    "two types of one simple name take the shortest trailing package part that tells them apart"
  ) {
    val a     = TypeName("shop.players.Profile", "Profile")
    val b     = TypeName("shop.reporting.Profile", "Profile")
    val c     = TypeName("a.same.Item", "Item")
    val d     = TypeName("b.same.Item", "Item")
    val names = SchemaNames.assign(Vector(a, b, c, d)).fold(p => fail(p.mkString), identity)
    assertEquals(names(a), "players.Profile")
    assertEquals(names(b), "reporting.Profile")
    assertEquals(names(c), "a.same.Item")
    assertEquals(names(d), "b.same.Item")
  }

  test("the names are the same whatever order the types are met in") {
    val types = Vector(
      TypeName("x.players.Profile", "Profile"),
      TypeName("x.reporting.Profile", "Profile"),
      TypeName("x.Cart", "Cart")
    )
    assertEquals(SchemaNames.assign(types), SchemaNames.assign(types.reverse))
  }

  test("a service type named AnkkaProblem is a problem") {
    final class Clash extends HttpEndpoint("/clash"):
      val acl: Acl = Acl.AllowAll
      get("/")(() => AnkkaProblem("no"))
    val problems = OpenApi.build("cart", "", Seq(Clash())).left.getOrElse(fail("built"))
    assert(problems.exists(_.contains("named AnkkaProblem")), problems)
  }

  test("two types of one simple name keep distinct schemas in one document") {
    final class Both extends HttpEndpoint("/both"):
      val acl: Acl = Acl.AllowAll
      get("/a")(() => players.Profile("a"))
      get("/b")(() => reporting.Profile(1))
    val schemas = at(json(document(Both())), "components", "schemas")
    assert(
      has(schemas, "players.Profile") && has(schemas, "reporting.Profile"),
      Shapes.write(schemas)
    )
  }

  // T011: deriving.feature.

  test("the document holds every route of the service's HTTP endpoints") {
    val d = document(Carts())
    assertEquals(parserMessages(d), Vector.empty)
    assertEquals(
      d.operations.map(_.describe).toSet,
      Set(
        "POST /carts/{cartId}/items",
        "GET /carts/{cartId}",
        "GET /carts/{cartId}/items/{line}",
        "DELETE /carts/{cartId}",
        "POST /carts/{cartId}/checkout",
        "GET /carts/{cartId}/events"
      )
    )
  }

  test("a path parameter is in the document under its name, with the type its route gives it") {
    val parameters =
      at(json(document(Carts())), "paths", "/carts/{cartId}/items/{line}", "get", "parameters")
    assertEquals(
      Shapes.write(parameters),
      """[{"name":"cartId","in":"path","required":true,"schema":{"type":"string"}},""" +
        """{"name":"line","in":"path","required":true,"schema":{"type":"integer"}}]"""
    )
  }

  test("a typed body has its type's shape") {
    val j    = json(document(Carts()))
    val body = at(
      j,
      "paths",
      "/carts/{cartId}/items",
      "post",
      "requestBody",
      "content",
      "application/json",
      "schema"
    )
    assertEquals(Shapes.write(body), """{"$ref":"#/components/schemas/AddItem"}""")
    assertEquals(
      Shapes.write(at(j, "components", "schemas", "AddItem")),
      """{"type":"object","properties":{"productId":{"type":"string"},"quantity":{"type":"integer"}},"required":["productId","quantity"]}"""
    )
  }

  test("a typed answer has its type's shape") {
    val answer = at(json(document(Carts())), "paths", "/carts/{cartId}", "get", "responses", "200")
    assertEquals(
      Shapes.write(answer),
      """{"description":"","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Cart"}}}}"""
    )
  }

  test("a route that answers nothing has an answer with no body, and is complete") {
    val d   = document(Carts())
    val del = at(json(d), "paths", "/carts/{cartId}", "delete", "responses")
    assertEquals(Shapes.write(at(del, "204")), """{"description":""}""")
    assert(!d.operations.find(_.describe == "DELETE /carts/{cartId}").get.incomplete)
  }

  test("a route answered as a stream of events is in the document as one") {
    val events =
      at(json(document(Carts())), "paths", "/carts/{cartId}/events", "get", "responses", "200")
    assertEquals(Shapes.write(events), """{"description":"","content":{"text/event-stream":{}}}""")
  }

  test("a type two routes use is one named shape that both refer to") {
    val j       = json(document(Carts()))
    val schemas = at(j, "components", "schemas") match
      case GraphJson.Obj(fields) => fields.map(_._1)
      case other                 => fail(Shapes.write(other))
    assertEquals(schemas.count(_ == "Cart"), 1)
    for (path, method) <- Seq("/carts/{cartId}" -> "get", "/carts/{cartId}/checkout" -> "post") do
      assertEquals(
        Shapes.write(
          at(j, "paths", path, method, "responses", "200", "content", "application/json", "schema")
        ),
        """{"$ref":"#/components/schemas/Cart"}"""
      )
  }

  test("writing the document twice from the same code gives the same document") {
    assertEquals(document(Carts()).render, document(Carts()).render)
  }

  test(
    "the document's order and form: sorted paths, methods in order, two spaces, a final newline"
  ) {
    val rendered = document(Carts()).render
    assert(rendered.endsWith("}\n") && !rendered.endsWith("\n\n"))
    assert(rendered.startsWith("{\n  \"openapi\": \"3.1.0\",\n  \"info\": {"), rendered.take(80))
    val paths = rendered.linesIterator.collect {
      case l if l.startsWith("    \"/") => l.trim.takeWhile(_ != ':')
    }.toVector
    assertEquals(paths, paths.sorted)
    val cart = rendered.indexOf("\"/carts/{cartId}\": {")
    assert(rendered.indexOf("\"get\"", cart) < rendered.indexOf("\"delete\"", cart))
  }

  test("an operation's id is its method and path, every other character made _") {
    assertEquals(
      document(Carts()).operations.map(_.operationId).toSet,
      Set(
        "post_carts_cartId_items",
        "get_carts_cartId",
        "get_carts_cartId_items_line",
        "delete_carts_cartId",
        "post_carts_cartId_checkout",
        "get_carts_cartId_events"
      )
    )
  }

  test("every operation has a default answer, the platform's AnkkaProblem") {
    val j = json(document(Carts()))
    assertEquals(
      Shapes.write(at(j, "paths", "/carts/{cartId}", "get", "responses", "default")),
      """{"description":"refused","content":{"application/json":{"schema":{"$ref":"#/components/schemas/AnkkaProblem"}}}}"""
    )
    assertEquals(
      Shapes.write(at(j, "components", "schemas", "AnkkaProblem")),
      """{"type":"object","properties":{"status":{"type":"integer"},"error":{"type":"string"},"details":{"type":"object","additionalProperties":{"type":"string"}}},"required":["status","error"]}"""
    )
  }

  test("an answer whose status the handler decides is under default") {
    final class Created extends HttpEndpoint("/made"):
      val acl: Acl = Acl.AllowAll
      post("/")(() => Respond(Cart("c", Nil), 201))
    val responses = at(json(document(Created())), "paths", "/made/", "post", "responses")
    assertEquals(
      Shapes.write(responses),
      """{"default":{"description":"","content":{"application/json":{"schema":{"$ref":"#/components/schemas/Cart"}}}}}"""
    )
  }

  // what is left out: a socket route, an MCP endpoint, a gRPC endpoint, the platform's own routes.

  test("what has no place in an OpenAPI document is left out of it: a socket route") {
    assert(!document(Carts()).operations.exists(_.path.endsWith("/live")))
  }

  test("what has no place in an OpenAPI document is left out of it: an MCP endpoint") {
    final class Tools extends HttpEndpoint("/mcp") with ServesRoutes:
      val acl: Acl = Acl.AllowAll
      get("/")(() => "tools")
      def served: Vector[com.thinkmorestupidless.ankka.runtime.ServedRoute] = Vector.empty
    assert(!document(Carts(), Tools()).operations.exists(_.path.startsWith("/mcp")))
  }

  test("what has no place in an OpenAPI document is left out of it: a gRPC endpoint") {
    // A gRPC endpoint is not an HttpEndpoint: nothing the build is handed can be one.
    assertEquals(document(Carts()).operations.size, 6)
  }

  test("what has no place in an OpenAPI document is left out of it: the platform's own routes") {
    final class Platform extends HttpEndpoint("/_ankka"):
      val acl: Acl = Acl.AllowAll
      get("/storage/{key}")((_: String) => "object")
    final class Rooted extends HttpEndpoint("/r"):
      val acl: Acl = Acl.AllowAll
      get("/")(() => "here")
      override private[ankka] def rootRoutes: Vector[Route] =
        Vector(Route("GET", PathTemplate.parse("/.well-known/x"), false, (_, _) => null))
    val d = document(Platform(), Rooted())
    assertEquals(d.operations.map(_.describe), Vector("GET /r/"))
  }

  // T012: security and incompleteness.

  private final class Guarded(acl0: Acl) extends HttpEndpoint("/carts"):
    val acl: Acl = acl0
    get("/{cartId}")((cartId: String) => Cart(cartId, Nil))

  private val tokens: Acl = Acl.Authenticate(new TokenAuthenticator:
    def authorizationServers: Vector[String]         = Vector("https://shop.example/realms/shop")
    def apply(context: RequestContext): AuthDecision = AuthDecision.Unauthenticated("Bearer"))

  private def operationJson(acl: Acl): GraphJson =
    at(json(document(Guarded(acl))), "paths", "/carts/{cartId}", "get")

  test("a route says who may call it as far as its ACL tells: allows all") {
    val op = operationJson(Acl.AllowAll)
    assertEquals(Shapes.write(at(op, "security")), "[]")
    assert(!has(op, "x-ankka-admission"))
  }

  test(
    "a route says who may call it as far as its ACL tells: admits tokens from the issuer \"shop\""
  ) {
    val d  = json(document(Guarded(tokens)))
    val op = at(d, "paths", "/carts/{cartId}", "get")
    assertEquals(Shapes.write(at(op, "security")), """[{"bearer":[]}]""")
    assertEquals(
      Shapes.write(at(d, "components", "securitySchemes")),
      """{"bearer":{"type":"http","scheme":"bearer"}}"""
    )
    assert(!document(Guarded(tokens)).render.contains("shop.example"), "an issuer is named")
  }

  test("a route says who may call it as far as its ACL tells: admits only the workloads it names") {
    val op = operationJson(Acl.allowCallers(CallerMatcher.AnyService))
    assert(!has(op, "security"))
    assertEquals(Shapes.write(at(op, "x-ankka-admission")), "\"not-open\"")
  }

  test("an AnyOf holding tokens needs a token") {
    val op = operationJson(Acl.anyOf(Acl.allowCallers(CallerMatcher.AnyService), tokens))
    assertEquals(Shapes.write(at(op, "security")), """[{"bearer":[]}]""")
  }

  test("a route's own ACL is the one that counts") {
    final class Mixed extends HttpEndpoint("/m"):
      val acl: Acl = Acl.allowCallers(CallerMatcher.AnyService)
      withAcl(Acl.AllowAll) {
        get("/open")(() => Cart("c", Nil))
      }
      get("/closed")(() => Cart("c", Nil))
    val d = document(Mixed())
    assertEquals(
      d.operations.map(o => o.path -> o.access),
      Vector("/m/closed" -> Access.NotOpen, "/m/open" -> Access.Open)
    )
  }

  private final class Players extends HttpEndpoint("/players"):
    val acl: Acl = Acl.AllowAll
    patchBody("/{playerId}")((_: String, edit: String) => edit.length)
    get("/{playerId}/export")((_: String) =>
      Streamed("text/csv", None, java.io.InputStream.nullInputStream())
    )
    postStream("/{playerId}/import")((_: String, _: StreamedBody) => Done.done)
    get("/{playerId}/card")((_: String) => Bytes("image/png", Array.emptyByteArray))

  test("an incomplete route is in the document, marked with what it lacks") {
    val j = json(document(Players()))
    assertEquals(
      Shapes.write(at(j, "paths", "/players/{playerId}", "patch", "x-ankka-incomplete")),
      """["request body"]"""
    )
    assertEquals(
      Shapes.write(at(j, "paths", "/players/{playerId}", "patch", "requestBody")),
      """{"content":{"text/plain":{"schema":{"type":"string"}}}}"""
    )
    assertEquals(
      Shapes.write(at(j, "paths", "/players/{playerId}/card", "get", "x-ankka-incomplete")),
      """["answer 200"]"""
    )
  }

  test("a body or an answer that is a stream of bytes does not make a route incomplete") {
    val d = document(Players())
    for route <- Seq("GET /players/{playerId}/export", "POST /players/{playerId}/import") do
      assert(!d.operations.find(_.describe == route).get.incomplete, route)
    assertEquals(
      Shapes.write(at(json(d), "paths", "/players/{playerId}/import", "post", "requestBody")),
      """{"content":{"application/octet-stream":{}}}"""
    )
  }

  test("every document this suite writes reads as 3.1 with no message") {
    for d <- Seq(
        document(Carts()),
        document(Players()),
        document(Guarded(tokens)),
        document(Guarded(Acl.DenyAll))
      )
    do assertEquals(parserMessages(d), Vector.empty)
  }

  // T013: the server answers for `openapi`.

  test("a server answers the openapi document of the endpoints its factories build") {
    val server   = HttpServer.of(_ => Carts())
    val answered = server.document("openapi", Map("name" -> "cart", "version" -> "1.0.0"))
    assertEquals(answered, Some(Right(document(Carts()).render)))
    assertEquals(server.document("asyncapi", Map.empty), None)
  }

  test(
    "an endpoint that cannot be built without the running service fails the writing and is named"
  ) {
    val answered = HttpServer
      .of(clients => OpenApiReportsEndpoint(clients), _ => Carts())
      .document("openapi", Map("name" -> "cart"))
    assertEquals(
      answered,
      Some(
        Left(
          Vector(
            "the endpoint OpenApiReportsEndpoint could not be built: not called while a document is built"
          )
        )
      )
    )
  }

  test("a server with two endpoints at one prefix is refused with validate's words") {
    val answered = HttpServer.of(_ => Carts(), _ => Carts()).document("openapi", Map.empty)
    assertEquals(answered, Some(Left(Vector("2 endpoints share the prefix '/carts'"))))
  }

  test("the options select and require completeness") {
    final class Mixed extends HttpEndpoint("/mixed"):
      val acl: Acl = Acl.AllowAll
      get("/bytes")(() => Bytes("image/png", Array.emptyByteArray))
    val strict = HttpServer.of(_ => Mixed()).document("openapi", Map("strict" -> "true"))
    assertEquals(strict, Some(Left(Vector("GET /mixed/bytes: incomplete, lacks answer 200"))))
    val under =
      HttpServer.of(_ => Mixed(), _ => Carts()).document("openapi", Map("prefixes" -> "/mixed"))
    assert(under.flatMap(_.toOption).exists(d => !d.contains("/carts")), under)
  }

/** An endpoint whose constructor reads a cart: it needs the running service to be built. */
final class OpenApiReportsEndpoint(clients: EndpointClients) extends HttpEndpoint("/reports"):
  val acl: Acl        = Acl.AllowAll
  private val opening = scala.concurrent.Await.result(
    clients.componentClient.transportRef.ask(
      com.thinkmorestupidless.ankka.core.ComponentId("cart"),
      com.thinkmorestupidless.ankka.core.EntityId("c1"),
      com.thinkmorestupidless.ankka.core.MethodName("get"),
      Array.emptyByteArray,
      com.thinkmorestupidless.ankka.core.Metadata.empty
    ),
    scala.concurrent.duration.Duration(1, "second")
  )
  get("/")(() => opening.length)
