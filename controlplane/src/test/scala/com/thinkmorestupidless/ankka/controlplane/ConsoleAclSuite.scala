package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.cli.console.{InvokeRequest, LocalSource}
import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import munit.FunSuite

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*

/** An endpoint that says no without a key, so a refusal is a real one and not a 404 in disguise. */
final class GatedEndpoint extends HttpEndpoint("/gated"):
  val acl: Acl = Acl.AllowIf(_.header("X-Api-Key").contains("let-me-in"))
  get("/")(() => "allowed")

/**
 * That the console cannot reach a handler an ordinary caller could not.
 *
 * This is FR-012, and the claim the design makes about it is unusually strong: the console is not
 * *checked* against an endpoint's `acl`, it is *subject* to it, because the invoke panel makes an
 * ordinary HTTP request to the service's own port and carries no privilege. There is no code path
 * from the console to a handler that a stranger could not also take.
 *
 * A claim like that is worth a test that could actually falsify it, so this compares the two
 * answers directly: the same request, through the console and through a plain HTTP client, against
 * an endpoint that refuses without a key. If the console ever gained a back door, these would stop
 * agreeing.
 *
 * It lives here because this is the one module whose test scope sees both `cli` (the console) and
 * `testkit` (a real service to point it at).
 */
final class ConsoleAclSuite extends FunSuite:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null
  private var registryDir: Path     = null
  private val client                = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    registryDir = Files.createTempDirectory("ankka-console-acl")
    sys.props.put("ankka.running.dir", registryDir.toString)
    testKit = AnkkaTestKit.start(
      Seq.empty,
      // Loopback and an ephemeral port, as every other HTTP suite does. `HttpServer.of` takes the
      // default 9000, which made this suite fail on any machine already serving that port — a
      // developer running `sbt shoppingCart/run` beside their tests, which is the documented way
      // to work on this repository. The same reason the cluster port defaults to random.
      Seq(HttpServer.at("127.0.0.1", 0)(_ => GatedEndpoint()))
    )

  override def afterAll(): Unit =
    if testKit != null then testKit.stop()
    sys.props.remove("ankka.running.dir"): Unit

  private def source = LocalSource(registryDir)

  private def serviceName =
    source.services().headOption.map(_.name).getOrElse(fail("the service did not announce itself"))

  /** The same request a stranger would make, with no involvement from the console at all. */
  private def directly(path: String, headers: Vector[(String, String)]): (Int, String) =
    val address = testKit.service.boundAddresses.headOption
      .getOrElse(fail("the service is not serving HTTP"))
    val builder = HttpRequest.newBuilder(URI.create(address + path)).GET()
    headers.foreach((k, v) => builder.header(k, v): Unit)
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    (response.statusCode(), response.body)

  /** The same request, made by the console on the developer's behalf. */
  private def viaConsole(path: String, headers: Vector[(String, String)]): (Int, String) =
    source
      .invoke(serviceName, InvokeRequest("GET", path, headers, None))
      .map(r => (r.status, r.body))
      .getOrElse(fail("the console could not reach the service"))

  test("an endpoint that refuses a stranger refuses the console identically") {
    val (directStatus, directBody)   = directly("/gated/", Vector.empty)
    val (consoleStatus, consoleBody) = viaConsole("/gated/", Vector.empty)

    assertEquals(directStatus, 403, directBody)
    assertEquals(
      consoleStatus,
      directStatus,
      "the console got a different answer from a plain client — it has a back door"
    )
    assertEquals(consoleBody, directBody, "and the refusal reads the same, byte for byte")
  }

  test("an endpoint that allows a caller allows the console on the same terms") {
    val key = Vector("X-Api-Key" -> "let-me-in")

    val (directStatus, directBody)   = directly("/gated/", key)
    val (consoleStatus, consoleBody) = viaConsole("/gated/", key)

    assertEquals(directStatus, 200, directBody)
    assertEquals(consoleStatus, 200, consoleBody)
    assertEquals(consoleBody, directBody)
  }

  test("the console cannot get in by leaving the key out and asking the platform instead") {
    // The observability endpoint is the console's privileged channel, and it deliberately offers
    // no way to reach a route. Confirm there is not one hiding under a plausible path.
    val observability = source
      .services()
      .headOption
      .map(_.observabilityAddress)
      .getOrElse(fail("no service"))

    for path <- Vector("/gated/", "/observability/invoke/gated", "/observability/routes/gated") do
      val response = client.send(
        HttpRequest.newBuilder(URI.create(observability + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      )
      assertNotEquals(
        response.statusCode(),
        200,
        s"the observability endpoint served '$path'; it must not be a way into the application"
      )
  }
