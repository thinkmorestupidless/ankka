package com.thinkmorestupidless.ankka.cli

import com.sun.net.httpserver.HttpServer
import munit.FunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * `ankka installation` (feature 044): what it asks the control plane and what it prints, against a
 * stand-in that answers `GET /installation` with whatever the case sets.
 */
final class InstallationCommandSuite extends FunSuite:

  private var server: HttpServer       = null
  private var config: Path             = null
  private var previous: Option[String] = None
  @volatile private var answer: String = "{}"
  @volatile private var asked: String  = ""

  override def beforeAll(): Unit =
    config = Files.createTempFile("ankka-cli-installation", ".json")
    Files.writeString(config, "{}")
    previous = sys.props.get("ankka.config")
    sys.props.put("ankka.config", config.toString): Unit
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
    server.createContext(
      "/",
      exchange =>
        asked = s"${exchange.getRequestMethod} ${exchange.getRequestURI}"
        val bytes = answer.getBytes(StandardCharsets.UTF_8)
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

  private def run(args: String*): (Int, String) =
    val out = ByteArrayOutputStream()
    val url = s"http://127.0.0.1:${server.getAddress.getPort}"
    val code = Main.run(
      Vector("installation") ++ args ++ Vector("--url", url, "--token", "t"),
      PrintStream(out),
      PrintStream(ByteArrayOutputStream())
    )
    (code, out.toString(StandardCharsets.UTF_8))

  test("an installation with a cloud provider prints it, its account, location and key") {
    answer =
      """{"platformVersion":"0.7.0","cloud":{"provider":"gcp","account":"acct","location":"europe-west2","kmsKey":"keys/ankka"}}"""
    val (code, out) = run()
    assertEquals(code, 0, out)
    assertEquals(asked, "GET /installation")
    assertEquals(
      out.trim,
      Vector(
        "platform   0.7.0",
        "provider   gcp",
        "account    acct",
        "location   europe-west2",
        "kms key    keys/ankka"
      ).mkString("\n")
    )
  }

  test("a member who is not shown the key sees no key line") {
    answer =
      """{"platformVersion":"0.7.0","cloud":{"provider":"gcp","account":"acct","location":"europe-west2"}}"""
    assert(!run()._2.contains("kms key"))
  }

  test("an installation with no cloud provider says none, and nothing more") {
    answer = """{"platformVersion":"0.7.0"}"""
    assertEquals(run()._2.trim, "platform   0.7.0\nprovider   none")
  }

  test("--json prints the wire type") {
    answer = """{"platformVersion":"0.7.0"}"""
    val (code, out) = run("-o", "json")
    assertEquals(code, 0, out)
    assertEquals(out.trim, """{"platformVersion":"0.7.0"}""")
  }
