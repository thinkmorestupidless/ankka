package com.thinkmorestupidless.ankka.cli

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.thinkmorestupidless.ankka.core.Codecs

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try
import scala.util.control.NonFatal

/**
 * A release of ankka: three numbers, ordered by major, then minor, then patch, written without a
 * `v`.
 */
private[cli] final case class Release(major: Int, minor: Int, patch: Int) extends Ordered[Release]:

  def compare(that: Release): Int =
    Ordering[(Int, Int, Int)].compare((major, minor, patch), (that.major, that.minor, that.patch))

  def render: String = s"$major.$minor.$patch"

private[cli] object Release:

  private val Pattern = raw"v?(\d+)\.(\d+)\.(\d+)".r

  /**
   * The release a text names, or none: anything but three numbers (dynver's
   * `0.16.0+3-abcdef12-SNAPSHOT` for a commit past a tag), and `0.0.0`, the placeholder of a build
   * from a checkout, are not releases.
   */
  def parse(text: String): Option[Release] = text.trim match
    case Pattern(major, minor, patch) =>
      Try(Release(major.toInt, minor.toInt, patch.toInt)).toOption.filterNot(_ == Release(0, 0, 0))
    case _ => None

/**
 * What `latest.json` beside the documentation says: the newest release and the day it was released.
 */
private[cli] final case class LatestRelease(version: String, released: String):
  def release: Option[Release] = Release.parse(version)

private[cli] object LatestRelease:
  given JsonValueCodec[LatestRelease] = Codecs.make[LatestRelease]

/**
 * What the command line last read of the latest release, when, and when it last gave the notice. It
 * decides whether to read again and whether to give the notice; it holds nothing about the person.
 */
private[cli] final case class RememberedCheck(
    checkedAt: Instant,
    latest: Option[LatestRelease] = None,
    noticeAt: Option[Instant] = None
)

private[cli] object RememberedCheck:
  given JsonValueCodec[RememberedCheck] = Codecs.make[RememberedCheck]

/**
 * Whether a newer release of ankka exists than this command line's, and the one sentence that says
 * so.
 *
 * The rules are pure functions of what they are given, so every case is a test with no network and
 * no file; the two effects, reading `latest.json` and the remembered check beside the settings,
 * swallow every failure, because nothing about a release check may fail or change a command.
 */
