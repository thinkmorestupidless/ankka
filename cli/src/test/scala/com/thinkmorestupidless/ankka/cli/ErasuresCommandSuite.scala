package com.thinkmorestupidless.ankka.cli

import com.sun.net.httpserver.HttpServer
import munit.FunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable

/**
 * `ankka projects erasures`: what each command sends, what it prints, and what it refuses before
 * sending anything — against a stand-in control plane that records each request and answers with
 * one applied erasure request.
 */
final class ErasuresCommandSuite extends FunSuite:

  private final case class Seen(method: String, path: String, body: String)

  private val seen                     = mutable.ArrayBuffer.empty[Seen]
  private var server: HttpServer       = null
  private var config: Path             = null
  private var previous: Option[String] = None

  private val applied =
    """{"id":"e1","projectId":"shop","subject":"customer/c1","state":"final",""" +
      """"askedBy":{"kind":"member","subject":"alice","display":"alice@example.test"},""" +
      """"askedAt":"2026-10-09T10:00:00Z","sequence":7,"keyDestroyedAt":"2026-10-09T10:00:01Z",""" +
      """"completions":[{"service":"cart","completedAt":"2026-10-09T10:00:02Z"}],""" +
      """"appliedAt":"2026-10-09T10:00:02Z","finalAt":"2026-10-09T10:00:02Z"}"""

  private def answer(method: String, path: String): String =
    if path.endsWith("/certificate") then
      s"""{"request":$applied,"issuedAt":"2026-10-09T11:00:00Z","statement":"Every personal field is unreadable."}"""
    else if method == "GET" && path.startsWith(
        "/projects/shop/erasures?"
      ) || path == "/projects/shop/erasures" && method == "GET"
    then s"[$applied]"
    else applied

  override def beforeAll(): Unit =
    config = Files.createTempFile("ankka-cli-erasures", ".json")
    Files.writeString(config, "{}")
    previous = sys.props.get("ankka.config")
    sys.props.put("ankka.config", config.toString): Unit
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext(
      "/",
      exchange =>
        val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        val path = exchange.getRequestURI.toString
        seen.synchronized(seen += Seen(exchange.getRequestMethod, path, body))
        val bytes = answer(exchange.getRequestMethod, path).getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.start()

  override def afterAll(): Unit =
    if server != null then server.stop(0)
    Files.deleteIfExists(config): Unit
    previous match
      case Some(p) => sys.props.put("ankka.config", p): Unit
      case None    => sys.props.remove("ankka.config"): Unit

  override def beforeEach(context: BeforeEach): Unit = seen.synchronized(seen.clear())

  private def run(args: String*): (Int, String, String) =
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val url = s"http://127.0.0.1:${server.getAddress.getPort}"
    val code = Main.run(
      Vector("projects", "erasures") ++ args ++ Vector("--url", url, "--token", "t", "-p", "shop"),
      PrintStream(out),
      PrintStream(err)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  test("request sends the subject, and prints the request with its state") {
    val (code, out, err) = run("request", "customer/c1", "--correlation", "ticket-41")
    assertEquals(code, 0, err)
    assertEquals(
      seen.toVector.map(r => (r.method, r.path)),
      Vector(("POST", "/projects/shop/erasures"))
    )
    assert(seen.head.body.contains("\"subject\":\"customer/c1\""), seen.head.body)
    assert(seen.head.body.contains("\"correlationId\":\"ticket-41\""), seen.head.body)
    assert(out.contains("e1") && out.contains("final"), out)
  }

  test("a hold without a reason, a date passed and a subject outside the rule are refused unsent") {
    for args <- Vector(
        Vector("request", "customer/c1", "--not-before", "2099-01-01"),
        Vector("request", "customer/c1", "--not-before", "2000-01-01", "--reason", "aml"),
        Vector("request", "customer c1")
      )
    do
      val (code, _, err) = run(args*)
      assertEquals(code, 1, s"${args.mkString(" ")}: $err")
    assertEquals(seen.toVector, Vector.empty, "nothing may be sent for a refused request")
  }

  test("list asks with its filters and prints a row per request") {
    val (code, out, err) = run("list", "--subject", "customer/c1", "--state", "final")
    assertEquals(code, 0, err)
    val path = seen.head.path
    assert(path.startsWith("/projects/shop/erasures?"), path)
    assert(path.contains("subject=customer") && path.contains("state=final"), path)
    assert(out.contains("customer/c1") && out.contains("e1"), out)
  }

  test("get prints each service's completion") {
    val (code, out, err) = run("get", "e1")
    assertEquals(code, 0, err)
    assertEquals(
      seen.toVector.map(r => (r.method, r.path)),
      Vector(("GET", "/projects/shop/erasures/e1"))
    )
    assert(out.contains("cart"), out)
  }

  test("certificate prints the statement and the request it is for") {
    val (code, out, err) = run("certificate", "e1")
    assertEquals(code, 0, err)
    assertEquals(seen.head.path, "/projects/shop/erasures/e1/certificate")
    assert(out.contains("Every personal field is unreadable.") && out.contains("customer/c1"), out)
  }

  test("the JSON format prints what the control plane answered") {
    val (code, out, err) = run("get", "e1", "-o", "json")
    assertEquals(code, 0, err)
    assert(out.contains("\"subject\"") && out.contains("customer/c1"), out)
  }
