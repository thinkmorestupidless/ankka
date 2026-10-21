package com.thinkmorestupidless.ankka.testollama

import com.sun.net.httpserver.{
  HttpExchange,
  HttpServer,
  HttpsConfigurator,
  HttpsParameters,
  HttpsServer
}

import java.io.OutputStream
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, Instant}
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList, CountDownLatch, Executors}
import javax.net.ssl.SSLContext
import scala.jdk.CollectionConverters.*

/**
 * A stand-in Ollama on loopback, for the keeper's suites and the sidecar's (feature 053).
 *
 * It answers the paths the keeper and a service use with Ollama's shapes, from a catalogue a test
 * scripts: what a fetch of a tag would bring (`offer`), what a fetch fails with (`failFetch`), what
 * is already held, as on a volume after a restart (`hold`), and what a chat completion answers. It
 * keeps a model loaded the way Ollama does: each request naming a model sets its expiry from the
 * request's `keep_alive`, or from the default the stand-in was started with, and a negative value
 * is for ever. Every request is recorded.
 */
final class FakeOllama private (server: HttpServer, defaultKeepAlive: Option[Duration]):
  import FakeOllama.*

  private val registry = new ConcurrentHashMap[String, Model]()
  private val failures = new ConcurrentHashMap[String, String]()
  private val held     = new ConcurrentHashMap[String, Model]()
  // Expiry in epoch milliseconds; Long.MaxValue is for ever.
  private val loaded   = new ConcurrentHashMap[String, java.lang.Long]()
  private val recorded = new CopyOnWriteArrayList[Recorded]()

  /** What a chat completion answers, when not streamed: the whole JSON body. */
  @volatile var completion: String = DefaultCompletion

  /** When set, a streamed completion waits on it between its first chunk and the rest. */
  @volatile var streamHold: Option[CountDownLatch] = None

  /** When set, a fetch waits on it after its first status line, as a download of minutes does. */
  @volatile var pullHold: Option[CountDownLatch] = None

  def port: Int = server.getAddress.getPort
  def url: String = server match
    case _: HttpsServer => s"https://localhost:$port"
    case _              => s"http://127.0.0.1:$port"

  /** A fetch of `tag` brings this model. */
  def offer(
      tag: String,
      digest: Option[String] = None,
      capabilities: Vector[String] = Vector("completion", "tools")
  ): FakeOllama =
    registry.put(tag, Model(tag, digest.getOrElse(digestOf(tag)), capabilities)): Unit
    this

  /** A fetch of `tag` ends with `line` as its error. */
  def failFetch(tag: String, line: String): FakeOllama =
    failures.put(tag, line): Unit
    this

  /** `tag` is already held, as on a volume the server restarted with; not loaded. */
  def hold(
      tag: String,
      digest: Option[String] = None,
      capabilities: Vector[String] = Vector("completion", "tools")
  ): FakeOllama =
    held.put(tag, Model(tag, digest.getOrElse(digestOf(tag)), capabilities)): Unit
    this

  /** Unloads `tag`, as Ollama does when its keep-alive runs out. */
  def unload(tag: String): Unit = loaded.remove(tag): Unit

  def heldTags: Set[String] = held.keySet.asScala.toSet

  def loadedTags: Set[String] =
    val now = System.currentTimeMillis()
    loaded.asScala.collect { case (tag, until) if until > now => tag }.toSet

  def requests: Vector[Recorded] = recorded.asScala.toVector

  def requestsTo(method: String, path: String): Vector[Recorded] =
    requests.filter(r => r.method == method && r.path == path)

  def stop(): Unit = server.stop(0)

  private def handle(exchange: HttpExchange): Unit =
    try
      val method = exchange.getRequestMethod.toUpperCase
      val path   = exchange.getRequestURI.getPath
      val body   = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
      val headers = exchange.getRequestHeaders.asScala
        .map((k, v) => k.toLowerCase -> v.asScala.mkString(","))
        .toMap
      recorded.add(Recorded(method, path, headers, body)): Unit
      (method, path) match
        case ("GET", "/api/version")          => json(exchange, 200, """{"version":"0.40.6"}""")
        case ("GET", "/api/tags")             => json(exchange, 200, tags)
        case ("GET", "/api/ps")               => json(exchange, 200, ps)
        case ("POST", "/api/show")            => show(exchange, body)
        case ("POST", "/api/pull")            => pull(exchange, body)
        case ("POST", "/api/generate")        => generate(exchange, body)
        case ("DELETE", "/api/delete")        => delete(exchange, body)
        case ("POST", "/v1/chat/completions") => chat(exchange, body)
        case ("GET", "/v1/models") =>
          val data = held.keySet.asScala.toVector.sorted
            .map(t => s"""{"id":"$t","object":"model","owned_by":"library"}""")
          json(exchange, 200, s"""{"object":"list","data":[${data.mkString(",")}]}""")
        case _ => json(exchange, 404, "404 page not found")
    catch case _: java.io.IOException => ()
    finally exchange.close()

  private def tags: String =
    val models = held.values.asScala.toVector.sortBy(_.tag).map { m =>
      s"""{"name":"${m.tag}","model":"${m.tag}","digest":"${m.digest}","size":523000000}"""
    }
    s"""{"models":[${models.mkString(",")}]}"""

  private def ps: String =
    val now = System.currentTimeMillis()
    val models = loaded.asScala.toVector.filter(_._2 > now).sortBy(_._1).map { (tag, until) =>
      val expires =
        if until == Long.MaxValue then "2318-02-23T00:00:00Z"
        else Instant.ofEpochMilli(until).toString
      val digest = Option(held.get(tag)).map(_.digest).getOrElse("")
      s"""{"name":"$tag","model":"$tag","digest":"$digest","expires_at":"$expires"}"""
    }
    s"""{"models":[${models.mkString(",")}]}"""

  private def show(exchange: HttpExchange, body: String): Unit =
    modelOf(body).flatMap(t => Option(held.get(t))) match
      case Some(m) =>
        val capabilities = m.capabilities.map(c => s""""$c"""").mkString(",")
        json(exchange, 200, s"""{"details":{"format":"gguf"},"capabilities":[$capabilities]}""")
      case None =>
        json(exchange, 404, s"""{"error":"model '${modelOf(body).getOrElse("")}' not found"}""")

  private def pull(exchange: HttpExchange, body: String): Unit =
    val tag = modelOf(body).getOrElse("")
    exchange.getResponseHeaders.add("Content-Type", "application/x-ndjson")
    exchange.sendResponseHeaders(200, 0)
    val out = exchange.getResponseBody
    line(out, """{"status":"pulling manifest"}""")
    pullHold.foreach(_.await())
    (Option(failures.get(tag)), Option(registry.get(tag))) match
      case (Some(failure), _) => line(out, s"""{"error":"${escape(failure)}"}""")
      case (None, Some(model)) =>
        line(
          out,
          s"""{"status":"pulling ${model.digest.take(
              12
            )}","total":523000000,"completed":523000000}"""
        )
        line(out, """{"status":"verifying sha256 digest"}""")
        line(out, """{"status":"writing manifest"}""")
        held.put(tag, model): Unit
        line(out, """{"status":"success"}""")
      case (None, None) => line(out, """{"error":"pull model manifest: file does not exist"}""")
    out.close()

  private def generate(exchange: HttpExchange, body: String): Unit =
    modelOf(body) match
      case Some(tag) if held.containsKey(tag) =>
        keep(tag, keepAliveOf(body))
        json(exchange, 200, s"""{"model":"$tag","response":"","done":true,"done_reason":"load"}""")
      case other =>
        json(exchange, 404, s"""{"error":"model '${other.getOrElse("")}' not found"}""")

  private def delete(exchange: HttpExchange, body: String): Unit =
    modelOf(body) match
      case Some(tag) if held.containsKey(tag) =>
        held.remove(tag)
        loaded.remove(tag)
        exchange.sendResponseHeaders(200, -1)
      case other =>
        json(exchange, 404, s"""{"error":"model '${other.getOrElse("")}' not found"}""")

  private def chat(exchange: HttpExchange, body: String): Unit =
    modelOf(body) match
      case Some(tag) if held.containsKey(tag) =>
        keep(tag, keepAliveOf(body))
        if StreamPattern.findFirstIn(body).isDefined then
          exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
          exchange.sendResponseHeaders(200, 0)
          val out = exchange.getResponseBody
          event(out, chunk(tag, "Hel"))
          streamHold.foreach(_.await())
          event(out, chunk(tag, "lo"))
          out.write("data: [DONE]\n\n".getBytes(UTF_8))
          out.close()
        else json(exchange, 200, completion.replace("{model}", tag))
      case other =>
        json(
          exchange,
          404,
          s"""{"error":{"message":"model \\"${other.getOrElse(
              ""
            )}\\" not found, try pulling it first","type":"api_error"}}"""
        )

  private def keep(tag: String, requested: Option[Duration]): Unit =
    requested.orElse(defaultKeepAlive) match
      case None                    => loaded.put(tag, Long.MaxValue): Unit
      case Some(d) if d.isNegative => loaded.put(tag, Long.MaxValue): Unit
      case Some(d) if d.isZero     => loaded.remove(tag): Unit
      case Some(d)                 => loaded.put(tag, System.currentTimeMillis() + d.toMillis): Unit

  private def json(exchange: HttpExchange, status: Int, text: String): Unit =
    val bytes = text.getBytes(UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)

  private def line(out: OutputStream, text: String): Unit =
    out.write((text + "\n").getBytes(UTF_8))
    out.flush()

  private def event(out: OutputStream, text: String): Unit =
    out.write(s"data: $text\n\n".getBytes(UTF_8))
    out.flush()

object FakeOllama:

  /** One request as the stand-in received it; header names lower-cased. */
  final case class Recorded(
      method: String,
      path: String,
      headers: Map[String, String],
      body: String
  ):
    def header(name: String): Option[String] = headers.get(name.toLowerCase)

  private final case class Model(tag: String, digest: String, capabilities: Vector[String])

  /**
   * Starts on an ephemeral loopback port. `defaultKeepAlive` is Ollama's `OLLAMA_KEEP_ALIVE`: how
   * long a model a request names stays loaded when the request says nothing; `None` is for ever.
   */
  def start(defaultKeepAlive: Option[Duration] = Some(Duration.ofMinutes(5))): FakeOllama =
    serve(
      HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0),
      defaultKeepAlive
    )

  /**
   * Starts behind mutual TLS, as a service meets the model server through its door: `context`
   * serves the listener's certificate, and a client without a certificate `context` trusts fails
   * the handshake. `url` is then `https://localhost:<port>`, the name a test certificate carries.
   */
  def startTls(
      context: SSLContext,
      defaultKeepAlive: Option[Duration] = Some(Duration.ofMinutes(5))
  ): FakeOllama =
    val server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.setHttpsConfigurator(
      new HttpsConfigurator(context):
        override def configure(parameters: HttpsParameters): Unit =
          val ssl = context.getDefaultSSLParameters
          ssl.setNeedClientAuth(true)
          parameters.setSSLParameters(ssl)
    )
    serve(server, defaultKeepAlive)

  private def serve(server: HttpServer, defaultKeepAlive: Option[Duration]): FakeOllama =
    val fake = new FakeOllama(server, defaultKeepAlive)
    server.createContext("/", exchange => fake.handle(exchange))
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    server.start()
    fake

  /** A digest of the shape Ollama reports, the same for the same tag. */
  def digestOf(tag: String): String =
    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(tag.getBytes(UTF_8))
    sha.map(b => f"$b%02x").mkString

  val DefaultCompletion: String =
    """{"id":"chatcmpl-1","object":"chat.completion","created":1760713445,"model":"{model}",""" +
      """"choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}],""" +
      """"usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}"""

  private val ModelPattern     = """"model"\s*:\s*"([^"]*)"""".r
  private val KeepAlivePattern = """"keep_alive"\s*:\s*(-?[0-9.]+|"[^"]*")""".r
  private val StreamPattern    = """"stream"\s*:\s*true""".r

  private def modelOf(body: String): Option[String] =
    ModelPattern.findFirstMatchIn(body).map(_.group(1))

  /** Ollama's reading: a number of seconds, or a Go duration such as `5m`, `30s` or `-1`. */
  private[testollama] def keepAliveOf(body: String): Option[Duration] =
    KeepAlivePattern
      .findFirstMatchIn(body)
      .flatMap(m => parseKeepAlive(m.group(1).stripPrefix("\"").stripSuffix("\"")))

  private[testollama] def parseKeepAlive(text: String): Option[Duration] =
    val unit = """(-?[0-9.]+)(ms|s|m|h)?""".r
    text.trim match
      case unit(n, null) => n.toDoubleOption.map(s => Duration.ofMillis((s * 1000).toLong))
      case unit(n, u) =>
        n.toDoubleOption.map { v =>
          val millis = u match
            case "ms" => v
            case "s"  => v * 1000
            case "m"  => v * 60000
            case _    => v * 3600000
          Duration.ofMillis(millis.toLong)
        }
      case _ => None

  private def escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

  private def chunk(model: String, content: String): String =
    s"""{"id":"chatcmpl-1","object":"chat.completion.chunk","model":"$model",""" +
      s""""choices":[{"index":0,"delta":{"role":"assistant","content":"$content"},"finish_reason":null}]}"""
