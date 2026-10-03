package com.thinkmorestupidless.ankka.cli

import com.sun.net.httpserver.HttpServer
import munit.FunSuite

import java.io.{ByteArrayOutputStream, PrintStream, StringReader}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable

/**
 * `ankka projects secrets`: what each command sends, what it prints, and what it refuses before
 * sending anything — against a stand-in control plane that records each request.
 */
final class ProjectSecretsCommandSuite extends FunSuite:

  private final case class Seen(method: String, path: String, body: String)

  private val seen                     = mutable.ArrayBuffer.empty[Seen]
  private var server: HttpServer       = null
  private var config: Path             = null
  private var listing: String          = "[]"
  private var previous: Option[String] = None

  override def beforeAll(): Unit =
    // A config file this suite owns, so the developer's own is never read or written.
    config = Files.createTempFile("ankka-cli-secrets", ".json")
    Files.writeString(config, "{}")
    previous = sys.props.get("ankka.config")
    sys.props.put("ankka.config", config.toString): Unit
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext(
      "/",
      exchange =>
        val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        seen.synchronized(
          seen += Seen(exchange.getRequestMethod, exchange.getRequestURI.toString, body)
        )
        if exchange.getRequestMethod == "GET" then
          val bytes = listing.getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
        else exchange.sendResponseHeaders(204, -1)
        exchange.close()
    )
    server.start()

  override def afterAll(): Unit =
    if server != null then server.stop(0)
    // Back as it was before the property is touched, in a directory this suite owns.
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
      Vector("projects", "secrets") ++ args ++ Vector("--url", url, "--token", "t", "-p", "shop"),
      PrintStream(out),
      PrintStream(err)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  test("set sends one PUT with every entry, and prints the names and never a value") {
    val (code, out, _) = run("set", "checkout", "STRIPE_KEY=sk_live_1", "WEBHOOK_KEY=whsec_1")
    assertEquals(code, 0)
    val requests = seen.toVector
    assertEquals(
      requests.map(r => (r.method, r.path)),
      Vector(("PUT", "/projects/shop/secrets/checkout"))
    )
    assert(requests.head.body.contains("\"STRIPE_KEY\":\"sk_live_1\""), requests.head.body)
    assert(requests.head.body.contains("\"WEBHOOK_KEY\":\"whsec_1\""), requests.head.body)
    assert(out.contains("STRIPE_KEY, WEBHOOK_KEY"), out)
    assert(!out.contains("sk_live_1") && !out.contains("whsec_1"), out)
  }

  test("KEY=- reads that value from standard input, without its trailing newline") {
    val (code, _, _) =
      Console.withIn(StringReader("sk_from_stdin\n"))(run("set", "checkout", "STRIPE_KEY=-"))
    assertEquals(code, 0)
    assert(seen.head.body.contains("\"STRIPE_KEY\":\"sk_from_stdin\""), seen.head.body)
  }

  test("two KEY=- pairs, a pair with no '=', and a platform's name are refused without a request") {
    for args <- Vector(
        Vector("set", "checkout", "A=-", "B=-"),
        Vector("set", "checkout", "NOEQUALS"),
        Vector("set", "cart-secret-key", "key=x")
      )
    do
      val (code, _, err) = Console.withIn(StringReader(""))(run(args*))
      assertEquals(code, 1, s"${args.mkString(" ")}: $err")
      assert(err.startsWith("error:"), err)
    assertEquals(seen.toVector, Vector.empty, "nothing may be sent for a refused command")
  }

  test("unset sends the DELETE naming the entry") {
    val (code, out, _) = run("unset", "checkout", "WEBHOOK_KEY")
    assertEquals(code, 0)
    assertEquals(
      seen.toVector.map(r => (r.method, r.path)),
      Vector(("DELETE", "/projects/shop/secrets/checkout?entry=WEBHOOK_KEY"))
    )
    assert(out.contains("WEBHOOK_KEY"), out)
  }

  test("list prints names and entries, as a table or as the JSON it was given") {
    listing = """[{"name":"checkout","entries":["STRIPE_KEY","WEBHOOK_KEY"],"setBy":"Ada"}]"""
    val (code, table, _) = run("list")
    assertEquals(code, 0)
    assert(table.contains("NAME") && table.contains("ENTRIES"), table)
    assert(
      table.contains("checkout") && table.contains("STRIPE_KEY, WEBHOOK_KEY") && table.contains(
        "Ada"
      ),
      table
    )
    val (_, json, _) = run("list", "-o", "json")
    assert(json.contains("\"entries\":[\"STRIPE_KEY\",\"WEBHOOK_KEY\"]"), json)
  }
