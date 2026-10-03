package com.thinkmorestupidless.ankka.proxy.core

import scala.concurrent.duration.*

/** The five answers the proxy gives by itself. */
class AnswersSuite extends munit.FunSuite:

  test("every answer is marked as the proxy's and is JSON") {
    val answers = Vector(
      Answers.refused("unrecognised caller certificate"),
      Answers.notAdmitted(Sender.Service("shop", "orders")),
      Answers.namesNoService("http://127.0.0.1:7630"),
      Answers.closedBeforeAnswering("the process"),
      Answers.notTheServiceAskedFor("shop", "cart"),
      Answers.notListening,
      Answers.cannotBeFound("shop", "ledger"),
      Answers.noAnswerInTime("the process", 60.seconds)
    )
    for answer <- answers do
      assert(answer.headers.contains("X-Ankka-Answered-By" -> "proxy"), answer)
      assert(answer.headers.contains("Content-Type" -> "application/json"), answer)
      assertEquals(answer.body, s"""{"error":"${answer.reason}"}""")
  }

  test("the statuses are the contract's") {
    assertEquals(Answers.refused("x").status, 403)
    assertEquals(Answers.notAdmitted(Sender.Local).status, 403)
    assertEquals(Answers.namesNoService("u").status, 400)
    assertEquals(Answers.closedBeforeAnswering("the process").status, 502)
    assertEquals(Answers.notTheServiceAskedFor("p", "s").status, 502)
    assertEquals(Answers.notListening.status, 503)
    assertEquals(Answers.cannotBeFound("p", "s").status, 503)
    assertEquals(Answers.noAnswerInTime("the process", 60.seconds).status, 504)
  }

  test("a refusal names the service that was refused") {
    assertEquals(
      Answers.notAdmitted(Sender.Service("shop", "orders")).reason,
      "the caller is not admitted: service shop/orders"
    )
  }

  test("a service that cannot be found is named with its project") {
    assertEquals(
      Answers.cannotBeFound("shop", "ledger").reason,
      "the service shop/ledger cannot be found"
    )
  }

  test("a call that names no service is told the shape of one") {
    assertEquals(
      Answers.namesNoService("http://127.0.0.1:7630").reason,
      "a call names a service: http://127.0.0.1:7630/<service>/<path>"
    )
  }

  test("the time an answer was waited for is in seconds") {
    assertEquals(
      Answers.noAnswerInTime("the service shop/cart", 60.seconds).reason,
      "the service shop/cart did not answer within 60 seconds"
    )
  }

  test("a reason containing a quote, a backslash or a newline is still valid JSON") {
    val answer = Answer(502, "the \"process\" said \\ and\nleft\t\u0001")
    assertEquals(
      answer.body,
      """{"error":"the \"process\" said \\ and\nleft\t\u0001"}"""
    )
  }
