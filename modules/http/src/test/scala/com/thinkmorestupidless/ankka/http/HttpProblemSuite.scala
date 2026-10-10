package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * How a refusal and a failed workflow reach an HTTP caller: the status, and the details in the
 * body.
 */
class HttpProblemSuite extends munit.FunSuite:

  private val failed = CommandError(
    "workflow quote 'q2' failed at step 'margin': no rates",
    ErrorCode.WorkflowFailed,
    Map("step" -> "margin", "reason" -> "no rates")
  )

  test("a failed workflow is 424, told apart from every refusal and every fault") {
    val problem = HttpProblem.from(failed)
    assertEquals(problem.status, 424)
    assertEquals(problem.details, failed.details)
    val others = ErrorCode.values.filterNot(_ == ErrorCode.WorkflowFailed)
    others.foreach { code =>
      assertNotEquals(HttpProblem.from(CommandError("x", code)).status, 424, code.toString)
    }
  }

  test("a refusal carries no details") {
    assertEquals(HttpProblem.from(CommandError("no", ErrorCode.Conflict)).details, Map.empty)
  }

  private given system: ActorSystem[Nothing] =
    ActorSystem(
      Behaviors.empty,
      "http-problem",
      com.typesafe.config.ConfigFactory
        .parseString("pekko.actor.provider = local")
        .withFallback(com.typesafe.config.ConfigFactory.load())
    )

  private final class Quotes extends HttpEndpoint("/quotes"):
    val acl: Acl = Acl.AllowAll
    get("/{id}")((id: String) =>
      val refusal = if id == "q2" then failed else CommandError("no", ErrorCode.Conflict)
      if id.nonEmpty then throw refusal
      id
    )

  private val server = HttpServer.at("127.0.0.1", 0)()
  private val client = HttpClient.newHttpClient()

  override def beforeAll(): Unit =
    server.serve(Vector(new Quotes), "127.0.0.1", 0, 5.seconds)

  override def afterAll(): Unit =
    server.stop()
    system.terminate()
    Await.ready(system.whenTerminated, 10.seconds): Unit

  private def fetch(id: String): HttpResponse[String] =
    client.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${server.boundPort.get}/quotes/$id"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("the body of a failed workflow's answer carries its details beside the message") {
    val response = fetch("q2")
    assertEquals(response.statusCode(), 424)
    assertEquals(
      response.body(),
      """{"status":424,"error":"workflow quote 'q2' failed at step 'margin': no rates","details":{"reason":"no rates","step":"margin"}}"""
    )
  }

  test("the body of a refusal is as it always was") {
    val response = fetch("q1")
    assertEquals(response.statusCode(), 409)
    assertEquals(response.body(), """{"status":409,"error":"no"}""")
  }
