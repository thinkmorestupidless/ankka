package com.thinkmorestupidless.ankka.http

import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import org.apache.pekko.stream.{Materializer, OverflowStrategy, QueueOfferResult}
import org.apache.pekko.stream.StreamLimitReachedException
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source, SourceQueueWithComplete}

import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}

/** A socket route's limits, from `ankka.http.socket`. */
private[http] final case class SocketSettings(
    maxFrameSize: Long,
    unreadFrames: Int,
    keepAlive: FiniteDuration
)

private[http] object SocketSettings:

  def from(config: com.typesafe.config.Config): SocketSettings =
    val socket = config.getConfig("ankka.http.socket")
    SocketSettings(
      maxFrameSize = socket.getBytes("max-frame-size"),
      unreadFrames = socket.getInt("unread-frames"),
      keepAlive = socket.getDuration("keep-alive").toMillis.millis
    )

  /**
   * What is wrong with these settings beside a server whose idle timeout is `idle`. A keep-alive
   * that is not shorter than the idle timeout does nothing: the idle timeout cuts a quiet socket
   * off first.
   */
  def problems(settings: SocketSettings, idle: Duration): Vector[String] =
    Vector(
      Option.when(settings.maxFrameSize <= 0)(
        s"ankka.http.socket.max-frame-size must be positive, not ${settings.maxFrameSize}"
      ),
      Option.when(settings.unreadFrames < 1)(
        s"ankka.http.socket.unread-frames must be at least 1, not ${settings.unreadFrames}"
      ),
      Option.when(idle.isFinite && settings.keepAlive >= idle)(
        s"ankka.http.socket.keep-alive (${settings.keepAlive.toCoarsest}) must be shorter than " +
          s"pekko.http.server.idle-timeout (${coarsest(idle)}), which would otherwise cut a quiet " +
          "socket off"
      )
    ).flatten

  private def coarsest(duration: Duration): Duration = duration match
    case finite: FiniteDuration => finite.toCoarsest
    case other                  => other

  /** UTF-8 bytes, counted without encoding. */
  def utf8Length(text: CharSequence): Long =
    var bytes = 0L
    var i     = 0
    while i < text.length do
      val c = text.charAt(i)
      bytes +=
        (if c < 0x80 then 1
         else if c < 0x800 then 2
         else if Character.isHighSurrogate(c) && i + 1 < text.length then
           i += 1
           4
         else 3)
      i += 1
    bytes

/** The sockets one server holds open, so a stopping server can close each one first. */
private[http] final class OpenSockets:
  private val open = ConcurrentHashMap.newKeySet[OpenSocket]()

  def add(socket: OpenSocket): Unit    = open.add(socket): Unit
  def remove(socket: OpenSocket): Unit = open.remove(socket): Unit
  def size: Int                        = open.size

  /** Closes every open socket with `reason`, and waits up to `within` for them to end. */
  def closeAll(reason: CloseReason, within: FiniteDuration): Unit =
    open.forEach(_.close(reason))
    val deadline = within.fromNow
    while !open.isEmpty && deadline.hasTimeLeft() do Thread.sleep(20)

/**
 * One open socket: the bridge between pekko-http's stream of messages and a handler that blocks.
 *
 * Inbound frames wait in a queue the handler takes from with `receive`; outbound frames are offered
 * to a bounded source the client drains, and `send` waits while it is full. Whoever ends the socket
 * first — the handler, a limit, a stopping server, the client — decides how it ended, once.
 */
