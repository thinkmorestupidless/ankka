package shoppingcart

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.networknt.schema.{InputFormat, JsonSchemaFactory, SpecVersion}
import com.thinkmorestupidless.ankka.grpc.GrpcClients
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.mcp.McpRuntime
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions
import shoppingcart.api.*
import shoppingcart.application.{CartRows, CheckoutLog, CheckoutWorkflow, ShoppingCartEntity}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * The sample's OpenAPI document (feature 067, T016), written by the sample's own `main` in a JVM of
 * its own, exactly as `ankka openapi` runs it: no database, no broker, no network.
 */
class OpenApiDocumentSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private val mapper  = ObjectMapper()
  private val schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

  /** The sample's `main` with `ankka.document` set, in a fresh JVM with an empty environment. */
  private def written(): Array[Byte] =
    val dir = Files.createTempDirectory("cart-openapi")
    val out = dir.resolve("openapi.json")
    try
      val java    = Path.of(sys.props("java.home"), "bin", "java").toString
      val builder = ProcessBuilder(
        java,
        "-cp",
        sys.props("java.class.path"),
        "-Dankka.document=openapi",
        s"-Dankka.document.out=$out",
        "-Dankka.service.name=shopping-cart",
        "runShoppingCart"
      ).redirectErrorStream(true)
      builder.environment().clear()
      sys.env.get("PATH").foreach(builder.environment().put("PATH", _))
      val process = builder.start()
      val said    = String(process.getInputStream.readAllBytes(), UTF_8)
      assertEquals(process.waitFor(), 0, said)
      Files.readAllBytes(out)
    finally
      Files.deleteIfExists(out)
      Files.deleteIfExists(dir): Unit

  private lazy val first: Array[Byte] = written()
  private lazy val document: JsonNode = mapper.readTree(first)

  /** The sample's endpoints as its `main` serves them with nothing set in its environment. */
  private val endpoints: Seq[EndpointClients => HttpEndpoint] = Seq(
    clients => ShoppingCartEndpoint(clients.componentClient),
    clients => OpenCartsEndpoint(clients.viewClient),
    clients => CallersEndpoint(clients.services),
    clients => QuestionsEndpoint(clients.componentClient),
    clients => SecretsEndpoint(clients.secrets),
    clients => StorageEndpoint(clients.storage),
    _ => GrpcCallersEndpoint(GrpcClients()),
    clients => CartMcpEndpoint(clients.componentClient),
    clients => CartKeysEndpoint(clients.clientKeys)
  )

  private var testKit: AnkkaTestKit = null
  private var server: HttpServer    = null
  private var baseUrl               = ""
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    server = HttpServer.at("127.0.0.1", 0)(endpoints*)
    testKit = AnkkaTestKit.start(
      Seq(
        ShoppingCartEntity.descriptor,
        CheckoutLog.descriptor,
        CheckoutWorkflow.descriptor,
        CartRows.descriptor
      ) ++ McpRuntime.descriptors,
      Seq(server, ProjectionRuntime(), McpRuntime())
    )
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()

  private def send(
      method: String,
      path: String,
      body: Option[String] = None,
      contentType: String = "application/json"
  ): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
    body match
      case Some(json) =>
        builder.header("Content-Type", contentType)
        builder.method(method, JdkRequest.BodyPublishers.ofString(json)): Unit
      case None => builder.method(method, JdkRequest.BodyPublishers.noBody()): Unit
    val response = http.send(builder.build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  /** What `value` breaks of `schema`, its references read from the document's components. */
  private def violations(schema: JsonNode, value: String): Vector[String] =
    val root = schema.deepCopy[JsonNode]() match
      case o: ObjectNode => o
      case other         => fail(s"a schema that is not an object: $other")
    root.set[JsonNode]("components", document.get("components"))
    schemas.getSchema(root).validate(value, InputFormat.JSON).asScala.toVector.map(_.getMessage)

  private def operation(method: String, path: String): JsonNode =
    Option(document.path("paths").get(path)).flatMap(p => Option(p.get(method.toLowerCase))) match
      case Some(op) => op
      case None     => fail(s"the document has no $method $path")

  private def bodySchema(method: String, path: String): JsonNode =
    operation(method, path)
      .path("requestBody")
      .path("content")
      .path("application/json")
      .path("schema")

  private def answerSchema(method: String, path: String, status: Int): JsonNode =
    operation(method, path)
      .path("responses")
      .path(status.toString)
      .path("content")
      .path("application/json")
      .path("schema")

  test("writing the document twice from the same code gives the same document") {
    assert(first.sameElements(written()), "the two documents differ")
  }

  test("the document parses as OpenAPI 3.1") {
    val options = ParseOptions()
    options.setResolve(true)
    val result = OpenAPIParser().readContents(String(first, UTF_8), null, options)
    assertEquals(
      Option(result.getMessages).map(_.asScala.toVector).getOrElse(Vector.empty[String]),
      Vector()
    )
    assertEquals(result.getOpenAPI.getOpenapi, "3.1.0")
  }

  test("every route the server lists, less sockets, MCP and _ankka, is an operation (SC-003)") {
    val listed = server.routes
      .filterNot(r =>
        r.method == "SOCKET" || r.endpoint == "endpoint:/mcp" || r.path.startsWith("/_ankka")
      )
      .map(r => s"${r.method} ${r.path}")
      .toSet
    val documented = document.path("paths").properties.asScala.toVector.flatMap { entry =>
      entry.getValue.properties.asScala.map(m => s"${m.getKey.toUpperCase} ${entry.getKey}")
    }
    assertEquals(documented.toSet, listed)
    assertEquals(documented.size, listed.size)
  }

  private def item(productId: String, name: String, quantity: Int) =
    s"""{"productId":"$productId","name":"$name","quantity":$quantity}"""

  test("every body the HTTP suite sends and every answer it gets fits the document (SC-004)") {
    val body = item("p1", "Widget", 2)
    assertEquals(violations(bodySchema("POST", "/carts/{cartId}/items"), body), Vector.empty)
    assertEquals(send("POST", "/carts/doc-1/items", Some(body))._1, 204)
    assertEquals(send("POST", "/carts/doc-1/items", Some(item("p2", "Gadget", 3)))._1, 204)

    val (status, cart) = send("GET", "/carts/doc-1")
    assertEquals(status, 200)
    assertEquals(violations(answerSchema("GET", "/carts/{cartId}", 200), cart), Vector.empty)

    val (totalStatus, total) = send("GET", "/carts/doc-1/total")
    assertEquals((totalStatus, total), (200, "5"))
    assertEquals(violations(answerSchema("GET", "/carts/{cartId}/total", 200), total), Vector.empty)

    assertEquals(
      violations(bodySchema("POST", "/carts/{cartId}/items"), """{"productId":"p1"}""").nonEmpty,
      true,
      "a body missing its fields fits, so the check checks nothing"
    )
  }

  test("the receipt route is described in at most ten lines (SC-005)") {
    val source = Files
      .readAllLines(
        Path.of("src/main/scala/shoppingcart/api/ShoppingCartEndpoint.scala"),
        UTF_8
      )
      .asScala
      .toVector
    val start = source.indexWhere(_.contains("// docs:start described"))
    val end   = source.indexWhere(_.contains("// docs:end described"))
    assert(start >= 0 && end > start, "the markers are not there")
    assert(end - start - 1 <= 10, s"${end - start - 1} lines")
  }

  test("the described receipt route is complete, and what it answers fits its document") {
    val receipt = operation("POST", "/carts/{cartId}/receipt")
    assert(!receipt.has("x-ankka-incomplete"), receipt.toString)
    assertEquals(
      receipt.path("parameters").elements.asScala.map(_.path("name").asText).toVector,
      Vector("cartId", "copies", "currency")
    )
    assertEquals(receipt.path("tags").toString, """["cart"]""")
    assertEquals(
      receipt.path("responses").path("404").path("description").asText,
      "the cart is empty"
    )

    assertEquals(send("POST", "/carts/doc-receipt/receipt", Some("thanks"), "text/plain")._1, 404)
    assertEquals(send("POST", "/carts/doc-receipt/items", Some(item("p1", "Widget", 2)))._1, 204)
    val (status, answer) =
      send("POST", "/carts/doc-receipt/receipt?currency=EUR&copies=2", Some("thanks"), "text/plain")
    assertEquals(status, 200, answer)
    assertEquals(
      violations(answerSchema("POST", "/carts/{cartId}/receipt", 200), answer),
      Vector.empty
    )
    assert(answer.contains("\"currency\":\"EUR\""), answer)
  }

  test("the sample's kept openapi.json is the document its code gives") {
    val kept = Path.of("openapi.json")
    if sys.props.get("ankka.docs.update").contains("true") then Files.write(kept, first): Unit
    assert(Files.exists(kept), "samples/shopping-cart/openapi.json is not kept")
    assert(
      Files.readAllBytes(kept).sameElements(first),
      "samples/shopping-cart/openapi.json is stale: run " +
        "sbt -Dankka.docs.update=true 'shoppingCart/testOnly *OpenApiDocumentSuite' and commit it"
    )
  }

  test("served to the callers its ACL admits, the document is the cart's") {
    // docs:start served
    val served = HttpServer
      .at("127.0.0.1", 0)(clients => ShoppingCartEndpoint(clients.componentClient))
      // GET /_ankka/openapi.json, answered to the callers this ACL admits.
      .withOpenApi(Acl.AllowAll)
    // docs:end served
    val kit = AnkkaTestKit.start(Seq(ShoppingCartEntity.descriptor), Seq(served))
    try
      val port    = served.boundPort.getOrElse(fail("server did not bind"))
      val request = JdkRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/_ankka/openapi.json"))
      val response = http.send(request.build(), JdkResponse.BodyHandlers.ofString())
      assertEquals(response.statusCode, 200, response.body)
      assert(response.body.contains("\"/carts/{cartId}/receipt\""), response.body)
    finally kit.stop()
  }
