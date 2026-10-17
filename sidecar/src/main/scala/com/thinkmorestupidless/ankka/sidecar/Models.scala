package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.agent.openai.OpenAiProvider
import com.thinkmorestupidless.ankka.agent.providers.{ModelProviders, ProviderConfig}
import com.thinkmorestupidless.ankka.agent.{
  AnthropicProvider,
  ChatMessage,
  Json,
  MessageContent,
  ModelProvider,
  ModelRequest,
  ModelResponse,
  StopReason,
  TestModelProvider,
  TokenUsage,
  ToolCall
}

import java.nio.file.{Files, Path}
import scala.util.Try

/**
 * The models a remote agent may name, and the one it gets when it names none.
 *
 * A process never calls a model: the keys live here, in the sidecar's environment, and the loop
 * runs here. The configuration is by environment because a process has no builder to hand a
 * provider to. Each provider has the name the service chose:
 *
 *   - `ANTHROPIC_API_KEY` configures `anthropic` (its model `ANKKA_MODEL_NAME`), and
 *     `OPENAI_API_KEY` configures `openai`: each is that vendor's preset with a key;
 *   - `ANKKA_MODEL_<NAME>_{PROTOCOL,URL,MODEL,KEY,SHAPE,PRESET}` configures any provider by name,
 *     and overrides a shorthand's preset for the same name;
 *   - `ANKKA_MODEL_SCRIPT` configures `scripted`, a model answering from a script, for a test.
 *
 * The default is the only real provider when there is one, else the one `ANKKA_MODEL_DEFAULT`
 * names; with several and none named, a plan that names no model is refused with `noDefault`. Every
 * configuration that cannot work is refused when the sidecar starts, naming the variable.
 */
final case class Models(
    providers: Map[String, ModelProvider],
    default: Option[ModelProvider],
    noDefault: Option[String] = None
):
  def named(name: String): Option[ModelProvider] = providers.get(name)

  /** What a plan or definition that names no model is told when there is no default. */
  def withoutDefault: String =
    noDefault.getOrElse(
      "the sidecar has no model: set ANTHROPIC_API_KEY or OPENAI_API_KEY, configure one as " +
        "ANKKA_MODEL_<NAME>_*, or ANKKA_MODEL_SCRIPT for a test"
    )

