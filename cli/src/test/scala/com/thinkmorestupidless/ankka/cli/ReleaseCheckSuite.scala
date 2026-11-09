package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.sun.net.httpserver.HttpServer

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicInteger

/**
 * `features/command-line/new-release-notice.feature`, User Story 1: each scenario is a test here,
 * by its name, an outline one test per row. The spec's edge cases follow, named for them.
 *
 * The command line is fixed at release 0.13.0 (`Main.run`'s `release`), because the build under
 * test is a development build, which never reads. Each test owns a directory through
 * `-Dankka.config`, where the remembered check lives, and a loopback server stands in for
 * `latest.json` through `-Dankka.latest.url`, counting its reads. A scenario whose Given is a known
 * latest release writes it as the remembered check, read an hour ago, so nothing races the
 * background read; the ones about the read itself use `ankka version`, which waits for it.
 */
class ReleaseCheckSuite extends munit.FunSuite:

  private val Notice16 = "ankka version 0.16.0 is available (you're currently using version 0.13.0)"

  private var dir: Path              = null
  private var server: HttpServer     = null
  private val reads                  = AtomicInteger(0)
  @volatile private var body: String = """{"version":"0.16.0","released":"2026-11-06"}"""
  @volatile private var status: Int  = 200
  @volatile private var delay: Long  = 0

  override def beforeEach(context: BeforeEach): Unit =
    dir = Files.createTempDirectory("ankka-release-check").toRealPath()
    sys.props("ankka.config") = dir.resolve("config.json").toString
    sys.props("ankka.terminal") = "true"
    reads.set(0)
    body = """{"version":"0.16.0","released":"2026-11-06"}"""
    status = 200
    delay = 0
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/latest.json",
      exchange =>
        reads.incrementAndGet(): Unit
        if delay > 0 then Thread.sleep(delay)
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    // One thread per exchange: the default runs them one at a time, so a slow read would hide the
    // next and every count of reads would be too low.
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
    server.start()
    sys.props("ankka.latest.url") = s"http://127.0.0.1:${server.getAddress.getPort}/latest.json"

  // Clean up while the override is still in place: `Credentials.path` follows it.
  override def afterEach(context: AfterEach): Unit =
    server.stop(0)
    dir.toFile.setWritable(true): Unit
    Files
      .walk(dir)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(p => Files.deleteIfExists(p): Unit)
    sys.props.remove("ankka.latest.url")
    sys.props.remove("ankka.terminal")
    sys.props.remove("ankka.config"): Unit

  // ── helpers ───────────────────────────────────────────────────────────────

  private final case class Ran(code: Int, out: String, err: String, millis: Long):
    def errLines: Vector[String] = err.linesIterator.toVector

  private def run(
      args: Seq[String],
      env: Map[String, String] = Map.empty,
      release: String = "0.13.0"
  ): Ran =
    val out     = ByteArrayOutputStream()
    val err     = ByteArrayOutputStream()
    val started = System.nanoTime()
    val code    = Main.run(
      args,
      PrintStream(out, true, StandardCharsets.UTF_8),
      PrintStream(err, true, StandardCharsets.UTF_8),
      env,
      release
    )
    Ran(
      code,
      out.toString(StandardCharsets.UTF_8),
      err.toString(StandardCharsets.UTF_8),
      (System.nanoTime() - started) / 1000000
    )

  private val command = Seq("config", "get")
  private val off     = Map("ANKKA_NO_UPDATE_CHECK" -> "1")

  private def known(
      version: String,
      checked: Duration = Duration.ofHours(1),
      noticed: Option[Duration] = None
  ): Unit =
    val now = Instant.now()
    ReleaseCheck.remember(
      RememberedCheck(
        Some(now.minus(checked)),
        Some(LatestRelease(version, "2026-11-06")),
        noticed.map(now.minus)
      )
    )

  private def rememberedFile: Option[RememberedCheck] =
    val file = dir.resolve("release-check.json")
    Option.when(Files.exists(file))(readFromString[RememberedCheck](Files.readString(file)))

  /** Long enough for a background read against loopback to have arrived, had one started. */
  private def settle(): Unit = Thread.sleep(300)

  private def assertNoNotice(ran: Ran): Unit =
    assert(!ran.err.contains("is available"), s"a notice was given: ${ran.err}")

  /** The command's output and exit code with the check turned off: what every run must match. */
  private def assertUnchanged(ran: Ran, args: Seq[String] = command): Unit =
    val baseline = run(args, off)
    assertEquals(ran.code, baseline.code)
    assertEquals(ran.out, baseline.out)

  // ── features/command-line/new-release-notice.feature, User Story 1 ────────

  test("a command of a command line older than the latest release ends with the notice") {
    known("0.16.0")
    val ran = run(command)
    assertEquals(ran.code, 0)
    assertEquals(ran.errLines.lastOption, Some(Notice16))
    assertEquals(ran.err.linesIterator.count(_.contains("is available")), 1)
    assertUnchanged(ran)
  }

  test("a command line at the latest release gives no notice") {
    known("0.16.0")
    assertNoNotice(run(command, release = "0.16.0"))
  }

  Seq("0.13.0", "0.12.4").foreach { latest =>
    test(s"a latest release that is not newer than the command line gives no notice ($latest)") {
      known(latest)
      assertNoNotice(run(command))
    }
  }

  test("the latest release is read at most once a day") {
    known("0.16.0", checked = Duration.ofHours(23))
    run(command): Unit
    settle()
    assertEquals(reads.get, 0)
  }

  test("the notice is given at most once a day") {
    known("0.16.0", noticed = Some(Duration.ofHours(23)))
    assertNoNotice(run(command))
  }

  test(
    "the version command reads the latest release at once and gives the notice whenever a newer release exists"
  ) {
    known("0.16.0", noticed = Some(Duration.ofHours(1)))
    body = """{"version":"0.17.0","released":"2026-11-20"}"""
    val ran = run(Seq("version"))
    assertEquals(reads.get, 1)
    assertEquals(ran.code, 0)
    assertEquals(
      ran.errLines.lastOption,
      Some("ankka version 0.17.0 is available (you're currently using version 0.13.0)")
    )
    assertEquals(rememberedFile.flatMap(_.latest).map(_.version), Some("0.17.0"))
  }

  test("a latest release that cannot be read changes nothing about the command") {
    status = 503
    body = "unavailable"
    val ran = run(Seq("version"))
    assertEquals(reads.get, 1)
    assertNoNotice(ran)
    assertEquals(ran.err, "")
    assertUnchanged(ran, Seq("version"))
    // Only the attempt is remembered, which holds the next one off for an hour.
    assertEquals(rememberedFile.flatMap(_.latest), None)
    assertEquals(rememberedFile.flatMap(_.checkedAt), None)
  }

  test("a command ends when its own work ends, not when the read of the latest release does") {
    delay = 5000
    val ran = run(command)
    assertEquals(ran.code, 0)
    assert(ran.millis < 1000, s"the command took ${ran.millis} ms")
    assertNoNotice(ran)
    // The read was started, and is still under way when the command has ended.
    settle()
    assertEquals(reads.get, 1)
  }

  Seq(
    "with JSON output" -> ((Seq("config", "get", "-o", "json"), Map.empty[String, String])),
    "under CI"         -> ((command, Map("CI" -> "true"))),
    "with the check turned off"            -> ((command, off)),
    "with its error output not a terminal" -> ((command, Map.empty[String, String]))
  ).foreach { case (where, (args, env)) =>
    test(
      s"the latest release is not read and no notice is given where a notice would be in the way ($where)"
    ) {
      // A day-old read, so both a read and a notice would otherwise be due.
      known("0.16.0", checked = Duration.ofHours(25))
      if where.contains("terminal") then sys.props("ankka.terminal") = "false"
      val ran = run(args, env)
      settle()
      assertEquals(reads.get, 0)
      assertNoNotice(ran)
    }
  }

  test("a development build of the command line never reads the latest release") {
    known("0.16.0", checked = Duration.ofHours(25))
    val ran = run(command, release = "0.16.0+3-abcdef12-SNAPSHOT")
    settle()
    assertEquals(reads.get, 0)
    assertNoNotice(ran)
  }

  // ── Not a scenario: the cases the scenarios rest on ───────────────────────

  test("a read a fast command did not wait for is not attempted again within the hour") {
    delay = 2000
    run(command): Unit
    run(command): Unit
    settle()
    assertEquals(reads.get, 1)
    assert(rememberedFile.flatMap(_.attemptedAt).isDefined)
    assertEquals(rememberedFile.flatMap(_.checkedAt), None)
  }

  test("an attempt an hour old that did not succeed is tried again") {
    val now = Instant.now()
    ReleaseCheck.remember(RememberedCheck(attemptedAt = Some(now.minus(Duration.ofMinutes(61)))))
    run(command): Unit
    settle()
    assertEquals(reads.get, 1)
  }

  test("a read that ends after its command is remembered by its own thread") {
    delay = 300
    val ran = run(command)
    assertNoNotice(ran)
    Thread.sleep(800)
    assertEquals(rememberedFile.flatMap(_.latest).map(_.version), Some("0.16.0"))
    // So the next command knows it without reading.
    assertEquals(run(command).errLines.lastOption, Some(Notice16))
    assertEquals(reads.get, 1)
  }

  test("a read that ends within the grace gives the notice on the command that started it") {
    run(Seq("version")): Unit // warm the HTTP client's classes, as a native image has them
    Files.delete(dir.resolve("release-check.json"))
    reads.set(0)
    assertEquals(run(command).errLines.lastOption, Some(Notice16))
    assertEquals(reads.get, 1)
  }

  test("a day-old read is read again, and a newer release it finds is remembered") {
    known("0.15.0", checked = Duration.ofHours(25))
    run(Seq("version")): Unit
    assertEquals(reads.get, 1)
    assertEquals(rememberedFile.flatMap(_.latest).map(_.version), Some("0.16.0"))
  }

  test("a notice given is remembered, and the next command gives none") {
    known("0.16.0")
    assertEquals(run(command).errLines.lastOption, Some(Notice16))
    assert(rememberedFile.flatMap(_.noticeAt).isDefined)
    assertNoNotice(run(command))
  }

  test("CI set to false is not CI") {
    known("0.16.0")
    assertEquals(run(command, Map("CI" -> "false")).errLines.lastOption, Some(Notice16))
  }

  test("a usage error gives no notice") {
    known("0.16.0")
    val ran = run(Seq("config", "no-such-command"))
    assertEquals(ran.code, 2)
    assertNoNotice(ran)
  }

  // ── spec.md's edge cases ──────────────────────────────────────────────────

  test("edge: a latest release file that is malformed gives no notice") {
    body = """{"version":"sixteen"}"""
    val ran = run(Seq("version"))
    assertEquals(ran.code, 0)
    assertNoNotice(ran)
    assertEquals(rememberedFile.flatMap(_.latest), None)
  }

  test("edge: a latest release older than the command line gives no notice") {
    body = """{"version":"0.12.4","released":"2026-08-01"}"""
    assertNoNotice(run(Seq("version")))
  }

  test("edge: an unreadable remembered check is an absent one") {
    Files.writeString(dir.resolve("release-check.json"), "{not json")
    val ran = run(Seq("version"))
    assertEquals(ran.errLines.lastOption, Some(Notice16))
  }

  test("edge: a directory the remembered check cannot be written to changes nothing") {
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
    val ran = run(Seq("version"))
    assertEquals(ran.code, 0)
    assertEquals(ran.errLines.lastOption, Some(Notice16))
    assertEquals(rememberedFile, None)
  }

  test("edge: two commands at once each succeed and leave the remembered check whole") {
    val runs = (1 to 4).map(_ =>
      java.util.concurrent.CompletableFuture.supplyAsync(() => run(Seq("version")))
    )
    runs.map(_.join()).foreach(ran => assertEquals(ran.code, 0))
    assertEquals(rememberedFile.flatMap(_.latest).map(_.version), Some("0.16.0"))
    assert(Files.list(dir).noneMatch(_.getFileName.toString.endsWith(".tmp")))
  }

  test("the remembered check is written as JSON a later release can read") {
    known("0.16.0")
    val text = Files.readString(dir.resolve("release-check.json"))
    assertEquals(readFromString[RememberedCheck](text).latest.map(_.version), Some("0.16.0"))
    assert(writeToString(LatestRelease("0.16.0", "2026-11-06")).contains("\"released\""))
  }

  // ── SC-002 ────────────────────────────────────────────────────────────────

  test("a command with the latest release unreachable takes no more than 100 ms longer") {
    // An address that refuses: a port bound and closed again. `ankka version` waits for its read, so
    // it is the command a refused read costs most. The median of twenty runs each way, not the mean,
    // so one run delayed by the collector or a busy machine does not decide the result.
    val socket = java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress)
    val port   = socket.getLocalPort
    socket.close()
    sys.props("ankka.latest.url") = s"http://127.0.0.1:$port/latest.json"
    def median(env: Map[String, String]): Long =
      val times = (1 to 20).map(_ => run(Seq("version"), env).millis).sorted
      times(times.size / 2)
    median(Map.empty): Unit // warm the HTTP client's classes
    val on      = median(Map.empty)
    val without = median(off)
    assert(on - without < 100, s"median $on ms with the check, $without ms without")
  }
