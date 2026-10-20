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
import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.runtime.{ClusterConfig, RotatingTls}
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

  /**
   * The provider a resolved configuration describes. An installation model is reached with the
   * service's certificate, asked for only when one is configured.
   *
   * @throws IllegalArgumentException
   *   for an installation model where the service has no certificate
   */
  def configure(
      config: ResolvedConfig,
      serviceTls: => Either[String, RotatingTls] = Left("no certificate was given")
  ): ModelProvider =
    if config.installation then
      serviceTls.fold(
        why => throw IllegalArgumentException(s"model provider '${config.name}': $why"),
        InstallationModels.provider(config, _)
      )
    else configureGiven(config)

  /**
   * A model the installation's model server serves, for a Scala service on the platform:
   * `ModelProviders.installation("qwen3:0.6b")`. The address is the platform's and the credential
   * the service's own certificate, so nothing else is given.
   *
   * @throws IllegalArgumentException
   *   where the platform runs no model server, or the service has no certificate
   */
  def installation(
      model: String,
      name: String = Presets.Installation,
      env: Map[String, String] = sys.env
  ): ModelProvider =
    ProviderConfig(name, model = Some(model), preset = Some(Presets.Installation))
      .resolve(env.get(PlatformVariables.ModelsAddress)) match
      case Right(resolved) =>
        configure(resolved, InstallationModels.serviceTls(ClusterConfig.load(env)))
      case Left(problem) => throw IllegalArgumentException(problem.describe)

  private def configureGiven(config: ResolvedConfig): ModelProvider =
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
