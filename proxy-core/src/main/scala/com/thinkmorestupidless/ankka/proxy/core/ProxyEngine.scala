package com.thinkmorestupidless.ankka.proxy.core

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.io.{IOException, InputStream, OutputStream}
import java.net.http.HttpRequest.{BodyPublisher, BodyPublishers}
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.net.{ConnectException, InetAddress, InetSocketAddress, Socket, URI}
import java.time.Duration
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * The proxy beside a web-hosted service's process.
 *
 * Two listeners. The public one, made by the `Transport`, takes every request to the service: it
 * reads who sent it, refuses a service the descriptor does not admit, and passes everything else to
 * the process on loopback with the headers `Headers.inbound` says, returning the process's answer
 * as the process makes it, part by part. The probe listener answers the platform's readiness check,
 * plain, with whether the process accepts a connection.
 *
 * The engine holds no request and no response: a body is streamed each way with a flush per read,
 * so a stream of events reaches the browser event by event. Its one bound, `responseTimeout`, is on
 * the status line; a response that has begun is never cut.
 */
final class ProxyEngine(
    settings: ProxySettings,
    transport: Transport,
    probeAddress: InetAddress = ProxyEngine.EveryAddress,
    events: ProxyEngine.Events = ProxyEngine.Events.None
):
  import ProxyEngine.*

  private val executor = Executors.newVirtualThreadPerTaskExecutor()

  private val client = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(5))
    .executor(executor)
    .build()

  private val processAddress  = s"http://127.0.0.1:${settings.processPort}"
  private val responseTimeout = Duration.ofMillis(settings.responseTimeout.toMillis)

  @volatile private var listeners: Option[(HttpServer, HttpServer)] = None

  /**
   * The settings with the port the public listener was given, which a setting of 0 chose at start.
   */
  @volatile private var effective: ProxySettings = settings

  /** Binds and starts both listeners. Fails before binding when the JVM cannot set `Host`. */
  def start(): Unit = synchronized {
    require(listeners.isEmpty, "the proxy is already started")
    requireHostHeaderAllowed()
    val public = transport.listener(settings.port)
    public.createContext("/", exchange => handle(exchange))
    public.setExecutor(executor)
    val probe = HttpServer.create(new InetSocketAddress(probeAddress, settings.probePort), 0)
    probe.createContext("/", exchange => readiness(exchange))
    probe.setExecutor(executor)
    public.start()
    probe.start()
    effective = settings.copy(port = public.getAddress.getPort)
    listeners = Some((public, probe))
  }

  /** The ports the listeners are bound to, which a setting of 0 chose at start. */
  def ports: Ports = listeners match
    case Some((public, probe)) => Ports(public.getAddress.getPort, probe.getAddress.getPort)
    case None                  => throw new IllegalStateException("the proxy is not started")

  /**
   * Stops accepting, lets the exchanges in flight finish for up to `drainTimeout`, then closes what
   * remains and releases the client.
   */
  def stop(): Unit = synchronized {
    listeners.foreach { (public, probe) =>
      probe.stop(0)
      public.stop(settings.drainTimeout.toSeconds.toInt.max(1))
      client.shutdownNow()
      executor.shutdown()
    }
    listeners = None
  }

  private def handle(exchange: HttpExchange): Unit =
    try
      transport.senderOf(exchange) match
        case Left(reason) => answer(exchange, None, Answers.refused(reason))
        case Right(sender) if !Admission.admits(effective, sender) =>
          answer(exchange, Some(sender), Answers.notAdmitted(sender))
        case Right(sender) => passOn(exchange, sender)
    catch
      case NonFatal(_) =>
        try answer(exchange, None, Answers.closedBeforeAnswering("the process"))
        catch case NonFatal(_) => ()
    finally exchange.close()

  private def passOn(exchange: HttpExchange, sender: Sender): Unit =
    val received = flatten(exchange)
    val target   = URI.create(processAddress + requestTarget(exchange))
    val request  = HttpRequest.newBuilder(target).timeout(responseTimeout)
    Headers
      .inbound(sender, effective, received)
      .filterNot((name, _) => ClientSets(name.toLowerCase))
      .foreach((name, value) => request.header(name, value))
    request.method(exchange.getRequestMethod, body(exchange, received))

    val response: Either[Answer, HttpResponse[InputStream]] =
      try Right(client.send(request.build(), BodyHandlers.ofInputStream()))
      catch
        case _: ConnectException => Left(Answers.notListening)
        case _: HttpTimeoutException =>
          Left(Answers.noAnswerInTime("the process", settings.responseTimeout))
        case _: IOException => Left(Answers.closedBeforeAnswering("the process"))
    response match
      case Left(answer)    => this.answer(exchange, Some(sender), answer)
      case Right(response) => deliver(exchange, response)

  /** The process's response, status and headers as given, the body as it arrives. */
  private def deliver(exchange: HttpExchange, response: HttpResponse[InputStream]): Unit =
    val status = response.statusCode
    val out    = exchange.getResponseHeaders
    response.headers.map.asScala.foreach { (name, values) =>
      if !Headers.droppedFromResponse(name) then values.asScala.foreach(v => out.add(name, v))
    }
    val bodyless =
      exchange.getRequestMethod.equalsIgnoreCase("HEAD") || status == 204 || status == 304
    val declared = response.headers.firstValueAsLong("content-length")
    val length =
      if bodyless then -1L
      else if declared.isPresent then if declared.getAsLong == 0 then -1L else declared.getAsLong
      else 0L
    exchange.sendResponseHeaders(status, length)
    val in = response.body
    try if !bodyless && length != -1L then copy(in, exchange.getResponseBody)
    finally in.close()

  private def answer(exchange: HttpExchange, sender: Option[Sender], answer: Answer): Unit =
    events.answered(sender, exchange.getRequestMethod, requestTarget(exchange), answer)
    val bytes = answer.body.getBytes("UTF-8")
    val out   = exchange.getResponseHeaders
    answer.headers.foreach((name, value) => out.add(name, value))
    exchange.sendResponseHeaders(answer.status, bytes.length.toLong)
    val body = exchange.getResponseBody
    body.write(bytes)
    body.close()

  private def readiness(exchange: HttpExchange): Unit =
    try
      val status =
        if exchange.getRequestURI.getPath != "/ready" then 404
        else if !exchange.getRequestMethod.equalsIgnoreCase("GET") then 405
        else if processListening then 200
        else 503
      exchange.sendResponseHeaders(status, -1L)
    finally exchange.close()

  /** Whether the process accepts a connection on its port now, asked within half a second. */
  def processListening: Boolean =
    val socket = new Socket()
    try
      socket.connect(new InetSocketAddress("127.0.0.1", settings.processPort), ConnectProbeMillis)
      true
    catch case _: IOException => false
    finally socket.close()

  /**
   * The request's body for the client. Its length is the request's own, so the process sees the
   * `Content-Length` the sender sent; a chunked request is sent chunked; a request without a body
   * has none.
   */
  private def body(exchange: HttpExchange, received: Vector[(String, String)]): BodyPublisher =
    val length  = first(received, "content-length").flatMap(_.toLongOption)
    val chunked = first(received, "transfer-encoding").exists(_.toLowerCase.contains("chunked"))
    length match
      case Some(0L) => BodyPublishers.noBody()
      case Some(n) =>
        BodyPublishers.fromPublisher(BodyPublishers.ofInputStream(() => exchange.getRequestBody), n)
      case None if chunked => BodyPublishers.ofInputStream(() => exchange.getRequestBody)
      case None            => BodyPublishers.noBody()

