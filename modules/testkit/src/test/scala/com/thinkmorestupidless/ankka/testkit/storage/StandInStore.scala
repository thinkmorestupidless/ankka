package com.thinkmorestupidless.ankka.testkit.storage

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.concurrent.TrieMap

/**
 * An object store on loopback that stands in for Garage's two faces (feature 055): the S3 API a
 * service keeps through, path-style and with any signature, and the address a public bucket is read
 * at with none. With `cdn` set the public face is also served under `/cdn/<bucket>/`, as a content
 * delivery network in front of public buckets would serve it. With `down` set it answers nothing
 * but 503, and counts what reached it, so a test can say that nothing did.
 *
 * Only a test's: it checks no signature and keeps every object in memory.
 */
final class StandInStore(down: Boolean = false):

  private val objects  = TrieMap.empty[String, (String, Array[Byte])]
  private val received = AtomicInteger()
  private val server   = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

  server.createContext(
    "/",
    (exchange: HttpExchange) =>
      try
        received.incrementAndGet(): Unit
        if down then answer(exchange, 503, "text/plain", "down".getBytes("UTF-8"))
        else serve(exchange)
      finally exchange.close()
  )
  server.start()

  def endpoint: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  /**
   * Where a public bucket's objects are read with no content delivery network: the store itself.
   */
  def publicBase(bucket: String): String = s"$endpoint/$bucket"

  /** Where they are read through the stand-in content delivery network. */
  def cdnBase(bucket: String): String = s"$endpoint/cdn/$bucket"

  /** How many requests reached the store. */
  def requests: Int = received.get

  def stop(): Unit = server.stop(0)

  private def serve(exchange: HttpExchange): Unit =
    val raw  = exchange.getRequestURI.getRawPath
    val path = java.net.URLDecoder.decode(raw.stripPrefix("/cdn"), "UTF-8").stripPrefix("/")
    exchange.getRequestMethod match
      case "PUT" =>
        val body = exchange.getRequestBody.readAllBytes()
        val kind = Option(exchange.getRequestHeaders.getFirst("Content-Type"))
          .getOrElse("application/octet-stream")
        objects.update(path, kind -> body)
        answer(exchange, 200, "text/plain", Array.emptyByteArray)
      case "GET" | "HEAD" =>
        objects.get(path) match
          case Some((kind, body)) =>
            answer(
              exchange,
              200,
              kind,
              if exchange.getRequestMethod == "HEAD" then Array.emptyByteArray else body
            )
          case None => answer(exchange, 404, "text/plain", "NoSuchKey".getBytes("UTF-8"))
      case _ => answer(exchange, 405, "text/plain", Array.emptyByteArray)

  private def answer(exchange: HttpExchange, status: Int, kind: String, body: Array[Byte]): Unit =
    exchange.getResponseHeaders.set("Content-Type", kind)
    exchange.sendResponseHeaders(status, if body.isEmpty then -1 else body.length.toLong)
    if body.nonEmpty then exchange.getResponseBody.write(body)
