package com.thinkmorestupidless.ankka.agent.providers

import java.net.URI
import scala.util.Try

/**
 * What is wrong with a model provider's configuration.
 *
 * @param setting
 *   the setting the problem is in (`PROTOCOL`, `URL`, …), when it is one: the sidecar names its
 *   variable, a Scala service the setting
 * @param fix
 *   the settings that would supply what is missing, for a problem of absence
 */
final case class ConfigProblem(
    name: String,
    message: String,
    setting: Option[String] = None,
    fix: Vector[String] = Vector.empty
):
  /** The problem with each setting named by `variable`, as the sidecar says it. */
  def describe(variable: String => String): String =
    setting match
      case Some(s) => s"${variable(s)}: $message"
      case None    =>
        val hint =
          if fix.isEmpty then "" else fix.map(variable).mkString("; set ", " or ", "")
        s"model provider '$name': $message$hint"

  /** The problem as a Scala service is told it. */
  def describe: String =
    setting match
      case Some(s) => s"model provider '$name': ${s.toLowerCase}: $message"
      case None    => s"model provider '$name': $message"

/**
 * A model provider as given: by hand, from a preset, or both.
 *
 * What the sidecar builds from its variables, and what a Scala service may build itself; one set of
 * rules for both, so a configuration is refused for the same reason, in the same words, wherever it
 * was written. An empty key is no key.
 */
