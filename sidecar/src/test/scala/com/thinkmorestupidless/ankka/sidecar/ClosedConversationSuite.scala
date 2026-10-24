package com.thinkmorestupidless.ankka.sidecar

import io.grpc.Status

/**
 * What the sidecar says when a process's stream ends with a fault: the cause, which grpc-java's
 * message leaves out (issue #87). No process, no actor system.
 */
class ClosedConversationSuite extends munit.FunSuite:

  test("a cancelled stream names what failed to serialise, from the status's cause") {
    val failure = Status.CANCELLED
      .withDescription("Failed to stream message")
      .withCause(NullPointerException("metadata entry value is null"))
      .asRuntimeException()
    assertEquals(
      GrpcConversation.describe(failure),
      "CANCELLED: Failed to stream message, caused by NullPointerException: metadata entry value is null"
    )
  }

  test("a fault with no cause is its message alone") {
    assertEquals(
      GrpcConversation.describe(
        Status.UNAVAILABLE.withDescription("io exception").asRuntimeException()
      ),
      "UNAVAILABLE: io exception"
    )
  }

  test("a cause with no message is named by its class, and a chain is bounded") {
    val inner = IllegalStateException()
    val outer = RuntimeException("stream ended", inner)
    assertEquals(
      GrpcConversation.describe(outer),
      "stream ended, caused by IllegalStateException: IllegalStateException"
    )
    val loop = RuntimeException("a")
    loop.initCause(RuntimeException("b", loop))
    assert(GrpcConversation.describe(loop).count(_ == ',') <= 8)
  }
