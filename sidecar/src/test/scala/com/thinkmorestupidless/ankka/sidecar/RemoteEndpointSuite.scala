package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import ankka.protocol.v1.discovery.{CallerMatcher, Endpoint as EndpointSpec, NamedService}
import ankka.protocol.v1.endpoint.Caller.Kind
import ankka.protocol.v1.payload.Empty
import com.thinkmorestupidless.ankka.http.{Caller, LocalCallers}
import ankka.protocol.v1.endpoint.HttpResponse
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, TestSocket}
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse as JdkResponse}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Declared routes served by the sidecar's own HTTP server, forwarded to the process double:
 * binding, specificity, query and headers, status passthrough, faults, the ACL applied before the
 * process is reached, SSE frames JSON-encoded, and a stopped process answering 503.
 */
class RemoteEndpointSuite extends munit.FunSuite with LogCapturing:

  given ExecutionContext = ExecutionContext.global

  override def munitTimeout: scala.concurrent.duration.Duration = 5.minutes

  private def json(status: Int, body: String) =
    Right(HttpResponse(status, "application/json", ByteString.copyFromUtf8(body)))

  private val carts = ProcessDouble.Endpoint(
    "carts",
    "/carts",
    Vector(
      ProcessDouble
        .Route("get", "GET", "/{id}", handler = r => json(200, s"""{"id":"${r.pathArgs.head}"}""")),
      ProcessDouble
        .Route("awkward", "GET", "/awkward", handler = _ => json(200, """{"literal":true}""")),
      ProcessDouble.Route(
        "add",
        "POST",
        "/{id}/items",
        hasBody = true,
        handler = r =>
          json(
            201,
            s"""{"id":"${r.pathArgs.head}","q":"${r.query
                .map(p => p.name + "=" + p.value)
                .mkString(",")}",""" +
              s""""x":"${r.headers
                  .find(_.name.equalsIgnoreCase("X-Test"))
                  .map(_.value)
                  .getOrElse("")}",""" +
              s""""ct":"${r.contentType}","body":${r.body.toStringUtf8}}"""
          )
      ),
      ProcessDouble.Route(
        "status",
        "GET",
        "/status/{code}",
        handler = r =>
          Right(
            HttpResponse(r.pathArgs.head.toInt, "text/plain", ByteString.copyFromUtf8("as asked"))
          )
      ),
      ProcessDouble.Route("boom", "GET", "/boom", handler = _ => Left(RuntimeException("kaboom"))),
      ProcessDouble.Route(
        "events",
        "GET",
        "/{id}/events",
        streaming = true,
        frames = _ => Vector(" leading space", "two\nlines", "end")
      )
    )
  )
  private val sockets = ProcessDouble.Endpoint(
    "sockets",
    "/sockets",
    Vector(
      ProcessDouble.Route("echo", "GET", "/echo", socket = true),
      ProcessDouble.Route(
        "fail",
        "GET",
        "/fail",
        socket = true,
        onSocket = ProcessDouble.SocketScript.FailOnFrame("the handler broke")
      ),
      ProcessDouble.Route(
        "complete",
        "GET",
        "/complete",
        socket = true,
        onSocket = ProcessDouble.SocketScript.SendThenComplete(2)
      ),
      ProcessDouble.Route(
        "never",
        "GET",
        "/never",
        socket = true,
        onSocket = ProcessDouble.SocketScript.NeverRead
      )
    )
  )
  private val denied = ProcessDouble.Endpoint(
    "private",
    "/private",
    Vector(ProcessDouble.Route("root", "GET", "/")),
    acl = EndpointSpec.Acl.DENY_ALL
  )
  private val authed = ProcessDouble.Endpoint(
    "auth",
    "/auth",
    Vector(ProcessDouble.Route("root", "GET", "/")),
    acl = EndpointSpec.Acl.AUTHENTICATED
  )

  /** Answers with the caller the sidecar forwarded, as the process sees it. */
  private val whoami = ProcessDouble.Endpoint(
    "whoami",
    "/whoami",
    Vector(
      ProcessDouble.Route(
        "root",
        "GET",
        "/",
        handler = r =>
          val seen = r.caller.map(_.kind) match
            case Some(Kind.Gateway(_))  => "gateway"
            case Some(Kind.Service(sv)) => s"service:${sv.project}/${sv.name}"
            case Some(Kind.Local(_))    => "local"
            case _                      => "absent"
          Right(HttpResponse(200, "text/plain", ByteString.copyFromUtf8(seen)))
      )
    ),
    acl = EndpointSpec.Acl.CALLERS,
    allowCallers = Vector(
      CallerMatcher(CallerMatcher.Kind.Internet(Empty())),
      CallerMatcher(CallerMatcher.Kind.Service(NamedService(None, "orders")))
    )
  )

  private var double: ProcessDouble   = scala.compiletime.uninitialized
  private var channel: ManagedChannel = scala.compiletime.uninitialized
  private var kit: AnkkaTestKit       = scala.compiletime.uninitialized
  private var base: String            = scala.compiletime.uninitialized
  private val client                  = HttpClient.newHttpClient()
  private var relayed: (GrpcConversation, Settings, Vector[EndpointSpec]) =
    scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    double = new ProcessDouble(
      ProcessDouble.DoubleSpec(
        entities = Vector(ProcessDouble.recorder()),
        endpoints = Vector(carts, denied, authed, whoami, sockets),
        protocolVersion = "1.9"
      )
    )
    val port = double.start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    val settings =
      Settings(s"127.0.0.1:$port", 0, "127.0.0.1", 5.seconds, 1.second, 2.seconds, 2.seconds)
    val conversation = GrpcConversation(channel, settings)
    val discovered = Discovery
      .validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true)
      .toOption
      .get
    val endpoints = discovered.endpoints.map(e => RemoteEndpoint.from(e, conversation, settings))
    relayed = (conversation, settings, discovered.endpoints)
    val http = HttpServer.at("127.0.0.1", 0)(endpoints.map(e => _ => e)*)
    kit = AnkkaTestKit.start(
      discovered.descriptors,
      Seq(http),
      60.seconds,
      _.withConversation(conversation)
    )
    base = kit.service.boundAddresses.find(_.startsWith("http")).getOrElse(fail("no http address"))

  override def afterAll(): Unit =
    Try(kit.stop())
    channel.shutdownNow()
    double.stop()

  private def get(path: String, headers: (String, String)*): JdkResponse[String] =
    val b = HttpRequest.newBuilder(URI.create(base + path)).GET()
    headers.foreach((k, v) => b.header(k, v))
    client.send(b.build(), JdkResponse.BodyHandlers.ofString())

  private def post(path: String, body: String, headers: (String, String)*): JdkResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(base + path))
      .POST(HttpRequest.BodyPublishers.ofString(body))
    headers.foreach((k, v) => b.header(k, v))
    client.send(b.build(), JdkResponse.BodyHandlers.ofString())

  test("path parameters bind in template order") {
    val r = get("/carts/c1")
    assertEquals(r.statusCode, 200)
    assertEquals(r.body, """{"id":"c1"}""")
  }

  test("a literal segment beats a parameter") {
    assertEquals(get("/carts/awkward").body, """{"literal":true}""")
  }

  test("query parameters, headers and the body cross whole; the status and body come back whole") {
    val r = post(
      "/carts/c1/items?a=1&a=2",
      """{"n":1}""",
      "Content-Type" -> "application/json",
      "X-Test"       -> "y"
    )
    assertEquals(r.statusCode, 201)
    assertEquals(
      r.body,
      """{"id":"c1","q":"a=1,a=2","x":"y","ct":"application/json","body":{"n":1}}"""
    )
  }

  test("a status the handler chose passes through") {
    assertEquals(get("/carts/status/418").statusCode, 418)
  }

  test("a handler fault is a 500 carrying the message") {
    val r = get("/carts/boom")
    assertEquals(r.statusCode, 500)
    assert(r.body.contains("kaboom"), r.body)
  }

  test("the ACL is applied before the process is reached") {
    val before = double.received.size
    assertEquals(get("/private/").statusCode, 403)
    assertEquals(get("/auth/").statusCode, 503)
    assertEquals(double.received.size, before)
  }

  test("SSE frames are JSON-encoded on the wire, so a leading space and a newline survive") {
    val r = get("/carts/c1/events")
    assertEquals(r.statusCode, 200)
    val data =
      r.body.linesIterator.filter(_.startsWith("data:")).map(_.stripPrefix("data:").trim).toVector
    assertEquals(data, Vector("\" leading space\"", "\"two\\nlines\"", "\"end\""))
  }

  test("an unknown route is the router's 404, not the process's") {
    val before = double.received.size
    assertEquals(get("/carts/c1/nothing/here").statusCode, 404)
    assertEquals(double.received.size, before)
  }

  test("with the process stopped a forwarded request is 503, and 200 once it is back") {
    val port = double.port
    double.stop()
    val down = get("/carts/c1")
    assertEquals(down.statusCode, 503, down.body)
    val _ = double.start(port)
    // The channel reconnects on its own; give it a moment.
    val deadline = System.currentTimeMillis() + 10000
    var up       = get("/carts/c1")
    while up.statusCode != 200 && System.currentTimeMillis() < deadline do
      Thread.sleep(200)
      up = get("/carts/c1")
    assertEquals(up.statusCode, 200)
  }

  test("the caller crosses to the process, and a CALLERS acl is applied before it is reached") {
    val gateway = LocalCallers.header(Caller.Gateway)
    assertEquals(get("/whoami/", gateway).body, "gateway")
    val orders = LocalCallers.header(Caller.Service("local", "orders"))
    assertEquals(get("/whoami/", orders).body, "service:local/orders")
    // Named service means this project's, and outside a cluster this service's project is `local`.
    assertEquals(
      get("/whoami/", LocalCallers.header(Caller.Service("billing", "orders"))).statusCode,
      403
    )
    assertEquals(get("/whoami/").body, "local")
  }

  test("discovery refuses a CALLERS acl that names nobody") {
    val empty = double.toSpec.copy(endpoints =
      Seq(
        EndpointSpec("x", "/x", EndpointSpec.Acl.CALLERS, Seq.empty, Seq.empty)
      )
    )
    val problems = Discovery
      .validate(empty, Discovery.ProtocolVersion, authConfigured = true)
      .left
      .toOption
      .getOrElse(fail("accepted"))
    assert(problems.exists(_.contains("must name at least one caller")), problems.toString)
  }

  // ── Sockets, relayed ──────────────────────────────────────────────────────

  private def socketAt(path: String): TestSocket =
    TestSocket
      .open("ws" + base.stripPrefix("http") + path)
      .fold(r => fail(s"not opened: $r"), identity)

  test("a socket is relayed to the process and back, in order") {
    val socket = socketAt("/sockets/echo")
    (1 to 10).foreach(i => socket.send(s"frame $i"))
    assertEquals(
      (1 to 10).map(_ => socket.receive()).toVector,
      (1 to 10).map(i => Some(s"frame $i")).toVector
    )
    socket.close()
    assertEquals(socket.closed().code, 1000)
  }

  test("the process completing closes the socket 1000, and failing closes it 1011") {
    val complete = socketAt("/sockets/complete")
    assertEquals(
      Vector(complete.receive(), complete.receive()),
      Vector(Some("frame 1"), Some("frame 2"))
    )
    assertEquals(complete.closed(), TestSocket.Closed(1000, "finished"))
    val fail = socketAt("/sockets/fail")
    fail.send("go")
    assertEquals(fail.closed(), TestSocket.Closed(1011, "failed"))
  }

  test(
    "a process that never reads leaves the client's frames to the socket's bound: 1008, unread"
  ) {
    val socket = socketAt("/sockets/never")
    val frame  = "x" * 16384 // the HTTP/2 window is counted in bytes
    (1 to 200).foreach(_ => Try(socket.send(frame)))
    assertEquals(socket.closed(10.seconds), TestSocket.Closed(1008, "unread"))
  }

  test(
    "a process that stops while a socket is open closes it 1011; one opened after it is back is served"
  ) {
    val socket = socketAt("/sockets/echo")
    socket.send("hello")
    assertEquals(socket.receive(), Some("hello"))
    val port = double.port
    double.stop()
    assertEquals(socket.closed(10.seconds), TestSocket.Closed(1011, "failed"))
    val _        = double.start(port)
    val deadline = System.currentTimeMillis() + 10000
    var served   = false
    while !served && System.currentTimeMillis() < deadline do
      Try {
        val again = socketAt("/sockets/echo")
        again.send("back")
        served = again.receive(2.seconds).contains("back")
        again.close()
      }
      if !served then Thread.sleep(200)
    assert(served, "a socket opened after the process came back was not served")
  }

  test("a sidecar refuses a frame bound larger than it can relay to its process") {
    val config = com.typesafe.config.ConfigFactory
      .parseString("ankka.http.socket.max-frame-size = 4MiB")
      .withFallback(com.typesafe.config.ConfigFactory.load())
    val problems = RemoteEndpoint.socketProblems(config)
    assert(
      problems.exists(_.contains("larger than a frame a sidecar can relay")),
      problems.toString
    )
    assertEquals(
      RemoteEndpoint.socketProblems(com.typesafe.config.ConfigFactory.load()),
      Vector.empty
    )
  }

  test("a stopping sidecar closes a relayed socket 1001, and tells the process it is going away") {
    // A second server over the same service and process, so stopping it ends no other case's socket.
    val (conversation, settings, specs) = relayed
    val server =
      HttpServer.at("127.0.0.1", 0)(
        specs.map(e =>
          (_: com.thinkmorestupidless.ankka.http.EndpointClients) =>
            RemoteEndpoint.from(e, conversation, settings)
        )*
      )
    server.start(kit.service)
    val socket = TestSocket
      .open(s"ws://127.0.0.1:${server.boundPort.get}/sockets/echo")
      .fold(r => fail(s"not opened: $r"), identity)
    socket.send("hello")
    assertEquals(socket.receive(), Some("hello"))
    val before = double.received.size
    server.stop()
    assertEquals(socket.closed(10.seconds), TestSocket.Closed(1001, "going away"))
    val deadline = System.currentTimeMillis() + 5000
    def told = double.received.asScala
      .drop(before)
      .map(_.message)
      .collect { case in: ankka.protocol.v1.endpoint.SocketIn =>
        in.message.closed.map(_.reason)
      }
      .flatten
    while !told.contains("going away") && System.currentTimeMillis() < deadline do Thread.sleep(50)
    assertEquals(told.toVector, Vector("going away"))
  }
