package com.thinkmorestupidless.ankka.cli.mcp

import com.thinkmorestupidless.ankka.cli.{LatestRelease, Release, ReleaseCheck, RememberedCheck}

import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{Executors, TimeUnit}

/**
 * What `ankka mcp` knows of the latest release, for the instructions and the tools to say.
 *
 * It starts from the remembered check, reads at once when a read is due, and while the server runs
 * looks every hour whether the last successful read is a day old, reading again when it is: a
 * server that runs for days learns of a release published meanwhile, and a read that failed is
 * tried again within the hour. Each successful read is remembered as the command line's is. Off
 * (the check turned off, under CI, or a development build) it reads nothing and knows only the
 * command line's release.
 */
private[cli] final class ReleaseStatus(
    val cli: String,
    enabled: Boolean,
    url: String,
    clock: () => Instant = () => Instant.now(),
    timeout: Duration = ReleaseCheck.Timeout
):

  private val remembered: Option[RememberedCheck] =
    if enabled then ReleaseCheck.remembered() else None

  private val state = AtomicReference[Option[RememberedCheck]](remembered)

  @volatile private var starting: Option[ReleaseCheck.Pending] = None

  private def current: Option[Release] = Release.parse(cli)

  /** The latest release, when one has been read. */
  def latest: Option[LatestRelease] = state.get().flatMap(_.latest)

  /** The latest release, when it is newer than the command line's. */
  def newer: Option[Release] = current.flatMap(ReleaseCheck.newer(latest, _))

  def available: Boolean = newer.isDefined

  /** The notice, when a newer release is known. */
  def notice: Option[String] =
    for
      newest <- newer
      mine   <- current
    yield ReleaseCheck.notice(newest, mine)

  /**
   * Whether a read is due. The command line's attempts are its own: the server reads at start
   * whenever no read succeeded in the last day, however recently a command tried, so a session
   * started on a machine whose last command's read was cut short still carries the notice.
   */
  private def due: Boolean =
    enabled && ReleaseCheck.shouldRead(state.get().map(_.copy(attemptedAt = None)), clock())

  /**
   * Starts the first read when one is due, without waiting for it. A read that ends after
   * `awaitStart` stops waiting still remembers what it found.
   */
  def start(): ReleaseStatus =
    if due then starting = Some(ReleaseCheck.start(url, timeout, keep = true))
    this

  /**
   * Waits for the first read, up to its timeout, when one is under way, so instructions fixed at
   * `initialize` carry what it found.
   */
  def awaitStart(): Unit =
    starting.foreach { pending =>
      pending.await(timeout.plusMillis(250)): Unit
      pending.finished.flatten.foreach(found)
      starting = None
    }

  /** Reads again when the last successful read is a day old: what the hourly look runs. */
  def tick(): Unit =
    if due then ReleaseCheck.read(url, timeout).foreach(found)

  private def found(latest: LatestRelease): Unit =
    state.set(Some(ReleaseCheck.succeeded(latest, clock())))

  /**
   * The `release` field of `whoami` and `installation_status`: the command line's release always;
   * the latest, whether it is newer, and the notice when a read has found one.
   */
  def json: Vector[(String, Json)] =
    Vector("cli" -> Json.str(cli)) ++
      latest.filter(_.release.isDefined).toVector.flatMap { found =>
        Vector("latest" -> Json.str(found.version), "available" -> Json.bool(available))
      } ++
      notice.toVector.map(text => "notice" -> Json.str(text))

  /** Looks every hour, on a daemon thread, for as long as the process runs. */
  def schedule(): ReleaseStatus =
    if enabled then
      val scheduler = Executors.newSingleThreadScheduledExecutor { runnable =>
        val thread = Thread(runnable, "ankka-release-status")
        thread.setDaemon(true)
        thread
      }
      scheduler.scheduleWithFixedDelay(() => tick(), 1, 1, TimeUnit.HOURS): Unit
    this

private[cli] object ReleaseStatus:

  /** Knows only the command line's release, and reads nothing. */
  def off(cli: String): ReleaseStatus = ReleaseStatus(cli, enabled = false, url = "")

  /**
   * The server's: on unless the check is turned off, under CI or a development build. The terminal
   * and JSON output rules do not apply; a model, not a terminal, reads what it says.
   */
  def forServer(cli: String, env: Map[String, String]): ReleaseStatus =
    ReleaseStatus(cli, enabled = !ReleaseCheck.silenced(env, cli), url = ReleaseCheck.url(env))
      .start()
      .schedule()

  extension (status: ReleaseStatus)

    /** The paragraph the instructions end with when a newer release is known. */
    def instructions: Option[String] = status.notice.map(notice =>
      s"$notice. The documentation tools serve this command line's release's pages; tell the person, who can upgrade the command line."
    )

    /** The line `search_docs` and `read_doc` begin with when a newer release is known. */
    def docsNote: Option[String] = status.notice.map(notice =>
      s"Note: these pages are the documentation of ankka ${status.cli}, the release of this command line. $notice"
    )
