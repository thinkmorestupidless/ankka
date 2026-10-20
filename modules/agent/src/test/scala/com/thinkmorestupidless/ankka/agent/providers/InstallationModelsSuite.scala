package com.thinkmorestupidless.ankka.agent.providers

import com.thinkmorestupidless.ankka.agent.{
  ChatMessage,
  Effort,
  Json,
  ModelCallFailed,
  ModelProvider,
  ModelRequest,
  ModelSettings,
  ToolSpec
}
import com.thinkmorestupidless.ankka.runtime.{RotatingTls, Trace, TraceContext, Traceparent}
import com.thinkmorestupidless.ankka.testollama.FakeOllama
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.nio.file.Files
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The preset `installation` against a stand-in model server behind mutual TLS, as a service meets
 * the real one through its door: the service's certificate presented, the server's identity
 * required, no key sent, and a model's capabilities asked before it is called.
 */
class InstallationModelsSuite extends munit.FunSuite:

  private val authority = TestPki.root("service authority")

  private def directory(leaf: TestPki.Leaf) =
    RotatingTls(leaf.writeTo(Files.createTempDirectory("ankka-tls")), 1.minute)

  private val service =
    directory(authority.issue(uris = Seq("ankka://shop/cart")))

  private def server(identity: String): FakeOllama =
    FakeOllama.startTls(
      directory(authority.issue(uris = Seq(identity), dnsNames = Seq("localhost"))).sslContext
    )

  private val door = FunFixture[FakeOllama](
    _ => server(InstallationModels.ServerIdentity).hold("qwen3:0.6b"),
    _.stop()
  )

  private def providerAt(fake: FakeOllama, model: String = "qwen3:0.6b"): ModelProvider =
    val resolved = ProviderConfig("local", model = Some(model), preset = Some("installation"))
      .resolve(Some(fake.url))
      .fold(p => fail(p.describe), identity)
    ModelProviders.configure(resolved, Right(service))

  private def ask(
      provider: ModelProvider,
      tools: Vector[ToolSpec] = Vector.empty,
      effort: Option[Effort] = None
  ): Either[Throwable, String] =
    val request = ModelRequest(
      ModelSettings(provider.modelName, effort = effort),
      None,
      Vector(ChatMessage.User.text("hello")),
      tools
    )
    scala.util.Try(Await.result(provider.complete(request), 30.seconds)).toEither.map(_.text)

  private val lookup =
    ToolSpec("lookup", "Looks a thing up.", Json.obj("type" -> Json.Str("object")))

  door.test("a call presents the service's certificate and sends no key") { fake =>
    assertEquals(ask(providerAt(fake)), Right("Hello"))
    val sent = fake.requestsTo("POST", "/v1/chat/completions")
    assertEquals(sent.size, 1)
    assertEquals(sent.head.header("authorization"), Some("Bearer none"))
    assertEquals(sent.head.header("traceparent"), None)
  }

  door.test("a call carries the trace of the turn that makes it") { fake =>
    val provider = providerAt(fake)
    assertEquals(Trace.within(0xabcdefL, 0x1234L)(ask(provider)), Right("Hello"))
    val sent = fake.requestsTo("POST", "/v1/chat/completions")
    assertEquals(
      sent.head.header("traceparent"),
      Some(Traceparent.render(TraceContext(0L, 0xabcdefL, 0x1234L)))
    )
  }

  test("a server without the model server's identity is never sent the prompt") {
    val impostor = server("ankka://shop/other").hold("qwen3:0.6b")
    try
      val outcome = ask(providerAt(impostor))
      assert(outcome.isLeft, s"answered by an impostor: $outcome")
      assertEquals(impostor.requests, Vector.empty)
    finally impostor.stop()
  }

  door.test("an agent with tools on a model that cannot call tools is told so at the first call") {
    fake =>
      fake.hold("tiny:1b", capabilities = Vector("completion"))
      ask(providerAt(fake, "tiny:1b"), tools = Vector(lookup)) match
        case Left(ModelCallFailed(name, message, _)) =>
          assertEquals(name, "local")
          assertEquals(message, "installation model \"tiny:1b\" lacks tool calling")
        case other => fail(s"expected the capability refused, got $other")
      assertEquals(fake.requestsTo("POST", "/v1/chat/completions"), Vector.empty)
  }

  door.test("effort on a model that cannot think is told so at the first call") { fake =>
    fake.hold("gemma:1b", capabilities = Vector("completion", "tools"))
    ask(providerAt(fake, "gemma:1b"), effort = Some(Effort.High)) match
      case Left(ModelCallFailed(_, message, _)) =>
        assertEquals(message, "installation model \"gemma:1b\" lacks thinking")
      case other => fail(s"expected the capability refused, got $other")
    assertEquals(fake.requestsTo("POST", "/v1/chat/completions"), Vector.empty)
  }

  door.test("a model with the capabilities a call needs is called, and asked about once") { fake =>
    val provider = providerAt(fake)
    assertEquals(ask(provider, tools = Vector(lookup)), Right("Hello"))
    assertEquals(ask(provider, tools = Vector(lookup)), Right("Hello"))
    assertEquals(fake.requestsTo("POST", "/api/show").size, 1)
    assertEquals(fake.requestsTo("POST", "/v1/chat/completions").size, 2)
  }

  door.test("a model the server cannot say anything about is asked about again at the next call") {
    fake =>
      val provider = providerAt(fake, "nope:1b")
      assert(ask(provider).isLeft)
      assert(ask(provider).isLeft)
      assertEquals(fake.requestsTo("POST", "/api/show").size, 2)
  }
