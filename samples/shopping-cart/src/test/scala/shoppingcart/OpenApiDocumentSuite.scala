package shoppingcart

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.networknt.schema.{InputFormat, JsonSchemaFactory, SpecVersion}
import com.thinkmorestupidless.ankka.grpc.GrpcClients
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, HttpServer}
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

  private def send(method: String, path: String, body: Option[String] = None): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
    body match
      case Some(json) =>
        builder.header("Content-Type", "application/json")
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
