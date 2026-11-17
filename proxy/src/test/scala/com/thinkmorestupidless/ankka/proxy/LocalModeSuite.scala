package com.thinkmorestupidless.ankka.proxy

import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.proxy.core.ProxyEngine

import java.net.{InetAddress, InetSocketAddress, ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.{CompletableFuture, TimeUnit}

/**
 * The proxy's local mode (feature 066): what `ankka local up` runs for a web-hosted service, the
 * engine over plain HTTP with services where `ANKKA_PROXY_LOCAL_SERVICES` says.
 */
class LocalModeSuite extends munit.FunSuite:

  private val loopback = InetAddress.getLoopbackAddress
  private val http     = HttpClient.newHttpClient()

  private def freePort(): Int =
    val socket = ServerSocket(0, 0, loopback)
    try socket.getLocalPort
    finally socket.close()

  /** A service the proxy is told of: answers every path with its name and the path it was asked. */
  private def standIn(name: String): HttpServer =
    val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
    server.createContext(
      "/",
      exchange =>
        val body = s"$name ${exchange.getRequestURI.getPath}".getBytes(UTF_8)
        exchange.sendResponseHeaders(200, body.length.toLong)
        exchange.getResponseBody.write(body)
        exchange.close()
    )
    server.start()
    server

  private def get(url: String): (Int, String) =
    val answer = http.send(
      HttpRequest.newBuilder(URI.create(url)).build(),
      HttpResponse.BodyHandlers.ofString()
    )
    (answer.statusCode, answer.body)

  /** The proxy in local mode with `env`, its engine once started, stopped after `body`. */
  private def proxy(env: Map[String, String])(body: ProxyEngine => Unit): Unit =
    val engine = CompletableFuture[ProxyEngine]()
    val said   = Vector.newBuilder[String]
    val runner = Thread(() =>
      Main.run(
        Array.empty,
        env.get,
        report = m => said.synchronized(said += m): Unit,
        started = engine.complete(_): Unit,
        adjust = _.copy(probePort = 0, callingPort = 0)
      ): Unit
    )
    runner.setDaemon(true)
    runner.start()
    val started =
      try engine.get(20, TimeUnit.SECONDS)
      catch
        case e: Exception =>
          fail(s"the proxy did not start: ${said.synchronized(said.result())}", e)
    try body(started)
    finally
      started.stop()
      runner.interrupt()

  private def settings(port: Int, services: String): Map[String, String] = Map(
    "ANKKA_PROXY_LOCAL"          -> "on",
    "ANKKA_PROXY_SERVICE"        -> "web",
    "ANKKA_PROXY_PORT"           -> port.toString,
    "ANKKA_PROXY_PROCESS_PORT"   -> freePort().toString,
    "ANKKA_PROXY_MOUNTS"         -> "/api=orders",
    "ANKKA_PROXY_LOCAL_SERVICES" -> services
  )

  test("a mount reaches the service the variable names, its path removed") {
    val orders = standIn("orders")
    try
      val port = freePort()
      proxy(settings(port, s"orders=http://127.0.0.1:${orders.getAddress.getPort}")) { _ =>
        assertEquals(get(s"http://127.0.0.1:$port/api/carts"), (200, "orders /carts"))
      }
    finally orders.stop(0)
  }

  test("the process's call at the calling address reaches the service the variable names") {
    val orders = standIn("orders")
    try
      proxy(settings(freePort(), s"orders=http://127.0.0.1:${orders.getAddress.getPort}")) {
        engine =>
          val calling = engine.ports.calling
          assertEquals(get(s"http://127.0.0.1:$calling/orders/invoices"), (200, "orders /invoices"))
      }
    finally orders.stop(0)
  }

  test("a service the variable does not name is answered 503, naming it") {
    proxy(settings(freePort(), "")) { engine =>
      val (status, body) = get(s"http://127.0.0.1:${engine.ports.calling}/billing/x")
      assertEquals(status, 503)
      assert(body.contains("billing"), body)
    }
  }

  test("without the local mode the proxy still requires its certificate") {
    val missing = Files.createTempDirectory("no-certificate").resolve("absent")
    val said    = Vector.newBuilder[String]
    val code    =
      val before = sys.props.get(Main.ServiceDirectoryProperty)
      sys.props(Main.ServiceDirectoryProperty) = missing.toString
      try
        Main.run(
          Array.empty,
          (settings(freePort(), "") - "ANKKA_PROXY_LOCAL" + ("ANKKA_PROXY_PROJECT" -> "shop")).get,
          report = said += _
        )
      finally
        before.fold(sys.props.remove(Main.ServiceDirectoryProperty))(
          sys.props.put(Main.ServiceDirectoryProperty, _)
        ): Unit
    assertEquals(code, 1)
    assert(said.result().exists(_.contains("failed to start")), said.result())
  }