private[cli] object ReleaseCheck:

  val DefaultUrl: String = "https://docs.ankka.cloud/latest.json"

  /** How long a read may take, to connect and to answer. */
  val Timeout: Duration = Duration.ofSeconds(2)

  /** How long a read, and a notice, last before the next. */
  val Interval: Duration = Duration.ofHours(24)

  /** The notice, in exactly these words. */
  def notice(latest: Release, current: Release): String =
    s"ankka version ${latest.render} is available (you're currently using version ${current.render})"

  // ── The rules ─────────────────────────────────────────────────────────────

  /**
   * True when nothing may read the latest release at all: the check turned off, under CI, or a
   * development build. These hold for `ankka mcp` too.
   */
  def silenced(env: Map[String, String], current: String): Boolean =
    env.get("ANKKA_NO_UPDATE_CHECK").contains("1") ||
      env.get("CI").exists(value => value.nonEmpty && value != "false") ||
      Release.parse(current).isEmpty

  /**
   * Whether the arguments ask for JSON output, in any of the spellings the options parser accepts.
   */
  def jsonOutput(args: Seq[String]): Boolean =
    args.contains("-ojson") || args.contains("--output=json") ||
      args.sliding(2).exists {
        case Seq("-o" | "--output", "json") => true
        case _                              => false
      }

  /**
   * Whether a built-in command reads the latest release and may give the notice: not silenced, not
   * JSON output, not `ankka mcp` (which says it where a model reads, not on the error output), and
   * only when the error output is a terminal a person reads.
   */
  def enabled(
      args: Seq[String],
      env: Map[String, String],
      current: String,
      terminal: Boolean
  ): Boolean =
    terminal && !silenced(env, current) && !jsonOutput(args) && !args.headOption.contains("mcp")

  /** A read is due when none succeeded, or the last one is a day old. */
  def shouldRead(remembered: Option[RememberedCheck], now: Instant): Boolean =
    remembered.forall(check => !check.checkedAt.plus(Interval).isAfter(now))

  /** The latest release, when it is newer than the command line's. */
  def newer(latest: Option[LatestRelease], current: Release): Option[Release] =
    latest.flatMap(_.release).filter(_ > current)

  /** The notice is given when a newer release is known and none was given in the last day. */
  def shouldNotify(
      latest: Option[LatestRelease],
      current: Release,
      noticeAt: Option[Instant],
      now: Instant
  ): Boolean =
    newer(latest, current).isDefined && noticeAt.forall(at => !at.plus(Interval).isAfter(now))

  /**
   * Whether the error output counts as a terminal: `-Dankka.terminal=true|false` for a test, else
   * whether the JVM has a console.
   *
   * JDK 21 has no way to ask whether standard error alone is a terminal. `System.console()` is
   * non-null when standard input and output are both the terminal, which is the interactive case; a
   * person who pipes the output into another program gets no notice, which is the case to keep
   * quiet. From JDK 22 `System.console()` returns a console even when redirected, and
   * `Console.isTerminal()` says whether it is one; the zip runs on whatever JDK is on the path, so
   * that method is asked for where it exists, by reflection, since the build targets 21.
   */
  def terminal(): Boolean =
    sys.props.get("ankka.terminal") match
      case Some(value) => value == "true"
      case None        =>
        Option(System.console()).exists { console =>
          Try(classOf[java.io.Console].getMethod("isTerminal").invoke(console)).toOption match
            case Some(answer: java.lang.Boolean) => answer.booleanValue
            case _                               => true
        }

  // ── The effects ───────────────────────────────────────────────────────────

  /** `-Dankka.latest.url`, else `ANKKA_LATEST_URL`, else the documentation site's `latest.json`. */
  def url(env: Map[String, String]): String =
    sys.props.get("ankka.latest.url").orElse(env.get("ANKKA_LATEST_URL")).getOrElse(DefaultUrl)

  /** The latest release, or none when it cannot be read in time or does not parse. */
  def read(url: String, timeout: Duration = Timeout): Option[LatestRelease] =
    try
      val client = HttpClient
        .newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
      val request =
        HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      if response.statusCode() != 200 then None
      else Some(readFromString[LatestRelease](response.body())).filter(_.release.isDefined)
    catch case NonFatal(_) => None

  /** A read on a daemon thread, which the process never waits for. */
  final class Pending private[ReleaseCheck] (url: String, timeout: Duration):
    private val result = AtomicReference[Option[Option[LatestRelease]]](None)

    private val thread = Thread
      .ofPlatform()
      .daemon(true)
      .name("ankka-release-check")
      .start(() => result.set(Some(read(url, timeout))))

    /** What the read found, if it has finished: `Some(None)` is a read that failed. */
    def finished: Option[Option[LatestRelease]] = result.get()

    /** Waits up to `limit` for the read to finish; true when it has. */
    def await(limit: Duration): Boolean =
      thread.join(limit)
      result.get().isDefined

  def start(url: String, timeout: Duration = Timeout): Pending = Pending(url, timeout)

  /** `release-check.json`, in the directory of the settings file, so their override moves both. */
  def file: Path =
    val settings = Settings.path.toAbsolutePath
    Option(settings.getParent).getOrElse(settings).resolve("release-check.json")

  /** The remembered check, or none when there is none or it cannot be read. */
  def remembered(): Option[RememberedCheck] =
    try
      val path = file
      if !Files.isRegularFile(path) then None
      else Some(readFromString[RememberedCheck](Files.readString(path)))
    catch case NonFatal(_) => None

  /**
   * Writes the remembered check whole: to a file beside it, then moved into place, so two commands
   * at once each leave a whole file. A directory that cannot be written is ignored.
   */
  def remember(check: RememberedCheck): Unit =
    try
      val target    = file
      val directory = target.getParent
      Files.createDirectories(directory): Unit
      val temporary = Files.createTempFile(directory, "release-check", ".tmp")
      try
        Files.writeString(temporary, writeToString(check)): Unit
        Files.move(
          temporary,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        ): Unit
      finally Files.deleteIfExists(temporary): Unit
    catch case NonFatal(_) => ()

  // ── Around one command ────────────────────────────────────────────────────

  /**
   * One built-in command's check: a read started before the command, when one is due, and the
   * notice given after it from whatever is known by then. The command never waits for the read: one
   * that ends first leaves it unfinished and unremembered, and the next command reads again. `ankka
   * version` alone reads every time and waits for the read, under the same timeout.
   */
  final class Around private[ReleaseCheck] (
      version: Boolean,
      current: Release,
      remembered: Option[RememberedCheck],
      pending: Option[Pending]
  ):

    def end(code: Int, err: java.io.PrintStream, now: Instant = Instant.now()): Unit =
      // A usage error printed help, not a command's output.
      if code != 2 then
        val read: Option[LatestRelease] =
          pending.flatMap { p =>
            if version then p.await(Timeout.plusMillis(250)): Unit
            p.finished.flatten
          }
        val known = read match
          case Some(latest) =>
            Some(RememberedCheck(now, Some(latest), remembered.flatMap(_.noticeAt)))
          case None => remembered
        val latest = known.flatMap(_.latest)
        val give   =
          if version then newer(latest, current).isDefined
          else shouldNotify(latest, current, known.flatMap(_.noticeAt), now)
        newer(latest, current).filter(_ => give).foreach { newest =>
          err.println(notice(newest, current))
          err.flush()
        }
        val updated = if give then known.map(_.copy(noticeAt = Some(now))) else known
        if updated != remembered then updated.foreach(remember)

  /**
   * Starts the check around a built-in command, or none when it does not apply: see `enabled`.
   * `now` and `terminal` are the test's to fix.
   */
  def begin(
      args: Seq[String],
      env: Map[String, String],
      current: String,
      terminal: Boolean = ReleaseCheck.terminal(),
      now: Instant = Instant.now()
  ): Option[Around] =
    if !enabled(args, env, current, terminal) then None
    else
      Release.parse(current).map { release =>
        val version    = args.headOption.contains("version")
        val remembered = ReleaseCheck.remembered()
        val pending    =
          Option.when(version || shouldRead(remembered, now))(start(url(env)))
        Around(version, release, remembered, pending)
      }
