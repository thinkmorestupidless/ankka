package com.thinkmorestupidless.ankka.core

/**
 * A business-rule rejection returned by `effects.error(...)`.
 *
 * Distinct from a thrown exception: an error is a *modelled* outcome, so the runtime neither
 * retries it nor persists anything, and the caller receives it as a typed failure rather than an
 * opaque 500.
 */
final case class CommandError(
    message: String,
    code: ErrorCode = ErrorCode.BadRequest,
    /**
     * What a caller in any language reads without parsing the message. Empty for every code but
     * `WorkflowFailed`, whose keys `WorkflowEnd` names.
     */
    details: Map[String, String] = Map.empty
) extends RuntimeException(message):
  // Modelled errors are control flow, not crashes — a stack trace would be noise on a
  // hot path and misleading in logs.
  override def fillInStackTrace(): Throwable = this

object CommandError:

  /**
   * The modelled rejection `failure` is, or carries as its cause.
   *
   * One level and no further. A client that receives another service's rejection — over gRPC, say —
   * can only throw its transport's own exception, and attaches the rejection as that exception's
   * cause; a handler that lets it pass should answer its own caller with the same rejection, not
   * with a fault. Anything wrapped deeper than that has been wrapped by someone who meant to.
   */
  def from(failure: Throwable): Option[CommandError] = failure match
    case error: CommandError => Some(error)
    case other =>
      Option(other).flatMap(t => Option(t.getCause)).collect { case error: CommandError => error }

/**
 * Transport-neutral error classification. `ankka-http` maps these onto status codes and the
 * ComponentClient surfaces them as typed failures.
 */
enum ErrorCode:
  case BadRequest
  case NotFound
  case Conflict
  case Forbidden
  case Unauthorized
  case Internal
  case Unavailable
  case Timeout

  /**
   * A workflow a caller waited for failed or was deleted. Not a refusal of the call, which was
   * answered, and not `Internal`, which would read as the call having broken: the step and the
   * reason are in the error's `details` (`WorkflowEnd.failure` reads them).
   */
  case WorkflowFailed

object ErrorCode:
  extension (code: ErrorCode)
    /** Whether a caller could reasonably retry the same command unchanged. */
    def retryable: Boolean = code match
      case Unavailable | Timeout => true
      case _                     => false

/** How a workflow a caller waited for ended, when it did not complete. */
object WorkflowEnd:

  /** The `details` keys of a `WorkflowFailed` error. */
  val StepKey: String    = "step"
  val ReasonKey: String  = "reason"
  val DeletedKey: String = "deleted"

  /**
   * A workflow that failed (`step` names the step, when one failed) or was deleted (`deleted`, no
   * step).
   */
  final case class Failure(step: Option[String], reason: String, deleted: Boolean):
    def details: Map[String, String] =
      Map(ReasonKey -> reason) ++ step.map(StepKey -> _) ++
        (if deleted then Map(DeletedKey -> "true") else Map.empty)

  /** The failure `error` describes, when it is a `WorkflowFailed`; `None` for any other code. */
  def failure(error: CommandError): Option[Failure] =
    Option.when(error.code == ErrorCode.WorkflowFailed)(
      Failure(
        error.details.get(StepKey),
        error.details.getOrElse(ReasonKey, error.message),
        error.details.get(DeletedKey).contains("true")
      )
    )
