package com.thinkmorestupidless.ankka.agent.providers

/** Every way a model provider's configuration is refused, in the words it is refused in. */
class ProviderConfigSuite extends munit.FunSuite:

  private def variable(name: String)(setting: String) = s"ANKKA_MODEL_${name.toUpperCase}_$setting"

  private def refusal(config: ProviderConfig): String =
    config.resolve match
      case Left(problem) => problem.describe(variable(config.name))
      case Right(ok)     => fail(s"resolved as $ok")

  private val local =
    ProviderConfig(
      "local",
      protocol = Some("openai"),
      url = Some("http://localhost:11434/v1"),
      model = Some("qwen3:8b")
    )

  test("four values with no key are a keyless endpoint") {
    val resolved = local.resolve.toOption.get
    assertEquals(resolved.protocol, ModelProtocol.OpenAi)
    assertEquals(resolved.shape, Shape.ChatCompletions)
    assertEquals(resolved.url, "http://localhost:11434/v1")
    assertEquals(resolved.model, "qwen3:8b")
    assertEquals(resolved.key, None)
    assertEquals(resolved.quirks, Quirks.compatibleOpenAi)
  }

  test("an empty key is no key") {
    assertEquals(local.copy(key = Some("")).resolve.toOption.get.key, None)
  }

  test("what is missing is named, with the variables that would supply it") {
    assertEquals(
      refusal(local.copy(protocol = None)),
      "model provider 'local': a model protocol is missing; set ANKKA_MODEL_LOCAL_PROTOCOL or ANKKA_MODEL_LOCAL_PRESET"
    )
    assertEquals(
      refusal(local.copy(model = None)),
      "model provider 'local': a model is missing; set ANKKA_MODEL_LOCAL_MODEL"
    )
    assertEquals(
      refusal(local.copy(url = None)),
      "model provider 'local': an address is missing; set ANKKA_MODEL_LOCAL_URL"
    )
  }

  test("a value that is not one the setting takes is named with its variable") {
    assertEquals(
      refusal(local.copy(protocol = Some("grpc"))),
      "ANKKA_MODEL_LOCAL_PROTOCOL: 'grpc' is not a model protocol; use anthropic or openai"
    )
    assertEquals(
      refusal(local.copy(url = Some("not a url"))),
      "ANKKA_MODEL_LOCAL_URL: 'not a url' is not an http or https address"
    )
    assertEquals(
      refusal(local.copy(shape = Some("websocket"))),
      "ANKKA_MODEL_LOCAL_SHAPE: 'websocket' is not a shape; use responses or chat-completions"
    )
    assert(
      refusal(local.copy(preset = Some("no-such-preset")))
        .startsWith(
          "ANKKA_MODEL_LOCAL_PRESET: 'no-such-preset' is not a preset; the presets are anthropic, azure,"
        )
    )
    assertEquals(
      refusal(local.copy(protocol = Some("anthropic"), shape = Some("responses"))),
      "ANKKA_MODEL_LOCAL_SHAPE: a shape applies to the openai protocol only"
    )
  }

  test("a preset that needs a key is refused without one, naming the variable that gives it") {
    assertEquals(
      refusal(ProviderConfig("eu", preset = Some("mistral"))),
      "model provider 'eu': the preset 'mistral' needs a key; set ANKKA_MODEL_EU_KEY"
    )
  }

  test("a preset and a key are a whole configuration, and a value beside it overrides it") {
    val eu = ProviderConfig("eu", preset = Some("mistral"), key = Some("k")).resolve.toOption.get
    assertEquals(eu.url, "https://api.mistral.ai/v1")
    assertEquals(eu.model, "mistral-large-latest")
    assertEquals(eu.quirks.toolCallIds, Some(IdRule.Alphanumeric(9)))
    val small = ProviderConfig(
      "eu",
      preset = Some("mistral"),
      key = Some("k"),
      model = Some("mistral-small-latest")
    )
    assertEquals(small.resolve.toOption.get.model, "mistral-small-latest")
    assertEquals(small.resolve.toOption.get.url, "https://api.mistral.ai/v1")
  }

  test("a preset with no key needed is an address that needs none") {
    val ollama = ProviderConfig(
      "local",
      preset = Some("ollama"),
      model = Some("qwen3:8b")
    ).resolve.toOption.get
    assertEquals(ollama.url, "http://localhost:11434/v1")
    assertEquals(ollama.key, None)
  }

  test("a protocol given against a preset's is another endpoint, with a bare one's quirks") {
    val messages =
      ProviderConfig(
        "local",
        preset = Some("ollama"),
        protocol = Some("anthropic"),
        model = Some("qwen3:8b")
      ).resolve.toOption.get
    assertEquals(messages.protocol, ModelProtocol.Anthropic)
    assertEquals(messages.quirks, Quirks.compatibleAnthropic)
  }

  test(
    "a name is lowercase letters and digits starting with a letter, and not one the platform keeps"
  ) {
    Vector("local", "fast", "claude2", "eu").foreach(n =>
      assertEquals(ModelProviders.validName(n), Right(n))
    )
    Vector("Fast", "my_model", "local-fast", "2fast", "").foreach { n =>
      assertEquals(
        ModelProviders.validName(n),
        Left(
          s"'$n' is not a name a model provider may have: lowercase letters and digits, starting with a letter"
        )
      )
    }
    Vector("scripted", "default", "name", "script").foreach { n =>
      assertEquals(
        ModelProviders.validName(n),
        Left(
          s"'$n' is reserved; a model provider may not be named scripted, default, name or script"
        )
      )
    }
  }

  test("a Scala service is told the same problem without the sidecar's variables") {
    val error = intercept[IllegalArgumentException](
      ModelProviders.named("local", preset = Some("bedrock"), key = Some("k"))
    )
    assertEquals(error.getMessage, "model provider 'local': an address is missing")
  }
