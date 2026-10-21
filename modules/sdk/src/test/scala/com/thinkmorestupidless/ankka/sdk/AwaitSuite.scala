package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.*

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*

/**
 * A blocking call that runs out of time is a timeout the caller can read, whether the transport or
 * the wait ran out first: an endpoint answers it as a timeout, not as an internal error.
 */
class AwaitSuite extends munit.FunSuite:

  test("a wait that runs out is a timeout, not an exception no handler expects") {
    val refused = intercept[CommandError](ComponentClient.await(Promise[Int]().future, 20.millis))
    assertEquals(refused.code, ErrorCode.Timeout)
    assertEquals(refused.getMessage, "no reply within 20 milliseconds")
  }

  test("a call that fails before the wait runs out keeps its own failure") {
    val refused = intercept[CommandError](
      ComponentClient.await(
        Future.failed(CommandError("did not reply", ErrorCode.Timeout)),
        1.second
      )
    )
    assertEquals(refused.getMessage, "did not reply")
  }

  test("a call answered in time is its answer") {
    assertEquals(ComponentClient.await(Future.successful(7), 1.second), 7)
  }
