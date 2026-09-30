package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.agent.judgment.{
  JudgedGuardrail,
  JudgmentFailed,
  JudgmentState,
  Judgments,
  NoJudgmentProvider
}
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

/**
 * Runs an interaction's guardrails, for every loop that has them.
 *
 * The request agent's loop and the autonomous agent's loop both check guardrails — on the way in
 * and on the way out — and they must not disagree about what a guardrail's answer means. This is
 * the one place that decides. It is also where a judged guardrail gets what the `Guardrail` trait
 * cannot give it: the service's judgment provider, somewhere to count the tokens it spends, and a
 * way to say it could not decide.
 */
private[ankka] object Guardrails:

  enum Direction:
    case Input, Output

  /** What the checks of one interaction have spent on judgments. Used from one thread. */
  final class Spent:
    var usage: TokenUsage = TokenUsage.zero

  /** A guardrail refused the text. */
  final case class Refused(guardrail: String, reason: String):
    def message: String = s"guardrail '$guardrail': $reason"

  /**
   * A guardrail could not decide — its provider failed, timed out or was never configured. Never a
   * refusal: a caller must be able to tell "not allowed" from "not checked", and may retry only the
   * second.
   */
  final class GuardrailCheckFailed(val guardrail: String, val cause: Throwable)
      extends RuntimeException(GuardrailCheckFailed.describe(guardrail, cause), cause):

    /** What a caller is told, and with which code. */
    def toCommandError: CommandError = cause match
      case _: NoJudgmentProvider           => CommandError(getMessage, ErrorCode.Internal)
      case f: JudgmentFailed if f.timedOut => CommandError(getMessage, ErrorCode.Timeout)
      case _                               => CommandError(getMessage, ErrorCode.Unavailable)

  object GuardrailCheckFailed:
    private def describe(guardrail: String, cause: Throwable): String = cause match
      case _: NoJudgmentProvider =>
        s"guardrail '$guardrail' has no judgment provider: give it one with provider(...), or " +
          "configure one with withJudgments(...) on the AgentRuntime"
      case other => s"guardrail '$guardrail' could not be checked: ${other.getMessage}"

  /**
   * The first refusal, in declaration order, or `None` when every guardrail allows the text.
   *
   * Lazy: a guardrail after one that refused is not run, so a free check declared first spares a
   * judgment. Throws [[GuardrailCheckFailed]] when a judged guardrail could not decide.
   */
  def check(
      guardrails: Vector[Guardrail],
      text: String,
      direction: Direction,
      judgments: Judgments,
      spent: Spent
  ): Option[Refused] =
    guardrails.iterator
      .map {
        case judged: JudgedGuardrail =>
          judge(judged, text, direction, judgments, spent).map(Refused(judged.name, _))
        case guard =>
          val verdict = direction match
            case Direction.Input  => guard.checkInput(text)
            case Direction.Output => guard.checkOutput(text)
          verdict.left.toOption.map(Refused(guard.name, _))
      }
      .collectFirst { case Some(refused) => refused }

  private def judge(
      guardrail: JudgedGuardrail,
      text: String,
      direction: Direction,
      judgments: Judgments,
      spent: Spent
  ): Option[String] =
    if !guardrail.hasRules then
      throw IllegalStateException(
        s"judged guardrail '${guardrail.name}' has no rules: add onInput(...) or onOutput(...)"
      )
    val rules = direction match
      case Direction.Input  => guardrail.input
      case Direction.Output => guardrail.output
    // Nothing to judge: a handler with no message, a reply with no text.
    if rules.isEmpty || text.isBlank then None
    else
      val judgment =
        try judgments.ask(guardrail.own, JudgmentState.Text(text), rules.map(_.question))
        catch
          case failure: (NoJudgmentProvider | JudgmentFailed) =>
            throw GuardrailCheckFailed(guardrail.name, failure)
      spent.usage = spent.usage + judgment.usage
      rules.find(_.isMetBy(judgment)).map(rule => s"question '${rule.question.id}'")
