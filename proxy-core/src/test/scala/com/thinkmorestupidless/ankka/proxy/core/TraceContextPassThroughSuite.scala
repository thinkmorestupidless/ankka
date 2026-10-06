package com.thinkmorestupidless.ankka.proxy.core

import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.net.{InetAddress, URI}
import scala.concurrent.duration.*

/**
 * The proxy passes a trace context on as it was given, on all three of its paths, and adds none: a
 * trace crosses a web-hosted service unbroken, with no span of the proxy's own. Nothing in the
 * proxy reads `traceparent`, so this holds only for as long as no filter of its headers is
 * tightened to drop it — which is what these cases are for.
 */
class TraceContextPassThroughSuite extends munit.FunSuite:

  override def munitTimeout: Duration = 60.seconds

  private val loopback = InetAddress.getByName("127.0.0.1")
  private val context  = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
  private val state    = "vendor=opaque"

  private final class Fixture:
    val process: StandInProcess = StandInProcess().start()
    val orders: StandInProcess  = StandInProcess().start()
    val engine = ProxyEngine(
      ProxySettings(
        project = "shop",
        service = "web",
        port = 0,
        processPort = process.port,
        probePort = 0,
        callingPort = 0,
        mounts = Vector("/api/orders" -> "orders"),
        drainTimeout = 1.second
      ),
      Transport.plain(loopback),
      probeAddress = loopback,
      locator = (project, service) =>
        Option.when(project == "shop" && service == "orders")(
          Located(URI.create(s"http://127.0.0.1:${orders.port}"))
        )
    )
    engine.start()
    val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    def send(url: String): Int =
      client
        .send(
          HttpRequest
            .newBuilder(URI.create(url))
            .header("traceparent", context)
            .header("tracestate", state)
            .GET()
            .build(),
          BodyHandlers.ofString()
        )
        .statusCode

    def close(): Unit =
      engine.stop()
      Vector(process, orders).foreach(_.stop())

  private def withFixture[A](body: Fixture => A): A =
    val f = Fixture()
    try body(f)
    finally f.close()

  private def only(process: StandInProcess) = process.requests match
    case Vector(one) => one
    case other       => fail(s"one request expected: $other")

  test("a request to the process keeps its trace context") {
    withFixture { f =>
      assertEquals(f.send(s"http://127.0.0.1:${f.engine.ports.public}/page"), 200)
      assertEquals(only(f.process).all("traceparent"), Vector(context))
      assertEquals(only(f.process).all("tracestate"), Vector(state))
    }
  }

  test("a request that passes through a web-hosted service keeps its trace context") {
    withFixture { f =>
      assertEquals(f.send(s"http://127.0.0.1:${f.engine.ports.public}/api/orders/o1"), 200)
      assertEquals(only(f.orders).all("traceparent"), Vector(context))
      assertEquals(only(f.orders).all("tracestate"), Vector(state))
      assertEquals(f.process.requests, Vector.empty)
    }
  }

  test(
    "a call the process of a web-hosted service makes keeps the trace context the process gave it"
  ) {
    withFixture { f =>
      assertEquals(f.send(f.engine.callingUrl + "/orders/o1"), 200)
      assertEquals(only(f.orders).all("traceparent"), Vector(context))
      assertEquals(only(f.orders).all("tracestate"), Vector(state))
    }
  }
