package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{CloudKinds, CloudResource, CloudResourceStatus}

import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.FiniteDuration

/**
 * What the operator saw of one cloud request (feature 044): the request's generation as the API
 * server counts it, when it was made, and the provider's answer if there is one.
 */
final case class CloudObservation(
    generation: Long,
    createdAt: Instant,
    status: Option[CloudResourceStatus]
):
  /** A provider has read this request as it now is. */
  def acknowledged: Boolean = status.flatMap(_.observedGeneration).exists(_ >= generation)

/** What the operator makes of a cloud request's answer. */
enum CloudPlan:
  /** Not yet answered for this generation, or the provider says it is still working. */
  case Waiting(detail: Option[String])

  /** The provider's answer for this generation. */
  case Ready(outputs: Map[String, String], recovered: Boolean, credentialGeneration: Option[Long])

  /** The provider's refusal, in its own words. */
  case Failed(detail: String)

  /** What to say about it: the provider's words, or the operator's. */
  def said: Option[String] = this match
    case Waiting(d) => d
    case Failed(d)  => Some(d)
    case _: Ready   => None

/**
 * Deciding what a cloud request's answer means, as `Provisioning.decide` does for a database: pure,
 * over a rendered request, an observation and the clock, so `CloudProvisioningSuite` is a table.
 *
 * The one rule that matters most: a status whose `observedGeneration` is behind the request's
 * describes something else (FR-004). It is never acted on, however `Ready` it says it is.
 */
object CloudProvisioning:

  /** What the operator says when nothing has answered for the acknowledgement bound. */
  def unanswered(provider: String): String = s"no provider for $provider has answered"

  def decide(
      request: CloudResource,
      observed: Option[CloudObservation],
      now: Instant,
      bound: FiniteDuration
  ): CloudPlan =
    observed match
      case None => CloudPlan.Waiting(None)
      case Some(seen) =>
        seen.status.flatMap(_.observedGeneration) match
          case None =>
            val waited = JDuration.between(seen.createdAt, now)
            if waited.toMillis >= bound.toMillis then
              CloudPlan.Waiting(Some(unanswered(request.getSpec.provider)))
            else CloudPlan.Waiting(None)
          case Some(observedGeneration) if observedGeneration < seen.generation =>
            CloudPlan.Waiting(
              Some(s"waiting on the provider for generation ${seen.generation}")
            )
          case Some(_) =>
            val status = seen.status.get
            status.phase match
              case CloudKinds.Ready | CloudKinds.Recovered =>
                CloudPlan.Ready(
                  status.outputs,
                  recovered = status.recovered || status.phase == CloudKinds.Recovered,
                  credentialGeneration = status.credentialGeneration
                )
              case CloudKinds.Failed =>
                CloudPlan.Failed(status.detail.getOrElse("the provider gave no reason"))
              case _ => CloudPlan.Waiting(status.detail)
