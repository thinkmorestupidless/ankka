package com.thinkmorestupidless.ankka.agent.javasdk.bridge

import com.thinkmorestupidless.ankka.agent
import com.thinkmorestupidless.ankka.agent.javasdk
import com.thinkmorestupidless.ankka.agent.judgment
import com.thinkmorestupidless.ankka.core
import com.thinkmorestupidless.ankka.javasdk.bridge.Declarations
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer

import java.util.function.{Function as JFunction, Predicate}
import scala.jdk.CollectionConverters.*

/** A Java judgment provider over the Scala one. */
private[ankka] final case class JavaJudgmentProvider(scala: judgment.JudgmentProvider)
    extends javasdk.JudgmentProvider:
  def name(): String = scala.name

private[bridge] final case class JavaChoice[T](scala: judgment.ChoiceQuestion[T])
    extends javasdk.ChoiceQuestion[T]:
  def id(): String           = scala.id
  def instructions(): String = scala.instructions
  def options(): java.util.List[javasdk.ChoiceOption[T]] =
    scala.options.map(o => javasdk.ChoiceOption(o.value, o.key, o.description)).asJava

private[bridge] final case class JavaScore(scala: judgment.ScoreQuestion)
    extends javasdk.ScoreQuestion:
  def id(): String                     = scala.id
  def instructions(): String           = scala.instructions
  def levels(): java.util.List[String] = scala.levels.asJava

private[bridge] final case class JavaYesNo(scala: judgment.YesNoQuestion)
    extends javasdk.YesNoQuestion:
  def id(): String           = scala.id
  def instructions(): String = scala.instructions
  def describing(yes: String, no: String): javasdk.YesNoQuestion =
    JavaYesNo(scala.describing(yes, no))

/** A Java judgment over the Scala one, its answers read through the Java questions. */
private[ankka] final case class JavaJudgment(scala: judgment.Judgment) extends javasdk.Judgment:
  def answer[A](question: javasdk.Question[A]): A =
    Judgments.javaAnswer(question, scala(Judgments.scalaQuestion(question))).asInstanceOf[A]
  def contains(question: javasdk.Question[?]): Boolean =
    scala.contains(Judgments.scalaQuestion(question))
  def model(): String = scala.model

/** A judgment as a Java handler's reply: stored and sent as the Scala SDK's, under its manifest. */
private[bridge] object JudgmentSerializer extends Serializer[javasdk.Judgment]:
  import judgment.Judgment.given
  private val scala = summon[core.Serializer[judgment.Judgment]]

  def manifest(): String = scala.manifest
  def toBytes(value: javasdk.Judgment): Array[Byte] = value match
    case JavaJudgment(j) => scala.toBytes(j)
    case other => throw IllegalArgumentException(s"$other is not a judgment a provider gave")
  def fromBytes(bytes: Array[Byte]): javasdk.Judgment = JavaJudgment(scala.fromBytes(bytes))

private[bridge] final case class JavaRefusal(scala: judgment.Refusal[?]) extends javasdk.Refusal

private[bridge] final case class JavaJudged(scala: judgment.JudgedGuardrail)
    extends javasdk.JudgedGuardrail:
  def name(): String = scala.name
  def onInput(rules: javasdk.Refusal*): javasdk.JudgedGuardrail =
    JavaJudged(scala.onInput(rules.map(Judgments.scalaRefusal)*))
  def onOutput(rules: javasdk.Refusal*): javasdk.JudgedGuardrail =
    JavaJudged(scala.onOutput(rules.map(Judgments.scalaRefusal)*))
  def onResult(rules: javasdk.Refusal*): javasdk.JudgedGuardrail =
    JavaJudged(scala.onResult(rules.map(Judgments.scalaRefusal)*))
  def provider(provider: javasdk.JudgmentProvider): javasdk.JudgedGuardrail =
    JavaJudged(scala.provider(Judgments.scalaProvider(provider)))

