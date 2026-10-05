package com.thinkmorestupidless.ankka.testkit.sockets

import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.{Observability, SpanOutcome}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing, TestSocket}
import com.typesafe.config.ConfigFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

/**
 * A socket route served by a whole service on loopback: what the opening request is answered, where
 * a token may arrive, how each way of ending a socket is told to its client — by code, never only
 * "closed" — and the limits a socket is held to.
 *
 * The limits are made small and the server's idle timeout short, through system properties read
 * when the service starts, so a case about either takes seconds. They are cleared afterwards.
 */
class SocketSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val Settings = Map(
    "ankka.http.socket.max-frame-size" -> "1KiB",
    "ankka.http.socket.unread-frames"  -> "16",
    "ankka.http.socket.keep-alive"     -> "1s",
    "pekko.http.server.idle-timeout"   -> "3s"
  )

  /** How many times a handler started, per route: "no handler ran" is a count of zero. */
  private val runs = new java.util.concurrent.ConcurrentHashMap[String, AtomicInteger]()
  private def ran(route: String): Int = Option(runs.get(route)).fold(0)(_.get)
  private def started(route: String): Unit =
    runs.computeIfAbsent(route, _ => AtomicInteger()).incrementAndGet(): Unit

  /** What a handler saw, for a case to read once its socket has closed. */
  private val seen    = ConcurrentLinkedQueue[String]()
  private val release = CountDownLatch(1)

  private def decide(context: RequestContext): AuthDecision =
    context.header("Authorization").map(_.stripPrefix("Bearer ")) match
      case None                => AuthDecision.Unauthenticated("""realm="sockets"""")
      case Some("forbidden")   => AuthDecision.Forbidden("not this one")
      case Some("unavailable") => AuthDecision.Unavailable("keys cannot be fetched")
      case Some(subject)       => AuthDecision.Allow(Principal(subject))

  private final class Sockets extends HttpEndpoint("/s"):
    val acl: Acl = Acl.AllowAll

    socket("/echo") { socket =>
      started("echo")
      Iterator.continually(socket.receive()).takeWhile(_.isDefined).flatten.foreach(socket.send)
      seen.add("echo: told closed"): Unit
    }
    socket("/once") { socket =>
      started("once")
      socket.receive(): Unit
    }
    socket("/fail") { socket =>
      started("fail")
      socket.receive(): Unit
      throw IllegalStateException("the handler broke")
    }
    socket("/never") { _ =>
      started("never")
      release.await(60, TimeUnit.SECONDS): Unit
    }
    socket("/after-close") { socket =>
      started("after-close")
      while socket.receive().isDefined do ()
      try
        socket.send("too late")
        seen.add("after-close: sent"): Unit
      catch case _: SocketClosed => seen.add("after-close: SocketClosed"): Unit
    }
    socket("/escape") { socket =>
      started("escape")
      while socket.receive().isDefined do ()
      socket.send("too late") // throws SocketClosed, which ends the handler as a return does
    }
    socket("/headers") { socket =>
      started("headers")
      socket.send(request.header("Authorization").getOrElse("none"))
      socket.send(request.header("Sec-WebSocket-Protocol").getOrElse("none"))
      socket.receive(): Unit
    }

    withAcl(Acl.Authenticate(decide)) {
      socket("/me") { socket =>
        started("me")
        socket.send(principal.subject)
        socket.receive(): Unit
      }
      get("/plain")(() => principal.subject)
    }

  private var testKit: AnkkaTestKit = null
  private var server: HttpServer    = null
  private var base: String          = ""

  override def beforeAll(): Unit =
    Settings.foreach((key, value) => System.setProperty(key, value): Unit)
    ConfigFactory.invalidateCaches()
    server = HttpServer.at("127.0.0.1", 0)(_ => Sockets())
    testKit = AnkkaTestKit.start(Nil, Seq(server))
    base = s"127.0.0.1:${server.boundPort.getOrElse(fail("not bound"))}"

  override def afterAll(): Unit =
    release.countDown()
    if testKit != null then testKit.stop()
    Settings.keys.foreach(System.clearProperty)
    ConfigFactory.invalidateCaches()

  private def open(
      path: String,
      headers: Map[String, String] = Map.empty,
      protocols: Seq[String] = Nil
  ): Either[TestSocket.Refused, TestSocket] =
    TestSocket.open(s"ws://$base$path", headers, protocols)

  private def opened(path: String, protocols: Seq[String] = Nil): TestSocket =
    open(path, protocols = protocols).fold(r => fail(s"refused: $r"), identity)

  private val http = HttpClient.newHttpClient()

  /** Retries `assertion` until it holds, for what a handler does after its socket has closed. */
  private def eventually(description: String, within: FiniteDuration = 10.seconds)(
      assertion: => Unit
  ): Unit =
    val deadline        = within.fromNow
    var last: Throwable = null
    while
      try
        assertion
        last = null
      catch case failure: AssertionError => last = failure
      last != null && deadline.hasTimeLeft()
    do Thread.sleep(50)
    if last != null then throw AssertionError(s"$description: ${last.getMessage}", last)

  private def plainGet(path: String, headers: (String, String)*): HttpResponse[String] =
    val builder = HttpRequest.newBuilder(URI.create(s"http://$base$path")).GET()
    headers.foreach((k, v) => builder.header(k, v))
    http.send(builder.build(), HttpResponse.BodyHandlers.ofString())

  private def spans(handler: String) =
    val observability = Observability(testKit.service.system)
    observability.recorder
      .snapshot()
      .filter(s => observability.names.nameOf(s.handlerRef).contains(handler))

  // --- The opening request

  test(
    "an opening request with no token is challenged as a request to the same ACL is, and no handler runs"
  ) {
    val refused = open("/s/me").left.getOrElse(fail("opened without a token"))
    val plain   = plainGet("/s/plain")
    assertEquals(refused.status, 401)
    assertEquals(plain.statusCode, 401)
    assertEquals(
      refused.headers.find(_._1.equalsIgnoreCase("www-authenticate")).map(_._2),
      plain.headers.firstValue("www-authenticate").toScala
    )
    assertEquals(ran("me"), 0)
  }

  test(
    "an opening request the ACL forbids is 403, and one it cannot decide is 503 with Retry-After"
  ) {
    val forbidden = open("/s/me", Map("Authorization" -> "Bearer forbidden")).left.toOption.get
    assertEquals(forbidden.status, 403)
    val unavailable = open("/s/me", Map("Authorization" -> "Bearer unavailable")).left.toOption.get
    assertEquals(unavailable.status, 503)
    assert(unavailable.headers.keys.exists(_.equalsIgnoreCase("retry-after")), unavailable.toString)
    assertEquals(ran("me"), 0)
  }

  test(
    "a plain GET to a socket route is 426 naming the upgrade; one the ACL refuses is refused first"
  ) {
    val plain = plainGet("/s/echo")
    assertEquals(plain.statusCode, 426)
    assertEquals(plain.headers.firstValue("upgrade").toScala, Some("websocket"))
    assertEquals(plainGet("/s/me").statusCode, 401, "the ACL is decided before anything else")
    assertEquals(ran("echo"), 0)
  }

  test("the 101 selects ankka.socket when it is offered, and nothing otherwise") {
    val offered = opened("/s/echo", Seq("ankka.socket"))
    assertEquals(offered.subprotocol, Some("ankka.socket"))
    offered.close()
    val bare = opened("/s/echo")
    assertEquals(bare.subprotocol, None)
    bare.close()
  }

  test(
    "a token is read from the header, from an offered subprotocol, and the header wins over it"
  ) {
    val fromHeader = open("/s/me", Map("Authorization" -> "Bearer ada")).toOption.get
    assertEquals(fromHeader.receive(), Some("ada"))
    fromHeader.close()
    val fromProtocol =
      open("/s/me", protocols = Seq("ankka.socket", "ankka.bearer.grace")).toOption.get
    assertEquals(fromProtocol.receive(), Some("grace"))
    assertEquals(fromProtocol.subprotocol, Some("ankka.socket"), "never the bearer subprotocol")
    fromProtocol.close()
    val both = open(
      "/s/me",
      Map("Authorization" -> "Bearer ada"),
      Seq("ankka.socket", "ankka.bearer.grace")
    ).toOption.get
    assertEquals(both.receive(), Some("ada"))
    both.close()
  }

  test("a handler sees the token as Authorization and never sees the offered subprotocols") {
    val socket = opened("/s/headers", Seq("ankka.socket", "ankka.bearer.grace"))
    assertEquals(socket.receive(), Some("Bearer grace"))
    assertEquals(socket.receive(), Some("none"))
    socket.close()
  }

  // --- How a socket ends, by code

  test("frames cross both ways, and a handler is told when the client closes") {
    val socket = opened("/s/echo")
    (1 to 10).foreach(i => socket.send(s"frame $i"))
    assertEquals(
      (1 to 10).map(_ => socket.receive()).toVector,
      (1 to 10).map(i => Some(s"frame $i")).toVector
    )
    socket.close()
    assertEquals(socket.closed().code, 1000)
    eventually("the handler is told")(assert(seen.contains("echo: told closed")))
  }

  test("a handler that returns closes its socket 1000, finished") {
    val socket = opened("/s/once")
    socket.send("go")
    assertEquals(socket.closed(), TestSocket.Closed(1000, "finished"))
  }

  test("a handler that throws closes its socket 1011, failed, and its span is recorded failed") {
    val socket = opened("/s/fail")
    socket.send("go")
    assertEquals(socket.closed(), TestSocket.Closed(1011, "failed"))
    eventually("the span is recorded") {
      assertEquals(spans("SOCKET /fail").map(_.outcome), Vector(SpanOutcome.Failed))
    }
  }

  test("a frame larger than a frame may be closes the socket 1009, too large") {
    val socket = opened("/s/echo")
    socket.send("x" * 1025)
    assertEquals(socket.closed(), TestSocket.Closed(1009, "too large"))
  }

  test("the bound is bytes: a two-byte character repeated past it is too large") {
    val socket = opened("/s/echo")
    socket.send("é" * 513) // 1026 bytes, 513 characters
    assertEquals(socket.closed().code, 1009)
  }

  test("a frame exactly at the bound crosses") {
    val socket = opened("/s/echo")
    socket.send("x" * 1024)
    assertEquals(socket.receive().map(_.length), Some(1024))
    socket.close()
  }

  test("frames waiting beyond the unread bound close the socket 1008, unread") {
    val socket = opened("/s/never")
    (1 to 20).foreach(i => socket.send(s"frame $i"))
    assertEquals(socket.closed(), TestSocket.Closed(1008, "unread"))
  }

  test("a frame that is not text closes the socket 1003, not text") {
    val socket = opened("/s/echo")
    socket.sendBinary(Array[Byte](1, 2, 3))
    assertEquals(socket.closed(), TestSocket.Closed(1003, "not text"))
  }

  test("a send after the client has closed throws SocketClosed") {
    val socket = opened("/s/after-close")
    socket.close()
    socket.closed(): Unit
    eventually("the handler tried")(
      assert(seen.contains("after-close: SocketClosed"), seen.toString)
    )
  }

  test("a handler ended by SocketClosed is recorded as ok, not failed") {
    val socket = opened("/s/escape")
    socket.close()
    socket.closed(): Unit
    eventually("the span is recorded") {
      assertEquals(spans("SOCKET /escape").map(_.outcome), Vector(SpanOutcome.Ok))
    }
  }

  test("a client that vanishes without a close frame ends its handler, and its span is recorded") {
    val before = spans("SOCKET /echo").size
    val socket = opened("/s/echo")
    socket.send("hello")
    assertEquals(socket.receive(), Some("hello"))
    socket.abort()
    eventually("the handler ended") {
      assert(spans("SOCKET /echo").size > before, "no span recorded for the vanished client")
    }
  }

  // --- The keep-alive

  test(
    "with the keep-alive, a quiet socket outlives the server's idle timeout and then carries a frame"
  ) {
    val socket = opened("/s/echo")
    socket.quietFor(5.seconds) // past the 3-second idle timeout
    socket.send("still here")
    assertEquals(socket.receive(), Some("still here"))
    socket.close()
  }
