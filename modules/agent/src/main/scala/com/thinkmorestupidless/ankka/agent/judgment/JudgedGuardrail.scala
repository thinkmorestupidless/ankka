package com.thinkmorestupidless.ankka.agent.judgment

import com.thinkmorestupidless.ankka.agent.{Guardrail, Guardrails}

/**
 * One rule of a judged guardrail: a question, and when its answer means refuse.
 *
 * Built with [[Refuse]], which checks the rule against its question — a threshold off the scale or
 * an option the question does not offer is a mistake found where the guardrail is declared.
 */
final class Refusal[A] private[judgment] (val question: Question[A], refuses: A => Boolean):
  private[ankka] def isMetBy(judgment: Judgment): Boolean = refuses(judgment(question))
  override def toString: String                           = s"Refusal(${question.id})"

object Refuse:

  /** Refuse when the probability of yes is at least `atLeast`. */
  def ifYes(question: YesNoQuestion, atLeast: Double): Refusal[YesNoAnswer] =
    if !Question.inRange(atLeast) then
      Question.refuse(question.id, s"a threshold of $atLeast is outside 0 to 1")
    Refusal(question, _.probability >= atLeast)

  /**
   * Refuse when the chosen option is one of `options`, and the model is at least `minConfidence`
   * sure of it.
   */
  def ifChosen[T](question: ChoiceQuestion[T], minConfidence: Double = 0.0)(
      options: T*
  ): Refusal[ChoiceAnswer[T]] =
    if options.isEmpty then Question.refuse(question.id, "name at least one option to refuse")
    options.find(o => question.optionFor(o).isEmpty).foreach { o =>
      Question.refuse(question.id, s"$o is not one of its options")
    }
    if !Question.inRange(minConfidence) then
      Question.refuse(question.id, s"a confidence of $minConfidence is outside 0 to 1")
    val refused = options.toSet
    Refusal(question, a => refused.contains(a.choice) && a.confidence >= minConfidence)

  /** Refuse when the score is at least `atLeast`, which may lie between two levels. */
  def ifScore(question: ScoreQuestion, atLeast: Double): Refusal[ScoreAnswer] =
    if atLeast < 0 || atLeast > question.top then
      Question.refuse(question.id, s"a threshold of $atLeast is off the scale 0 to ${question.top}")
    Refusal(question, _.score >= atLeast)

  /** Refuse when `refuse` says so of the typed answer. */
  def when[A](question: Question[A])(refuse: A => Boolean): Refusal[A] =
    Refusal(question, refuse)

/**
 * A guardrail that asks rather than matches: questions about the text going into or coming out of a
 * model, each with a rule that decides whether to refuse.
 *
 * {{{
 * val safety = Guardrail
 *   .judged("safety")
 *   .onInput(Refuse.ifYes(Safety.overridesInstructions, atLeast = 0.7))
 *   .onOutput(Refuse.ifYes(Safety.givesMedicalAdvice, atLeast = 0.7))
 * }}}
 *
 * It goes in the same list as the deterministic guardrails and is run in the same order; put the
 * free checks first. One judgment is made per text checked, with all of that direction's questions,
 * and the first rule met in declaration order is the one a refusal names. The refusal names the
 * question and not the score: a judged guardrail reads text its author may be tuning against it.
 *
 * When the check cannot be made — the provider failed, timed out, or there is none — the
 * interaction does not proceed, and the caller is told the check could not be made rather than that
 * it was refused.
 */
final class JudgedGuardrail private[ankka] (
    val name: String,
    private[ankka] val input: Vector[Refusal[?]],
    private[ankka] val output: Vector[Refusal[?]],
    private[ankka] val own: Option[JudgmentProvider]
) extends Guardrail:

  /** Rules for the text going into the model: a request's message, a task's instructions. */
  def onInput(rules: Refusal[?]*): JudgedGuardrail =
    JudgedGuardrail(name, JudgedGuardrail.distinct(name, input ++ rules), output, own)

  /** Rules for the text coming out: a model's reply, a task's completed result. */
  def onOutput(rules: Refusal[?]*): JudgedGuardrail =
    JudgedGuardrail(name, input, JudgedGuardrail.distinct(name, output ++ rules), own)

  /** Asks this provider rather than the service's default. */
  def provider(provider: JudgmentProvider): JudgedGuardrail =
    JudgedGuardrail(name, input, output, Some(provider))

  private[ankka] def hasRules: Boolean       = input.nonEmpty || output.nonEmpty
  private[ankka] def hasOwnProvider: Boolean = own.isDefined

  /**
   * Checks directly, outside an agent. Works when the guardrail has a provider of its own; the
   * agent runtime runs it otherwise, with the service's.
   */
  override def checkInput(text: String): Either[String, Unit] =
    direct(text, Guardrails.Direction.Input)

  override def checkOutput(text: String): Either[String, Unit] =
    direct(text, Guardrails.Direction.Output)

  private def direct(text: String, direction: Guardrails.Direction): Either[String, Unit] =
    own match
      case None =>
        throw IllegalStateException(
          s"judged guardrail '$name' has no provider of its own; it is run by the agent runtime"
        )
      case Some(provider) =>
        Guardrails
          .check(
            Vector(this),
            text,
            direction,
            Judgments(Some(provider), Judgments.DefaultTimeout),
            Guardrails.Spent()
          )
          .map(_.reason)
          .toLeft(())

  override def toString: String = s"JudgedGuardrail($name)"

object JudgedGuardrail:
  private def distinct(name: String, rules: Vector[Refusal[?]]): Vector[Refusal[?]] =
    rules.groupBy(_.question.id).collectFirst {
      case (id, rs) if rs.sizeIs > 1 =>
        throw IllegalArgumentException(
          s"judged guardrail '$name' asks question '$id' twice in one direction"
        )
    }: Unit
    rules