/** A judgment described in a Java handler, over the Scala builder. */
private[bridge] final class JavaJudgmentBuilder(
    scala: agent.JudgmentBuilder,
    serializers: Declarations.Serializers
) extends javasdk.JudgmentBuilder:
  private def next(b: agent.JudgmentBuilder) = JavaJudgmentBuilder(b, serializers)

  def state(text: String): javasdk.JudgmentBuilder = next(scala.state(text))
  def state(value: Object): javasdk.JudgmentBuilder =
    next(
      scala.state(value)(using
        Values.codec(value.getClass.asInstanceOf[Class[Object]], serializers)
      )
    )
  override def question(questions: javasdk.Question[?]*): javasdk.JudgmentBuilder =
    questions.map(Judgments.scalaQuestion).toList match
      case first :: more => next(scala.question(first, more*))
      case Nil           => this
  def provider(provider: javasdk.JudgmentProvider): javasdk.JudgmentBuilder =
    next(scala.provider(Judgments.scalaProvider(provider)))

  def thenReply(): javasdk.AgentEffect[javasdk.Judgment] =
    Held(scala.thenReply(j => JavaJudgment(j): javasdk.Judgment))
  def thenReply[T](reply: JFunction[javasdk.Judgment, T]): javasdk.AgentEffect[T] =
    Held(scala.thenReply(j => reply.apply(JavaJudgment(j))))

private[bridge] object Judgments:

  def choice[T](
      id: String,
      instructions: String,
      options: java.util.List[javasdk.ChoiceOption[T]]
  ): javasdk.ChoiceQuestion[T] =
    JavaChoice(
      judgment.Question.choice[T](id, instructions)(
        options.asScala.toSeq.map(o => o.value -> (o.key, o.description))*
      )
    )

  def scalaQuestion(q: javasdk.Question[?]): judgment.Question[?] = q match
    case JavaChoice(s) => s
    case JavaScore(s)  => s
    case JavaYesNo(s)  => s
    case other         => throw IllegalArgumentException(s"$other is not a question Question made")

  /** The Scala answer to a Java question, as the Java answer. */
  def javaAnswer(q: javasdk.Question[?], answer: Any): Any = (q, answer) match
    case (_: JavaChoice[?], a: judgment.ChoiceAnswer[?]) =>
      javasdk.ChoiceAnswer(
        a.choice,
        a.probabilities.map((k, v) => k -> java.lang.Double.valueOf(v)).asJava,
        a.confidence
      )
    case (_: JavaScore, a: judgment.ScoreAnswer) =>
      javasdk.ScoreAnswer(
        a.score,
        a.probabilities.map(java.lang.Double.valueOf).asJava,
        a.confidence
      )
    case (_: JavaYesNo, a: judgment.YesNoAnswer) => javasdk.YesNoAnswer(a.probability)
    case (question, other) =>
      throw IllegalStateException(s"question '${question.id}' was answered with $other")

  def scalaProvider(p: javasdk.JudgmentProvider): judgment.JudgmentProvider = p match
    case JavaJudgmentProvider(scala) => scala
    case other =>
      throw IllegalArgumentException(
        s"${other.name} is not a provider JudgmentProviders made: a judgment provider is the platform's"
      )

  def scalaRefusal(r: javasdk.Refusal): judgment.Refusal[?] = r match
    case JavaRefusal(scala) => scala
    case other              => throw IllegalArgumentException(s"$other is not a rule Refusal made")

  def ifChosen[T](
      q: javasdk.ChoiceQuestion[T],
      minConfidence: Double,
      options: java.util.List[T]
  ): javasdk.Refusal = q match
    case JavaChoice(s) =>
      JavaRefusal(judgment.Refuse.ifChosen(s, minConfidence)(options.asScala.toSeq*))
    case other => throw IllegalArgumentException(s"$other is not a question Question made")

  def when[A](q: javasdk.Question[A], refuse: Predicate[A]): javasdk.Refusal =
    val question = scalaQuestion(q).asInstanceOf[judgment.Question[Any]]
    JavaRefusal(judgment.Refuse.when(question)(a => refuse.test(javaAnswer(q, a).asInstanceOf[A])))
