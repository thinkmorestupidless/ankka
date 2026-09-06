package nakka.testkit

import nakka.agent.*
import nakka.core.{CommandError, ErrorCode, SessionId}

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * Agents end to end: sharded per session, real session memory in Postgres, real tool
 * loop — with a scripted model so the assertions are about nakka's behaviour rather than
 * a model's mood.
 */
class AgentSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  private var testKit: NakkaTestKit = null
  private val model                 = TestModelProvider()

  override def beforeAll(): Unit =
    testKit = NakkaTestKit.start(
      Seq(WeatherAgent.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    WeatherAgent.toolCalls.clear()

  private def agent(session: String) =
    testKit.componentClient.forAgent(SessionId(session))

  private def memory(session: String) =
    testKit.componentClient.forSessionMemory(SessionId(session))

  private def historyOf(session: String) =
    memory(session).call(SessionMemoryEntity.history).invoke()

  /** Tool invocations recorded by the fixture, in the order they ran. */
  private def toolCalls: Vector[String] = WeatherAgent.toolCalls.asScala.toVector

  test("a plain question reaches the model and its answer comes back") {
    model.expectText("It is sunny in Berlin.")

    val reply = agent("s-basic").call(WeatherAgent.ask).invoke("What is the weather in Berlin?")
    assertEquals(reply, "It is sunny in Berlin.")

    // The agent sent the system message and the question, and offered its tools.
    val request = model.lastRequest
    assertEquals(request.systemMessage, Some(WeatherAgent.SystemMessage))
    assertEquals(request.tools.map(_.name).sorted, Vector("current_date", "get_weather"))
    assert(
      request.messages.exists {
        case ChatMessage.User(content) =>
          content.exists {
            case MessageContent.Text(text) => text.contains("weather in Berlin")
            case _                         => false
          }
        case _ => false
      },
      request.messages.toString
    )
  }

  test("a tool call is executed and the result fed back for a second model turn") {
    model
      .expectToolCall("get_weather", Json.obj("location" -> Json.str("Berlin")))
      .expectText("Berlin is 18C and sunny.")

    val reply = agent("s-tool").call(WeatherAgent.ask).invoke("Weather in Berlin?")
    assertEquals(reply, "Berlin is 18C and sunny.")

    // The tool actually ran, with the arguments the model supplied.
    assertEquals(toolCalls, Vector("get_weather(Berlin,-)"))

    // Two model calls: the request, then the follow-up carrying the tool result.
    assertEquals(model.callCount, 2)
    val second = model.requests(1)
    assert(
      second.messages.exists {
        case ChatMessage.ToolResults(results) => results.exists(_.content.contains("18C"))
        case _                                => false
      },
      second.messages.toString
    )
  }

  test("parallel tool calls come back as one tool-results turn") {
    model
      .expectParallelToolCalls(
        ToolCall("c1", "get_weather", Json.obj("location" -> Json.str("Oslo"))),
        ToolCall("c2", "current_date", Json.obj())
      )
      .expectText("Oslo is sunny today.")

    assertEquals(agent("s-parallel").call(WeatherAgent.ask).invoke("Oslo?"), "Oslo is sunny today.")
    assertEquals(toolCalls.sorted, Vector("current_date()", "get_weather(Oslo,-)"))

    // One message with both results — splitting them teaches a model to stop asking in
    // parallel.
    val toolTurns = model.requests(1).messages.collect { case t: ChatMessage.ToolResults => t }
    assertEquals(toolTurns.size, 1)
    assertEquals(toolTurns.head.results.size, 2)
  }

  test("a failing tool is reported to the model rather than failing the request") {
    model
      .expectToolCall("get_weather", Json.obj("location" -> Json.str("Nowhere")))
      .expectText("I could not find that location.")

    val reply = agent("s-toolfail").call(WeatherAgent.ask).invoke("Weather in Nowhere?")
    assertEquals(reply, "I could not find that location.")

    val results = model.requests(1).messages.collect { case t: ChatMessage.ToolResults => t }
    assertEquals(results.head.results.head.isError, true)
    assert(results.head.results.head.content.contains("unknown location"))
  }

  test("a tool the agent does not have is reported, not crashed on") {
    model
      .expectToolCall("send_email", Json.obj("to" -> Json.str("x@example.com")))
      .expectText("I cannot send email.")

    assertEquals(agent("s-notool").call(WeatherAgent.ask).invoke("Email me"), "I cannot send email.")
    val results = model.requests(1).messages.collect { case t: ChatMessage.ToolResults => t }
    assert(results.head.results.head.isError)
    assert(results.head.results.head.content.contains("get_weather"), "should list what is available")
  }

  test("session memory carries context into the next request") {
    model.expectText("Hello, I can help with the weather.")
    val _ = agent("s-memory").call(WeatherAgent.ask).invoke("Hi there")

    model.expectText("You asked about the weather.")
    val _ = agent("s-memory").call(WeatherAgent.ask).invoke("What did I just ask?")

    // The second request replayed the first exchange.
    val second = model.requests(1)
    val texts = second.messages.collect {
      case ChatMessage.User(content) =>
        content.collect { case MessageContent.Text(t) => t }.mkString
      case ChatMessage.Assistant(text, _) => text
    }
    assert(texts.exists(_.contains("Hi there")), texts.toString)
    assert(texts.exists(_.contains("I can help with the weather")), texts.toString)
  }

  test("memory is persisted as a session entity, tool traffic included") {
    model
      .expectToolCall("get_weather", Json.obj("location" -> Json.str("Rome")))
      .expectText("Rome is warm.")
    val _ = agent("s-persist").call(WeatherAgent.ask).invoke("Rome?")

    val history = historyOf("s-persist")
    val kinds = history.messages.map(_.getClass.getSimpleName)
    assertEquals(
      kinds,
      Vector("UserMessage", "AiMessage", "ToolResultMessage", "AiMessage"),
      s"unexpected history: $kinds"
    )
    assert(history.messages.forall(_.agentId == WeatherAgent.role))
  }

  test("MemoryProvider.none neither reads nor writes") {
    model.expectText("greeting")
    val _ = agent("s-nomem").call(WeatherAgent.ask).invoke("Hi")
    assert(historyOf("s-nomem").messages.nonEmpty)

    model.expectText("weather-query")
    val _ = agent("s-nomem").call(WeatherAgent.classify).invoke("Is it raining?")

    // The classify call saw no history and contributed none.
    val classifyRequest = model.requests(1)
    assertEquals(classifyRequest.messages.size, 1, classifyRequest.messages.toString)
    assertEquals(historyOf("s-nomem").messages.size, 2, "classify must not have written")
  }

  test("readLast bounds how much history is replayed") {
    (1 to 3).foreach { n =>
      model.expectText(s"reply-$n")
      val _ = agent("s-window").call(WeatherAgent.ask).invoke(s"question-$n")
    }
    assertEquals(historyOf("s-window").messages.size, 6)

    model.expectText("windowed")
    val _ = agent("s-window").call(WeatherAgent.askWithShortMemory).invoke("and now?")

    val replayed = model.lastRequest.messages
    // Two remembered messages plus the new question.
    assertEquals(replayed.size, 3, replayed.toString)
  }

  test("a structured reply is parsed, and a malformed one is an error") {
    model.expectText("""{"location":"Paris","summary":"cloudy","degreesCelsius":14}""")
    val forecast = agent("s-struct").call(WeatherAgent.askStructured).invoke("Paris?")
    assertEquals(forecast, Forecast("Paris", "cloudy", 14))

    model.expectText("not json at all")
    val failure = intercept[CommandError] {
      agent("s-struct2").call(WeatherAgent.askStructured).invoke("Paris?")
    }
    assert(failure.getMessage.contains("not valid"), failure.getMessage)
  }

  test("a guardrail rejects before the model is called") {
    val failure = intercept[CommandError] {
      agent("s-guard").call(WeatherAgent.guarded).invoke("x" * 100)
    }
    assertEquals(failure.code, ErrorCode.Forbidden)
    assert(failure.getMessage.contains("max-input-length"), failure.getMessage)
    assertEquals(model.callCount, 0, "a rejected input must not reach the model")
    assert(historyOf("s-guard").isEmpty, "a rejected request must leave no history")
  }

  test("a refusal is surfaced as a typed failure, not as an answer") {
    model.expectRefusal("I cannot help with that.")
    val failure = intercept[CommandError] {
      agent("s-refusal").call(WeatherAgent.ask).invoke("something disallowed")
    }
    assertEquals(failure.code, ErrorCode.Forbidden)
    assert(failure.getMessage.contains("cannot help"), failure.getMessage)
    assert(historyOf("s-refusal").isEmpty, "a refused request must leave no history")
  }

  test("an agent's own validation rejects without a model call") {
    val failure = intercept[CommandError] {
      agent("s-blank").call(WeatherAgent.ask).invoke("   ")
    }
    assertEquals(failure.code, ErrorCode.BadRequest)
    assertEquals(model.callCount, 0)
  }

  test("the agent knows its component id and session") {
    model.expectText("acknowledged")
    val _ = agent("s-identity").call(WeatherAgent.whoAmI).invoke()
    val text = model.lastRequest.messages.collect {
      case ChatMessage.User(content) => content.collect { case MessageContent.Text(t) => t }.mkString
    }.mkString
    assert(text.contains("weather-agent"), text)
    assert(text.contains("s-identity"), text)
  }

  test("sessions are isolated from each other") {
    model.expectText("first")
    val _ = agent("s-iso-a").call(WeatherAgent.ask).invoke("only in A")

    model.expectText("second")
    val _ = agent("s-iso-b").call(WeatherAgent.ask).invoke("only in B")

    assertEquals(historyOf("s-iso-a").messages.size, 2)
    assertEquals(historyOf("s-iso-b").messages.size, 2)
    val aText = historyOf("s-iso-a").messages.collect {
      case m: SessionMessage.UserMessage => m.text
    }
    assertEquals(aText, Vector("only in A"))
  }

  test("history survives a restart") {
    model.expectText("remembered")
    val _ = agent("s-durable").call(WeatherAgent.ask).invoke("remember this")
    assertEquals(historyOf("s-durable").messages.size, 2)

    testKit.restartService()

    val recovered = historyOf("s-durable")
    assertEquals(recovered.messages.size, 2)
    assertEquals(
      recovered.messages.collect { case m: SessionMessage.UserMessage => m.text },
      Vector("remember this")
    )
  }

  test("running out of script fails loudly rather than inventing an answer") {
    val failure = intercept[CommandError] {
      agent("s-unscripted").call(WeatherAgent.ask).invoke("anything")
    }
    assert(failure.getMessage.contains("no scripted response"), failure.getMessage)
  }
