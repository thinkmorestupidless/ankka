package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.net.URI
import java.net.http.{HttpClient, WebSocket, WebSocketHandshakeException}
import java.nio.file.Files
import java.util.concurrent.{CompletableFuture, CompletionException, CompletionStage}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * Who opened a socket, over the platform's mutual TLS on loopback: the caller is read from the
 * client's certificate at the opening request, as for any route, and an ACL naming services is
 * decided there. The client is the JDK's own; `TestSocket` is the test kit's, which this module
 * cannot see.
 */
class SocketCallerSuite extends munit.FunSuite:

  private val authority = TestPki.root("socket-caller-suite")
  private val serverDir =
    authority
      .issue(uris = Seq("ankka://checkout/notices"), dnsNames = Seq("localhost"))
      .writeTo(Files.createTempDirectory("socket-caller"))

  private val runs = AtomicInteger()

  /** A stand-in for a verifier: the token is the subject, as the real one would establish it. */
  private def decide(context: RequestContext): AuthDecision =
    context.header("Authorization").map(_.stripPrefix("Bearer ")) match
      case Some(subject) => AuthDecision.Allow(Principal(subject))
      case None          => AuthDecision.Unauthenticated("""realm="sockets"""")

  private final class Notices extends HttpEndpoint("/notices"):
    val acl: Acl = Acl.allowCallers(Callers.service("checkout"))
    socket("/stream") { socket =>
      runs.incrementAndGet(): Unit
      socket.send(Caller.encode(caller))
      socket.receive(): Unit
    }
    withAcl(Acl.Authenticate(decide)) {
      socket("/account") { socket =>
        socket.send(s"${principal.subject} via ${Caller.encode(caller)}")
        socket.receive(): Unit
      }
    }

  private val running = TlsServing.start("socket-caller-suite", serverDir, Vector(Notices()))

  override def afterAll(): Unit = running.stop()

  private final class Listener extends WebSocket.Listener:
    val frames                               = LinkedBlockingQueue[String]()
    override def onOpen(ws: WebSocket): Unit = ws.request(Long.MaxValue)
    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      frames.put(data.toString)
      CompletableFuture.completedFuture(null)

  /**
   * Opens `path` as the workload `uri` names: the frame it is sent first, or the refusal status.
   */
  private def openAs(uri: String, path: String, protocols: Seq[String] = Nil): Either[Int, String] =
    val leaf = authority.issue(uris = Seq(uri)).writeTo(Files.createTempDirectory("socket-client"))
    val client = HttpClient.newBuilder().sslContext(RotatingTls(leaf, 1.minute).sslContext).build()
    val listener = Listener()
    val builder  = client.newWebSocketBuilder()
    protocols.toList match
      case first :: rest => builder.subprotocols(first, rest*)
      case Nil           => ()
    try
      val ws = builder.buildAsync(URI(s"wss://localhost:${running.port}$path"), listener).join()
      try Right(Option(listener.frames.poll(5, TimeUnit.SECONDS)).getOrElse(fail("no frame")))
      finally ws.abort()
    catch
      case e: CompletionException =>
        e.getCause match
          case refused: WebSocketHandshakeException => Left(refused.getResponse.statusCode)
          case other                                => throw other

  test("a socket route that admits a named service tells its handler the calling workload") {
    assertEquals(
      openAs("ankka://checkout/checkout", "/notices/stream"),
      Right("service:checkout/checkout")
    )
  }

  test(
    "a socket route that admits a named service refuses any other service, and no handler runs"
  ) {
    val before = runs.get
    assertEquals(openAs("ankka://checkout/billing", "/notices/stream"), Left(403))
    assertEquals(runs.get, before)
  }

  test("a browser's token opens an authenticated socket route at an exposed service's hostname") {
    // Through the gateway: its certificate, and the token offered as a subprotocol, since a
    // browser cannot set a header on a socket.
    val opened = openAs(
      RotatingTls.GatewayUri,
      "/notices/account",
      Seq("ankka.socket", "ankka.bearer.ada")
    )
    assertEquals(opened, Right("ada via gateway"))
  }
