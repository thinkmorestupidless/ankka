package com.thinkmorestupidless.ankka.runtime.keyring

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.core.{CommandError, DataSubject, ErrorCode, KeyAnswer}
import com.thinkmorestupidless.ankka.core.keyring.*
import com.thinkmorestupidless.ankka.core.keyring.KeyringWire.given
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.time.{Clock, Duration, Instant}
import java.util.Base64
import java.util.concurrent.{CompletionStage, ConcurrentLinkedQueue, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.net.ssl.SSLParameters
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * The installation's keyring (feature 042, `contracts/keyring-and-protocol.md`), reached over the
 * service's own certificate, which is what the keyring admits it by: its keys and lookup key over
 * HTTPS, and its notices on a WebSocket held open from start and opened again whenever it ends,
 * saying after which notice to begin. The keyring must present `ankka://platform/keyring`, so a key
 * is never fetched from, nor an erasure heard from, anything else.
 *
 * Every call blocks its thread (a virtual one, in a service), for at most `timeout`; a keyring that
 * cannot be reached is `Unavailable`, as the cache expects.
 */
final class RemoteKeyStoreClient(
    address: String,
    tls: Option[RotatingTls],
    project: String,
    service: String,
    instance: String,
    timeout: FiniteDuration,
    clock: Clock = Clock.systemUTC(),
    retryEvery: FiniteDuration = FiniteDuration(1, TimeUnit.SECONDS)
) extends KeyStoreClient:

  import RemoteKeyStoreClient.*

  private val log  = LoggerFactory.getLogger(classOf[RemoteKeyStoreClient])
  private val own  = project
  private val base = address.stripSuffix("/")

  @volatile private var secured: Option[(Int, HttpClient)] = None

  /** One client per certificate generation: a renewed certificate is used from its renewal. */
  private def client: HttpClient = tls match
    case None           => plain
    case Some(identity) =>
      val context = identity.contextRequiring(KeyringUri)
      val stamp   = System.identityHashCode(context)
      secured match
        case Some((s, c)) if s == stamp => c
        case _                          =>
          val params = new SSLParameters()
          params.setEndpointIdentificationAlgorithm("HTTPS")
          params.setProtocols(Array("TLSv1.3"))
          val c = HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .sslContext(context)
            .sslParameters(params)
            .connectTimeout(Duration.ofMillis(timeout.toMillis))
            .build()
          secured = Some((stamp, c))
          c

  private lazy val plain = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofMillis(timeout.toMillis))
    .build()

  private def unavailable(why: String) =
    CommandError(s"the keyring is unavailable: $why", ErrorCode.Unavailable)

  private def post(path: String, body: String): (Int, String) =
    val request = HttpRequest
      .newBuilder(URI.create(base + path))
      .timeout(Duration.ofMillis(timeout.toMillis))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    try
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      (response.statusCode, response.body)
    catch
      case e: InterruptedException => throw e
      case NonFatal(e)             =>
        throw unavailable(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

  def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    post("/v1/keys", writeToString(KeyRequest(project, subject.value, create))) match
      case (200, body) =>
        val reply = readFromString[KeyReply](body)
        reply.key match
          case Some(k) => KeyAnswer.Key(IArray.unsafeFromArray(Base64.getDecoder.decode(k)))
          case None if reply.destroyed.contains(true) => KeyAnswer.Destroyed
          case None => throw unavailable(s"an answer with no key: $body")
      case (404, _)    => KeyAnswer.Absent
      case (403, body) =>
        val why =
          scala.util.Try(readFromString[KeyReply](body).refused).toOption.flatten.getOrElse(body)
        // Another project's key is refused without a grant to read it with decryption: the field
        // reads as erased. The service's own is never refused but by a fault, which is an error.
        if project != own then KeyAnswer.Refused(why)
        else
          throw CommandError(
            s"the keyring refused this service the key of '${subject.value}': $why",
            ErrorCode.Forbidden
          )
      case (status, body) => throw unavailable(s"$status ${body.take(200)}")

  def lookupKey(project: String): IArray[Byte] =
    post("/v1/lookup-keys", writeToString(LookupKeyRequest(project))) match
      case (200, body) =>
        IArray.unsafeFromArray(Base64.getDecoder.decode(readFromString[LookupKeyReply](body).key))
      case (status, body) => throw unavailable(s"$status ${body.take(200)}")

  // ── notices ──────────────────────────────────────────────────────────────

  private val confirmed = AtomicReference[Option[Instant]](None)
  // What a hello says this instance carried out: never past a notice whose duties failed.
  private val carried   = CarriedThrough()
  private val pending   = ConcurrentLinkedQueue[KeyringAck]()
  private val socket    = AtomicReference[Option[WebSocket]](None)
  private val closed    = AtomicBoolean(false)
  private val reopening = Executors.newSingleThreadScheduledExecutor(r =>
    val t = Thread(r, s"ankka-keyring-notices-$service")
    t.setDaemon(true)
    t
  )

  def confirmedAt(): Option[Instant] = confirmed.get

  private val firstBeat                       = scala.concurrent.Promise[Unit]()
  def caughtUp: scala.concurrent.Future[Unit] = firstBeat.future

  def subscribe(after: Long)(listener: KeyringNotice => Unit): AutoCloseable =
    carried.atLeast(after)
    open(listener)
    () =>
      closed.set(true)
      socket.getAndSet(None).foreach(s => scala.util.Try(s.sendClose(WebSocket.NORMAL_CLOSURE, "")))
      reopening.shutdownNow(): Unit

  private def open(listener: KeyringNotice => Unit): Unit =
    if !closed.get then
      val wsBase = base.replaceFirst("^https", "wss").replaceFirst("^http", "ws")
      client
        .newWebSocketBuilder()
        .connectTimeout(Duration.ofMillis(timeout.toMillis))
        .buildAsync(URI.create(s"$wsBase/v1/notices"), Listening(listener))
        .whenComplete { (ws, failure) =>
          if failure != null then
            log.warn(
              s"the keyring's notices could not be opened: ${failure.getMessage}; trying again"
            )
            reopenLater(listener)
          else
            socket.set(Some(ws))
            send(ws, NoticeFrame.Hello(project, service, instance, carried.value))
            flush(ws)
        }: Unit

  private def reopenLater(listener: KeyringNotice => Unit): Unit =
    if !closed.get then
      try
        reopening.schedule(
          (() => open(listener)): Runnable,
          retryEvery.toMillis,
          TimeUnit.MILLISECONDS
        ): Unit
      catch case _: java.util.concurrent.RejectedExecutionException => ()

  private def send(ws: WebSocket, frame: NoticeFrame): Unit =
    // The JDK allows one outstanding send: each waits for the one before.
    synchronized {
      ws.sendText(writeToString[NoticeFrame](frame), true)
        .get(timeout.toMillis, TimeUnit.MILLISECONDS): Unit
    }

  private def flush(ws: WebSocket): Unit =
    var next = pending.peek()
    while next != null do
      try
        // As the instance that said hello: the keyring holds a connection to one instance.
        send(ws, NoticeFrame.Ack(next.seq, service, instance, next.completion))
        pending.poll(): Unit
        carried.carried(next.seq): Unit
        next = pending.peek()
      catch
        case NonFatal(e) =>
          log.warn(s"an acknowledgement waits for the notices to reopen: ${e.getMessage}")
          next = null

  def ack(ack: KeyringAck): Unit =
    pending.add(ack): Unit
    socket.get.foreach(flush)

  private final class Listening(listener: KeyringNotice => Unit) extends WebSocket.Listener:
    private val text = StringBuilder()

    override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
      text.append(data)
      if last then
        val frame = text.toString
        text.clear()
        scala.util.Try(readFromString[NoticeFrame](frame)).toOption match
          case Some(n: NoticeFrame.Notice) =>
            confirmed.set(Some(clock.instant()))
            carried.handed(n.seq)
            try listener(n.notice)
            catch case NonFatal(e) => log.warn(s"notice ${n.seq} was not handled: ${e.getMessage}")
          case Some(_: NoticeFrame.Heartbeat) =>
            // The keyring's first heartbeat on a channel follows the channel's backlog.
            confirmed.set(Some(clock.instant()))
            firstBeat.trySuccess(()): Unit
          case _ => ()
      ws.request(1)
      null

    override def onClose(ws: WebSocket, status: Int, reason: String): CompletionStage[?] =
      ended(ws, s"closed ($status $reason)")
      null

    override def onError(ws: WebSocket, error: Throwable): Unit =
      ended(ws, Option(error.getMessage).getOrElse(error.getClass.getSimpleName))

    private def ended(ws: WebSocket, why: String): Unit =
      if socket.compareAndSet(Some(ws), None) && !closed.get then
        log.warn(s"the keyring's notices ended: $why; opening them again")
        reopenLater(listener)

  override def close(): Unit =
    closed.set(true)
    socket.getAndSet(None).foreach(s => scala.util.Try(s.sendClose(WebSocket.NORMAL_CLOSURE, "")))
    reopening.shutdownNow(): Unit

object RemoteKeyStoreClient:
  /** The identity the keyring's certificate carries. */
  val KeyringUri: String = "ankka://platform/keyring"

  /** The service's own certificate, as every port reads it, when the service has one. */
  def tlsFrom(config: com.typesafe.config.Config): Option[RotatingTls] =
    val key       = "ankka.tls.service-directory"
    val directory = if config.hasPath(key) then config.getString(key).trim else ""
    Option.when(directory.nonEmpty)(
      RotatingTls(
        java.nio.file.Paths.get(directory),
        FiniteDuration(
          config.getDuration("ankka.tls.reload-interval").toMillis,
          TimeUnit.MILLISECONDS
        )
      )
    )
