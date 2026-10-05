package com.thinkmorestupidless.ankka.testkit

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.sdk.{
  ServiceCallFailed,
  ServiceIdentityMismatch,
  ServiceUnanswered,
  ServiceUnresolvable
}

/** The unit-test double for other services answers as scripted and records what it was asked. */
class ScriptedServicesSuite extends munit.FunSuite:

  final case class Payout(amount: Int)
  given JsonValueCodec[Payout] = JsonCodecMaker.make

  test("a scripted answer is returned, and the request is recorded") {
    val services = ScriptedServices().answer("psp")(_ => ScriptedServices.text("hello"))
    assertEquals(services("psp").getText("/hi"), "hello")
    val request = services.requests.single
    assertEquals(
      (request.project, request.service, request.method, request.path),
      ("local", "psp", "GET", "/hi")
    )
  }

  test("post encodes its body as JSON, and get decodes the answer") {
    val services = ScriptedServices().answer("psp")(request => ScriptedServices.json(request.text))
    assertEquals(services("psp").post[Payout, Payout]("/payouts", Payout(5)), Payout(5))
    assertEquals(services.requests.single.contentType, Some("application/json"))
  }

  test("an answer outside 2xx is returned by request and raised by a typed helper") {
    val services = ScriptedServices().answer("psp")(_ => ScriptedServices.text("gone", 404))
    assertEquals(services("psp").request("GET", "/x").status, 404)
    val failed = intercept[ServiceCallFailed](services("psp").getText("/x"))
    assertEquals((failed.status, failed.body), (404, "gone"))
  }

  test("each scripted failure is raised as its own error") {
    val services = ScriptedServices()
      .unresolvable("a")
      .unanswered("b")
      .mismatch("c")
    intercept[ServiceUnresolvable](services("a").getText("/"))
    intercept[ServiceUnanswered](services("b").getText("/"))
    intercept[ServiceIdentityMismatch](services("c").getText("/"))
  }

  test("a service in another project is scripted and called by project and name") {
    val services = ScriptedServices().answer("invoices", "billing")(_ => ScriptedServices.text("i"))
    assertEquals(services("billing", "invoices").getText("/"), "i")
    intercept[AssertionError](services("invoices").getText("/"))
  }

  test("a call to a service nothing is scripted for fails the test, naming the service") {
    val e = intercept[AssertionError](ScriptedServices()("ledger").getText("/"))
    assert(e.getMessage.contains("local/ledger"), e.getMessage)
    assert(e.getMessage.contains("""answer("ledger")"""), e.getMessage)
  }

  extension [A](as: Vector[A])
    private def single: A =
      assertEquals(as.size, 1, as.toString)
      as.head
