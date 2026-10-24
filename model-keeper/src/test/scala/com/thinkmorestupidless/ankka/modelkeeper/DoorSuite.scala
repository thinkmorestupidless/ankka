package com.thinkmorestupidless.ankka.modelkeeper

import com.thinkmorestupidless.ankka.core.{
  ModelServerDocument,
  ModelServerDocumentJson,
  ModelState,
  ModelStatus
}
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.testollama.FakeOllama
import com.thinkmorestupidless.ankka.testpki.TestPki
import io.opentelemetry.api.common.AttributeKey

import java.io.{BufferedReader, IOException, InputStreamReader}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetAddress, URI}
import java.nio.file.{Files, Path}
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLContext
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}

/**
 * The model server's door over mutual TLS on loopback, with certificates a test authority issued
 * and the stand-in Ollama behind it: who gets a connection at all, who is answered, what is passed
 * on, and what is said about each call.
 */
class DoorSuite extends munit.FunSuite with LogCapturing:

  override def munitTimeout: Duration = 60.seconds

  private val loopback  = InetAddress.getByName("127.0.0.1")
  private val authority = TestPki.root("model-door")
  private val foreign   = TestPki.root("model-door-foreign")

  private val doorDir: Path = Files.createTempDirectory("model-door")
  authority.issue(uris = Seq(Main.Identity), dnsNames = Seq("localhost")).writeTo(doorDir)
  private val doorTls = RotatingTls(doorDir, 1.minute)

  private val ollama = FakeOllama.start()
  ollama.hold("qwen3:0.6b")

  @volatile private var ready: Either[String, Unit] = Left("the first pass has not run")
  private val document                              = ModelServerDocument(
    server = "https://models.models-system.svc:8443",
    ready = true,
    models =
      Vector(ModelStatus("qwen3:0.6b", ModelState.Served, Some(FakeOllama.digestOf("qwen3:0.6b"))))
  )
  private val source = new Door.Source:
    def document: ModelServerDocument = DoorSuite.this.document
    def ready: Either[String, Unit]   = DoorSuite.this.ready

  private val settings = KeeperSettings
    .fromEnvironment(
      Map(
        "ANKKA_KEEPER_ADDRESS"     -> document.server,
        "ANKKA_KEEPER_OLLAMA"      -> ollama.url,
        "ANKKA_KEEPER_PORT"        -> "0",
        "ANKKA_KEEPER_STATUS_PORT" -> "0",
        "ANKKA_KEEPER_PROBE_PORT"  -> "0"
      ).get
    )
    .fold(p => throw IllegalStateException(p.mkString), identity)

  private val exported  = RecordingExporter()
  private val telemetry =
    KeeperTelemetry.exportingTo(
      exported.spanExporter,
      None,
      None,
      1,
      KeeperTelemetry.Source(() => document, () => 1L)
    )
  private val door = Door(settings, doorTls, source, telemetry, loopback)
  door.start()
  private val ports = door.ports

  override def afterAll(): Unit =
    door.stop()
    ollama.stop()

  /** A client presenting a certificate `issuer` made for `uri`, trusting the door's authority. */
  private def presenting(issuer: TestPki, uris: String*): SSLContext =
    val dir = issuer.issue(uris = uris).writeTo(Files.createTempDirectory("model-client"))
    Files.writeString(dir.resolve("ca.crt"), authority.pem)
    RotatingTls(dir, 1.minute).sslContext

  /** A client that trusts the authority and presents nothing. */
  private def bare: SSLContext =
    val trust = java.security.KeyStore.getInstance("PKCS12")
    trust.load(null, null)
    trust.setCertificateEntry("ca", authority.certificate)
    val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
      javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm
    )
    tmf.init(trust)
    val context = SSLContext.getInstance("TLS")
    context.init(null, tmf.getTrustManagers, null)
    context

  private lazy val cart = presenting(authority, "ankka://shop/cart")

  private def client(context: SSLContext): HttpClient =
    HttpClient.newBuilder().sslContext(context).version(HttpClient.Version.HTTP_1_1).build()

  private def request(
      context: SSLContext,
      method: String,
      path: String,
      body: String = "",
      headers: Seq[(String, String)] = Nil
  ): HttpResponse[String] =
    val builder = HttpRequest.newBuilder(URI.create(s"https://localhost:${ports.door}$path"))
    headers.foreach((n, v) => builder.header(n, v): Unit)
    builder.method(
      method,
      if body.isEmpty then BodyPublishers.noBody() else BodyPublishers.ofString(body)
    )
    client(context).send(builder.build(), BodyHandlers.ofString())

  private val chatBody = """{"model":"qwen3:0.6b","messages":[{"role":"user","content":"hello"}]}"""

  private def plain(port: Int, path: String): HttpResponse[String] =
    HttpClient
      .newHttpClient()
      .send(
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path")).build(),
        BodyHandlers.ofString()
      )

  /**
   * A connection that never got an HTTP answer. Under TLS 1.3 the client may finish its half of the
   * handshake before the server's alert arrives, so the refusal reads as a closed connection as
   * often as an SSL error; what matters is that no answer came and nothing reached Ollama.
   */
  private def handshakeFailed(attempt: Try[HttpResponse[String]]): Boolean = attempt match
    case Success(response)       => fail(s"answered ${response.statusCode}: ${response.body}")
    case Failure(_: IOException) => true
    case Failure(other) => fail(s"failed with ${other.getClass.getName}: ${other.getMessage}")

  test("a service's certificate is admitted, and its call is passed to the model server") {
    val before   = ollama.requestsTo("POST", "/v1/chat/completions").size
    val response = request(cart, "POST", "/v1/chat/completions", chatBody)
    assertEquals(response.statusCode, 200, response.body)
    assert(response.body.contains("chat.completion"), response.body)
    assertEquals(ollama.requestsTo("POST", "/v1/chat/completions").size, before + 1)
  }

  test("a streamed answer reaches the caller part by part, before the model server has finished") {
    val hold = new CountDownLatch(1)
    ollama.streamHold = Some(hold)
    try
      val builder = HttpRequest
        .newBuilder(URI.create(s"https://localhost:${ports.door}/v1/chat/completions"))
        .POST(BodyPublishers.ofString(chatBody.dropRight(1) + ""","stream":true}"""))
      val response = client(cart).send(builder.build(), BodyHandlers.ofInputStream())
      assertEquals(response.statusCode, 200)
      val reader = new BufferedReader(new InputStreamReader(response.body))
      val first  = reader.readLine()
      // The stand-in is still holding the rest: the first event arrived on its own.
      assert(first.startsWith("data: ") && first.contains("Hel"), first)
      hold.countDown()
      val rest = Iterator.continually(reader.readLine()).takeWhile(_ != null).mkString("\n")
      assert(rest.contains("lo") && rest.contains("[DONE]"), rest)
    finally
      hold.countDown()
      ollama.streamHold = None
  }

  test("the gateway's certificate is refused: the model server admits services only") {
    val response = request(presenting(authority, RotatingTls.GatewayUri), "GET", "/api/tags")
    assertEquals(response.statusCode, 403)
    assertEquals(response.body, Door.NotAService)
  }

  test("a mount's certificate is refused") {
    val response = request(presenting(authority, "ankka://shop/web/mount"), "GET", "/api/tags")
    assertEquals(response.statusCode, 403)
  }

  test("a connection without a certificate fails at the handshake, and Ollama is given nothing") {
    val before = ollama.requests.size
    assert(handshakeFailed(Try(request(bare, "GET", "/api/tags"))))
    assertEquals(ollama.requests.size, before)
  }

  test("a certificate of another authority fails at the handshake") {
    val before = ollama.requests.size
    assert(
      handshakeFailed(Try(request(presenting(foreign, "ankka://shop/cart"), "GET", "/api/tags")))
    )
    assertEquals(ollama.requests.size, before)
  }

  test("only the five paths are passed on: a fetch through the door is not served") {
    val before = ollama.requestsTo("POST", "/api/pull").size
    val pull   = request(cart, "POST", "/api/pull", """{"model":"evil:1b"}""")
    assertEquals(pull.statusCode, 404)
    assertEquals(pull.body, Door.NotServed)
    assertEquals(ollama.requestsTo("POST", "/api/pull").size, before)
    for (method, path) <- Seq(
        "DELETE" -> "/api/delete",
        "POST"   -> "/api/generate",
        "POST"   -> "/api/embed"
      )
    do
      assertEquals(
        request(cart, method, path, """{"model":"qwen3:0.6b"}""").statusCode,
        404,
        s"$method $path"
      )
    for (method, path) <- Seq("GET" -> "/v1/models", "GET" -> "/api/tags", "GET" -> "/api/ps") do
      assertEquals(request(cart, method, path).statusCode, 200, s"$method $path")
    assertEquals(request(cart, "POST", "/api/show", """{"model":"qwen3:0.6b"}""").statusCode, 200)
  }

  test("a caller's credential is not passed on, and the caller is named by its certificate") {
    request(
      cart,
      "POST",
      "/v1/chat/completions",
      chatBody,
      Seq("Authorization" -> "Bearer sk-nothing", "X-Ankka-Caller" -> "platform/controlplane")
    )
    val passed = ollama.requestsTo("POST", "/v1/chat/completions").last
    assertEquals(passed.header("authorization"), None)
    assertEquals(passed.header("x-ankka-caller"), Some("shop/cart"))
  }

  test("the status document is answered to a service through the door, counts included") {
    val response = request(cart, "GET", Door.StatusPath)
    assertEquals(response.statusCode, 200)
    val read = ModelServerDocumentJson.read(response.body).fold(fail(_), identity)
    assertEquals(read.served, Vector("qwen3:0.6b"))
    assert(read.calls.answered >= 1, read.calls.toString)
  }

  test("the status port answers the document and nothing else") {
    val status = plain(ports.status, Door.StatusPath)
    assertEquals(status.statusCode, 200)
    assert(ModelServerDocumentJson.read(status.body).isRight, status.body)
    assertEquals(plain(ports.status, "/v1/chat/completions").statusCode, 404)
    assertEquals(plain(ports.status, "/api/tags").statusCode, 404)
  }

  test("the probe is not ready before the first pass, and ready after it") {
    ready = Left("the first pass has not run")
    val before = plain(ports.probe, "/ready")
    assertEquals(before.statusCode, 503)
    assertEquals(before.body, "the first pass has not run")
    ready = Right(())
    assertEquals(plain(ports.probe, "/ready").statusCode, 200)
    assertEquals(plain(ports.probe, "/live").statusCode, 200)
  }

  test("a call to an installation model is a span in the turn's trace") {
    val trace  = "4bf92f3577b34da6a3ce929d0e0e4736"
    val parent = "00f067aa0ba902b7"
    request(
      cart,
      "POST",
      "/v1/chat/completions",
      chatBody,
      Seq("traceparent" -> s"00-$trace-$parent-01")
    )
    telemetry.flush()
    val span = exported.spans.reverse
      .find(s => s.getName == "models.call" && s.getTraceId == trace)
      .getOrElse(fail(s"no span in the trace: ${exported.spans.map(_.getName)}"))
    assertEquals(span.getParentSpanId, parent)
    val attributes = span.getAttributes
    assertEquals(attributes.get(AttributeKey.stringKey("ankka.model")), "qwen3:0.6b")
    assertEquals(attributes.get(AttributeKey.stringKey("ankka.caller.project")), "shop")
    assertEquals(attributes.get(AttributeKey.stringKey("ankka.caller.service")), "cart")
    assertEquals(
      attributes.get(AttributeKey.longKey("http.response.status_code")),
      java.lang.Long.valueOf(200L)
    )
  }
