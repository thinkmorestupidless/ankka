package com.thinkmorestupidless.ankka.agent.judgment

/**
 * Something asked of a state, declared once and used both to ask and to read the answer.
 *
 * `A` is the type of the answer read back from a judgment. The id is the question's wire name: it
 * is what the provider is sent and what a stored judgment is keyed by, so it is declared rather
 * than derived from a Scala identifier — renaming a `val` must not change what a stored judgment
 * means.
 *
 * Every question is checked where it is built. A question declared as a `val` on a companion is
 * built when the companion is, which for a registered agent is at startup, so a malformed question
 * fails the service rather than its first request.
 */
sealed trait Question[A]:
  def id: String
  def instructions: String

  /** The typed answer from what was stored, or why it cannot be read. */
  private[judgment] def read(stored: Judgment.Stored): Either[String, A]

  /** Whether a provider's answer fits this question; `None` when it does. */
  private[judgment] def problemWith(stored: Judgment.Stored): Option[String]

object Question:

  /** The provider's limit on a choice's options. */
  val MaxOptions: Int = 255

  /** The provider's limit on a score's levels. */
  val MaxLevels: Int = 10

  /**
   * A choice among options that are values of the developer's own type.
   *
   * Each option is `value -> (key, description)`. The key is the option's wire name, and it is also
   * what the model reads, so make it a meaningful word. A question need not offer every value of
   * its type; one that is not offered is never chosen.
   *
   * {{{
   * val route = Question.choice[Team]("route", "Which team should handle this ticket?")(
   *   Team.Billing   -> ("billing", "Payments, invoicing, refunds"),
   *   Team.Technical -> ("technical", "Bugs, outages, integrations")
   * )
   * }}}
   */
  def choice[T](id: String, instructions: String)(
      options: (T, (String, String))*
  ): ChoiceQuestion[T] =
    ChoiceQuestion(
      id,
      instructions,
      options.toVector.map { case (value, (key, description)) =>
        ChoiceOption(value, key, description)
      }
    )

  /** A choice among plain keys, each `key -> description`; the answer reads back as the key. */
  def choiceByKey(id: String, instructions: String)(
      options: (String, String)*
  ): ChoiceQuestion[String] =
    ChoiceQuestion(
      id,
      instructions,
      options.toVector.map((key, description) => ChoiceOption(key, key, description))
    )

  /** A score over two to ten ordered levels, the first of which is level 0. */
  def score(id: String, instructions: String)(levels: String*): ScoreQuestion =
    ScoreQuestion(id, instructions, levels.toVector)

  /** A yes or no, answered as the probability that the answer is yes. */
  def yesNo(id: String, instructions: String): YesNoQuestion =
    YesNoQuestion(id, instructions, None)

  private[judgment] def refuse(id: String, problem: String): Nothing =
    throw IllegalArgumentException(s"question '$id': $problem")

  private[judgment] def checkCommon(id: String, instructions: String): Unit =
    if id.trim.isEmpty then refuse(id, "an id is required")
    if instructions.trim.isEmpty then refuse(id, "instructions are required")

  private[judgment] def inRange(value: Double): Boolean = value >= 0.0 && value <= 1.0

  private[judgment] def kindOf(stored: Judgment.Stored): String = stored match
    case _: Judgment.Stored.Choice => "choice"
    case _: Judgment.Stored.Score  => "score"
    case _: Judgment.Stored.YesNo  => "yes/no"

/** One option of a choice: the developer's value, its wire key, and what the model reads. */
final case class ChoiceOption[T](value: T, key: String, description: String)

final class ChoiceQuestion[T] private[judgment] (
    val id: String,
    val instructions: String,
    val options: Vector[ChoiceOption[T]]
) extends Question[ChoiceAnswer[T]]:

  Question.checkCommon(id, instructions)
  if options.sizeIs < 2 then Question.refuse(id, "a choice needs at least two options")
  if options.sizeIs > Question.MaxOptions then
    Question.refuse(id, s"a choice may offer at most ${Question.MaxOptions} options")
  options.foreach { o =>
    if o.key.trim.isEmpty then Question.refuse(id, "every option needs a key")
    if o.description.trim.isEmpty then Question.refuse(id, s"option '${o.key}' needs a description")
  }
  options.groupBy(_.key).collectFirst {
    case (key, os) if os.sizeIs > 1 => Question.refuse(id, s"option key '$key' is used twice")
  }: Unit
  options.groupBy(_.value).collectFirst {
    case (value, os) if os.sizeIs > 1 =>
      Question.refuse(id, s"the value $value is offered twice, as ${os.map(_.key).mkString(", ")}")
  }: Unit

  private val byKey = options.map(o => o.key -> o).toMap

  /** The option with this wire key, if the question offers one. */
  def option(key: String): Option[ChoiceOption[T]] = byKey.get(key)

  /** The option for this value, if the question offers one. */
  def optionFor(value: T): Option[ChoiceOption[T]] = options.find(_.value == value)

  private[judgment] def read(stored: Judgment.Stored): Either[String, ChoiceAnswer[T]] =
    stored match
      case Judgment.Stored.Choice(key, probabilities, confidence) =>
        byKey.get(key) match
          case None => Left(s"question '$id' does not offer the option '$key' its answer names")
          case Some(chosen) =>
            Right(
              ChoiceAnswer(
                chosen.value,
                options.map(o => o.value -> probabilities.getOrElse(o.key, 0.0)).toMap,
                confidence
              )
            )
      case other =>
        Left(s"question '$id' is a choice, but its answer is a ${Question.kindOf(other)}")

  private[judgment] def problemWith(stored: Judgment.Stored): Option[String] = stored match
    case Judgment.Stored.Choice(key, probabilities, confidence) =>
      if !byKey.contains(key) then Some(s"question '$id': the answer chose '$key', not an option")
      else
        probabilities.keys.find(k => !byKey.contains(k)) match
          case Some(k) =>
            Some(s"question '$id': the answer gives a probability for '$k', not an option")
          case None =>
            options.find(o => !probabilities.contains(o.key)) match
              case Some(o) =>
                Some(s"question '$id': the answer gives no probability for '${o.key}'")
              case None =>
                if !probabilities.values.forall(Question.inRange) then
                  Some(s"question '$id': a probability is outside 0 to 1")
                else if !Question.inRange(confidence) then
                  Some(s"question '$id': the confidence is outside 0 to 1")
                else None
    case other =>
      Some(s"question '$id' is a choice, but its answer is a ${Question.kindOf(other)}")

  override def toString: String = s"ChoiceQuestion($id)"

