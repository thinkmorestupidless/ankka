package com.thinkmorestupidless.ankka.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * `ankka context-map check` and `render` through the real command tree (feature 062): silent and 0
 * on a true map, one line per problem and 1 otherwise, and `render` writing what `check` holds the
 * rendering to. The problems themselves are `core`'s `ContextMapSuite`'s.
 */
class ContextMapCommandSuite extends munit.FunSuite:

  private val Map =
    """{"project": "shop",
      | "services": [
      |   {"name": "orders", "owns": "orders", "boundary": {"criterion": "ownership", "why": "its team"}},
      |   {"name": "billing", "owns": "invoices", "boundary": {"criterion": "ownership", "why": "finance"}}
      | ],
      | "crossings": [{"from": "orders", "to": "billing", "route": "GET /billing/{id}"}]
      |}""".stripMargin

  private def write(root: Path, path: String, text: String): Unit =
    val file = root.resolve(path)
    Files.createDirectories(file.getParent)
    Files.writeString(file, text, UTF_8): Unit

  private def project(): Path =
    val root = Files.createTempDirectory("context-map-command")
    write(root, "services/orders/service.json", """{"name":"orders","service":{"image":"o:1"}}""")
    write(root, "services/billing/service.json", """{"name":"billing","service":{"image":"b:1"}}""")
    write(root, "context-map.json", Map)
    root

  private def run(args: String*): (Int, String, String) =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val code = Main.run(args, PrintStream(out, true, UTF_8), PrintStream(err, true, UTF_8))
    (code, out.toString(UTF_8).trim, err.toString(UTF_8).trim)

  test("render writes CONTEXT-MAP.md, and check is then silent and succeeds") {
    val root               = project()
    val (rendered, out, _) = run("context-map", "render", "--dir", root.toString)
    assertEquals(rendered, 0)
    assertEquals(out, root.resolve("CONTEXT-MAP.md").toString)
    assertEquals(run("context-map", "check", "--dir", root.toString), (0, "", ""))
  }

  test("check fails with one line per problem") {
    val root = project()
    run("context-map", "render", "--dir", root.toString): Unit
    write(
      root,
      "services/shipping/service.json",
      """{"name":"shipping","service":{"image":"s:1"}}"""
    )
    val (code, _, err) = run("context-map", "check", "--dir", root.toString)
    assertEquals(code, 1)
    assertEquals(
      err,
      "shop has a service \"shipping\" (services/shipping/service.json) that the context map does not name"
    )
  }

  test("check outside a project says there is no context map") {
    val root           = Files.createTempDirectory("context-map-command")
    val (code, _, err) = run("context-map", "check", "--dir", root.toString)
    assertEquals(code, 1)
    assertEquals(
      err,
      s"$root holds no project started from the project template (no context-map.json)"
    )
  }

  test("render outside a project writes nothing") {
    val root = Files.createTempDirectory("context-map-command")
    assertEquals(run("context-map", "render", "--dir", root.toString)._1, 1)
    assert(!Files.exists(root.resolve("CONTEXT-MAP.md")))
  }
