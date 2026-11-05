package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.keyring.{
  Completion,
  HandlerReport,
  KeyringAck,
  KeyringNotice
}
import com.thinkmorestupidless.ankka.runtime.Database
import org.apache.pekko.actor.typed.ActorSystem
import org.slf4j.LoggerFactory

import java.util.{Collections, IdentityHashMap}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * What an instance does for an erasure notice (feature 042, R14, R15, R16, R23), in order: its key
 * was dropped from the cache before the notice was handed here; a process beside the runtime drops
 * it from its own (protocol 1.23); then each view's rows are redacted, the agent sessions and
 * instances of the subject are ended, the service's erasure handler is run (on the one instance
 * that holds its singleton), and the keyring is told, with what was done. A duty that fails is
 * logged and nothing is acknowledged: the keyring hands the notice again to an instance that
 * reconnects, and every duty is safe to run twice.
 */
final class ErasureDuties(
    service: String,
    instance: String,
    client: KeyringClient,
    database: () => Database,
    tables: Vector[ViewRedaction.Table],
    hooks: ErasureHooks,
    /** The database line whose record says how far this service has carried out the notices. */
    line: Option[String] = None,
    /** What that record said at start. */
    carriedFrom: Long = 0L
)(using ExecutionContext):

  private val log = LoggerFactory.getLogger(classOf[ErasureDuties])

  private val carried = CarriedThrough(carriedFrom)

  /**
   * Every notice handed here up to this one has been carried out and its ack is the keyring's: what
   * the line's record holds, so a start says hello from it rather than asking for the project's
   * whole history.
   */
  def carriedThrough: Long = carried.value

  // The notices handed here and not yet carried out; one that failed stays until it is carried out.
  private val outstanding = java.util.concurrent.ConcurrentSkipListSet[java.lang.Long]()

  /** Whether every notice up to `seq` handed here has been carried out. */
  def doneThrough(seq: Long): Boolean = outstanding.headSet(seq, true).isEmpty

  /** Carries out every notice from now on. */
  def start(): Unit = client.onNotice(notice => run(notice): Unit)

  /** Carries out one notice; its acknowledgement once it is done. */
  def run(notice: KeyringNotice): Future[KeyringAck] =
    outstanding.add(notice.seq): Unit
    carried.handed(notice.seq)
    val done =
      for
        _        <- hooks.process(notice)
        redacted <-
          if tables.isEmpty then Future.successful(ViewRedaction.Redacted.none)
          else ViewRedaction.redact(database(), tables, notice.project, notice.subject)
        sessions <- hooks.sessions(notice)
        handler  <- hooks.handler(notice)
      yield KeyringAck(
        notice.seq,
        service,
        instance,
        Completion(redacted.rows, redacted.tokens, sessions, handler)
      )
    done.andThen {
      case Success(ack) =>
        outstanding.remove(notice.seq): Unit
        // The database's mark moves only once the keyring has the ack: a mark recorded before it
        // would make a restart in an outage say hello past a notice the keyring never heard was
        // done, and the keyring would not hand it again (decided 2026-11-05, research.md).
        client.store
          .ack(ack)
          .onComplete {
            case Success(_) =>
              val before = carried.value
              val after  = carried.carried(notice.seq)
              if after > before then record(after)
            case Failure(e) =>
              log.warn(
                s"erasure notice ${notice.seq}'s acknowledgement did not reach the keyring",
                e
              )
          }
        log.info(
          s"erasure notice ${notice.seq} carried out: ${ack.completion.rowsRedacted} rows " +
            s"redacted, ${ack.completion.tokensDropped} rows' tokens dropped, " +
            s"${ack.completion.sessionsEnded} sessions ended" +
            ack.completion.handler.fold("")(h => s", the handler erased ${h.objectsErased} objects")
        )
      case Failure(e) =>
        // The subject is named; nothing personal is.
        log.warn(s"erasure notice ${notice.seq} of '${notice.subject}' was not carried out", e)
    }

  // The line's record, once the erasure log has been replayed on it (`ErasureReplay` writes the row):
  // before that there is no row and nothing is written, and the replay writes the mark it reaches.
  private def record(through: Long): Unit = line.foreach { l =>
    database()
      .execute(ErasureReplay.advance(l, through))
      .failed
      .foreach(e =>
        log.warn(s"how far this service carried out the erasure notices was not recorded: $e")
      )
  }

/**
 * The duties a notice runs that belong to other parts of the runtime: the agents' sessions and
 * instances of the subject (story 6), and the service's erasure handler. Each answers at once with
 * nothing to do until its part sets it.
 */
final class ErasureHooks:
  private val sessionsHook =
    AtomicReference[KeyringNotice => Future[Long]](_ => Future.successful(0L))
  private val handlerHook =
    AtomicReference[KeyringNotice => Future[Option[HandlerReport]]](_ => Future.successful(None))
  private val processHook =
    AtomicReference[KeyringNotice => Future[Unit]](_ => Future.unit)
  private val dropAllHook = AtomicReference[() => Unit](() => ())

  /**
   * Every key held outside the runtime's cache is to be dropped: the notices channel went
   * unconfirmed past the notice bound (`KeyringClient.check`).
   */
  def dropAll(): Unit = dropAllHook.get()()

  /** Sets what drops the keys a process beside the runtime holds (its notices streams ended). */
  def onDropAll(f: () => Unit): Unit = dropAllHook.set(f)

  /** A process beside the runtime dropping the subject's key: done once every instance has. */
  def process(notice: KeyringNotice): Future[Unit] = processHook.get()(notice)

  /** Sets what tells the process beside the runtime (protocol 1.23), first of every duty. */
  def onProcess(f: KeyringNotice => Future[Unit]): Unit = processHook.set(f)

  def sessions(notice: KeyringNotice): Future[Long]                 = sessionsHook.get()(notice)
  def handler(notice: KeyringNotice): Future[Option[HandlerReport]] = handlerHook.get()(notice)

  /** Sets what ends a subject's agent sessions and instances: its count of them. */
  def onSessions(f: KeyringNotice => Future[Long]): Unit = sessionsHook.set(f)

  /** Sets what runs the service's erasure handler for a notice: its report, when this ran it. */
  def onHandler(f: KeyringNotice => Future[Option[HandlerReport]]): Unit = handlerHook.set(f)

object ErasureHooks:
  private val bySystem =
    Collections.synchronizedMap(IdentityHashMap[ActorSystem[?], ErasureHooks]())

  /** The hooks of the service on `system`, made on first use. */
  def of(system: ActorSystem[?]): ErasureHooks =
    bySystem.synchronized(bySystem.computeIfAbsent(system, _ => ErasureHooks()))

  def forget(system: ActorSystem[?]): Unit = bySystem.remove(system): Unit
