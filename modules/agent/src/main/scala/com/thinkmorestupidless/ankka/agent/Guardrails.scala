package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.agent.judgment.Judgments

/**
 * Runs an interaction's guardrails, for every loop that has them.
 *
 * The request agent's loop and the autonomous agent's loop both check guardrails — on the way in
 * and on the way out — and they must not disagree about what a guardrail's answer means. This is
 * the one place that decides.
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
   * refusal: a caller must be able to tell "not allowed" from "not checked".
   */
  final class GuardrailCheckFailed(val guardrail: String, val cause: Throwable)
      extends RuntimeException(s"guardrail '$guardrail' could not be checked", cause)

  /**
   * The first refusal, in declaration order, or `None` when every guardrail allows the text. Throws
   * [[GuardrailCheckFailed]] when a guardrail could not decide.
   */
  def check(
      guardrails: Vector[Guardrail],
      text: String,
      direction: Direction,
      judgments: Judgments,
      spent: Spent
  ): Option[Refused] =
    val _ = (judgments, spent)
    guardrails.iterator
      .map { guard =>
        val verdict = direction match
          case Direction.Input  => guard.checkInput(text)
          case Direction.Output => guard.checkOutput(text)
        guard.name -> verdict
      }
      .collectFirst { case (name, Left(reason)) => Refused(name, reason) }
