package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.agent.{GuardrailRequest, PlanRequest, ToolRequest}
import com.thinkmorestupidless.ankka.agent.{
  AgentRuntime,
  ChatMessage,
  Json,
  MessageContent,
  StreamHandle,
  TestModelProvider,
  forAgent
}
import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  EntityId,
  ErrorCode,
  Metadata,
  MethodName,
  SessionId
}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

/**
 * The sidecar's agent loop driving an agent whose plan, tools and guardrails live in the process:
 * the model is the sidecar's (scripted here), the tool runs at the double with the model's
 * arguments, a tool error is fed back and the loop goes on, tokens stream in order, a guardrail
 * blocks, the session outlives the process, and the step bound holds.
 */
class RemoteAgentSuite extends munit.FunSuite:
  import ProcessDouble.*

  given ExecutionContext = ExecutionContext.global

  override def munitTimeout: scala.concurrent.duration.Duration = 5.minutes

  private val assistant = AgentOf(
    "assistant",
    handlers = Map(
      "ask" -> (input =>
        plan("You are helpful", input, Vector("lookup", "boom"), Vector("no-secrets"))
      ),
      "forget" -> (input => plan("You are helpful", input, memory = false))
    ),
    streams = Map("stream" -> (input => plan("You are helpful", input))),
    tools = Map(
      "lookup" -> ((_, arguments) => Right(s"the count for $arguments is 7")),
      "boom"   -> ((_, _) => Left("no such thing"))
    ),
    guardrails = Map(
      "no-secrets" -> ((stage, text) =>
        if stage == GuardrailRequest.Stage.OUTPUT && text.contains("sk-") then Some("a key leaked")
        else None
      )
    ),
    maxToolCallSteps = 2
  )

  private val model                   = TestModelProvider()
  private var double: ProcessDouble   = scala.compiletime.uninitialized
  private var channel: ManagedChannel = scala.compiletime.uninitialized
  private var kit: AnkkaTestKit       = scala.compiletime.uninitialized
  private var settings: Settings      = scala.compiletime.uninitialized

  override def beforeAll(): Unit =
    double = new ProcessDouble(DoubleSpec(agents = Vector(assistant)))
    val port = double.start()
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    settings =
      Settings(s"127.0.0.1:$port", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)
    val conversation = GrpcConversation(channel, settings)
    val discovered   = Discovery.validate(double.toSpec).fold(p => fail(p.mkString("; ")), identity)
    val models       = Models.only(Models.Scripted, model)
    val agents = discovered.agents.map(c =>
      RemoteAgent.descriptor(RemoteAgent.spec(c).toOption.get, conversation, models, 5.seconds)
    )
    kit = AnkkaTestKit.start(
      discovered.descriptors ++ agents ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model)),
      60.seconds,
      _.withConversation(conversation)
    )

  override def afterAll(): Unit =
    Try(kit.stop())
    channel.shutdownNow()
    double.stop()

  override def beforeEach(context: BeforeEach): Unit = model.reset()

  private val component = ComponentId("assistant")

  private def ask(session: String, name: String, input: String): Either[CommandError, String] =
    Try(
      Await.result(
        kit.componentClient.transportRef
          .ask(component, EntityId(session), MethodName(name), input.getBytes, Metadata.empty),
        20.seconds
      )
    ).toEither.map(String(_)).left.map {
      case e: CommandError => e
      case other           => CommandError(other.getMessage, ErrorCode.Internal)
    }

  private def userTexts(messages: Vector[ChatMessage]): Vector[String] =
    messages.collect { case ChatMessage.User(content) =>
      content.collect { case MessageContent.Text(t) => t }.mkString
    }

  test("A1 the process plans; the sidecar's model answers; the reply reaches the caller") {
    model.expectText("Hello there")
    assertEquals(ask("s1", "ask", "hi"), Right("Hello there"))
    val planned = double.messagesOf { case p: PlanRequest => p }
    assertEquals(planned.last.name, "ask")
    assertEquals(planned.last.sessionId, "s1")
    assertEquals(planned.last.payload.map(_.data.toStringUtf8), Some("hi"))
    assertEquals(model.lastRequest.systemMessage, Some("You are helpful"))
    assertEquals(userTexts(model.lastRequest.messages), Vector("hi"))
    assertEquals(model.lastRequest.tools.map(_.name), Vector("lookup", "boom"))
  }

  test("A2 the model calls a tool; it runs in the process with the model's arguments") {
    model
      .expectToolCall("lookup", Json.obj("id" -> Json.str("a")))
      .expectText("The count is 7")
    assertEquals(ask("s2", "ask", "how many?"), Right("The count is 7"))
    val calls = double.messagesOf { case t: ToolRequest => t }
    assertEquals(calls.last.tool, "lookup")
    assertEquals(calls.last.sessionId, "s2")
    assertEquals(calls.last.argumentsJson, """{"id":"a"}""")
    val results = model.lastRequest.messages.collect { case ChatMessage.ToolResults(rs) =>
      rs
    }.flatten
    assertEquals(
      results.map(r => (r.name, r.content, r.isError)),
      Vector(("lookup", """the count for {"id":"a"} is 7""", false))
    )
  }

  test("A3 a tool error is fed back to the model and the loop continues") {
    model.expectToolCall("boom", Json.obj()).expectText("sorry, that failed")
    assertEquals(ask("s3", "ask", "try it"), Right("sorry, that failed"))
    val results = model.lastRequest.messages.collect { case ChatMessage.ToolResults(rs) =>
      rs
    }.flatten
    assertEquals(
      results.map(r => (r.name, r.content, r.isError)),
      Vector(("boom", "no such thing", true))
    )
  }

  test("A4 a streaming handler's tokens arrive in order") {
    model.expectText("one two three")
    val descriptor = kit.service.registry.components.collectFirst {
      case a: com.thinkmorestupidless.ankka.agent.AgentDescriptor[?]
          if a.componentId == component =>
        a
    }.get
    val handle =
      descriptor.streams(MethodName("stream")).asInstanceOf[StreamHandle[RemoteAgent, Array[Byte]]]
    given org.apache.pekko.actor.typed.ActorSystem[?] = kit.service.system
    val tokens = Await.result(
      kit.componentClient.forAgent(SessionId("s4")).stream(handle)("go".getBytes).runWith(Sink.seq),
      20.seconds
    )
    assertEquals(tokens.toVector, Vector("one", " two", " three"))
    assertEquals(double.messagesOf { case p: PlanRequest => p }.last.name, "stream")
  }

  test("A5 an output guardrail in the process blocks the reply") {
    model.expectText("the key is sk-123")
    val blocked = ask("s5", "ask", "what is the key?")
    assert(blocked.isLeft, blocked)
    assertEquals(blocked.left.map(_.code), Left(ErrorCode.Forbidden))
    assert(blocked.left.exists(_.message.contains("a key leaked")), blocked)
    val checks = double.messagesOf { case g: GuardrailRequest => g }
    assert(
      checks.exists(g => g.stage == GuardrailRequest.Stage.OUTPUT && g.text.contains("sk-123"))
    )
  }

  test("A6 the session survives the process restarting: memory is the sidecar's") {
    model.expectText("noted")
    assertEquals(ask("s6", "ask", "remember the blue door"), Right("noted"))
    double.restart()
    model.expectText("the blue door")
    assertEquals(ask("s6", "ask", "what did I say?"), Right("the blue door"))
    assertEquals(
      userTexts(model.lastRequest.messages),
      Vector("remember the blue door", "what did I say?")
    )
  }

  test("A6b memory NONE: a turn the plan keeps out of the session") {
    model.expectText("ok")
    assertEquals(ask("s6", "forget", "secret"), Right("ok"))
    assertEquals(userTexts(model.lastRequest.messages), Vector("secret"))
    model.expectText("still")
    assertEquals(ask("s6", "ask", "and now?"), Right("still"))
    assert(!userTexts(model.lastRequest.messages).contains("secret"))
  }

  test("A7 max_tool_call_steps from discovery bounds the loop") {
    model
      .expectToolCall("lookup", Json.obj("id" -> Json.str("1")))
      .expectToolCall("lookup", Json.obj("id" -> Json.str("2")))
      .expectToolCall("lookup", Json.obj("id" -> Json.str("3")))
      .expectText("never reached")
    val result = ask("s7", "ask", "loop")
    assert(result.left.exists(_.message.contains("exceeded 2 tool-call steps")), result)
  }
