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
    attribution: () => Metadata = () => Metadata.empty,
    /**
     * The services of a project that take part in an erasure: those whose descriptor declares
     * `personal`. A request is final only once each has completed, whether or not it was connected
     * to the keyring when the notice was written; a paused or crash-looping one keeps it open.
     */
    val expected: String => Set[String] = _ => Set.empty
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
    attempt(current).recoverWith { case e =>
      // Nothing was destroyed: the key is destroyed only after both copies of the log are written.
      // The request says why, and the failure is the caller's still, so the sweep tries again.
      val reason = e match
        case f: ApplyStep => f.getMessage
        case other        => s"the erasure could not be applied: ${other.getMessage}"
      blocking(
        request(current.id)
          .call(ErasureRequestEntity.applyFailed)
          .withMetadata(attribution())
          .invoke(reason)
      ).transformWith(_ =>
        Future.failed(e match
          case f: ApplyStep => f.getCause
          case other        => other)
      )
    }

  /** A step of an application that failed, named for the request's `lastFailure`. */
  private final class ApplyStep(message: String, cause: Throwable)
      extends RuntimeException(message, cause)

  private def step[A](what: String)(f: => Future[A]): Future[A] =
    scala.util.Try(f).fold(Future.failed, identity).recoverWith { case e =>
      Future.failed(ApplyStep(s"$what: ${e.getMessage}", e))
    }

  private def attempt(current: ErasureRequest): Future[ErasureRequest] =
    for
      entry <- step("the erasure log could not be written")(
        log.write(current.project, current.rid, current.subject, Instant.now(clock))
      )
      _ <- blocking(
        request(current.id)
          .call(ErasureRequestEntity.logWritten)
          .withMetadata(attribution())
          .invoke(entry.seq)
      )
      gone <- step("the keyring did not destroy the subject key")(
        keyring.destroy(ErasureOrder(entry.seq, current.project, current.subject))
      )
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
            val due      = status.services.map(_.service).toSet ++ expected(current.project)
            val everyone = due.nonEmpty && due.forall(withObjects.completions.contains)
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

  /**
   * Reads each service's latest report from the keyring into the request, for a service that has
   * completed before and reported since (a re-run, FR-015): a run that erased or failed is a
   * `HandlerRan` (FR-016); runs that did neither set only `lastRunAt`.
   */
  def recordRuns(current: ErasureRequest): Future[ErasureRequest] =
    current.erasureSeq.filter(_ => current.state.contains(ErasureState.Applied)) match
      case None      => Future.successful(current)
      case Some(seq) =>
        keyring.status(seq).flatMap { status =>
          blocking {
            // This service's own runs only: another's quiet run says nothing of this one's.
            def known(service: String) =
              (current.completions.get(service).flatMap(_.at).toVector ++
                current.handlerRuns.filter(_.service == service).flatMap(_.at) ++
                current.quietRuns.get(service)).maxOption
            val fresh = status.services.collect {
              case com.thinkmorestupidless.ankka.core.keyring.ServiceErasureStatus(
                    service,
                    Some(at),
                    Some(c)
                  ) if current.completions.contains(service) && known(service).forall(at.isAfter) =>
                (service, at, c)
            }
            val loud = fresh.flatMap((service, at, c) =>
              c.handler
                .filter(h => h.objectsErased > 0 || h.error.isDefined)
                .map(h => HandlerRun(service, h, Some(at)))
            )
            val ran = loud.foldLeft(current)((req, run) =>
              request(req.id)
                .call(ErasureRequestEntity.recordHandlerRun)
                .withMetadata(attribution())
                .invoke(run)
            )
            val loudly = loud.map(_.service).toSet
            fresh
              .filterNot((service, _, _) => loudly(service))
              .foldLeft(ran)((req, quiet) =>
                request(req.id)
                  .call(ErasureRequestEntity.ranQuietly)
                  .withMetadata(attribution())
                  .invoke(QuietRun(quiet._1, quiet._2))
              )
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
  /**
   * The services request `r` still waits on: those its project expects that have not completed,
   * none once it is final. The request's page and its certificate list them with no completion.
   */
  def awaiting(r: ErasureRequest, expected: Set[String]): Vector[String] =
    if r.finalAt.isDefined || !r.state.contains(ErasureState.Applied) then Vector.empty
    else (expected -- r.completions.keySet).toVector.sorted

  /** When a handler's objects are final: now for `immediate`, after the duration for `after:`. */
  def finalAt(finality: String, ranAt: Option[Instant]): Option[Instant] =
    val at = ranAt.getOrElse(Instant.EPOCH)
    finality match
      case "immediate"                 => Some(at)
      case f if f.startsWith("after:") =>
        scala.util.Try(java.time.Duration.parse(f.stripPrefix("after:"))).toOption.map(at.plus)
      case _ => None
