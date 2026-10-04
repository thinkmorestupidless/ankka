package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.sdk.ServiceResponse
import munit.FunSuite

import java.net.http.HttpTimeoutException
import scala.util.{Failure, Success}

/** How a call to another service ended, as its caller saw it: for its span and for its count. */
final class CallOutcomeSuite extends FunSuite:

  private def answered(status: Int) =
    HttpServiceClients.outcomeOf(
      Success(ServiceResponse(status, "", Array.emptyByteArray, Vector.empty))
    )

  test("a 2xx or 3xx is ok, a 4xx refused, a 5xx failed") {
    assertEquals(answered(200), SpanOutcome.Ok)
    assertEquals(answered(304), SpanOutcome.Ok)
    assertEquals(answered(404), SpanOutcome.Refused)
    assertEquals(answered(503), SpanOutcome.Failed)
  }

  test("a call its caller stopped waiting for timed out; any other that got no answer failed") {
    assertEquals(
      HttpServiceClients.outcomeOf(Failure(HttpTimeoutException("slow"))),
      SpanOutcome.TimedOut
    )
    assertEquals(
      HttpServiceClients.outcomeOf(Failure(RuntimeException("refused"))),
      SpanOutcome.Failed
    )
  }
