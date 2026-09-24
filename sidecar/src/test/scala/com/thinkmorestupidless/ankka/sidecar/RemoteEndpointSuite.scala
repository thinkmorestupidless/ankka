package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.Endpoint as EndpointSpec
import ankka.protocol.v1.endpoint.HttpResponse
import com.google.protobuf.ByteString
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse as JdkResponse}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Try

/**
 * Declared routes served by the sidecar's own HTTP server, forwarded to the process double:
 * binding, specificity, query and headers, status passthrough, faults, the ACL applied before the
 * process is reached, SSE frames JSON-encoded, and a stopped process answering 503.
 */
class RemoteEndpointSuite extends munit.FunSuite:

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

  private var double: ProcessDouble   = scala.compiletime.uninitialized
  private var channel: ManagedChannel = scala.compiletime.uninitialized
  private var kit: AnkkaTestKit       = scala.compiletime.uninitialized
  private var base: String            = scala.compiletime.uninitialized
  private val client                  = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    double = new ProcessDouble(
      ProcessDouble.DoubleSpec(
        entities = Vector(ProcessDouble.recorder()),
        endpoints = Vector(carts, denied, authed)
      )
    )
    val port = double.start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    val settings =
      Settings(s"127.0.0.1:$port", 0, "127.0.0.1", 5.seconds, 1.second, 2.seconds, 2.seconds)
    val conversation = GrpcConversation(channel, settings)
    val discovered   = Discovery.validate(double.toSpec).toOption.get
    val endpoints    = discovered.endpoints.map(e => RemoteEndpoint.from(e, conversation, settings))
    val http         = HttpServer.at("127.0.0.1", 0)(endpoints.map(e => _ => e)*)
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
