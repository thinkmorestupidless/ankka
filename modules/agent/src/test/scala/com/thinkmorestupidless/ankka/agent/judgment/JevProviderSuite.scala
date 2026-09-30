package com.thinkmorestupidless.ankka.agent.judgment

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import com.thinkmorestupidless.ankka.agent.{Json, TokenUsage}
import org.slf4j.LoggerFactory

import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.{
  ConcurrentLinkedQueue,
  CopyOnWriteArrayList,
  CountDownLatch,
  Executors,
  TimeUnit
}
import scala.concurrent.Await
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

/**
 * The adapter against a stand-in for the provider's endpoint: what it sends, what it makes of what
 * comes back, what it retries, and that the key goes nowhere but the one header.
 *
 * The stand-in is the JDK's HTTP server on a loopback ephemeral port, so the suite runs beside
 * anything else on the machine and needs no network.
 */
class JevProviderSuite extends munit.FunSuite:

  import Questions.*

  private val Key = "sk-test-secret"

  private final case class Reply(
      status: Int,
      body: String = "",
      headers: Map[String, String] = Map.empty,
      hang: Boolean = false
  )
  private final case class Received(path: String, headers: Map[String, String], body: String)

  private val replies            = ConcurrentLinkedQueue[Reply]()
  private val received           = CopyOnWriteArrayList[Received]()
  private val release            = CountDownLatch(1)
  private var server: HttpServer = null
  private var url: String        = null

  private val logs = ListAppender[ILoggingEvent]()
  private def root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger]
  private val answer = String(
    Files.readAllBytes(
      FixtureFiles.repositoryRoot.resolve(
        "modules/agent/src/test/resources/judgment/jev-response.json"
      )
    ),
    "UTF-8"
  )

  override def beforeAll(): Unit =
    logs.setContext(LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext])
    logs.start()
    root.addAppender(logs)
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    server.createContext(
      "/",
      exchange =>
        val body = String(exchange.getRequestBody.readAllBytes(), "UTF-8")
        val headers =
          exchange.getRequestHeaders.asScala.map((k, v) => k.toLowerCase -> v.get(0)).toMap
        received.add(Received(exchange.getRequestURI.getPath, headers, body)): Unit
        val reply = Option(replies.poll()).getOrElse(Reply(418, "no reply was scripted"))
        if reply.hang then release.await(30, TimeUnit.SECONDS): Unit
        reply.headers.foreach((k, v) => exchange.getResponseHeaders.add(k, v))
        val bytes = reply.body.getBytes("UTF-8")
        try
          exchange.sendResponseHeaders(reply.status, if bytes.isEmpty then -1 else bytes.length)
          if bytes.nonEmpty then exchange.getResponseBody.write(bytes)
        catch case _: java.io.IOException => () // the client gave up; nothing to tell it
        finally exchange.close()
    )
    server.start()
    url = s"http://127.0.0.1:${server.getAddress.getPort}"

  override def afterAll(): Unit =
    release.countDown()
    if server != null then server.stop(0)
    root.detachAppender(logs): Unit

  override def beforeEach(context: BeforeEach): Unit =
    replies.clear()
    received.clear()

  private def provider(model: String = JevProvider.DefaultModel) =
    JevProvider.withApiKey(Key, model = model, baseUrl = url)

  private def request(
      questions: Vector[Question[?]] = Vector(route, frustration, refund),
      state: JudgmentState = JudgmentState.Text("I was charged twice."),
      timeout: FiniteDuration = 2.seconds
  ) = JudgmentRequest(state, questions, timeout)

  private def judge(p: JevProvider, r: JudgmentRequest): Judgment =
    Await.result(p.judge(r), r.timeout + 2.seconds)

  /** The adapter's failure, checked for the key on the way out. */
  private def failure(p: JevProvider, r: JudgmentRequest): JudgmentFailed =
    val failed = intercept[JudgmentFailed](judge(p, r))
    Iterator
      .iterate[Throwable](failed)(_.getCause)
      .takeWhile(_ != null)
      .foreach(t => assert(!String.valueOf(t.getMessage).contains(Key), t.getMessage))
    assert(!p.toString.contains(Key))
    failed

  private def sent: Json = Json.parse(received.asScala.last.body).fold(fail(_), identity)

  // ── What is sent ─────────────────────────────────────────────────────────

  test("the request carries the model, the state and every question in the provider's form") {
    replies.add(Reply(200, answer))
    judge(provider(), request()): Unit

    val call = received.asScala.last
    assertEquals(call.path, "/v1/systemone")
    assertEquals(call.headers.get("authorization"), Some(s"Bearer $Key"))
    assertEquals(call.headers.get("content-type"), Some("application/json"))

    val body = sent
    assertEquals(body("model").flatMap(_.asString), Some("jev-1.13.0"))
    assertEquals(body("state").flatMap(_.asString), Some("I was charged twice."))
    val questions = body("questions").get
    val choice    = questions("route").get
    assertEquals(choice("type").flatMap(_.asString), Some("choice"))
    assertEquals(
      choice("instructions").flatMap(_.asString),
      Some("Which team should handle this ticket?")
    )
    choice("criteria") match
      case Some(Json.Obj(fields)) =>
        assertEquals(fields.keys.toVector, Vector("billing", "technical", "sales"))
        assertEquals(fields("billing").asString, Some("Payments, invoicing, refunds"))
      case other => fail(s"a choice's criteria should be an object: $other")
    val score = questions("frustration").get
    assertEquals(score("type").flatMap(_.asString), Some("score"))
    assertEquals(score("criteria").flatMap(_.asArray).map(_.size), Some(4))
    val yesNo = questions("refund").get
    assertEquals(yesNo("type").flatMap(_.asString), Some("noul"))
    assertEquals(yesNo("criteria"), None)
  }

  test("a described yes/no sends what yes and no mean") {
    replies.add(
      Reply(
        200,
        """{"model":"jev-1.13.0","answers":{"urgent":{"type":"noul","noul":0.4}},"usage":{"input_tokens":1,"output_tokens":0}}"""
      )
    )
    judge(provider(), request(Vector(urgent))): Unit
    val criteria = sent("questions").flatMap(_("urgent")).flatMap(_("criteria")).get
    assertEquals(
      criteria("true").flatMap(_.asString),
      Some("A deadline, an outage or money at risk")
    )
    assertEquals(criteria("false").flatMap(_.asString), Some("It can wait"))
  }

  test("a structured state is sent as the value itself, not as a string holding it") {
    replies.add(Reply(200, answer))
    val state = Json.obj("ticket" -> Json.obj("id" -> Json.str("A-104")))
    judge(provider(), request(state = JudgmentState.Structured(state))): Unit
    assertEquals(sent("state"), Some(state))
  }

  test("the documented response converts to the judgment it describes") {
    replies.add(Reply(200, answer))
    val judgment = judge(provider(), request())
    assertEquals(judgment.model, "jev-1.13.0")
    assertEquals(judgment.usage, TokenUsage(inputTokens = 296, outputTokens = 20))
    assertEquals(judgment(route).choice, Team.Billing)
    assertEquals(judgment(route).confidence, 0.82)
    assertEquals(judgment(frustration).score, 1.43)
    assertEquals(judgment(frustration).probabilities, Vector(0.0, 0.57, 0.43, 0.0))
    assertEquals(judgment(refund).probability, 0.95)
  }

  // ── The model and the address ────────────────────────────────────────────

  test("with no model named, a version is sent, not an alias; an alias is sent only when named") {
    replies.add(Reply(200, answer))
    judge(provider(), request()): Unit
    assertEquals(sent("model").flatMap(_.asString), Some(JevProvider.DefaultModel))
    assert(!JevProvider.DefaultModel.endsWith("latest"))

    replies.add(Reply(200, answer))
    judge(provider(model = "jev-latest"), request()): Unit
    assertEquals(sent("model").flatMap(_.asString), Some("jev-latest"))
  }

  test("the judgment reports the version the provider says answered") {
    replies.add(Reply(200, answer.replace("\"jev-1.13.0\"", "\"jev-1.14.2\"")))
    assertEquals(judge(provider(model = "jev-latest"), request()).model, "jev-1.14.2")
  }

  test("the base address is where the request goes, from the argument or the environment") {
    replies.add(Reply(200, answer))
    judge(provider(), request()): Unit
    assertEquals(received.size, 1)

    replies.add(Reply(200, answer))
    val fromEnv = JevProvider.fromEnv(
      env = Map(JevProvider.KeyVariable -> Key, JevProvider.BaseUrlVariable -> s"$url/")
    )
    judge(fromEnv, request()): Unit
    assertEquals(received.size, 2)
    assertEquals(received.asScala.last.path, "/v1/systemone")
  }

  test("with no key in the environment, construction fails naming the variable") {
    val failure = intercept[IllegalArgumentException](JevProvider.fromEnv(env = Map.empty))
    assertEquals(failure.getMessage, "TYPESAFE_API_KEY is not set")
    intercept[IllegalArgumentException](JevProvider.fromEnv(env = Map("TYPESAFE_API_KEY" -> " ")))
  }

  // ── Retries ──────────────────────────────────────────────────────────────

  test("rate limiting is retried after the wait the provider asks for") {
    replies.add(Reply(429, "slow down", Map("retry-after-ms" -> "50")))
    replies.add(Reply(200, answer))
    val started = System.nanoTime()
    judge(provider(), request()): Unit
    assertEquals(received.size, 2)
    assert(System.nanoTime() - started >= 50.millis.toNanos)
  }

  test("overload and server errors are retried; Retry-After in seconds is honoured") {
    replies.add(Reply(529, "overloaded"))
    replies.add(Reply(503, "unavailable", Map("Retry-After" -> "0")))
    replies.add(Reply(200, answer))
    judge(provider(), request()): Unit
    assertEquals(received.size, 3)
  }

  test("a retry that would pass the deadline is not made, and the last failure is reported") {
    replies.add(Reply(429, "slow down", Map("Retry-After" -> "1")))
    val failed = failure(provider(), request(timeout = 300.millis))
    assert(failed.getMessage.contains("429: slow down"), failed.getMessage)
    assert(!failed.timedOut)
    assertEquals(received.size, 1)
  }

  test("a refused key and an invalid request fail at once, with the provider's body") {
    replies.add(Reply(401, """{"error":"invalid key"}"""))
    assert(failure(provider(), request()).getMessage.contains("""401: {"error":"invalid key"}"""))
    replies.add(Reply(422, """{"error":"state too large"}"""))
    assert(failure(provider(), request()).getMessage.contains("422"))
    assertEquals(received.size, 2)
  }

  test("no answer within the timeout is a failure that timed out") {
    replies.add(Reply(200, answer, hang = true))
    val started = System.nanoTime()
    val failed  = failure(provider(), request(timeout = 300.millis))
    assert(failed.timedOut, failed.getMessage)
    assert(System.nanoTime() - started < 2.seconds.toNanos)
  }

  // ── What comes back ──────────────────────────────────────────────────────

  test("a body that is not JSON, or lacks what a judgment needs, fails in the adapter") {
    replies.add(Reply(200, "<html>bad gateway</html>"))
    assert(failure(provider(), request()).getMessage.contains("not JSON"))
    replies.add(Reply(200, """{"model":"jev-1.13.0","usage":{}}"""))
    assert(failure(provider(), request()).getMessage.contains("no 'answers'"))
  }

  test("an answer that does not fit its question fails through the platform, naming it") {
    val judgments = Judgments(Some(provider()), 2.seconds)
    replies.add(
      Reply(
        200,
        answer.replace(
          """"refund": {"type": "noul", "noul": 0.95}""",
          """"other": {"type": "noul", "noul": 0.95}"""
        )
      )
    )
    val missing = intercept[JudgmentFailed](
      judgments.ask(None, JudgmentState.Text("t"), Vector(route, frustration, refund))
    )
    assert(missing.getMessage.contains("question 'refund' was asked and not answered"))

    replies.add(Reply(200, answer.replace("\"choice\": \"billing\"", "\"choice\": \"legal\"")))
    val notOffered = intercept[JudgmentFailed](
      judgments.ask(None, JudgmentState.Text("t"), Vector(route, frustration, refund))
    )
    assert(notOffered.getMessage.contains("question 'route'"), notOffered.getMessage)
  }

  // ── The key ──────────────────────────────────────────────────────────────

  test("nothing logged by any case holds the key") {
    val held = logs.list.asScala.filter { event =>
      val thrown = Option(event.getThrowableProxy).map(_.getMessage).getOrElse("")
      event.getFormattedMessage.contains(Key) || String.valueOf(thrown).contains(Key)
    }
    assert(held.isEmpty, held.mkString("\n"))
  }
