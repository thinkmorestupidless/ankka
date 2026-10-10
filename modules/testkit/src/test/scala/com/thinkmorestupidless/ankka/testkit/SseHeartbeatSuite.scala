package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, HttpServer}
import com.typesafe.config.ConfigFactory
import org.apache.pekko.stream.scaladsl.Source

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A route that sends one event, says nothing for five seconds, then sends another. */
final class QuietEndpoint extends HttpEndpoint(""):
  val acl: Acl = Acl.AllowAll
  sse("/quiet")(() => Source.single("first") ++ Source.single("second").initialDelay(5.seconds))

/**
 * A quiet event stream outlives the server's idle timeout, because the service sends a heartbeat —
 * an event with no data, which a browser does not dispatch — while it is quiet; and a heartbeat
 * that could not do that stops the service from starting. The idle timeout is two seconds here, so
 * a stream without a heartbeat would be cut off before its second event.
 */
class SseHeartbeatSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private def started(heartbeat: String): (AnkkaTestKit, HttpServer) =
    val server = HttpServer.at("127.0.0.1", 0)(_ => QuietEndpoint())
    val kit = AnkkaTestKit.start(
      Nil,
      Seq(server),
      settings = ConfigFactory.parseString(
        s"""pekko.http.server.idle-timeout = 2s
           |ankka.http.socket.keep-alive = 1s
           |ankka.http.sse.heartbeat = $heartbeat""".stripMargin
      )
    )
    (kit, server)

  test("a quiet stream is kept open by heartbeats past the idle timeout") {
    val (kit, server) = started("500ms")
    try
      val port = server.boundPort.getOrElse(fail("not bound"))
      val lines = HttpClient
        .newHttpClient()
        .send(
          HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port/quiet")).GET().build(),
          HttpResponse.BodyHandlers.ofLines()
        )
        .body
        .iterator
        .asScala
        .toVector
      val data = lines.filter(_.startsWith("data:")).map(_.stripPrefix("data:").trim)
      // A heartbeat is an event with no data, which a browser's EventSource does not dispatch.
      assertEquals(data.filter(_.nonEmpty), Vector("\"first\"", "\"second\""))
      assert(data.count(_.isEmpty) >= 3, s"too few heartbeats for five quiet seconds: $lines")
    finally kit.stop()
  }

  test("a heartbeat not shorter than the idle timeout stops the service from starting") {
    val outcome = Try(started("3s"))
    outcome.foreach((kit, _) => kit.stop())
    val message = outcome.failed.toOption.flatMap(e =>
      Iterator
        .iterate(e)(_.getCause)
        .takeWhile(_ != null)
        .map(_.getMessage)
        .find(m => m != null && m.contains("heartbeat"))
    )
    assert(
      message.exists(_.contains("must be shorter than")),
      s"started, or refused otherwise: $outcome"
    )
  }
