package com.thinkmorestupidless.ankka.agent.judgment

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.{Json, TokenUsage}
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import scala.concurrent.duration.FiniteDuration

/** What the questions are about: text, or a structured value sent as structured data. */
enum JudgmentState:
  case Text(text: String)
  case Structured(value: Json)

/**
 * One judgment to be made: a state, the questions to ask of it, and how long it may take, retries
 * included.
 *
 * Checked on construction, so a request that could never be answered is refused before any provider
 * sees it.
 */
final case class JudgmentRequest(
    state: JudgmentState,
    questions: Vector[Question[?]],
    timeout: FiniteDuration
):
  if questions.isEmpty then throw IllegalArgumentException("a judgment needs at least one question")
  questions.groupBy(_.id).collectFirst {
    case (id, qs) if qs.sizeIs > 1 =>
      throw IllegalArgumentException(s"question '$id' is asked twice in one judgment")
  }: Unit
  if timeout.length <= 0 then throw IllegalArgumentException("a judgment needs a positive timeout")

/**
 * The answers to one judgment, keyed by question id, with the model version that answered and the
 * tokens it spent.
 *
 * Stored by wire names and numbers only, so a judgment can be a reply, cross between services and
 * be kept in state across a deploy that renames Scala identifiers. Read an answer through the
 * question that asked it:
 *
 * {{{
 * val team = judgment(TriageAgent.route)   // ChoiceAnswer[Team]
 * }}}
 */
final case class Judgment(model: String, answers: Map[String, Judgment.Stored], usage: TokenUsage):

  /**
   * The answer to `question`, typed.
   *
   * Throws when the judgment holds no answer to it, holds an answer of another kind, or names an
   * option the question no longer offers. It never returns a default: a question that was not asked
   * has no answer, and inventing one would hide the mistake.
   */
  def apply[A](question: Question[A]): A =
    answers.get(question.id) match
      case None =>
        throw IllegalArgumentException(
          s"question '${question.id}' was not asked in this judgment; it holds " +
            answers.keys.toVector.sorted.map(k => s"'$k'").mkString(", ")
        )
      case Some(stored) =>
        question.read(stored).fold(p => throw IllegalArgumentException(p), identity)

  def contains(question: Question[?]): Boolean = answers.contains(question.id)

object Judgment:

  /** One answer as stored: wire keys and numbers, nothing typed. */
  enum Stored:
    case Choice(key: String, probabilities: Map[String, Double], confidence: Double)
    case Score(score: Double, probabilities: Vector[Double], confidence: Double)
    case YesNo(probability: Double)

  given JsonValueCodec[Judgment] = Codecs.make[Judgment]
  given Serializer[Judgment]     = Codecs.serializer[Judgment]("judgment")

  /**
   * Whether a provider's judgment answers the request: one answer per question asked and no other,
   * each of its question's kind and fitting it. Applied to every provider's answer, so a
   * developer's own provider gets the same protection as the platform's.
   */
  private[ankka] def verify(request: JudgmentRequest, judgment: Judgment): Either[String, Unit] =
    val asked    = request.questions.map(_.id).toSet
    val answered = judgment.answers.keySet
    (asked -- answered).toVector.sorted.headOption match
      case Some(missing) => Left(s"question '$missing' was asked and not answered")
      case None =>
        (answered -- asked).toVector.sorted.headOption match
          case Some(extra) =>
            Left(s"an answer was given for question '$extra', which was not asked")
          case None =>
            request.questions.iterator
              .flatMap(q => q.problemWith(judgment.answers(q.id)))
              .nextOption()
              .toLeft(())