final case class ProviderConfig(
    name: String,
    protocol: Option[String] = None,
    shape: Option[String] = None,
    url: Option[String] = None,
    model: Option[String] = None,
    key: Option[String] = None,
    preset: Option[String] = None
):

  /** The preset's values under the given ones, checked; or the first thing wrong. */
  def resolve: Either[ConfigProblem, ResolvedConfig] = resolve(None)

  /**
   * As `resolve`, where the platform may run a model server.
   *
   * @param modelsAddress
   *   the installation's model server, when the platform runs one here: what the preset
   *   `installation` is resolved against, and nothing else is
   */
  def resolve(modelsAddress: Option[String]): Either[ConfigProblem, ResolvedConfig] =
    if preset.contains(Presets.Installation) then installation(modelsAddress)
    else resolveGiven

  /**
   * An installation model names a model and nothing else: the platform gives the address, and the
   * service's certificate is the credential, so anything else beside it is a mistake.
   */
  private def installation(modelsAddress: Option[String]): Either[ConfigProblem, ResolvedConfig] =
    def refuse(value: Option[String], what: String, setting: String) =
      if value.exists(_.nonEmpty) then
        Left(ConfigProblem(name, s"an installation model takes no $what", Some(setting)))
      else Right(())
    for
      _      <- ModelProviders.validName(name).left.map(ConfigProblem(name, _))
      _      <- refuse(url, "address", "URL")
      _      <- refuse(key, "key", "KEY")
      _      <- refuse(protocol, "protocol", "PROTOCOL")
      _      <- refuse(shape, "shape", "SHAPE")
      chosen <- model
        .filter(_.nonEmpty)
        .toRight(ConfigProblem(name, "an installation model must be named", Some("MODEL")))
      address <- modelsAddress
        .filter(_.nonEmpty)
        .toRight(ConfigProblem(name, ProviderConfig.NoModelServer))
    yield ResolvedConfig(
      name,
      ModelProtocol.OpenAi,
      Shape.ChatCompletions,
      s"${address.stripSuffix("/")}/v1",
      chosen,
      None,
      Presets.all(Presets.Installation).quirks,
      installation = true
    )

  private def resolveGiven: Either[ConfigProblem, ResolvedConfig] =
    def problem(
        message: String,
        setting: Option[String] = None,
        fix: Vector[String] = Vector.empty
    ) =
      Left(ConfigProblem(name, message, setting, fix))
    for
      _      <- ModelProviders.validName(name).left.map(ConfigProblem(name, _))
      chosen <- preset match
        case None    => Right(None)
        case Some(p) =>
          Presets
            .named(p)
            .map(Some(_))
            .toRight(
              ConfigProblem(
                name,
                s"'$p' is not a preset; the presets are ${Presets.names.mkString(", ")}",
                Some("PRESET")
              )
            )
      givenProtocol <- protocol match
        case None    => Right(None)
        case Some(p) =>
          ModelProtocol
            .fromWire(p)
            .map(Some(_))
            .toRight(
              ConfigProblem(
                name,
                s"'$p' is not a model protocol; use anthropic or openai",
                Some("PROTOCOL")
              )
            )
      givenShape <- shape match
        case None    => Right(None)
        case Some(s) =>
          Shape
            .fromWire(s)
            .map(Some(_))
            .toRight(
              ConfigProblem(
                name,
                s"'$s' is not a shape; use responses or chat-completions",
                Some("SHAPE")
              )
            )
      _ <- url match
        case Some(u) if !ProviderConfig.isAddress(u) =>
          problem(s"'$u' is not an http or https address", Some("URL"))
        case _ => Right(())
      resolvedProtocol <- givenProtocol
        .orElse(chosen.map(_.protocol))
        .toRight(
          ConfigProblem(name, "a model protocol is missing", fix = Vector("PROTOCOL", "PRESET"))
        )
      _ <-
        if resolvedProtocol == ModelProtocol.Anthropic && givenShape.isDefined then
          problem("a shape applies to the openai protocol only", Some("SHAPE"))
        else Right(())
      address <- url
        .orElse(chosen.flatMap(_.address))
        .toRight(ConfigProblem(name, "an address is missing", fix = Vector("URL")))
      chosenModel <- model
        .orElse(chosen.flatMap(_.defaultModel))
        .toRight(ConfigProblem(name, "a model is missing", fix = Vector("MODEL")))
      givenKey = key.filter(_.nonEmpty)
      _ <- chosen match
        case Some(p) if p.keyRequired && givenKey.isEmpty =>
          problem(s"the preset '${p.name}' needs a key", fix = Vector("KEY"))
        case _ => Right(())
    yield
      // A preset's quirks hold for its own protocol; a protocol given by hand against it is a
      // different endpoint, whose quirks are a bare one's.
      val quirks = chosen
        .filter(_.protocol == resolvedProtocol)
        .map(_.quirks)
        .getOrElse(
          if resolvedProtocol == ModelProtocol.Anthropic then Quirks.compatibleAnthropic
          else Quirks.compatibleOpenAi
        )
      val resolvedShape =
        givenShape
          .orElse(chosen.filter(_.protocol == resolvedProtocol).map(_.shape))
          .getOrElse(Shape.ChatCompletions)
      ResolvedConfig(name, resolvedProtocol, resolvedShape, address, chosenModel, givenKey, quirks)

object ProviderConfig:

  /** The settings a provider is configured by, as the sidecar's variables end. */
  val Settings: Vector[String] = Vector("PROTOCOL", "URL", "MODEL", "KEY", "SHAPE", "PRESET")

  /** What a service naming an installation model is told where no model server runs. */
  val NoModelServer: String =
    "there is no installation model server here: this platform runs none, or the service runs " +
      "outside an installation. Point a model provider at a server of your own instead; see " +
      "docs.ankka.cloud/platform/installation-models"

  private[providers] def isAddress(value: String): Boolean =
    Try(URI.create(value)).toOption.exists { uri =>
      Option(uri.getScheme).exists(s => s == "http" || s == "https") && Option(uri.getHost).nonEmpty
    }

/** A configuration with nothing missing: what `ModelProviders.configure` builds a provider from. */
final case class ResolvedConfig(
    name: String,
    protocol: ModelProtocol,
    shape: Shape,
    url: String,
    model: String,
    key: Option[String],
    quirks: Quirks,
    /**
     * The installation's model server: reached with the service's certificate, admitting only the
     * server's, and asked what a model can do before the model is called.
     */
    installation: Boolean = false
)