object Models:

  val Anthropic = "anthropic"
  val OpenAi    = "openai"
  val Scripted  = "scripted"

  // Built from the prefix the platform routes, never written out as a list of whole names: the
  // platform's variables are declared once, in `PlatformVariables`.
  private val Prefix          = "ANKKA_MODEL_"
  private val ModelVariable   = Prefix + "NAME"
  private val ScriptVariable  = Prefix + "SCRIPT"
  private val DefaultVariable = Prefix + "DEFAULT"
  private val AnthropicKey    = "ANTHROPIC_API_KEY"
  private val OpenAiKey       = "OPENAI_API_KEY"

  val none: Models = Models(Map.empty, None)

  /** A single provider under `name`, also the default — what a test hands the sidecar. */
  def only(name: String, provider: ModelProvider): Models =
    Models(Map(name -> provider), Some(provider))

  private def refuse(message: String): Nothing = throw IllegalArgumentException(message)

  private def present(env: Map[String, String], name: String): Option[String] =
    env.get(name).filter(_.nonEmpty)

  /**
   * The family's variables, by provider name and setting; anything else under the prefix refused.
   */
  private def family(env: Map[String, String]): Map[String, Map[String, String]] =
    env.toVector
      .filter((k, _) =>
        k.startsWith(Prefix) && k != ModelVariable && k != ScriptVariable && k != DefaultVariable
      )
      .map { (variable, value) =>
        val rest    = variable.drop(Prefix.length)
        val cut     = rest.lastIndexOf('_')
        val setting = if cut < 0 then "" else rest.drop(cut + 1)
        if cut <= 0 || !ProviderConfig.Settings.contains(setting) then
          refuse(
            s"$variable: not a model setting; a provider's settings are " +
              s"${Prefix}<NAME>_{${ProviderConfig.Settings.mkString(",")}}"
          )
        val segment = rest.take(cut)
        val shown   = if segment.forall(c => !c.isLower) then segment.toLowerCase else segment
        ModelProviders.validName(shown).left.foreach(problem => refuse(s"$variable: $problem"))
        (shown, setting, value)
      }
      .groupBy(_._1)
      .view
      .mapValues(_.map((_, setting, value) => setting -> value).toMap)
      .toMap

  private def variableOf(name: String)(setting: String): String =
    s"$Prefix${name.toUpperCase}_$setting"

  private def configure(name: String, settings: Map[String, String]): ModelProvider =
    def setting(s: String) = settings.get(s).filter(_.nonEmpty)
    ProviderConfig(
      name,
      protocol = setting("PROTOCOL"),
      shape = setting("SHAPE"),
      url = setting("URL"),
      model = setting("MODEL"),
      key = setting("KEY"),
      preset = setting("PRESET")
    ).resolve match
      case Right(resolved) => ModelProviders.configure(resolved)
      case Left(problem)   => refuse(problem.describe(variableOf(name)))

  def fromEnv(env: Map[String, String] = sys.env): Models =
    val byName = family(env)

    // `ANKKA_MODEL_NAME` is the Claude model's shorthand: the same setting as the family's.
    (
      present(env, ModelVariable),
      byName.get(Anthropic).flatMap(_.get("MODEL")).filter(_.nonEmpty)
    ) match
      case (Some(a), Some(b)) if a != b =>
        refuse(
          s"$ModelVariable and ${variableOf(Anthropic)("MODEL")} are one setting and differ: '$a' and '$b'"
        )
      case _ => ()

    // A vendor's key is that vendor's preset with a key; the family's values for the same name are
    // laid over it. With nothing laid over it, it is built exactly as before providers were named.
    val anthropic = present(env, AnthropicKey).map { key =>
      val model = present(env, ModelVariable).getOrElse(AnthropicProvider.DefaultModel)
      Anthropic -> byName
        .get(Anthropic)
        .fold(AnthropicProvider.fromEnv(env, model, AnthropicProvider.Defaults())) { over =>
          configure(Anthropic, Map("PRESET" -> Anthropic, "KEY" -> key, "MODEL" -> model) ++ over)
        }
    }
    val openai = present(env, OpenAiKey).map { key =>
      OpenAi -> byName
        .get(OpenAi)
        .fold(OpenAiProvider.fromEnv(env, OpenAiProvider.DefaultModel, OpenAiProvider.Defaults())) {
          over =>
            configure(OpenAi, Map("PRESET" -> OpenAi, "KEY" -> key) ++ over)
        }
    }
    val shorthands = (anthropic ++ openai).toMap
    val named = byName.collect {
      case (name, settings) if !shorthands.contains(name) => name -> configure(name, settings)
    }
    val scripted = present(env, ScriptVariable).map { script =>
      Scripted -> scriptedFrom(script).fold(
        problem => refuse(s"$ScriptVariable: $problem"),
        identity
      )
    }

    val real      = shorthands ++ named
    val providers = real ++ scripted
    present(env, DefaultVariable) match
      case Some(name) =>
        providers.get(name) match
          case Some(chosen) => Models(providers, Some(chosen))
          case None =>
            refuse(
              s"$DefaultVariable: '$name' is not a configured model provider; the providers are " +
                (if providers.isEmpty then "none"
                 else providers.keys.toVector.sorted.mkString(", "))
            )
      case None =>
        // A real model wins over a script: a script beside a key is a test's leftover, not a choice.
        if real.size == 1 then Models(providers, real.values.headOption)
        else if real.isEmpty then Models(providers, scripted.map(_._2))
        else
          val names = real.keys.toVector.sorted
          Models(
            providers,
            None,
            Some(
              s"the sidecar has ${names.size} models and no default: set $DefaultVariable to one of " +
                names.mkString(", ")
            )
          )

  /**
   * A scripted model from JSON — the text itself, or the path of a file holding it. An array of
   * turns: `{"text": "..."}`, `{"tool": "name", "arguments": {...}}` (several tools:
   * `{"tools": [...]}`), `{"refusal": "..."}`. A plain turn is consumed in order. A turn with
   * `"when": "<substring of the user's message>"` or `"when_tool_result": "<substring of the latest
   * tool result>"` is a standing rule instead, answered whenever it matches once the ordered turns
   * have run out — which is what lets a script survive the sidecar being replaced mid-task.
   */
  def scriptedFrom(script: String): Either[String, TestModelProvider] =
    val text =
      Try(Path.of(script)).toOption.filter(p => Files.isRegularFile(p)) match
        case Some(path) => Files.readString(path)
        case None       => script
    Json.parse(text).flatMap {
      case Json.Arr(turns) =>
        val model = TestModelProvider()
        turns.zipWithIndex
          .foldLeft[Either[String, Unit]](Right(())) { case (acc, (turn, i)) =>
            acc.flatMap(_ => scriptTurn(model, turn).left.map(p => s"turn $i: $p"))
          }
          .map(_ => model)
      case _ => Left("expected a JSON array of turns")
    }

  private def scriptTurn(model: TestModelProvider, turn: Json): Either[String, Unit] =
    responseOf(turn).map { response =>
      (turn("when").flatMap(_.asString), turn("when_tool_result").flatMap(_.asString)) match
        case (Some(substring), _) =>
          model.whenRequest(r => latestUser(r).exists(_.contains(substring)))(response): Unit
        case (None, Some(substring)) => model.whenToolResult(substring)(response): Unit
        case (None, None)            => model.expect(response): Unit
    }

  private def responseOf(turn: Json): Either[String, ModelResponse] =
    def call(t: Json, i: Int): Either[String, ToolCall] =
      t("tool").flatMap(_.asString) match
        case None => Left("a tool call needs a \"tool\" name")
        case Some(name) =>
          Right(
            ToolCall(
              t("id").flatMap(_.asString).getOrElse(s"call-$i"),
              name,
              t("arguments").getOrElse(Json.obj())
            )
          )
    def calls(cs: Vector[ToolCall]) =
      ModelResponse("", cs, StopReason.ToolUse, TokenUsage.zero, None)
    turn("tools").flatMap(_.asArray) match
      case Some(list) =>
        list.zipWithIndex
          .foldLeft[Either[String, Vector[ToolCall]]](Right(Vector.empty)) { case (acc, (c, i)) =>
            acc.flatMap(v => call(c, i).map(v :+ _))
          }
          .map(calls)
      case None if turn("tool").isDefined => call(turn, 0).map(c => calls(Vector(c)))
      case None =>
        (turn("text").flatMap(_.asString), turn("refusal").flatMap(_.asString)) match
          case (Some(text), _) => Right(response(text))
          case (None, Some(why)) =>
            Right(ModelResponse("", Vector.empty, StopReason.Refusal, TokenUsage.zero, Some(why)))
          case (None, None) => Left("a turn is a text, a tool call, or a refusal")

  private def latestUser(request: ModelRequest): Option[String] =
    request.messages.reverseIterator
      .collectFirst { case u: ChatMessage.User => u }
      .map(_.content.collect { case MessageContent.Text(t) => t }.mkString("\n"))

  /** A response the scripted model can be handed directly, for tests that build their own. */
  def response(text: String): ModelResponse =
    ModelResponse(text, Vector.empty, StopReason.EndTurn, TokenUsage.zero, None)
