package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.http.HttpServer

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.OptionConverters.*

/**
 * Token streaming all the way out: agent -> token ref -> pekko stream -> SSE -> a plain JDK HTTP
 * client reading the wire.
 */
class HttpSseSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null
  private var server: HttpServer    = null
  private var baseUrl: String       = ""
  private val model                 = TestModelProvider()

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  override def beforeAll(): Unit =
    server = HttpServer.at("127.0.0.1", 0)(clients => ChatEndpoint(clients.componentClient))
    testKit = AnkkaTestKit.start(
      Seq(WeatherAgent.descriptor, ApprovalAgent.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model), server)
    )
    baseUrl = s"http://127.0.0.1:${server.boundPort.getOrElse(fail("server did not bind"))}"

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit = model.reset()

  private def get(path: String): (Int, String, Option[String]) =
    val response = http.send(
      JdkRequest
        .newBuilder(URI.create(baseUrl + path))
        .timeout(Duration.ofSeconds(60))
        .GET()
        .build(),
      JdkResponse.BodyHandlers.ofString()
    )
    (
      response.statusCode,
      response.body,
      response.headers.firstValue("content-type").toScala
    )

  /**
   * SSE frames are `data: <json>` lines. The payload is a JSON string, so whitespace and newlines
   * inside a token survive the wire exactly.
   */
  private def dataLines(body: String): Vector[String] =
    body.linesIterator
      .collect { case line if line.startsWith("data:") => line.drop(5).trim }
      .map(json =>
        Json.parse(json).flatMap(_.asString.toRight("not a string")).fold(fail(_), identity)
      )
      .toVector

  test("a stream ends with the approval request as its last part") {
    model.expect(
      ModelResponse(
        text = "Let me ask a supervisor.",
        toolCalls = Vector(
          ToolCall(
            "call-refund",
            "issue_refund",
            Json.obj("order" -> Json.str("o-7"), "amount" -> Json.num(40))
          )
        ),
        stopReason = StopReason.ToolUse
      )
    ): Unit
    ApprovalAgent.runs.clear()

    val (status, body, _) = get("/chat/support/s-stream-approval")

    assertEquals(status, 200)
    val frames           = body.split("\n\n").toVector.map(_.trim).filter(_.nonEmpty)
    val (named, unnamed) = frames.partition(_.linesIterator.exists(_.startsWith("event:")))
    // The text the model wrote before its tool call came first, as ordinary events.
    assertEquals(dataLines(unnamed.mkString("\n")).mkString, "Let me ask a supervisor.")
    // Then one event named `approval`, last, carrying the request; then the stream ended.
    assertEquals(named.size, 1, frames.mkString("\n---\n"))
    assertEquals(frames.last, named.head)
    assert(
      named.head.linesIterator.exists(_.replace(" ", "") == "event:approval"),
      named.head
    )
    assert(named.head.contains("\"tool\":\"issue_refund\""), named.head)
    assertEquals(ApprovalAgent.runsOf("issue_refund"), Vector.empty)
  }

  test("a plain source is served as server-sent events") {
    val (status, body, contentType) = get("/chat/fixed/s-1")
    assertEquals(status, 200)
    assert(contentType.exists(_.startsWith("text/event-stream")), contentType.toString)
    assertEquals(dataLines(body), Vector("one", "two", "three-s-1"))
  }

  test("an agent's tokens reach the client as separate events") {
    model.expectText("It is sunny in Berlin")

    val (status, body, _) = get("/chat/sse-agent")
    assertEquals(status, 200)

    val events = dataLines(body)
    // One event per token, not one event with the whole answer.
    assert(events.sizeIs > 1, s"expected several events, got $events")
    assertEquals(events.mkString(""), "It is sunny in Berlin")
  }

  test("tool preamble and answer both reach the client, in order") {
    model
      .expect(
        ModelResponse(
          text = "Checking",
          toolCalls =
            Vector(ToolCall("c1", "get_weather", Json.obj("location" -> Json.str("Berlin")))),
          stopReason = StopReason.ToolUse
        )
      )
      .expectText("Berlin is 18C")

    val (_, body, _) = get("/chat/sse-tool")
    assertEquals(dataLines(body).mkString(""), "CheckingBerlin is 18C")
  }

  test("whitespace and newlines inside a token survive the wire exactly") {
    // Regression: raw text in a `data:` field loses a leading space and a newline splits
    // the frame, so a token stream would silently reassemble wrong.
    val (status, body, _) = get("/chat/awkward")
    assertEquals(status, 200)
    assertEquals(
      dataLines(body),
      Vector("word", " leading", "trailing ", "with\nnewline", "  two  ")
    )
    assertEquals(dataLines(body).mkString, "word leadingtrailing with\nnewline  two  ")
  }

  test("an unknown streaming path is a 404, not a hanging connection") {
    val (status, _, _) = get("/chat/fixed/a/b/c")
    assertEquals(status, 404)
  }
