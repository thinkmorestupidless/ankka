package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import io.grpc.Status

/**
 * How a modelled rejection crosses gRPC, in both directions, from one table.
 *
 * One table so the two directions cannot disagree: a service that refuses with `Conflict` is
 * answered `FAILED_PRECONDITION`, and a service that calls it and receives `FAILED_PRECONDITION`
 * gets `Conflict` back. `Conflict` is the one code with no obvious status; the platform uses it for
 * "the current state does not permit this", which is what failed precondition means, and `ABORTED`
 * would invite a caller to retry a domain rule unchanged.
 */
object GrpcStatus:

  private val table: Vector[(ErrorCode, Status.Code)] = Vector(
    ErrorCode.BadRequest   -> Status.Code.INVALID_ARGUMENT,
    ErrorCode.Unauthorized -> Status.Code.UNAUTHENTICATED,
    ErrorCode.Forbidden    -> Status.Code.PERMISSION_DENIED,
    ErrorCode.NotFound     -> Status.Code.NOT_FOUND,
    ErrorCode.Conflict     -> Status.Code.FAILED_PRECONDITION,
    ErrorCode.Timeout      -> Status.Code.DEADLINE_EXCEEDED,
    ErrorCode.Unavailable  -> Status.Code.UNAVAILABLE,
    ErrorCode.Internal     -> Status.Code.INTERNAL
  )

  private val toCode   = table.toMap
  private val fromCode = table.map(_.swap).toMap

  /** Every code the table covers; a suite holds it to `ErrorCode.values`. */
  private[grpc] def codes: Set[ErrorCode] = toCode.keySet

  def toStatus(error: CommandError): Status =
    Status.fromCode(toCode(error.code)).withDescription(error.message)

  /**
   * The rejection a status stands for, or `None` for one that is not a rejection — `OK`,
   * `CANCELLED`, `UNKNOWN`, `RESOURCE_EXHAUSTED` and the rest are the transport's to report.
   */
  def fromStatus(status: Status): Option[CommandError] =
    fromCode
      .get(status.getCode)
      .map(code => CommandError(Option(status.getDescription).getOrElse(""), code))

  /** A fault the caller is told nothing about: what went wrong is the service's log's business. */
  val internal: Status = Status.INTERNAL.withDescription("internal error")