final class ScoreQuestion private[judgment] (
    val id: String,
    val instructions: String,
    val levels: Vector[String]
) extends Question[ScoreAnswer]:

  Question.checkCommon(id, instructions)
  if levels.sizeIs < 2 then Question.refuse(id, "a score needs at least two levels")
  if levels.sizeIs > Question.MaxLevels then
    Question.refuse(id, s"a score may have at most ${Question.MaxLevels} levels")
  levels.zipWithIndex.foreach { (level, i) =>
    if level.trim.isEmpty then Question.refuse(id, s"level $i needs a description")
  }

  /** The highest level, counted from 0. */
  def top: Int = levels.size - 1

  private[judgment] def read(stored: Judgment.Stored): Either[String, ScoreAnswer] =
    stored match
      case Judgment.Stored.Score(score, probabilities, confidence) =>
        Right(ScoreAnswer(score, probabilities, confidence))
      case other =>
        Left(s"question '$id' is a score, but its answer is a ${Question.kindOf(other)}")

  private[judgment] def problemWith(stored: Judgment.Stored): Option[String] = stored match
    case Judgment.Stored.Score(score, probabilities, confidence) =>
      if probabilities.sizeIs != levels.size then
        Some(
          s"question '$id': the answer gives ${probabilities.size} probabilities for ${levels.size} levels"
        )
      else if !probabilities.forall(Question.inRange) then
        Some(s"question '$id': a probability is outside 0 to 1")
      else if score < -ScoreQuestion.Tolerance || score > top + ScoreQuestion.Tolerance then
        Some(s"question '$id': the score $score is off the scale 0 to $top")
      else if !Question.inRange(confidence) then
        Some(s"question '$id': the confidence is outside 0 to 1")
      else None
    case other =>
      Some(s"question '$id' is a score, but its answer is a ${Question.kindOf(other)}")

  override def toString: String = s"ScoreQuestion($id)"

object ScoreQuestion:
  private val Tolerance = 1e-6

final class YesNoQuestion private[judgment] (
    val id: String,
    val instructions: String,
    val described: Option[(String, String)]
) extends Question[YesNoAnswer]:

  Question.checkCommon(id, instructions)
  described.foreach { (yes, no) =>
    if yes.trim.isEmpty || no.trim.isEmpty then
      Question.refuse(id, "describing needs both a yes and a no")
  }

  /** Says what yes means and what no means, which a model reads as part of the question. */
  def describing(yes: String, no: String): YesNoQuestion =
    YesNoQuestion(id, instructions, Some(yes -> no))

  def yes: Option[String] = described.map(_._1)
  def no: Option[String]  = described.map(_._2)

  private[judgment] def read(stored: Judgment.Stored): Either[String, YesNoAnswer] =
    stored match
      case Judgment.Stored.YesNo(probability) => Right(YesNoAnswer(probability))
      case other =>
        Left(s"question '$id' is a yes/no, but its answer is a ${Question.kindOf(other)}")

  private[judgment] def problemWith(stored: Judgment.Stored): Option[String] = stored match
    case Judgment.Stored.YesNo(probability) =>
      if Question.inRange(probability) then None
      else Some(s"question '$id': the probability $probability is outside 0 to 1")
    case other =>
      Some(s"question '$id' is a yes/no, but its answer is a ${Question.kindOf(other)}")

  override def toString: String = s"YesNoQuestion($id)"

/**
 * A choice's answer: the option chosen, a probability for every option the question offers, and how
 * confident the model was — a measure of how concentrated the probabilities are, not itself a
 * probability.
 */
final case class ChoiceAnswer[T](choice: T, probabilities: Map[T, Double], confidence: Double)

/**
 * A score's answer: where the state sits on the scale, counted from 0. It may lie between two
 * levels — it is the probability-weighted mean of the level numbers.
 */
final case class ScoreAnswer(score: Double, probabilities: Vector[Double], confidence: Double):
  /** The nearest level. */
  def level: Int = math.round(score).toInt

/**
 * A yes/no's answer: the probability that the answer is yes. There is no separate confidence; the
 * probability is the model's belief.
 */
final case class YesNoAnswer(probability: Double)
