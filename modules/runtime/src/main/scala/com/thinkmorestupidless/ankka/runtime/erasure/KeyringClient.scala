package com.thinkmorestupidless.ankka.runtime.erasure

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.core.personal.KeyResult
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import org.apache.pekko.actor.typed.ActorSystem

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.file.Paths
import java.time.Duration
import java.util.Base64
import java.util.concurrent.{
  CompletableFuture,
  CompletionStage,
  ConcurrentHashMap,
  Executors,
  ScheduledExecutorService,
  TimeUnit
}
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLParameters
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * A service instance's one WebSocket to the keyring (R8), over the JDK's client: no library added.
 *
 * Opened with `hello` and kept open: every fetch rides on it with an id its answer repeats, and the
 * keyring pushes destroyed notices, apply orders and the log down it. A closed channel is reopened
 * with a backoff from one second, doubling to thirty; while it is closed every fetch is
 * `Unavailable`, which the cache answers from what it holds for the outage bound.
 *
 * In Kubernetes the channel is mutual TLS as the service's own certificate, and the keyring must
 * present `ankka://platform/keyring`; locally it is plain, as every local call is.
 */
final class KeyringClient(
    uri: URI,
    tls: Option[RotatingTls],
    answerWithin: FiniteDuration = 10.seconds,
    log: String => Unit = _ => ()
) extends KeyringConnection:
  import ChannelWire.*

  private val ids     = AtomicLong()
  private val pending = ConcurrentHashMap[Long, CompletableFuture[In]]()
  private val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())
  @volatile private var socket: Option[WebSocket]         = None
  @volatile private var hello: Option[Hello]              = None
  @volatile private var listener: Option[KeyringListener] = None
  @volatile private var closed                            = false
  @volatile private var backoff                           = 1.second

  private def client: HttpClient =
    val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
    tls.foreach { identity =>
      val params = SSLParameters()
      params.setEndpointIdentificationAlgorithm("HTTPS")
      params.setProtocols(Array("TLSv1.3"))
      builder
        .sslContext(identity.contextRequiring(KeyringClient.KeyringIdentity))
        .sslParameters(params)
    }
    builder.build()

  def open(hello: Hello, listener: KeyringListener): Unit =
    this.hello = Some(hello)
    this.listener = Some(listener)
    connect()

  private def connect(): Unit =
    if !closed then
      client
        .newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .buildAsync(uri, Receiver())
        .whenComplete { (ws, failure) =>
          if failure != null then
            log(s"keyring channel to $uri could not open: ${failure.getMessage}; again in $backoff")
            retry()
          else
            socket = Some(ws)
            backoff = 1.second
            hello.foreach { h =>
              send(
                Out.Hello(h.project, h.service, h.instance, h.reads.toVector.sorted, h.appliedUpTo)
              )
            }
        }: Unit

  private def retry(): Unit =
    if !closed then
      val wait = backoff
      backoff = (backoff * 2).min(30.seconds)
      scheduler.schedule((() => connect()): Runnable, wait.toMillis, TimeUnit.MILLISECONDS): Unit

  private def lost(reason: String): Unit =
    socket = None
    val failure = KeyringClient.unavailable(reason)
    pending.forEach((_, answer) => answer.completeExceptionally(failure): Unit)
    pending.clear()
    listener.foreach(_.closed(reason))
    retry()

  private def send(out: Out): Unit =
    socket match
      case Some(ws) => ws.sendText(write(out), true).join(): Unit
      case None     => throw KeyringClient.unavailable("the channel to the keyring is not open")

  private def ask(make: Long => Out): In =
    val id     = ids.incrementAndGet()
    val answer = CompletableFuture[In]()
    pending.put(id, answer)
    try
      send(make(id))
      answer.get(answerWithin.toMillis, TimeUnit.MILLISECONDS)
    catch
      case e: java.util.concurrent.ExecutionException =>
        e.getCause match
          case error: CommandError => throw error
          case other               => throw KeyringClient.unavailable(String.valueOf(other))
      case _: java.util.concurrent.TimeoutException =>
        throw KeyringClient.unavailable(s"the keyring did not answer within $answerWithin")
    finally pending.remove(id): Unit

  def fetch(project: String, subject: String, create: Boolean): KeyResult =
    ask(id => Out.Fetch(id, project, subject, create)) match
      case In.Key(_, _, _, key)          => KeyResult.Available(Base64.getDecoder.decode(key))
      case In.Erased(_, _, _, erasureId) => KeyResult.Destroyed(erasureId)
      case In.Unknown(_, _, _)           => KeyResult.Unknown
      case In.Refused(_, _, _, reason)   => KeyResult.Refused(reason)
      case other => throw KeyringClient.unavailable(s"unexpected answer: $other")

  def lookupKey(project: String): Array[Byte] =
    ask(id => Out.LookupKey(id, project)) match
      case In.LookupKeyIs(_, _, key) => Base64.getDecoder.decode(key)
      case In.Refused(_, _, _, reason) =>
        throw CommandError(
          s"the keyring refused the lookup key of $project: $reason",
          ErrorCode.Forbidden
        )
      case other => throw KeyringClient.unavailable(s"unexpected answer: $other")

  def ack(erasureId: String): Unit =
    try send(Out.Ack(erasureId))
    catch
      case NonFatal(_) =>
        () // the keyring closes an unacknowledged channel; the cache is dropped then

  def completed(completion: Completion): Unit = send(ChannelWire.completed(completion))

  def close(): Unit =
    closed = true
    socket.foreach(ws => ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown"): Unit)
    scheduler.shutdownNow(): Unit

  private final class Receiver extends WebSocket.Listener:
    private val text = StringBuilder()

    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      text.append(data)
      if last then
        val frame = text.toString
        text.clear()
        try dispatch(readIn(frame))
        catch case NonFatal(failure) => log(s"keyring channel: a frame could not be read: $failure")
      ws.request(1)
      null

    override def onClose(ws: WebSocket, status: Int, reason: String): CompletionStage[?] =
      lost(if reason == null || reason.isEmpty then s"closed ($status)" else reason)
      null

    override def onError(ws: WebSocket, error: Throwable): Unit =
      lost(s"failed: ${error.getMessage}")

  private def answered(id: Long, answer: In): Unit =
    Option(pending.get(id)).foreach(_.complete(answer): Unit)

  private def dispatch(in: In): Unit = in match
    case answer @ In.Key(id, _, _, _)      => answered(id, answer)
    case answer @ In.Erased(id, _, _, _)   => answered(id, answer)
    case answer @ In.Unknown(id, _, _)     => answered(id, answer)
    case answer @ In.Refused(id, _, _, _)  => answered(id, answer)
    case answer @ In.LookupKeyIs(id, _, _) => answered(id, answer)
    case In.Log(entries) =>
      listener.foreach(
        _.log(
          entries.map(e =>
            LogEntry(
              e.erasureId,
              e.sequence,
              e.subject,
              java.time.Instant.ofEpochMilli(e.destroyedAt)
            )
          )
        )
      )
    case In.Destroyed(project, subject, erasureId) =>
      listener.foreach(_.destroyed(project, subject, erasureId))
    case In.Apply(erasureId, sequence, subject, reapply) =>
      listener.foreach(_.apply(ErasureOrder(erasureId, sequence, subject, reapply)))
    case In.Close(reason) => listener.foreach(_.closed(reason))

object KeyringClient:
  val KeyringIdentity: String = "ankka://platform/keyring"
  val UrlKey: String          = "ankka.erasure.keyring-url"

  def unavailable(reason: String): CommandError =
    CommandError(s"the keyring is unavailable: $reason", ErrorCode.Unavailable)

  /** The channel `ANKKA_KEYRING_URL` names, or none where it names nothing. */
  def fromConfig(system: ActorSystem[?]): Option[KeyringConnection] =
    val config = system.settings.config
    val url    = if config.hasPath(UrlKey) then config.getString(UrlKey).trim else ""
    Option.when(url.nonEmpty) {
      val directory = "ankka.tls.service-directory"
      val tls = Option.when(config.hasPath(directory) && config.getString(directory).nonEmpty)(
        RotatingTls(
          Paths.get(config.getString(directory)),
          FiniteDuration(config.getDuration("ankka.tls.reload-interval").toMillis, MILLISECONDS)
        )
      )
      val base   = url.stripSuffix("/")
      val socket = base.replaceFirst("^http", "ws") + "/channel"
      KeyringClient(URI.create(socket), tls, log = line => system.log.warn(line))
    }
