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

  private final class Players extends HttpEndpoint("/players"):
    val acl: Acl = Acl.AllowAll
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

  test("a suite that does not mix the trait in is unaffected") {
    val ran = runIn(new munit.FunSuite {})(assertEquals(status("/players/taken"), 409))
    assert(ran.isSuccess, ran.toString)
    HeldToDocument.clear()
  }
