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
    server.setExecutor(null)
    server.start()

    if port != preferred then out.println(s"port $preferred was busy")
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
      case Some(server) => (server, port)
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
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length.toLong)
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
