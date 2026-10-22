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
        "kms key    keys/ankka",
        "model server: none"
      ).mkString("\n")
    )
  }

  test("a member who is not shown the key sees no key line") {
    answer =
      """{"platformVersion":"0.7.0","cloud":{"provider":"gcp","account":"acct","location":"europe-west2"}}"""
    assert(!run()._2.contains("kms key"))
  }

  test("an installation with no cloud provider says none, and no model server") {
    answer = """{"platformVersion":"0.7.0"}"""
    assertEquals(run()._2.trim, "platform   0.7.0\nprovider   none\nmodel server: none")
  }

  test("an installation that says where it keeps its secrets prints the backend and the record") {
    answer =
      """{"platformVersion":"0.7.0","secrets":{"backend":"postgres","recordRetention":"365d","auditLog":"unknown"}}"""
    assertEquals(
      run()._2.trim,
      Vector(
        "platform   0.7.0",
        "provider   none",
        "secrets    postgres",
        "records    365d",
        "access log unknown",
        "model server: none"
      ).mkString("\n")
    )
  }

  test("the broker and the longest retention follow the secrets, each value or not stated") {
    answer =
      """{"platformVersion":"0.12.0","secrets":{"backend":"postgres","recordRetention":"365d","auditLog":"unknown"},"broker":{"nodes":3,"defaultCopies":3,"minInSync":2},"retention":{"longest":"30d"}}"""
    assertEquals(
      run()._2.trim,
      Vector(
        "platform   0.12.0",
        "provider   none",
        "secrets    postgres",
        "records    365d",
        "access log unknown",
        "broker     nodes 3, copies 3, in sync 2",
        "retention  longest 30d",
        "model server: none"
      ).mkString("\n")
    )
    answer =
      """{"platformVersion":"0.12.0","broker":{"defaultCopies":1,"minInSync":1},"retention":{"unbounded":true}}"""
    val out = run()._2
    assert(out.contains("broker     nodes not stated, copies 1, in sync 1"), out)
    assert(out.contains("retention  unbounded"), out)
    answer = """{"platformVersion":"0.12.0","retention":{}}"""
    assert(run()._2.contains("retention  not stated"), "neither a bound nor unbounded")
  }

  test("--json prints the wire type") {
    answer = """{"platformVersion":"0.7.0"}"""
    val (code, out) = run("-o", "json")
    assertEquals(code, 0, out)
    assertEquals(out.trim, """{"platformVersion":"0.7.0"}""")
  }
