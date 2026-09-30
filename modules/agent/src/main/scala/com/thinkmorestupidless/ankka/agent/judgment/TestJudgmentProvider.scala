package com.thinkmorestupidless.ankka.agent.judgment

import com.thinkmorestupidless.ankka.agent.TokenUsage

import scala.collection.mutable
import scala.concurrent.Future

/** One question's scripted answer, checked against the question when it was scripted. */
final class ScriptedAnswer private[judgment] (val questionId: String, val stored: Judgment.Stored):
  override def toString: String = s"ScriptedAnswer($questionId, $stored)"

/**
 * Scripted answers, each checked against its question as it is written, so a test cannot script an
 * answer the question could never receive.
 *
 * An answer can be given by its value alone — the option chosen, the score, the probability of yes
 * — and carries probabilities and a confidence consistent with it; or by its full probabilities,
 * from which the value and the confidence follow. Confidence is computed as the provider documents
 * it: `(n × largest − 1) / (n − 1)` over `n` outcomes.
 */
object Answers:

  private val SumTolerance = 1e-3

  /** The option chosen; `confidence` of 1 puts all the probability on it. */
  def choice[T](question: ChoiceQuestion[T], choice: T, confidence: Double = 1.0): ScriptedAnswer =
    val chosen = question
      .optionFor(choice)
      .getOrElse(Question.refuse(question.id, s"$choice is not one of its options"))
    if !Question.inRange(confidence) then
      Question.refuse(question.id, s"a confidence of $confidence is outside 0 to 1")
    val n    = question.options.size
    val top  = (confidence * (n - 1) + 1) / n
    val rest = (1 - top) / (n - 1)
    val probabilities =
      question.options.map(o => o.key -> (if o.key == chosen.key then top else rest)).toMap
    ScriptedAnswer(question.id, Judgment.Stored.Choice(chosen.key, probabilities, confidence))

  /** Every option's probability; the choice is the most probable. */
  def choiceWith[T](question: ChoiceQuestion[T], probabilities: Map[T, Double]): ScriptedAnswer =
    probabilities.keys.find(v => question.optionFor(v).isEmpty).foreach { v =>
      Question.refuse(question.id, s"$v is not one of its options")
    }
    question.options.find(o => !probabilities.contains(o.value)).foreach { o =>
      Question.refuse(question.id, s"no probability is given for '${o.key}'")
    }
    val byKey = question.options.map(o => o.key -> probabilities(o.value)).toMap
    checkDistribution(question.id, byKey.values)
    val (key, top) = byKey.maxBy(_._2)
    ScriptedAnswer(
      question.id,
      Judgment.Stored.Choice(key, byKey, confidenceOf(top, question.options.size))
    )

  /** Where on the scale, from 0 to the top level; split between the two levels either side. */
  def score(question: ScoreQuestion, score: Double): ScriptedAnswer =
    if score < 0 || score > question.top then
      Question.refuse(question.id, s"a score of $score is off the scale 0 to ${question.top}")
    val lo = math.floor(score).toInt
    val hi = math.ceil(score).toInt
    val probabilities = Vector.tabulate(question.levels.size) { i =>
      if lo == hi then if i == lo then 1.0 else 0.0
      else if i == lo then hi - score
      else if i == hi then score - lo
      else 0.0
    }
    ScriptedAnswer(
      question.id,
      Judgment.Stored
        .Score(score, probabilities, confidenceOf(probabilities.max, probabilities.size))
    )

  /** Every level's probability, in order; the score is their weighted mean. */
  def scoreWith(question: ScoreQuestion, probabilities: Vector[Double]): ScriptedAnswer =
    if probabilities.sizeIs != question.levels.size then
      Question.refuse(
        question.id,
        s"${probabilities.size} probabilities were given for ${question.levels.size} levels"
      )
    checkDistribution(question.id, probabilities)
    val score = probabilities.zipWithIndex.map((p, i) => p * i).sum
    ScriptedAnswer(
      question.id,
      Judgment.Stored
        .Score(score, probabilities, confidenceOf(probabilities.max, probabilities.size))
    )

  /** The probability that the answer is yes. */
  def yesNo(question: YesNoQuestion, probability: Double): ScriptedAnswer =
    if !Question.inRange(probability) then
      Question.refuse(question.id, s"a probability of $probability is outside 0 to 1")
    ScriptedAnswer(question.id, Judgment.Stored.YesNo(probability))

  private def checkDistribution(id: String, values: Iterable[Double]): Unit =
    if !values.forall(Question.inRange) then Question.refuse(id, "a probability is outside 0 to 1")
    if math.abs(values.sum - 1.0) > SumTolerance then
      Question.refuse(id, s"the probabilities sum to ${values.sum}, not 1")

  private def confidenceOf(top: Double, n: Int): Double =
    math.min(1.0, math.max(0.0, (n * top - 1) / (n - 1)))