object ProxyEngine:

  final case class Ports(public: Int, probe: Int)

  /** What the engine tells whoever runs it; the image logs one line per answer of its own. */
  trait Events:
    def answered(sender: Option[Sender], method: String, target: String, answer: Answer): Unit

  object Events:
    val None: Events = new Events:
      def answered(sender: Option[Sender], method: String, target: String, answer: Answer): Unit =
        ()

  val EveryAddress: InetAddress = InetAddress.getByName("0.0.0.0")

  private val ConnectProbeMillis = 500

  /**
   * Headers the JDK's client sets itself and refuses to be given: the length comes from the body
   * publisher, and `Expect` would have the client wait for a continuation the process never owes.
   */
  private val ClientSets: Set[String] = Set("content-length", "expect")

  private val RestrictedHeadersProperty = "jdk.httpclient.allowRestrictedHeaders"

  /**
   * The JDK's client reads `jdk.httpclient.allowRestrictedHeaders` once, when its classes load, so
   * it is a JVM option and not something the engine can set. Without `host` in it the proxy could
   * not tell the process the address a request was sent to, so starting is refused by name.
   */
  def requireHostHeaderAllowed(): Unit =
    val allowed =
      sys.props.getOrElse(RestrictedHeadersProperty, "").split(",").map(_.trim.toLowerCase)
    if !allowed.contains("host") then
      throw new IllegalStateException(
        s"the JVM must be started with -D$RestrictedHeadersProperty=host: the proxy sets Host on " +
          "every request it passes on, and the JDK's client reads that property once, when it loads"
      )

  /** The request target as it arrived: the raw path and query, never decoded and re-encoded. */
  private def requestTarget(exchange: HttpExchange): String =
    val uri   = exchange.getRequestURI
    val path  = Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/")
    val query = Option(uri.getRawQuery).map("?" + _).getOrElse("")
    path + query

  private def flatten(exchange: HttpExchange): Vector[(String, String)] =
    exchange.getRequestHeaders.asScala.toVector.flatMap { (name, values) =>
      values.asScala.map(name -> _)
    }

  private def first(headers: Vector[(String, String)], name: String): Option[String] =
    headers.collectFirst { case (n, v) if n.equalsIgnoreCase(name) => v }

  /** Copies with a flush per read, so a part written by the process is a part the client sees. */
  private def copy(in: InputStream, out: OutputStream): Unit =
    val buffer = new Array[Byte](16 * 1024)
    var read   = in.read(buffer)
    while read != -1 do
      out.write(buffer, 0, read)
      out.flush()
      read = in.read(buffer)
    out.close()
