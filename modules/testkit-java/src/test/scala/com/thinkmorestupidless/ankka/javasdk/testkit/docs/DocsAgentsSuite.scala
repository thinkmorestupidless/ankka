package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.agent.javasdk.{AgentClients, AgentOutcome}
import com.thinkmorestupidless.ankka.javasdk.client.{CommandException, ServiceResponse}
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, Settings, TestModelProvider}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.net.http.{HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** The Java samples of `docs/build/agents.md`, run against a scripted model in the Java kit. */
class DocsAgentsSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model             = TestModelProvider()
  private var kit: AnkkaTestKit = null
  private def client            = kit.componentClient

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      AgentService.service(model),
      Settings.defaults.withScriptedService("wallet")
    )
  override def afterAll(): Unit                      = if kit != null then kit.close()
  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    WeatherAgent.TOOL_CALLS.clear()
    ApprovalAgent.RUNS.clear()

  private def post(path: String, body: String, contentType: String): HttpResponse[String] =
    kit.httpClient.send(
      HttpRequest
        .newBuilder(kit.http.resolve(path))
        .header("content-type", contentType)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )

  test("a tool is offered with its parameters' descriptions, and runs with the model's arguments") {
    model
      .expectToolCall("get_weather", """{"location":"Lisbon"}""")
      .expectText("Lisbon is 18C and sunny.")
    val answer = AgentClients.forAgent(client, "w-1").call(DocsRefs.weatherAsk).invoke("Lisbon?")
    assertEquals(answer, "Lisbon is 18C and sunny.")
    assertEquals(WeatherAgent.TOOL_CALLS.asScala.toList, List("get_weather(Lisbon,-)"))
    val offered = model.requests.get(0)
    assert(offered.contains("A location or city name."), offered)
    assert(offered.contains("current_date"), offered)
    assert(model.lastRequest.contains("Lisbon: 18C, sunny"), model.lastRequest)
  }

  test("a structured reply is read as the record the handler names") {
    model.expectText("""{"location":"Lisbon","summary":"sunny","degreesCelsius":18}""")
    val forecast =
      AgentClients.forAgent(client, "w-2").call(DocsRefs.weatherStructured).invoke("Lisbon?")
    assertEquals(forecast, WeatherAgent.Forecast("Lisbon", "sunny", 18))
  }

  test("an input guardrail refuses before any model is called") {
    val refused = intercept[CommandException](
      AgentClients
        .forAgent(client, "w-3")
        .call(DocsRefs.weatherGuarded)
        .invoke("a question far longer than the forty characters the guardrail allows")
    )
    assertEquals(refused.problem.status, 403, refused.problem.toString)
    assertEquals(model.callCount, 0)
  }

  test("a guardrail of the service's own refuses what the model wrote") {
    model.expectText("the key is sk-000")
    val refused = intercept[CommandException](
      AgentClients.forAgent(client, "w-4").call(DocsRefs.weatherSafely).invoke("key?")
    )
    assertEquals(refused.problem.status, 403, refused.problem.toString)
    assert(refused.problem.toString.contains("no-keys"), refused.problem.toString)
  }

  test("a memory window of two reads the last exchange and not the one before it") {
    val session = AgentClients.forAgent(client, "w-5")
    model.expectText("first answer").expectText("second answer").expectText("third answer")
    session.call(DocsRefs.weatherShortMemory).invoke("first question"): Unit
    session.call(DocsRefs.weatherShortMemory).invoke("second question"): Unit
    session.call(DocsRefs.weatherShortMemory).invoke("third question"): Unit
    val sent = model.lastRequest
    assert(sent.contains("second question"), sent)
    assert(!sent.contains("first question"), sent)
  }

  test("retrieved context reaches the model beside the question the user asked") {
    client
      .forKeyValueEntity("ada")
      .call(DocsRefs.setPreferences)
      .invoke("museums, no hiking"): Unit
    model.expectText("Visit the Gulbenkian and take a tram tour.")
    val answer = AgentClients
      .forAgent(client, "a-1")
      .call(DocsRefs.consult)
      .invoke(ActivityAgent.Request("ada", "Lisbon"))
    assertEquals(answer, "Visit the Gulbenkian and take a tram tour.")
    assert(
      model.lastRequest.contains("Traveller preferences: museums, no hiking"),
      model.lastRequest
    )
    assert(model.lastRequest.contains("What should I do in Lisbon?"), model.lastRequest)
  }

  test("a tool that needs approval waits, and runs once a person approves") {
    model.expectToolCall("issue_refund", """{"order":"o-7","amount":20}""")
    model.expectText("Refunded 20 on o-7.")
    assertEquals(ApprovalCalls.refund(client), "Refunded 20 on o-7.")
    assertEquals(ApprovalAgent.RUNS.asScala.toList, List("issue_refund(o-7,20)"))
  }

  test("the routes answer a waiting turn with its requests, and a decision with the answer") {
    model.expectToolCall("issue_refund", """{"order":"o-9","amount":5}""")
    val waiting = post("/approver/r-1", "refund order o-9", "text/plain")
    assertEquals(waiting.statusCode, 200, waiting.body)
    assert(waiting.body.contains("\"tool\":\"issue_refund\""), waiting.body)
    val id = "\"id\":\"([^\"]+)\"".r.findFirstMatchIn(waiting.body).map(_.group(1)).get
    model.expectText("Refunded 5 on o-9.")
    val answered =
      post(s"/approver/r-1/decide/$id", """{"approved":true,"by":"dana"}""", "application/json")
    assertEquals(answered.statusCode, 200, answered.body)
    assert(answered.body.contains("\"answered\":\"Refunded 5 on o-9.\""), answered.body)
    assertEquals(ApprovalAgent.RUNS.asScala.toList, List("issue_refund(o-9,5)"))
  }

  test("a tool's time limit is on the request, and the platform refuses when it passes") {
    model.expectToolCall("close_account", """{"customer":"c-1"}""")
    val session = AgentClients.forAgent(client, "r-2")
    val waiting = session.ask(DocsRefs.approvalAsk).invoke("close account c-1") match
      case w: AgentOutcome.AwaitingApproval[String] => w.requests.get(0)
      case other                                    => fail(s"answered without a decision: $other")
    val limit = Duration.between(waiting.requestedAt, waiting.expiresAt.orElseThrow())
    assertEquals(limit, Duration.ofSeconds(2))
    model.expectText("Account kept.")
    kit.eventually(Duration.ofSeconds(15), () => assertEquals(model.callCount, 2))
    assert(ApprovalAgent.RUNS.isEmpty, ApprovalAgent.RUNS.toString)
    assert(model.lastRequest.contains("expired"), model.lastRequest)
  }

  test("a tool calls another service as this service") {
    kit
      .service("wallet")
      .answer(_ => ServiceResponse(200, "text/plain", "42".getBytes, java.util.List.of()))
    model.expectToolCall("read_balance", """{"customer":"ada"}""").expectText("Ada has 42.")
    val answer = AgentClients.forAgent(client, "b-1").call(DocsRefs.walletAsk).invoke("ada?")
    assertEquals(answer, "Ada has 42.")
    assertEquals(
      kit.service("wallet").requests.asScala.map(_.path).toList,
      List("/wallet/balances/ada")
    )
    assert(model.lastRequest.contains("42"), model.lastRequest)
  }

  test("a handler that names a provider is answered by it, not by the service's default") {
    assume(sys.env.get("OPENAI_API_KEY").isEmpty, "the named provider would reach OpenAI")
    val refused = intercept[CommandException](
      AgentClients.forAgent(client, "t-1").call(DocsRefs.route).invoke("Which team?")
    )
    assertEquals(refused.problem.status, 503, refused.problem.toString)
    assert(refused.problem.toString.contains("OPENAI_API_KEY"), refused.problem.toString)
    assertEquals(model.callCount, 0)
  }

  test("the providers are named as the page says") {
    assertEquals(Providers.CLAUDE.name(), "anthropic")
    assertEquals(Providers.GPT.name(), "openai")
    assertEquals(Providers.LOCAL.name(), "local")
    assertEquals(Providers.module().getClass.getSimpleName.isEmpty, false)
  }

  test("a service with compaction on starts and answers") {
    val compacted = TestModelProvider().expectText("Short.")
    val other     = AnkkaTestKit.start(AgentService.compacting(compacted))
    try
      val answer =
        AgentClients.forAgent(other.componentClient, "c-1").call(DocsRefs.weatherAsk).invoke("Hi")
      assertEquals(answer, "Short.")
    finally other.close()
  }
