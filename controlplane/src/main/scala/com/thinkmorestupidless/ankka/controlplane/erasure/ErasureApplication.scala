package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.core.{EntityId, Metadata}
import com.thinkmorestupidless.ankka.core.keyring.ErasureOrder
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.slf4j.LoggerFactory

import java.time.{Clock, Instant}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Applies an erasure request (feature 042, R22): the log's two copies first, then the key
 * destroyed, then each service's completion read from the keyring into the request. Every step is
 * safe to run again, so a request left applying by a failure is applied by running this again; a
 * log write that fails destroys nothing.
 */
final class ErasureApplication(
    client: ComponentClient,
    val log: ErasureLog,
    keyring: KeyringAdmin,
    clock: Clock = Clock.systemUTC(),
    attribution: () => Metadata = () => Metadata.empty
)(using ExecutionContext):

  private val logger = LoggerFactory.getLogger(classOf[ErasureApplication])

  private def request(id: String) =
    client.forEventSourcedEntity(EntityId(id))

  private def blocking[A](f: => A): Future[A] =
    Future(f)(using com.thinkmorestupidless.ankka.runtime.AnkkaExecutors.virtual)

  /** Carries request `id` (`<project>/<rid>`) as far as it can go now; its state after. */
  def apply(id: String): Future[ErasureRequest] =
    blocking(request(id).call(ErasureRequestEntity.get).invoke()).flatMap { current =>
      current.state match
        case Some(ErasureState.Applying) => applying(current)
        case Some(ErasureState.Applied)  => complete(current)
        case _                           => Future.successful(current)
    }

  private def applying(current: ErasureRequest): Future[ErasureRequest] =
    for
      entry <- log.write(current.project, current.rid, current.subject, Instant.now(clock))
      _     <- blocking(
        request(current.id)
          .call(ErasureRequestEntity.logWritten)
          .withMetadata(attribution())
          .invoke(entry.seq)
      )
      gone  <- keyring.destroy(ErasureOrder(entry.seq, current.project, current.subject))
      after <- blocking(
        request(current.id)
          .call(ErasureRequestEntity.keyDestroyed)
          .withMetadata(attribution())
          .invoke(gone.destroyedAt)
      )
      done <- complete(after)
    yield done

  /** Reads what the keyring knows of each service's completion into the request. */
  def complete(current: ErasureRequest): Future[ErasureRequest] =
    current.erasureSeq match
      case None      => Future.successful(current)
      case Some(seq) =>
        keyring.status(seq).flatMap { status =>
          blocking {
            val recorded = status.services.foldLeft(current) { (req, s) =>
              (s.completedAt, s.completion) match
                case (Some(at), Some(c)) if !req.completions.contains(s.service) =>
                  request(req.id)
                    .call(ErasureRequestEntity.recordCompletion)
                    .withMetadata(attribution())
                    .invoke(
                      RecordCompletion(
                        s.service,
                        ServiceCompletion(
                          Some(at),
                          c.rowsRedacted,
                          c.tokensDropped,
                          c.sessionsEnded,
                          c.handler
                        )
                      )
                    )
                case _ => req
            }
            val objectsFinal = recorded.completions.values
              .flatMap(c => c.handler.flatMap(h => ErasureApplication.finalAt(h.finality, c.at)))
            val withObjects =
              if objectsFinal.isEmpty then recorded
              else
                request(recorded.id)
                  .call(ErasureRequestEntity.objectsFinal)
                  .withMetadata(attribution())
                  .invoke(objectsFinal.max)
            val everyone =
              status.services.nonEmpty && status.services
                .forall(s => withObjects.completions.contains(s.service))
            val failed = withObjects.completions.values.exists(_.handler.exists(_.error.isDefined))
            if everyone && !failed && withObjects.objectsFinalAt.forall(
                !_.isAfter(Instant.now(clock))
              )
            then
              request(withObjects.id)
                .call(ErasureRequestEntity.finalise)
                .withMetadata(attribution())
                .invoke()
            else withObjects
          }
        }

  /** Asks the keyring to send request `id`'s notice again, so every service runs it again. */
  def resend(current: ErasureRequest): Future[Unit] =
    current.erasureSeq.fold(Future.unit) { seq =>
      keyring.resend(seq).recover { case e =>
        logger.warn(s"the keyring did not re-send erasure ${current.id}", e)
      }
    }

object ErasureApplication:
  /** When a handler's objects are final: now for `immediate`, after the duration for `after:`. */
  def finalAt(finality: String, ranAt: Option[Instant]): Option[Instant] =
    val at = ranAt.getOrElse(Instant.EPOCH)
    finality match
      case "immediate"                 => Some(at)
      case f if f.startsWith("after:") =>
        scala.util.Try(java.time.Duration.parse(f.stripPrefix("after:"))).toOption.map(at.plus)
      case _ => None
