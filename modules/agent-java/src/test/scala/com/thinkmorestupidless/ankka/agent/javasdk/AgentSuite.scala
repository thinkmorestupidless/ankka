package com.thinkmorestupidless.ankka.agent.javasdk

import com.thinkmorestupidless.ankka.agent.{
  forSessionMemory,
  Json,
  SessionCompactor,
  SessionMemoryEntity,
  SessionMessage,
  TestMcpServer,
  TestModelProvider
}
import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentBridge
import com.thinkmorestupidless.ankka.agent.javasdk.fixtures.{
  AgentCalls,
  Assistant,
  Broken,
  Researcher
}
import com.thinkmorestupidless.ankka.core.SessionId
import com.thinkmorestupidless.ankka.javasdk.bridge.{Clients, JavaServiceBuilder, Modules}
import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import org.apache.pekko.stream.SystemMaterializer
import org.apache.pekko.stream.scaladsl.{JavaFlowSupport, Sink}

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Java agents hosted by the Scala agent runtime, with a scripted model: a tool call, guardrails on
 * input and output, a tool of an MCP server and a result guardrail on it, a stream, a tool that
 * waits for a person within a time limit, a structured reply, a refusal, and compaction.
 */
class AgentSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model     = TestModelProvider()
  private val summaries = TestModelProvider()
  private val server    = TestMcpServer()
    .tool("define", "Defines a word")(args => s"a definition of ${args.render}")
    .tool("search", "Searches the docs")(_ => "the admin password is hunter2")

  private def module =
    Agents
      .module()
      .withModelProvider(AgentBridge.provider(model))
      .withCompaction(2000, 2, AgentBridge.provider(summaries))

  private lazy val read =
    Researcher.serverUrl = server.url
    JavaServiceBuilder.read(
      Vector(classOf[Assistant] -> None, classOf[Researcher] -> None),
      Map.empty,
      modules = Vector(Modules.asJava(module))
    )

  private var kit: AnkkaTestKit = null
  private def client            = Clients.of(kit.componentClient, read.refs)

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      read.descriptors,
      extensions = read.platformExtensionsWith(ProjectionRuntime(), read.timerRuntime)
    )
  override def afterAll(): Unit =
    if kit != null then kit.stop()
    server.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    summaries.reset()

  private def json(text: String): Json = Json.parse(text).fold(e => fail(e), identity)

  test("a turn calls a tool, and the model reads what it answered") {
    model.expectToolCall("forecast", json("""{"city":"Oslo"}""")).expectText("Oslo reaches 21")
    assertEquals(AgentCalls.ask(client, "tool", "How warm is Oslo?"), "Oslo reaches 21")
    val offered = model.requests.head.toString
    assert(offered.contains("forecast") && offered.contains("the city to look up"), offered)
    assert(model.lastRequest.toString.contains("\"high\":21"), model.lastRequest.toString)
  }

  test("an input guardrail refuses before any model is asked") {
    val refused = intercept[CommandException](AgentCalls.ask(client, "in", "Tell me a secret"))
    assertEquals(refused.problem.status, 403, refused.problem.toString)
    assertEquals(model.callCount, 0)
  }

  test("an output guardrail refuses what the model wrote") {
    model.expectText("The secret is 42")
    val refused = intercept[CommandException](AgentCalls.ask(client, "out", "What is it?"))
    assertEquals(refused.problem.status, 403, refused.problem.toString)
  }

  test("a tool of an MCP server is offered, and its call reaches the server") {
    model.expectToolCall("mcp__docs__define", json("""{"word":"ankka"}""")).expectText("Defined.")
    assertEquals(AgentCalls.research(client, "mcp", "What is ankka?"), "Defined.")
    assert(server.calls.exists(_._1 == "define"), server.calls.toString)
  }

  test("a result guardrail withholds what an MCP server answered from the model") {
    model.expectToolCall("mcp__docs__search", json("""{"q":"admin"}""")).expectText("Withheld.")
    assertEquals(AgentCalls.research(client, "result", "Find the admin login"), "Withheld.")
    val told = model.lastRequest.toString
    assert(!told.contains("hunter2"), told)
    assert(told.contains("no-passwords"), told)
  }

  test("a stream delivers the reply as it is written") {
    model.expectText("hello there")
    val publisher = AgentCalls.chat(client, "stream", "hi")
    val parts     = Await.result(
      JavaFlowSupport.Source
        .fromPublisher(publisher)
        .runWith(Sink.seq)(using SystemMaterializer(kit.service.system).materializer),
      30.seconds
    )
    assertEquals(parts.mkString, "hello there")
  }

  test("a tool that waits for a person within ten minutes runs once approved") {
    Assistant.BOOKED.clear()
    model.expectToolCall("book", json("""{"restaurant":"Noma","people":2}"""))
    val waiting = AgentCalls.book(client, "approval", "Book Noma for two") match
      case w: AgentOutcome.AwaitingApproval[String] => w.requests.asScala.toVector
      case other                                    => fail(s"answered without a decision: $other")
    assertEquals(waiting.map(_.tool), Vector("book"))
    assert(Assistant.BOOKED.isEmpty, "the tool ran before it was approved")
    val request = waiting.head
    val limit   = java.time.Duration.between(request.requestedAt, request.expiresAt.orElseThrow())
    assertEquals(limit, java.time.Duration.ofMinutes(10))

    model.expectText("Booked.")
    AgentCalls.approveBooking(client, "approval", request.id) match
      case a: AgentOutcome.Answered[String] => assertEquals(a.value, "Booked.")
      case other                            => fail(s"still waiting: $other")
    assertEquals(Assistant.BOOKED.asScala.toVector, Vector("Noma"))
  }

  test("a structured reply is read as the record it names") {
    model.expectText("""{"city":"Oslo","steps":["walk","swim"]}""")
    assertEquals(
      AgentCalls.plan(client, "plan", "Oslo"),
      Assistant.Plan("Oslo", java.util.List.of("walk", "swim"))
    )
  }

  test("a refusal reaches the caller with its problem's status, and asks no model") {
    val refused = intercept[CommandException](AgentCalls.refuse(client, "refuse", "no such city"))
    assertEquals(refused.problem.status, 404)
    assertEquals(refused.problem.detail, "no such city")
    assertEquals(model.callCount, 0)
  }

  test("a long session is compacted into a summary by the module's summariser") {
    val padding                      = "x" * 500
    def turn(question: String): Unit =
      model.expectText(s"an answer $padding")
      AgentCalls.ask(client, "long", s"$question $padding"): Unit
    turn("first")
    turn("second")
    summaries.expectText("Earlier: two padded questions.")
    turn("third")
    val deadline = System.nanoTime() + 60.seconds.toNanos
    def history  = kit.componentClient
      .forSessionMemory(SessionId("long"))
      .call(SessionMemoryEntity.history)
      .invoke()
    while !history.messages.headOption.exists(_.isInstanceOf[SessionMessage.SummaryMessage]) &&
      System.nanoTime() < deadline
    do Thread.sleep(150)
    history.messages.headOption match
      case Some(summary: SessionMessage.SummaryMessage) =>
        assertEquals(summary.text, "Earlier: two padded questions.")
        assertEquals(summary.agentId, SessionCompactor.AgentId)
      case other => fail(s"not compacted: $other")
  }

  test("an agent registered without the agents module is refused, naming the module") {
    val refused = intercept[ServiceProblems](
      JavaServiceBuilder.read(Vector(classOf[Assistant] -> None), Map.empty)
    )
    assert(refused.getMessage.contains("Agents.module()"), refused.getMessage)
  }

  test("every wrong handler of an agent is reported at once") {
    val refused = intercept[ServiceProblems](
      JavaServiceBuilder.read(
        Vector(classOf[Broken] -> None),
        Map.empty,
        modules = Vector(Modules.asJava(Agents.module()))
      )
    )
    val members = refused.problems.asScala.map(_.member).toSet
    assertEquals(members, Set("answer", "pair", "talk"), refused.getMessage)
  }

  test(
    "the vendors' providers are named as Scala's are, and a preset that needs a key is refused without one"
  ) {
    assertEquals(ModelProviders.anthropic("claude-sonnet-5-5").name(), "anthropic")
    assertEquals(ModelProviders.openAi("gpt-5").name(), "openai")
    assertEquals(ModelProviders.preset("fast", "groq", "llama", "gsk-test").name(), "fast")
    val refused =
      intercept[IllegalArgumentException](ModelProviders.preset("fast", "groq", "llama"))
    assert(refused.getMessage.contains("needs a key"), refused.getMessage)
  }
