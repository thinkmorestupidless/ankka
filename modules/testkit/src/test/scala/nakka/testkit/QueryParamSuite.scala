package nakka.testkit

import nakka.http.HttpServer

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt

/** Query parameters and headers, over real HTTP. */
class QueryParamSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  private var testKit: NakkaTestKit = null
  private var server: HttpServer    = null
  private var baseUrl: String       = ""

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    server = HttpServer.at("127.0.0.1", 0)(_ => QueryEndpoint(), _ => GatedEndpoint())
    testKit = NakkaTestKit.start(Seq(OrderEntity.descriptor), Seq(server))
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def get(
      path: String,
      headers: Vector[(String, String)] = Vector.empty
  ): (Int, String) =
    val builder = JdkRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
    headers.foreach((name, value) => builder.header(name, value): Unit)
    val response = http.send(builder.GET().build(), JdkResponse.BodyHandlers.ofString())
    (response.statusCode, response.body)

  private def post(path: String, body: String): (Int, String) =
    val response = http.send(
      JdkRequest
        .newBuilder(URI.create(baseUrl + path))
        .header("Content-Type", "application/json")
        .POST(JdkRequest.BodyPublishers.ofString(body))
        .build(),
      JdkResponse.BodyHandlers.ofString()
    )
    (response.statusCode, response.body)

  test("a required parameter is read and typed") {
    val (status, body) = get("/search/?q=widgets&limit=5")
    assertEquals(status, 200)
    assert(body.contains("\"term\":\"widgets\""), body)
    assert(body.contains("\"limit\":5"), body)
  }

  test("an optional parameter falls back to the handler's default") {
    val (_, body) = get("/search/?q=widgets")
    assert(body.contains("\"limit\":20"), body)
  }

  test("a missing required parameter is a 400 naming it") {
    val (status, body) = get("/search/?limit=5")
    assertEquals(status, 400)
    assert(body.contains("'q'"), body)
    assert(body.contains("required"), body)
  }

  test("an unparseable parameter is a 400 naming it and the expected type") {
    val (status, body) = get("/search/?q=widgets&limit=abc")
    assertEquals(status, 400)
    assert(body.contains("'limit'"), body)
    assert(body.contains("int"), body)
  }

  test("a repeated parameter yields every value, in order") {
    val (_, body) = get("/search/?q=widgets&tag=red&tag=large&tag=sale")
    assert(body.contains("[\"red\",\"large\",\"sale\"]"), body)
  }

  test("a flag counts as true when present with no value") {
    // `?verbose` and `?verbose=true` should agree.
    assert(get("/search/?q=x&verbose")._2.contains("\"verbose\":true"))
    assert(get("/search/?q=x&verbose=true")._2.contains("\"verbose\":true"))
    assert(get("/search/?q=x")._2.contains("\"verbose\":false"))
    assert(get("/search/?q=x&verbose=false")._2.contains("\"verbose\":false"))
  }

  test("query parameters work alongside a path parameter") {
    assertEquals(get("/search/in/tools?q=hammer&limit=3"), (200, "tools:hammer:3"))
  }

  test("query parameters work alongside a request body") {
    val body = """{"term":"drill","limit":1,"tags":[],"verbose":false}"""
    assertEquals(post("/search/submit/tools?mode=fast", body), (200, "tools:drill:fast"))
  }

  test("headers are readable") {
    assertEquals(get("/search/trace", Vector("X-Trace-Id" -> "abc-123")), (200, "abc-123"))
    assertEquals(get("/search/trace"), (200, "none"))
    // Header lookup is case-insensitive, as HTTP requires.
    assertEquals(get("/search/trace", Vector("x-trace-id" -> "lower")), (200, "lower"))
  }

  test("the context exposes method, path and parameter count") {
    val (_, body) = get("/search/describe?a=1&b=2")
    assertEquals(body, "GET /search/describe params=2")
  }

  test("percent-encoded values are decoded before the handler sees them") {
    val (_, body) = get("/search/?q=two%20words%20%26%20more")
    assert(body.contains("\"term\":\"two words & more\""), body)
  }

  test("a streaming handler reads query parameters while building its source") {
    val (status, body) = get("/search/stream?q=tick&count=3")
    assertEquals(status, 200)
    val events = body.linesIterator
      .collect { case line if line.startsWith("data:") => line.drop(5).trim }
      .toVector
    assertEquals(events, Vector("\"tick-1\"", "\"tick-2\"", "\"tick-3\""))
  }

  test("a streaming handler's parameter errors still become a 400") {
    assertEquals(get("/search/stream?count=3")._1, 400)
  }

  test("an ACL predicate reads the same context the handler would") {
    assertEquals(get("/gated/")._1, 403)
    assertEquals(get("/gated/", Vector("X-Api-Key" -> "let-me-in")), (200, "allowed"))
    assertEquals(get("/gated/", Vector("X-Api-Key" -> "wrong"))._1, 403)
    // The predicate can inspect query parameters too.
    assertEquals(get("/gated/?public")._1, 200)
  }
