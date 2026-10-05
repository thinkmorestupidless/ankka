package com.thinkmorestupidless.ankka.testkit

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import com.thinkmorestupidless.ankka.sdk.ServiceResponse

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.util.concurrent.{ConcurrentLinkedQueue, Executors}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Another service, played on loopback for a test of a whole service: it records every request it is
 * sent and answers as the test told it to.
 *
 * Plain HTTP on `127.0.0.1` and an ephemeral port, so a test can run beside anything else on the
 * machine. Give its `address` to the service under test as `ankka.local-services.<name>`
 * (`AnkkaTestKit.start(…, localServices = Map(name -> scripted.address))`). It is not an ankka
 * service and reads no caller: locally every caller is the local machine anyway.
 */
final class ScriptedService private (server: HttpServer):

  private val received = ConcurrentLinkedQueue[ScriptedService.Request]()
  private val answering = AtomicReference[ScriptedService.Request => ServiceResponse](_ =>
    ServiceResponse(200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8), Vector.empty)
  )
  private val failures = AtomicInteger(0)
  private val failWith = AtomicInteger(500)
  private val waiting  = AtomicReference[FiniteDuration](Duration.Zero)

  /** Where it answers: `http://127.0.0.1:<port>`. */
  val address: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  /** Sets how it answers from now on. */
  def answer(handler: ScriptedService.Request => ServiceResponse): Unit = answering.set(handler)

  /** Answers the next request with `status`, once, and then as before. */
  def failNext(status: Int = 500): Unit =
    failWith.set(status)
    failures.incrementAndGet(): Unit

  /** Waits this long before every answer. */
  def delay(duration: FiniteDuration): Unit = waiting.set(duration)

  /** Every request received, in order. */
  def requests: Vector[ScriptedService.Request] = received.asScala.toVector

  /** Forgets what it received. */
  def clear(): Unit = received.clear()

  def stop(): Unit = server.stop(0)

  private[testkit] def handle(exchange: HttpExchange): Unit =
    try
      val headers = exchange.getRequestHeaders.asScala.toVector
        .flatMap((name, values) => values.asScala.map(name.toLowerCase -> _))
      val request = ScriptedService.Request(
        exchange.getRequestMethod,
        exchange.getRequestURI.toString,
        headers,
        headers.collectFirst { case ("content-type", value) => value },
        exchange.getRequestBody.readAllBytes()
      )
      received.add(request)
      val pause = waiting.get
      if pause > Duration.Zero then Thread.sleep(pause.toMillis)
      val response =
        if failures.getAndUpdate(n => if n > 0 then n - 1 else 0) > 0 then
          ServiceResponse(failWith.get, "text/plain", "failed".getBytes, Vector.empty)
        else answering.get()(request)
      if response.contentType.nonEmpty then
        exchange.getResponseHeaders.add("Content-Type", response.contentType)
      response.headers.foreach((name, value) => exchange.getResponseHeaders.add(name, value))
      exchange.sendResponseHeaders(
        response.status,
        if response.body.isEmpty then -1L else response.body.length.toLong
      )
      if response.body.nonEmpty then exchange.getResponseBody.write(response.body)
    finally exchange.close()

object ScriptedService:

  /** One request as it arrived. Header names are lower case. */
  final case class Request(
      method: String,
      path: String,
      headers: Vector[(String, String)],
      contentType: Option[String],
      body: Array[Byte]
  ):
    def text: String = String(body, StandardCharsets.UTF_8)
    def header(name: String): Option[String] =
      headers.collectFirst { case (n, v) if n == name.toLowerCase => v }

  /** Starts one on loopback and an ephemeral port. */
  def start(): ScriptedService =
    val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    val scripted = new ScriptedService(server)
    server.createContext("/", exchange => scripted.handle(exchange))
    server.start()
    scripted
