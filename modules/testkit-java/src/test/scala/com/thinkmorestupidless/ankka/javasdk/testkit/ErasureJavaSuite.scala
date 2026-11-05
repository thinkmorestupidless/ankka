package com.thinkmorestupidless.ankka.javasdk.testkit

import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.core.DataSubject
import com.thinkmorestupidless.ankka.core.keyring.HandlerReport
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.erasure.ErasureReport
import com.thinkmorestupidless.ankka.javasdk.testkit.bridge.ServiceKit
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.{PlayerErasures, Players}
import com.thinkmorestupidless.ankka.runtime.ServiceIdentity
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.InetSocketAddress
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * A Java service's part of an erasure (feature 042, FR-010 and FR-008): its erasure handler runs on
 * each application and re-run, its report reaches the keyring, and a handler that throws is
 * reported as failed, as a Scala service's are (`ErasureHandlerSuite`); and an endpoint asks for an
 * erasure of the control plane as the service, in its own project and in another.
 */
class ErasureJavaSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private def eventually[A](what: String)(probe: => Option[A]): A =
    Iterator
      .continually { Thread.sleep(100); probe }
      .take(300)
      .collectFirst { case Some(a) => a }
      .getOrElse(fail(s"never: $what"))

  test("a Java erasure handler runs for each application, re-runs included, and reports") {
    PlayerErasures.runs.clear()
    val kit = AnkkaTestKit.start(
      Ankka
        .service("players")
        .register(classOf[Players])
        .withErasureHandler(PlayerErasures.handle)
    )
    try
      val bucket = kit.storage().defaultBucket()
      bucket.putBytes("subjects/player%2F8c1f/passport.png", Array[Byte](1), "image/png")
      bucket.putBytes("subjects/player%2F8c1f/selfie.png", Array[Byte](2), "image/png")
      bucket.putBytes("subjects/player%2F77a0/passport.png", Array[Byte](3), "image/png")
      val scala = kit.bridge().scalaKit
      val seq   = kit.erase("player/8c1f")
      val ack   = eventually("the acknowledgement")(scala.keyring.acks.find(_.seq == seq))
      assertEquals(ack.completion.handler, Some(HandlerReport(2, HandlerReport.Immediate)))
      assertEquals(
        bucket.list("subjects/").asScala.map(_.name).toVector,
        Vector(
          "subjects/player%2F77a0/passport.png"
        )
      )
      val again = scala.keyring.erase(scala.project, DataSubject("player/8c1f"), rerun = true)
      val rerun =
        eventually("the re-run's acknowledgement")(scala.keyring.acks.find(_.seq == again))
      assertEquals(rerun.completion.handler, Some(HandlerReport(0, HandlerReport.Immediate)))
      assertEquals(
        PlayerErasures.runs.asScala.toVector,
        Vector(
          PlayerErasures.Run("player/8c1f", seq, false),
          PlayerErasures.Run("player/8c1f", again, true)
        )
      )
    finally kit.close()
  }

  test("a Java erasure handler runs in a group of installations, restarted ones included") {
    val group = AnkkaTestKit.installations("a", "b")
    try
      def service = Ankka
        .service("players")
        .register(classOf[Players])
        .withErasureHandler((_, _) => ErasureReport.immediate(4))
      def ranAt(kit: AnkkaTestKit) =
        val scala = kit.bridge().scalaKit
        val seq   = kit.erase("player/8c1f")
        eventually("the acknowledgement")(scala.keyring.acks.find(_.seq == seq)).completion.handler
      assertEquals(
        ranAt(group.start("a", service)),
        Some(HandlerReport(4, HandlerReport.Immediate))
      )
      assertEquals(
        ranAt(group.restart("a", service)),
        Some(HandlerReport(4, HandlerReport.Immediate))
      )
    finally group.close()
  }

  test("a Java handler's report says when what it erased is gone for good") {
    val kit = AnkkaTestKit.start(
      Ankka
        .service("players")
        .register(classOf[Players])
        .withErasureHandler((_, _) => ErasureReport.after(3, java.time.Duration.ofDays(7)))
    )
    try
      val seq = kit.erase("player/8c1f")
      val ack =
        eventually("the acknowledgement")(kit.bridge().scalaKit.keyring.acks.find(_.seq == seq))
      assertEquals(ack.completion.handler, Some(HandlerReport(3, "after:PT168H")))
    finally kit.close()
  }

  test(
    "a Java handler that throws is reported as failed, and the platform's duties are acknowledged"
  ) {
    val kit = AnkkaTestKit.start(
      Ankka
        .service("players")
        .register(classOf[Players])
        .withErasureHandler((_, _) => throw IllegalStateException("no bucket 'kyc'"))
    )
    try
      kit
        .componentClient()
        .forEventSourcedEntity("8c1f")
        .call(PlayerRefs.register)
        .invoke("ada@example.com")
      val seq = kit.erase("player/8c1f")
      val ack =
        eventually("the acknowledgement")(kit.bridge().scalaKit.keyring.acks.find(_.seq == seq))
      assertEquals(ack.completion.handler, Some(HandlerReport.failed("no bucket 'kyc'")))
      kit.restartService()
      assertEquals(
        kit.componentClient().forEventSourcedEntity("8c1f").call(PlayerRefs.email).invoke(),
        "erased"
      )
    finally kit.close()
  }

  test("a Java service with no handler reports none") {
    val kit = AnkkaTestKit.start(Ankka.service("players").register(classOf[Players]))
    try
      val seq = kit.erase("player/8c1f")
      assertEquals(
        eventually("the acknowledgement")(
          kit.bridge().scalaKit.keyring.acks.find(_.seq == seq)
        ).completion.handler,
        None
      )
    finally kit.close()
  }

  // ── Asking (FR-008) ─────────────────────────────────────────────────────────────────────────

  /** The control plane's erasure route as the real one answers it; `refuse` answers 403. */
  private final class FakePlane(refuse: Boolean = false):
    val asked  = ConcurrentLinkedQueue[(String, String)]()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange =>
        val body             = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
        asked.add(exchange.getRequestURI.getPath -> body)
        val (status, answer) =
          if refuse then 403 -> """{"error":"players has no erasure grant in payments"}"""
          else 201           -> """{"id":"er-1","state":"applying","subject":"x"}"""
        val bytes = answer.getBytes(UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.start()
    def url: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  private def askingKit(plane: FakePlane) =
    new AnkkaTestKit(
      ServiceKit.start(
        Ankka.service("players").withEndpoint(classOf[PlayerErasures.Asking]),
        Settings.defaults().withConfig("ankka.secrets.records-url", plane.url),
        ServiceIdentity.deployed("brand", "players")
      )
    )

  private def post(kit: AnkkaTestKit, path: String): HttpResponse[String] =
    kit
      .httpClient()
      .send(
        HttpRequest
          .newBuilder(kit.http().resolve(path))
          .POST(HttpRequest.BodyPublishers.noBody())
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )

  test("a Java endpoint asks for an erasure in its own project, as the service") {
    val plane = FakePlane()
    val kit   = askingKit(plane)
    try
      val response = post(kit, "/forget/8c1f")
      assertEquals((response.statusCode, response.body), (200, "er-1 applying"))
      val (path, body) = plane.asked.asScala.head
      assertEquals(path, "/projects/brand/erasures")
      assert(body.contains("\"subject\":\"player/8c1f\""), body)
    finally
      kit.close()
      plane.server.stop(0)
  }

  test("a Java endpoint asks in another project, with a reason and a correlation id") {
    val plane = FakePlane()
    val kit   = askingKit(plane)
    try
      assertEquals(post(kit, "/forget-cardholder/77a0").statusCode, 200)
      val (path, body) = plane.asked.asScala.head
      assertEquals(path, "/projects/payments/erasures")
      assert(body.contains("\"subject\":\"cardholder/77a0\""), body)
      assert(body.contains("\"reason\":\"account closed\""), body)
      assert(body.contains("\"correlation\":\"closure-4411\""), body)
    finally
      kit.close()
      plane.server.stop(0)
  }

  test("the control plane's refusal of a Java ask is the route's refusal, in its words") {
    val plane = FakePlane(refuse = true)
    val kit   = askingKit(plane)
    try
      val response = post(kit, "/forget-cardholder/77a0")
      assertEquals(response.statusCode, 403, response.body)
      assert(response.body.contains("no erasure grant"), response.body)
    finally
      kit.close()
      plane.server.stop(0)
  }

  test("a Java service that reaches no control plane is refused its ask as unavailable") {
    val kit = AnkkaTestKit.start(
      Ankka.service("players").withEndpoint(classOf[PlayerErasures.Asking])
    )
    try
      val response = post(kit, "/forget/8c1f")
      assertEquals(response.statusCode, 503, response.body)
      assert(response.body.contains("control plane"), response.body)
    finally kit.close()
  }
