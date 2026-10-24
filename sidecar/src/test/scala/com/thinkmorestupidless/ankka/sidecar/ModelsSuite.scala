package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.providers.ProviderConfig
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.thinkmorestupidless.ankka.testollama.FakeOllama
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.nio.file.Files
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** The sidecar's scripted model: ordered turns, and standing rules on what a request contains. */
class ModelsSuite extends munit.FunSuite with LogCapturing:

  private def next(model: TestModelProvider, request: ModelRequest): ModelResponse =
    Await.result(model.complete(request), 1.second)

  private def asking(text: String, toolResult: Option[String] = None) =
    ModelRequest(
      ModelSettings("scripted"),
      None,
      Vector(ChatMessage.User.text(text)) ++
        toolResult.map(r => ChatMessage.ToolResults(Vector(ToolResult("c", "t", r)))).toVector
    )

  private def scripted(json: String) = Models.scriptedFrom(json).fold(fail(_), identity)

  test("ordered turns are used in order, then rules") {
    val model = scripted("""[{"text":"first"},{"when":"hello","text":"hi"}]""")
    assertEquals(next(model, asking("hello")).text, "first")
    assertEquals(next(model, asking("hello")).text, "hi")
  }

  test("a rule may answer with a tool call, not only text") {
    val model = scripted("""[{"when":"cart q1","tool":"cart_total","arguments":{"cartId":"q1"}}]""")
    val call  = next(model, asking("How many in cart q1?")).toolCalls.head
    assertEquals(
      (call.name, call.arguments("cartId").flatMap(_.asString)),
      ("cart_total", Some("q1"))
    )
  }

  test("a rule on the latest tool result, checked in the order the rules are declared") {
    val model = scripted(
      """[{"when_tool_result":"holds","tool":"complete_task","arguments":{"answer":"3"}},
        | {"when":"q1","tool":"cart_total","arguments":{"cartId":"q1"}}]""".stripMargin
    )
    assertEquals(next(model, asking("q1")).toolCalls.head.name, "cart_total")
    assertEquals(
      next(model, asking("q1", Some("cart q1 holds 3 items"))).toolCalls.head.name,
      "complete_task"
    )
  }

  test("a turn that is none of text, tool or refusal is refused, naming the turn") {
    assertEquals(
      Models.scriptedFrom("""[{"text":"ok"},{"when":"x"}]"""),
      Left("turn 1: a turn is a text, a tool call, or a refusal")
    )
  }

  // ── The sidecar's model table, from its variables ─────────────────────────

  private def refusal(env: Map[String, String]): String =
    intercept[IllegalArgumentException](Models.fromEnv(env)).getMessage

  private val local = Map(
    "ANKKA_MODEL_LOCAL_PROTOCOL" -> "openai",
    "ANKKA_MODEL_LOCAL_URL"      -> "http://localhost:11434/v1",
    "ANKKA_MODEL_LOCAL_MODEL"    -> "qwen3:8b"
  )

  test("both vendors' keys give anthropic and openai, and no default until one is named") {
    val both   = Map("ANTHROPIC_API_KEY" -> "a", "OPENAI_API_KEY" -> "o")
    val models = Models.fromEnv(both)
    assertEquals(models.providers.keySet, Set("anthropic", "openai"))
    assertEquals(
      models.providers.map((k, p) => k -> p.name),
      Map("anthropic" -> "anthropic", "openai" -> "openai")
    )
    assertEquals(models.default, None)
    assertEquals(
      models.withoutDefault,
      "the sidecar has 2 models and no default: set ANKKA_MODEL_DEFAULT to one of anthropic, openai"
    )
    assertEquals(
      Models.fromEnv(both + ("ANKKA_MODEL_DEFAULT" -> "openai")).default.map(_.name),
      Some("openai")
    )
  }

  for (key, name) <- Vector("ANTHROPIC_API_KEY" -> "anthropic", "OPENAI_API_KEY" -> "openai") do
    test(s"one vendor's key alone is the default: $name") {
      assertEquals(Models.fromEnv(Map(key -> "k")).default.map(_.name), Some(name))
    }

  test("the Claude model is ANKKA_MODEL_NAME, and the same setting given twice must agree") {
    val models =
      Models.fromEnv(Map("ANTHROPIC_API_KEY" -> "a", "ANKKA_MODEL_NAME" -> "claude-sonnet-5"))
    assertEquals(models.default.map(_.modelName), Some("claude-sonnet-5"))
    assertEquals(
      refusal(
        Map(
          "ANTHROPIC_API_KEY"           -> "a",
          "ANKKA_MODEL_NAME"            -> "claude-opus-5",
          "ANKKA_MODEL_ANTHROPIC_MODEL" -> "claude-sonnet-5"
        )
      ),
      "ANKKA_MODEL_NAME and ANKKA_MODEL_ANTHROPIC_MODEL are one setting and differ: 'claude-opus-5' and 'claude-sonnet-5'"
    )
  }

  test("a script beside a key is still nameable as scripted, and not the default") {
    val models =
      Models.fromEnv(Map("OPENAI_API_KEY" -> "o", "ANKKA_MODEL_SCRIPT" -> """[{"text":"x"}]"""))
    assertEquals(models.default.map(_.name), Some("openai"))
    assert(models.named("scripted").isDefined)
  }

  test(
    "a model provider is configured by name from a model protocol, an address, a model and no key"
  ) {
    val provider = Models.fromEnv(local).named("local").getOrElse(fail("not configured"))
    assertEquals(provider.name, "local")
    assertEquals(provider.modelName, "qwen3:8b")
    assertEquals(Models.fromEnv(local).default.map(_.name), Some("local"))
  }

  for (left, missing) <- Vector(
      "ANKKA_MODEL_LOCAL_PROTOCOL" -> "a model protocol is missing; set ANKKA_MODEL_LOCAL_PROTOCOL or ANKKA_MODEL_LOCAL_PRESET",
      "ANKKA_MODEL_LOCAL_MODEL" -> "a model is missing; set ANKKA_MODEL_LOCAL_MODEL",
      "ANKKA_MODEL_LOCAL_URL"   -> "an address is missing; set ANKKA_MODEL_LOCAL_URL"
    )
  do
    test(s"a model provider configured without $left is refused at the service's start") {
      assertEquals(refusal(local - left), s"model provider 'local': $missing")
    }

  for (name, variable, expected) <- Vector(
      ("scripted", "ANKKA_MODEL_SCRIPTED_URL", "'scripted' is reserved"),
      ("default", "ANKKA_MODEL_DEFAULT_URL", "'default' is reserved"),
      ("name", "ANKKA_MODEL_NAME_URL", "'name' is reserved"),
      ("script", "ANKKA_MODEL_SCRIPT_URL", "'script' is reserved"),
      ("Fast", "ANKKA_MODEL_Fast_URL", "'Fast' is not a name a model provider may have"),
      (
        "my_model",
        "ANKKA_MODEL_MY_MODEL_URL",
        "'my_model' is not a name a model provider may have"
      ),
      (
        "local-fast",
        "ANKKA_MODEL_LOCAL-FAST_URL",
        "'local-fast' is not a name a model provider may have"
      ),
      ("2fast", "ANKKA_MODEL_2FAST_URL", "'2fast' is not a name a model provider may have")
    )
  do
    test(
      s"a model provider configured with a reserved name is refused at the service's start: $name"
    ) {
      val message = refusal(Map(variable -> "http://localhost:1/v1"))
      assert(message.startsWith(s"$variable: $expected"), message)
    }

  for (variable, value, expected) <- Vector(
      (
        "ANKKA_MODEL_LOCAL_PROTOCOL",
        "grpc",
        "ANKKA_MODEL_LOCAL_PROTOCOL: 'grpc' is not a model protocol"
      ),
      (
        "ANKKA_MODEL_LOCAL_URL",
        "not a url",
        "ANKKA_MODEL_LOCAL_URL: 'not a url' is not an http or https address"
      ),
      (
        "ANKKA_MODEL_LOCAL_PRESET",
        "no-such-preset",
        "ANKKA_MODEL_LOCAL_PRESET: 'no-such-preset' is not a preset"
      ),
      (
        "ANKKA_MODEL_DEFAULT",
        "fast",
        "ANKKA_MODEL_DEFAULT: 'fast' is not a configured model provider; the providers are local"
      )
    )
  do
    test(
      s"a model provider whose settings do not agree is refused at the service's start: $variable"
    ) {
      val message = refusal(local + (variable -> value))
      assert(message.startsWith(expected), message)
    }

  test(
    "a variable of the model provider family that is not a setting is refused at the service's start"
  ) {
    assertEquals(
      refusal(local + ("ANKKA_MODEL_FAST_URLS" -> "http://x")),
      "ANKKA_MODEL_FAST_URLS: not a model setting; a provider's settings are ANKKA_MODEL_<NAME>_{PROTOCOL,URL,MODEL,KEY,SHAPE,PRESET}"
    )
  }

  test("a value of the model provider family beside a vendor's key overrides what the key set") {
    val models = Models.fromEnv(
      Map(
        "OPENAI_API_KEY"           -> "o",
        "ANKKA_MODEL_OPENAI_URL"   -> "http://proxy.internal/v1",
        "ANKKA_MODEL_OPENAI_MODEL" -> "gpt-5-mini"
      )
    )
    assertEquals(models.providers.keySet, Set("openai"), "one provider, not two")
    assertEquals(models.named("openai").map(_.modelName), Some("gpt-5-mini"))
  }

  // ── Presets ────────────────────────────────────────────────────────────────

  test("a preset configures a model provider from a name and a key") {
    val eu = Models
      .fromEnv(Map("ANKKA_MODEL_EU_PRESET" -> "mistral", "ANKKA_MODEL_EU_KEY" -> "k"))
      .named("eu")
      .get
    assertEquals(eu.modelName, "mistral-large-latest")
  }

  test("a preset that needs a key is refused at the service's start without one") {
    assertEquals(
      refusal(Map("ANKKA_MODEL_EU_PRESET" -> "mistral")),
      "model provider 'eu': the preset 'mistral' needs a key; set ANKKA_MODEL_EU_KEY"
    )
  }

  test("a value given beside a preset overrides what the preset set") {
    val eu = Models
      .fromEnv(
        Map(
          "ANKKA_MODEL_EU_PRESET" -> "mistral",
          "ANKKA_MODEL_EU_KEY"    -> "k",
          "ANKKA_MODEL_EU_MODEL"  -> "mistral-small-latest"
        )
      )
      .named("eu")
      .get
    assertEquals(eu.modelName, "mistral-small-latest")
  }

  test("a preset without a key is a model provider at an address that needs none") {
    val local = Models.fromEnv(
      Map("ANKKA_MODEL_LOCAL_PRESET" -> "ollama", "ANKKA_MODEL_LOCAL_MODEL" -> "qwen3:8b")
    )
    assertEquals(local.named("local").map(_.name), Some("local"))
  }

  // ── Installation models ────────────────────────────────────────────────────

  private val noCertificate: Map[String, String] => Either[String, RotatingTls] =
    _ => Left("no certificate here")

  private val installed = Map(
    "ANKKA_MODEL_LOCAL_PRESET" -> "installation",
    "ANKKA_MODEL_LOCAL_MODEL"  -> "qwen3:0.6b",
    "ANKKA_MODELS_ADDRESS"     -> "https://models.models-system.svc:8443"
  )

  test(
    "the preset installation is resolved against the model server, with the service's certificate"
  ) {
    val authority = TestPki.root("service authority")
    val door      = FakeOllama.startTls(
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
      val models = Models.fromEnv(
        installed + ("ANKKA_MODELS_ADDRESS" -> door.url),
        _ => Right(service)
      )
      val local = models.named("local").get
      assertEquals(local.modelName, "qwen3:0.6b")
      assertEquals(models.default.map(_.name), Some("local"))
      val answer = Await.result(
        local.complete(
          ModelRequest(ModelSettings("qwen3:0.6b"), None, Vector(ChatMessage.User.text("hi")))
        ),
        30.seconds
      )
      assertEquals(answer.text, "Hello")
      assertEquals(
        door.requestsTo("POST", "/v1/chat/completions").head.header("authorization"),
        Some("Bearer none")
      )
    finally door.stop()
  }

  test("an installation model is refused at the service's start beside an address or a key") {
    def refused(env: Map[String, String]) =
      intercept[IllegalArgumentException](Models.fromEnv(env, noCertificate)).getMessage
    assertEquals(
      refused(installed + ("ANKKA_MODEL_LOCAL_URL" -> "http://elsewhere:11434/v1")),
      "ANKKA_MODEL_LOCAL_URL: an installation model takes no address"
    )
    assertEquals(
      refused(installed + ("ANKKA_MODEL_LOCAL_KEY" -> "k")),
      "ANKKA_MODEL_LOCAL_KEY: an installation model takes no key"
    )
    assertEquals(
      refused(installed - "ANKKA_MODEL_LOCAL_MODEL"),
      "ANKKA_MODEL_LOCAL_MODEL: an installation model must be named"
    )
    assertEquals(
      refused(installed),
      "model provider 'local': no certificate here"
    )
  }

  test(
    "a service run outside an installation that names the preset installation is told there is no model server"
  ) {
    assertEquals(
      intercept[IllegalArgumentException](
        Models.fromEnv(installed - "ANKKA_MODELS_ADDRESS", noCertificate)
      ).getMessage,
      s"model provider 'local': ${ProviderConfig.NoModelServer}"
    )
  }

  test("the service's certificate is read only for an installation model") {
    var asked = 0
    Models.fromEnv(
      Map("ANKKA_MODEL_LOCAL_PRESET" -> "ollama", "ANKKA_MODEL_LOCAL_MODEL" -> "qwen3:8b"),
      _ =>
        asked += 1
        Left("not wanted")
    ): Unit
    assertEquals(asked, 0)
  }
