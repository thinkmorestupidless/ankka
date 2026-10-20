package com.thinkmorestupidless.ankka.agent.providers

/**
 * A tested endpoint's configuration, under a name.
 *
 * A service names a preset and gives what it leaves open: a key where one is needed, a model where
 * the preset names none, an address where none is fixed. Anything given beside a preset overrides
 * what it set.
 *
 * @param address
 *   where the endpoint is, or `None` when it differs per account (Bedrock's region, Azure's
 *   resource)
 * @param defaultModel
 *   the model used when none is given, or `None` when the model must be given: every local server,
 *   and every vendor whose model names change faster than a release
 * @param asOf
 *   when the address and the default model were last read from the vendor
 */
final case class Preset(
    name: String,
    protocol: ModelProtocol,
    shape: Shape,
    address: Option[String],
    keyRequired: Boolean,
    defaultModel: Option[String],
    quirks: Quirks,
    asOf: String,
    notes: String = ""
)

/** Every preset the platform names. The documentation's table is generated from this list. */
object Presets:

  private val Read = "2026-10-17"

  /**
   * The installation's own model server (feature 053). Its address is the platform's
   * (`ANKKA_MODELS_ADDRESS`) and its credential is the service's certificate, so a service gives a
   * model and nothing else; `ProviderConfig.resolve` holds those rules.
   */
  val Installation: String = "installation"

  private def openAiCompatible(
      name: String,
      address: Option[String],
      keyRequired: Boolean,
      defaultModel: Option[String] = None,
      quirks: Quirks = Quirks.compatibleOpenAi,
      shape: Shape = Shape.ChatCompletions,
      notes: String = ""
  ): Preset =
    Preset(
      name,
      ModelProtocol.OpenAi,
      shape,
      address,
      keyRequired,
      defaultModel,
      quirks,
      Read,
      notes
    )

  val all: Map[String, Preset] = Vector(
    openAiCompatible(
      "openai",
      Some("https://api.openai.com/v1"),
      keyRequired = true,
      defaultModel = Some("gpt-6.1-sol"),
      quirks = Quirks.openAi,
      shape = Shape.Responses,
      notes = "OpenAI's own API; `OPENAI_API_KEY` is its shorthand"
    ),
    Preset(
      "anthropic",
      ModelProtocol.Anthropic,
      Shape.ChatCompletions,
      Some("https://api.anthropic.com"),
      keyRequired = true,
      defaultModel = Some("claude-opus-5"),
      quirks = Quirks.anthropic,
      asOf = Read,
      notes = "Anthropic's own API; `ANTHROPIC_API_KEY` is its shorthand"
    ),
    openAiCompatible(
      "ollama",
      Some("http://localhost:11434/v1"),
      keyRequired = false,
      notes = "also serves Anthropic's protocol at `http://localhost:11434`"
    ),
    openAiCompatible(
      Installation,
      None,
      keyRequired = false,
      notes = "the installation's model server; the platform gives the address and the certificate"
    ),
    openAiCompatible("lmstudio", Some("http://localhost:1234/v1"), keyRequired = false),
    openAiCompatible(
      "llamacpp",
      Some("http://127.0.0.1:9931/v1"),
      keyRequired = false,
      notes = "older builds listen on `8080`"
    ),
    openAiCompatible(
      "docker-model-runner",
      Some("http://localhost:12434/engines/v1"),
      keyRequired = false,
      notes = "from a container: `http://model-runner.docker.internal/engines/v1`"
    ),
    openAiCompatible("vllm", Some("http://localhost:8000/v1"), keyRequired = false),
    openAiCompatible(
      "groq",
      Some("https://api.groq.com/openai/v1"),
      keyRequired = true,
      defaultModel = Some("openai/gpt-oss-120b")
    ),
    openAiCompatible(
      "mistral",
      Some("https://api.mistral.ai/v1"),
      keyRequired = true,
      defaultModel = Some("mistral-large-latest"),
      quirks = Quirks.compatibleOpenAi.copy(toolCallIds = Some(IdRule.Alphanumeric(9))),
      notes = "tool call ids are rewritten to nine letters and digits"
    ),
    openAiCompatible("deepseek", Some("https://api.deepseek.com"), keyRequired = true),
    openAiCompatible(
      "xai",
      Some("https://api.x.ai/v1"),
      keyRequired = true,
      quirks = Quirks.compatibleOpenAi.copy(reasoningEfforts = Some(Vector("low", "high"))),
      shape = Shape.Responses,
      notes = "Chat Completions is deprecated there"
    ),
    openAiCompatible(
      "gemini",
      Some("https://generativelanguage.googleapis.com/v1beta/openai/"),
      keyRequired = true,
      defaultModel = Some("gemini-flash-latest"),
      quirks =
        Quirks.compatibleOpenAi.copy(reasoningEfforts = Some(Vector("low", "medium", "high")))
    ),
    openAiCompatible(
      "openrouter",
      Some("https://openrouter.ai/api/v1"),
      keyRequired = true,
      notes = "a model is named `vendor/model`"
    ),
    openAiCompatible("huggingface", Some("https://router.huggingface.co/v1"), keyRequired = true),
    openAiCompatible(
      "bedrock",
      None,
      keyRequired = true,
      notes = "`https://bedrock-runtime.<region>.amazonaws.com/openai/v1`, with a Bedrock API key"
    ),
    openAiCompatible(
      "azure",
      None,
      keyRequired = true,
      quirks = Quirks.openAi,
      shape = Shape.Responses,
      notes = "`https://<resource>.openai.azure.com/openai/v1`, with an API key"
    )
  ).map(p => p.name -> p).toMap

  def named(name: String): Option[Preset] = all.get(name)

  /** The names, sorted, for a message that lists them. */
  def names: Vector[String] = all.keys.toVector.sorted
