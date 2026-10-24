package com.thinkmorestupidless.ankka.modelkeeper

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import com.thinkmorestupidless.ankka.core.{ModelServerDocument, ModelServerDocumentJson}
import com.thinkmorestupidless.ankka.proxy.TlsTransport
import com.thinkmorestupidless.ankka.proxy.core.Sender
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import org.slf4j.LoggerFactory

import java.io.{InputStream, OutputStream}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetAddress, InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * The model server's three listeners (contracts/keeper.md of feature 053).
 *
 * The **door** is mutual TLS from the service authority: a connection without a certificate it
 * issued never completes its handshake, so nothing the developer's program could send reaches
 * Ollama. A certificate that names a service of the installation is admitted, and its request on
 * one of five paths is passed to Ollama on the pod's loopback, the answer streamed back as it
 * comes; the gateway, a mount and anything else the authority issued are refused, and every other
 * path is not served, so no service can fetch, remove or make a model.
 *
 * The **status port** is plain, for the operator, which holds no service certificate; the
 * component's policy admits the operator's pods alone. The **probe** is plain, for the kubelet.
 */
final class Door(
    settings: KeeperSettings,
    tls: RotatingTls,
    source: Door.Source,
    telemetry: KeeperTelemetry,
    bind: InetAddress = Door.EveryAddress
):
  import Door.*

  private val log       = LoggerFactory.getLogger("ankka.model-keeper.door")
  private val executor  = Executors.newVirtualThreadPerTaskExecutor()
  private val transport = TlsTransport(tls, bind)
  private val ollama    = settings.ollama.toString.stripSuffix("/")
  private val client    = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(2))
    .executor(executor)
    .build()

  @volatile private var listeners: Option[(HttpServer, HttpServer, HttpServer)] = None

  def start(): Unit = synchronized {
    require(listeners.isEmpty, "the door is already open")
    val door = transport.listener(settings.port)
    door.createContext("/", exchange => handle(exchange))
    door.setExecutor(executor)
    val status = HttpServer.create(new InetSocketAddress(bind, settings.statusPort), 0)
    status.createContext("/", exchange => statusOnly(exchange))
    status.setExecutor(executor)
    val probe = HttpServer.create(new InetSocketAddress(bind, settings.probePort), 0)
    probe.createContext("/", exchange => readiness(exchange))
    probe.setExecutor(executor)
    door.start()
    status.start()
    probe.start()
    listeners = Some((door, status, probe))
  }

  /** The ports the listeners are bound to, which a setting of 0 chose at start. */
  def ports: Ports = listeners match
    case Some((door, status, probe)) =>
      Ports(door.getAddress.getPort, status.getAddress.getPort, probe.getAddress.getPort)
    case None => throw new IllegalStateException("the door is not open")

  def stop(): Unit = synchronized {
    listeners.foreach { (door, status, probe) =>
      probe.stop(0)
      status.stop(0)
      door.stop(2)
      client.shutdownNow()
      executor.shutdown()
    }
    listeners = None
  }

  private def handle(exchange: HttpExchange): Unit =
    try
      transport.senderOf(exchange) match
        case Right(Sender.Service(project, service)) => admitted(exchange, project, service)
        case Right(other)                            =>
          log.info("refused {}: {}", Sender.describe(other), target(exchange))
          telemetry.refused()
          json(exchange, 403, NotAService)
        case Left(reason) =>
          log.info("refused a connection: {}", reason)
          telemetry.refused()
          json(exchange, 403, NotAService)
    catch
      case NonFatal(e) =>
        log.warn("a request through the door failed: {}", e.toString)
        try json(exchange, 502, """{"error":"the model server could not answer"}""")
        catch case NonFatal(_) => ()
    finally exchange.close()

  private def admitted(exchange: HttpExchange, project: String, service: String): Unit =
    val method = exchange.getRequestMethod.toUpperCase
    val path   = exchange.getRequestURI.getRawPath
    if method == "GET" && path == StatusPath then json(exchange, 200, document)
    else if !Forwarded.contains(method -> path) then
      telemetry.refused()
      json(exchange, 404, NotServed)
    else
      val body = exchange.getRequestBody.readNBytes(MaxBody + 1)
      if body.length > MaxBody then json(exchange, 413, """{"error":"the request is too large"}""")
      else
        val model = modelOf(body).getOrElse(NoModel)
        val call  = telemetry.call(
          model,
          project,
          service,
          Option(exchange.getRequestHeaders.getFirst("traceparent"))
        )
        var status = 502
        try status = forward(exchange, method, body, s"$project/$service")
        finally call.end(status)

  /** Sends the request to Ollama and the answer back; the status the caller was given. */
  private def forward(
      exchange: HttpExchange,
      method: String,
      body: Array[Byte],
      caller: String
  ): Int =
    val request = HttpRequest.newBuilder(URI.create(ollama + target(exchange)))
    exchange.getRequestHeaders.asScala.foreach { (name, values) =>
      if !NotPassedOn.contains(name.toLowerCase) then
        values.asScala.foreach(v => request.header(name, v))
    }
    request.header(CallerHeader, caller)
    request.method(
      method,
      if body.isEmpty then BodyPublishers.noBody() else BodyPublishers.ofByteArray(body)
    )
    val response: Either[Int, HttpResponse[InputStream]] =
      try Right(client.send(request.build(), BodyHandlers.ofInputStream()))
      catch case NonFatal(_) => Left(503)
    response match
      case Left(status) =>
        json(exchange, status, """{"error":"the model server is not answering"}""")
        status
      case Right(answer) =>
        deliver(exchange, answer)
        answer.statusCode

  private def deliver(exchange: HttpExchange, response: HttpResponse[InputStream]): Unit =
    val out = exchange.getResponseHeaders
    response.headers.map.asScala.foreach { (name, values) =>
      if !NotPassedBack.contains(name.toLowerCase) then
        values.asScala.foreach(v => out.add(name, v))
    }
    val declared = response.headers.firstValueAsLong("content-length")
    val length   =
      if declared.isPresent then if declared.getAsLong == 0 then -1L else declared.getAsLong
      else 0L
    exchange.sendResponseHeaders(response.statusCode, length)
    val in = response.body
    try if length != -1L then copy(in, exchange.getResponseBody)
    finally in.close()

  private def statusOnly(exchange: HttpExchange): Unit =
    try
      if exchange.getRequestMethod.equalsIgnoreCase("GET") &&
        exchange.getRequestURI.getPath == StatusPath
      then json(exchange, 200, document)
      else json(exchange, 404, NotServed)
    finally exchange.close()

  private def readiness(exchange: HttpExchange): Unit =
    try
      exchange.getRequestURI.getPath match
        case "/live"  => text(exchange, 200, "live")
        case "/ready" =>
          source.ready match
            case Right(())    => text(exchange, 200, "ready")
            case Left(reason) => text(exchange, 503, reason)
        case _ => text(exchange, 404, "not found")
    finally exchange.close()

  private def document: String =
    ModelServerDocumentJson.write(source.document.copy(calls = telemetry.counts))

  private def json(exchange: HttpExchange, status: Int, body: String): Unit =
    exchange.getResponseHeaders.set("Content-Type", "application/json")
    send(exchange, status, body)

  private def text(exchange: HttpExchange, status: Int, body: String): Unit =
    exchange.getResponseHeaders.set("Content-Type", "text/plain; charset=utf-8")
    send(exchange, status, body)

  private def send(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(UTF_8)
    exchange.sendResponseHeaders(status, if bytes.isEmpty then -1L else bytes.length.toLong)
    if bytes.nonEmpty then
      val out = exchange.getResponseBody
      out.write(bytes)
      out.close()

object Door:

  /**
   * What the door serves of the keeper: the document as last published, and whether it is ready.
   */
  trait Source:
    def document: ModelServerDocument
    def ready: Either[String, Unit]

  final case class Ports(door: Int, status: Int, probe: Int)

  val EveryAddress: InetAddress = InetAddress.getByName("0.0.0.0")

  val StatusPath: String   = "/ankka/models"
  val CallerHeader: String = "X-Ankka-Caller"

  /** The requests a service may make of Ollama: the call, the listing, and what it can see. */
  val Forwarded: Set[(String, String)] = Set(
    "POST" -> "/v1/chat/completions",
    "GET"  -> "/v1/models",
    "POST" -> "/api/show",
    "GET"  -> "/api/tags",
    "GET"  -> "/api/ps"
  )

  val NotAService: String =
    """{"error":"the model server admits services of the installation only"}"""
  val NotServed: String = """{"error":"not served to services"}"""

  /** A model call's body is a conversation: large, but not unbounded. */
  val MaxBody: Int = 32 * 1024 * 1024

  private val NoModel = "(none)"

  /**
   * Not passed on: a hop's own headers, those the JDK's client sets itself, the caller's credential
   * (Ollama has none to check, and nothing of a caller's should reach its log), and a caller header
   * a request might carry to claim someone else's name.
   */
  private val NotPassedOn: Set[String] = Set(
    "authorization",
    "connection",
    "content-length",
    "expect",
    "host",
    "keep-alive",
    "proxy-authorization",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
    CallerHeader.toLowerCase
  )

  private val NotPassedBack: Set[String] =
    Set("connection", "content-length", "keep-alive", "transfer-encoding", "trailer", "upgrade")

  private val ModelPattern = """"model"\s*:\s*"([^"]*)"""".r

  private def modelOf(body: Array[Byte]): Option[String] =
    if body.isEmpty then None
    else ModelPattern.findFirstMatchIn(new String(body, UTF_8)).map(_.group(1))

  private def target(exchange: HttpExchange): String =
    val uri = exchange.getRequestURI
    Option(uri.getRawPath).filter(_.nonEmpty).getOrElse("/") +
      Option(uri.getRawQuery).map("?" + _).getOrElse("")

  /** Copies with a flush per read, so a part Ollama wrote is a part the caller sees. */
  private def copy(in: InputStream, out: OutputStream): Unit =
    val buffer = new Array[Byte](16 * 1024)
    var read   = in.read(buffer)
    while read != -1 do
      out.write(buffer, 0, read)
      out.flush()
      read = in.read(buffer)
    out.close()
