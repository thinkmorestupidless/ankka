package com.thinkmorestupidless.ankka.proxy.core

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.InetSocketAddress
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A stand-in for a web-hosted service's process, on loopback at a port it chose.
 *
 * It records every request it is given, and answers by path: `/stream` writes three parts with
 * `interval` between them, and remembers when it wrote the last; `/stall` never answers; `/close`
 * closes the connection without answering; `/body` answers the body it was sent; `/status/<n>`
 * answers that status with a header of its own; anything else echoes the method, the target and the
 * headers, one per line with the name in lower case, in the form `Echo.parse` reads.
 */
final class StandInProcess(interval: FiniteDuration = 1.second):

  private val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
  private val recorded = new ConcurrentLinkedQueue[StandInProcess.Received]()
  private val stopped  = new CountDownLatch(1)

  /** When `/stream`'s last part was written, as `System.nanoTime`; 0 until it was. */
  @volatile var lastPartWrittenAt: Long = 0L

  /** Where `/call` calls services: what the process is told as `ANKKA_SERVICES_URL`. */
  @volatile var servicesUrl: String = ""

  /** When each of `/stream`'s parts was written, as `System.nanoTime`, in order. */
  @volatile var partsWrittenAt: Vector[Long] = Vector.empty

  server.createContext("/", exchange => handle(exchange))
  server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())

  def start(): StandInProcess =
    server.start()
    this

  def port: Int = server.getAddress.getPort

  def stop(): Unit =
    stopped.countDown()
    server.stop(0)

  /** Every request given so far, in order. */
  def requests: Vector[StandInProcess.Received] = recorded.asScala.toVector

  /** When `/trickle`'s first body byte arrived, as `System.nanoTime`; 0 until it has. */
  @volatile var firstBodyByteAt: Long = 0L

  private def handle(exchange: HttpExchange): Unit =
    val body =
      if exchange.getRequestURI.getPath != "/trickle" then exchange.getRequestBody.readAllBytes()
      else
        // The first byte on its own, so a test can see it arrive before the rest is sent.
        val in    = exchange.getRequestBody
        val first = in.read()
        firstBodyByteAt = System.nanoTime()
        if first < 0 then Array.emptyByteArray else first.toByte +: in.readAllBytes()
    val headers = exchange.getRequestHeaders.asScala.toVector.flatMap { (name, values) =>
      values.asScala.map(name.toLowerCase -> _)
    }
    val target = StandInProcess.target(exchange)
    recorded.add(StandInProcess.Received(exchange.getRequestMethod, target, headers, body))
    try
      exchange.getRequestURI.getPath match
        case "/stream" =>
          exchange.getResponseHeaders.add("Content-Type", "text/plain")
          exchange.sendResponseHeaders(200, 0L)
          val out = exchange.getResponseBody
          for part <- 1 to 3 do
            if part > 1 then Thread.sleep(interval.toMillis)
            out.write(s"part $part\n".getBytes("UTF-8"))
            out.flush()
            partsWrittenAt = partsWrittenAt :+ System.nanoTime()
            if part == 3 then lastPartWrittenAt = System.nanoTime()
          out.close()
        case "/stall" =>
          stopped.await()
        case "/call" =>
          // What the process reads from a service: `?path=` at the calling address, its status and
          // body returned as `<status> <body>`.
          val path = Option(exchange.getRequestURI.getRawQuery)
            .flatMap(_.split("&").collectFirst { case q if q.startsWith("path=") => q.drop(5) })
            .map(java.net.URLDecoder.decode(_, "UTF-8"))
            .getOrElse("/")
          val called = java.net.http.HttpClient
            .newHttpClient()
            .send(
              java.net.http.HttpRequest.newBuilder(java.net.URI.create(servicesUrl + path)).build(),
              java.net.http.HttpResponse.BodyHandlers.ofString()
            )
          val text = s"${called.statusCode} ${called.body}".getBytes("UTF-8")
          exchange.sendResponseHeaders(200, text.length.toLong)
          exchange.getResponseBody.write(text)
          exchange.getResponseBody.close()
        case "/close" =>
          exchange.close()
        case "/redirect" =>
          // A redirect to a path this stand-in serves, so following it would be seen as a request.
          exchange.getResponseHeaders.add("Location", "/status/200")
          exchange.sendResponseHeaders(303, -1L)
        case "/body" | "/trickle" =>
          exchange.getResponseHeaders.add("Content-Type", "application/octet-stream")
          exchange.sendResponseHeaders(200, if body.isEmpty then -1L else body.length.toLong)
          if body.nonEmpty then exchange.getResponseBody.write(body)
          exchange.getResponseBody.close()
        case path if path.startsWith("/status/") =>
          val status = path.stripPrefix("/status/").toInt
          val text   = s"status $status".getBytes("UTF-8")
          exchange.getResponseHeaders.add("X-Flavour", "tea")
          exchange.getResponseHeaders.add("Content-Type", "text/plain")
          if exchange.getRequestMethod == "HEAD" then exchange.sendResponseHeaders(status, -1L)
          else
            exchange.sendResponseHeaders(status, text.length.toLong)
            exchange.getResponseBody.write(text)
            exchange.getResponseBody.close()
        case _ =>
          val text = StandInProcess.Echo.render(exchange.getRequestMethod, target, headers)
          exchange.getResponseHeaders.add("Content-Type", "text/plain")
          exchange.sendResponseHeaders(200, text.length.toLong)
          exchange.getResponseBody.write(text)
          exchange.getResponseBody.close()
    finally exchange.close()

object StandInProcess:

  /** One request as the process saw it; header names in lower case. */
  final case class Received(
      method: String,
      target: String,
      headers: Vector[(String, String)],
      body: Array[Byte]
  ):
    def header(name: String): Option[String] =
      headers.collectFirst { case (n, v) if n == name.toLowerCase => v }
    def all(name: String): Vector[String] =
      headers.collect { case (n, v) if n == name.toLowerCase => v }

  /** `METHOD /target?query` on the first line, then `name: value` per header, names sorted. */
  object Echo:
    def render(method: String, target: String, headers: Vector[(String, String)]): Array[Byte] =
      val lines = s"$method $target" +: headers.sorted.map((n, v) => s"$n: $v")
      lines.mkString("", "\n", "\n").getBytes("UTF-8")

    def parse(text: String): Received =
      val lines = text.split("\n").toVector.filter(_.nonEmpty)
      val first = lines.head.split(" ", 2)
      val headers = lines.tail.map { line =>
        val parts = line.split(": ", 2)
        parts(0) -> parts.lift(1).getOrElse("")
      }
      Received(first(0), first(1), headers, Array.empty)

  private def target(exchange: HttpExchange): String =
    val uri = exchange.getRequestURI
    uri.getRawPath + Option(uri.getRawQuery).map("?" + _).getOrElse("")
