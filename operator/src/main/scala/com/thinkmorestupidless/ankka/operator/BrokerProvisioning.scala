package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{AnkkaServiceSpec, TopicEntry}

import java.time.Instant

/**
 * What the platform does about one service on the installation's broker, decided as the database's
 * is: a pure function from what the operator observed to a plan, whose reported phase is a property
 * of the case (feature 027, research R8).
 *
 * The plan is the status. What is rendered does not wait on it: a service known to the broker is
 * given its user, its topics and the broker's address on every pass, so a phase that turns `Failed`
 * — a descriptor that asked for fewer partitions — never takes the broker away from a running
 * service. `topicsToRender` is the one thing the observation changes about the rendering.
 */
enum BrokerPlan:
  /** Nothing to do and nothing to report: web-hosted, or no broker and no topic declared. */
  case NotNeeded

  /** The descriptor names a broker of its own. */
  case Supplied

  /** The broker's operators have not yet made everything; `detail` says what is outstanding. */
  case Waiting(detail: Option[String])

  /** The user and every declared topic are ready; `recovered` when they were there before. */
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

/** A topic as found: its state, and the partitions its resource asks for. */
final case class TopicState(state: StrimziObjectState, partitions: Option[Int] = None)

/**
 * What the operator found on the broker for one service: its user, and each topic it declares by
 * the name the broker holds it under.
 */
final case class BrokerObservation(
    user: StrimziObjectState = StrimziObjectState.absent,
    topics: Map[String, TopicState] = Map.empty,
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

  /**
   * The declared topics to render, or `None` when nothing of the broker is rendered. A topic whose
   * resource already asks for more partitions than declared is left out: a topic is never made
   * smaller, and the decision reports it.
   */
  def topicsToRender(
      spec: AnkkaServiceSpec,
      broker: Option[BrokerSettings],
      observed: BrokerObservation
  ): Option[Vector[TopicEntry]] =
    Option.when(known(spec, broker))(
      spec.topics.toVector.filterNot(t => shrinks(spec, t, observed))
    )

  def decide(
      spec: AnkkaServiceSpec,
      broker: Option[BrokerSettings],
      observed: BrokerObservation
  ): BrokerPlan =
    if spec.hosting == Rendering.WebHosting then BrokerPlan.NotNeeded
    else if !spec.provisionBroker then BrokerPlan.Supplied
    else if broker.isEmpty then
      if spec.topics.isEmpty then BrokerPlan.NotNeeded
      else BrokerPlan.Failed(Vector("the installation has no broker"))
    else
      val declared = spec.topics.toVector.map(t => (t, BrokerNames.topic(spec.projectId, t.name)))
      val states   = declared.map((t, name) => (t, name, observed.topics.get(name)))
      val rejected =
        (("user" -> observed.user) +: states.collect { case (_, name, Some(s)) =>
          s"topic '$name'" -> s.state
        }).collect {
          case (what, s) if s.ready.contains(false) && s.reason.exists(PermanentReasons) =>
            s"$what: ${s.message.orElse(s.reason).getOrElse("refused")}"
        }
      val smaller = states.collect {
        case (t, name, Some(s)) if s.partitions.exists(_ > t.partitions) =>
          s"topic '$name' has ${s.partitions.get} partitions and cannot have fewer; " +
            s"${t.partitions} was asked"
      }
      if rejected.nonEmpty || smaller.nonEmpty then BrokerPlan.Failed(smaller ++ rejected)
      else
        val outstanding =
          Option.when(!observed.user.ready.contains(true))("the user").toVector ++
            states.collect {
              case (_, name, s) if !s.exists(_.state.ready.contains(true)) => s"topic '$name'"
            }
        if outstanding.nonEmpty then
          BrokerPlan.Waiting(Some(s"waiting for the broker to make ${outstanding.mkString(", ")}"))
        else BrokerPlan.Ready(recovered(spec, observed))

  private def shrinks(spec: AnkkaServiceSpec, t: TopicEntry, observed: BrokerObservation): Boolean =
    observed.topics
      .get(BrokerNames.topic(spec.projectId, t.name))
      .flatMap(_.partitions)
      .exists(_ > t.partitions)

  /**
   * True when the service declares a topic, and its user and every topic it declares were made
   * before its resource was: the same name, applied again, finding what it had.
   */
  private def recovered(spec: AnkkaServiceSpec, observed: BrokerObservation): Boolean =
    observed.resourceCreatedAt.exists { created =>
      def before(s: StrimziObjectState) = s.createdAt.exists(_.isBefore(created))
      spec.topics.nonEmpty && before(observed.user) &&
      spec.topics.forall(t =>
        observed.topics.get(BrokerNames.topic(spec.projectId, t.name)).exists(s => before(s.state))
      )
    }