private[http] final class OpenSocket(settings: SocketSettings, registry: OpenSockets)
    extends Socket:

  private enum Item:
    case Frame(text: String)
    case End

  private enum Ending:
    case ByPlatform(reason: CloseReason)
    case ByClient

  private val ending  = AtomicReference[Option[Ending]](None)
  private val inbound = LinkedBlockingQueue[Item]()
  private val waiting = AtomicInteger(0)

  @volatile private var outbound: SourceQueueWithComplete[Message] = null

  /** The close code and reason ankka's side sends, when ankka's side ended the socket. */
  def chosen(): Option[(Int, String)] = ending.get match
    case Some(Ending.ByPlatform(reason)) => Some(reason.code -> reason.word)
    case _                               => None

  /** How the socket ended, once it has: a reason, or `None` for the client's own close. */
  def endedBy: Option[Option[CloseReason]] = ending.get.map {
    case Ending.ByPlatform(reason) => Some(reason)
    case Ending.ByClient           => None
  }

  private[ankka] override def closedBecause: Option[String] = endedBy.map(_.fold("client")(_.word))

  def receive(): Option[String] =
    inbound.take() match
      case Item.Frame(text) =>
        waiting.decrementAndGet(): Unit
        Some(text)
      case Item.End =>
        inbound.put(Item.End) // every later receive answers the same
        None

  def send(text: String): Unit = synchronized {
    if ending.get.isDefined then throw SocketClosed(describeEnding)
    val offered = outbound.offer(TextMessage.Strict(text))
    // Waits for room, waking to notice a close: an offer pending when the socket ends may never
    // be answered, and a send must not outlive its socket.
    var result: Option[QueueOfferResult] = None
    while result.isEmpty do
      try result = Some(Await.result(offered, 200.millis))
      catch
        case _: TimeoutException =>
          if ending.get.isDefined then throw SocketClosed(describeEnding)
        case scala.util.control.NonFatal(_) => throw SocketClosed(describeEnding)
    if result.get != QueueOfferResult.Enqueued then throw SocketClosed(describeEnding)
  }

  /** Ends the socket with `reason`, unless it has already ended. */
  def close(reason: CloseReason): Unit =
    if ending.compareAndSet(None, Some(Ending.ByPlatform(reason))) then
      inbound.put(Item.End)
      if outbound != null then outbound.complete()

  private def clientEnded(): Unit =
    if ending.compareAndSet(None, Some(Ending.ByClient)) then
      inbound.put(Item.End)
      if outbound != null then outbound.complete()

  private def describeEnding: String = endedBy match
    case Some(Some(reason)) => reason.word
    case Some(None)         => "closed by the client"
    case None               => "open"

  private def accept(frame: Either[CloseReason, String]): Unit = frame match
    case Left(reason) => close(reason)
    case Right(text) =>
      if waiting.get >= settings.unreadFrames then close(CloseReason.Unread)
      else
        waiting.incrementAndGet(): Unit
        inbound.put(Item.Frame(text))

  /**
   * The message flow pekko-http serves the socket with. `start` is called once, when the connection
   * is upgraded and the flow materialized — not before, so a handler never waits on a socket that
   * never opened.
   */
  def flow(start: OpenSocket => Unit): Flow[Message, Message, NotUsed] =
    Flow
      .fromMaterializer { (materializer, _) =>
        given Materializer     = materializer
        given ExecutionContext = materializer.executionContext
        val (queue, source) =
          Source.queue[Message](16, OverflowStrategy.backpressure).preMaterialize()
        outbound = queue
        registry.add(this)
        val max = settings.maxFrameSize
        val frames: Flow[Message, Either[CloseReason, String], NotUsed] =
          Flow[Message].mapAsync(1) {
            case TextMessage.Strict(text) =>
              Future.successful(
                if SocketSettings.utf8Length(text) > max then Left(CloseReason.TooLarge)
                else Right(text)
              )
            case streamed: TextMessage =>
              streamed.textStream
                .limitWeighted(max)(SocketSettings.utf8Length)
                .runFold(StringBuilder())(_ ++= _)
                .map(b => Right(b.result()))
                .recover { case _: StreamLimitReachedException => Left(CloseReason.TooLarge) }
            case binary: BinaryMessage =>
              binary.dataStream.runWith(Sink.ignore)
              Future.successful(Left(CloseReason.NotText))
          }
        val sink = frames.to(Sink.foreach(accept)).mapMaterializedValue(_ => NotUsed)
        start(this)
        Flow
          .fromSinkAndSourceCoupled(
            Flow[Message]
              .watchTermination() { (_, done) =>
                done.onComplete { _ =>
                  clientEnded()
                  registry.remove(this)
                }
                NotUsed
              }
              .to(sink),
            source
          )
      }
      .mapMaterializedValue(_ => NotUsed)
