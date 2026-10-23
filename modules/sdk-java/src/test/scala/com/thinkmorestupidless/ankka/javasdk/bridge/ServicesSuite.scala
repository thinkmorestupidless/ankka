package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.routes.PriceEndpoint
import com.thinkmorestupidless.ankka.sdk.ServiceResponse
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing, ScriptedService}

import java.nio.charset.StandardCharsets.UTF_8

/** A Java endpoint calls another service through `services()`, its body and reply by the codec. */
class ServicesSuite extends munit.FunSuite with LogCapturing:

  private var pricing: ScriptedService = null
  private var kit: AnkkaTestKit        = null
  private lazy val http                = HttpCalls(() => kit.service.boundAddresses.head)

  override def beforeAll(): Unit =
    pricing = ScriptedService.start()
    val read = JavaServiceBuilder.read(Vector.empty, Map.empty, Vector(classOf[PriceEndpoint]))
    kit = AnkkaTestKit.start(
      Nil,
      extensions = Seq(read.httpServer(Some("127.0.0.1" -> 0))),
      localServices = Map("pricing" -> pricing.address)
    )
  override def afterAll(): Unit =
    if kit != null then kit.stop()
    if pricing != null then pricing.stop()

  private def json(text: String) =
    ServiceResponse(200, "application/json", text.getBytes(UTF_8), Vector.empty)

  test("a reply read by the record codec") {
    pricing.answer(r => json(s"""{"id":"${r.path.stripPrefix("/prices/")}","size":12}"""))
    assertEquals(http.get("/price/tea").body, """{"id":"tea","size":12}""")
    assertEquals(pricing.requests.last.method, "GET")
  }

  test("a body written by the record codec; a refusal is a ServiceCallFailed") {
    pricing.clear()
    pricing.answer(r => json(r.text))
    assertEquals(http.post("/quote/cup").body, "cup")
    assertEquals(pricing.requests.last.text, """{"id":"cup","size":1}""")
    pricing.failNext(409)
    assertEquals(http.post("/quote/cup").body, "pricing answered 409")
  }
