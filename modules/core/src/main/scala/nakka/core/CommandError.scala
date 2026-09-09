package nakka.core

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

/**
 * Transport-neutral error classification. `nakka-http` maps these onto status codes and the
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
