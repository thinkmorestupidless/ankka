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
 * was dropped from the cache before the notice was handed here; then each view's rows are redacted,
 * the agent sessions and instances of the subject are ended, the service's erasure handler is run
 * (on the one instance that holds its singleton), and the keyring is told, with what was done. A
 * duty that fails is logged and nothing is acknowledged: the keyring hands the notice again to an
 * instance that reconnects, and every duty is safe to run twice.
 */
final class ErasureDuties(
    service: String,
    instance: String,
    client: KeyringClient,
    database: () => Database,
    tables: Vector[ViewRedaction.Table],
    hooks: ErasureHooks
)(using ExecutionContext):

  private val log = LoggerFactory.getLogger(classOf[ErasureDuties])

  /** Carries out every notice from now on. */
  def start(): Unit = client.onNotice(notice => run(notice): Unit)

  /** Carries out one notice; its acknowledgement once it is done. */
  def run(notice: KeyringNotice): Future[KeyringAck] =
    val done =
      for
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
        client.store.ack(ack)
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
