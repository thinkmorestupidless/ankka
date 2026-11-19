package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.http.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
 * The munit side of holding a service to its document (feature 067): a suite that mixes the trait
 * in fails a test whose service did what its document does not allow, naming the route; a suite
 * that does not is unaffected by the same requests. The scenarios are `OpenApiFeatures`'.
 */
class HeldToDocumentSuite extends munit.FunSuite with LogCapturing:

  /** A score whose shape says a number, written as text when negative: a codec that lies. */
  private final case class Score(n: Int)
  private given ToResponse[Score] = new ToResponse[Score]:
    def status(value: Score) = 200
    val contentType          = "application/json"
    def write(value: Score)  =
      (if value.n < 0 then "\"none\"" else value.n.toString)
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
    override def shape: Option[com.thinkmorestupidless.ankka.core.shape.ShapeOf[?]] =
      Some(summon[com.thinkmorestupidless.ankka.core.shape.ShapeOf[Int]])

  private final class Players extends HttpEndpoint("/players"):
    val acl: Acl = Acl.AllowAll
    // The handler decides the status, so the document describes the answer as `default`.
    get("/{playerId}/score")((id: String) => Respond(Score(if id == "liar" then -1 else 3), 202))
    described(_.answers[Int](200)) {
      get("/{playerId}") { (id: String) =>
        if id == "taken" then throw HttpProblem.conflict("taken") else id.length
      }
    }

  private val server = HeldToDocument.server(HttpServer.at("127.0.0.1", 0)(_ => Players()))
  private val kit    = AnkkaTestKit.start(Seq.empty, Seq(server))
  private val http   = HttpClient.newHttpClient()

  override def afterAll(): Unit = kit.stop()

  private def status(path: String): Int =
    val uri = URI.create(s"http://127.0.0.1:${server.boundPort.get}$path")
    http
      .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding())
      .statusCode

  /** A test of `suite` whose body is `body`, run through that suite's own transforms. */
  private def runIn(suite: munit.FunSuite)(body: => Unit): Try[Any] =
    val test = munit.Test(
      "a test",
      () => Future(body)(using ExecutionContext.global),
      Set.empty,
      munit.Location.empty
    )
    Try(Await.result(suite.munitTestTransforms.foldLeft(test)((t, f) => f(t)).body(), 30.seconds))

  test("a held suite fails a test whose service answered what its document does not name") {
    val ran =
      runIn(new munit.FunSuite with HeldToDocument {})(assertEquals(status("/players/taken"), 409))
    val failure = ran.failed.getOrElse(fail("the test passed"))
    assert(
      failure.getMessage.contains("GET /players/{playerId}: answered 409, which its description"),
      failure.getMessage
    )
    assertEquals(HeldToDocument.violations, Vector.empty, "assertHeld clears what it reported")
  }

  test("a held suite passes a test whose service answered as its document says") {
    val ran =
      runIn(new munit.FunSuite with HeldToDocument {})(assertEquals(status("/players/ann"), 200))
    assert(ran.isSuccess, ran.toString)
  }

  test("a body the handler's own status carries is held to the route's default answer") {
    HeldToDocument.clear()
    assertEquals(status("/players/ann/score"), 202)
    assertEquals(HeldToDocument.violations, Vector.empty)
    assertEquals(status("/players/liar/score"), 202)
    val found = HeldToDocument.violations
    HeldToDocument.clear()
    assert(
      found.exists(
        _.startsWith("GET /players/{playerId}/score: answered 202 with a body its shape")
      ),
      found.toString
    )
  }

  test("a server recording into a record of its own leaves the shared one alone") {
    val own   = HeldToDocument.Violations()
    val other = HeldToDocument.server(HttpServer.at("127.0.0.1", 0)(_ => Players()), own)
    val kit2  = AnkkaTestKit.start(Seq.empty, Seq(other))
    try
      HeldToDocument.clear()
      val uri = URI.create(s"http://127.0.0.1:${other.boundPort.get}/players/taken")
      http.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding())
      assertEquals(HeldToDocument.violations, Vector.empty)
      assertEquals(own.all.size, 1, own.all.toString)
      HeldToDocument.clear()
      assertEquals(own.all.size, 1, "clearing the shared record cleared another")
    finally kit2.stop()
  }

  test("a suite that does not mix the trait in is unaffected") {
    val ran = runIn(new munit.FunSuite {})(assertEquals(status("/players/taken"), 409))
    assert(ran.isSuccess, ran.toString)
    HeldToDocument.clear()
  }
