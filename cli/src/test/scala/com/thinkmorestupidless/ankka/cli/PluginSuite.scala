package com.thinkmorestupidless.ankka.cli

import java.io.{BufferedReader, ByteArrayOutputStream, InputStreamReader, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.jdk.CollectionConverters.*

/**
 * `features/command-line/plugins.feature`: each of its scenarios is a test here, by its name. The
 * steps are a shell's (programs, signals, the path), so no `GherkinSuite` runs the file. The other
 * tests (an empty PATH, a hidden duplicate, `ankka plugin` alone, the ctrl-c wait) are the spec's
 * edge cases, named for them.
 *
 * Each test has its own directory holding `config.json` (through `-Dankka.config`) and `bin/`
 * (through `-Dankka.path`), so nothing here reads the developer's PATH or `~/.ankka`. A stub plugin
 * is a `/bin/sh` script that writes what it was given into `rec/`, which the test reads.
 *
 * The first run case leaves the test JVM ignoring SIGINT: the CLI installs that handler before it
 * starts a plugin and never restores it, because the CLI exits right after. sbt stops a forked JVM
 * with SIGTERM, so nothing here minds.
 */
class PluginSuite extends munit.FunSuite:

  private var dir: Path = null
  private def bin: Path = dir.resolve("bin")
  private def rec: Path = dir.resolve("rec")

  override def beforeEach(context: BeforeEach): Unit =
    dir = Files.createTempDirectory("ankka-plugins").toRealPath()
    Files.createDirectories(bin)
    Files.createDirectories(rec)
    sys.props("ankka.config") = dir.resolve("config.json").toString
    sys.props("ankka.path") = bin.toString

  // Clean up while the override is still in place: `Credentials.path` follows it, and without it
  // would name the developer's own credentials file.
  override def afterEach(context: AfterEach): Unit =
    Files
      .walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(p => Files.deleteIfExists(p): Unit)
    sys.props.remove("ankka.path")
    sys.props.remove("ankka.config"): Unit

  // ── helpers ───────────────────────────────────────────────────────────────

  /** A `/bin/sh` program named `name` in `in`; `$rec` is the directory it may write to. */
  private def stub(
      name: String,
      body: String = "",
      in: Path = null,
      executable: Boolean = true
  ): Path =
    val directory = Option(in).getOrElse(bin)
    Files.createDirectories(directory)
    val file = directory.resolve(name)
    Files.writeString(file, s"#!/bin/sh\nrec='$rec'\n$body\n")
    Files.setPosixFilePermissions(
      file,
      PosixFilePermissions.fromString(if executable then "rwxr-xr-x" else "rw-r--r--")
    )
    file

  /** A stub that records its arguments, one per line, and its `ANKKA_` environment. */
  private val recording =
    """for a in "$@"; do printf '%s\n' "$a"; done > "$rec/args"
      |env | grep '^ANKKA_' > "$rec/env" || true""".stripMargin

  private def run(args: String*): (Int, String, String) =
    val out  = ByteArrayOutputStream()
    val err  = ByteArrayOutputStream()
    val code = Main.run(
      args,
      PrintStream(out, true, StandardCharsets.UTF_8),
      PrintStream(err, true, StandardCharsets.UTF_8)
    )
    (code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))

  private def ran: Boolean = Files.exists(rec.resolve("args"))

  private def recordedArgs: Vector[String] =
    Files.readAllLines(rec.resolve("args")).asScala.toVector

  private def recordedEnv: Map[String, String] =
    Files
      .readAllLines(rec.resolve("env"))
      .asScala
      .map(line => line.takeWhile(_ != '=') -> line.dropWhile(_ != '=').drop(1))
      .toMap

  /**
   * The resolution reads the environment before the saved file, and a test cannot unset the
   * environment of its own JVM: a developer's exported `ANKKA_TOKEN` would answer for the saved
   * login. Such a test is skipped there, never passed.
   */
  private def assumeNoAnkkaEnvironment(): Unit =
    val set = Seq("ANKKA_URL", "ANKKA_TOKEN", "ANKKA_PROJECT", "ANKKA_CA").filter(sys.env.contains)
    assume(set.isEmpty, s"the test JVM's environment sets ${set.mkString(", ")}")

  private def saveLogin(url: String, issuer: String, accessToken: String, expiresAt: Long): Unit =
    Credentials.put(url, Login(issuer, "ankka-cli", "refresh-1", accessToken, expiresAt)): Unit

  private def later: Long = Instant.now().getEpochSecond + 3600

  // ── User story 1: a product's command runs as if it were built in ─────────

  test(
    "a word that names no built-in command runs the plugin of that name with the rest of the command line"
  ) {
    stub("ankka-acme", recording)
    val (code, _, err) = run("acme", "modules", "list")
    assertEquals(code, 0, err)
    assertEquals(recordedArgs, Vector("modules", "list"))
  }

  test("the plugin's exit code is the command's exit code") {
    stub("ankka-acme", "exit 3")
    assertEquals(run("acme", "modules", "list")._1, 3)
  }

  test("every flag after the plugin's name is the plugin's, untouched") {
    stub("ankka-acme", recording)
    val (code, out, err) =
      run("acme", "modules", "list", "-o", "json", "--project", "p-2", "--help")
    assertEquals(code, 0, err)
    assertEquals(
      recordedArgs,
      Vector("modules", "list", "-o", "json", "--project", "p-2", "--help")
    )
    assertEquals(out, "")
    assertEquals(err, "", "the CLI parsed nothing, so it has nothing to say")
  }

  test("a plugin ended by a signal ends the command with 128 and the signal's number") {
    stub("ankka-acme", "kill -TERM $$")
    val (code, _, err) = run("acme", "modules", "list")
    assertEquals(code, 143)
    assertEquals(err, "")
  }

  test("a word that names neither a built-in command nor a plugin is a usage error") {
    stub("ankka-acme", recording)
    val (code, _, err) = run("reports", "summary")
    assertEquals(code, 2)
    assert(err.contains("'reports' is not an ankka command"), err)
    assert(err.contains("no program 'ankka-reports' is on the path"), err)
    assert(err.contains("See 'ankka --help'."), err)
  }

  test("a plugin's name is matched exactly, whatever case the filesystem ignores") {
    stub("ankka-acme", recording)
    val (code, _, err) = run("Acme")
    assertEquals(code, 2, err)
    assert(err.contains("no program 'ankka-Acme'"), err)
    assert(!ran, "ankka-acme ran for the word Acme")
  }

  test("a program of a plugin's name that is not executable is passed over") {
    val early = dir.resolve("early")
    val first = stub("ankka-acme", "echo wrong > \"$rec/args\"", in = early, executable = false)
    stub("ankka-acme", recording)
    sys.props("ankka.path") = Seq(early, bin).mkString(java.io.File.pathSeparator)
    val (code, _, err) = run("acme", "modules", "list")
    assertEquals(code, 0, err)
    assertEquals(recordedArgs, Vector("modules", "list"))

    val (_, list, _) = run("plugin", "list")
    assert(
      list.linesIterator.exists(l => l.contains(first.toString) && l.endsWith("not executable")),
      list
    )
    assert(
      list.linesIterator.exists(l =>
        l.contains(bin.resolve("ankka-acme").toString) && l.endsWith("runs")
      ),
      list
    )
  }

  test("a plugin that cannot be run fails the command, naming the program") {
    val program = bin.resolve("ankka-broken")
    Files.writeString(program, "#!/nonexistent/interpreter\n")
    Files.setPosixFilePermissions(program, PosixFilePermissions.fromString("rwxr-xr-x"))
    val (code, _, err) = run("broken")
    assertEquals(code, 1)
    assert(err.contains("cannot run"), err)
    assert(err.contains(program.toString), err)
  }

  test("a plugin's name holds no directory") {
    stub("ankka-acme", recording)
    val (code, _, err) = run("../acme")
    assertEquals(code, 2)
    assert(err.contains("a plugin is named by a word, not a path: '../acme'"), err)
    assert(!ran)
  }

  test("an empty PATH finds no plugin, and says so") {
    sys.props("ankka.path") = ""
    val (code, _, err) = run("acme")
    assertEquals(code, 2)
    assert(err.contains("PATH is empty"), err)
  }

  test("a flag where the command's word belongs is a usage error, as it was") {
    stub("ankka-acme", recording)
    val (code, _, _) = run("-o", "json", "acme")
    assertEquals(code, 2)
    assert(!ran)
  }

  // ── User story 2: the plugin is given what the CLI had already resolved ───

  test("a plugin is given the settings a built-in command would have resolved") {
    assumeNoAnkkaEnvironment()
    val url = "https://cp.example.test"
    Settings.save(Settings(url = url, project = Some("p-1"), ca = Some("local-roots.pem"))): Unit
    saveLogin(url, "https://auth.example.test/realms/ankka", "token-1", later)
    stub("ankka-acme", recording)
    val (code, _, err) = run("acme")
    assertEquals(code, 0, err)
    val env = recordedEnv
    assertEquals(env.get("ANKKA_URL"), Some(url))
    assertEquals(env.get("ANKKA_TOKEN"), Some("token-1"))
    assertEquals(env.get("ANKKA_PROJECT"), Some("p-1"))
    val ca = Paths.get(env("ANKKA_CA"))
    assert(ca.isAbsolute && ca.endsWith("local-roots.pem"), ca.toString)
  }

  test("a plugin is told where the settings are saved") {
    stub("ankka-acme", recording)
    assertEquals(run("acme")._1, 0)
    assertEquals(recordedEnv.get("ANKKA_CONFIG"), Some(dir.resolve("config.json").toString))
  }

  test("a saved login that has run out is refreshed before the plugin runs") {
    assumeNoAnkkaEnvironment()
    val issuer = FakeIssuer()
    try
      Settings.save(Settings(url = issuer.url)): Unit
      saveLogin(issuer.url, issuer.issuer, "access-1", 0L)
      issuer.accessToken = "access-2"
      stub("ankka-acme", recording)
      val (code, _, err) = run("acme")
      assertEquals(code, 0, err)
      assertEquals(issuer.refreshes.get(), 1)
      assertEquals(recordedEnv.get("ANKKA_TOKEN"), Some("access-2"))
      assertEquals(Credentials.get(issuer.url).map(_.accessToken), Some("access-2"))
    finally issuer.stop()
  }

  test("a plugin is given no token when none can be resolved, and still runs") {
    assumeNoAnkkaEnvironment()
    stub("ankka-acme", recording)
    val (code, _, err) = run("acme")
    assertEquals(code, 0)
    assertEquals(err, "")
    assert(ran)
    assert(!recordedEnv.contains("ANKKA_TOKEN"), recordedEnv.toString)
    assertEquals(recordedEnv.get("ANKKA_URL"), Some(Settings.DefaultUrl))
  }

  test("a refused refresh leaves the plugin without a token, and says why") {
    assumeNoAnkkaEnvironment()
    val issuer = FakeIssuer()
    try
      issuer.refuseRefresh = true
      Settings.save(Settings(url = issuer.url)): Unit
      saveLogin(issuer.url, issuer.issuer, "access-1", 0L)
      stub("ankka-acme", recording)
      val (code, _, err) = run("acme")
      assertEquals(code, 0, err)
      assert(ran)
      assert(!recordedEnv.contains("ANKKA_TOKEN"), recordedEnv.toString)
      assert(err.startsWith("warning:"), err)
      assert(err.contains(issuer.url) && err.contains("rejected the login"), err)
      assert(err.contains("run 'ankka login'"), err)
    finally issuer.stop()
  }

  test(
    "an identity provider that cannot be reached leaves the plugin without a token, and says why"
  ) {
    assumeNoAnkkaEnvironment()
    val issuer = FakeIssuer()
    issuer.stop()
    Settings.save(Settings(url = issuer.url)): Unit
    saveLogin(issuer.url, issuer.issuer, "access-1", 0L)
    stub("ankka-acme", recording)
    val (code, _, err) = run("acme")
    assertEquals(code, 0, err)
    assert(ran)
    assert(!recordedEnv.contains("ANKKA_TOKEN"), recordedEnv.toString)
    assert(err.startsWith("warning:") && err.contains("run 'ankka login'"), err)
    assert(!err.contains("\tat "), s"a stack trace reached the person: $err")
  }

  test("the token reaches the plugin by its environment alone") {
    assumeNoAnkkaEnvironment()
    val token = "tok-7f3a9c1e5b"
    val url   = "https://cp.example.test"
    Settings.save(Settings(url = url)): Unit
    saveLogin(url, "https://auth.example.test/realms/ankka", token, later)
    // Records that it saw the token without writing the token anywhere.
    stub(
      "ankka-acme",
      s"""for a in "$$@"; do printf '%s\\n' "$$a"; done > "$$rec/args"
         |[ "$$ANKKA_TOKEN" = "$token" ] && echo yes > "$$rec/seen"""".stripMargin
    )
    val (code, out, err) = run("acme", "modules", "list")
    assertEquals(code, 0, err)
    assert(Files.exists(rec.resolve("seen")), "the plugin was not given the token")
    assert(!recordedArgs.exists(_.contains(token)))
    assert(!out.contains(token) && !err.contains(token))
    val holding = Files
      .walk(dir)
      .iterator
      .asScala
      .filter(Files.isRegularFile(_))
      .filterNot(
        _.startsWith(bin)
      ) // the stub carries the token to compare against; the test wrote it
      .filter(f => Files.readString(f).contains(token))
      .map(_.getFileName.toString)
      .toVector
    assertEquals(holding, Vector("credentials.json"))
  }

  // ── User story 3: a person can see what would run ──────────────────────────

  test("the plugin list names every plugin on the path and where it is") {
    stub("ankka-acme")
    stub("ankka-reports")
    val (code, table, _) = run("plugin", "list")
    assertEquals(code, 0)
    val lines = table.linesIterator.toVector
    assert(lines.head.split("\\s+").toSeq == Seq("PLUGIN", "PROGRAM", "STATUS"), table)
    Seq("acme", "reports").foreach { word =>
      val program = bin.resolve(s"ankka-$word").toString
      assert(
        lines.exists(l => l.startsWith(word) && l.contains(program) && l.endsWith("runs")),
        table
      )
    }

    val (_, json, _) = run("plugin", "list", "-o", "json")
    assertEquals(
      json.trim,
      s"""[{"word":"acme","program":"${bin.resolve("ankka-acme")}","status":"runs"},""" +
        s"""{"word":"reports","program":"${bin.resolve("ankka-reports")}","status":"runs"}]"""
    )
  }

  test("a plugin named like a built-in command is listed as shadowed and never run") {
    stub("ankka-services", recording)
    val (code, _, err) = run("services", "list", "-p", "p-1", "--url", "http://127.0.0.1:1")
    assertEquals(code, 1, err)
    assert(err.contains("127.0.0.1:1"), s"the built-in command did not answer: $err")
    assert(!ran, "ankka-services ran")

    val (_, table, _) = run("plugin", "list")
    assert(
      table.linesIterator.exists(l =>
        l.startsWith("services") && l.endsWith("shadowed by the built-in command")
      ),
      table
    )
    val (_, json, _) = run("plugin", "list", "-o", "json")
    assert(json.contains(""""word":"services"""") && json.contains(""""status":"shadowed""""), json)
  }

  test("two programs of one plugin's name: the first on the path runs, the other is hidden") {
    val early = dir.resolve("early")
    val first = stub("ankka-acme", in = early)
    val later = stub("ankka-acme")
    sys.props("ankka.path") = Seq(early, bin).mkString(java.io.File.pathSeparator)
    val (_, table, _) = run("plugin", "list")
    assert(table.linesIterator.exists(l => l.contains(first.toString) && l.endsWith("runs")), table)
    assert(
      table.linesIterator.exists(l =>
        l.contains(later.toString) && l.endsWith(s"hidden by $first")
      ),
      table
    )
    val (_, json, _) = run("plugin", "list", "-o", "json")
    assert(
      json.contains(s""""program":"$later","status":"hidden","hiddenBy":"$first""""),
      json
    )
  }

  test("no plugin on the path is said plainly") {
    assertEquals(run("plugin", "list")._2.trim, "no plugins on the path")
    assertEquals(run("plugin", "list", "-o", "json")._2.trim, "[]")
  }

  test("ankka plugin alone is a usage error") {
    assertEquals(run("plugin")._1, 2)
  }

  test("help names the plugins found beside the built-in commands") {
    stub("ankka-acme")
    stub("ankka-services")
    val appendix         = "Plugins (programs named ankka-<word> on the path):\n    acme"
    val (code, out, err) = run("--help")
    assertEquals(code, 2)
    assertEquals(out, "")
    assert(err.stripTrailing.endsWith(appendix), err)
    val listed = err.substring(err.indexOf("Plugins ("))
    assert(!listed.contains("services"), s"a shadowed program was offered as a plugin: $listed")
    assert(run()._3.stripTrailing.endsWith(appendix))
  }

  test("help with no plugin on the path has no plugins section") {
    assert(!run("--help")._3.contains("Plugins ("))
  }

  // ── The spec's ctrl-c edge case: the real CLI, as a process ────────────────

  test("the CLI waits for an interrupted plugin and reports its code") {
    assume(!sys.props("os.name").toLowerCase.contains("windows"))
    stub(
      "ankka-sleepy",
      """echo $$ > "$rec/pid"
        |trap 'kill $! 2>/dev/null; echo interrupted; exit 7' INT
        |echo started
        |sleep 20 &
        |wait $!""".stripMargin
    )
    val java = Paths.get(sys.props("java.home"), "bin", "java").toString
    val cli  = ProcessBuilder(
      java,
      s"-Dankka.config=${dir.resolve("config.json")}",
      s"-Dankka.path=$bin",
      "-cp",
      sys.props("java.class.path"),
      "com.thinkmorestupidless.ankka.cli.Main",
      "sleepy"
    ).redirectErrorStream(true).start()
    val lines  = LinkedBlockingQueue[String]()
    val reader = Thread(() =>
      BufferedReader(InputStreamReader(cli.getInputStream, StandardCharsets.UTF_8)).lines
        .forEach(lines.put)
    )
    reader.setDaemon(true)
    reader.start()
    def kill(pid: Long): Unit =
      ProcessBuilder("kill", "-INT", pid.toString).start().waitFor(): Unit
    try
      val started = Iterator
        .continually(lines.poll(30, TimeUnit.SECONDS))
        .takeWhile(_ != null)
        .find(_ == "started")
      assert(started.isDefined, "the plugin never started")
      val began = System.nanoTime()

      // The terminal sends ctrl-c to the CLI and the plugin both. The CLI alone first: it must
      // still be waiting afterwards, not gone with 130.
      kill(cli.pid())
      Thread.sleep(500)
      assert(cli.isAlive, s"the CLI ended on SIGINT with ${cli.exitValue()}")

      kill(Files.readString(rec.resolve("pid")).trim.toLong)
      assert(cli.waitFor(15, TimeUnit.SECONDS), "the CLI did not end after its plugin")
      assertEquals(cli.exitValue(), 7)
      assert(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(15))
      reader.join(5000)
      assert(lines.asScala.toSeq.contains("interrupted"), lines.asScala.mkString("\n"))
    finally cli.destroyForcibly(): Unit
  }
