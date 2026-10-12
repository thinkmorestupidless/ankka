package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import io.grpc.Status

class GrpcStatusSuite extends munit.FunSuite:

  private val expected = Map(
    ErrorCode.BadRequest     -> Status.Code.INVALID_ARGUMENT,
    ErrorCode.Unauthorized   -> Status.Code.UNAUTHENTICATED,
    ErrorCode.Forbidden      -> Status.Code.PERMISSION_DENIED,
    ErrorCode.NotFound       -> Status.Code.NOT_FOUND,
    ErrorCode.Conflict       -> Status.Code.FAILED_PRECONDITION,
    ErrorCode.Timeout        -> Status.Code.DEADLINE_EXCEEDED,
    ErrorCode.Unavailable    -> Status.Code.UNAVAILABLE,
    ErrorCode.Internal       -> Status.Code.INTERNAL,
    ErrorCode.WorkflowFailed -> Status.Code.ABORTED
  )

  test("every code maps to its status, with the message, and back to itself") {
    expected.foreach { (code, status) =>
      val error  = CommandError(s"refused: $code", code)
      val mapped = GrpcStatus.toStatus(error)
      assertEquals(mapped.getCode, status, code.toString)
      assertEquals(mapped.getDescription, error.message)
      assertEquals(GrpcStatus.fromStatus(mapped), Some(error), code.toString)
    }
  }

  test("the table covers every code there is, so a new one cannot cross unmapped") {
    assertEquals(GrpcStatus.codes, ErrorCode.values.toSet)
  }

  test("a status that is not a rejection maps back to none") {
    Seq(
      Status.OK,
      Status.CANCELLED,
      Status.UNKNOWN,
      Status.RESOURCE_EXHAUSTED,
      Status.UNIMPLEMENTED
    )
      .foreach(s => assertEquals(GrpcStatus.fromStatus(s), None, s.getCode.toString))
  }

  test("a fault tells the caller nothing about itself") {
    assertEquals(GrpcStatus.internal.getCode, Status.Code.INTERNAL)
    assertEquals(GrpcStatus.internal.getDescription, "internal error")
  }
