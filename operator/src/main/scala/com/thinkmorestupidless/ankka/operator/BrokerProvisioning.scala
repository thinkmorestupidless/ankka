package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.AnkkaServiceSpec
import com.thinkmorestupidless.ankka.operator.strimzi.StrimziStatus

import java.time.Instant

/**
 * What the platform does about one service's credential on the installation's broker, decided as
 * the database's is: a pure function from what the operator observed to a plan, whose reported
 * phase is a property of the case (feature 027, research R8, R22). A project's topics are decided
 * by `TopicProvisioning`.
 *
 * The plan is the status. What is rendered does not wait on it: a service known to the broker is
 * given its user and the broker's address on every pass, whatever the phase.
 */
enum BrokerPlan:
  /** Nothing to do and nothing to report: web-hosted, or no broker in the installation. */
  case NotNeeded

  /** The descriptor names a broker of its own. */
  case Supplied

  /** The broker's operators have not yet made the user; `detail` says so. */
  case Waiting(detail: Option[String])

  /** The user is ready; `recovered` when it was there before the service's resource was. */
  case Ready(recovered: Boolean)

  /** A problem waiting will not clear. */
  case Failed(problems: Vector[String])

  /** The phase the status reports, `None` for nothing to report. */
  def reportedPhase: Option[String] = this match
    case NotNeeded    => None
    case Supplied     => Some("Supplied")
    case Waiting(_)   => Some("Waiting")
    case Ready(false) => Some("Provisioned")
    case Ready(true)  => Some("Recovered")
    case Failed(_)    => Some("Failed")

/**
 * One topic or user as the operator found it on the broker.
 *
 * @param ready
 *   Strimzi's `Ready` condition: `Some(true)`, `Some(false)` with a reason, or `None` before it has
 *   reported at all
 */
final case class StrimziObjectState(
    exists: Boolean,
    ready: Option[Boolean] = None,
    reason: Option[String] = None,
    message: Option[String] = None,
    createdAt: Option[Instant] = None
)

object StrimziObjectState:
  val absent: StrimziObjectState = StrimziObjectState(exists = false)

  /**
   * An object that exists, as its status describes it. A condition about an earlier generation of
   * the object says nothing about this one: the broker's operator has not yet acted on what was
   * last applied — a topic asked for more partitions, say, still reads `Ready` from before — so it
   * is read as not yet reported, which is waiting.
   */
  def found(
      generation: Option[Long],
      status: Option[StrimziStatus],
      createdAt: Option[Instant]
  ): StrimziObjectState =
    val current = (generation, status.flatMap(_.observedGeneration)) match
      case (Some(applied), Some(observed)) => observed >= applied
      case _                               => true
    val ready = status.filter(_ => current).flatMap(_.ready)
    StrimziObjectState(
      exists = true,
      ready = ready.map(_.status == "True"),
      reason = ready.flatMap(_.reason),
      message = ready.flatMap(_.message),
      createdAt = createdAt
    )

/** A topic as found: its state, and the partitions its resource asks for. */
final case class TopicState(state: StrimziObjectState, partitions: Option[Int] = None)

/** What the operator found on the broker for one service: its user. */
final case class BrokerObservation(
    user: StrimziObjectState = StrimziObjectState.absent,
    resourceCreatedAt: Option[Instant] = None
)

object BrokerObservation:
  val empty: BrokerObservation = BrokerObservation()

object BrokerProvisioning:

  /** Reasons Strimzi gives for a problem that no amount of waiting clears. */
  val PermanentReasons: Set[String] = Set("NotSupported", "InvalidRequest")

  /** Whether the service is known to the broker at all: the rendering's whole condition. */
  def known(spec: AnkkaServiceSpec, broker: Option[BrokerSettings]): Boolean =
    broker.isDefined && spec.hosting != Rendering.WebHosting && spec.provisionBroker

  def decide(
      spec: AnkkaServiceSpec,
      broker: Option[BrokerSettings],
      observed: BrokerObservation
  ): BrokerPlan =
    if spec.hosting == Rendering.WebHosting then BrokerPlan.NotNeeded
    else if !spec.provisionBroker then BrokerPlan.Supplied
    else if broker.isEmpty then BrokerPlan.NotNeeded
    else
      val user = observed.user
      if user.ready.contains(false) && user.reason.exists(PermanentReasons) then
        BrokerPlan.Failed(Vector(s"user: ${user.message.orElse(user.reason).getOrElse("refused")}"))
      else if !user.ready.contains(true) then
        BrokerPlan.Waiting(Some("waiting for the broker to make the user"))
      else BrokerPlan.Ready(recovered(observed))

  /** The user was made before the service's resource was: the same name, applied again. */
  private def recovered(observed: BrokerObservation): Boolean =
    (for
      created <- observed.resourceCreatedAt
      made    <- observed.user.createdAt
    yield made.isBefore(created)).getOrElse(false)
