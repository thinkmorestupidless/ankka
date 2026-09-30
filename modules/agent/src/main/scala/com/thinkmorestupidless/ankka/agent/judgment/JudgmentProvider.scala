package com.thinkmorestupidless.ankka.agent.judgment

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

/**
 * A model ankka can ask for judgments: typed questions about a state, answered with probabilities
 * rather than text.
 *
 * Kept apart from `ModelProvider` on purpose. A text model takes a conversation and tools and
 * returns prose and tool calls; a judgment model takes and returns neither, so behind the text seam
 * the agent loop would ask it for a turn and receive nothing. One method, and ankka's own types, so
 * adding a provider is one adapter.
 */
trait JudgmentProvider:

  /** Identifies the provider in logs and errors. */
  def name: String

  /** The model this provider is configured to ask. */
  def modelName: String

  /**
   * Answers every question in the request, keyed by id, or fails with [[JudgmentFailed]].
   *
   * A provider should give up by `request.timeout`; the platform stops waiting then whether it has
   * or not. The platform checks every answer against the request, so a provider need not.
   */
  def judge(request: JudgmentRequest): Future[Judgment]

/** A judgment that failed at the transport or the provider. */
final case class JudgmentFailed(
    provider: String,
    message: String,
    cause: Option[Throwable] = None,
    timedOut: Boolean = false
) extends RuntimeException(s"$provider: $message", cause.orNull)

/**
 * A scripted judgment that could not be answered from its script.
 *
 * Deliberately not a [[JudgmentFailed]]: a test's unanswered question must fail the test now, not
 * take the path a provider's outage takes, where it would be retried.
 */
final class JudgmentScriptFailed(message: String) extends RuntimeException(message)

/** No provider was named and none is configured. */
private[ankka] final class NoJudgmentProvider
    extends RuntimeException("no judgment provider is configured")

/**
 * The service's judgments: its default provider, if any, and how long a judgment may take.
 *
 * `ask` is the one place the platform calls a provider, so every judgment — an effect's, a
 * guardrail's — is resolved, bounded and checked the same way.
 */
private[ankka] final case class Judgments(
    default: Option[JudgmentProvider],
    timeout: FiniteDuration
):

  def ask(
      provider: Option[JudgmentProvider],
      state: JudgmentState,
      questions: Vector[Question[?]]
  ): Judgment =
    val request = JudgmentRequest(state, questions, timeout)
    val chosen  = provider.orElse(default).getOrElse(throw NoJudgmentProvider())
    val judgment =
      try Await.result(chosen.judge(request), timeout)
      catch
        case _: java.util.concurrent.TimeoutException =>
          throw JudgmentFailed(chosen.name, s"did not answer within $timeout", timedOut = true)
        case failure: (JudgmentFailed | JudgmentScriptFailed | InterruptedException) =>
          throw failure
        case NonFatal(failure) =>
          throw JudgmentFailed(
            chosen.name,
            Option(failure.getMessage).getOrElse(failure.toString),
            Some(failure)
          )
    Judgment.verify(request, judgment) match
      case Left(problem) => throw JudgmentFailed(chosen.name, problem)
      case Right(())     => judgment

private[ankka] object Judgments:
  val DefaultTimeout: FiniteDuration = 5.seconds
  val none: Judgments                = Judgments(None, DefaultTimeout)
