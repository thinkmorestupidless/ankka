package com.thinkmorestupidless.ankka.testkit

import java.net.URI
import java.net.http.{HttpClient, WebSocket, WebSocketHandshakeException}
import java.util.concurrent.{CompletableFuture, CompletionException, CompletionStage}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import javax.net.ssl.SSLContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A socket a test opens to a socket route: blocking, over the JDK's own client.
 *
 * Its one rule is that a socket ended without a close frame — cut off — can never read as closed:
 * `closed` fails for it. A test that asked only whether the socket was gone would pass for a server
 * that dropped every connection.
 */
final class TestSocket private (ws: WebSocket, listener: TestSocket.Listener):

  /** The subprotocol the server selected, if any. */
  def subprotocol: Option[String] = Option(ws.getSubprotocol).filter(_.nonEmpty)

  def send(text: String): Unit = ws.sendText(text, true).join(): Unit

  /** Sends a binary frame, which no socket route accepts. */
  def sendBinary(bytes: Array[Byte]): Unit =
    ws.sendBinary(java.nio.ByteBuffer.wrap(bytes), true).join(): Unit

  /**
   * The next frame. `None` once the socket has ended, closed or cut off; fails when nothing arrives
   * within `within`.
   */
  def receive(within: FiniteDuration = 5.seconds): Option[String] =
    listener.next(within) match
      case Some(TestSocket.Event.Frame(text)) => Some(text)
      case Some(ended)                        => listener.ended = Some(ended); None
      case None if listener.ended.isDefined   => None
      case None => throw AssertionError(s"no frame and no close within $within")

  /** Asserts that nothing arrives within `within`: the socket is still open and quiet. */
  def quietFor(within: FiniteDuration): Unit =
    listener.next(within).foreach(event => throw AssertionError(s"expected quiet, got $event"))

  /** Closes this side with 1000, as a well-behaved client does. */
  def close(): Unit = ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join(): Unit

  /** Ends the connection with no close frame, as a client that vanished does. */
  def abort(): Unit = ws.abort()

  /**
   * How the socket was closed, skipping any frames still arriving. Fails when it was cut off, or
   * when it has not ended within `within`.
   */
  def closed(within: FiniteDuration = 5.seconds): TestSocket.Closed =
    val deadline = within.fromNow
    def ended: TestSocket.Event =
      listener.ended.getOrElse {
        listener.next(deadline.timeLeft.max(Duration.Zero)) match
          case Some(TestSocket.Event.Frame(_)) => ended
          case Some(event)                     => listener.ended = Some(event); event
          case None => throw AssertionError(s"the socket did not end within $within")
      }
    ended match
      case TestSocket.Event.Closed(code, reason) => TestSocket.Closed(code, reason)
      case other => throw AssertionError(s"the socket was cut off, not closed: $other")

object TestSocket:

  final case class Closed(code: Int, reason: String)

  /** The opening request was answered with something other than 101. */
  final case class Refused(status: Int, headers: Map[String, String])

  private enum Event:
    case Frame(text: String)
    case Closed(code: Int, reason: String)
    case CutOff(error: String)

  private final class Listener extends WebSocket.Listener:
    private val events                       = LinkedBlockingQueue[Event]()
    private val buffer                       = StringBuilder()
    @volatile var ended: Option[Event]       = None
    override def onOpen(ws: WebSocket): Unit = ws.request(Long.MaxValue)
    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      buffer.append(data)
      if last then
        events.put(Event.Frame(buffer.result()))
        buffer.clear()
      CompletableFuture.completedFuture(null)
    override def onClose(ws: WebSocket, code: Int, reason: String): CompletionStage[?] =
      events.put(Event.Closed(code, reason))
      CompletableFuture.completedFuture(null)
    override def onError(ws: WebSocket, error: Throwable): Unit =
      events.put(Event.CutOff(s"${error.getClass.getSimpleName}: ${error.getMessage}"))
    def next(within: FiniteDuration): Option[Event] =
      Option(events.poll(within.toMillis, TimeUnit.MILLISECONDS))

  private lazy val shared = HttpClient.newHttpClient()

  /**
   * Opens a socket at `url` (`ws://` or `wss://`). `client` is shared by default, so a test that
   * opens many sockets measures the server's threads and not a client per socket.
   */
  def open(
      url: String,
      headers: Map[String, String] = Map.empty,
      subprotocols: Seq[String] = Nil,
      tls: Option[SSLContext] = None,
      client: Option[HttpClient] = None
  ): Either[Refused, TestSocket] =
    val chosen = client.getOrElse(
      tls.fold(shared)(context => HttpClient.newBuilder().sslContext(context).build())
    )
    val listener = Listener()
    val builder  = chosen.newWebSocketBuilder()
    headers.foreach((name, value) => builder.header(name, value))
    subprotocols.toList match
      case first :: rest => builder.subprotocols(first, rest*)
      case Nil           => ()
    try Right(TestSocket(builder.buildAsync(URI.create(url), listener).join(), listener))
    catch
      case e: CompletionException =>
        e.getCause match
          case refused: WebSocketHandshakeException =>
            val response = refused.getResponse
            Left(
              Refused(
                response.statusCode,
                response.headers.map.asScala.view.mapValues(_.asScala.mkString(",")).toMap
              )
            )
          case other => throw other
