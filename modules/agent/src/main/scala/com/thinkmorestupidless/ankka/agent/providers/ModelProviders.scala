package com.thinkmorestupidless.ankka.agent.providers

import com.thinkmorestupidless.ankka.agent.{
  AnthropicProvider,
  ModelCallFailed,
  ModelChunk,
  ModelProvider,
  ModelRequest,
  ModelResponse
}
import com.thinkmorestupidless.ankka.agent.openai.OpenAiProvider
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.Future

/** Builds a model provider from a name and what it is configured by. */
object ModelProviders:

  /**
   * The bearer token sent to an address that needs no key. Both vendors' SDKs refuse to build a
   * client with no credential at all, and a local server ignores the header.
   */
  val NoKey: String = "none"

  /** Names a provider may not have: the sidecar's script, the default, and its own variables. */
  val Reserved: Set[String] = Set("scripted", "default", "name", "script")

  private val NamePattern = "^[a-z][a-z0-9]*$".r

  /**
   * A name a provider may have: lowercase letters and digits, starting with a letter, so that the
   * name upper-cased is its segment of a variable with nothing mapped.
   */
  def validName(name: String): Either[String, String] =
    if Reserved.contains(name) then
      Left(
        s"'$name' is reserved; a model provider may not be named scripted, default, name or script"
      )
    else if NamePattern.matches(name) then Right(name)
    else
      Left(
        s"'$name' is not a name a model provider may have: lowercase letters and digits, " +
          "starting with a letter"
      )

  def configure(config: ResolvedConfig): ModelProvider =
    config.protocol match
      case ModelProtocol.Anthropic =>
        AnthropicProvider.at(
          config.name,
          config.url,
          config.model,
          config.key,
          quirks = config.quirks
        )
      case ModelProtocol.OpenAi =>
        OpenAiProvider.at(
          config.name,
          config.url,
          config.model,
          config.key,
          shape = config.shape,
          quirks = config.quirks
        )

  /**
   * A provider from the four values, or a preset, in one call:
   * `ModelProviders.named("local", preset = Some("ollama"), model = Some("qwen3:8b"))`.
   *
   * @throws IllegalArgumentException
   *   naming what is wrong with the configuration
   */
  def named(
      name: String,
      protocol: Option[String] = None,
      url: Option[String] = None,
      model: Option[String] = None,
      key: Option[String] = None,
      preset: Option[String] = None,
      shape: Option[String] = None
  ): ModelProvider =
    ProviderConfig(name, protocol, shape, url, model, key, preset).resolve match
      case Right(resolved) => configure(resolved)
      case Left(problem)   => throw IllegalArgumentException(problem.describe)

  /**
   * A provider that exists under its name and fails every call with `reason`.
   *
   * What a provider built from the environment is when its key is not there: the service starts,
   * and only the work that names this provider is refused.
   */
  def unavailable(providerName: String, model: String, reason: String): ModelProvider =
    new ModelProvider:
      def name: String      = providerName
      def modelName: String = model
      def complete(request: ModelRequest): Future[ModelResponse] =
        Future.failed(ModelCallFailed(providerName, reason))
      override def stream(request: ModelRequest): Source[ModelChunk, NotUsed] =
        Source.failed(ModelCallFailed(providerName, reason))
