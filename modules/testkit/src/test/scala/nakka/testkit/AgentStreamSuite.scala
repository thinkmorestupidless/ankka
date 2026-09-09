package nakka.testkit

import nakka.agent.*
import nakka.core.{CommandError, ErrorCode, SessionId}
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/**
 * Token streaming end to end: agent loop -> token actor ref -> pekko stream.
 *
 * The scripted model splits its reply into per-word deltas, so these tests can tell a genuinely
 * incremental stream from one that merely arrives all at once.
 */
class AgentStreamSuite extends munit.FunSuite:

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

  private given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.service.system

  private def agent(session: String) =
    testKit.componentClient.forAgent(SessionId(session))

  private def collect(session: String, handleInput: String): Vector[String] =
    Await
      .result(
        agent(session).stream(WeatherAgent.chat)(handleInput).runWith(Sink.seq),
        60.seconds
      )
      .toVector

  private def historyOf(session: String) =
    testKit.componentClient
      .forSessionMemory(SessionId(session))
      .call(SessionMemoryEntity.history)
      .invoke()

  test("a reply arrives as several tokens, in order") {
    model.expectText("It is sunny in Berlin today")

    val tokens = collect("st-basic", "Weather in Berlin?")
    assert(tokens.sizeIs > 1, s"expected incremental delivery, got $tokens")
    assertEquals(tokens.mkString, "It is sunny in Berlin today")
  }

  test("tool preamble and final answer both stream, in that order") {
    model
      .expect(
        ModelResponse(
          text = "Let me check",
          toolCalls =
            Vector(ToolCall("c1", "get_weather", Json.obj("location" -> Json.str("Berlin")))),
          stopReason = StopReason.ToolUse
        )
      )
      .expectText("Berlin is 18C and sunny")

    val text = collect("st-tool", "Weather in Berlin?").mkString

    // The preamble is real output — withholding it until the tool returns is what makes
    // a streaming UI feel broken.
    assertEquals(text, "Let me checkBerlin is 18C and sunny")
    assertEquals(WeatherAgent.toolCalls.asScala.toVector, Vector("get_weather(Berlin,-)"))
  }

  test("a streamed interaction is written to session memory") {
    model.expectText("Sunny again")
    val _ = collect("st-memory", "And tomorrow?")

    val history = historyOf("st-memory")
    assertEquals(history.messages.size, 2)
    assertEquals(
      history.messages.collect { case m: SessionMessage.UserMessage => m.text },
      Vector("And tomorrow?")
    )
    assertEquals(
      history.messages.collect { case m: SessionMessage.AiMessage => m.text },
      Vector("Sunny again")
    )
  }

  test("memory from a streamed turn is visible to the next request") {
    model.expectText("First answer")
    val _ = collect("st-continuity", "First question")

    model.expectText("Second answer")
    val _ = collect("st-continuity", "Second question")

    val replayed = model.requests(1).messages.collect {
      case ChatMessage.User(content) =>
        content.collect { case MessageContent.Text(t) => t }.mkString
      case ChatMessage.Assistant(text, _) => text
    }
    assert(replayed.exists(_.contains("First question")), replayed.toString)
    assert(replayed.exists(_.contains("First answer")), replayed.toString)
  }

  test("a rejected stream fails the Source rather than hanging it") {
    val failure = intercept[CommandError] {
      Await.result(
        agent("st-guard").stream(WeatherAgent.chatGuarded)("x" * 100).runWith(Sink.seq),
        30.seconds
      )
    }
    assertEquals(failure.code, ErrorCode.Forbidden)
    assertEquals(model.callCount, 0, "a rejected input must not reach the model")
  }

  test("a refusal fails the stream with the model's reason") {
    model.expectRefusal("I cannot help with that.")
    val failure = intercept[CommandError] {
      Await.result(
        agent("st-refusal").stream(WeatherAgent.chat)("something disallowed").runWith(Sink.seq),
        30.seconds
      )
    }
    assertEquals(failure.code, ErrorCode.Forbidden)
    assert(failure.getMessage.contains("cannot help"), failure.getMessage)
  }

  test("an unscripted model fails the stream, loudly") {
    val failure = intercept[CommandError] {
      Await.result(
        agent("st-unscripted").stream(WeatherAgent.chat)("anything").runWith(Sink.seq),
        30.seconds
      )
    }
    assert(failure.getMessage.contains("no scripted response"), failure.getMessage)
  }

  test("streaming a non-streaming component is rejected, not ignored") {
    // The entity hosts must answer an InvokeStream they cannot serve — otherwise the
    // caller waits forever.
    val failure = intercept[CommandError] {
      Await.result(
        testKit.componentClient
          .forAgent(SessionId("st-wrong"))
          .stream(WeatherAgent.chat)("hello")
          .runWith(Sink.seq),
        30.seconds
      )
    }
    // `chat` is a real streaming handler, so this should be the model failing, not a
    // routing failure — which confirms the request reached the agent.
    assert(failure.getMessage.contains("no scripted response"), failure.getMessage)
  }

  test("sessions stream independently") {
    model.expectText("Answer A").expectText("Answer B")
    assertEquals(collect("st-iso-a", "Question A").mkString, "Answer A")
    assertEquals(collect("st-iso-b", "Question B").mkString, "Answer B")
    assertEquals(historyOf("st-iso-a").messages.size, 2)
    assertEquals(historyOf("st-iso-b").messages.size, 2)
  }
