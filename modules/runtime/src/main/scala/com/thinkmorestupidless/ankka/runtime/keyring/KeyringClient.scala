package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.{
  CommandError,
  DataSubject,
  ErrorCode,
  KeyAnswer,
  Personal
}
import com.thinkmorestupidless.ankka.core.keyring.KeyringNotice
import org.slf4j.LoggerFactory

import java.time.{Clock, Duration as JDuration}
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/**
 * One instance's client of the keyring (feature 042, R5): keys through the instance's cache, a
 * fetch bounded by `fetch-timeout`, and the notices channel held open from start.
 *
 * A notice drops its subject's key before anything else hears of it, so no read after the notice
 * opens a field under the destroyed key; then it is handed to `onNotice` (the erasure duties). An
 * instance whose channel has gone unconfirmed for `notice-bound` cannot know it missed no notice,
 * and drops every key it holds: every read asks the keyring again, which answers `Destroyed` for an
 * erased subject. That is the whole of the 60-second bound on an instance (FR-022).
 */
final class KeyringClient(
    val store: KeyStoreClient,
    val cache: KeyCache,
    settings: KeyringSettings,
    clock: Clock = Clock.systemUTC(),
    /** The service's own project: a key of any other is held for `foreign-ttl` at most. */
    ownProject: Option[String] = None
):

  private val log      = LoggerFactory.getLogger(classOf[KeyringClient])
  private val fetching = Executors.newVirtualThreadPerTaskExecutor()
  private val handlers = new AtomicReference[Vector[KeyringNotice => Unit]](Vector.empty)
  private val channel  = new AtomicReference[Option[AutoCloseable]](None)
  private val checker  =
    new AtomicReference[Option[java.util.concurrent.ScheduledExecutorService]](None)
  @volatile private var lastSeq: Long = 0L

  /** The key of `subject`: a fresh one held, else asked for; a held one in an outage's bound. */
  def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    if ownProject.exists(_ != project) then foreignKey(project, subject, create)
    else ownKey(project, subject, create)

  private def ownKey(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    cache.fresh(project, subject).getOrElse {
      try
        val since  = cache.generation
        val answer = fetch(project, subject, create)
        if cache.putIfUndropped(project, subject, answer, since) then answer
        else
          // A notice (or the channel's loss) dropped keys while this fetch was in flight, so its
          // answer may have been read before an erasure: it is neither held nor answered, and the
          // keyring is asked again, which answers Destroyed for an erased subject (FR-022).
          val again = cache.generation
          val fresh = fetch(project, subject, create)
          cache.putIfUndropped(project, subject, fresh, again): Unit
          fresh
      catch
        case unavailable: CommandError if unavailable.code == ErrorCode.Unavailable =>
          (if withinOutageBound() then cache.stale(project, subject) else None)
            .getOrElse(throw unavailable)
    }

  // When the keyring last answered this instance: a fetch, or a confirmation of the channel.
  private val answeredAt = new AtomicReference[Option[java.time.Instant]](None)

  /**
   * Whether the keyring was last heard within `outage-bound`: a held key serves through an outage
   * that long and no longer. The bound never exceeds `notice-bound` (`KeyringSettings`), so
   * `check()` dropping every key at the notice bound is never undone by the outage (FR-020, decided
   * 2026-11-05: the notice bound wins).
   */
  private def withinOutageBound(): Boolean =
    (answeredAt.get.toList ++ store.confirmedAt().toList).maxOption.exists(at =>
      JDuration.between(at, clock.instant()).toMillis < settings.outageBound.toMillis
    )

  // Keys of another project's data subjects, read under a grant with decryption: no notice of
  // that project reaches this instance, so each is held for `foreign-ttl` and no longer, and a
  // refusal (no grant, or a revoked one) is never held at all (decided 2026-11-04, T073).
  private val foreign =
    new KeyCache(settings.maxKeys, settings.foreignTtl, clock)

  private def foreignKey(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    foreign.fresh(project, subject).getOrElse {
      fetch(project, subject, create) match
        case refused: KeyAnswer.Refused => refused
        case answer                     =>
          foreign.put(project, subject, answer)
          answer
    }

  private def fetch(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    val call = fetching.submit(() => store.key(project, subject, create))
    try
      val answer = call.get(settings.fetchTimeout.toMillis, TimeUnit.MILLISECONDS)
      answeredAt.set(Some(clock.instant()))
      answer
    catch
      case _: TimeoutException =>
        call.cancel(true): Unit
        throw CommandError(
          s"the keyring is unavailable: it did not answer within ${settings.fetchTimeout} " +
            "(ankka.keyring.fetch-timeout)",
          ErrorCode.Unavailable,
          Map(Personal.ErrorKey -> Personal.Unavailable)
        )
      case e: java.util.concurrent.ExecutionException =>
        throw Option(e.getCause).getOrElse(e)

  private val lookupKeys = java.util.concurrent.ConcurrentHashMap[String, IArray[Byte]]()

  /** `project`'s lookup key: asked for once, then held, since an erasure never destroys it. */
  def lookupKey(project: String): IArray[Byte] =
    Option(lookupKeys.get(project)).getOrElse {
      val call = fetching.submit(() => store.lookupKey(project))
      val key  =
        try call.get(settings.fetchTimeout.toMillis, TimeUnit.MILLISECONDS)
        catch
          case _: TimeoutException =>
            call.cancel(true): Unit
            throw CommandError(
              s"the keyring is unavailable: it did not answer within ${settings.fetchTimeout} " +
                "(ankka.keyring.fetch-timeout)",
              ErrorCode.Unavailable,
              Map(Personal.ErrorKey -> Personal.Unavailable)
            )
          case e: java.util.concurrent.ExecutionException => throw Option(e.getCause).getOrElse(e)
      lookupKeys.putIfAbsent(project, key)
      key
    }

  // Notices handed before any handler was set: the channel opens before the views exist, so its
  // backlog may arrive before the duties do, and is handed to the first handler set.
  private val early = scala.collection.mutable.ArrayBuffer.empty[KeyringNotice]

  /** Hands every notice to `handler` too, after the cache has dropped its key. */
  def onNotice(handler: KeyringNotice => Unit): Unit =
    val missed = synchronized {
      val first = handlers.get.isEmpty
      handlers.updateAndGet(_ :+ handler)
      if first then
        val held = early.toVector
        early.clear()
        held
      else Vector.empty
    }
    missed.foreach(n => dispatch(handler, n))

  private val opened = scala.concurrent.Promise[Unit]()

  /**
   * Completes once the channel is open and the keyring's backlog for the project has been handed
   * on: the store's `caughtUp`, after this client subscribed.
   */
  def caughtUp: scala.concurrent.Future[Unit] = opened.future

  /** The last notice this instance was handed. */
  def lastNotice: Long = lastSeq

  /**
   * Opens the notices channel, saying this instance carried out every notice up to `after` (what
   * the service's own database records, `ErasureReplay.carriedThrough`), and checks it is confirmed
   * every quarter of `notice-bound`.
   */
  def start(after: Long = 0L): Unit =
    if channel.get.isEmpty then
      lastSeq = math.max(lastSeq, after)
      channel.set(Some(store.subscribe(lastSeq)(handle)))
      opened.completeWith(store.caughtUp)
      val every     = math.max(settings.noticeBound.toMillis / 4, 50L)
      val scheduler = Executors.newSingleThreadScheduledExecutor(r =>
        Thread.ofVirtual().name("ankka-keyring-notices").unstarted(r)
      )
      scheduler.scheduleAtFixedRate(() => check(): Unit, every, every, TimeUnit.MILLISECONDS): Unit
      checker.set(Some(scheduler))

  private def handle(notice: KeyringNotice): Unit =
    cache.drop(notice.project, DataSubject(notice.subject))
    lastSeq = math.max(lastSeq, notice.seq)
    val now = synchronized {
      val hs = handlers.get
      if hs.isEmpty then early += notice
      hs
    }
    now.foreach(dispatch(_, notice))

  private def dispatch(h: KeyringNotice => Unit, notice: KeyringNotice): Unit =
    try h(notice)
    catch
      case NonFatal(e) =>
        log.warn(s"an erasure notice's duty failed: notice ${notice.seq} of ${notice.subject}", e)

  /**
   * Drops every key held when the channel has not been confirmed within `notice-bound`; answers
   * whether it did. Run on a schedule; a test calls it after moving its clock.
   */
  def check(): Boolean =
    val unconfirmed = store.confirmedAt() match
      case None     => true
      case Some(at) =>
        JDuration.between(at, clock.instant()).toMillis > settings.noticeBound.toMillis
    if unconfirmed && cache.size > 0 then
      log.warn(
        s"the keyring's notices channel has not been confirmed within ${settings.noticeBound}: " +
          s"every key this instance held (${cache.size}) is dropped and will be asked for again"
      )
      cache.dropAll()
      true
    else false

  def close(): Unit =
    checker.getAndSet(None).foreach(_.shutdownNow(): Unit)
    channel.getAndSet(None).foreach(c => scala.util.Try(c.close()): Unit)
    fetching.shutdownNow(): Unit
    store.close()

object KeyringClient:
  def apply(
      store: KeyStoreClient,
      settings: KeyringSettings,
      ownProject: Option[String] = None
  ): KeyringClient =
    new KeyringClient(store, KeyCache(settings), settings, ownProject = ownProject)
