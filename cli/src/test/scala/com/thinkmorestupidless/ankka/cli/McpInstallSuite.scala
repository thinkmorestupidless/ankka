package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.mcp.{Json, McpInstall}
import com.thinkmorestupidless.ankka.cli.mcp.McpInstall.{Client, Outcome, Request, Scope}

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}

/**
 * `ankka mcp install`. Everything is written under temporary directories: Claude Desktop's
 * configuration through `-Dankka.claude.desktop.config`, and Claude Code's through a scripted
 * runner in place of the real `claude`, which would write the developer's own.
 */
class McpInstallSuite extends munit.FunSuite:

  private def parse(text: String): Json = Json.parse(text).fold(e => fail(e), identity)

  private def servers(file: Path): Json =
    parse(Files.readString(file))("mcpServers").getOrElse(fail(s"$file has no mcpServers"))

  /** A directory holding an executable called `name`, to stand for a PATH entry. */
  private def binDir(name: String): Path =
    val dir = Files.createTempDirectory("bin")
    val exe = Files.createFile(
      dir.resolve(name),
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x"))
    )
    assert(Files.isExecutable(exe))
    dir

  private def run(args: String*): (Int, String, String) =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val code = Main.run(args, PrintStream(out), PrintStream(err))
    (code, out.toString, err.toString)

  // ── merging ───────────────────────────────────────────────────────────────

  private val entry = McpInstall.ProjectLaunch.entry

  test("a missing configuration gains one server and nothing else") {
    val (merged, outcome) = McpInstall.merge(None, entry, force = false)
    assertEquals(outcome, Outcome.Added)
    assertEquals(merged.render, """{"mcpServers":{"ankka":{"command":"ankka","args":["mcp"]}}}""")
  }

  test("other servers and other settings are kept, in the order they were written") {
    val existing = parse(
      """{"theme":"dark","mcpServers":{"github":{"command":"gh-mcp"}},"globalShortcut":"Cmd+K"}"""
    )
    val (merged, outcome) = McpInstall.merge(Some(existing), entry, force = false)
    assertEquals(outcome, Outcome.Added)
    assertEquals(
      merged.render,
      """{"theme":"dark","mcpServers":{"github":{"command":"gh-mcp"},"ankka":{"command":"ankka","args":["mcp"]}},"globalShortcut":"Cmd+K"}"""
    )
  }

  test("the same entry, with its keys in another order, is already there") {
    val existing = parse("""{"mcpServers":{"ankka":{"args":["mcp"],"command":"ankka"}}}""")
    assertEquals(McpInstall.merge(Some(existing), entry, force = false)._2, Outcome.Unchanged)
  }

  test("a different entry named ankka is left alone, and replaced in place only when forced") {
    val theirs = """{"command":"/opt/ankka-dev/bin/ankka","args":["mcp","--url","https://x"]}"""
    val existing =
      parse(s"""{"mcpServers":{"a":{"command":"a"},"ankka":$theirs,"z":{"command":"z"}}}""")

    val (kept, outcome) = McpInstall.merge(Some(existing), entry, force = false)
    assertEquals(outcome, Outcome.Kept(parse(theirs)))
    assertEquals(kept, existing)

    val (replaced, forced) = McpInstall.merge(Some(existing), entry, force = true)
    assertEquals(forced, Outcome.Replaced)
    assertEquals(
      replaced.render,
      """{"mcpServers":{"a":{"command":"a"},"ankka":{"command":"ankka","args":["mcp"]},"z":{"command":"z"}}}"""
    )
  }

  test("a file that is not JSON is refused and not rewritten") {
    val file = Files.createTempDirectory("mcp").resolve(".mcp.json")
    Files.writeString(file, "{ not json")
    val error = intercept[ApiError](McpInstall.install(file, entry, force = true, dryRun = false))
    assert(error.detail.contains("left as it is"), error.detail)
    assertEquals(Files.readString(file), "{ not json")
  }

  // ── finding ankka ─────────────────────────────────────────────────────────

  test("the ankka a client starts is --command, else the first on PATH, else this process") {
    val first  = binDir("ankka")
    val second = binDir("ankka")
    val path =
      Seq(Files.createTempDirectory("empty"), first, second).mkString(java.io.File.pathSeparator)
    val self = () => Some(Path.of("/self/ankka"))
    assertEquals(McpInstall.locate(None, path, self), first.resolve("ankka"))
    assertEquals(McpInstall.locate(Some("/given/ankka"), path, self), Path.of("/given/ankka"))
    assertEquals(McpInstall.locate(None, "", self), Path.of("/self/ankka"))
    val error = intercept[ApiError](McpInstall.locate(None, "", () => None))
    assert(error.detail.contains("--command"), error.detail)
  }

  test("Claude Desktop is given JAVA_HOME for the JVM build, and nothing extra for a native one") {
    val jvm = McpInstall.desktopLaunch(Path.of("/usr/local/bin/ankka"), native = false)
    assertEquals(jvm.env, Vector("JAVA_HOME" -> sys.props("java.home")))
    assertEquals(
      McpInstall.desktopLaunch(Path.of("/usr/local/bin/ankka"), native = true).env,
      Vector.empty
    )
    assertEquals(
      jvm.entry.render,
      s"""{"command":"/usr/local/bin/ankka","args":["mcp"],"env":{"JAVA_HOME":"${sys.props(
          "java.home"
        )}"}}"""
    )
  }

  test("Claude Desktop's configuration is where it reads it, on macOS and Windows only") {
    assertEquals(
      McpInstall.desktopConfig("Mac OS X", "/Users/a", None),
      Path.of("/Users/a/Library/Application Support/Claude/claude_desktop_config.json")
    )
    assertEquals(
      McpInstall
        .desktopConfig("Windows 11", "C:\\Users\\a", Some("C:\\Users\\a\\AppData\\Roaming")),
      Path.of("C:\\Users\\a\\AppData\\Roaming", "Claude", "claude_desktop_config.json")
    )
    val error = intercept[ApiError](McpInstall.desktopConfig("Linux", "/home/a", None))
    assert(error.detail.contains("macOS and Windows"), error.detail)
  }

  // ── Claude Code ───────────────────────────────────────────────────────────

  private val withClaude =
    Seq(binDir("claude"), binDir("ankka")).mkString(java.io.File.pathSeparator)

  /** A runner that answers from a script and records what it was asked to run. */
  private final class Scripted(answers: PartialFunction[Vector[String], (Int, String)]):
    val ran = Vector.newBuilder[Vector[String]]
    val runner: McpInstall.Runner = argv =>
      ran += argv
      answers.applyOrElse(
        argv,
        (a: Vector[String]) => fail(s"unexpected command: ${a.mkString(" ")}")
      )

  test("with no server named ankka, Claude Code is asked to add one for this user") {
    val script = Scripted {
      case McpInstall.claudeGet                                   => (1, "No MCP server found")
      case argv if argv.take(3) == Vector("claude", "mcp", "add") => (0, "Added")
    }
    val said = McpInstall.perform(Request(), script.runner, withClaude)
    val add  = script.ran.result().last
    assertEquals(add.take(5), Vector("claude", "mcp", "add", "--scope", "user"))
    assertEquals(
      add.drop(5),
      Vector("ankka", "--", withClaude.split(java.io.File.pathSeparator)(1) + "/ankka", "mcp")
    )
    assert(said.startsWith("registered ankka with Claude Code"), said)
  }

  test("an existing server named ankka is left alone, and replaced only when forced") {
    val kept = Scripted { case McpInstall.claudeGet => (0, "ankka:\n  Command: /old/ankka") }
    val said = McpInstall.perform(Request(), kept.runner, withClaude)
    assertEquals(kept.ran.result(), Vector(McpInstall.claudeGet))
    assert(said.contains("left as it is") && said.contains("/old/ankka"), said)

    val forced = Scripted {
      case McpInstall.claudeGet                                   => (0, "ankka: ...")
      case McpInstall.claudeRemove                                => (0, "Removed")
      case argv if argv.take(3) == Vector("claude", "mcp", "add") => (0, "Added")
    }
    McpInstall.perform(Request(force = true), forced.runner, withClaude): Unit
    assertEquals(forced.ran.result().map(_.take(3)).map(_.last), Vector("get", "remove", "add"))
  }

  test("a dry run, or no claude on PATH, runs nothing and prints the command to paste") {
    val none = Scripted(PartialFunction.empty)
    val dry  = McpInstall.perform(Request(dryRun = true), none.runner, withClaude)
    assert(dry.startsWith("would run: claude mcp add --scope user ankka -- "), dry)

    val onlyAnkka = binDir("ankka").toString
    val absent    = McpInstall.perform(Request(), none.runner, onlyAnkka)
    assert(absent.contains("is not on PATH, so nothing was changed"), absent)
    assert(absent.contains(s"claude mcp add --scope user ankka -- $onlyAnkka/ankka mcp"), absent)
    assert(absent.contains("/plugin install ankka@ankka"), absent)
    assertEquals(none.ran.result(), Vector.empty)
  }

  test("a failing claude is reported with what it printed") {
    val script = Scripted {
      case McpInstall.claudeGet => (1, "")
      case _                    => (2, "boom: config locked")
    }
    val error = intercept[ApiError](McpInstall.perform(Request(), script.runner, withClaude))
    assert(error.detail.contains("boom: config locked"), error.detail)
  }

  // ── the command, end to end ───────────────────────────────────────────────

  test("`ankka mcp install --scope project` writes a .mcp.json naming ankka, once") {
    val dir              = Files.createTempDirectory("project")
    val (code, out, err) = run("mcp", "install", "--scope", "project", "--dir", dir.toString)
    assertEquals(code, 0, err)
    assert(out.contains("added ankka to") && out.contains("Commit it"), out)
    assertEquals(
      servers(dir.resolve(".mcp.json")).render,
      """{"ankka":{"command":"ankka","args":["mcp"]}}"""
    )

    val (again, twice, _) = run("mcp", "install", "--scope", "project", "--dir", dir.toString)
    assertEquals(again, 0)
    assert(twice.contains("already starts ankka mcp; nothing to change"), twice)
  }

  test("`--dry-run` shows the file it would write and writes nothing") {
    val dir = Files.createTempDirectory("project")
    val (code, out, _) =
      run("mcp", "install", "--scope", "project", "--dir", dir.toString, "--dry-run")
    assertEquals(code, 0)
    assert(out.contains("would write") && out.contains("\"ankka\""), out)
    assert(!Files.exists(dir.resolve(".mcp.json")))
  }

  test("`--client desktop` merges into Claude Desktop's configuration, keeping its servers") {
    val config = Files.createTempDirectory("desktop").resolve("claude_desktop_config.json")
    Files.writeString(config, """{"mcpServers":{"github":{"command":"gh-mcp"}}}""")
    sys.props("ankka.claude.desktop.config") = config.toString
    try
      val (code, out, err) =
        run("mcp", "install", "--client", "desktop", "--command", "/opt/ankka/bin/ankka")
      assertEquals(code, 0, err)
      assert(out.contains("Quit and reopen Claude Desktop"), out)
      val written = servers(config)
      assertEquals(written("github").map(_.render), Some("""{"command":"gh-mcp"}"""))
      val ankka = written("ankka").getOrElse(fail(s"no ankka in ${written.render}"))
      assertEquals(ankka.string("command"), Some("/opt/ankka/bin/ankka"))
      assertEquals(ankka("args").map(_.render), Some("""["mcp"]"""))
    finally
      val _ = sys.props.remove("ankka.claude.desktop.config")
  }

  test("Claude Desktop has no project scope, and an unknown client or scope is a usage error") {
    val (desktop, _, err) = run("mcp", "install", "--client", "desktop", "--scope", "project")
    assertEquals(desktop, 1)
    assert(err.contains("--scope project is for Claude Code"), err)
    assertEquals(run("mcp", "install", "--client", "vim")._1, 2)
    assertEquals(run("mcp", "install", "--scope", "team")._1, 2)
  }
