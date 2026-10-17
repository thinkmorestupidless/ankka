# Contract: the Scala API

Everything a Scala service sees, in `com.thinkmorestupidless.ankka.agent`. Existing signatures
keep working; every addition has a default.

## Providers

```scala
object AnthropicProvider:
  val DefaultModel: String = "claude-opus-5"
  final case class Defaults(maxTokens: Long = 16000L, thinking: Boolean = true, effort: Option[Effort] = None)

  def fromEnv(modelName: String = DefaultModel, defaults: Defaults = Defaults()): AnthropicProvider   // signature unchanged; no key: unavailable, not a throw (R15)
  def fromEnv(env: Map[String, String], modelName: String, defaults: Defaults): AnthropicProvider  // the same from a given map, for tests
  def withApiKey(apiKey: String, modelName: String = DefaultModel, defaults: Defaults = Defaults()): AnthropicProvider   // unchanged
  def apply(client: AnthropicClient, modelName: String = DefaultModel, defaults: Defaults = Defaults(),
            name: String = "anthropic"): AnthropicProvider                                              // name added
  /** Anthropic's protocol at any address, under a name the service chose; no key sends a placeholder. */
  def at(name: String, address: String, modelName: String, key: Option[String] = None,
         defaults: Defaults = Defaults(), quirks: Quirks = Quirks.compatibleAnthropic): AnthropicProvider

object OpenAiProvider:
  val DefaultModel: String = "gpt-6.1-sol"
  enum Shape: case Responses, ChatCompletions
  final case class Defaults(maxTokens: Long = 16000L, thinking: Boolean = true, effort: Option[Effort] = None)

  /** OPENAI_API_KEY from the environment; OpenAI's own API in the Responses shape; named `openai`. */
  def fromEnv(modelName: String = DefaultModel, defaults: Defaults = Defaults()): OpenAiProvider
  def fromEnv(env: Map[String, String], modelName: String, defaults: Defaults): OpenAiProvider
  def withApiKey(apiKey: String, modelName: String = DefaultModel, defaults: Defaults = Defaults()): OpenAiProvider
  def apply(client: OpenAIClient, modelName: String = DefaultModel, shape: Shape = Shape.Responses,
            defaults: Defaults = Defaults(), name: String = "openai"): OpenAiProvider
  /** OpenAI's protocol at any address; Chat Completions unless told otherwise. */
  def at(name: String, address: String, modelName: String, key: Option[String] = None,
         shape: Shape = Shape.ChatCompletions, defaults: Defaults = Defaults(),
         quirks: Quirks = Quirks.compatibleOpenAi): OpenAiProvider
```

Both classes: `def name: String` returns the name given. A reserved or malformed `name` throws
`IllegalArgumentException` from the builder, with the message in contracts/variables.md.

`fromEnv` with the key absent (or empty) no longer throws: it returns an **unavailable provider**
under the same name, whose `complete` and `stream` fail with
`ModelCallFailed(name, "OPENAI_API_KEY is not set")` (`ANTHROPIC_API_KEY` likewise), and logs one
warning at construction. The service starts; the loop answers the agent that names it
`Unavailable`; everything else works (research R15). The shopping cart sample's own guard becomes
unnecessary and is simplified.

`TestModelProvider` gains `name: String = "test"` beside `modelName`, so a test can script a
provider called `openai`; the autonomous loop recognises a scripted model by type, never by name.
A `ModelCallFailed` an adapter raises is built from the provider's `name`, the HTTP status and a
short body summary, never from the SDK exception's class or text.

## The factory

```scala
package com.thinkmorestupidless.ankka.agent.providers

enum ModelProtocol: case Anthropic, OpenAi

final case class ProviderConfig(name: String, protocol: Option[ModelProtocol] = None,
  shape: Option[OpenAiProvider.Shape] = None, url: Option[String] = None, model: Option[String] = None,
  key: Option[String] = None, preset: Option[String] = None):
  def resolve: Either[String, ResolvedConfig]

final case class ResolvedConfig(name: String, protocol: ModelProtocol, shape: OpenAiProvider.Shape,
  url: String, model: String, key: Option[String], quirks: Quirks)

object ModelProviders:
  val NoKey: String = "none"                      // the placeholder bearer token a keyless address is sent
  val Reserved: Set[String] = Set("scripted", "default", "name", "script")
  def validName(name: String): Either[String, String]
  def configure(config: ResolvedConfig): ModelProvider
  /** The four values, or a preset, in one call: `ModelProviders.named("local", preset = "ollama", model = "qwen3:8b")`. */
  def named(name: String, protocol: Option[ModelProtocol] = None, url: Option[String] = None,
            model: Option[String] = None, key: Option[String] = None, preset: Option[String] = None,
            shape: Option[OpenAiProvider.Shape] = None): ModelProvider   // throws IllegalArgumentException on a problem

object Presets:
  val all: Map[String, Preset]
  def named(name: String): Option[Preset]
```

## What does not change

- `AgentRuntime.withDefaultModel(provider, modelTimeout)`, `effects.model(provider)`,
  `AutonomousAgentDefinition.model(provider)`, `BlueprintRegistry.models(name -> provider)` and
  `Worker.model(name)`: a provider is a provider, whichever vendor.
- `ModelProvider`'s two methods.
- `ModelRequest`, `ModelSettings`, `TokenUsage`, `StopReason`, `ToolSpec`.

## What is added above the seam

```scala
final case class ProviderState(provider: String, value: String)

final case class ModelResponse(..., providerState: Option[ProviderState] = None)
object ChatMessage:
  final case class Assistant(text: String, toolCalls: Vector[ToolCall] = Vector.empty,
                             providerState: Option[ProviderState] = None) extends ChatMessage

object SessionMessage:
  final case class AiMessage(timestamp: Long, text: String, agentId: String,
                             toolCalls: Vector[RecordedToolCall] = Vector.empty,
                             provider: Option[String] = None, model: Option[String] = None)

final case class SessionHistory(..., usageByProvider: Map[String, TokenUsage] = Map.empty)
```

`AgentRuntime.start` refuses (throws, naming it) a default provider whose name fails
`ModelProviders.validName`.
