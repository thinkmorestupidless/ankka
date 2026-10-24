package com.thinkmorestupidless.ankka.javasdk.testkit.kinds

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.http.{Caller, CloseReason}
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt
import scala.jdk.OptionConverters.*

/**
 * A Java endpoint that names its callers, read through the Java test kit: a request made to arrive
 * as a caller, a query parameter that is required and a flag, and a socket.
 */
class CallersSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(Ankka.service("gate").withEndpoint(classOf[Gate]))

  override def afterAll(): Unit = if kit != null then kit.close()

  private def get(path: String, as: Option[Caller] = None): (Int, String) =
    val request = HttpRequest.newBuilder(URI.create(s"${kit.http()}$path"))
    as.foreach { caller =>
      val header = kit.asCaller(caller)
      request.header(header.getKey, header.getValue)
    }
    val response = kit.httpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    response.statusCode() -> response.body()

  test("a caller the matchers name is admitted, and one they do not is refused") {
    assertEquals(get("/gate/who", Some(Caller.Gateway())), 200 -> "the internet")
    assertEquals(get("/gate/who", Some(Caller.Service("elsewhere", "billing")))._1, 403)
    assertEquals(get("/gate/who"), 200 -> "this machine")
  }

  test("a flag is set by its name alone or by true") {
    assertEquals(get("/gate/who?loud")._2, "THIS MACHINE")
    assertEquals(get("/gate/who?loud=true")._2, "THIS MACHINE")
    assertEquals(get("/gate/who?loud=no")._2, "this machine")
  }

  test("a required query parameter's absence is a bad request naming it") {
    assertEquals(get("/gate/need?q=it"), 200 -> "got it")
    val (status, body) = get("/gate/need")
    assertEquals(status, 400)
    assert(body.contains("query parameter 'q' is required"), body)
  }

  test("a socket opened by the kit answers each frame, and its close is read as a reason") {
    val socket = kit.socket("/gate/shout")
    socket.send("hi")
    assertEquals(socket.receive().toScala, Some("HI"))
    socket.close()
    val closed = socket.closed()
    assertEquals(closed.code, 1000, closed.toString)
    assert(!closed.is(CloseReason.FAILED))
  }

  test("a frame sent on a socket already closed throws SocketClosed, saying how it closed") {
    Gate.lateReason.set(null)
    val socket = kit.socket("/gate/late")
    socket.close()
    socket.closed(): Unit
    kit.eventually(
      java.time.Duration.ofSeconds(10),
      () => assert(Gate.lateReason.get != null, "the handler never sent")
    )
  }

  test("a socket refused at its opening says how") {
    // A path no route serves is refused by default, before anything could be upgraded.
    val refused = intercept[com.thinkmorestupidless.ankka.javasdk.testkit.TestSocket.Refused](
      kit.socket("/gate/nowhere")
    )
    assertEquals(refused.status, 403, refused.headers.toString)
  }
