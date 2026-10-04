package com.thinkmorestupidless.ankka.http

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.runtime.HttpServiceClients
import com.thinkmorestupidless.ankka.sdk.{
  ServiceCallFailed,
  ServiceIdentityMismatch,
  ServiceUnanswered,
  ServiceUnresolvable
}
import com.sun.net.httpserver.HttpServer as JdkHttpServer
import com.thinkmorestupidless.ankka.testpki.TestPki
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.net.{ConnectException, InetAddress, InetSocketAddress, ServerSocket}
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * One service calling another as itself (feature 014): the caller presents its certificate, the
 * callee reads it as the caller, and a callee that is not who was asked for is refused before
 * anything is sent.
 */
class ServiceClientSuite extends munit.FunSuite:

  final case class Echo(caller: String)
  given JsonValueCodec[Echo] = JsonCodecMaker.make

  private val authority = TestPki.root("service-client-suite")
  private val served    = AtomicInteger()

  private final class Callee extends HttpEndpoint("/echo"):
    val acl: Acl = Acl.allowCallers(Callers.service("checkout", "orders"))
    get("/whoami")(() =>
      served.incrementAndGet()
      Echo(Caller.encode(caller))
    )

  /** A TLS server presenting `uri`, on loopback. */
  private def serving(uri: String): (ActorSystem[Nothing], HttpServer, Int) =
    val dir = authority
      .issue(uris = Seq(uri), dnsNames = Seq("localhost"))
      .writeTo(Files.createTempDirectory("callee"))
    given system: ActorSystem[Nothing] = ActorSystem(
      Behaviors.empty,
      "callee",
      ConfigFactory
        .parseString(s"""
          |ankka.http.tls.enabled = on
          |ankka.tls.service-directory = "$dir"
          |""".stripMargin)
        .withFallback(ConfigFactory.load())
    )
    val server = HttpServer.at("127.0.0.1", 0)()
    server.serve(Vector(new Callee), "127.0.0.1", 0, 5.seconds)
    (system, server, server.boundPort.get)

  private def stop(system: ActorSystem[?], server: HttpServer): Unit =
    server.stop()
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  /** The caller's side: its own certificate, and the callee found at `port` on loopback. */
  private def clientsAs(uri: String, port: Int): HttpServiceClients =
    val dir = authority.issue(uris = Seq(uri)).writeTo(Files.createTempDirectory("caller"))
    HttpServiceClients(
      ConfigFactory
        .parseString(s"""ankka.tls.service-directory = "$dir"""")
        .withFallback(ConfigFactory.load()),
      None,
      (_, _) => Some("localhost" -> port)
    )

  test("a call presents the caller's certificate, and the callee reads it as the caller") {
    val (system, server, port) = serving("ankka://checkout/carts")
    try
      val echo = clientsAs("ankka://checkout/orders", port)("carts").get[Echo]("/echo/whoami")
      assertEquals(echo, Echo("service:checkout/orders"))
    finally stop(system, server)
  }

  test("a caller the callee's ACL does not admit is answered 403, as a ServiceCallFailed") {
    val (system, server, port) = serving("ankka://checkout/carts")
    try
      val failed = intercept[ServiceCallFailed](
        clientsAs("ankka://checkout/payments", port)("carts").get[Echo]("/echo/whoami")
      )
      assertEquals(failed.status, 403)
    finally stop(system, server)
  }

  test("a callee holding another service's certificate is refused before anything is sent") {
    val (system, server, port) = serving("ankka://checkout/impostor")
    try
      val before = served.get
      intercept[ServiceIdentityMismatch](
        clientsAs("ankka://checkout/orders", port)("carts").get[Echo]("/echo/whoami")
      )
      assertEquals(served.get, before, "the impostor received the request")
    finally stop(system, server)
  }

  test("a name that resolves to nothing says what was tried") {
    val dir = authority
      .issue(uris = Seq("ankka://checkout/orders"))
      .writeTo(Files.createTempDirectory("caller"))
    val clients = HttpServiceClients(
      ConfigFactory
        .parseString(s"""ankka.tls.service-directory = "$dir"""")
        .withFallback(ConfigFactory.load()),
      None,
      (_, _) => None
    )
    val e = intercept[ServiceUnresolvable](clients("nowhere").getText("/"))
    assert(e.reason.contains("_http._tcp.nowhere."), e.reason)
  }

  test("outside a cluster a service is reached by its configured local address, over plain HTTP") {
    given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "local-callee")
    val server                         = HttpServer.at("127.0.0.1", 0)()
    server.serve(Vector(new Callee), "127.0.0.1", 0, 5.seconds)
    try
      val clients = HttpServiceClients(
        ConfigFactory
          .parseString(
            s"""ankka.local-services.carts = "http://127.0.0.1:${server.boundPort.get}""""
          )
          .withFallback(ConfigFactory.load()),
        None
      )
      // Locally every caller is Local, which a caller-naming ACL admits.
      assertEquals(clients("carts").get[Echo]("/echo/whoami"), Echo("local"))
    finally stop(system, server)
  }

  /** Plain local clients reaching `name` at `address`, with `extra` configuration on top. */
  private def localClients(name: String, address: String, extra: String = ""): HttpServiceClients =
    HttpServiceClients(
      ConfigFactory
        .parseString(s"""ankka.local-services."$name" = "$address"\n$extra""")
        .withFallback(ConfigFactory.load()),
      None
    )

  /** A listener on loopback that accepts each connection and closes it at once, counting them. */
  private final class Hangup extends AutoCloseable:
    private val socket  = ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    val connections     = AtomicInteger()
    val address: String = s"http://127.0.0.1:${socket.getLocalPort}"
    private val acceptor = Thread.ofVirtual().start { () =>
      try
        while !socket.isClosed do
          val s = socket.accept()
          connections.incrementAndGet()
          s.close()
      catch case _: java.io.IOException => ()
    }
    def close(): Unit = socket.close()

  // The JDK's client sends a GET or a HEAD once more when its connection closes before any part of
  // an answer arrives, and no other method again; there is no setting that changes it. Pinned here
  // so a JDK that behaves otherwise is noticed: a request that may change something is sent once.
  test("a request that may change something is sent at most once; a GET or a HEAD at most twice") {
    for (method, expected) <- Seq(
        "GET"    -> 2,
        "HEAD"   -> 2,
        "POST"   -> 1,
        "PUT"    -> 1,
        "DELETE" -> 1,
        "PATCH"  -> 1
      )
    do
      val hangup = Hangup()
      try
        val clients = localClients("flaky", hangup.address)
        intercept[Exception](
          clients("flaky")
            .request(method, "/x", Option.when(Set("POST", "PUT", "PATCH")(method))(Array[Byte](1)))
        )
        Thread.sleep(200)
        assertEquals(hangup.connections.get, expected, s"connections opened for a $method")
      finally hangup.close()
  }

  /** A plain HTTP stand-in on loopback whose answer is `answer` of the request's headers. */
  private def standIn(
      answer: Map[String, Seq[String]] => String,
      delay: FiniteDuration = Duration.Zero
  ): JdkHttpServer =
    val server = JdkHttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext(
      "/",
      exchange =>
        Thread.sleep(delay.toMillis)
        val headers = exchange.getRequestHeaders.entrySet.toArray
          .map(_.asInstanceOf[java.util.Map.Entry[String, java.util.List[String]]])
          .map(e =>
            e.getKey.toLowerCase -> scala.jdk.CollectionConverters
              .ListHasAsScala(e.getValue)
              .asScala
              .toSeq
          )
          .toMap
        val bytes = answer(headers).getBytes("UTF-8")
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
    server.start()
    server

  private def addressOf(server: JdkHttpServer): String =
    s"http://127.0.0.1:${server.getAddress.getPort}"

  test("a service nothing listens for is unanswered, not unresolvable") {
    val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    val port   = closed.getLocalPort
    closed.close()
    val e = intercept[ServiceUnanswered](
      localClients("gone", s"http://127.0.0.1:$port")("gone").getText("/")
    )
    assert(e.service.endsWith("/gone"), e.service)
    assert(e.getCause.isInstanceOf[ConnectException], String.valueOf(e.getCause))
  }

  test("a call that is not answered within the time its service is set to wait is unanswered") {
    val slow = standIn(_ => "late", delay = 2.seconds)
    try
      val started = System.nanoTime()
      val e = intercept[ServiceUnanswered](
        localClients("slow", addressOf(slow), "ankka.service-client.timeout = 500ms")("slow")
          .getText("/")
      )
      assert((System.nanoTime() - started).nanos < 2.seconds, "waited for the answer")
      assert(e.reason.contains("500"), e.reason)
    finally slow.stop(0)
  }

  test("a service waits thirty seconds for an answer unless it is set otherwise") {
    assertEquals(
      ConfigFactory.load().getDuration("ankka.service-client.timeout"),
      java.time.Duration.ofSeconds(30)
    )
  }

  test("none of the platform's headers reaches the service called, whoever set them") {
    val echo = standIn(headers => headers.keys.toVector.sorted.mkString(","))
    try
      val received = localClients("echo", addressOf(echo))("echo")
        .request(
          "GET",
          "/",
          headers = Seq(
            "X-Ankka-Caller"       -> "ankka://payments/orders",
            "x-ankka-local-caller" -> "token service:payments/orders",
            "Host"                 -> "elsewhere",
            "Content-Length"       -> "7",
            "Expect"               -> "100-continue",
            "Forwarded"            -> "for=1.2.3.4",
            "X-Forwarded-For"      -> "1.2.3.4",
            "Connection"           -> "close",
            "Keep-Alive"           -> "timeout=5",
            "TE"                   -> "trailers",
            "X-Request-Id"         -> "r1"
          )
        )
        .text
        .split(',')
        .toSet
      assert(received.contains("x-request-id"), received.toString)
      for name <- Seq(
          "x-ankka-caller",
          "x-ankka-local-caller",
          "expect",
          "forwarded",
          "x-forwarded-for",
          "keep-alive",
          "te"
        )
      do assert(!received.contains(name), s"$name arrived: $received")
    finally echo.stop(0)
  }

  test(
    "the content type is the one given, and a Content-Type among the headers does not add a second"
  ) {
    val echo = standIn(headers => headers.getOrElse("content-type", Nil).mkString("|"))
    try
      val received = localClients("echo", addressOf(echo))("echo")
        .request(
          "POST",
          "/",
          Some("{}".getBytes),
          Some("application/json"),
          Seq("Content-Type" -> "text/plain")
        )
        .text
      assertEquals(received, "application/json")
    finally echo.stop(0)
  }
