package com.thinkmorestupidless.ankka.proxy.core

import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetAddress, ServerSocket, URI}
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

/**
 * The calling address, served by the engine in plain HTTP: a call is parsed, located and sent on,
 * and what the called service answered comes back as it answered it. The called services are
 * stand-ins on loopback, found through a locator the suite holds.
 */
class CallingAddressEngineSuite extends munit.FunSuite:

  override def munitTimeout: Duration = 60.seconds

  private val loopback = InetAddress.getByName("127.0.0.1")

  private final class Fixture(responseTimeout: FiniteDuration = 60.seconds):
    val process: StandInProcess  = StandInProcess().start()
    val cart: StandInProcess     = StandInProcess(200.millis).start()
    val invoices: StandInProcess = StandInProcess().start()

    /** A port nothing listens on: a service that is found and does not answer. */
    val closedPort: Int =
      val socket = new ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()
    val located = Map(
      ("shop", "cart")        -> Located(URI.create(s"http://127.0.0.1:${cart.port}")),
      ("billing", "invoices") -> Located(URI.create(s"http://127.0.0.1:${invoices.port}")),
      ("shop", "gone")        -> Located(URI.create(s"http://127.0.0.1:$closedPort"))
    )
    val engine = ProxyEngine(
      ProxySettings(
        project = "shop",
        service = "web",
        port = 0,
        processPort = process.port,
        probePort = 0,
        callingPort = 0,
        responseTimeout = responseTimeout,
        drainTimeout = 1.second
      ),
      Transport.plain(loopback),
      probeAddress = loopback,
      locator = (project, service) => located.get((project, service))
    )
    engine.start()
    val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    def call(
        target: String,
        method: String = "GET",
        body: Option[String] = None,
        headers: (String, String)*
    ): HttpResponse[String] =
      val builder = HttpRequest.newBuilder(URI.create(engine.callingUrl + target))
      headers.foreach((n, v) => builder.header(n, v))
      builder.method(method, body.fold(BodyPublishers.noBody())(BodyPublishers.ofString))
      client.send(builder.build(), BodyHandlers.ofString())

    def close(): Unit =
      engine.stop()
      Vector(process, cart, invoices).foreach(_.stop())

  private def withFixture[A](timeout: FiniteDuration = 60.seconds)(body: Fixture => A): A =
    val f = Fixture(timeout)
    try body(f)
    finally f.close()

  private def marked(response: HttpResponse[?]): Boolean =
    response.headers.firstValue(Answers.MarkerName).toScala.contains(Answers.MarkerValue)

  test("the calling address is bound to the loopback address and nothing else") {
    withFixture() { f =>
      assert(f.engine.callingAddress.isLoopbackAddress, f.engine.callingAddress.toString)
      assertEquals(f.engine.callingUrl, s"http://127.0.0.1:${f.engine.ports.calling}")
    }
  }

  test("a call reaches the service with its method, path, query, headers and body") {
    withFixture() { f =>
      val response = f.call(
        "/cart/body?x=1",
        method = "PUT",
        body = Some("""{"item":"tea"}"""),
        "Content-Type" -> "application/json",
        "X-Custom"     -> "kept"
      )
      assertEquals(response.statusCode, 200)
      assertEquals(response.body, """{"item":"tea"}""")
      val received = f.cart.requests match
        case Vector(one) => one
        case other       => fail(s"one request expected: $other")
      assertEquals(received.method, "PUT")
      assertEquals(received.target, "/body?x=1")
      assertEquals(received.header("content-type"), Some("application/json"))
      assertEquals(received.header("x-custom"), Some("kept"))
      assertEquals(received.header("host"), Some(s"127.0.0.1:${f.cart.port}"))
      assertEquals(f.process.requests, Vector.empty)
    }
  }

  test("nothing the process says about who called reaches the service") {
    withFixture() { f =>
      f.call("/cart/x", headers = "X-Ankka-Caller" -> "service shop/orders")
      assertEquals(f.cart.requests.head.header("x-ankka-caller"), None)
    }
  }

  test("a service of another project is reached by project") {
    withFixture() { f =>
      assertEquals(f.call("/invoices.billing/issue").statusCode, 200)
      assertEquals(f.invoices.requests.map(_.target), Vector("/issue"))
      assertEquals(f.cart.requests, Vector.empty)
    }
  }

  test("a refusal and a failure come back as the service gave them, not as the proxy's") {
    withFixture() { f =>
      for status <- Vector(403, 404, 500) do
        val response = f.call(s"/cart/status/$status")
        assertEquals(response.statusCode, status)
        assertEquals(response.body, s"status $status")
        assertEquals(response.headers.firstValue("X-Flavour").toScala, Some("tea"))
        assert(!marked(response), s"$status was marked as the proxy's")
    }
  }

  test("a redirect comes back to the process, and is not followed") {
    withFixture() { f =>
      val response = f.call("/cart/redirect")
      assertEquals(response.statusCode, 303)
      assertEquals(response.headers.firstValue("Location").toScala, Some("/status/200"))
      assert(!marked(response), "the redirect was marked as the proxy's")
      assertEquals(f.cart.requests.map(_.target), Vector("/redirect"))
    }
  }

  test("a call the service answered, or read and closed on, is not sent again") {
    withFixture() { f =>
      assertEquals(f.call("/cart/status/503", "POST", Some("x")).statusCode, 503)
      assertEquals(f.call("/cart/close", "POST", Some("x")).statusCode, 502)
      assertEquals(f.cart.requests.map(_.target), Vector("/status/503", "/close"))
      // A GET or HEAD whose connection closed before any answer is the one exception: the client
      // sends it once more on a new connection, as for a pooled connection the service had closed.
      assertEquals(f.call("/cart/close").statusCode, 502)
      assertEquals(f.cart.requests.count(_.target == "/close"), 3)
    }
  }

  test("a streamed answer comes back part by part") {
    withFixture() { f =>
      assertEquals(f.call("/cart/stream").body, "part 1\npart 2\npart 3\n")
    }
  }

  test("a call to a service nobody can find is answered, and nothing is sent") {
    withFixture() { f =>
      val response = f.call("/ledger/entries")
      assertEquals(response.statusCode, 503)
      assert(marked(response))
      assertEquals(response.body, """{"error":"no service 'ledger'"}""")
      val other = f.call("/ledger.billing/entries")
      assertEquals(other.body, """{"error":"no service 'ledger' in the project 'billing'"}""")
      assertEquals(f.cart.requests ++ f.invoices.requests ++ f.process.requests, Vector.empty)
    }
  }

  test("a call that names no service is 400, and nothing is sent") {
    withFixture() { f =>
      val response = f.call("/")
      assertEquals(response.statusCode, 400)
      assert(marked(response))
      assertEquals(
        response.body,
        s"""{"error":"a call names a service: ${f.engine.callingUrl}/<service>/<path>"}"""
      )
      assertEquals(f.cart.requests ++ f.process.requests, Vector.empty)
    }
  }

  test("a service found and not listening is 503") {
    withFixture() { f =>
      val response = f.call("/gone/x")
      assertEquals(response.statusCode, 503)
      assert(marked(response))
      assertEquals(response.body, """{"error":"the service shop/gone is not listening"}""")
    }
  }

  test("a service that closes the connection before answering is 502") {
    withFixture() { f =>
      val response = f.call("/cart/close")
      assertEquals(response.statusCode, 502)
      assert(marked(response))
      assertEquals(
        response.body,
        """{"error":"the service shop/cart closed the connection before answering"}"""
      )
    }
  }

  test("a service that does not answer in time is 504") {
    withFixture(timeout = 500.millis) { f =>
      val response = f.call("/cart/stall")
      assertEquals(response.statusCode, 504)
      assert(marked(response))
      assertEquals(
        response.body,
        """{"error":"the service shop/cart did not answer within 500 milliseconds"}"""
      )
    }
  }
