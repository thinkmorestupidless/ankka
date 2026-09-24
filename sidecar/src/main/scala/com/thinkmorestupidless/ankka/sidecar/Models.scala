package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.agent.{
  AnthropicProvider,
  Json,
  ModelProvider,
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
 * A process never calls a model: the key lives here, in the sidecar's environment, and the loop
 * runs here. The configuration is by environment because a process has no builder to hand a
 * provider to — `ANTHROPIC_API_KEY` configures `anthropic`, and `ANKKA_MODEL_SCRIPT` a `scripted`
 * model that answers from a script, which is what a test sets on the sidecar.
 */
final case class Models(providers: Map[String, ModelProvider], default: Option[ModelProvider]):
  def named(name: String): Option[ModelProvider] = providers.get(name)

object Models:

  val Anthropic = "anthropic"
  val Scripted  = "scripted"

  val none: Models = Models(Map.empty, None)

  /** A single provider under `name`, also the default — what a test hands the sidecar. */
  def only(name: String, provider: ModelProvider): Models =
    Models(Map(name -> provider), Some(provider))

  def fromEnv(env: Map[String, String] = sys.env): Models =
    val anthropic = env.get("ANTHROPIC_API_KEY").filter(_.nonEmpty).map { _ =>
      Anthropic -> AnthropicProvider.fromEnv(
        env.getOrElse("ANKKA_MODEL_NAME", AnthropicProvider.DefaultModel)
      )
    }
    val scripted = env.get("ANKKA_MODEL_SCRIPT").filter(_.nonEmpty).map { script =>
      Scripted -> scriptedFrom(script).fold(
        problem => throw IllegalArgumentException(s"ANKKA_MODEL_SCRIPT: $problem"),
        identity
      )
    }
    val providers = (anthropic ++ scripted).toMap
    // A real model wins over a script: a script beside a key is a test's leftover, not a choice.
    Models(providers, anthropic.map(_._2).orElse(scripted.map(_._2)))

  /**
   * A scripted model from JSON — the text itself, or the path of a file holding it. An array of
   * turns, consumed in order: `{"text": "..."}`, `{"tool": "name", "arguments": {...}}` (several
   * tools: `{"tools": [...]}`), `{"refusal": "..."}`; and standing rules used once the script runs
   * out, `{"when": "<substring of the user's message>", "text": "..."}`.
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
    (turn("when").flatMap(_.asString), turn("text").flatMap(_.asString)) match
      case (Some(substring), Some(text)) =>
        model.whenUserSays(substring)(text): Unit
        Right(())
      case (Some(_), None) => Left("a \"when\" rule needs a \"text\"")
      case (None, _) =>
        turn("tools").flatMap(_.asArray) match
          case Some(calls) =>
            calls.zipWithIndex
              .foldLeft[Either[String, Vector[ToolCall]]](Right(Vector.empty)) {
                case (acc, (c, i)) => acc.flatMap(v => call(c, i).map(v :+ _))
              }
              .map(cs => model.expectParallelToolCalls(cs*): Unit)
          case None if turn("tool").isDefined =>
            call(turn, 0).map(c => model.expectParallelToolCalls(c): Unit)
          case None =>
            (turn("text").flatMap(_.asString), turn("refusal").flatMap(_.asString)) match
              case (Some(text), _)   => Right(model.expectText(text): Unit)
              case (None, Some(why)) => Right(model.expectRefusal(why): Unit)
              case (None, None)      => Left("a turn is a text, a tool call, or a refusal")

  /** A response the scripted model can be handed directly, for tests that build their own. */
  def response(text: String): ModelResponse =
    ModelResponse(text, Vector.empty, StopReason.EndTurn, TokenUsage.zero, None)
