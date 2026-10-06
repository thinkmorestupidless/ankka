package com.thinkmorestupidless.ankka.http

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.impl.engine.ws.AnkkaSocketUpgrade
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.settings.ServerSettings
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.{CompletableFuture, CompletionStage, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * Whether a client can be told each close code, and whether the keep-alive outlives pekko-http's
 * own idle timeout (feature 028, research V1, V2 and V4). Runs only with `-Dankka.spikes=on`.
 */
class SocketCloseCodeSpike extends munit.FunSuite:

  override val munitTimeout: Duration = 5.minutes

  override def munitIgnore: Boolean = !sys.props.get("ankka.spikes").contains("on")

  private given system: ActorSystem[Nothing] = ActorSystem(
    Behaviors.empty,
    "socket-spike",
    com.typesafe.config.ConfigFactory
      .parseString("pekko.actor.provider = local")
      .withFallback(com.typesafe.config.ConfigFactory.load())
  )

  private val log = org.apache.pekko.event.Logging(system.classicSystem, "socket-spike")

  private def bind(keepAlive: Duration, idle: FiniteDuration): Http.ServerBinding =
    val base     = ServerSettings(system)
    val settings = base.withTimeouts(base.timeouts.withIdleTimeout(idle))
    val sockets  = base.websocketSettings
    val handler: HttpRequest => HttpResponse = request =>
      AnkkaSocketUpgrade.of(request) match
        case None => HttpResponse(StatusCodes.UpgradeRequired)
        case Some(upgrade) =>
          assert(AnkkaSocketUpgrade.choosesCloseCodes(upgrade), upgrade.getClass.getName)
          val path                                    = request.uri.path.toString
          val code                                    = path.stripPrefix("/").toIntOption
          @volatile var chosen: Option[(Int, String)] = None
          val messages: Flow[Message, Message, Any] = code match
            // Closes as soon as it opens, having chosen the code the path names.
            case Some(c) =>
              Flow.fromSinkAndSourceCoupled(
                Sink.ignore,
                Source
                  .lazySingle { () =>
                    chosen = Some(c -> s"code $c"); ()
                  }
                  .flatMapConcat(_ => Source.empty[Message])
              )
            // Echoes, and never closes of its own accord.
            case None => Flow[Message].collect { case t: TextMessage.Strict => t }
          AnkkaSocketUpgrade.respond(
            upgrade,
            messages,
            request.headers
              .collectFirst {
                case h if h.lowercaseName == "sec-websocket-protocol" => h.value
              }
              .flatMap(_.split(",").map(_.trim).find(_ == "ankka.socket")),
            () => chosen,
            sockets.withPeriodicKeepAliveMaxIdle(keepAlive),
            log
          )
    Await.result(
      Http().newServerAt("127.0.0.1", 0).withSettings(settings).bindSync(handler),
      10.seconds
    )

  final class Listener extends WebSocket.Listener:
    val events                               = LinkedBlockingQueue[String]()
    private val buffer                       = StringBuilder()
    override def onOpen(ws: WebSocket): Unit = ws.request(Long.MaxValue)
    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      buffer.append(data)
      if last then
        events.put(s"frame ${buffer.result()}")
        buffer.clear()
      CompletableFuture.completedFuture(null)
    override def onClose(ws: WebSocket, code: Int, reason: String): CompletionStage[?] =
      events.put(s"closed $code $reason")
      CompletableFuture.completedFuture(null)
    override def onError(ws: WebSocket, error: Throwable): Unit =
      events.put(s"error ${error.getClass.getSimpleName}")
    def next(within: FiniteDuration): Option[String] =
      Option(events.poll(within.toMillis, TimeUnit.MILLISECONDS))

  private val client = HttpClient.newHttpClient()

  private def open(port: Int, path: String, subprotocols: String*): (WebSocket, Listener) =
    val listener = Listener()
    val builder  = client.newWebSocketBuilder()
    subprotocols.toList match
      case first :: rest => builder.subprotocols(first, rest*)
      case Nil           => ()
    val ws = builder.buildAsync(URI.create(s"ws://127.0.0.1:$port$path"), listener).join()
    (ws, listener)

  override def afterAll(): Unit =
    Await.ready(Http().shutdownAllConnectionPools(), 10.seconds)
    system.terminate()
    Await.ready(system.whenTerminated, 30.seconds): Unit

  test("V1: a client is told each close code ankka chose") {
    val binding = bind(Duration.Inf, 60.seconds)
    val port    = binding.localAddress.getPort
    for code <- Seq(1000, 1001, 1003, 1008, 1009, 1011) do
      val (_, listener) = open(port, s"/$code")
      assertEquals(listener.next(5.seconds), Some(s"closed $code code $code"), s"code $code")
    Await.ready(binding.terminate(1.second), 5.seconds)
  }

  test("V1 control: an echo whose client closes is answered with the client's code") {
    val binding        = bind(Duration.Inf, 60.seconds)
    val (ws, listener) = open(binding.localAddress.getPort, "/echo")
    ws.sendText("hello", true).join()
    assertEquals(listener.next(5.seconds), Some("frame hello"))
    ws.sendClose(1000, "bye").join()
    assertEquals(listener.next(5.seconds).map(_.take(11)), Some("closed 1000"))
    Await.ready(binding.terminate(1.second), 5.seconds)
  }

  test(
    "V2: with a keep-alive, a silent socket outlives the idle timeout and then carries a frame"
  ) {
    val binding        = bind(1.second, 3.seconds)
    val (ws, listener) = open(binding.localAddress.getPort, "/echo")
    assertEquals(listener.next(8.seconds), None, "nothing is delivered while it is silent")
    ws.sendText("still here", true).join()
    assertEquals(listener.next(5.seconds), Some("frame still here"))
    Await.ready(binding.terminate(1.second), 5.seconds)
  }

  test("V2 negative: without one, the idle timeout ends the socket without a close frame") {
    val binding       = bind(Duration.Inf, 3.seconds)
    val (_, listener) = open(binding.localAddress.getPort, "/echo")
    val ended         = listener.next(10.seconds)
    assert(ended.isDefined, "the idle timeout ended nothing")
    println(s"V2 negative: the client saw '${ended.get}'")
    Await.ready(binding.terminate(1.second), 5.seconds)
  }

  test("V4: what the JDK client does with offered subprotocols") {
    val binding = bind(Duration.Inf, 60.seconds)
    val port    = binding.localAddress.getPort
    val alone   = scala.util.Try(open(port, "/echo", "ankka.bearer.x"))
    println(s"V4: offering only the bearer, none selected: ${alone.map(_ => "opened")}")
    val (ws, _) = open(port, "/echo", "ankka.socket", "ankka.bearer.x")
    println(s"V4: offering both: selected '${ws.getSubprotocol}'")
    assertEquals(ws.getSubprotocol, "ankka.socket")
    Await.ready(binding.terminate(1.second), 5.seconds)
  }
