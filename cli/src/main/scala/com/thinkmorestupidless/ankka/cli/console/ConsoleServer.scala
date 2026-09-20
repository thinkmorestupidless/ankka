package com.thinkmorestupidless.ankka.cli.console

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.io.PrintStream
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets

/**
 * `ankka local console` — the web UI, served from the CLI itself.
 *
 * Uses the JDK's own HTTP server and hand-written assets read out of the CLI jar, so the module
 * whose defining property is carrying no actor system, no database driver and no Kubernetes client
 * gains no dependency to serve a web page either. There is no bundler, no lockfile and no build
 * step: five panels do not justify a frontend toolchain in a repository that has none.
 *
 * Talks to a `Source` and never to the registry directly — see that trait for why.
 */
final class ConsoleServer private (server: HttpServer, val port: Int):
  def address: String = s"http://localhost:$port"
  def stop(): Unit    = server.stop(0)

object ConsoleServer:

  /** Akka's port. Imitating it costs nothing and means knowing one console means knowing both. */
  val DefaultPort = 9889

  /**
   * Binds the first free port at or after `preferred`.
   *
   * A busy port is not a failure: a developer who left a console running in another terminal should
   * get a second one and be told where it is, not an error telling them to go and find it.
   */
  def start(source: Source, preferred: Int = DefaultPort, out: PrintStream): ConsoleServer =
    val (server, port) = bind(preferred, attempts = 20)
    val handler        = Handler(source)

    server.createContext("/", exchange => handler.asset(exchange))
    server.createContext("/api/services", exchange => handler.services(exchange))
    server.createContext("/api/service/", exchange => handler.service(exchange))
    server.createContext("/api/traces/", exchange => handler.traces(exchange))
    server.createContext("/api/invoke/", exchange => handler.invoke(exchange))
    server.createContext("/api/session/", exchange => handler.session(exchange))
    server.createContext("/api/invoke-stream/", exchange => handler.invokeStream(exchange))
    server.createContext("/api/query/", exchange => handler.query(exchange))
    server.setExecutor(null)
    server.start()

    // Compared against what was asked for, so "any free port" (0) is never reported as busy.
    if preferred != 0 && port != preferred then out.println(s"port $preferred was busy")
    out.println(s"Local console: http://localhost:$port")
    new ConsoleServer(server, port)

  private def bind(preferred: Int, attempts: Int): (HttpServer, Int) =
    var port      = preferred
    var remaining = attempts
    var bound     = Option.empty[HttpServer]
    while bound.isEmpty && remaining > 0 do
      try
        bound = Some(HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, port), 0))
      catch
        case _: Throwable =>
          port += 1
          remaining -= 1
    bound match
      // The port the server actually bound, never the one we asked for: a request for 0 means
      // "any free port", and reporting 0 back would make `address` read http://localhost:0.
      case Some(server) => (server, server.getAddress.getPort)
      case None         =>
        // Every port in the range was taken. Fall back to whatever the OS will give us rather
        // than refusing to start.
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
        (server, server.getAddress.getPort)

  private final class Handler(source: Source):

    def services(exchange: HttpExchange): Unit =
      val json = source
        .services()
        .map { s =>
          s"""{"name":${quote(s.name)},"instances":[{"id":${quote(s.instanceId)},""" +
            s""""startedAt":${quote(s.startedAt)}}]}"""
        }
        .mkString("[", ",", "]")
      json200(exchange, s"""{"services":$json}""")

    def service(exchange: HttpExchange): Unit =
      val name = exchange.getRequestURI.getPath.stripPrefix("/api/service/")
      source.service(name) match
        case Some(body) => json200(exchange, body)
        case None       => notFound(exchange)

    def traces(exchange: HttpExchange): Unit =
      val path = exchange.getRequestURI.getPath.stripPrefix("/api/traces/")
      path.split("/").toList match
        case name :: Nil =>
          source.traces(name) match
            case Some(body) => json200(exchange, body)
            case None       => notFound(exchange)
        case name :: traceId :: Nil =>
          source.trace(name, traceId) match
            case Some(body) => json200(exchange, body)
            case None       => notFound(exchange)
        case _ => notFound(exchange)

    /**
     * Sends the invoke panel's request to the service's own port and hands back what came.
     *
     * A refusal is a result, not an error: a 403 from an endpoint's ACL is the console showing the
     * platform working, and is displayed exactly as a 200 is.
     */
    def invoke(exchange: HttpExchange): Unit =
      val name = exchange.getRequestURI.getPath.stripPrefix("/api/invoke/")
      val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val request = InvokeRequest(
        method = str(body, "method").getOrElse("GET"),
        path = str(body, "path").getOrElse("/"),
        headers = str(body, "contentType") match
          case Some(ct) if ct.nonEmpty => Vector("content-type" -> ct)
          case _                       => Vector.empty,
        body = str(body, "body").filter(_.nonEmpty)
      )
      source.invoke(name, request) match
        case None => notFound(exchange)
        case Some(response) =>
          json200(
            exchange,
            s"""{"status":${response.status},"body":${quote(response.body)},""" +
              s""""headers":${response.headers
                  .map((k, v) => s"""{"name":${quote(k)},"value":${quote(v)}}""")
                  .mkString("[", ",", "]")}}"""
          )

    /**
     * One string field from the panel's own JSON.
     *
     * The console writes this object and this reads it, four fields, all strings. A parser would be
     * a dependency in the module whose defining property is carrying almost none.
     */
    private def str(json: String, key: String): Option[String] =
      val marker = s""""$key":""""
      json.indexOf(marker) match
        case -1 => None
        case at =>
          val from    = at + marker.length
          val builder = StringBuilder()
          var i       = from
          var done    = false
          while !done && i < json.length do
            json.charAt(i) match
              case '\\' if i + 1 < json.length =>
                json.charAt(i + 1) match
                  case 'n'   => builder.append('\n')
                  case 't'   => builder.append('\t')
                  case 'r'   => builder.append('\r')
                  case '"'   => builder.append('"')
                  case '\\'  => builder.append('\\')
                  case other => builder.append(other)
                i += 2
              case '"' => done = true
              case c =>
                builder.append(c)
                i += 1
          Some(builder.toString)

    /**
     * The streaming invoke, forwarded chunk by chunk.
     *
     * `sendResponseHeaders(200, 0)` means chunked: no length is known, because the point is that
     * the end has not happened yet. Each line is written and flushed as it arrives, so the browser
     * can render it — a buffered proxy in the middle would defeat the whole exercise just as
     * thoroughly as a buffered client.
     */
    def invokeStream(exchange: HttpExchange): Unit =
      val name = exchange.getRequestURI.getPath.stripPrefix("/api/invoke-stream/")
      val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val request = InvokeRequest(
        method = str(body, "method").getOrElse("GET"),
        path = str(body, "path").getOrElse("/"),
        headers = str(body, "contentType") match
          case Some(ct) if ct.nonEmpty => Vector("content-type" -> ct)
          case _                       => Vector.empty,
        body = str(body, "body").filter(_.nonEmpty)
      )

      exchange.getResponseHeaders.add("Content-Type", "text/plain; charset=utf-8")
      exchange.sendResponseHeaders(200, 0)
      val out = exchange.getResponseBody
      try
        val reached = source.invokeStream(
          name,
          request,
          chunk =>
            out.write(chunk.getBytes(StandardCharsets.UTF_8))
            out.flush()
        )
        if !reached then out.write("(no such service)".getBytes(StandardCharsets.UTF_8)): Unit
      catch
        // The browser closing the tab shows up here. Not a fault — the reader stopped reading.
        case _: Throwable => ()
      finally out.close()

    def session(exchange: HttpExchange): Unit =
      exchange.getRequestURI.getPath.stripPrefix("/api/session/").split("/").toList match
        case name :: sessionId :: Nil =>
          source.session(name, sessionId) match
            case Some(body) => json200(exchange, body)
            case None       => notFound(exchange)
        case _ => notFound(exchange)

    def query(exchange: HttpExchange): Unit =
      exchange.getRequestURI.getPath.stripPrefix("/api/query/").split("/").toList match
        case name :: component :: entityId :: method :: Nil =>
          source.query(name, component, entityId, method) match
            // The service's status travels. A command refused with 405 and a reason must not
            // arrive at the panel as a bare 404 — the reason is the whole value of the refusal.
            case Some(response) => json(exchange, response.status, response.body)
            case None           => notFound(exchange)
        case _ => notFound(exchange)

    /** The UI itself, read out of the jar. */
    def asset(exchange: HttpExchange): Unit =
      val path = exchange.getRequestURI.getPath match
        case "/" | "" => "index.html"
        case other    => other.stripPrefix("/")

      Option(getClass.getClassLoader.getResourceAsStream(s"console/$path")) match
        case None => notFound(exchange)
        case Some(stream) =>
          val bytes =
            try stream.readAllBytes()
            finally stream.close()
          exchange.getResponseHeaders.add("Content-Type", contentType(path))
          exchange.sendResponseHeaders(200, bytes.length.toLong)
          val out = exchange.getResponseBody
          try out.write(bytes)
          finally out.close()

    private def contentType(path: String): String =
      if path.endsWith(".html") then "text/html; charset=utf-8"
      else if path.endsWith(".css") then "text/css; charset=utf-8"
      else if path.endsWith(".js") then "text/javascript; charset=utf-8"
      else "application/octet-stream"

  private def json200(exchange: HttpExchange, body: String): Unit =
    json(exchange, 200, body)

  /** A JSON body under the status the service itself gave, refusals included. */
  private def json(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val out = exchange.getResponseBody
    try out.write(bytes)
    finally out.close()

  private def notFound(exchange: HttpExchange): Unit =
    val bytes = """{"error":"not found"}""".getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(404, bytes.length.toLong)
    val out = exchange.getResponseBody
    try out.write(bytes)
    finally out.close()

  private def quote(value: String): String =
    val escaped = value.flatMap {
      case '"'                 => "\\\""
      case '\\'                => "\\\\"
      case '\n'                => "\\n"
      case c if c.toInt < 0x20 => f"\\u${c.toInt}%04x"
      case c                   => c.toString
    }
    s""""$escaped""""