/**
 * A judgment provider that answers from a script.
 *
 * Tests of judgments should be deterministic and free, like tests of agents. Answers come from two
 * places: a queue, one judgment per entry, taken in order; and standing answers, by question, given
 * every time the question is asked without touching the queue — which is what a guardrail asked on
 * every request needs.
 *
 * It fails loudly: a question with no answer, an answer for a question that was not asked, and an
 * empty queue when one is needed each throw [[JudgmentScriptFailed]] naming the questions. A test
 * whose provider quietly answered something is no longer testing what it says.
 *
 * {{{
 * val judge = TestJudgmentProvider()
 *   .always(Answers.yesNo(Safety.overridesInstructions, 0.02))
 *   .expect(Answers.choice(TriageAgent.route, Team.Billing), Answers.yesNo(TriageAgent.refund, 0.97))
 * }}}
 */
final class TestJudgmentProvider(val modelName: String = "test-judge") extends JudgmentProvider:

  def name: String = "test"

  private val queue    = mutable.Queue.empty[Vector[ScriptedAnswer]]
  private val standing = mutable.Map.empty[String, ScriptedAnswer]
  private val failures = mutable.Queue.empty[JudgmentFailed]
  private val seen     = mutable.ArrayBuffer.empty[JudgmentRequest]
  private var usage    = TokenUsage.zero

  /** Queues one judgment's answers. */
  def expect(answers: ScriptedAnswer*): TestJudgmentProvider = synchronized {
    if answers.isEmpty then throw IllegalArgumentException("expect needs at least one answer")
    answers.groupBy(_.questionId).collectFirst {
      case (id, as) if as.sizeIs > 1 =>
        throw IllegalArgumentException(s"question '$id' is answered twice in one judgment")
    }: Unit
    queue.enqueue(answers.toVector)
    this
  }

  /** Answers these questions whenever they are asked, without consuming the queue. */
  def always(answers: ScriptedAnswer*): TestJudgmentProvider = synchronized {
    answers.foreach(a => standing(a.questionId) = a)
    this
  }

  /**
   * Queues one failure: a later judgment fails as a provider fails. Failures are taken in order,
   * before any answer is looked up, so three calls fail the next three judgments.
   */
  def failNext(message: String, timedOut: Boolean = false): TestJudgmentProvider = synchronized {
    failures.enqueue(JudgmentFailed(name, message, timedOut = timedOut))
    this
  }

  /** The tokens every judgment reports. Zero unless set. */
  def reporting(usage: TokenUsage): TestJudgmentProvider = synchronized {
    this.usage = usage
    this
  }

  def requests: Vector[JudgmentRequest] = synchronized(seen.toVector)

  def lastRequest: JudgmentRequest = synchronized {
    seen.lastOption.getOrElse(throw AssertionError("no judgment has been asked for"))
  }

  def callCount: Int = synchronized(seen.size)

  /** Clears everything scripted and recorded, and puts the reported usage back to zero. */
  def reset(): Unit = synchronized {
    queue.clear()
    standing.clear()
    failures.clear()
    seen.clear()
    usage = TokenUsage.zero
  }

  def judge(request: JudgmentRequest): Future[Judgment] = synchronized {
    seen += request
    if failures.nonEmpty then Future.failed(failures.dequeue())
    else
      val asked                = request.questions.map(_.id)
      val (fromStanding, rest) = asked.partition(standing.contains)
      val answered             = fromStanding.map(id => id -> standing(id).stored)
      if rest.isEmpty then Future.successful(Judgment(modelName, answered.toMap, usage))
      else if queue.isEmpty then
        Future.failed(
          JudgmentScriptFailed(
            s"the judgment script has run out: no answer for ${quoted(rest)}"
          )
        )
      else
        val next     = queue.dequeue()
        val scripted = next.map(_.questionId).toSet
        val missing  = rest.filterNot(scripted)
        val extra    = next.map(_.questionId).filterNot(rest.contains)
        if missing.nonEmpty then
          Future.failed(
            JudgmentScriptFailed(s"the scripted judgment has no answer for ${quoted(missing)}")
          )
        else if extra.nonEmpty then
          Future.failed(
            JudgmentScriptFailed(
              s"the scripted judgment answers ${quoted(extra)}, which the request did not ask " +
                s"(it asked ${quoted(asked)})"
            )
          )
        else
          Future.successful(
            Judgment(modelName, (answered ++ next.map(a => a.questionId -> a.stored)).toMap, usage)
          )
  }

  private def quoted(ids: Iterable[String]): String = ids.map(id => s"'$id'").mkString(", ")
