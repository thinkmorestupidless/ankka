package com.thinkmorestupidless.ankka.operator

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * The operator's health, as the kubelet's probes ask for it: `GET /healthz` answers 200 `ok` when
 * `problems` is empty and 503 with one problem a line when it is not. The liveness probe restarts
 * an operator whose informers have stopped or whose queues no longer take work, which nothing else
 * would; the process otherwise goes on running, watching and logging, and reconciles nothing.
 *
 * The JDK's own server on a platform thread of its own, not a virtual one: what it reports includes
 * every virtual thread being stuck, and a check that needed one to answer could not say so.
 */
final class HealthCheck private (server: HttpServer):
  def port: Int     = server.getAddress.getPort
  def close(): Unit = server.stop(0)

object HealthCheck:

  val Path = "/healthz"

  /** Serves `problems` on `port` (0 for any free one) until closed. */
  def serve(port: Int, problems: () => Vector[String]): HealthCheck =
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext(
      Path,
      (exchange: HttpExchange) =>
        try
          val found =
            try problems()
            catch case scala.util.control.NonFatal(e) => Vector(s"the check failed: $e")
          val (status, text) =
            if found.isEmpty then (200, "ok\n") else (503, found.mkString("", "\n", "\n"))
          val body = text.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.set("Content-Type", "text/plain; charset=utf-8")
          exchange.sendResponseHeaders(status, body.length.toLong)
          exchange.getResponseBody.write(body)
        finally exchange.close()
    ): Unit
    server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor { r =>
      val thread = new Thread(r, "ankka-operator-health")
      thread.setDaemon(true)
      thread
    })
    server.start()
    new HealthCheck(server)
