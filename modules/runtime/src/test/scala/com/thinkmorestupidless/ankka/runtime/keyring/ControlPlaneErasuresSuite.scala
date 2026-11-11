package com.thinkmorestupidless.ankka.runtime.keyring

import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.core.{CommandError, DataSubject, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.ControlPlaneHttp

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

/** `ctx.erasures.ask` against a control plane that answers as the real route does (feature 042). */
class ControlPlaneErasuresSuite extends munit.FunSuite:

  private val asked  = AtomicReference[(String, String)](("", ""))
  private val answer = AtomicReference[(Int, String)]((201, ""))

  private val server = FunFixture[ControlPlaneErasures](
    _ =>
      val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
      s.createContext(
        "/",
        exchange =>
          val body = String(exchange.getRequestBody.readAllBytes(), UTF_8)
          asked.set((s"${exchange.getRequestMethod} ${exchange.getRequestURI}", body))
          val (status, text) = answer.get
          val bytes          = text.getBytes(UTF_8)
          exchange.sendResponseHeaders(status, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
          exchange.close()
      )
      s.start()
      stopping.set(s)
      ControlPlaneErasures(
        ControlPlaneHttp(s"http://127.0.0.1:${s.getAddress.getPort}", None, 5.seconds),
        "brand"
      )
    ,
    _ => Option(stopping.get).foreach(_.stop(0))
  )
  private val stopping = AtomicReference[HttpServer](null)

  server.test("an ask goes to the service's own project, and answers the request's id and state") {
    erasures =>
      answer.set((201, """{"id":"01J","subject":"player/8c1f","state":"applying","extra":1}"""))
      val got = erasures.ask(
        DataSubject("player/8c1f"),
        notBefore = Some(LocalDate.parse("2027-01-31")),
        correlation = Some("closure-4411")
      )
      assertEquals((got.id, got.state), ("01J", "applying"))
      assertEquals(asked.get._1, "POST /projects/brand/erasures")
      assertEquals(
        asked.get._2,
        """{"subject":"player/8c1f","notBefore":"2027-01-31T00:00:00Z","correlation":"closure-4411"}"""
      )
  }

  server.test("an ask held until a date carries its reason and its correlation id") { erasures =>
    answer.set((201, """{"id":"03L","state":"held"}"""))
    // docs:start ask
    val asked = erasures.ask(
      DataSubject("player/8c1f"),
      notBefore = Some(LocalDate.parse("2031-10-08")),
      reason = Some("aml-retention"),
      correlation = Some("closure-4411")
    )
    // asked.id, asked.state: "held", "applying" or "applied"
    // docs:end ask
    assertEquals((asked.id, asked.state), ("03L", "held"))
    assertEquals(
      this.asked.get._2,
      """{"subject":"player/8c1f","notBefore":"2031-10-08T00:00:00Z","reason":"aml-retention","correlation":"closure-4411"}"""
    )
  }

  server.test("an ask names another project when told to") { erasures =>
    answer.set((201, """{"id":"02K","state":"held"}"""))
    erasures.ask(DataSubject("cardholder/77a0"), project = Some("payments")): Unit
    assertEquals(asked.get._1, "POST /projects/payments/erasures")
  }

  server.test("the control plane's refusal is answered with its code and its words") { erasures =>
    answer.set((403, """{"status":403,"error":"service players of brand has no erasure grant"}"""))
    val refused = intercept[CommandError](erasures.ask(DataSubject("player/8c1f")))
    assertEquals(refused.code, ErrorCode.Forbidden)
    assert(refused.getMessage.contains("has no erasure grant"), refused.getMessage)
    answer.set((409, """{"error":"erasure is off on this installation (ANKKA_ERASURE)"}"""))
    assertEquals(intercept[CommandError](erasures.ask(DataSubject("a"))).code, ErrorCode.Conflict)
  }

  test("a service that reaches no control plane is refused every ask, saying why") {
    val none = ControlPlaneErasures.from(
      com.typesafe.config.ConfigFactory.parseString(
        """ankka.secrets.records-url = "", ankka.secrets.record-timeout = 5s"""
      ),
      Some("brand")
    )
    val refused = intercept[CommandError](none.ask(DataSubject("player/8c1f")))
    assertEquals(refused.code, ErrorCode.Unavailable)
  }
