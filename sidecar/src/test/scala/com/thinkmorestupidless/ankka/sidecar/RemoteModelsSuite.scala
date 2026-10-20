package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.agent.{
  AgentRuntime,
  ModelResponse,
  ModelStandIn,
  SessionMemoryEntity,
  StopReason,
  TestModelProvider,
  TokenUsage,
  ToolCall,
  Json,
  forSessionMemory
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
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import com.thinkmorestupidless.ankka.testollama.FakeOllama
import com.thinkmorestupidless.ankka.testpki.TestPki
import ankka.protocol.v1.agent.AgentPlan
import io.grpc.ManagedChannelBuilder

import java.nio.file.Files
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import scala.util.Try

/**
 * A process-hosted service naming its model providers: the sidecar answers each plan with the
 * provider it names, or its default, and refuses only a plan it cannot answer. The providers are
 * scripted, or configured from the sidecar's own variables against a stand-in on loopback.
 */
class RemoteModelsSuite extends munit.FunSuite with LogCapturing:
  import ProcessDouble.*

  override val munitTimeout = 3.minutes

  given ExecutionContext = ExecutionContext.global

  private def naming(model: Option[String]): String => AgentPlan =
    input => plan("Answer briefly.", input, Vector("lookup"), model = model)

  private val assistant = AgentOf(
    "assistant",
    handlers = Map(
      "ask"           -> naming(None),
      "ask-openai"    -> naming(Some("openai")),
      "ask-anthropic" -> naming(Some("anthropic")),
      "ask-local"     -> naming(Some("local"))
    ),
    tools = Map("lookup" -> ((_, arguments) => Right(s"looked up $arguments")))
  )

  private final class Sidecar(val kit: AnkkaTestKit, close: () => Unit):
    def ask(session: String, handler: String, input: String): Either[CommandError, String] =
      Try(
        Await.result(
          kit.componentClient.transportRef.ask(
            ComponentId("assistant"),
            EntityId(session),
            MethodName(handler),
            input.getBytes,
            Metadata.empty
          ),
          20.seconds
        )
      ).toEither.map(String(_)).left.map {
        case e: CommandError => e
        case other           => CommandError(other.getMessage, ErrorCode.Internal)
      }

    def history(session: String) =
      kit.componentClient
        .forSessionMemory(SessionId(session))
        .call(SessionMemoryEntity.history)
        .invoke()

    def stop(): Unit = close()

  /** A sidecar in front of the double, answering agents from `models`. */
  private def sidecar(models: Models): Sidecar =
    val double  = new ProcessDouble(DoubleSpec(agents = Vector(assistant)))
    val port    = double.start()
    val channel = ManagedChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
    val settings =
      Settings(s"127.0.0.1:$port", 0, "127.0.0.1", 5.seconds, 1.second, 5.seconds, 5.seconds)
    val conversation = GrpcConversation(channel, settings)
    val discovered = Discovery
      .validate(double.toSpec, Discovery.ProtocolVersion, authConfigured = true)
      .fold(p => fail(p.mkString("; ")), identity)
    val agents = discovered.agents.map(c =>
      RemoteAgent.descriptor(RemoteAgent.spec(c).toOption.get, conversation, models, 5.seconds)
    )
    val runtime = models.default.fold(AgentRuntime())(AgentRuntime.withDefaultModel(_))
    val kit = AnkkaTestKit.start(
      discovered.descriptors ++ agents ++ AgentRuntime.descriptors,
      Seq(runtime),
      60.seconds,
      _.withConversation(conversation)
    )
    Sidecar(
      kit,
      () =>
        Try(kit.stop())
        channel.shutdownNow()
        double.stop()
    )

  private def using[A](models: Models)(body: Sidecar => A): A =
    val s = sidecar(models)
    try body(s)
    finally s.stop()

  private val anthropic = TestModelProvider("claude-opus-5", name = "anthropic")
  private val openai    = TestModelProvider("gpt-6.1-sol", name = "openai")

  override def beforeEach(context: BeforeEach): Unit =
    anthropic.reset()
    openai.reset()

  test(
    "an agent naming the model provider openai is answered by it, and one naming none by the default"
  ) {
    openai.expectText("from openai")
    anthropic.expectText("from anthropic").expectText("from the default")
    using(Models(Map("anthropic" -> anthropic, "openai" -> openai), Some(anthropic))) { s =>
      assertEquals(s.ask("s-1", "ask-openai", "hi"), Right("from openai"))
      assertEquals(s.ask("s-2", "ask-anthropic", "hi"), Right("from anthropic"))
      assertEquals(s.ask("s-3", "ask", "hi"), Right("from the default"))
    }
    assertEquals(openai.callCount, 1)
    assertEquals(anthropic.callCount, 2)
  }

  // ── Configured from the sidecar's variables, against a stand-in ──────────

  private def standInVariables(at: ModelStandIn, names: String*): Map[String, String] =
    names.flatMap { name =>
      val upper = name.toUpperCase
      Vector(
        s"ANKKA_MODEL_${upper}_PROTOCOL" -> "openai",
        s"ANKKA_MODEL_${upper}_URL"      -> s"${at.address}/v1",
        s"ANKKA_MODEL_${upper}_MODEL"    -> s"$name-model"
      )
    }.toMap

  test(
    "a service written in another language with two model providers and no default model refuses only an agent that names none"
  ) {
    val at = ModelStandIn.start()
    try
      at.answer(ModelResponse("from openai"))
      using(Models.fromEnv(standInVariables(at, "anthropic", "openai"))) { s =>
        assertEquals(s.ask("s-4", "ask-openai", "hi"), Right("from openai"))
        val refused =
          s.ask("s-5", "ask", "hi").left.toOption.getOrElse(fail("an unnamed plan is answered"))
        assertEquals(refused.code, ErrorCode.Unavailable)
        assertEquals(
          refused.getMessage,
          "the sidecar has 2 models and no default: set ANKKA_MODEL_DEFAULT to one of anthropic, openai"
        )
      }
    finally at.close()
  }

  test(
    "a service written in another language with one model provider has it as its default model"
  ) {
    val at = ModelStandIn.start()
    try
      at.answer(ModelResponse("from the only one"))
      using(Models.fromEnv(standInVariables(at, "openai"))) { s =>
        assertEquals(s.ask("s-6", "ask", "hi"), Right("from the only one"))
      }
    finally at.close()
  }

  test(
    "a model provider is configured by name from a model protocol, an address, a model and no key"
  ) {
    val at = ModelStandIn.start()
    try
      at.answer(ModelResponse("from the laptop", usage = TokenUsage(6, 2)))
      using(Models.fromEnv(standInVariables(at, "local"))) { s =>
        assertEquals(s.ask("s-7", "ask-local", "hi"), Right("from the laptop"))
        assertEquals(s.history("s-7").usageByProvider, Map("local" -> TokenUsage(6, 2)))
      }
      val request = at.lastRequest
      assertEquals(request.path, "/v1/chat/completions")
      assertEquals(request.body("model").flatMap(_.asString), Some("local-model"))
      assertEquals(request.header("authorization"), Some("Bearer none"))
    finally at.close()
  }

  test("a model provider at an address of the developer's own answers an agent with tool calls") {
    val at = ModelStandIn.start()
    try
      at.answer(
        ModelResponse(
          "",
          Vector(ToolCall("c-1", "lookup", Json.obj("city" -> Json.str("Berlin")))),
          StopReason.ToolUse
        )
      )
      at.answer(ModelResponse("It is sunny."))
      using(Models.fromEnv(standInVariables(at, "local"))) { s =>
        assertEquals(s.ask("s-8", "ask-local", "weather?"), Right("It is sunny."))
      }
      val sent = at.lastRequest.body.render
      assert(sent.contains("looked up"), "the model is sent the tool's result: " + sent)
    finally at.close()
  }

  test("the model provider's name is the service's, in failures and model usage") {
    val at        = ModelStandIn.start()
    val variables = standInVariables(at, "fast")
    at.close() // nothing answers at the address now
    using(Models.fromEnv(variables)) { s =>
      val refused = s.ask("s-9", "ask", "hi").left.toOption.getOrElse(fail("answered by nothing"))
      assertEquals(refused.code, ErrorCode.Unavailable)
      assert(refused.getMessage.startsWith("fast: could not be reached"), refused.getMessage)
      assert(!refused.getMessage.toLowerCase.contains("openai"), refused.getMessage)
    }
  }

  test(
    "an agent of a process naming an installation model is answered by the model server, and its turn recorded under the provider"
  ) {
    val authority = TestPki.root("service authority")
    val door = FakeOllama.startTls(
      RotatingTls(
        authority
          .issue(uris = Seq("ankka://platform/models"), dnsNames = Seq("localhost"))
          .writeTo(Files.createTempDirectory("ankka-door")),
        1.minute
      ).sslContext
    )
    try
      door.hold("qwen3:0.6b")
      val service = RotatingTls(
        authority
          .issue(uris = Seq("ankka://shop/support"))
          .writeTo(Files.createTempDirectory("ankka-service")),
        1.minute
      )
      val variables = Map(
        "ANKKA_MODEL_LOCAL_PRESET" -> "installation",
        "ANKKA_MODEL_LOCAL_MODEL"  -> "qwen3:0.6b",
        "ANKKA_MODELS_ADDRESS"     -> door.url
      )
      using(Models.fromEnv(variables, _ => Right(service))) { s =>
        assertEquals(s.ask("s-11", "ask-local", "hi"), Right("Hello"))
        assertEquals(s.history("s-11").usageByProvider.keySet, Set("local"))
      }
      assertEquals(door.requestsTo("POST", "/v1/chat/completions").size, 1)
    finally door.stop()
  }

  test("a model provider whose address reports no model usage is recorded as none") {
    val at = ModelStandIn.start().reportNoUsage()
    try
      at.answer(ModelResponse("hi", usage = TokenUsage(5, 5)))
      using(Models.fromEnv(standInVariables(at, "local"))) { s =>
        assertEquals(s.ask("s-10", "ask-local", "hi"), Right("hi"))
        assertEquals(s.history("s-10").usageByProvider, Map("local" -> TokenUsage.zero))
      }
    finally at.close()
  }
