package com.thinkmorestupidless.ankka.proxy.core

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.io.{BufferedReader, InputStreamReader}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{ConnectException, InetAddress, InetSocketAddress, URI}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * The engine in plain HTTP, against a stand-in process. Who sent a request is read from a header
 * only this suite's transport knows, `X-Test-Sender`, in place of the certificate a cluster reads.
 */
class ProxyEngineSuite extends munit.FunSuite:

  override def munitTimeout: Duration = 60.seconds

  private val loopback = InetAddress.getByName("127.0.0.1")

  /** A transport whose sender is whatever the request's `X-Test-Sender` header says. */
  private object TestTransport extends Transport:
    def listener(port: Int): HttpServer =
      HttpServer.create(new InetSocketAddress(loopback, port), 0)
    def senderOf(exchange: HttpExchange): Either[String, Sender] =
      Option(exchange.getRequestHeaders.getFirst("X-Test-Sender")).getOrElse("local") match
        case "internet" => Right(Sender.Internet(None))
        case "local"    => Right(Sender.Local)
        case "unknown"  => Left("unrecognised caller certificate")
        case service =>
          val parts = service.stripPrefix("service ").split("/", 2)
          Right(Sender.Service(parts(0), parts(1)))

  private final class Proxy(
      interval: FiniteDuration = 300.millis,
      adjust: ProxySettings => ProxySettings = identity
  ):
    val process: StandInProcess = StandInProcess(interval).start()
    val settings: ProxySettings = adjust(
      ProxySettings(
        project = "shop",
        service = "web",
        port = 0,
        processPort = process.port,
        probePort = 0,
        callingPort = 0,
        drainTimeout = 5.seconds
      )
    )
    val engine: ProxyEngine = ProxyEngine(settings, TestTransport, probeAddress = loopback)
    engine.start()
    val ports: ProxyEngine.Ports = engine.ports
    val client: HttpClient =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    def request(target: String, headers: (String, String)*): HttpRequest.Builder =
      val builder = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${ports.public}$target"))
      headers.foreach((n, v) => builder.header(n, v))
      builder

    def get(target: String, headers: (String, String)*): HttpResponse[String] =
      client.send(request(target, headers*).GET().build(), BodyHandlers.ofString())

    def probe(path: String = "/ready"): Int =
      client
        .send(
          HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${ports.probe}$path")).build(),
          BodyHandlers.discarding()
        )
        .statusCode

    def close(): Unit =
      engine.stop()
      process.stop()

  private def withProxy[A](
      interval: FiniteDuration = 300.millis,
      adjust: ProxySettings => ProxySettings = identity
  )(body: Proxy => A): A =
    val proxy = Proxy(interval, adjust)
    try body(proxy)
    finally proxy.close()

  private def marked(response: HttpResponse[?]): Boolean =
    response.headers.firstValue(Answers.MarkerName).toScala.contains(Answers.MarkerValue)

  test("the option that lets the proxy set Host is set on this JVM") {
    val allowed = sys.props.getOrElse("jdk.httpclient.allowRestrictedHeaders", "")
    assert(
      allowed.split(",").map(_.trim).contains("host"),
      s"'$allowed': see proxyCore in build.sbt"
    )
    ProxyEngine.requireHostHeaderAllowed()
  }

  test("starting without that option is refused by name") {
    val key   = "jdk.httpclient.allowRestrictedHeaders"
    val value = sys.props(key)
    sys.props -= key
    try
      val refused = intercept[IllegalStateException](ProxyEngine.requireHostHeaderAllowed())
      assert(refused.getMessage.contains(s"-D$key=host"), refused.getMessage)
    finally sys.props(key) = value
  }

  test(
    "a request arrives at the process unchanged apart from the contract's headers, Host among them"
  ) {
    withProxy(adjust = _.copy(publicAuthority = Some("web-shop.example.test"))) { proxy =>
      val response = proxy.get(
        "/path/to?x=1&y=%20z",
        "Accept"           -> "text/plain",
        "X-Custom"         -> "kept",
        "X-Ankka-Caller"   -> "service shop/orders",
        "X-Forwarded-Host" -> "bank.example",
        "X-Test-Sender"    -> "internet"
      )
      assertEquals(response.statusCode, 200)
      assert(!marked(response))
      val received = proxy.process.requests match
        case Vector(one) => one
        case other       => fail(s"one request expected: $other")
      assertEquals(received.method, "GET")
      assertEquals(received.target, "/path/to?x=1&y=%20z")
      assertEquals(received.header("accept"), Some("text/plain"))
      assertEquals(received.header("x-custom"), Some("kept"))
      assertEquals(received.all("x-ankka-caller"), Vector("internet"))
      assertEquals(received.all("host"), Vector("web-shop.example.test"))
      assertEquals(received.all("x-forwarded-host"), Vector("web-shop.example.test"))
      assertEquals(received.header("x-forwarded-proto"), Some("https"))
      assertEquals(received.header("x-forwarded-port"), Some("443"))
      // The echo the process answered reads back as what it recorded.
      val echoed = StandInProcess.Echo.parse(response.body)
      assertEquals((echoed.method, echoed.target), ("GET", "/path/to?x=1&y=%20z"))
      assertEquals(echoed.header("x-ankka-caller"), Some("internet"))
    }
  }

  test("a local request is told so, with the proxy's own address") {
    withProxy() { proxy =>
      proxy.get("/", "X-Test-Sender" -> "local")
      val received = proxy.process.requests.head
      assertEquals(received.header("x-ankka-caller"), Some("local"))
      assertEquals(received.header("x-forwarded-proto"), Some("http"))
      assertEquals(received.header("host"), Some(s"127.0.0.1:${proxy.ports.public}"))
      assertEquals(received.header("x-forwarded-port"), Some(proxy.ports.public.toString))
    }
  }

  test("a posted 1 MB body arrives whole, with the length the sender sent") {
    withProxy() { proxy =>
      val body = new Array[Byte](1024 * 1024)
      new scala.util.Random(7).nextBytes(body)
      val response = proxy.client.send(
        proxy.request("/body").POST(BodyPublishers.ofByteArray(body)).build(),
        BodyHandlers.ofByteArray()
      )
      assertEquals(response.statusCode, 200)
      assert(java.util.Arrays.equals(response.body, body), "the body came back changed")
      val received = proxy.process.requests.head
      assertEquals(received.header("content-length"), Some(body.length.toString))
      assertEquals(received.header("transfer-encoding"), None)
      assert(java.util.Arrays.equals(received.body, body), "the body arrived changed")
    }
  }

  test("a chunked body arrives whole") {
    withProxy() { proxy =>
      val body = ("x" * 100_000).getBytes("UTF-8")
      val response = proxy.client.send(
        proxy
          .request("/body")
          .POST(BodyPublishers.ofInputStream(() => new java.io.ByteArrayInputStream(body)))
          .build(),
        BodyHandlers.ofByteArray()
      )
      assertEquals(response.statusCode, 200)
      assert(java.util.Arrays.equals(response.body, body))
      assert(java.util.Arrays.equals(proxy.process.requests.head.body, body))
    }
  }

  test("the first streamed part is read before the last is written") {
    withProxy() { proxy =>
      val response =
        proxy.client.send(proxy.request("/stream").GET().build(), BodyHandlers.ofInputStream())
      val reader  = new BufferedReader(new InputStreamReader(response.body, "UTF-8"))
      val first   = reader.readLine()
      val firstAt = System.nanoTime()
      val rest    = Iterator.continually(reader.readLine()).takeWhile(_ != null).toVector
      assertEquals(first +: rest, Vector("part 1", "part 2", "part 3"))
      assert(proxy.process.lastPartWrittenAt != 0L, "the process never wrote its last part")
      assert(
        firstAt < proxy.process.lastPartWrittenAt,
        "the first part was read only after the last was written: the proxy held the response"
      )
    }
  }

  test("the response's status and headers pass as the process gave them") {
    withProxy() { proxy =>
      val response = proxy.get("/status/418")
      assertEquals(response.statusCode, 418)
      assertEquals(response.headers.firstValue("X-Flavour").toScala, Some("tea"))
      assertEquals(response.headers.firstValue("Content-Type").toScala, Some("text/plain"))
      assertEquals(response.body, "status 418")
      assert(!marked(response))
    }
  }

  test("a HEAD request is answered with the process's status and headers and no body") {
    withProxy() { proxy =>
      val response = proxy.client.send(
        proxy.request("/status/418").method("HEAD", BodyPublishers.noBody()).build(),
        BodyHandlers.ofString()
      )
      assertEquals(response.statusCode, 418)
      assertEquals(response.headers.firstValue("X-Flavour").toScala, Some("tea"))
      assertEquals(response.body, "")
      assertEquals(proxy.process.requests.head.method, "HEAD")
    }
  }

  test(
    "a request the process does not answer in time is answered by the proxy, and the probe still answers"
  ) {
    withProxy(adjust = _.copy(responseTimeout = 500.millis)) { proxy =>
      val response = proxy.get("/stall")
      assertEquals(response.statusCode, 504)
      assert(marked(response))
      assertEquals(
        response.body,
        """{"error":"the process did not answer within 500 milliseconds"}"""
      )
      assertEquals(proxy.probe(), 200)
    }
  }

  test("the timeout never cuts a response that has begun") {
    withProxy(interval = 400.millis, adjust = _.copy(responseTimeout = 500.millis)) { proxy =>
      val response = proxy.get("/stream")
      assertEquals(response.statusCode, 200)
      assertEquals(response.body, "part 1\npart 2\npart 3\n")
    }
  }

  test("with the process stopped the answer is 503 and so is the probe") {
    withProxy() { proxy =>
      assertEquals(proxy.probe(), 200)
      proxy.process.stop()
      val response = proxy.get("/anything")
      assertEquals(response.statusCode, 503)
      assert(marked(response))
      assertEquals(response.body, """{"error":"the process is not listening"}""")
      assertEquals(proxy.probe(), 503)
    }
  }

  test("a process that closes the connection before answering is 502") {
    withProxy() { proxy =>
      val response = proxy.get("/close")
      assertEquals(response.statusCode, 502)
      assert(marked(response))
      assertEquals(
        response.body,
        """{"error":"the process closed the connection before answering"}"""
      )
    }
  }

  test("a service the descriptor does not admit is refused, and the process is given no request") {
    withProxy() { proxy =>
      val response = proxy.get("/anything", "X-Test-Sender" -> "service shop/orders")
      assertEquals(response.statusCode, 403)
      assert(marked(response))
      assertEquals(response.body, """{"error":"the caller is not admitted: service shop/orders"}""")
      assertEquals(proxy.process.requests, Vector.empty)
    }
  }

  test("a named service is admitted and told who it is") {
    withProxy(adjust = _.copy(callers = Vector(Admitted.Service("shop", "orders")))) { proxy =>
      val response = proxy.get("/anything", "X-Test-Sender" -> "service shop/orders")
      assertEquals(response.statusCode, 200)
      val received = proxy.process.requests.head
      assertEquals(received.header("x-ankka-caller"), Some("service shop/orders"))
      assertEquals(
        received.header("host"),
        Some(s"web.ankka-shop.svc.cluster.local:${proxy.ports.public}")
      )
    }
  }

  test("a sender nobody recognises is refused with the transport's reason") {
    withProxy() { proxy =>
      val response = proxy.get("/anything", "X-Test-Sender" -> "unknown")
      assertEquals(response.statusCode, 403)
      assert(marked(response))
      assertEquals(response.body, """{"error":"unrecognised caller certificate"}""")
      assertEquals(proxy.process.requests, Vector.empty)
    }
  }

  test("the proxy's own answers are reported, and passed requests are not") {
    val reported = new java.util.concurrent.ConcurrentLinkedQueue[(String, Int)]()
    val process  = StandInProcess().start()
    val settings = ProxySettings("shop", "web", 0, process.port, probePort = 0, callingPort = 0)
    val events = new ProxyEngine.Events:
      def answered(sender: Option[Sender], method: String, target: String, answer: Answer): Unit =
        reported.add(target -> answer.status): Unit
    val engine = ProxyEngine(settings, TestTransport, loopback, events)
    engine.start()
    try
      val client = HttpClient.newHttpClient()
      def get(target: String, sender: String) = client.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${engine.ports.public}$target"))
          .header("X-Test-Sender", sender)
          .build(),
        BodyHandlers.discarding()
      )
      get("/passed", "local")
      get("/refused", "service shop/orders")
      assertEquals(reported.asScala.toVector, Vector("/refused" -> 403))
    finally
      engine.stop()
      process.stop()
  }

  test("the probe answers 404 for any path but /ready") {
    withProxy() { proxy =>
      assertEquals(proxy.probe("/healthz"), 404)
      assertEquals(proxy.probe("/"), 404)
    }
  }

  test("stop() lets a request in flight finish, then refuses new connections") {
    val proxy = Proxy()
    val response =
      proxy.client.send(proxy.request("/stream").GET().build(), BodyHandlers.ofInputStream())
    val reader = new BufferedReader(new InputStreamReader(response.body, "UTF-8"))
    assertEquals(reader.readLine(), "part 1")
    val stopping = CompletableFuture.runAsync(() => proxy.engine.stop())
    val rest     = Iterator.continually(reader.readLine()).takeWhile(_ != null).toVector
    assertEquals(rest, Vector("part 2", "part 3"))
    stopping.get(10, TimeUnit.SECONDS)
    intercept[ConnectException](
      HttpClient.newHttpClient().send(proxy.request("/").GET().build(), BodyHandlers.discarding())
    )
    proxy.process.stop()
  }

  test("ports are the ones bound, when the settings said 0") {
    withProxy() { proxy =>
      assert(proxy.ports.public > 0)
      assert(proxy.ports.probe > 0)
      assertNotEquals(proxy.ports.public, proxy.ports.probe)
    }
  }
