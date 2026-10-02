package com.thinkmorestupidless.ankka.core

/**
 * A business-rule rejection returned by `effects.error(...)`.
 *
 * Distinct from a thrown exception: an error is a *modelled* outcome, so the runtime neither
 * retries it nor persists anything, and the caller receives it as a typed failure rather than an
 * opaque 500.
 */
final case class CommandError(message: String, code: ErrorCode = ErrorCode.BadRequest)
    extends RuntimeException(message):
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

object ErrorCode:
  extension (code: ErrorCode)
    /** Whether a caller could reasonably retry the same command unchanged. */
    def retryable: Boolean = code match
      case Unavailable | Timeout => true
      case _                     => false
