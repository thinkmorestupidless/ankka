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
    clock: Clock = Clock.systemUTC()
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
    cache.fresh(project, subject).getOrElse {
      try
        val answer = fetch(project, subject, create)
        cache.put(project, subject, answer)
        answer
      catch
        case unavailable: CommandError if unavailable.code == ErrorCode.Unavailable =>
          cache.stale(project, subject).getOrElse(throw unavailable)
    }

  private def fetch(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    val call = fetching.submit(() => store.key(project, subject, create))
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

  /** Hands every notice to `handler` too, after the cache has dropped its key. */
  def onNotice(handler: KeyringNotice => Unit): Unit =
    handlers.updateAndGet(_ :+ handler): Unit

  /** The last notice this instance was handed. */
  def lastNotice: Long = lastSeq

  /** Opens the notices channel, and checks it is confirmed every quarter of `notice-bound`. */
  def start(): Unit =
    if channel.get.isEmpty then
      channel.set(Some(store.subscribe(lastSeq)(handle)))
      val every     = math.max(settings.noticeBound.toMillis / 4, 50L)
      val scheduler = Executors.newSingleThreadScheduledExecutor(r =>
        Thread.ofVirtual().name("ankka-keyring-notices").unstarted(r)
      )
      scheduler.scheduleAtFixedRate(() => check(): Unit, every, every, TimeUnit.MILLISECONDS): Unit
      checker.set(Some(scheduler))

  private def handle(notice: KeyringNotice): Unit =
    cache.drop(notice.project, DataSubject(notice.subject))
    lastSeq = math.max(lastSeq, notice.seq)
    handlers.get.foreach { h =>
      try h(notice)
      catch
        case NonFatal(e) =>
          log.warn(s"an erasure notice's duty failed: notice ${notice.seq} of ${notice.subject}", e)
    }

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
  def apply(store: KeyStoreClient, settings: KeyringSettings): KeyringClient =
    new KeyringClient(store, KeyCache(settings), settings)
